package com.pragon.mobile

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v11: several language models side by side without wasting the phone.
 *
 * LANES, each with its own engine and its own lock (see OnDeviceLlm), so one never waits for another's load:
 *   ROUTER  FunctionGemma 270M on the phone: turns loose wording into one phone action
 *   CHAT    optional chat model on the phone (chat.litertlm)
 *   CLOUD   Gemini or your Ghost / custom server (online only)
 *
 * HOW IT STAYS CHEAP AND FAST
 *  - Phrase rules answer first: no model runs at all for ordinary commands.
 *  - Models load in PARALLEL at start-up (router and chat on separate threads) when the phone has the RAM and cores, so the first
 *    reply is not slow; otherwise only the router is preloaded.
 *  - Online, the cloud answers. The phone's chat model is started only if the cloud is slow (HEDGE_MS) or fails, and whichever
 *    delivers first wins (the other is dropped). On a fast connection the phone's model never uses the CPU.
 *  - A model idle for 5 minutes is released; when RAM is short the idle one is released before another loads.
 *  - One small shared thread pool (3 threads); never one thread per request.
 */
object LlmHub {

    private const val HEDGE_MS = 2500L
    const val MIN_FREE_MB = 1500L
    private const val MIN_CORES = 6

    private val pool: ExecutorService = Executors.newFixedThreadPool(3) { r -> Thread(r, "pragon-llm").also { it.isDaemon = true } }

    fun freeRamMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / 1_048_576L
    }

    /** True when the phone can afford two models at once: setting on, 6+ cores, 6+ GB RAM, 1.5 GB free right now. */
    fun canRunParallel(c: Context): Boolean =
        Prefs.parallelLlm(c) && Runtime.getRuntime().availableProcessors() >= MIN_CORES &&
            Offline.ramGb(c) >= 6.0 && freeRamMb(c) >= MIN_FREE_MB

    /**
     * Online chat: cloud first, the phone's own model only when the cloud is slow or fails.
     * Falls back to a plain cloud call when there is no chat model or the phone can't spare the memory.
     */
    fun cloudChat(
        ctx: Context, text: String, files: List<Attachment>, mode: PMode, viaVoice: Boolean, sink: ChatSink,
    ): ChatResult {
        val gemini = Prefs.engine(ctx) == "gemini"
        val maxGemini = if (viaVoice) 1024 else 2048
        val maxLocal = if (viaVoice) 400 else 900
        fun cloud(s: ChatSink): ChatResult =
            if (gemini) GeminiClient.chat(ctx, text, files, mode, maxGemini, s) else LocalLlmClient.chat(ctx, text, files, mode, maxLocal, s)
        fun failed(e: Exception) = ChatResult("Something went wrong: ${e.message}", emptyList(), true)

        val canHedge = files.isEmpty() && Offline.chatFile(ctx) != null && canRunParallel(ctx)
        if (!canHedge) return try { cloud(sink) } catch (e: Exception) { failed(e) }

        val winner = AtomicInteger(0)                 // 0 nobody yet, 1 cloud, 2 phone
        val firstText = CountDownLatch(1)
        val guarded = object : ChatSink {
            override fun onText(delta: String) {
                winner.compareAndSet(0, 1)
                if (winner.get() == 1) { firstText.countDown(); sink.onText(delta) }
            }
            override fun onSentence(sentence: String) { if (winner.get() == 1) sink.onSentence(sentence) }
        }
        val cloudF: Future<ChatResult> = pool.submit<ChatResult> { try { cloud(guarded) } catch (e: Exception) { failed(e) } }
        val cap = if (viaVoice) 200 else 400
        var localF: Future<String?>? = null
        fun startLocal() { if (localF == null) localF = pool.submit<String?> { OnDeviceLlm.chat(ctx, text, cap) } }
        val t0 = System.currentTimeMillis()
        try { firstText.await(HEDGE_MS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { }

        while (true) {
            if (cloudF.isDone) {
                val r = try { cloudF.get() } catch (e: Exception) { failed(e) }
                if (!r.error || winner.get() == 1) { localF?.cancel(true); return r }
                // The cloud failed before saying anything: the phone's own model answers instead.
                startLocal()
                val t = try { localF!!.get() } catch (e: Exception) { null }
                return if (!t.isNullOrBlank()) ChatResult(t, emptyList(), false) else r
            }
            if (localF == null && winner.get() == 0 && System.currentTimeMillis() - t0 >= HEDGE_MS) startLocal()
            val lf = localF
            if (lf != null && lf.isDone && winner.get() == 0) {
                val t = try { lf.get() } catch (e: Exception) { null }
                if (!t.isNullOrBlank() && winner.compareAndSet(0, 2)) { cloudF.cancel(true); return ChatResult(t, emptyList(), false) }
            }
            try { Thread.sleep(25) } catch (e: InterruptedException) { return ChatResult("Interrupted.", emptyList(), true) }
        }
    }

    /** One paragraph for "model status". */
    fun status(c: Context): String {
        val call = Prefs.callMe(c)
        val sb = StringBuilder()
        sb.append("Command model: ").append(if (Offline.routerFile(c) == null) "not installed" else OnDeviceLlm.routerState).append(". ")
        sb.append("Chat model: ").append(if (Offline.chatFile(c) == null) "not installed" else OnDeviceLlm.chatState + " on " + OnDeviceLlm.chatBackend).append(". ")
        sb.append("Speech model: ").append(if (!SherpaStt.hasModel(c)) "not installed" else SherpaStt.state).append(". ")
        sb.append("Cloud: ").append(if (Offline.active(c)) "off while offline" else Prefs.engineLabel(c).lowercase()).append(". ")
        sb.append("Parallel models are ").append(
            if (!Prefs.parallelLlm(c)) "switched off"
            else if (canRunParallel(c)) "on, and this phone has room for them"
            else "on, but the phone is short of memory or cores right now, so they take turns"
        ).append(", $call. ")
        sb.append("Free memory ${freeRamMb(c)} megabytes.")
        return sb.toString()
    }
}
