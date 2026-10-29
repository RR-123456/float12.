package com.pragon.mobile

/**
 * v10: the ONE list of phone tools the on-device model (FunctionGemma 270M) may call.
 * Every tool maps onto an action CommandExecutor already has, so there is a single command-routing layer:
 *
 *   speech -> rules (V10Voice / V9Voice / VoiceParser, instant) -> FunctionGemma (only when the rules miss) -> CommandExecutor
 *
 * The model can ONLY pick from this whitelist. Privacy, firewall, allow-list, accessibility-feature and macro actions are
 * deliberately not here, so a wrong or manipulated model answer can never reach them.
 * No Android classes in this file on purpose: it is unit-testable on a plain JVM.
 */
object ToolCatalog {

    class Param(val name: String, val desc: String, val enum: List<String>? = null, val required: Boolean = true, val type: String = "string")
    class Tool(val name: String, val desc: String, val params: List<Param> = emptyList())

    val KEYS = listOf("home", "back", "recents", "notifications", "quick_settings", "lock", "screenshot",
        "volume_up", "volume_down", "mute", "play", "pause", "next", "previous")
    val DIRS = listOf("up", "down", "left", "right")
    val PAGES = listOf("wifi", "bluetooth", "display", "sound", "battery", "location", "apps", "accessibility", "storage", "language", "date")
    val CHANNELS = listOf("whatsapp", "sms", "telegram")

    // Short descriptions on purpose: the schema is re-read by the model on every request, so every token costs latency.
    val tools: List<Tool> = listOf(
        Tool("open_app", "Open an app", listOf(Param("app_name", "App name"))),
        Tool("close_app", "Close an app", listOf(Param("app_name", "App name"))),
        Tool("press_key", "Press a system or media button", listOf(Param("key", "Button", KEYS))),
        Tool("scroll", "Scroll the screen", listOf(Param("direction", "Direction", DIRS))),
        Tool("tap_text", "Tap the button or text with this label", listOf(Param("text", "Label"))),
        Tool("type_text", "Type text into the focused box", listOf(Param("text", "Text"))),
        Tool("send_message", "Send a message to a contact", listOf(
            Param("contact", "Contact name"), Param("message", "Message"), Param("app", "App", CHANNELS, required = false))),
        Tool("call_contact", "Call a contact or number", listOf(Param("contact", "Name or number"))),
        Tool("set_timer", "Start a timer", listOf(Param("minutes", "Minutes", required = true, type = "number"))),
        Tool("set_alarm", "Set an alarm", listOf(Param("time", "Time, like 7:30 am"))),
        Tool("flashlight", "Flashlight on or off", listOf(Param("state", "State", listOf("on", "off")))),
        Tool("open_settings", "Open a settings page", listOf(Param("page", "Page", PAGES))),
        Tool("read_screen", "Read the visible screen text aloud"),
    )

    fun byName(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** OpenAPI-style JSON for one tool (the format LiteRT-LM's OpenApiTool wants). Compact on purpose. */
    fun schemaJson(t: Tool): String {
        val props = t.params.joinToString(",") { p ->
            val en = p.enum?.joinToString(",", prefix = ",\"enum\":[", postfix = "]") { "\"$it\"" } ?: ""
            "\"${p.name}\":{\"type\":\"${p.type}\",\"description\":\"${esc(p.desc)}\"$en}"
        }
        val req = t.params.filter { it.required }.joinToString(",") { "\"${it.name}\"" }
        return "{\"name\":\"${t.name}\",\"description\":\"${esc(t.desc)}\",\"parameters\":{\"type\":\"object\",\"properties\":{$props},\"required\":[$req]}}"
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Checks a model's tool call and turns it into the action CommandExecutor runs. null = not a valid call
     * (unknown tool, missing or absurd arguments, value not in the allowed list): the caller then falls back to chat.
     */
    fun toAction(name: String, args: Map<String, String>): VoiceParser.Action? {
        val tool = byName(name) ?: return null
        val a = HashMap<String, String>()
        for ((k, v) in args) a[k] = v.trim()
        for (p in tool.params) {
            val v = a[p.name].orEmpty()
            if (p.required && v.isEmpty()) return null
            if (v.length > 300) return null
            if (p.enum != null && v.isNotEmpty() && v.lowercase() !in p.enum) return null
        }
        fun s(k: String) = a[k].orEmpty()
        return when (name) {
            "open_app" -> VoiceParser.Action("open_app", s("app_name"))
            "close_app" -> VoiceParser.Action("close_app", s("app_name"))
            "press_key" -> VoiceParser.Action("key", s("key").lowercase())
            "scroll" -> {
                // "scroll down" = move the content up = swipe up (same rule the voice parser uses)
                val d = s("direction").lowercase()
                VoiceParser.Action("swipe", when (d) { "down" -> "up"; "up" -> "down"; else -> d })
            }
            "tap_text" -> VoiceParser.Action("click", s("text"))
            "type_text" -> VoiceParser.Action("type_text", s("text"))
            "send_message" -> VoiceParser.Action("send_message", s("contact") + "|" + s("message") + "|" + s("app").lowercase())
            "call_contact" -> VoiceParser.Action("call", s("contact"))
            "set_timer" -> {
                val m = s("minutes").toDoubleOrNull() ?: return null
                if (m <= 0 || m > 24 * 60) return null
                VoiceParser.Action("timer", Math.round(m * 60).toString())
            }
            "set_alarm" -> VoiceParser.Action("alarm", s("time"))
            "flashlight" -> VoiceParser.Action("flashlight", s("state").lowercase())
            "open_settings" -> VoiceParser.Action("settings", s("page").lowercase())
            "read_screen" -> VoiceParser.Action("screen_read")
            else -> null
        }
    }

    private val COMMAND_WORDS = Regex(
        "\\b(open|close|launch|start|go|scroll|swipe|tap|click|press|type|write|send|text|message|whatsapp|call|dial|take|read|turn|switch|set|play|pause|" +
            "stop|next|previous|volume|brightness|back|home|show|lock|mute|louder|quieter|alarm|timer|wake|flashlight|torch|screenshot|settings|wifi|bluetooth)\\b")

    /** Cheap gate so chit-chat never pays for a model run. */
    fun looksLikeCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty() || t.length > 200) return false
        return COMMAND_WORDS.containsMatchIn(t)
    }
}
