package com.pragon.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v11: what Pragon can do RIGHT NOW. The list follows the connection:
 *  - offline: only features that work with no internet (plus the on-phone AI that exists for offline use)
 *  - online:  only features that work online (the on-phone-only extras step aside)
 * Settings > "Features right now" and the spoken "what can you do" read from here. "Show all features" turns the filter off.
 */
object Features {
    enum class Needs { ANY, ONLINE, OFFLINE }

    class F(val title: String, val example: String, val needs: Needs)

    val ALL = listOf(
        F("Open and close apps", "open YouTube", Needs.ANY),
        F("Scroll, swipe, tap by label, type, go home or back", "scroll down", Needs.ANY),
        F("Screenshot, read the screen aloud, ask about the screen", "read the screen", Needs.ANY),
        F("Timers, alarms, stopwatch, reminders", "set a timer for ten minutes", Needs.ANY),
        F("Volume, brightness, flashlight, settings pages", "turn the flashlight on", Needs.ANY),
        F("Calls to Pragon contacts (switchable) or phone contacts", "call Ravi", Needs.ANY),
        F("SMS messages", "text Ravi saying I'm late", Needs.ANY),
        F("Calculator and unit sums", "what is 15 percent of 240", Needs.ANY),
        F("Time, date, storage, notes, coin, dice, jokes, facts", "take a note buy milk", Needs.ANY),
        F("AirPods / headset listening and speaking", "airpods status", Needs.ANY),
        F("Macros and battery reminders", "record a macro", Needs.ANY),
        F("Pragon PA: answers your calls", "pragon PA on", Needs.ANY),
        F("On-phone streaming speech recognition (sherpa-onnx)", "download the speech model", Needs.OFFLINE),
        F("On-phone command model (understands loose wording)", "load the model", Needs.OFFLINE),
        F("On-phone chat model (optional file)", "just talk", Needs.OFFLINE),
        F("WhatsApp and Telegram messages", "message Ravi on WhatsApp saying hi", Needs.ONLINE),
        F("Pragon Live: real-time voice conversation", "just talk", Needs.ONLINE),
        F("Gemini / Ghost / custom-server chat answers", "explain how a rainbow forms", Needs.ONLINE),
        F("Web and YouTube search, open websites", "search YouTube for lo-fi", Needs.ONLINE),
        F("Weather, scores, followed topics, daily brief", "what's the weather", Needs.ONLINE),
        F("Link to your Pragon PC", "connect to my PC", Needs.ONLINE),
    )

    /** "online" or "offline", as the rest of Pragon sees it right now. */
    fun view(c: Context): String = if (Offline.active(c)) "offline" else "online"

    fun visible(c: Context): List<F> {
        if (Prefs.showAllFeatures(c)) return ALL
        val offline = Offline.active(c)
        return ALL.filter { it.needs == Needs.ANY || (offline && it.needs == Needs.OFFLINE) || (!offline && it.needs == Needs.ONLINE) }
    }

    fun json(c: Context): String {
        val arr = JSONArray()
        val offline = Offline.active(c)
        for (f in visible(c)) {
            val works = f.needs == Needs.ANY || (offline && f.needs == Needs.OFFLINE) || (!offline && f.needs == Needs.ONLINE)
            arr.put(JSONObject().put("title", f.title).put("example", f.example).put("needs", f.needs.name.lowercase()).put("works", works))
        }
        return JSONObject().put("view", view(c)).put("showAll", Prefs.showAllFeatures(c)).put("features", arr).toString()
    }

    /** One spoken paragraph for "what can you do" / "what works offline". */
    fun spoken(c: Context): String {
        val call = Prefs.callMe(c)
        val head = if (Prefs.showAllFeatures(c)) "Here is everything I have, $call: "
            else if (Offline.active(c)) "You're offline, $call, so these work right now: "
            else "You're online, $call, so these work right now: "
        val names = visible(c).joinToString("; ") { it.title.substringBefore(" (").substringBefore(":") }
        return head + names + ". Say how fast are you for a speed report."
    }
}
