package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Pragon's brain for Standalone mode: one entry point used by both the chat screen and the float bubble.
 *
 *  ASSISTANT  only talks. Phone commands get a polite "I'm in Assistant mode".
 *  MASTER     (default) talks AND acts, and says what it did: "open youtube" opens it, then "YouTube opened, sir."
 *  AGENT      acts with as little talk as possible: silent on success, speaks only failures and answers.
 *
 * Plain phone commands are recognised locally and run instantly (no AI round trip, works offline, no API key).
 * Everything else goes to the chosen AI engine with Pragon's persona and streams back sentence by sentence.
 */
object Brain {

    /** What the UI should do with the answer. */
    class Reply(
        val text: String,
        /** Speak it? (false = show only, e.g. Agent mode after a successful action) */
        val speak: Boolean,
        /** True when its sentences were already delivered through [Sink.onSentence], so don't speak [text] again. */
        val streamed: Boolean,
        val acted: Boolean = false,
    )

    interface Sink {
        /** Text so far of the reply (for the chat bubble). */
        fun onPartial(textSoFar: String) {}
        /** A finished sentence, ready to be spoken. Only called in Assistant and Master mode. */
        fun onSentence(sentence: String) {}
    }

    private val lock = Any()
    /** Actions that only answer a question (they don't control the phone), so Assistant mode may run them. */
    private val INFO_ONLY = setOf("airpods", "features", "llm", "info", "note", "fun", "stt", "pc_list", "calc", "screen_ask", "screen_read", "privacy", "allowlist", "macro_list", "help", "metrics")
    private val ANSWERS = setOf("pc_toggle", "airpods", "stt", "features", "llm", "info", "note", "fun", "audio_route", "headset_mic", "pc_add", "pc_remove", "pc_list", "pa", "calc", "calc_screen", "stopwatch", "ollama_list", "battery_alert", "status", "typer", "screen_ask", "screen_read", "scan", "privacy", "allowlist", "firewall", "macro_list", "macro_record", "macro_stop", "macro_run", "help", "metrics", "offline")

    /** Times the whole command (rules + model + action + reply text) so "how fast are you" has real numbers. */
    fun respond(
        ctx: Context, input: String, files: List<Attachment> = emptyList(), viaVoice: Boolean = false, sink: Sink? = null,
    ): Reply {
        val t0 = Metrics.now()
        val r = respondInner(ctx, input, files, viaVoice, sink)
        Metrics.add("total", Metrics.now() - t0)
        return r
    }

    private fun respondInner(
        ctx: Context, input: String, files: List<Attachment> = emptyList(), viaVoice: Boolean = false, sink: Sink? = null,
    ): Reply = synchronized(lock) {
        val raw = input.trim()
        val call = Prefs.callMe(ctx)
        val mode = Prefs.mode(ctx)

        // ---- typer mode: everything you say is typed into the focused text box ----
        if (viaVoice && Typer.active && files.isEmpty()) return typerStep(ctx, raw, mode)

        // ---- local commands (work with no AI at all) ----
        if (VoiceParser.isSelfClose(raw)) {
            return Reply("I can't close myself from here, $call. In float mode, say goodbye.", true, false)
        }
        val c = VoiceParser.cleanCommand(raw)
        VoiceParser.modeSwitch(c)?.let { m ->
            Prefs.saveMode(ctx, m)
            val t = Narrator.modeChanged(ctx, m)
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        VoiceParser.voiceChange(c)?.let { v ->
            // Works in every mode. reload() is queued before the speech below, so the reply is in the NEW voice.
            val t = Speaker.get(ctx).changeVoice(reset = v == "reset")
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        // v10: ONE routing layer (Router.kt): phrase rules first, then the on-device model, then conversation.
        val tRules = Metrics.now()
        var act = if (files.isEmpty()) Router.rules(raw, c) else null
        Metrics.add("rules", Metrics.now() - tRules)
        if (act == null && files.isEmpty()) act = Router.model(ctx, raw)
        if (act != null) {
            if (mode == PMode.ASSISTANT && act.action !in INFO_ONLY) {
                val t = Narrator.assistantRefusal(ctx)
                Conversation.note(raw, t)
                return Reply(t, true, false)
            }
            val tAct = Metrics.now()
            val res = CommandExecutor.run(
                ctx, JSONObject().put("action", act.action).put("value", act.value), fromStandalone = true, user = true
            )
            Metrics.add("act", Metrics.now() - tAct)
            // "show me the weather" is conversation, not an app called "weather": let the AI answer it
            val notAnApp = act.soft && act.action == "open_app" && !res.ok && res.msg.startsWith("I couldn't find an app")
            if (!notAnApp) {
                val t = Narrator.confirm(ctx, act, res)
                Conversation.note(raw, t)
                // Answers (a sum, a stopwatch reading, the model list) are always spoken, even in Agent mode.
                val isAnswer = act.action in ANSWERS
                return Reply(t, speak = mode == PMode.MASTER || !res.ok || isAnswer, streamed = false, acted = true)
            }
        }

        // ---- strict offline: nothing matched, so this is conversation. Only the on-device chat model may answer. ----
        if (Prefs.engine(ctx) == "local") return localChat(ctx, raw, files, mode, sink)

        // ---- the AI ----
        Guard.engineProblem(ctx)?.let { return Reply(it, true, false) }
        Conversation.checkEngine(ctx)
        val streaming = mode != PMode.AGENT          // Agent mode never narrates while it works
        var spoken = 0
        val acc = StringBuilder()
        val chatSink = object : ChatSink {
            override fun onText(delta: String) { acc.append(delta); sink?.onPartial(acc.toString()) }
            override fun onSentence(sentence: String) {
                if (streaming && sink != null) { spoken++; sink.onSentence(sentence) }
            }
        }
        // v11: LlmHub asks the cloud first and lets the phone's own chat model take over only if the cloud is slow or fails.
        val r = try {
            LlmHub.cloudChat(ctx, raw.ifEmpty { "Please look at the attached file." }, files, mode, viaVoice, chatSink)
        } catch (e: Exception) {
            ChatResult("Something went wrong: ${e.message}", emptyList(), true)
        }

        val acted = r.actions.any { it.acted }
        val allOk = r.actions.all { it.ok }
        var text = r.text
        var streamed = spoken > 0 && text.isNotBlank()
        if (text.isBlank() && r.actions.isNotEmpty()) {
            // The model did the action but said nothing: report the real result ourselves.
            val last = r.actions.last()
            text = if (last.ok) "Done, $call." else last.msg
            streamed = false
        }
        if (text.isBlank()) text = "Done, $call."
        if (r.error) streamed = false
        val speak = when (mode) {
            PMode.AGENT -> r.error || !acted || !allOk
            else -> true
        }
        Reply(text, speak, streamed && speak, acted)
    }

    /** v10: offline conversation. Uses the optional chat model (chat.litertlm); otherwise says plainly what it can't do. */
    private fun localChat(ctx: Context, raw: String, files: List<Attachment>, mode: PMode, sink: Sink?): Reply {
        val call = Prefs.callMe(ctx)
        if (files.isNotEmpty()) return Reply("I can't look at attachments while I'm offline, $call.", true, false)
        if (Offline.chatFile(ctx) == null) {
            val msg = if (ToolCatalog.looksLikeCommand(raw) && Offline.routerFile(ctx) == null)
                "I didn't understand that one, $call. Say download the model, and I'll understand many more ways of asking."
            else if (ToolCatalog.looksLikeCommand(raw))
                "I couldn't turn that into a phone action, $call. Try saying it a little differently."
            else
                "I'm running fully offline, $call, and I have no chat model installed, so I only do phone commands. Say what can you do for the list."
            return Reply(msg, true, false)
        }
        val text = Metrics.time("chat") { OnDeviceLlm.chat(ctx, raw, if (mode == PMode.AGENT) 128 else if (sink != null) 200 else 400) }
            ?: return Reply("My offline chat model didn't answer, $call. ${OnDeviceLlm.chatState}", true, false)
        Conversation.note(raw, text)
        val speak = mode != PMode.AGENT
        if (speak && sink != null) {
            sink.onPartial(text)
            text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { sink.onSentence(it) }
            return Reply(text, speak = true, streamed = true, acted = false)
        }
        return Reply(text, speak, false, false)
    }

    /** One heard sentence while typer mode is on. Silent: it only speaks to confirm start/stop or to report a problem. */
    private fun typerStep(ctx: Context, raw: String, mode: PMode): Reply {
        val call = Prefs.callMe(ctx)
        val svc = PragonAccessibilityService.instance
        if (svc == null) {
            Typer.stop()
            return Reply("The accessibility service turned off, $call, so typer mode stopped.", true, false)
        }
        Typer.touch()
        fun quiet(msg: String) = Reply(msg, speak = false, streamed = false, acted = true)
        return when (val st = Typer.interpret(raw)) {
            Typer.Step.Stop -> { Typer.stop(); Reply("Typer mode off, $call.", true, false, true) }
            Typer.Step.Nothing -> quiet("")
            is Typer.Step.Text -> if (svc.dictate(st.piece)) quiet("Typed: " + st.piece)
                else Reply("I can't see a text box on this screen, $call. Open one (or tap it) and keep talking.", true, false)
            Typer.Step.DeleteLast -> if (svc.deleteLastDictation()) quiet("Deleted that.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.DeleteWord -> if (svc.deleteLastWord()) quiet("Deleted the last word.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.Backspace -> if (svc.backspace()) quiet("Backspace.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.ClearAll -> if (svc.clearField()) quiet("Cleared the box.") else Reply("There's no text box to clear.", true, false)
            Typer.Step.SelectAll -> if (svc.selectAll()) quiet("Selected all.") else Reply("There's no text box to select in.", true, false)
            Typer.Step.Enter -> if (svc.pressEnter()) quiet("Sent.") else Reply("I couldn't find a send button, $call.", true, false)
        }
    }

    /** Loads the model and primes its cache in the background (Ollama / custom server). */
    fun warmup(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try { CommandExecutor.prewarm(app) } catch (e: Exception) { }                  // app list cached: "open youtube" no longer scans every app
            try { if (Offline.active(app)) OnDeviceLlm.warmup(app) else LocalLlmClient.warmup(app) } catch (e: Exception) { }
            try { SherpaStt.warmup(app) } catch (e: Exception) { }
        }.start()
    }
}
