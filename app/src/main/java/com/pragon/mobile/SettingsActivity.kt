package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Settings screen (assets/settings.html): pairing (QR / manual), permissions, forget this PC. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pairing = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon."); return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    /** Android's own "Allow Pragon to set up a VPN connection?" screen (needed once for the firewall). */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Firewall.start(this)
            toast("Firewall on. " + Firewall.describe(this))
        } else {
            Guard.setFirewallOn(this, false)
            toast("The firewall needs your OK to run.")
        }
        js("loadSec()")
    }

    private fun askVpn() {
        val need = Firewall.prepareIntent(this)
        if (need == null) { Firewall.start(this); js("loadSec()") } else vpnConsent.launch(need)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("ask_vpn", false) == true) {
            // came from "firewall on" by voice: show Android's consent screen straight away
            if (Guard.firewallApps(this).isNotEmpty()) window.decorView.post { askVpn() }
        }
        web = WebView(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            settings.javaScriptEnabled = true
            // Without a WebChromeClient, JavaScript confirm() silently returns false: every DELETE button did nothing.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }
            }
            addJavascriptInterface(Bridge2(), "PragonNative")
            loadUrl("file:///android_asset/settings.html")
        }
        setContentView(web)
    }

    inner class Bridge2 {
        @JavascriptInterface fun getState(): String {
            val pm = getSystemService(PowerManager::class.java)
            val host = Prefs.host(this@SettingsActivity)
            return JSONObject()
                .put("paired", Bridge.status.startsWith("Connected"))
                .put("host", if (host.isBlank()) "" else "$host:${Prefs.port(this@SettingsActivity)}")
                .put("status", Bridge.status)
                .put("a11y", PragonAccessibilityService.instance != null)
                .put("overlay", Settings.canDrawOverlays(this@SettingsActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .toString()
        }
        @JavascriptInterface fun scanQr() {
            runOnUiThread {
                scan.launch(
                    ScanOptions().setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            }
        }
        @JavascriptInterface fun connect(hostPort: String, key: String) {
            val parts = hostPort.trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else runOnUiThread { startPairing(host, port, key) }
        }
        @JavascriptInterface fun getAi(): String {
            val own = Prefs.aiKey(this@SettingsActivity)
            return JSONObject()
                .put("own", own.isNotBlank())
                .put("pc", Prefs.pcAiKey(this@SettingsActivity).isNotBlank())
                .put("model", Prefs.aiModel(this@SettingsActivity))
                .put("hint", if (own.length > 8) own.take(4) + "..." + own.takeLast(3) else "")
                .toString()
        }
        @JavascriptInterface fun saveAi(key: String, model: String) {
            val cur = Prefs.aiKey(this@SettingsActivity)
            Prefs.saveAi(this@SettingsActivity, if (key.isBlank()) cur else key.trim(), model.trim())
            toast("Saved.")
        }
        // ---- AI engine (Gemini online / Ollama / custom OpenAI-compatible) ----
        @JavascriptInterface fun getEngine(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("engine", Prefs.engine(c))
                .put("ollamaHost", Prefs.ollamaHost(c))
                .put("ollamaModel", Prefs.ollamaModel(c))
                .put("openaiBase", Prefs.openaiBase(c))
                .put("openaiModel", Prefs.openaiModel(c))
                .put("openaiKeySet", Prefs.openaiKey(c).isNotBlank())
                .toString()
        }
        @JavascriptInterface fun saveEngine(engine: String, oHost: String, oModel: String, base: String, key: String, cModel: String) {
            val c = this@SettingsActivity
            Prefs.saveEngine(
                c, engine, oHost.trim(), oModel.trim(), base.trim(),
                if (key.isBlank()) null else key.trim(), cModel.trim()
            )
            toast("Saved. Engine: " + Prefs.engineLabel(c))
        }
        @JavascriptInterface fun clearEngineKey() {
            val c = this@SettingsActivity
            Prefs.saveEngine(c, Prefs.engine(c), Prefs.ollamaHost(c), Prefs.ollamaModel(c), Prefs.openaiBase(c), "", Prefs.openaiModel(c))
            toast("Custom server key removed.")
        }
        /** Lists the models the server has (Ollama /api/tags or OpenAI /models). Answers via pmModels(json). */
        @JavascriptInterface fun listModels(engine: String, oHost: String, base: String, key: String) {
            Thread {
                val names = JSONArray()
                var err = ""
                try {
                    if (engine == "openai") {
                        val rb = Request.Builder().url(LocalLlmClient.openaiBase(base) + "/models")
                        val k = if (key.isBlank()) Prefs.openaiKey(this@SettingsActivity) else key
                        if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
                        LocalLlmClient.http.newCall(rb.build()).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("data")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("id"))
                            }
                        }
                    } else {
                        val req = Request.Builder().url(LocalLlmClient.ollamaBase(oHost) + "/api/tags").build()
                        LocalLlmClient.http.newCall(req).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("models")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("name"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    err = e.message ?: "can't connect"
                }
                js("pmModels(" + JSONObject().put("models", names).put("error", err).toString() + ")")
            }.start()
        }
        /** Lists installed Ollama models with sizes. Answers via pmInstalled(json). */
        @JavascriptInterface fun listOllama(oHost: String) {
            Thread {
                val arr = JSONArray()
                var err = ""
                try {
                    for (m in OllamaTools.list(OllamaTools.base(this@SettingsActivity, oHost)))
                        arr.put(JSONObject().put("name", m.name).put("size", OllamaTools.fmtSize(m.size)))
                } catch (e: Exception) { err = e.message ?: "can't connect" }
                js("pmInstalled(" + JSONObject().put("models", arr).put("error", err).put("current", Prefs.ollamaModel(this@SettingsActivity)).toString() + ")")
            }.start()
        }
        /** Downloads a model on the Ghost server (anything `ollama pull` accepts). Progress via pmPullProgress(pct, text, done). */
        @JavascriptInterface fun pullModel(oHost: String, model: String) {
            val name = model.trim()
            if (name.isEmpty()) { toast("Type a model name to pull."); return }
            Thread {
                var last = 0L
                val err = OllamaTools.pull(OllamaTools.base(this@SettingsActivity, oHost), name) { st, pct, done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 350 || st == "success") {
                        last = now
                        val size = if (total > 0) " - " + OllamaTools.fmtSize(done) + " of " + OllamaTools.fmtSize(total) else ""
                        js("pmPullProgress(" + pct + "," + JSONObject.quote(st + size) + ",false)")
                    }
                }
                if (err == null) js("pmPullProgress(100," + JSONObject.quote("Done - $name is ready.") + ",true)")
                else js("pmPullProgress(-1," + JSONObject.quote("Failed: $err") + ",true)")
            }.start()
        }
        @JavascriptInterface fun deleteModel(oHost: String, model: String) {
            Thread {
                val err = OllamaTools.delete(OllamaTools.base(this@SettingsActivity, oHost), model.trim())
                toast(if (err == null) "Deleted ${model.trim()}." else "Couldn't delete: $err")
                listOllama(oHost)
            }.start()
        }
        /** Makes this the model Pragon uses (and switches the engine to Ollama). */
        @JavascriptInterface fun useModel(oHost: String, model: String) {
            val c = this@SettingsActivity
            Prefs.saveEngine(c, "ollama", oHost.trim().ifBlank { Prefs.ollamaHost(c) }, model.trim(), Prefs.openaiBase(c), null, Prefs.openaiModel(c))
            Brain.warmup(c)
            toast("Now using ${model.trim()} on Ghost.")
        }

        // ---- security: privacy shield, scanner, firewall, allowed apps, sidebar, macros ----
        @JavascriptInterface fun getSecurity(): String {
            val c = this@SettingsActivity
            val log = JSONArray()
            Guard.connectionLog().take(25).forEach { log.put(JSONObject().put("host", it.host).put("allowed", it.allowed).put("count", it.count)) }
            return Guard.summary(c)
                .put("log", log)
                .put("fwRunning", Firewall.running())
                .put("fwDropped", Firewall.droppedPackets)
                .put("fwApps", Guard.firewallApps(c).size)
                .put("allowedCount", Guard.allowedApps(c).size)
                .put("secure", AccessFeatures.canWriteSecure(c))
                .put("writeSettings", Settings.System.canWrite(c))
                .put("grantCmd", AccessFeatures.GRANT_CMD)
                .put("sidebarOn", SidebarService.running)
                .put("sidebarLeft", SidebarService.leftSide(c))
                .toString()
        }
        @JavascriptInterface fun setSwitch(name: String, on: Boolean) {
            val c = this@SettingsActivity
            when (name) {
                "privacy" -> Guard.setPrivacy(c, on)
                "allowOnly" -> {
                    if (on && Guard.allowedApps(c).isEmpty()) { toast("Tick at least one allowed app first."); return }
                    Guard.setAllowlist(c, on)
                }
                "screenAI" -> Guard.setScreenReading(c, on)
                "a11y" -> Guard.setA11yFeatures(c, on)
                "macros" -> Guard.setMacros(c, on)
                "mediaFriendly" -> Guard.setMediaFriendly(c, on)
                "typerAuto" -> Guard.setTyperAuto(c, on)
                "liveOn" -> Prefs.setLiveOn(c, on)
                "liveBargeIn" -> Prefs.setLiveBargeIn(c, on)
            }
        }
        @JavascriptInterface fun saveTrusted(csv: String) {
            Guard.setTrustedHosts(this@SettingsActivity, csv.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() })
            toast("Trusted hosts saved.")
        }
        @JavascriptInterface fun clearLog() { Guard.clearLog() }

        /** Every app with a launcher icon, and which lists it is on. */
        @JavascriptInterface fun getApps(): String {
            val c = this@SettingsActivity
            val pm = packageManager
            val allowed = Guard.allowedApps(c); val side = Guard.sidebarApps(c).toSet(); val fw = Guard.firewallApps(c)
            val arr = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
                .forEach { arr.put(JSONObject().put("pkg", it.second).put("label", it.first)
                    .put("allowed", it.second in allowed).put("sidebar", it.second in side).put("fw", it.second in fw)) }
            return arr.toString()
        }
        @JavascriptInterface fun saveApps(kind: String, jsonArr: String) {
            val c = this@SettingsActivity
            val l = try { JSONArray(jsonArr).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { return }
            when (kind) {
                "allowed" -> { Guard.setAllowedApps(c, l); if (l.isEmpty()) Guard.setAllowlist(c, false) }
                "sidebar" -> Guard.setSidebarApps(c, l)
                "fw" -> {
                    Guard.setFirewallApps(c, l)
                    if (Guard.firewallOn(c) && (l.isNotEmpty() || Guard.firewallMode(c) == "allow")) Firewall.start(c)
                    else if (l.isEmpty() && Guard.firewallMode(c) == "block") Firewall.stop(c)
                }
            }
            toast("Saved.")
        }
        @JavascriptInterface fun setFirewall(on: Boolean) {
            val c = this@SettingsActivity
            if (!on) { Firewall.stop(c); toast("Firewall off. Every app has internet again."); return }
            if (Guard.firewallApps(c).isEmpty() && Guard.firewallMode(c) == "block") { toast("Tick the apps to block first."); js("loadSec()"); return }
            runOnUiThread { askVpn() }
        }
        @JavascriptInterface fun setFirewallMode(mode: String) {
            val c = this@SettingsActivity
            Guard.setFirewallMode(c, mode)
            if (Guard.firewallOn(c)) Firewall.start(c)
        }
        @JavascriptInterface fun runScan() {
            Thread {
                val f = try { Scanner.scan(this@SettingsActivity) } catch (e: Exception) { emptyList() }
                js("pmScan(" + Scanner.toJson(f).toString() + ")")
            }.start()
        }
        @JavascriptInterface fun appInfo(pkg: String) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) }
        }
        @JavascriptInterface fun uninstall(pkg: String) {
            runOnUiThread { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }
        }
        @JavascriptInterface fun blockApp(pkg: String) {
            val c = this@SettingsActivity
            Guard.setFirewallMode(c, "block")
            Guard.setFirewallApps(c, Guard.firewallApps(c) + pkg)
            toast("Added to the firewall list. Switch the firewall on to block it.")
            js("loadSec()")
        }
        @JavascriptInterface fun allowWriteSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        @JavascriptInterface fun setSidebar(on: Boolean, left: Boolean) {
            val c = this@SettingsActivity
            val sideChanged = SidebarService.leftSide(c) != left
            SidebarService.setLeft(c, left)
            SidebarService.setAutoStart(c, on)
            if (on) {
                if (SidebarService.running && sideChanged) { SidebarService.stop(c); Thread.sleep(300) }
                SidebarService.start(c)?.let { toast(it) } ?: toast("Sidebar on. Tap the thin bar on the screen edge.")
            } else { SidebarService.stop(c); toast("Sidebar off.") }
            js("loadSec()")
        }
        @JavascriptInterface fun listMacros(): String {
            val c = this@SettingsActivity
            val a = JSONArray()
            Macros.names(c).forEach { a.put(JSONObject().put("name", it).put("steps", Macros.load(c, it).size).put("text", Macros.describe(c, it))) }
            return JSONObject().put("macros", a).put("recording", Macros.recordingName ?: "").put("playing", Macros.playing ?: "").toString()
        }
        @JavascriptInterface fun macroRun(name: String) { Thread { toast(Macros.run(this@SettingsActivity, name)) }.start() }
        @JavascriptInterface fun macroDelete(name: String) { Macros.delete(this@SettingsActivity, name); js("loadMacros()") }
        @JavascriptInterface fun macroRecord(name: String) { toast(Macros.start(this@SettingsActivity, name.trim())); js("loadMacros()") }
        @JavascriptInterface fun macroStop() {
            toast(if (Macros.isRecording) Macros.stop(this@SettingsActivity) else { Macros.cancelPlayback(); "Stopped." })
            js("loadMacros()")
        }

        // ---- battery reminders ----
        @JavascriptInterface fun getBattery(): String {
            val c = this@SettingsActivity
            return JSONObject().put("lowOn", BatteryReminder.lowOn(c)).put("levels", BatteryReminder.lowLevelsText(c))
                .put("target", BatteryReminder.chargeTarget(c)).put("level", BatteryReminder.level(c))
                .put("summary", BatteryReminder.describe(c)).toString()
        }
        @JavascriptInterface fun saveBattery(lowOn: Boolean, levels: String, target: Int) {
            val c = this@SettingsActivity
            BatteryReminder.setLow(c, lowOn, levels)
            BatteryReminder.setChargeTarget(c, target)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
            toast("Battery reminders saved.")
        }
        // ---- v8: reminders, calendar, contacts, weather ----
        @JavascriptInterface fun getDataAccess(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("calendar", Perm.has(c, android.Manifest.permission.READ_CALENDAR))
                .put("contacts", Perm.has(c, android.Manifest.permission.READ_CONTACTS))
                .put("notifications", Notes.notificationsAllowed(c))
                .put("exact", Reminders.exactAllowed(c))
                .put("weather", Guard.trustedHosts(c).contains("api.open-meteo.com") || !Guard.privacyOn(c))
                .put("topics", org.json.JSONArray(Topics.names(c)))
                .toString()
        }
        @JavascriptInterface fun grantDataAccess() {
            val c = this@SettingsActivity
            val want = mutableListOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.READ_CONTACTS)
            if (android.os.Build.VERSION.SDK_INT >= 33) want.add(android.Manifest.permission.POST_NOTIFICATIONS)
            Perm.request(c, *want.toTypedArray())
        }
        @JavascriptInterface fun allowExact() {
            val r = Reminders.act(this@SettingsActivity, JSONObject().put("action", "allow_exact"))
            toast(r.msg)
        }
        @JavascriptInterface fun trustWeather() {
            val c = this@SettingsActivity
            Guard.setTrustedHosts(c, Guard.trustedHosts(c) + listOf("api.open-meteo.com", "geocoding-api.open-meteo.com"))
            toast("Weather servers trusted. Privacy Shield still blocks everything else.")
        }
        // ---- v9: calls, AirPods / speaker, Pragon contacts, Pragon PA ----
        @JavascriptInterface fun getCalls(): String {
            val c = this@SettingsActivity
            val pcs = JSONArray()
            PragonContacts.all(c).forEach { pcs.put(JSONObject().put("name", it.name).put("number", it.number)) }
            val fmt = java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.getDefault())
            val sums = JSONArray()
            PragonPA.summaries(c).reversed().forEach { sums.put(JSONObject().put("at", fmt.format(java.util.Date(it.optLong("t")))).put("text", PragonPA.line(it))) }
            return JSONObject()
                .put("audio", AudioRoute.saved(c)).put("hsMic", AudioRoute.headsetMic(c)).put("headset", AudioRoute.headsetName(c) ?: "")
                .put("contacts", pcs).put("pcOn", Prefs.contactsOn(c)).put("paOn", PragonPA.on(c)).put("permsOk", PragonPA.missing(c).isEmpty())
                .put("summaries", sums).toString()
        }
        @JavascriptInterface fun setAudio(m: String) { toast(V9Actions.run(this@SettingsActivity, "audio_route", m).msg) }
        @JavascriptInterface fun setHeadsetMic(on: Boolean) { toast(V9Actions.run(this@SettingsActivity, "headset_mic", if (on) "on" else "off").msg) }
        @JavascriptInterface fun saveContact(name: String, number: String) {
            Thread { toast(PragonContacts.add(this@SettingsActivity, name, number).msg); js("loadCalls()") }.start()
        }
        @JavascriptInterface fun removeContact(name: String) {
            toast(PragonContacts.remove(this@SettingsActivity, name).msg); js("loadCalls()")
        }
        @JavascriptInterface fun setPA(on: Boolean) {
            val c = this@SettingsActivity
            toast((if (on) PragonPA.enable(c) else PragonPA.disable(c)).msg)
            js("loadCalls()")
        }
        @JavascriptInterface fun grantCallPerms() {
            val c = this@SettingsActivity
            val m = PragonPA.missing(c)
            if (m.isEmpty()) toast("All call permissions are already allowed.") else Perm.request(c, *m.toTypedArray())
        }
        // ---- v10: strict offline, on-device models, speed ----
        @JavascriptInterface fun getOffline(): String = Offline.status(this@SettingsActivity).toString()
        @JavascriptInterface fun setOfflineOnly(on: Boolean) {
            Prefs.setOfflineOnly(this@SettingsActivity, on)
            toast(if (on) "Always-offline lock is ON. No cloud AI, no remote servers, even with internet." else "Lock is OFF. Pragon follows your connection by itself.")
            js("loadOffline()"); js("loadFeatures()")
        }
        // ---- v11: speech model, engine, parallel models, features, Pragon contacts switch ----
        @JavascriptInterface fun downloadSpeechModel() {
            val c = this@SettingsActivity
            when {
                SherpaStt.hasModel(c) -> toast("The offline speech model is already installed.")
                SherpaStt.downloading -> toast("Already downloading: ${SherpaStt.dlState}")
                !Net.online(c) -> toast("Connect to Wi-Fi first: the model downloads once.")
                else -> { SherpaStt.download(c); toast("Downloading the speech model (about 70 MB). Use Wi-Fi.") }
            }
            js("loadOffline()")
        }
        @JavascriptInterface fun setSpeechModelUrl(url: String) { Prefs.setSttModelUrl(this@SettingsActivity, url); toast(if (url.isBlank()) "Speech model link reset to English." else "Speech model link saved. Tap DOWNLOAD SPEECH MODEL.") }
        @JavascriptInterface fun setSttEngine(e: String) {
            val c = this@SettingsActivity
            if (e == "vosk" && !SherpaStt.hasModel(c)) { toast("Download the speech model first."); js("loadOffline()"); return }
            Prefs.setSttEngine(c, e)
            toast(when (e) { "vosk" -> "Using Pragon's own offline speech recognition."; "android" -> "Using Android's speech recognition."; else -> "Automatic speech engine." })
            js("loadOffline()")
        }
        @JavascriptInterface fun setParallel(on: Boolean) { Prefs.setParallelLlm(this@SettingsActivity, on) }
        @JavascriptInterface fun setShowAll(on: Boolean) { Prefs.setShowAllFeatures(this@SettingsActivity, on) }
        @JavascriptInterface fun getFeatures(): String = Features.json(this@SettingsActivity)
        @JavascriptInterface fun setContactsOn(on: Boolean) {
            Prefs.setContactsOn(this@SettingsActivity, on)
            toast(if (on) "Pragon contacts ON." else "Pragon contacts OFF: calls use your phone contacts.")
        }
        @JavascriptInterface fun downloadModel(url: String) {
            val c = this@SettingsActivity
            if (url.isNotBlank()) Prefs.setModelUrl(c, url)
            if (Offline.routerFile(c) != null) toast("The command model is already installed.")
            else { Offline.download(c, Prefs.modelUrl(c)); toast("Downloading the command model (about 280 MB). Use Wi-Fi.") }
            js("loadOffline()")
        }
        @JavascriptInterface fun warmModel() {
            val c = this@SettingsActivity
            if (Offline.routerFile(c) == null) toast("Install the command model first.") else { OnDeviceLlm.warmup(c); toast("Loading the command model...") }
            js("loadOffline()")
        }
        @JavascriptInterface fun speechPack() {
            val c = this@SettingsActivity
            runOnUiThread {
                try {
                    val r = OfflineSpeech.create(c, true)
                    OfflineSpeech.checkPack(c, r) { toast(it) }
                    web.postDelayed({ try { r.destroy() } catch (e: Exception) { } }, 10_000)
                } catch (e: Exception) { toast("Couldn't check the speech pack: ${e.message}") }
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (e: Exception) { }
                }
            }
            js("setTimeout(loadOffline,1500)")
        }
        @JavascriptInterface fun setAutoSend(on: Boolean) { Prefs.setAutoSend(this@SettingsActivity, on) }
        @JavascriptInterface fun speedReport(): String = Metrics.spoken(this@SettingsActivity) + " Memory in use: " + Metrics.memoryMb(this@SettingsActivity) + " MB."
        // ---- mode, voice and memory ----
        @JavascriptInterface fun getMode(): String = Prefs.mode(this@SettingsActivity).key
        @JavascriptInterface fun saveMode(m: String) {
            Prefs.saveMode(this@SettingsActivity, PMode.from(m))
            toast("Mode: " + Prefs.mode(this@SettingsActivity).label)
        }
        @JavascriptInterface fun getVoice(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("callMe", Prefs.callMe(c)).put("speechLang", Prefs.speechLang(c)).put("replyLang", Prefs.replyLang(c))
                .put("rate", Prefs.ttsRate(c).toDouble()).put("pitch", Prefs.ttsPitch(c).toDouble())
                .put("voice", Prefs.ttsVoice(c)).put("speakTyped", Prefs.speakTyped(c))
                .put("floatNeedsWake", Prefs.floatNeedsWake(c)).toString()
        }
        @JavascriptInterface fun saveVoice(
            callMe: String, speechLang: String, replyLang: String, rate: Double, pitch: Double,
            voice: String, speakTyped: Boolean, floatNeedsWake: Boolean,
        ) {
            val c = this@SettingsActivity
            Prefs.saveVoice(c, callMe, speechLang, replyLang, rate.toFloat(), pitch.toFloat(), voice, speakTyped, floatNeedsWake)
            Speaker.get(c).reload()
            toast("Voice settings saved.")
        }
        @JavascriptInterface fun listVoices(): String = Speaker.get(this@SettingsActivity).voicesJson()
        @JavascriptInterface fun nextVoice() {
            val sp = Speaker.get(this@SettingsActivity)
            val t = sp.changeVoice(reset = false)
            sp.speak(t)
            toast(t)
        }
        @JavascriptInterface fun testVoice() { Speaker.get(this@SettingsActivity).also { it.reload(); it.test() } }
        @JavascriptInterface fun getMemory(): String {
            val c = this@SettingsActivity
            return JSONObject().put("count", PragonMemory.count(c)).put("text", PragonMemory.promptBlock(c)).toString()
        }
        @JavascriptInterface fun clearMemory() {
            PragonMemory.clear(this@SettingsActivity)
            toast("Memory cleared.")
        }
        @JavascriptInterface fun clearAiKey() {
            Prefs.saveAi(this@SettingsActivity, "", Prefs.aiModel(this@SettingsActivity))
            toast("Your key was removed.")
        }
        @JavascriptInterface fun openA11y() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Find PragonMobile in the list and turn it on.")
            }
        }
        @JavascriptInterface fun openOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun openBattery() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun forget() {
            runOnUiThread {
                ContextCompat.startForegroundService(
                    this@SettingsActivity,
                    Intent(this@SettingsActivity, PragonService::class.java).setAction(PragonService.ACTION_STOP)
                )
                Prefs.clear(this@SettingsActivity)
                RemoteBridge.socket = null
                Bridge.set("Not paired yet - scan the QR from Pragon")
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    private fun startPairing(host: String, port: Int, key: String) {
        pairing = true
        Prefs.save(this, host, port, "")
        ContextCompat.startForegroundService(
            this, Intent(this, PragonService::class.java).putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        )
        Bridge.set("Pairing with $host:$port ...")
    }

    override fun onResume() {
        super.onResume()
        // After a successful pairing, go straight back to PhoneView.
        Bridge.listener = { s -> if (pairing && s.startsWith("Connected")) finish() }
    }

    override fun onPause() { Bridge.listener = null; super.onPause() }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}
