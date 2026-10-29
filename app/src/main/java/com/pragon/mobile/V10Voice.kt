package com.pragon.mobile

/**
 * v10 offline phrases (no model, no AI key): sending messages, speed report, offline status, help.
 * No Android classes in here on purpose, so it can be tested on a plain JVM (see tests/v10voice_regex_check.py).
 *
 * parse() returns an Action: send_message (value = "contact|text|channel"), metrics, offline (status / on / download / warm), help.
 */
object V10Voice {

    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please|yo)\\s+)+", RegexOption.IGNORE_CASE)
    private val LEAD_P = Regex("^(?:pragon|paragon|pragan|pragun)\\b[,]?\\s*", RegexOption.IGNORE_CASE)
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$", RegexOption.IGNORE_CASE)

    /** Keeps the person's own capital letters and punctuation inside the message; only trims the ends. */
    private fun tidy(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ")
        s = LEAD.replaceFirst(s, "")
        s = LEAD_P.replaceFirst(s, "")
        s = TRAIL.replace(s, "")
        return s.trim().trimEnd('.', '!', '?', ',').trim()
    }

    fun channel(c: String?): String = when ((c ?: "").lowercase()) {
        "whatsapp", "wa" -> "whatsapp"
        "telegram", "tg" -> "telegram"
        "sms", "text", "messages", "message" -> "sms"
        else -> ""
    }

    private const val NAME = "([a-zA-Z][a-zA-Z.']*(?: [a-zA-Z][a-zA-Z.']*){0,2}?)"
    private const val CH = "(whatsapp|sms|text|telegram)"
    private const val SAYING = "(?:saying|says|that says|that|with the message|with message|:)"
    private const val VIA = "(?:on|via|in|by|through|using)"

    private val SEND_TO = Regex("^(?:send|write|drop|shoot)\\s+(?:a\\s+|an\\s+)?(?:$CH\\s+)?(?:message|msg|text|sms)\\s+to\\s+$NAME(?:\\s+$VIA\\s+$CH)?\\s+$SAYING?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val SEND_X_TO = Regex("^send\\s+(.+?)\\s+to\\s+$NAME\\s+$VIA\\s+$CH$", RegexOption.IGNORE_CASE)
    private val SHORT = Regex("^(whatsapp|text|message|sms|msg)\\s+$NAME(?:\\s+$VIA\\s+$CH)?\\s+$SAYING\\s*(.+)$", RegexOption.IGNORE_CASE)

    private val METRICS = Regex("^(?:how fast are you|latency report|speed report|show (?:the )?latency|show (?:the )?speed|how (?:much )?(?:ram|memory) (?:are you|do you) us(?:ing|e)|memory usage|performance report)$", RegexOption.IGNORE_CASE)
    private val OFF_STATUS = Regex("^(?:are you offline|offline status|offline mode status|status of offline mode|is offline mode on|are you running offline)$", RegexOption.IGNORE_CASE)
    private val OFF_ON = Regex("^(?:go offline|turn on offline mode|offline mode on|enable offline mode|strict offline on|switch to offline mode)$", RegexOption.IGNORE_CASE)
    private val DOWNLOAD = Regex("^(?:download|get|install) (?:the )?(?:offline |local )?(?:ai )?model$", RegexOption.IGNORE_CASE)
    private val WARM = Regex("^(?:load|warm up|wake up|preload) (?:the )?(?:offline |local )?(?:ai )?model$", RegexOption.IGNORE_CASE)
    private val HELP = Regex("^(?:what can you do|help|list (?:your )?commands|what commands do you (?:know|have)|show commands)$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): VoiceParser.Action? {
        val s = tidy(raw)
        if (s.isEmpty()) return null

        SEND_TO.matchEntire(s)?.let {
            val ch1 = it.groupValues[1]; val name = it.groupValues[2]; val ch2 = it.groupValues[3]; val text = it.groupValues[4]
            if (name.isNotBlank() && text.isNotBlank()) return VoiceParser.Action("send_message", "${name.trim()}|${text.trim()}|${channel(ch2.ifEmpty { ch1 })}")
        }
        SEND_X_TO.matchEntire(s)?.let {
            val text = it.groupValues[1]; val name = it.groupValues[2]; val ch = it.groupValues[3]
            if (name.isNotBlank() && text.isNotBlank()) return VoiceParser.Action("send_message", "${name.trim()}|${text.trim()}|${channel(ch)}")
        }
        SHORT.matchEntire(s)?.let {
            val verb = it.groupValues[1]; val name = it.groupValues[2]; val ch = it.groupValues[3]; val text = it.groupValues[4]
            // "whatsapp Ravi saying hi" and "text Ravi saying hi" already name the app; "message Ravi saying hi" does not
            val via = channel(ch.ifEmpty { if (verb.lowercase() in listOf("whatsapp", "text", "sms")) verb else "" })
            if (name.isNotBlank() && text.isNotBlank()) return VoiceParser.Action("send_message", "${name.trim()}|${text.trim()}|$via")
        }
        if (METRICS.matches(s)) return VoiceParser.Action("metrics")
        if (OFF_STATUS.matches(s)) return VoiceParser.Action("offline", "status")
        if (OFF_ON.matches(s)) return VoiceParser.Action("offline", "on")
        if (DOWNLOAD.matches(s)) return VoiceParser.Action("offline", "download")
        if (WARM.matches(s)) return VoiceParser.Action("offline", "warm")
        if (HELP.matches(s)) return VoiceParser.Action("help")
        return null
    }
}
