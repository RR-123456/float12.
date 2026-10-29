package com.pragon.mobile

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Where Pragon's voice comes out and which microphone it listens with (Pragon Live and Pragon PA).
 *  AUTO     headset / AirPods when connected (their microphone too, unless switched off), otherwise the loudspeaker
 *  SPEAKER  loudspeaker, always
 *  EARPIECE the phone's earpiece ("normal" call volume)
 * [forced] is used by Pragon PA while it talks to a caller (it must come out loud so the caller can hear it).
 * release() puts the phone's audio back the way it was, so music and calls sound normal again afterwards.
 */
object AudioRoute {
    const val AUTO = "auto"
    const val SPEAKER = "speaker"
    const val EARPIECE = "earpiece"

    @Volatile var forced: String? = null
    /** Called when the user changes the route, so a running Live session can move its audio at once. */
    @Volatile var onChange: (() -> Unit)? = null

    private var taken = false
    private var prevSpeaker = false
    private var askedBt = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_audio", Context.MODE_PRIVATE)
    private fun am(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun saved(c: Context): String = sp(c).getString("mode", AUTO) ?: AUTO
    fun mode(c: Context): String = forced ?: saved(c)
    fun setMode(c: Context, m: String) { sp(c).edit().putString("mode", m).apply(); onChange?.invoke() }
    /**
     * v11: ON by default again. v10 turned it off, so the AirPods were never asked to listen and (the Bluetooth permission was only
     * requested when this was on) Pragon never even noticed them. Pragon now holds the Bluetooth link while it listens and falls
     * back to the phone's microphone by itself if the AirPods microphone does not come up in 1.5 s.
     * "turn off the headset mic" = phone mic for listening, AirPods for sound only.
     */
    fun headsetMic(c: Context): Boolean = sp(c).getBoolean("hs_mic2", true)
    fun setHeadsetMic(c: Context, on: Boolean) { sp(c).edit().putBoolean("hs_mic2", on).apply(); onChange?.invoke() }

    private fun isBt(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWired(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    fun headset(c: Context): AudioDeviceInfo? {
        val outs = am(c).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.firstOrNull { isWired(it) } ?: outs.firstOrNull { isBt(it) }
    }

    fun headsetName(c: Context): String? = try { headset(c)?.productName?.toString() } catch (e: Exception) { null }

    /** Android 12+ needs the Bluetooth permission to move a call-style stream onto AirPods. Ask once. */
    private fun btOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        if (Perm.has(c, android.Manifest.permission.BLUETOOTH_CONNECT)) return true
        if (!askedBt) { askedBt = true; Perm.request(c, android.Manifest.permission.BLUETOOTH_CONNECT) }
        return false
    }

    /** Applies the current route and returns the output device the audio track should prefer (null = let Android decide). */
    @Suppress("DEPRECATION")
    fun apply(c: Context): AudioDeviceInfo? {
        val a = am(c)
        try {
            if (!taken) { taken = true; prevSpeaker = a.isSpeakerphoneOn }
            val m = mode(c)
            val hs = if (m == AUTO) headset(c) else null
            if (hs != null) {
                if (isBt(hs)) {
                    if (!headsetMic(c) || !btOk(c)) {
                        // headset for sound, the phone's own microphone for listening
                        stopSco(a)
                        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
                        a.isSpeakerphoneOn = false
                        return hs
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        val dev = a.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                        if (dev != null) { a.setCommunicationDevice(dev); return dev }
                        return hs
                    }
                    a.startBluetoothSco(); a.isBluetoothScoOn = true
                    return null
                }
                // wired / USB headset
                if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
                a.isSpeakerphoneOn = false
                return hs
            }
            stopSco(a)
            val wantType = if (m == EARPIECE) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            val dev = a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == wantType }
            if (Build.VERSION.SDK_INT >= 31 && dev != null) a.setCommunicationDevice(dev)
            a.isSpeakerphoneOn = m != EARPIECE
            return dev
        } catch (e: Exception) {
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco(a: AudioManager) {
        try { if (a.isBluetoothScoOn) { a.stopBluetoothSco(); a.isBluetoothScoOn = false } } catch (e: Exception) { }
    }

    /** Puts the phone's audio back (speaker off again, Bluetooth call audio released). Safe to call twice. */
    @Suppress("DEPRECATION")
    fun release(c: Context) {
        if (!taken) return
        taken = false
        try {
            val a = am(c)
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            stopSco(a)
            a.isSpeakerphoneOn = prevSpeaker
        } catch (e: Exception) { }
    }

    // ── v11: listening through the headset (offline recognizer, float bubble) ─────

    /** Which microphone a listening session uses. [communication] = Bluetooth call-style audio is up (AudioSource.VOICE_COMMUNICATION). */
    class Mic(val device: AudioDeviceInfo?, val communication: Boolean, val label: String)

    private val mainH = Handler(Looper.getMainLooper())
    @Volatile private var listenBt = false          // the Bluetooth link is held up for listening
    private var cb: AudioDeviceCallback? = null

    /** True while Pragon listens over the AirPods (speech then rides the same link, see Speaker). */
    fun listeningOverBluetooth(): Boolean = listenBt

    /** Asks for the Bluetooth permission once (Android 12+). True when already allowed. */
    fun ensureBluetoothPermission(c: Context): Boolean = btOk(c)

    private fun btInput(a: AudioManager): AudioDeviceInfo? =
        a.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    private fun wiredInput(a: AudioManager): AudioDeviceInfo? =
        a.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /**
     * Picks the microphone. For AirPods it brings the Bluetooth call link up and waits (max 1.5 s, on the calling worker thread,
     * never the main thread) until Android lists the headset microphone; if that never happens the phone's microphone is used,
     * so listening never stalls because of a headset.
     */
    @Suppress("DEPRECATION")
    fun beginListening(c: Context): Mic {
        val a = am(c)
        try {
            if (mode(c) != AUTO) return Mic(null, false, "phone")
            val hs = headset(c) ?: return Mic(null, false, "phone")
            if (isWired(hs)) return Mic(wiredInput(a), false, "wired headset")
            if (!headsetMic(c) || !btOk(c)) return Mic(null, false, "phone")      // AirPods for sound only
            if (!listenBt) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = a.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    } ?: return Mic(null, false, "phone")
                    a.setCommunicationDevice(dev)
                } else {
                    a.startBluetoothSco(); a.isBluetoothScoOn = true
                }
                listenBt = true
            }
            var waited = 0
            var inp = btInput(a)
            while (inp == null && waited < 1500) { Thread.sleep(100); waited += 100; inp = btInput(a) }
            if (inp == null) { dropBluetoothListening(a); return Mic(null, false, "phone") }
            return Mic(inp, true, headsetName(c) ?: "headset")
        } catch (e: Exception) {
            return Mic(null, false, "phone")
        }
    }

    @Suppress("DEPRECATION")
    private fun dropBluetoothListening(a: AudioManager) {
        if (!listenBt) return
        listenBt = false
        try { if (Build.VERSION.SDK_INT >= 31 && !taken) a.clearCommunicationDevice() } catch (e: Exception) { }
        if (!taken) stopSco(a)
    }

    /** One listening session ended. The Bluetooth link stays up between phrases on purpose (bringing it up costs 1-2 s each time). */
    fun endListening(c: Context) { }

    /** Gives the Bluetooth call link back so music sounds normal again (float mode closes, or sleeps for media). */
    fun releaseListening(c: Context) { try { dropBluetoothListening(am(c)) } catch (e: Exception) { } }

    /** Calls [onChange] (main thread) when a headset / AirPods connects or disconnects. Null stops watching. */
    fun watch(c: Context, onChange: (() -> Unit)?) {
        val a = am(c)
        try { cb?.let { a.unregisterAudioDeviceCallback(it) } } catch (e: Exception) { }
        cb = null
        if (onChange == null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (added?.any { isBt(it) || isWired(it) } == true) onChange() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                if (removed?.any { isBt(it) || isWired(it) } == true) { listenBt = false; onChange() }
            }
        }
        cb = callback
        try { a.registerAudioDeviceCallback(callback, mainH) } catch (e: Exception) { }
    }

    /** The headset situation in words, for "airpods status". */
    fun status(c: Context): String {
        val call = Prefs.callMe(c)
        val hs = headset(c)
        if (hs == null) return "I don't see any headset or AirPods connected to the phone, $call. Connect them in Bluetooth settings; once Android shows them as connected, I pick them up by myself."
        val sb = StringBuilder("${headsetName(c) ?: "A headset"} is connected, $call. ")
        if (isBt(hs)) {
            if (Build.VERSION.SDK_INT >= 31 && !Perm.has(c, android.Manifest.permission.BLUETOOTH_CONNECT))
                sb.append("Pragon doesn't have the Bluetooth permission yet, so I can only use them for sound. Allow it and ask again. ")
            else if (!headsetMic(c)) sb.append("I'm using them for sound only, with the phone's microphone. Say turn on the headset mic to talk through them. ")
            else if (listenBt) sb.append("I'm listening through their microphone. ")
            else sb.append("I'll listen through their microphone when I start listening. ")
        }
        return sb.toString().trim()
    }

    /** One spoken sentence about the current choice. */
    fun describe(c: Context): String {
        val call = Prefs.callMe(c)
        val hs = headsetName(c)
        return when (saved(c)) {
            SPEAKER -> "Speaker it is, $call."
            EARPIECE -> "Earpiece, $call."
            else -> if (hs != null) "Automatic: I'll use $hs, $call."
                    else "Automatic: your headset when it connects, the speaker until then, $call."
        }
    }
}
