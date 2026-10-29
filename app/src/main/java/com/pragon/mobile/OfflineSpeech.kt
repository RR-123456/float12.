package com.pragon.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v10 on-device speech recognition for float mode.
 *  - Uses Android's ON-DEVICE recognizer (no audio leaves the phone) when the phone has one; otherwise the normal recognizer with
 *    EXTRA_PREFER_OFFLINE.
 *  - Checks that the offline language pack is installed (Android 13+) and can ask Android to download it.
 *  - Turns the recognizer's error numbers into plain words, so the bubble never stays yellow with no explanation.
 */
object OfflineSpeech {

    /** "unknown", "ready", "missing" or "unsupported" (last answer of the pack check). */
    @Volatile var state = "unknown"
    private val exec = Executors.newSingleThreadExecutor()
    private var asked = false

    fun create(ctx: Context, onDevice: Boolean): SpeechRecognizer =
        if (onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)

    fun language(ctx: Context): String = Prefs.speechLang(ctx).ifBlank { Locale.getDefault().toLanguageTag() }

    fun intent(ctx: Context, preferOffline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)          // lets "I'm home" wake the bubble before you finish the breath
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        val lang = Prefs.speechLang(ctx)
        if (lang.isNotBlank()) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        }
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
    }

    /**
     * Asks Android whether the offline pack for our language is installed and, if it isn't, to download it (a one-time
     * download by the phone's speech service). [say] gets a short sentence for the bubble caption.
     */
    fun checkPack(ctx: Context, rec: SpeechRecognizer, say: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return
        val i = intent(ctx, true)
        val want = language(ctx).substringBefore('-').lowercase()
        try {
            rec.checkRecognitionSupport(i, exec, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val have = s.installedOnDeviceLanguages.any { it.lowercase().startsWith(want) }
                    if (have) { state = "ready"; return }
                    val canGet = s.supportedOnDeviceLanguages.any { it.lowercase().startsWith(want) } ||
                        s.pendingOnDeviceLanguages.any { it.lowercase().startsWith(want) }
                    state = if (canGet) "missing" else "unsupported"
                    if (canGet && !asked) {
                        asked = true
                        say("Downloading the offline speech pack (one time)...")
                        try { rec.triggerModelDownload(i) } catch (e: Exception) { }
                    }
                }
                override fun onError(error: Int) { }
            })
        } catch (e: Exception) { }
    }

    fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "The speech service wanted the internet"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
        SpeechRecognizer.ERROR_SERVER -> "Speech service error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service is rate-limited"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language isn't supported offline"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The offline speech pack for this language isn't installed"
        else -> "Speech error $error"
    }

    fun isLanguageProblem(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
}
