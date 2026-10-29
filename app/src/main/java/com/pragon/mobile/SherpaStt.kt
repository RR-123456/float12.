package com.pragon.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * v12: Pragon's own offline speech recognition, now sherpa-onnx STREAMING (zipformer transducer) instead of Vosk.
 *
 *  - Real streaming: partial text arrives while you are still talking, and the recognizer itself decides where the phrase ends
 *    (endpointing), so a command is handed over ~0.7 s after you stop instead of after a fixed wait.
 *  - Optional Silero VAD (a ~1 MB model): while nobody is speaking the recognizer does no work at all (battery, heat). The last
 *    400 ms of audio are kept and replayed when speech starts, so the first word is not clipped. No VAD file = no gating, still works.
 *  - Same public surface as the old VoskStt (state, hasModel, shouldUse, warmup, release, start, download, ...), so FloatService,
 *    MainActivity and Settings only changed the class name. The engine id stored in Prefs is still "vosk" so saved settings keep
 *    working; read it as "Pragon's own engine".
 *
 * The native library and the Kotlin API come from the sherpa-onnx release (see tools/fetch_sherpa.sh); this file only uses the
 * stable core calls: createStream, acceptWaveform, isReady, decode, getResult, isEndpoint, reset, inputFinished, release.
 */
object SherpaStt {

    const val ERR_NO_MODEL = -101
    const val ERR_LOAD = -102

    /** Small English streaming zipformer (about 20M parameters). Any streaming transducer archive (.tar.bz2 or .zip) works. */
    const val DEFAULT_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"
    const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    private const val RATE = 16000
    private const val CHUNK = 800                         // 50 ms of audio per read: low latency, still cheap
    private const val MIN_MODEL_BYTES = 5_000_000L
    private const val NO_SPEECH_MS = 10_000L
    private const val MAX_PHRASE_MS = 20_000L
    private const val PREROLL_CHUNKS = 8                  // 8 x 50 ms = 400 ms kept for the VAD gate
    private const val ASR_THREADS = 2
    /** Trailing silence that ends a phrase. Short enough to feel instant, long enough not to cut "set a timer ... for ten minutes". */
    private const val END_SILENCE_S = 0.7f

    @Volatile var state = "idle"                           // "idle", "loading", "ready" or "error: ..."
    @Volatile var downloading = false
    @Volatile var dlState = ""                             // "", "37%", "unpacking", "done" or "error: ..."

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val loadLock = Any()

    // ── where things live ────────────────────────────────────────────────

    private fun url(c: Context): String = Prefs.sttModelUrl(c)

    private fun dirs(c: Context): List<File> = listOf(File(c.filesDir, "sherpa-asr"), File(Offline.modelDir(c), "sherpa"))

    private fun vadFile(c: Context): File = File(c.filesDir, "sherpa-vad/silero_vad.onnx")

    private fun valid(d: File): Boolean = d.isDirectory && SherpaFiles.pick(d.list()?.toList() ?: emptyList()) != null

    fun modelDir(c: Context): File? = dirs(c).firstOrNull { valid(it) }

    fun hasModel(c: Context): Boolean = modelDir(c) != null

    fun hasVad(c: Context): Boolean = vadFile(c).length() > 100_000L

    private fun languageOk(c: Context): Boolean {
        if (url(c) != DEFAULT_MODEL_URL) return true       // a custom model: the person chose the language
        val l = Prefs.speechLang(c).lowercase()
        return l.isBlank() || l.startsWith("en")
    }

    /**
     * Should float mode listen with this engine right now?
     *  engine "vosk" (= Pragon's own): whenever the model is installed
     *  engine "android": never
     *  engine "auto": offline (or Android has no recognizer) and the model is installed and fits the chosen language.
     */
    fun shouldUse(c: Context): Boolean {
        if (!hasModel(c)) return false
        return when (Prefs.sttEngine(c)) {
            "vosk" -> true
            "android" -> false
            else -> languageOk(c) && (Offline.active(c) || !SpeechRecognizer.isRecognitionAvailable(c) || Guard.preferOfflineSpeech(c))
        }
    }

    // ── loading ──────────────────────────────────────────────────────────

    fun warmup(c: Context) {
        if (recognizer != null || !hasModel(c)) return
        val app = c.applicationContext
        Thread { try { load(app) } catch (e: Throwable) { } }.start()
    }

    private fun load(c: Context): OnlineRecognizer? = synchronized(loadLock) {
        recognizer?.let { return it }
        val d = modelDir(c) ?: return null
        val f = SherpaFiles.pick(d.list()?.toList() ?: emptyList()) ?: return null
        state = "loading"
        try {
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(d, f.encoder).absolutePath,
                        decoder = File(d, f.decoder).absolutePath,
                        joiner = File(d, f.joiner).absolutePath,
                    ),
                    tokens = File(d, f.tokens).absolutePath,
                    numThreads = ASR_THREADS,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),               // long silence before any speech
                    rule2 = EndpointRule(true, END_SILENCE_S, 0.0f),       // speech, then this much silence = phrase over
                    rule3 = EndpointRule(false, 0.0f, 20.0f),              // hard cap on one phrase
                ),
            )
            val r = OnlineRecognizer(assetManager = null, config = cfg)
            recognizer = r
            state = "ready"
            r
        } catch (e: Throwable) {
            state = "error: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /** Lets the model go (tens of MB of RAM); it reloads on next use. */
    fun release() {
        synchronized(loadLock) {
            try { recognizer?.release() } catch (e: Throwable) { }
            recognizer = null
            if (state == "ready" || state == "loading") state = "idle"
        }
    }

    fun describe(error: Int): String = when (error) {
        ERR_NO_MODEL -> "The offline speech model isn't installed"
        ERR_LOAD -> "The offline speech model couldn't load ($state)"
        else -> OfflineSpeech.describe(error)
    }

    // ── one listening session ────────────────────────────────────────────

    class Session internal constructor(private val onCancel: () -> Unit) {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; onCancel() }
    }

    private fun makeVad(c: Context): Vad? {
        if (!hasVad(c)) return null
        return try {
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile(c).absolutePath,
                        threshold = 0.45f,
                        minSilenceDuration = 0.3f,
                        minSpeechDuration = 0.1f,
                        windowSize = 512,
                    ),
                    sampleRate = RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        } catch (e: Throwable) { null }
    }

    /**
     * Listens for one phrase and reports through [l] like Android's recognizer: onBeginningOfSpeech, onPartialResults,
     * onEndOfSpeech, then onResults (or onError). Runs on its own thread; every callback is delivered on the main thread.
     * [wakeOnly] is kept for the same call shape as before; the VAD gate already makes standby nearly free.
     */
    @Suppress("UNUSED_PARAMETER")
    fun start(c: Context, l: RecognitionListener, wakeOnly: Boolean): Session {
        val app = c.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var thread: Thread? = null
        val s = Session { thread?.interrupt() }
        fun post(f: () -> Unit) = main.post { if (!s.cancelled) f() }

        val t = Thread({
            var rec: AudioRecord? = null
            var micAcquired = false
            var vad: Vad? = null
            var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
            try {
                val r0 = recognizer ?: load(app)
                if (r0 == null) { post { l.onError(if (hasModel(app)) ERR_LOAD else ERR_NO_MODEL) }; return@Thread }
                if (s.cancelled) return@Thread

                val mic = AudioRoute.beginListening(app)
                micAcquired = true
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) { post { l.onError(SpeechRecognizer.ERROR_AUDIO) }; return@Thread }
                val source = if (mic.communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
                val ar = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
                rec = ar
                if (ar.state != AudioRecord.STATE_INITIALIZED) { post { l.onError(SpeechRecognizer.ERROR_AUDIO) }; return@Thread }
                if (mic.device != null) try { ar.setPreferredDevice(mic.device) } catch (e: Exception) { }

                vad = makeVad(app)
                val st = r0.createStream("")
                stream = st
                ar.startRecording()
                post { l.onReadyForSpeech(null) }

                val shorts = ShortArray(CHUNK)
                val pre = ArrayDeque<FloatArray>()           // pre-roll for the VAD gate
                val started = System.currentTimeMillis()
                var inUtterance = vad == null                // no VAD: always feeding
                var spoke = false
                var lastPartial = ""
                var finalText = ""

                while (!s.cancelled && !Thread.currentThread().isInterrupted) {
                    val n = ar.read(shorts, 0, shorts.size)
                    if (n < 0) { post { l.onError(SpeechRecognizer.ERROR_AUDIO) }; return@Thread }
                    if (n == 0) continue
                    val f = FloatArray(n) { i -> shorts[i] / 32768f }

                    val v = vad
                    if (v != null && !inUtterance) {
                        v.acceptWaveform(f)
                        val speech = v.isSpeechDetected()
                        v.clear()                              // we only use the yes/no, so never let segments pile up
                        if (!speech) {
                            pre.addLast(f)
                            while (pre.size > PREROLL_CHUNKS) pre.removeFirst()
                            val el = System.currentTimeMillis() - started
                            if (el > NO_SPEECH_MS) { post { l.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }; return@Thread }
                            continue
                        }
                        inUtterance = true
                        for (p in pre) st.acceptWaveform(p, RATE)
                        pre.clear()
                    }

                    st.acceptWaveform(f, RATE)
                    while (r0.isReady(st)) r0.decode(st)
                    val text = r0.getResult(st).text.trim()
                    if (text.isNotEmpty() && text != lastPartial) {
                        lastPartial = text
                        if (!spoke) { spoke = true; post { l.onBeginningOfSpeech() } }
                        post { l.onPartialResults(bundle(listOf(text))) }
                    }
                    if (r0.isEndpoint(st)) {
                        if (text.isEmpty()) {                  // silence "endpoint": start over, keep listening
                            r0.reset(st)
                            lastPartial = ""
                            if (v != null) { inUtterance = false; v.reset() }
                            continue
                        }
                        finalText = text
                        break
                    }
                    val el = System.currentTimeMillis() - started
                    if (!spoke && el > NO_SPEECH_MS) { post { l.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }; return@Thread }
                    if (el > MAX_PHRASE_MS) {
                        st.inputFinished()
                        while (r0.isReady(st)) r0.decode(st)
                        finalText = r0.getResult(st).text.trim()
                        break
                    }
                }
                if (s.cancelled) return@Thread
                post { l.onEndOfSpeech() }
                if (finalText.isEmpty()) post { l.onError(SpeechRecognizer.ERROR_NO_MATCH) }
                else post { l.onResults(bundle(listOf(finalText))) }
            } catch (e: UnsatisfiedLinkError) {
                state = "error: native library missing"
                post { l.onError(ERR_LOAD) }
            } catch (e: Throwable) {
                if (!s.cancelled) post { l.onError(SpeechRecognizer.ERROR_AUDIO) }
            } finally {
                try { stream?.release() } catch (e: Throwable) { }
                try { vad?.release() } catch (e: Throwable) { }
                try { rec?.stop() } catch (e: Exception) { }
                try { rec?.release() } catch (e: Exception) { }
                if (micAcquired) AudioRoute.endListening(app)
            }
        }, "pragon-sherpa")
        thread = t
        t.isDaemon = true
        t.start()
        return s
    }

    private fun bundle(texts: List<String>) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts))
    }

    // ── model download (the one network use, started by you) ─────────────

    fun download(c: Context) {
        if (downloading) return
        downloading = true
        dlState = "starting"
        val app = c.applicationContext
        Thread {
            val archive = File(app.cacheDir, "sherpa-model.part")
            val target = File(app.filesDir, "sherpa-asr")
            val tmpDir = File(app.filesDir, "sherpa-asr.tmp")
            try {
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
                val modelUrl = url(app)
                fetch(http, modelUrl, archive) { dlState = it }
                if (archive.length() < MIN_MODEL_BYTES) throw IOException("the file is too small to be a speech model")
                dlState = "unpacking"
                tmpDir.deleteRecursively(); tmpDir.mkdirs()
                if (modelUrl.lowercase().endsWith(".zip")) unzip(archive, tmpDir) else untarBz2(archive, tmpDir)
                val picked = SherpaFiles.pick(tmpDir.list()?.toList() ?: emptyList()) ?: throw IOException("the archive isn't a streaming transducer model")
                // keep only the files that will be used (the archive also carries the other precisions)
                val keep = setOf(picked.encoder, picked.decoder, picked.joiner, picked.tokens)
                tmpDir.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
                release()
                target.deleteRecursively()
                if (!tmpDir.renameTo(target)) throw IOException("couldn't save the model")
                // optional voice-activity model (about 1 MB); failure here is not an error
                try {
                    val vf = vadFile(app)
                    vf.parentFile?.mkdirs()
                    if (!hasVad(app)) fetch(http, VAD_URL, vf) { }
                } catch (e: Exception) { vadFile(app).delete() }
                File(app.filesDir, "vosk-model").deleteRecursively()      // the old v11 Vosk model: free its ~40 MB
                state = "idle"
                dlState = "done"
                warmup(app)
            } catch (e: Exception) {
                tmpDir.deleteRecursively()
                dlState = "error: ${e.message}"
            } finally {
                archive.delete()
                downloading = false
            }
        }.start()
    }

    private fun fetch(http: OkHttpClient, link: String, dest: File, progress: (String) -> Unit) {
        http.newCall(Request.Builder().url(link).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty answer")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(if (total > 0) "${done * 100 / total}%" else "${done / 1_000_000} MB")
                    }
                }
            }
        }
    }

    /** Extracts only .onnx and tokens.txt files, flat into [dest] (drops the top folder and test_wavs). */
    private fun untarBz2(archive: File, dest: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tin ->
            while (true) {
                val e = tin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (!SherpaFiles.wanted(name)) continue
                File(dest, name).outputStream().use { tin.copyTo(it) }
            }
        }
    }

    private fun unzip(archive: File, dest: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (!SherpaFiles.wanted(name)) continue
                File(dest, name).outputStream().use { zin.copyTo(it) }
            }
        }
    }
}
