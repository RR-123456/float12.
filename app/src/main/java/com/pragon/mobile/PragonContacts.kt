package com.pragon.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** The people Pragon is allowed to call by name. Stored on the phone only. */
object PragonContacts {
    class Entry(val name: String, val number: String)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_contacts", Context.MODE_PRIVATE)
    private fun load(c: Context): JSONObject = try { JSONObject(sp(c).getString("list", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun save(c: Context, o: JSONObject) { sp(c).edit().putString("list", o.toString()).apply() }
    private fun key(n: String) = n.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun tail(n: String) = n.filter { it.isDigit() }.takeLast(10)

    @Synchronized
    fun all(c: Context): List<Entry> {
        val o = load(c)
        val out = mutableListOf<Entry>()
        for (k in o.keys()) { val e = o.optJSONObject(k) ?: continue; out.add(Entry(e.optString("name"), e.optString("number"))) }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Exact name first, then a name that starts with / contains the words, but only when exactly one matches. */
    @Synchronized
    fun find(c: Context, q: String): Entry? {
        val k = key(q)
        if (k.isEmpty()) return null
        val o = load(c)
        o.optJSONObject(k)?.let { return Entry(it.optString("name"), it.optString("number")) }
        val hits = mutableListOf<Entry>()
        for (n in o.keys()) if (n.startsWith(k) || n.contains(k)) o.optJSONObject(n)?.let { hits.add(Entry(it.optString("name"), it.optString("number"))) }
        return if (hits.size == 1) hits[0] else null
    }

    @Synchronized
    fun findByNumber(c: Context, num: String): Entry? {
        val t = tail(num)
        if (t.length < 6) return null
        return all(c).firstOrNull { tail(it.number) == t }
    }

    /** [number] empty = copy it from the phone's own contacts (needs the contacts permission). */
    fun add(c: Context, name: String, number: String): CommandExecutor.Res {
        val nm = name.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val k = key(nm)
        if (k.isEmpty()) return CommandExecutor.Res(false, "Which name should I save?")
        var num = number.trim()
        if (num.isEmpty()) {
            Perm.need(c, android.Manifest.permission.READ_CONTACTS, "read your phone contacts")?.let { return CommandExecutor.Res(false, it) }
            val hit = try { ContactsTool.find(c, nm).firstOrNull() } catch (e: SecurityException) { null }
            num = hit?.numbers?.firstOrNull() ?: return CommandExecutor.Res(false, "I couldn't find a number for $nm in your phone contacts. Say it with the number.")
        }
        val d = V9Voice.dialable(num) ?: return CommandExecutor.Res(false, "That doesn't look like a phone number.")
        synchronized(this) {
            val o = load(c)
            o.put(k, JSONObject().put("name", nm).put("number", d))
            save(c, o)
        }
        return CommandExecutor.Res(true, "Saved $nm to Pragon contacts.", label = "Pragon contacts")
    }

    fun remove(c: Context, name: String): CommandExecutor.Res {
        val e = find(c, name) ?: return CommandExecutor.Res(false, "I don't have '${name.trim()}' in Pragon contacts.")
        synchronized(this) { val o = load(c); o.remove(key(e.name)); save(c, o) }
        return CommandExecutor.Res(true, "Removed ${e.name} from Pragon contacts.", label = "Pragon contacts")
    }

    fun spokenList(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return "Your Pragon contacts list is empty. Say: add Ravi 98xxxxxxxx to Pragon contacts."
        return "You have ${l.size} Pragon contact" + (if (l.size > 1) "s" else "") + ": " + l.joinToString(", ") { it.name } + "."
    }
}

/** Direct voice calling. Only names in Pragon contacts (or a number you say) are dialled. * and # codes only open the dialer. */
object Calls {
    fun dial(ctx: Context, target: String): CommandExecutor.Res {
        val t = target.trim()
        val me = Prefs.callMe(ctx)
        if (t.isEmpty()) return CommandExecutor.Res(false, "Who should I call?")
        val digits = V9Voice.dialable(t)
        val number: String
        val who: String
        if (digits != null) { number = digits; who = digits } else {
            if (!Prefs.contactsOn(ctx)) {
                // v11: Pragon contacts is OFF, so names come from the phone's own contacts.
                Perm.need(ctx, android.Manifest.permission.READ_CONTACTS, "read your phone contacts")?.let { return CommandExecutor.Res(false, it) }
                val hits = try { ContactsTool.find(ctx, t) } catch (x: SecurityException) { emptyList() }
                val hit = hits.firstOrNull { it.numbers.isNotEmpty() }
                    ?: return CommandExecutor.Res(false, "I couldn't find '$t' in your phone contacts, $me. (Pragon contacts is off; say turn on Pragon contacts to use that list.)")
                if (hits.count { it.numbers.isNotEmpty() } > 1 && hits[0].score == hits.getOrNull(1)?.score)
                    return CommandExecutor.Res(false, "I found more than one '$t' in your phone contacts: " + hits.take(3).joinToString(", ") { it.name } + ". Say the full name, $me.")
                number = hit.numbers[0]; who = hit.name
            } else {
                val e = PragonContacts.find(ctx, t)
                    ?: return CommandExecutor.Res(false, "I only call people in Pragon contacts, and '$t' isn't there, $me. Say: add $t 98xxxxxxxx to Pragon contacts, or turn Pragon contacts off to use your phone contacts.")
                number = e.number; who = e.name
            }
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (number.contains('*') || number.contains('#')) {
            val err = Launch.start(ctx, Intent(Intent.ACTION_DIAL, uri), "The dialer", guard = false)
            return if (err == null) CommandExecutor.Res(true, "Opened the dialer with $number. Tap call to place it, $me.", label = "Phone")
            else CommandExecutor.Res(false, err)
        }
        if (Perm.has(ctx, android.Manifest.permission.CALL_PHONE)) {
            val err = Launch.start(ctx, Intent(Intent.ACTION_CALL, uri), "The phone", guard = false)
            return if (err == null) CommandExecutor.Res(true, "Calling $who, $me.", label = "Phone") else CommandExecutor.Res(false, err)
        }
        Perm.request(ctx, android.Manifest.permission.CALL_PHONE)
        val err = Launch.start(ctx, Intent(Intent.ACTION_DIAL, uri), "The dialer", guard = false)
        return if (err == null) CommandExecutor.Res(true, "I need the Phone permission to call directly. Allow the prompt and ask again. The dialer is open with $who for now.", label = "Phone")
        else CommandExecutor.Res(false, err)
    }
}

/** Entry point for the v9 actions (offline voice parser, the AI's phone_control tool and Settings all end up here). */
object V9Actions {
    val NAMES = setOf("audio_route", "headset_mic", "pc_add", "pc_remove", "pc_list", "pa")

    private fun ok(m: String, l: String = "") = CommandExecutor.Res(true, m, label = l)
    private fun no(m: String) = CommandExecutor.Res(false, m)

    fun run(ctx: Context, action: String, value: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        return when (action) {
            "audio_route" -> {
                val cur = AudioRoute.saved(ctx)
                val want = when (value.lowercase()) {
                    "toggle" -> if (cur == AudioRoute.SPEAKER) AudioRoute.EARPIECE else AudioRoute.SPEAKER
                    "speaker", "earpiece", "auto" -> value.lowercase()
                    else -> return no("Say speaker, earpiece or auto, $me.")
                }
                AudioRoute.setMode(ctx, want)
                ok(AudioRoute.describe(ctx), "Audio")
            }
            "headset_mic" -> {
                val on = value.lowercase() != "off"
                AudioRoute.setHeadsetMic(ctx, on)
                ok(if (on) "I'll listen through your headset microphone, $me." else "Headset microphone off. I'll listen with the phone's microphone, $me.", "Audio")
            }
            "pc_add" -> { val p = value.split("|", limit = 2); PragonContacts.add(ctx, p[0], p.getOrElse(1) { "" }) }
            "pc_remove" -> PragonContacts.remove(ctx, value)
            "pc_list" -> ok(PragonContacts.spokenList(ctx), "Pragon contacts")
            "pa" -> when (value.lowercase()) {
                "on" -> PragonPA.enable(ctx)
                "off" -> PragonPA.disable(ctx)
                "status" -> ok(PragonPA.status(ctx), "Pragon PA")
                "takeover" -> PragonPA.takeover()
                "summary" -> ok(PragonPA.summaryText(ctx), "Pragon PA")
                else -> no("Say Pragon PA on, off, takeover or summary, $me.")
            }
            else -> no("Unknown action '$action'.")
        }
    }
}
