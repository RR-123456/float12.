package com.pragon.mobile

import android.app.ActivityManager
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v10 STRICT OFFLINE. One switch (Prefs.offlineOnly, ON by default) that means:
 *  - no Gemini Live, no Gemini chat, no cloud AI of any kind; the engine is always "local" (on-device models)
 *  - the shared network guard (Guard.ShieldInterceptor) lets ONLY loopback (127.x / localhost) through; every other host is refused and logged
 *  - speech recognition must be the phone's on-device recognizer, and text to speech must use an installed (non-network) voice
 *  - Pragon works with no internet at all for everything it can do: apps, gestures, typing, messages, calls, screenshots, timers, settings
 *
 * The ONE exception is the model download, which you start yourself from Settings (or by saying "download the model"): a plain
 * one-time file download over Wi-Fi. You can skip it completely by copying the .litertlm file into the models folder.
 */
object Offline {

    /** FunctionGemma 270M as a LiteRT-LM bundle (~283 MB). Change it in Settings if you host the file yourself. Not verified from here. */
    const val DEFAULT_MODEL_URL = "https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM/resolve/main/model.litertlm"
    const val ROUTER_FILE = "functiongemma-270m.litertlm"
    /** Optional: any chat-capable LiteRT-LM model (for example a small Gemma). Without it, free chat is unavailable offline. */
    const val CHAT_FILE = "chat.litertlm"
    private const val MIN_BYTES = 50_000_000L

    /** v11: true when Pragon must act offline: the manual lock is on, OR there is no working internet right now. */
    fun active(c: Context): Boolean = Prefs.offlineOnly(c) || !Net.online(c)

    /** Just the manual lock. */
    fun locked(c: Context): Boolean = Prefs.offlineOnly(c)

    fun isLoopback(host0: String): Boolean {
        val h = host0.trim().lowercase().trim('[', ']')
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }

    /** Home network / Tailscale / *.local: your own devices, not the internet (same rule as Privacy Shield). */
    fun isPrivate(host: String): Boolean = Guard.isLocalHost(host)

    /** null = fine, else why the connection is refused while offline. */
    fun hostProblem(c: Context, host: String): String? {
        if (!active(c) || isLoopback(host)) return null
        // No internet but still on the home Wi-Fi (PC link, Ghost server): fine, unless the manual lock is on.
        if (!locked(c) && isPrivate(host)) return null
        return if (locked(c)) "Always-offline lock is on, so Pragon won't connect to $host. Everything runs on this phone."
        else "There's no internet right now, so Pragon can't reach $host."
    }

    // ── model files ──────────────────────────────────────────────────────

    /** /sdcard/Android/data/com.pragon.mobile/files/models (visible to a file manager and to adb push). */
    fun modelDir(c: Context): File {
        val d = c.getExternalFilesDir("models") ?: File(c.filesDir, "models")
        d.mkdirs()
        return d
    }

    fun routerFile(c: Context): File? {
        val d = modelDir(c)
        val named = File(d, ROUTER_FILE)
        if (named.isFile && named.length() > MIN_BYTES) return named
        val alt = File(d, "model.litertlm")
        if (alt.isFile && alt.length() > MIN_BYTES) return alt
        return null
    }

    fun chatFile(c: Context): File? = File(modelDir(c), CHAT_FILE).takeIf { it.isFile && it.length() > MIN_BYTES }

    // ── one-time model download (the only network use, started by you) ───

    @Volatile var downloading = false
    /** "" idle, "37%", "done" or "error: ..." (shown in Settings). */
    @Volatile var dlState = ""

    fun download(c: Context, url: String, fileName: String = ROUTER_FILE) {
        if (downloading) return
        downloading = true
        dlState = "starting"
        val app = c.applicationContext
        Thread {
            val dest = File(modelDir(app), fileName)
            val tmp = File(dest.path + ".part")
            try {
                // Deliberately NOT the shielded client: this is the one download you asked for.
                val http = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code} (the file may need a Hugging Face login, or the link moved)")
                    val body = r.body ?: throw IOException("empty answer")
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                dlState = if (total > 0) "${done * 100 / total}%" else "${done / 1_000_000} MB"
                            }
                        }
                    }
                }
                if (tmp.length() < MIN_BYTES) throw IOException("the file is too small to be a model")
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("couldn't save the file")
                dlState = "done"
            } catch (e: Exception) {
                tmp.delete()
                dlState = "error: ${e.message}"
            } finally {
                downloading = false
            }
        }.start()
    }

    // ── status for Settings and for "are you offline" ────────────────────

    fun ramGb(c: Context): Double {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    fun status(c: Context): JSONObject {
        val r = routerFile(c)
        val ch = chatFile(c)
        return JSONObject()
            .put("offline", active(c)).put("locked", locked(c)).put("online", Net.online(c))
            .put("stt", Prefs.sttEngine(c)).put("sttUrl", Prefs.sttModelUrl(c)).put("sttUrlCustom", Prefs.sttModelUrl(c) != SherpaStt.DEFAULT_MODEL_URL).put("parallel", Prefs.parallelLlm(c))
            .put("vosk", SherpaStt.hasModel(c)).put("voskState", SherpaStt.state).put("voskDl", SherpaStt.dlState).put("voskDownloading", SherpaStt.downloading)
            .put("modelDir", modelDir(c).absolutePath)
            .put("routerReady", r != null)
            .put("routerMb", if (r != null) r.length() / 1_000_000 else 0)
            .put("routerState", OnDeviceLlm.routerState)
            .put("chatReady", ch != null)
            .put("chatState", OnDeviceLlm.chatState)
            .put("speech", OfflineSpeech.state)
            .put("voice", if (Speaker.get(c).hasOfflineVoice()) "ok" else "missing")
            .put("a11y", PragonAccessibilityService.instance != null)
            .put("dl", dlState)
            .put("downloading", downloading)
            .put("url", Prefs.modelUrl(c))
            .put("ram", String.format(java.util.Locale.US, "%.1f", ramGb(c)))
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("autoSend", Prefs.autoSend(c))
    }

    /** One spoken paragraph. */
    fun spokenStatus(c: Context): String {
        val call = Prefs.callMe(c)
        val sb = StringBuilder()
        sb.append(if (locked(c)) "The always-offline lock is on, $call. " else if (active(c)) "There is no internet right now, so I'm running offline, $call. " else "You're online, $call, so online features are available. ")
        sb.append(if (routerFile(c) != null) "The command model is installed. " else "The command model isn't installed yet, so I only use my built-in phrases. ")
        sb.append(if (SherpaStt.hasModel(c)) "The on-phone speech model is installed. " else "No on-phone speech model is installed; say download the speech model. ")
        sb.append(when (OfflineSpeech.state) {
            "ready" -> "Offline speech recognition is ready. "
            "missing" -> "The offline speech pack for your language is missing. "
            else -> "I haven't checked offline speech recognition yet. "
        })
        if (!Speaker.get(c).hasOfflineVoice()) sb.append("There's no offline voice installed for text to speech. ")
        if (PragonAccessibilityService.instance == null) sb.append("The accessibility service is off, so taps and typing won't work. ")
        return sb.toString().trim()
    }
}
