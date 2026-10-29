package com.pragon.mobile

/**
 * v9 offline phrases (no AI key needed): calling, Pragon contacts, speaker / earpiece / AirPods, headset mic, Pragon PA.
 * No Android classes in here on purpose, so it can be tested on a plain JVM.
 *
 * parse() returns an Action: call (value = number or name), pc_add ("name|number"), pc_remove (name), pc_list,
 * audio_route (speaker / earpiece / auto / toggle), headset_mic (on / off), pa (on / off / status / takeover / summary).
 */
object V9Voice {

    /** Lower-case, keeps the characters a phone number needs (+ * #). */
    fun light(s: String): String = s.lowercase()
        .replace("\u2019", "").replace("'", "")
        .replace(Regex("[^a-z0-9+*# ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val DIGITS = mapOf(
        "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )

    /** "98765 43210", "+91 98765 43210", "nine eight seven...", "double nine", "star 123 hash" -> "9876543210" etc., or null. */
    fun dialable(text: String): String? {
        val t = light(text)
        if (t.isEmpty()) return null
        val sb = StringBuilder()
        var mult = 1
        for (tok in t.split(' ')) {
            when {
                tok == "double" -> mult = 2
                tok == "triple" -> mult = 3
                tok == "plus" -> { if (sb.isNotEmpty()) return null; sb.append('+') }
                tok == "star" -> { sb.append('*'); mult = 1 }
                tok == "hash" || tok == "pound" -> { sb.append('#'); mult = 1 }
                DIGITS.containsKey(tok) -> { repeat(mult) { sb.append(DIGITS[tok]) }; mult = 1 }
                tok.all { it in "0123456789+*#" } -> {
                    if (tok.contains('+') && (sb.isNotEmpty() || tok.indexOf('+') != 0 || tok.lastIndexOf('+') != 0)) return null
                    if (mult > 1 && tok.length == 1) repeat(mult) { sb.append(tok) } else sb.append(tok)
                    mult = 1
                }
                else -> return null
            }
        }
        if (mult != 1) return null
        return if (sb.length >= 3) sb.toString() else null
    }

    private const val P = "(?:pragon|paragon|pragan|pragun)"
    private const val SPK = "(?:speaker|speakerphone|speaker phone|loudspeaker|loud speaker)"
    private const val VERB = "(?:use|switch to|change to|turn on|enable|activate|go to|put it on|set to)"
    private const val HS = "(?:airpods|air pods|headset|bluetooth|earbuds|earphones)"
    private const val MIC = "(?:mic|microphone)"

    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please)\\s+)+")
    private val LEAD_P = Regex("^$P\\b\\s*")
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$")

    private val CALL = Regex("(?:please\\s+)?(?:call|dial)\\s+(.+)")
    private val NOT_A_TARGET = Regex("\\b(?:me|it|back|later|them|us|this|that|a|an|the|off|out|time|home|up|over)\\b")
    private val CHANNELS = Regex("\\b(?:whatsapp|telegram|video|sms|message|text|facetime|zoom|meet|duo|skype|signal)\\b")

    private val ADD = Regex("(?:please\\s+)?(?:add|save|store)\\s+(.+?)\\s+(?:to|in|into)\\s+(?:my\\s+|the\\s+)?$P\\s+contacts?(?:\\s+list)?")
    private val REMOVE = Regex("(?:please\\s+)?(?:remove|delete)\\s+(.+?)\\s+from\\s+(?:my\\s+|the\\s+)?$P\\s+contacts?(?:\\s+list)?")
    private val LIST = Regex("(?:list|show|read)\\s+(?:out\\s+)?(?:my\\s+|the\\s+)?$P\\s+contacts?(?:\\s+list)?")
    private val LIST_NAMED = Regex("(?:(?:list|show|read)\\s+(?:out\\s+)?(?:my\\s+|the\\s+)?)?contacts?(?:\\s+list)?")
    private val NAME_NOISE = Regex("^(?:the\\s+)?(?:contact\\s+)?(?:named\\s+|name\\s+)?")
    private val NUMBER_NOISE = Regex("\\b(?:number|phone|mobile)\\b")

    private val TOGGLE = Regex("(?:toggle|flip)\\s+(?:the\\s+)?$SPK")
    private val SPEAKER = Regex("$VERB\\s+(?:the\\s+|my\\s+)?$SPK(?:\\s+mode)?|(?:the\\s+)?$SPK\\s+mode")
    private val EARPIECE = Regex("$VERB\\s+(?:the\\s+|my\\s+)?(?:earpiece|ear piece|ear speaker)(?:\\s+mode)?|(?:the\\s+)?(?:earpiece|ear piece|ear speaker)\\s+mode|(?:use|switch to|go to|back to)\\s+normal\\s+mode|normal\\s+mode")
    private val AUTO = Regex("(?:use|switch to)\\s+(?:my\\s+|the\\s+)?$HS(?:\\s+audio)?|(?:automatic|auto)\\s+(?:audio|sound|route|routing|mode|output)")
    private val HMIC_ON = Regex("(?:turn on|enable|use)\\s+(?:the\\s+|my\\s+)?$HS\\s+$MIC|$HS\\s+$MIC\\s+on")
    private val HMIC_OFF = Regex("(?:turn off|disable|mute)\\s+(?:the\\s+|my\\s+)?$HS\\s+$MIC|$HS\\s+$MIC\\s+off|use\\s+(?:the\\s+)?phone\\s+$MIC")

    private val PA_ON = Regex("(?:turn on|enable|activate|start|switch on)\\s+(?:$P\\s+)?pa|(?:$P\\s+)?pa\\s+on|turn\\s+(?:$P\\s+)?pa\\s+on")
    private val PA_OFF = Regex("(?:turn off|disable|deactivate|stop|switch off)\\s+(?:$P\\s+)?pa|(?:$P\\s+)?pa\\s+off|turn\\s+(?:$P\\s+)?pa\\s+off")
    private val PA_STATUS = Regex("(?:$P\\s+)?pa\\s+status")
    private val TAKEOVER = Regex("(?:$P\\s+)?take\\s?over(?:\\s+the\\s+call)?")
    private val SUMMARY = Regex("(?:read|show|tell me)\\s+(?:me\\s+)?(?:my\\s+|the\\s+)?(?:call\\s+)?summar(?:y|ies)|who called(?:\\s+me)?|(?:my\\s+)?call summar(?:y|ies)")

    private val D_REJECT = Regex("\\b(?:reject|decline|cut|hang up|end it|dont pick|do not pick|disconnect|no)\\b")
    private val D_IGNORE = Regex("\\b(?:ignore|let it ring|leave it|skip)\\b")
    private val D_OK = Regex("\\b(?:ok|okay|yes|answer|attend|pick up|accept|sure|go ahead)\\b")

    private fun splitNameNumber(s0: String): Pair<String, String> {
        var s = NAME_NOISE.replaceFirst(s0.trim(), "")
        s = NUMBER_NOISE.replace(s, " ").replace(Regex("\\s+"), " ").trim()
        val toks = s.split(' ').filter { it.isNotEmpty() }
        for (i in 1 until toks.size) {
            val n = dialable(toks.subList(i, toks.size).joinToString(" "))
            if (n != null && !n.contains('*') && !n.contains('#')) return toks.subList(0, i).joinToString(" ") to n
        }
        return toks.joinToString(" ") to ""
    }

    fun parse(raw: String): VoiceParser.Action? {
        val l0 = light(raw)
        if (l0.isEmpty()) return null
        var l = LEAD.replaceFirst(l0, "")
        val named = LEAD_P.containsMatchIn(l)
        l = LEAD_P.replaceFirst(l, "")
        l = TRAIL.replace(l, "").trim()
        if (l.isEmpty()) return null

        CALL.matchEntire(l)?.let {
            val t = it.groupValues[1].trim().removePrefix("number ").trim()
            if (t.isNotEmpty() && !NOT_A_TARGET.containsMatchIn(t) && !CHANNELS.containsMatchIn(t)) {
                val d = dialable(t)
                if (d != null) return VoiceParser.Action("call", d)
                if (t.split(' ').size <= 3) return VoiceParser.Action("call", t)
            }
        }
        ADD.matchEntire(l)?.let {
            val (n, num) = splitNameNumber(it.groupValues[1])
            if (n.isNotEmpty()) return VoiceParser.Action("pc_add", "$n|$num")
        }
        REMOVE.matchEntire(l)?.let {
            val n = NAME_NOISE.replaceFirst(it.groupValues[1].trim(), "").trim()
            if (n.isNotEmpty()) return VoiceParser.Action("pc_remove", n)
        }
        if (LIST.matches(l) || (named && LIST_NAMED.matches(l))) return VoiceParser.Action("pc_list")
        if (TOGGLE.matches(l)) return VoiceParser.Action("audio_route", "toggle")
        if (SPEAKER.matches(l)) return VoiceParser.Action("audio_route", "speaker")
        if (EARPIECE.matches(l)) return VoiceParser.Action("audio_route", "earpiece")
        if (AUTO.matches(l)) return VoiceParser.Action("audio_route", "auto")
        if (HMIC_ON.matches(l)) return VoiceParser.Action("headset_mic", "on")
        if (HMIC_OFF.matches(l)) return VoiceParser.Action("headset_mic", "off")
        if (PA_ON.matches(l)) return VoiceParser.Action("pa", "on")
        if (PA_OFF.matches(l)) return VoiceParser.Action("pa", "off")
        if (PA_STATUS.matches(l)) return VoiceParser.Action("pa", "status")
        if (TAKEOVER.matches(l)) return VoiceParser.Action("pa", "takeover")
        if (SUMMARY.matches(l)) return VoiceParser.Action("pa", "summary")
        return null
    }

    /** What the user said while a call is ringing: "ok" (attend), "ignore" (leave ringing), "reject" (cut), or null. */
    fun decision(alts: List<String>): String? {
        for (a in alts) {
            val t = light(a)
            if (t.isEmpty()) continue
            if (D_REJECT.containsMatchIn(t)) return "reject"
            if (D_IGNORE.containsMatchIn(t)) return "ignore"
            if (D_OK.containsMatchIn(t)) return "ok"
        }
        return null
    }
}
