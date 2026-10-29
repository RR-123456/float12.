package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PRAGON PA: answers phone calls for you.
 *  ring  -> Pragon tells you who is calling and asks: OK = attend, ignore = leave it ringing, reject = cut
 *           (say it, or tap the buttons on the notification)
 *  OK    -> Pragon answers on the loudspeaker, asks who it is, why, how urgent and a callback, refuses personal info / OTP /
 *           bank details and flags scams, then hangs up politely
 *  after -> a spoken summary + a notification, the last 20 are kept. "Pragon takeover" / the TAKE OVER button / a tap on the
 *           bubble hands the live call back to you.
 * Android does not let apps record the other side of a phone call, so the caller is heard through the loudspeaker. Phones that
 * silence the microphone during calls cannot run the assistant part (the ring prompt and summaries still work).
 */
object PragonPA {
    private const val FILE = "pragon_pa"
    @Volatile var aiOnCall = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean("on", false)
    private fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("on", v).apply() }

    private fun required(): List<String> {
        val l = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(android.Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    /** Everything the calls card asks for in one go (caller number, direct calling and AirPods are optional extras). */
    fun allPerms(): Array<String> {
        val l = required().toMutableList()
        l.add(android.Manifest.permission.READ_CALL_LOG)
        l.add(android.Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 31) l.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        return l.toTypedArray()
    }

    fun missing(c: Context): List<String> = allPerms().filter { !Perm.has(c, it) }

    fun enable(c: Context): CommandExecutor.Res {
        val me = Prefs.callMe(c)
        if (required().any { !Perm.has(c, it) }) {
            val shown = Perm.request(c, *allPerms().filter { !Perm.has(c, it) }.toTypedArray())
            return CommandExecutor.Res(false, if (shown) "Pragon PA needs the phone, microphone and notification permissions. A prompt opened: allow them, then say turn on Pragon PA again, $me."
                else "Pragon PA needs phone permissions. Allow them in Settings, $me.")
        }
        setOn(c, true)
        return try {
            c.applicationContext.startForegroundService(Intent(c, PragonPAService::class.java).setAction(PragonPAService.ACTION_START))
            CommandExecutor.Res(true, "Pragon PA is on, $me. When a call comes in I'll tell you who it is and ask whether to attend, ignore or reject it.", label = "Pragon PA")
        } catch (e: Exception) {
            setOn(c, false)
            CommandExecutor.Res(false, "Android wouldn't start Pragon PA from here (${e.message}). Open the Pragon app and turn it on in Settings.")
        }
    }

    fun disable(c: Context): CommandExecutor.Res {
        setOn(c, false)
        try { c.applicationContext.stopService(Intent(c, PragonPAService::class.java)) } catch (e: Exception) { }
        return CommandExecutor.Res(true, "Pragon PA is off, ${Prefs.callMe(c)}.", label = "Pragon PA")
    }

    fun status(c: Context): String {
        val me = Prefs.callMe(c)
        return if (on(c) && PragonPAService.instance != null) "Pragon PA is on and watching for calls, $me."
        else if (on(c)) "Pragon PA is switched on but not running right now, $me. Say turn on Pragon PA again."
        else "Pragon PA is off, $me."
    }

    fun takeover(): CommandExecutor.Res {
        val s = PragonPAService.instance
        return if (s != null && s.takeover()) CommandExecutor.Res(true, "Over to you.", label = "Pragon PA")
        else CommandExecutor.Res(false, "I'm not on a call for you right now.")
    }

    // ---- last 20 summaries ----
    @Synchronized
    fun summaries(c: Context): List<JSONObject> {
        val a = try { JSONArray(sp(c).getString("sum", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    @Synchronized
    fun addSummary(c: Context, o: JSONObject) {
        val a = JSONArray()
        val old = summaries(c)
        for (x in old.takeLast(19)) a.put(x)
        a.put(o)
        sp(c).edit().putString("sum", a.toString()).apply()
    }

    fun line(o: JSONObject): String {
        val sb = StringBuilder("Call from ").append(o.optString("from", "an unknown number")).append(". ")
        o.optString("caller_name").takeIf { it.isNotBlank() }?.let { sb.append("They said they are $it. ") }
        o.optString("purpose").takeIf { it.isNotBlank() }?.let { sb.append("About: $it. ") }
        o.optString("urgency").takeIf { it.isNotBlank() }?.let { sb.append("Urgency: $it. ") }
        o.optString("callback").takeIf { it.isNotBlank() }?.let { sb.append("Callback: $it. ") }
        if (o.optString("scam").lowercase().startsWith("y")) sb.append("Flagged as a possible scam. ")
        if (!o.has("purpose") && !o.has("caller_name")) {
            val t = o.optString("transcript")
            sb.append(if (t.isBlank()) "The caller didn't say anything I could hear." else "What I heard: " + t.replace("\n", " ").take(220))
        }
        return sb.toString().trim()
    }

    fun summaryText(c: Context): String {
        val l = summaries(c)
        if (l.isEmpty()) return "No handled calls yet, ${Prefs.callMe(c)}."
        val fmt = SimpleDateFormat("d MMM h:mm a", Locale.getDefault())
        return l.takeLast(3).reversed().joinToString(" ") { fmt.format(Date(it.optLong("t"))) + ". " + line(it) }
    }

    fun callPrompt(owner: String): String =
        "You are Pragon, the personal call assistant of $owner. You have just answered a phone call on their behalf.\n" +
        "- Say first, in one short sentence: you are Pragon, $owner's assistant, and $owner can't take the call right now.\n" +
        "- Ask who is calling, what it is about, how urgent it is, and a callback number or a good time. One question at a time, short sentences, in the language the caller speaks.\n" +
        "- Call save_call_info as soon as you learn anything, and again when you learn more.\n" +
        "- NEVER give personal information, addresses, where $owner is, passwords, OTPs, card or bank details. If the caller asks for them, or pressures you (police, KYC, prize, 'urgent' payment), refuse politely and call save_call_info with scam=yes.\n" +
        "- Never promise or agree to anything for $owner. Say you will pass the message on.\n" +
        "- When you have what you need (or the caller wants to hang up), thank them, say goodbye, then call end_call.\n" +
        "- If you hear $owner say 'Pragon takeover', call takeover_owner at once and say nothing else."
}

class PragonPAService : Service() {

    companion object {
        const val ACTION_START = "com.pragon.mobile.PA_START"
        const val ACTION_OK = "com.pragon.mobile.PA_OK"
        const val ACTION_IGNORE = "com.pragon.mobile.PA_IGNORE"
        const val ACTION_REJECT = "com.pragon.mobile.PA_REJECT"
        const val ACTION_TAKEOVER = "com.pragon.mobile.PA_TAKEOVER"
        private const val CH = "pragon_pa"
        private const val CH_CALL = "pragon_pa_call"
        private const val NID = 21
        private const val NID_CALL = 22
        private const val NID_SUM = 23
        @Volatile var instance: PragonPAService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var rec: SpeechRecognizer? = null
    private var live: LiveSession? = null
    private var lastNumber: String? = null
    private var ringing = false
    private var handling = false
    private var pendingAi = false
    private var aiUsed = false
    private var callerLabel = "an unknown number"
    private var callerNumber = ""
    private val info = JSONObject()
    private val transcript = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OK -> if (instance != null) decide("ok")
            ACTION_IGNORE -> if (instance != null) decide("ignore")
            ACTION_REJECT -> if (instance != null) decide("reject")
            ACTION_TAKEOVER -> if (instance != null) takeover()
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Pragon PA", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_CALL, "Pragon PA calls", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(this, CH).setContentTitle("Pragon PA is on")
                .setContentText("Watching for calls. Say \"turn off Pragon PA\" to stop.")
                .setSmallIcon(android.R.drawable.sym_action_call).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NID, n)
        } catch (e: Throwable) {
            android.util.Log.e("PragonPA", "start failed", e)
            stopSelf(); return
        }
        instance = this
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { onPhone(i) }
            }
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            receiver = r
        }
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
        stopListen()
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
        stopAi()
        AudioRoute.forced = null
        AudioRoute.release(this)
        instance = null
        super.onDestroy()
    }

    // ── phone state ───────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun onPhone(i: Intent) {
        val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!num.isNullOrBlank()) lastNumber = num
                if (!ringing) {
                    ringing = true
                    // the number can arrive in a second broadcast: give it a moment
                    main.postDelayed({ if (ringing) onRinging() }, 800)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> onOffhook()
            TelephonyManager.EXTRA_STATE_IDLE -> onIdle()
        }
    }

    private fun nameFor(num: String): String {
        if (num.isBlank()) return "an unknown number"
        if (Prefs.contactsOn(this)) PragonContacts.findByNumber(this, num)?.let { return it.name }
        if (Perm.has(this, android.Manifest.permission.READ_CONTACTS)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
                contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() }?.let { n -> return n }
                }
            } catch (e: Exception) { }
        }
        return num
    }

    private fun onRinging() {
        if (handling || !PragonPA.on(this)) return
        handling = true
        pendingAi = false
        aiUsed = false
        synchronized(info) { for (k in info.keys().asSequence().toList()) info.remove(k) }
        transcript.setLength(0)
        callerNumber = lastNumber ?: ""
        callerLabel = nameFor(callerNumber)
        FloatService.pauseForExternalMic(true)     // one microphone user at a time
        postRingNotification()
        Speaker.get(this).speak("Call from $callerLabel, ${Prefs.callMe(this)}. Say OK to attend, ignore, or reject.")
        main.postDelayed({ listen() }, 4500)
    }

    private fun onOffhook() {
        if (pendingAi && handling) {
            pendingAi = false
            stopListen()
            main.postDelayed({ startAi() }, 700)
        }
    }

    private fun onIdle() {
        val wasAi = aiUsed
        ringing = false
        pendingAi = false
        stopListen()
        stopAi()
        AudioRoute.forced = null
        AudioRoute.release(this)
        cancelNote(NID_CALL)
        if (handling || wasAi) FloatService.pauseForExternalMic(false)
        if (handling && wasAi) finishSummary()
        handling = false
        aiUsed = false
        lastNumber = null
    }

    // ── asking you what to do ─────────────────────────────────────────────

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val d = V9Voice.decision(alts)
            if (d != null) decide(d) else main.postDelayed({ listen() }, 300)
        }
        override fun onError(error: Int) { main.postDelayed({ listen() }, 800) }
    }

    private fun listen() {
        if (!ringing || !handling || pendingAi) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            if (rec == null) rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recListener) }
            rec?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                Prefs.speechLang(this@PragonPAService).takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            })
        } catch (e: Exception) {
            try { rec?.destroy() } catch (x: Exception) { }
            rec = null
            main.postDelayed({ listen() }, 1500)
        }
    }

    private fun stopListen() { try { rec?.cancel() } catch (e: Exception) { } }

    private fun say(t: String) { Speaker.get(this).speak(t) }

    private fun decide(d: String) {
        if (!handling) return
        stopListen()
        val me = Prefs.callMe(this)
        when (d) {
            "ok" -> {
                cancelNote(NID_CALL)
                pendingAi = true
                val ok = try {
                    if (Perm.has(this, android.Manifest.permission.ANSWER_PHONE_CALLS)) {
                        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall(); true
                    } else false
                } catch (e: Exception) { false }
                if (!ok) say("I can't pick up by myself, $me. Answer the call and I'll take it from there.")
            }
            "ignore" -> {
                cancelNote(NID_CALL)
                say("Leaving it to ring, $me.")
            }
            "reject" -> {
                cancelNote(NID_CALL)
                val ok = try {
                    if (Build.VERSION.SDK_INT >= 28 && Perm.has(this, android.Manifest.permission.ANSWER_PHONE_CALLS)) {
                        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
                    } else false
                } catch (e: Exception) { false }
                say(if (ok) "Call rejected, $me." else "I couldn't cut it, $me. Decline it on the screen.")
            }
        }
    }

    // ── Pragon talks to the caller ────────────────────────────────────────

    private fun startAi() {
        if (live != null) return
        aiUsed = true
        PragonPA.aiOnCall = true
        AudioRoute.forced = AudioRoute.SPEAKER      // the caller can only hear Pragon through the loudspeaker
        val owner = PragonMemory.find(this, "name") ?: "the owner"
        val s = LiveSession(applicationContext, object : LiveSession.Listener {
            override fun onState(state: LiveSession.State) {}
            override fun onUserText(text: String) { synchronized(transcript) { transcript.append("Caller: ").append(text).append('\n') } }
            override fun onPragonText(text: String) { synchronized(transcript) { transcript.append("Pragon: ").append(text).append('\n') } }
            override fun onError(message: String) {
                main.post {
                    android.util.Log.e("PragonPA", message)
                    say("I couldn't handle that call, ${Prefs.callMe(this@PragonPAService)}. $message Please take over.")
                    takeover()
                }
            }
            override fun onShutdownRequested() { main.post { hangUp() } }
            override fun onMuteRequested() {}
        })
        s.callPrompt = PragonPA.callPrompt(owner)
        s.onCallTool = { name, args ->
            when (name) {
                "save_call_info" -> {
                    synchronized(info) {
                        for (k in listOf("caller_name", "purpose", "urgency", "callback", "scam")) {
                            val v = args.optString(k).trim()
                            if (v.isNotEmpty()) info.put(k, v)
                        }
                    }
                    "Saved."
                }
                "takeover_owner" -> { main.post { takeover() }; "Handing the call to the owner." }
                else -> "ok"
            }
        }
        live = s
        postTalkingNotification()
        s.start("The call is connected and the caller can hear you. Introduce yourself now and ask who is calling.")
    }

    private fun stopAi() {
        val l = live
        live = null
        PragonPA.aiOnCall = false
        try { l?.stop() } catch (e: Exception) { }
    }

    private fun hangUp() {
        stopAi()
        try {
            if (Build.VERSION.SDK_INT >= 28 && Perm.has(this, android.Manifest.permission.ANSWER_PHONE_CALLS))
                (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
        } catch (e: Exception) { }
    }

    /** The owner wants the live call: Pragon stops talking, the audio goes back to the normal earpiece. */
    fun takeover(): Boolean {
        if (live == null && !PragonPA.aiOnCall) return false
        stopAi()
        AudioRoute.forced = null
        AudioRoute.release(this)
        cancelNote(NID_CALL)
        return true
    }

    // ── summary ───────────────────────────────────────────────────────────

    private fun finishSummary() {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("from", callerLabel).put("number", callerNumber)
        synchronized(info) { for (k in info.keys()) o.put(k, info.get(k)) }
        o.put("transcript", synchronized(transcript) { transcript.toString().take(1500) })
        PragonPA.addSummary(this, o)
        val text = PragonPA.line(o)
        try {
            val n = Notification.Builder(this, CH_CALL).setContentTitle("Pragon PA call summary")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.sym_action_call).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(NID_SUM, n)
        } catch (e: Exception) { }
        say("Call summary, ${Prefs.callMe(this)}. $text")
    }

    // ── notifications ─────────────────────────────────────────────────────

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, PragonPAService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun postRingNotification() {
        try {
            val n = Notification.Builder(this, CH_CALL).setContentTitle("Call from $callerLabel")
                .setContentText("OK = attend, ignore = leave it ringing, reject = cut.")
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call, "OK", pi(ACTION_OK, 31)).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "IGNORE", pi(ACTION_IGNORE, 32)).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "REJECT", pi(ACTION_REJECT, 33)).build())
                .build()
            getSystemService(NotificationManager::class.java).notify(NID_CALL, n)
        } catch (e: Exception) { }
    }

    private fun postTalkingNotification() {
        try {
            val n = Notification.Builder(this, CH_CALL).setContentTitle("Pragon is on the call with $callerLabel")
                .setContentText("Tap TAKE OVER, tap the bubble, or say \"Pragon takeover\".")
                .setSmallIcon(android.R.drawable.sym_action_call).setOngoing(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call, "TAKE OVER", pi(ACTION_TAKEOVER, 34)).build())
                .build()
            getSystemService(NotificationManager::class.java).notify(NID_CALL, n)
        } catch (e: Exception) { }
    }

    private fun cancelNote(id: Int) { try { getSystemService(NotificationManager::class.java).cancel(id) } catch (e: Exception) { } }
}
