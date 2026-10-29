import com.pragon.mobile.*
var fails = 0
var total = 0
fun v(inp: String, action: String?, value: String? = null) {
    total++
    val r = V10Voice.parse(inp)
    val ok = if (action == null) r == null else (r != null && r.action == action && (value == null || r.value == value))
    if (!ok) { fails++; println("FAIL V10 '$inp' -> ${r?.action}/${r?.value} (want $action/$value)") }
}
fun t(name: String, args: Map<String, String>, action: String?, value: String? = null) {
    total++
    val r = ToolCatalog.toAction(name, args)
    val ok = if (action == null) r == null else (r != null && r.action == action && (value == null || r.value == value))
    if (!ok) { fails++; println("FAIL tool $name $args -> ${r?.action}/${r?.value} (want $action/$value)") }
}
fun c(text: String, want: Boolean) {
    total++
    if (ToolCatalog.looksLikeCommand(text) != want) { fails++; println("FAIL looksLikeCommand '$text' want $want") }
}
fun main() {
    // messages
    v("send a message to Ravi saying I'll be late", "send_message", "Ravi|I'll be late|")
    v("Send message to Priya on WhatsApp saying are you free tonight?", "send_message", "Priya|are you free tonight|whatsapp")
    v("pragon send a message to Ravi that I am running late", "send_message", "Ravi|I am running late|")
    v("send hi to Ravi on whatsapp", "send_message", "Ravi|hi|whatsapp")
    v("text Ravi saying call me", "send_message", "Ravi|call me|sms")
    v("whatsapp Priya saying good morning", "send_message", "Priya|good morning|whatsapp")
    v("message Ravi saying hello there", "send_message", "Ravi|hello there|")
    v("send a text message to Mom saying dinner is ready", "send_message", "Mom|dinner is ready|sms")
    v("send a whatsapp message to Dad saying I reached", "send_message", "Dad|I reached|whatsapp")
    // must NOT be messages
    v("text me when you are ready", null); v("send a message to", null); v("message from ravi", null); v("call ravi", null)
    v("open whatsapp", null); v("send me a photo", null)
    // info commands
    v("how fast are you", "metrics"); v("latency report", "metrics"); v("memory usage", "metrics")
    v("are you offline", "offline", "status"); v("offline status", "offline", "status"); v("go offline", "offline", "on")
    v("download the model", "offline", "download"); v("download the offline model", "offline", "download"); v("load the model", "offline", "warm")
    v("what can you do", "help"); v("help", "help"); v("list commands", "help")
    v("help me with my homework", null); v("what can you do about this", null)

    // tool-call validation (the model's answer is never trusted blindly)
    t("open_app", mapOf("app_name" to "YouTube"), "open_app", "YouTube")
    t("open_app", mapOf(), null); t("open_app", mapOf("app_name" to ""), null)
    t("scroll", mapOf("direction" to "down"), "swipe", "up"); t("scroll", mapOf("direction" to "up"), "swipe", "down"); t("scroll", mapOf("direction" to "sideways"), null)
    t("press_key", mapOf("key" to "home"), "key", "home"); t("press_key", mapOf("key" to "screenshot"), "key", "screenshot"); t("press_key", mapOf("key" to "format_phone"), null)
    t("set_timer", mapOf("minutes" to "5"), "timer", "300"); t("set_timer", mapOf("minutes" to "0.5"), "timer", "30"); t("set_timer", mapOf("minutes" to "-3"), null); t("set_timer", mapOf("minutes" to "abc"), null); t("set_timer", mapOf("minutes" to "99999"), null)
    t("send_message", mapOf("contact" to "Ravi", "message" to "hi"), "send_message", "Ravi|hi|")
    t("send_message", mapOf("contact" to "Ravi", "message" to "hi", "app" to "whatsapp"), "send_message", "Ravi|hi|whatsapp")
    t("send_message", mapOf("contact" to "Ravi", "message" to "hi", "app" to "snapchat"), null); t("send_message", mapOf("contact" to "Ravi"), null)
    t("flashlight", mapOf("state" to "on"), "flashlight", "on"); t("flashlight", mapOf("state" to "maybe"), null)
    t("open_settings", mapOf("page" to "wifi"), "settings", "wifi"); t("open_settings", mapOf("page" to "developer"), null)
    t("tap_text", mapOf("text" to "Subscribe"), "click", "Subscribe"); t("type_text", mapOf("text" to "hello"), "type_text", "hello"); t("read_screen", mapOf(), "screen_read")
    t("call_contact", mapOf("contact" to "Dad"), "call", "Dad"); t("set_alarm", mapOf("time" to "7:30 am"), "alarm", "7:30 am")
    // privileged actions can never be reached through the model
    for (bad in listOf("privacy", "allowlist", "firewall", "a11y_feature", "macro_record", "force_stop", "close_all", "pa", "offline", "ollama_pull"))
        t(bad, mapOf("value" to "off", "app_name" to "x"), null)
    t("open_app", mapOf("app_name" to "x".repeat(400)), null)
    // schema text
    for (tool in ToolCatalog.tools) {
        total++
        val j = ToolCatalog.schemaJson(tool)
        if (j.count { it == '{' } != j.count { it == '}' } || j.count { it == '[' } != j.count { it == ']' } || !j.contains("\"name\":\"${tool.name}\"")) { fails++; println("FAIL schema ${tool.name}: $j") }
    }
    // gate: commands reach the model, chit-chat does not
    c("please open the youtube thing", true); c("make it louder", true); c("take me to the home screen", true); c("tap on subscribe", true)
    c("how are you", false); c("who is the president of france", false); c("tell me a joke", false); c("", false)
    println(if (fails == 0) "ALL $total V10 TESTS PASSED" else "$fails of $total FAILED")
    if (fails != 0) System.exit(1)
}
