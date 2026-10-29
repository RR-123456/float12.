package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PRAGON LIVE: the PC's live Gemini native-audio session (pragon_main.py: LIVE_MODEL + _build_config + _listen_audio +
 * _receive_audio + _play_audio), running on the phone.
 *
 *   microphone (16 kHz PCM) ──► Gemini Live WebSocket ──► voice (24 kHz PCM, "Charon") ──► speaker
 *                                      │
 *                                      └─ tool calls: phone_control / remember / forget / set_mode / change_voice / shutdown_pragon
 *
 * Same behaviour as the PC session:
 *  - system instruction = date/time + saved memory + the PC protocol + address + mode + language (Persona.liveSystem)
 *  - voice "Charon" (Prefs.liveVoice), audio in/out transcription, session resumption, sliding-window context compression
 *  - affective dialog + proactive audio on v1alpha, dropped automatically (v1beta) if the server rejects them
 *  - the user can interrupt: the server sends "interrupted" and playback is flushed
 *  - reconnects by itself (goAway, dropped network) and keeps the conversation through the resumption handle
 *
 * Privacy Shield: the socket goes through Guard.ShieldInterceptor like every other connection Pragon makes, and start()
 * refuses up front when the Shield blocks Google.
 *
 * Threading: every Listener callback comes from a background thread, post to the main thread yourself.
 * Not compiled or run in the build sandbox (no Android SDK there): see CHANGES_v7.md for the on-device checklist.
 */
class LiveSession(private val app: Context, private val ui: Listener) {

    interface Listener {
        fun onState(state: State)
        /** What the user said in the turn that just finished (Gemini's transcript). */
        fun onUserText(text: String)
        /** What Pragon said in the turn that just finished. */
        fun onPragonText(text: String)
        /** The session is over and will not reconnect (bad key, Shield, no mic, too many failures). Message is user-facing. */
        fun onError(message: String)
        /** The model called shutdown_pragon and finished saying goodbye. */
        fun onShutdownRequested()
        /** The model called mute_pragon: stop listening until the user taps the bubble. */
        fun onMuteRequested()
    }

    enum class State { CONNECTING, LISTENING, SPEAKING }

    companion object {
        const val HOST = "generativelanguage.googleapis.com"
        const val SEND_RATE = 16000       // PC: SEND_SAMPLE_RATE
        const val RECV_RATE = 24000       // PC: RECEIVE_SAMPLE_RATE
        const val MIC_CHUNK_BYTES = 2048  // 1024 samples, same as the PC's CHUNK_SIZE
        const val OUT_SLICE_BYTES = 2400  // 50 ms of 24 kHz 16-bit mono: interrupting stops audio within ~50 ms (PC: _SLICE)

        /** The PC's GEMINI_VOICES. */
        val VOICES: List<String> = listOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
            "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
            "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
        )

        fun voiceName(raw: String): String? = VOICES.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }

        private val CTRL_TAGS = Regex("<ctrl\\d+>")
    }

    // ---- connection state ----
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Guard.ShieldInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)            // a live socket is quiet between turns
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val sched = Executors.newSingleThreadScheduledExecutor()   // reconnects, drain checks
    private val toolsEx = Executors.newSingleThreadExecutor()          // tool calls can take seconds (opening an app)
    private val lock = Any()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var generation = 0          // bumped on every (re)connect/drop so late callbacks of an old socket are ignored
    @Volatile private var wantRunning = false
    @Volatile private var ready = false           // setupComplete received on the current socket
    @Volatile private var dropAudio = false       // user tapped "stop talking": ignore the rest of this reply
    @Volatile private var speakingNow = false
    @Volatile private var spokeSinceTool = false
    @Volatile private var afterTurn: (() -> Unit)? = null
    @Volatile private var mode: PMode = Prefs.mode(app)

    private var resumeHandle: String? = null
    private var enhanced = Prefs.liveEnhanced(app)                   // affective dialog + proactive audio (v1alpha)
    private var everReady = false
    private var failures = 0
    private var greeting: String? = null

    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()   // "one request, one tool execution" (Tools.run)
    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()

    private var player: Player? = null
    private var micThread: Thread? = null
    @Volatile private var micRun = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    val running: Boolean get() = wantRunning

    /** Pragon PA: when set, this session answers a phone call (own prompt + own tools) instead of being the normal assistant. */
    @Volatile var callPrompt: String? = null
    @Volatile var onCallTool: ((String, JSONObject) -> String)? = null

    private fun reroute() { if (wantRunning) { val d = AudioRoute.apply(app); player?.prefer(d) } }

    // ───────────────────────────── public API ─────────────────────────────

    /** [greeting] is an internal instruction spoken once the session is ready (the PC's [STARTUP_BRIEFING]). */
    fun start(greeting: String? = null) {
        if (wantRunning) return
        if (Prefs.effectiveAiKey(app).isBlank()) {
            ui.onError("I need a Pragon AI key for the live voice. Add yours in Settings."); return
        }
        Guard.checkHost(app, HOST)?.let { ui.onError(it); return }
        this.greeting = greeting
        wantRunning = true
        try {
            player = Player(AudioRoute.apply(app)); AudioRoute.onChange = { reroute() }
        } catch (e: Exception) {
            wantRunning = false
            ui.onError("I couldn't open the audio output: " + (e.message ?: e.javaClass.simpleName)); return
        }
        startMic()
        connect()
    }

    fun stop() {
        if (!wantRunning && player == null) return
        wantRunning = false
        micRun = false
        synchronized(lock) { generation++; ready = false }
        try { ws?.close(1000, "bye") } catch (e: Exception) { }
        ws = null
        player?.release(); player = null
        AudioRoute.onChange = null
        AudioRoute.release(app)
        try { aec?.release() } catch (e: Exception) { }
        try { ns?.release() } catch (e: Exception) { }
        aec = null; ns = null
        try { sched.shutdownNow() } catch (e: Exception) { }
        try { toolsEx.shutdownNow() } catch (e: Exception) { }
        try { http.dispatcher.executorService.shutdown() } catch (e: Exception) { }
    }

    // ── output routing: USAGE_VOICE_COMMUNICATION defaults to the earpiece, so push it to the loudspeaker
    //    unless headphones / Bluetooth / USB audio are connected ──
    private var routed = false
    private var prevSpeakerphone = false

    private fun routeToSpeaker(): AudioDeviceInfo? {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val external = outs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (external) return null
            val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            prevSpeakerphone = am.isSpeakerphoneOn
            if (Build.VERSION.SDK_INT >= 31 && spk != null) am.setCommunicationDevice(spk)
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            routed = true
            return spk
        } catch (e: Exception) { return null }
    }

    private fun restoreRouting() {
        if (!routed) return
        routed = false
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = prevSpeakerphone
        } catch (e: Exception) { }
    }

    /** Bubble tapped while Pragon talks: silence the rest of this reply and listen. */
    fun skipSpeech() {
        dropAudio = true
        player?.flush()
        speakingNow = false
        ui.onState(State.LISTENING)
    }

    // ───────────────────────────── connection ─────────────────────────────

    private fun connect() {
        if (!wantRunning) return
        val gen = synchronized(lock) { generation++; ready = false; generation }
        ui.onState(State.CONNECTING)
        val ver = if (enhanced) "v1alpha" else "v1beta"    // v1alpha carries affective dialog + proactive audio
        val url = "wss://$HOST/ws/google.ai.generativelanguage.$ver.GenerativeService.BidiGenerateContent?key=" +
            Prefs.effectiveAiKey(app)
        try {
            ws = http.newWebSocket(Request.Builder().url(url).build(), Sock(gen))
        } catch (e: Exception) {
            dropped(gen, 0, e.message ?: "", e)
        }
    }

    private fun scheduleConnect(ms: Long) {
        try { sched.schedule(Runnable { connect() }, ms, TimeUnit.MILLISECONDS) } catch (e: Exception) { }
    }

    /** Close the socket and open a new one (config changed, or Google sent goAway). */
    private fun reconnect(keepHandle: Boolean) {
        if (!wantRunning) return
        val old = ws
        synchronized(lock) { generation++; ready = false }
        if (!keepHandle) resumeHandle = null
        try { old?.close(1000, null) } catch (e: Exception) { }
        scheduleConnect(250)
    }

    private inner class Sock(val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            webSocket.send(setupJson().toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) { if (gen == generation) handle(gen, text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (gen == generation) handle(gen, bytes.utf8()) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try { webSocket.close(1000, null) } catch (e: Exception) { }
            dropped(gen, code, reason, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(gen, code, reason, null) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(gen, response?.code ?: 0, "", t)
        }
    }

    /** The socket of generation [gen] went away. Decide: give up, retry without the preview features, or back off and retry. */
    private fun dropped(gen: Int, code: Int, reason: String, t: Throwable?) {
        synchronized(lock) {
            if (gen != generation || !wantRunning) return
            generation++; ready = false
        }
        val detail = clean((t?.message ?: reason).ifBlank { "connection closed ($code)" })
        if (detail.startsWith("Privacy Shield")) { fatal(detail); return }
        if (detail.contains("API key", true) || detail.contains("API_KEY", true) || code == 401 || code == 403) {
            fatal("Pragon rejected the API key. Check it in Settings. ($detail)"); return
        }
        val serverSaidNo = code != 0
        // The PC does the same: if the server rejects the preview audio features, reconnect with the plain config.
        if (!everReady && enhanced && serverSaidNo) { enhanced = false; scheduleConnect(300); return }
        failures++
        val limit = if (everReady) 8 else 3
        if (failures > limit) { fatal("I couldn't keep a live connection to Pragon: $detail"); return }
        scheduleConnect(minOf(1000L shl (failures - 1), 15000L))
    }

    private fun fatal(message: String) {
        if (!wantRunning) return
        stop()
        ui.onError(clean(message))
    }

    private fun clean(s: String): String {
        val k = Prefs.effectiveAiKey(app)
        return if (k.isNotBlank()) s.replace(k, "***") else s
    }

    // ───────────────────────────── setup message ─────────────────────────────

    private fun setupJson(): JSONObject {
        val voice = voiceName(Prefs.liveVoice(app)) ?: "Charon"
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        if (enhanced) gen.put("enableAffectiveDialog", true)
        val session = JSONObject()
        resumeHandle?.let { session.put("handle", it) }
        val setup = JSONObject()
            .put("model", Prefs.liveModel(app))
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", callPrompt ?: Persona.liveSystem(app, mode)))))
            .put("tools", tools())
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", session)
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // v10: end-of-speech is detected fast, so "hi" is answered right after you stop talking (not after your next sentence)
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 40)
                .put("silenceDurationMs", 400)))
        if (enhanced) setup.put("proactivity", JSONObject().put("proactiveAudio", true))
        return JSONObject().put("setup", setup)
    }

    private fun callTools(): JSONArray {
        fun s(d: String) = JSONObject().put("type", "STRING").put("description", d)
        val save = JSONObject().put("name", "save_call_info")
            .put("description", "Record what you learned about the caller. Call it as soon as you learn something, and again when you learn more.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                .put("caller_name", s("Who the caller says they are."))
                .put("purpose", s("Why they are calling."))
                .put("urgency", s("low, normal or high, and why."))
                .put("callback", s("Callback number or time they gave."))
                .put("scam", s("yes if this looks like a scam or fishing for private data, otherwise no."))))
        val end = JSONObject().put("name", "end_call").put("description", "Hang up after you have said goodbye.")
        val take = JSONObject().put("name", "takeover_owner").put("description", "The owner said Pragon takeover and wants to take the call.")
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(save).put(end).put(take)))
    }

    private fun tools(): JSONArray {
        if (callPrompt != null) return callTools()
        val base = Tools.gemini(mode)       // [{functionDeclarations:[phone_control (not in Assistant mode), remember, forget]}]
        val decls = base.getJSONObject(0).getJSONArray("functionDeclarations")
        decls.put(decl("set_mode", "Switch Pragon's mode: assistant (only talks), master (talks and acts) or agent (acts quietly).",
            "mode", "assistant, master or agent", required = true))
        decls.put(decl("change_voice", "Change Pragon's speaking voice. Give a voice name (" + VOICES.joinToString(", ") +
            "), or leave it empty to move to the next voice.", "voice", "Voice name, or empty for the next voice.", required = false))
        decls.put(JSONObject().put("name", "mute_pragon")
            .put("description", "Stop listening until the user taps the bubble. Use when the user says mute, be quiet, stop listening or pause listening."))
        decls.put(JSONObject().put("name", "shutdown_pragon")
            .put("description", "End the assistant session. Only when the user clearly wants to end it. Say a brief goodbye first."))
        return base
    }

    private fun decl(name: String, desc: String, param: String, paramDesc: String, required: Boolean): JSONObject {
        val props = JSONObject().put(param, JSONObject().put("type", "STRING").put("description", paramDesc))
        val p = JSONObject().put("type", "OBJECT").put("properties", props)
        if (required) p.put("required", JSONArray().put(param))
        return JSONObject().put("name", name).put("description", desc).put("parameters", p)
    }

    // ───────────────────────────── incoming messages ─────────────────────────────

    private fun handle(gen: Int, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        if (o.has("setupComplete")) { onReady(); return }
        o.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (h.isNotBlank() && u.optBoolean("resumable", true)) resumeHandle = h
        }
        if (o.has("goAway")) { reconnect(keepHandle = true); return }
        o.optJSONObject("toolCall")?.let { runTools(gen, it) }
        o.optJSONObject("serverContent")?.let { content(it) }
    }

    private fun onReady() {
        ready = true; everReady = true; failures = 0
        ui.onState(State.LISTENING)
        greeting?.let { g -> greeting = null; sendText("[STARTUP_BRIEFING] $g") }
    }

    private fun content(sc: JSONObject) {
        if (sc.optBoolean("interrupted")) {
            player?.flush()
            dropAudio = false
            speakingNow = false
            synchronized(outBuf) { outBuf.setLength(0) }
            ui.onState(State.LISTENING)
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val mime = d.optString("mimeType")
                if (mime.isNotEmpty() && !mime.startsWith("audio/")) continue
                if (dropAudio) continue
                val pcm = try { Base64.decode(d.optString("data"), Base64.DEFAULT) } catch (e: Exception) { continue }
                var off = 0
                while (off < pcm.size) {
                    val n = minOf(OUT_SLICE_BYTES, pcm.size - off)
                    player?.enqueue(pcm.copyOfRange(off, off + n))
                    off += n
                }
                spokeSinceTool = true
                if (!speakingNow) { speakingNow = true; ui.onState(State.SPEAKING) }
            }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let { t ->
            if (t.isNotEmpty()) synchronized(inBuf) { inBuf.append(t) }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { t ->
            if (t.isNotEmpty()) synchronized(outBuf) { outBuf.append(t) }
        }
        if (sc.optBoolean("turnComplete")) turnComplete()
    }

    private fun turnComplete() {
        dropAudio = false
        val said = synchronized(inBuf) { val s = CTRL_TAGS.replace(inBuf.toString(), "").trim(); inBuf.setLength(0); s }
        val reply = synchronized(outBuf) { val s = CTRL_TAGS.replace(outBuf.toString(), "").trim(); outBuf.setLength(0); s }
        if (said.isNotEmpty()) ui.onUserText(said)
        if (reply.isNotEmpty()) {
            ui.onPragonText(reply)
            if (callPrompt == null) Conversation.note(said.ifEmpty { "(voice)" }, reply)   // the chat screen and the text engines share this history
        }
        seen.clear()   // a new request may repeat an earlier call

        // "end the session" / "reload with the new mode or voice" run only after the spoken confirmation has finished
        val next = if (spokeSinceTool) afterTurn else null
        if (next != null) afterTurn = null
        drainThen {
            speakingNow = false
            if (wantRunning) ui.onState(State.LISTENING)
            next?.invoke()
        }
    }

    /** Runs [action] once everything Pragon was saying has actually been played (generation runs faster than playback). */
    private fun drainThen(tries: Int = 0, action: () -> Unit) {
        val p = player
        if (p == null || !p.busy || tries > 100) { action(); return }
        try { sched.schedule(Runnable { drainThen(tries + 1, action) }, 150, TimeUnit.MILLISECONDS) } catch (e: Exception) { }
    }

    // ───────────────────────────── tools ─────────────────────────────

    private fun runTools(gen: Int, tc: JSONObject) {
        val arr = tc.optJSONArray("functionCalls") ?: return
        val calls = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        try {
            toolsEx.execute {
                val responses = JSONArray()
                for (c in calls) {
                    val id = c.optString("id")
                    val name = c.optString("name")
                    val args = c.optJSONObject("args") ?: JSONObject()
                    val out = try {
                        execTool(id, name, args)
                    } catch (e: Exception) {
                        reply(false, "That failed: " + (e.message ?: e.javaClass.simpleName))
                    }
                    responses.put(JSONObject().put("id", id).put("name", name).put("response", out))
                }
                if (gen == generation) {
                    ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
                }
            }
        } catch (e: Exception) { }
    }

    private fun reply(ok: Boolean, msg: String): JSONObject = JSONObject().put("ok", ok).put("result", msg)

    /** Runs the follow-up [what] when the model has finished speaking about this tool call, or after 25 s at the latest. */
    private fun afterSpoken(what: () -> Unit) {
        spokeSinceTool = false
        afterTurn = what
        try {
            sched.schedule(Runnable {
                val a = afterTurn
                if (a != null) { afterTurn = null; a() }
            }, 25, TimeUnit.SECONDS)
        } catch (e: Exception) { }
    }

    private fun execTool(id: String, name: String, args: JSONObject): JSONObject {
        when (name) {
            "save_call_info", "takeover_owner" -> {
                val r = onCallTool?.invoke(name, args) ?: "ok"
                return reply(true, r)
            }
            "end_call" -> {
                onCallTool?.invoke(name, args)
                afterSpoken { ui.onShutdownRequested() }
                return reply(true, "Ending the call after your goodbye.")
            }
            "phone_control", "remember", "forget" -> {
                val r = Tools.run(app, ToolCall(id, name, args), mode, seen)
                return reply(r.ok, r.msg)
            }
            in ExtraTools.NAMES -> {
                val r = Tools.run(app, ToolCall(id, name, args), mode, seen)
                return reply(r.ok, r.msg)
            }
            "mute_pragon" -> {
                afterSpoken { ui.onMuteRequested() }
                return reply(true, "Muting after a very short acknowledgement. Say OK in at most three words.")
            }
            "shutdown_pragon" -> {
                afterSpoken { ui.onShutdownRequested() }
                return reply(true, "Shutting down after you say goodbye.")
            }
            "set_mode" -> {
                val want = args.optString("mode").trim().lowercase()
                val m = PMode.values().firstOrNull { it.key == want }
                    ?: return reply(false, "Unknown mode. Use assistant, master or agent.")
                Prefs.saveMode(app, m)
                mode = m
                afterSpoken { reconnect(keepHandle = false) }   // the tool list and the mode block of the prompt change
                return reply(true, "Mode is now ${m.label.lowercase()}. Confirm it in one short sentence.")
            }
            "change_voice" -> {
                val asked = args.optString("voice").trim()
                val current = voiceName(Prefs.liveVoice(app)) ?: "Charon"
                val v = if (asked.isEmpty() || asked.equals("next", true)) VOICES[(VOICES.indexOf(current) + 1) % VOICES.size]
                        else voiceName(asked) ?: return reply(false, "I don't have a voice called $asked.")
                Prefs.setLiveVoice(app, v)
                afterSpoken { reconnect(keepHandle = false) }   // Gemini fixes the voice when the session starts
                return reply(true, "Voice changed to $v. Say one short sentence now; it will be in the new voice.")
            }
        }
        return reply(false, "Unknown tool '$name'.")
    }

    private fun sendText(t: String) {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t)))
        ws?.send(JSONObject().put("clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)).toString())
    }

    // ───────────────────────────── microphone ─────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startMic() {
        micRun = true
        micThread = Thread {
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(SEND_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // VOICE_COMMUNICATION turns on the phone's echo cancellation: Pragon's own voice is not heard as the user.
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, SEND_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MIC_CHUNK_BYTES * 4)
                )
                rec = r
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    fatal("The microphone couldn't start. Another app may be using it.")
                } else {
                    try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.setEnabled(true) } } catch (e: Exception) { }
                    try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.setEnabled(true) } } catch (e: Exception) { }
                    r.startRecording()
                    val buf = ByteArray(MIC_CHUNK_BYTES)
                    while (micRun) {
                        val n = r.read(buf, 0, buf.size)
                        if (n < 0) { fatal("The microphone stopped working (error $n)."); break }
                        if (n == 0 || !ready) continue
                        // Barge-in off (loudspeaker, no echo cancelling): don't listen while Pragon is talking.
                        if (!Prefs.liveBargeIn(app) && player?.busy == true) continue
                        val sock = ws ?: continue
                        if (sock.queueSize() > 400_000L) continue   // network is behind: drop audio rather than pile it up
                        val msg = JSONObject().put("realtimeInput", JSONObject().put("audio",
                            JSONObject()
                                .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                                .put("mimeType", "audio/pcm;rate=$SEND_RATE")))
                        sock.send(msg.toString())
                    }
                }
            } catch (e: Exception) {
                if (micRun) fatal("The microphone failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { rec?.stop() } catch (e: Exception) { }
                try { rec?.release() } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; name = "pragon-live-mic"; start() }
    }

    // ───────────────────────────── playback ─────────────────────────────

    /** Plays Gemini's 24 kHz PCM from a queue on its own thread, so the socket thread never blocks on audio. */
    private class Player(private val speaker: AudioDeviceInfo?) {
        private val q = LinkedBlockingQueue<ByteArray>()
        @Volatile private var alive = true
        @Volatile private var writing = false
        @Volatile private var lastEnd = 0L
        private val track: AudioTrack
        private val thread: Thread

        init {
            val min = AudioTrack.getMinBufferSize(RECV_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // pairs with the mic's echo canceller
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RECV_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, RECV_RATE * 2 * 4 / 10))      // about 0.4 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try { if (speaker != null) track.setPreferredDevice(speaker) } catch (e: Exception) { }
            track.play()
            thread = Thread {
                while (alive) {
                    val b = next() ?: continue
                    writing = true
                    try { track.write(b, 0, b.size) } catch (e: Exception) { }
                    writing = false
                    lastEnd = System.currentTimeMillis()
                }
            }.apply { isDaemon = true; name = "pragon-live-out"; start() }
        }

        private fun next(): ByteArray? = try { q.poll(100, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

        fun enqueue(b: ByteArray) { q.offer(b) }

        /** Move the audio to another output (speaker / earpiece / headset) while it plays. */
        fun prefer(d: AudioDeviceInfo?) { try { track.setPreferredDevice(d) } catch (e: Exception) { } }

        /** Drop everything not yet played (the user interrupted). */
        fun flush() {
            q.clear()
            try { track.pause(); track.flush(); track.play() } catch (e: Exception) { }
        }

        /** True while audio is queued, being written, or just finished (the device buffer still holds a moment of speech). */
        val busy: Boolean
            get() = q.isNotEmpty() || writing || System.currentTimeMillis() - lastEnd < 600

        fun release() {
            alive = false
            q.clear()
            try { thread.interrupt() } catch (e: Exception) { }
            try { track.pause(); track.flush() } catch (e: Exception) { }
            try { track.release() } catch (e: Exception) { }
        }
    }
}
