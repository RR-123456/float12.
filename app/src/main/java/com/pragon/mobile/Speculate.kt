package com.pragon.mobile

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * v12: speculative command routing.
 *
 * While you are still talking, sherpa-onnx streams partial text. When the partial has stopped changing for a moment, FloatService
 * calls [onStable]. If the phrase rules do not understand it and it looks like a command, the on-phone router model starts on it right
 * away, on its own thread. When the final text arrives and is the same sentence, Router.model takes the answer that is already done
 * (or already half done) instead of starting from zero. Different final text: the guess is thrown away, nothing else changes.
 *
 * Cost control: one guess at a time, only offline (that is when the router model is used at all), only when a router model exists,
 * never when the phrase rules already answer (the common case: no model runs at all).
 */
object Speculate {

    private class Guess(val key: String, val future: Future<VoiceParser.Action?>)

    private val pool: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pragon-spec").also { it.isDaemon = true } }
    @Volatile private var guess: Guess? = null

    /** Same sentence, ignoring case, punctuation and spacing, so "Set a timer." and "set a timer" match. */
    fun key(text: String): String = SherpaFiles.sentenceKey(text)

    /** Words needed before a guess is worth a model run. */
    const val MIN_WORDS = 3

    fun onStable(ctx: Context, partial: String) {
        val k = key(partial)
        if (k.split(' ').size < MIN_WORDS) return
        val g = guess
        if (g != null && (g.key == k || !g.future.isDone)) return          // same guess, or one still running
        if (!Offline.active(ctx) || Offline.routerFile(ctx) == null) return
        if (!ToolCatalog.looksLikeCommand(partial)) return
        if (Router.rules(partial, VoiceParser.cleanCommand(partial)) != null) return   // rules answer it: no model needed
        val app = ctx.applicationContext
        guess = Guess(k, pool.submit<VoiceParser.Action?> { Router.modelNow(app, partial) })
    }

    /** What a guess came to. [action] null with a Taken means the router ran and chose no tool. */
    class Taken(val action: VoiceParser.Action?)

    /** The guess for exactly this sentence, waited for up to [waitMs]. null = no usable guess (none, different sentence, timed out, failed). */
    fun take(text: String, waitMs: Long = 3000L): Taken? {
        val g = guess ?: return null
        if (g.key != key(text)) return null
        guess = null
        return try {
            val a = g.future.get(waitMs, TimeUnit.MILLISECONDS)
            Metrics.add("spec_hit", 1)
            Taken(a)
        } catch (e: Exception) { null }
    }

    fun has(text: String): Boolean = guess?.key == key(text)

    fun clear() { guess = null }
}
