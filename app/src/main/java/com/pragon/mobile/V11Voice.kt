package com.pragon.mobile

/**
 * v11 phrases that work with no model and no AI key: Pragon contacts ON/OFF, AirPods status, speech engine, features list,
 * parallel models, always-offline lock, plus small everyday helpers (time, date, storage, notes, coin, dice, random number,
 * joke, fun fact). No Android classes in here on purpose, so it can be tested on a plain JVM.
 *
 * Actions: pc_toggle (on/off/status), airpods (status/connect), stt (download/vosk/android/auto/status), features (here/all/available),
 * llm (status/parallel_on/parallel_off), offline (on/off), info (time/date/storage), note (add:<text>/read/clear),
 * fun (coin/dice/random:<a>:<b>/joke/fact).
 */
object V11Voice {

    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please|yo)\\s+)+", RegexOption.IGNORE_CASE)
    private val LEAD_P = Regex("^(?:pragon|paragon|pragan|pragun)\\b[,]?\\s*", RegexOption.IGNORE_CASE)
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$", RegexOption.IGNORE_CASE)

    private fun tidy(raw: String): String {
        var s = raw.trim().replace("\u2019", "'").replace(Regex("\\s+"), " ")
        s = LEAD.replaceFirst(s, "")
        s = LEAD_P.replaceFirst(s, "")
        s = TRAIL.replace(s, "")
        return s.trim().trimEnd('.', '!', '?', ',').trim()
    }

    private const val P = "(?:pragon|paragon|pragan|pragun)"
    private const val HS = "(?:airpods|air pods|airpod|air pod|headset|earbuds|earphones|bluetooth headset|bluetooth)"
    private val I = RegexOption.IGNORE_CASE

    private val PC_TOGGLE1 = Regex("^(?:turn|switch|set)\\s+(on|off)\\s+(?:my\\s+|the\\s+)?(?:$P\\s+)?contacts?(?:\\s+list)?$", I)
    private val PC_TOGGLE2 = Regex("^(enable|disable)\\s+(?:my\\s+|the\\s+)?(?:$P\\s+)?contacts?(?:\\s+list)?$", I)
    private val PC_TOGGLE3 = Regex("^(?:$P\\s+)?contacts?(?:\\s+list)?\\s+(on|off)$", I)
    private val PC_STATUS = Regex("^(?:is\\s+)?(?:my\\s+|the\\s+)?(?:$P\\s+)?contacts?(?:\\s+list)?\\s+(?:on|off|enabled|status)\\??$|^(?:$P\\s+)?contacts?\\s+status$", I)

    private val AIR_STATUS = Regex("^(?:$HS\\s+(?:status|connected|connection)|(?:are|is)\\s+(?:my\\s+|the\\s+)?$HS\\s+connected|(?:check|show)\\s+(?:my\\s+|the\\s+)?$HS(?:\\s+status)?)$", I)
    private val AIR_CONNECT = Regex("^(?:connect|pair|find|use)\\s+(?:to\\s+)?(?:my\\s+|the\\s+)?$HS$", I)

    private val STT_DL = Regex("^(?:download|get|install)\\s+(?:the\\s+)?(?:offline\\s+)?(?:speech|voice)\\s+(?:model|pack|recognition|recogniser|recognizer)$", I)
    private val STT_VOSK = Regex("^(?:use|switch to)\\s+(?:the\\s+)?(?:offline|vosk|on[- ]?phone|on[- ]?device)\\s+(?:speech|voice)(?:\\s+(?:recognition|engine))?$", I)
    private val STT_ANDROID = Regex("^(?:use|switch to)\\s+(?:the\\s+)?(?:android|google|online)\\s+(?:speech|voice)(?:\\s+(?:recognition|engine))?$", I)
    private val STT_AUTO = Regex("^(?:use|switch to)\\s+(?:automatic|auto)\\s+(?:speech|voice)(?:\\s+(?:recognition|engine))?$|^(?:speech|voice)\\s+(?:engine|recognition)\\s+auto$", I)
    private val STT_STATUS = Regex("^(?:(?:which|what)\\s+(?:speech|voice)\\s+(?:engine|recogni\\w+)(?:\\s+(?:are you|is it)\\s+using)?|(?:speech|voice)\\s+(?:engine|recognition)\\s+status|is\\s+(?:offline\\s+)?(?:speech|voice)\\s+recognition\\s+(?:working|ready|installed))$", I)

    private val FEAT_HERE = Regex("^(?:what\\s+(?:features\\s+)?(?:work|works|can i use|are available|do you have)\\s+(?:right now|now|here|online|offline)|(?:online|offline|available)\\s+features|list\\s+(?:the\\s+)?(?:available\\s+)?features|what\\s+works\\s+(?:online|offline|right now)|show\\s+(?:the\\s+)?features)$", I)
    private val FEAT_ALL = Regex("^(?:show|list)\\s+all\\s+(?:the\\s+)?features$", I)
    private val FEAT_AVAIL = Regex("^(?:show|list)\\s+(?:only\\s+)?(?:the\\s+)?available\\s+features\\s+only$|^show\\s+only\\s+(?:what\\s+works|available\\s+features)$", I)

    private val OFF_LOCK_OFF = Regex("^(?:go online|turn off offline mode|offline mode off|disable offline mode|always offline off|unlock offline)$", I)

    private val LLM_STATUS = Regex("^(?:model\\s+status|llm\\s+status|ai\\s+status|which\\s+models?\\s+are\\s+(?:loaded|running)|what\\s+models?\\s+are\\s+(?:loaded|running))$", I)
    private val LLM_GPU = Regex("^(?:run\\s+|put\\s+|use\\s+)?(?:the\\s+)?chat(?:\\s+model)?\\s+(?:on|using)\\s+(?:the\\s+)?(gpu|cpu)$|^(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(?:chat\\s+)?gpu$", I)
    private val LLM_PAR = Regex("^(?:turn|switch)\\s+(on|off)\\s+parallel\\s+(?:models?|llms?|ai)$|^parallel\\s+(?:models?|llms?|ai)\\s+(on|off)$", I)

    private val TIME = Regex("^(?:what(?:'s| is)?\\s+(?:the\\s+)?time(?:\\s+(?:is it|now|right now))?|what\\s+time\\s+is\\s+it(?:\\s+now)?|tell\\s+me\\s+the\\s+time|current\\s+time|time\\s+now)$", I)
    private val DATE = Regex("^(?:what(?:'s| is)?\\s+(?:the\\s+|today'?s\\s+)?date(?:\\s+today)?|what\\s+day\\s+(?:is\\s+it|is\\s+today)(?:\\s+today)?|what\\s+is\\s+today|today'?s\\s+date|tell\\s+me\\s+the\\s+date)$", I)
    private val STORAGE = Regex("^(?:how\\s+much\\s+(?:storage|space)\\s+(?:do\\s+i\\s+have\\s+)?(?:is\\s+)?(?:left|free|available)|storage\\s+(?:left|status|free)|free\\s+storage|phone\\s+storage)$", I)

    private val NOTE_ADD = Regex("^(?:(?:take|make|add|save|write)\\s+(?:a\\s+)?note(?:\\s*(?:that|to|saying|:))?|note\\s+down(?:\\s+that)?|note\\s+that)\\s+(.+)$", I)
    private val NOTE_READ = Regex("^(?:read|show|list|open)\\s+(?:out\\s+)?(?:my\\s+|the\\s+)?notes$|^what\\s+are\\s+my\\s+notes$", I)
    private val NOTE_CLEAR = Regex("^(?:clear|delete|erase)\\s+(?:all\\s+)?(?:my\\s+|the\\s+)?notes$", I)

    private val COIN = Regex("^(?:flip|toss)\\s+a\\s+coin$|^heads\\s+or\\s+tails$", I)
    private val DICE = Regex("^(?:roll|throw)\\s+(?:a\\s+|the\\s+)?(?:dice|die)$", I)
    private val RANDOM = Regex("^(?:pick|give\\s+me|choose)\\s+(?:a\\s+)?(?:random\\s+)?number\\s+(?:between|from)\\s+(\\d{1,9})\\s+(?:and|to)\\s+(\\d{1,9})$", I)
    private val JOKE = Regex("^(?:tell\\s+me\\s+a\\s+joke|(?:say|crack)\\s+a\\s+joke|make\\s+me\\s+laugh|joke)$", I)
    private val FACT = Regex("^(?:tell\\s+me\\s+a\\s+(?:fun\\s+)?fact|(?:fun|random)\\s+fact|did\\s+you\\s+know)$", I)

    fun parse(raw: String): VoiceParser.Action? {
        val s = tidy(raw)
        if (s.isEmpty()) return null

        PC_TOGGLE1.matchEntire(s)?.let { return VoiceParser.Action("pc_toggle", it.groupValues[1].lowercase()) }
        PC_TOGGLE2.matchEntire(s)?.let { return VoiceParser.Action("pc_toggle", if (it.groupValues[1].lowercase() == "enable") "on" else "off") }
        PC_TOGGLE3.matchEntire(s)?.let { return VoiceParser.Action("pc_toggle", it.groupValues[1].lowercase()) }
        if (PC_STATUS.matches(s)) return VoiceParser.Action("pc_toggle", "status")

        if (AIR_STATUS.matches(s)) return VoiceParser.Action("airpods", "status")
        if (AIR_CONNECT.matches(s)) return VoiceParser.Action("airpods", "connect")

        if (STT_DL.matches(s)) return VoiceParser.Action("stt", "download")
        if (STT_VOSK.matches(s)) return VoiceParser.Action("stt", "vosk")
        if (STT_ANDROID.matches(s)) return VoiceParser.Action("stt", "android")
        if (STT_AUTO.matches(s)) return VoiceParser.Action("stt", "auto")
        if (STT_STATUS.matches(s)) return VoiceParser.Action("stt", "status")

        if (FEAT_ALL.matches(s)) return VoiceParser.Action("features", "all")
        if (FEAT_AVAIL.matches(s)) return VoiceParser.Action("features", "available")
        if (FEAT_HERE.matches(s)) return VoiceParser.Action("features", "here")

        if (OFF_LOCK_OFF.matches(s)) return VoiceParser.Action("offline", "off")

        if (LLM_STATUS.matches(s)) return VoiceParser.Action("llm", "status")
        LLM_GPU.matchEntire(s)?.let {
            val g = it.groupValues
            val on = if (g[1].isNotEmpty()) g[1].lowercase() == "gpu" else g[2].lowercase() == "on"
            return VoiceParser.Action("llm", if (on) "chat_gpu" else "chat_cpu")
        }
        LLM_PAR.matchEntire(s)?.let {
            val v = it.groupValues[1].ifEmpty { it.groupValues[2] }.lowercase()
            return VoiceParser.Action("llm", if (v == "on") "parallel_on" else "parallel_off")
        }

        if (TIME.matches(s)) return VoiceParser.Action("info", "time")
        if (DATE.matches(s)) return VoiceParser.Action("info", "date")
        if (STORAGE.matches(s)) return VoiceParser.Action("info", "storage")

        NOTE_ADD.matchEntire(s)?.let { val t = it.groupValues[1].trim(); if (t.isNotEmpty()) return VoiceParser.Action("note", "add:$t") }
        if (NOTE_READ.matches(s)) return VoiceParser.Action("note", "read")
        if (NOTE_CLEAR.matches(s)) return VoiceParser.Action("note", "clear")

        if (COIN.matches(s)) return VoiceParser.Action("fun", "coin")
        if (DICE.matches(s)) return VoiceParser.Action("fun", "dice")
        RANDOM.matchEntire(s)?.let { return VoiceParser.Action("fun", "random:${it.groupValues[1]}:${it.groupValues[2]}") }
        if (JOKE.matches(s)) return VoiceParser.Action("fun", "joke")
        if (FACT.matches(s)) return VoiceParser.Action("fun", "fact")
        return null
    }
}
