package com.pragon.mobile

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/** v11 actions: Pragon contacts ON/OFF, AirPods, speech engine, features, parallel models, small everyday helpers. */
object V11Actions {

    val NAMES = setOf("pc_toggle", "airpods", "stt", "features", "llm", "info", "note", "fun")

    private fun ok(m: String, l: String = "") = CommandExecutor.Res(true, m, label = l)
    private fun no(m: String) = CommandExecutor.Res(false, m)
    private val rnd = Random()

    fun run(ctx: Context, action: String, value: String): CommandExecutor.Res = when (action) {
        "pc_toggle" -> contacts(ctx, value)
        "airpods" -> airpods(ctx, value)
        "stt" -> stt(ctx, value)
        "features" -> features(ctx, value)
        "llm" -> llm(ctx, value)
        "info" -> info(ctx, value)
        "note" -> note(ctx, value)
        "fun" -> fun_(ctx, value)
        else -> no("I don't know how to do '$action'.")
    }

    private fun contacts(ctx: Context, v: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        return when (v) {
            "on" -> { Prefs.setContactsOn(ctx, true); ok("Pragon contacts is on, $me. I call names saved there, or numbers you say.", "Pragon contacts") }
            "off" -> { Prefs.setContactsOn(ctx, false); ok("Pragon contacts is off, $me. I'll look names up in your phone contacts instead.", "Pragon contacts") }
            else -> ok(if (Prefs.contactsOn(ctx)) "Pragon contacts is on, $me." else "Pragon contacts is off, $me; I use your phone contacts.", "Pragon contacts")
        }
    }

    private fun airpods(ctx: Context, v: String): CommandExecutor.Res {
        val connected = AudioRoute.headset(ctx) != null
        if (v == "connect" && !connected) {
            // Android doesn't let an app connect Bluetooth devices by itself; the Bluetooth page does it in one tap.
            val r = CommandExecutor.run(ctx, JSONObject().put("action", "settings").put("value", "bluetooth"), fromStandalone = true, user = true)
            return ok("I don't see them connected, ${Prefs.callMe(ctx)}. I opened Bluetooth settings: tap your AirPods there, and I'll switch to them the moment they connect." + if (r.ok) "" else " (" + r.msg + ")", "Bluetooth")
        }
        AudioRoute.ensureBluetoothPermission(ctx)
        return ok(AudioRoute.status(ctx), "AirPods")
    }

    private fun stt(ctx: Context, v: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        return when (v) {
            "download" -> when {
                SherpaStt.hasModel(ctx) -> ok("An offline speech model is already installed, $me.", "Speech")
                SherpaStt.downloading -> ok("The speech model is already downloading: ${SherpaStt.dlState}.", "Speech")
                !Net.online(ctx) -> no("I need the internet once to download it, $me. Connect to Wi-Fi and ask again.")
                else -> { SherpaStt.download(ctx); ok("Downloading the offline speech model, about 70 megabytes, once. Use Wi-Fi. Progress is in Settings, Offline.", "Speech") }
            }
            "vosk" -> if (!SherpaStt.hasModel(ctx)) no("The offline speech model isn't installed yet. Say download the speech model.")
                else { Prefs.setSttEngine(ctx, "vosk"); ok("Using Pragon's own offline speech recognition, $me.", "Speech") }
            "android" -> { Prefs.setSttEngine(ctx, "android"); ok("Using Android's speech recognition, $me.", "Speech") }
            "auto" -> { Prefs.setSttEngine(ctx, "auto"); ok("Automatic: my own offline recognition when offline, Android's when online, $me.", "Speech") }
            else -> {
                val engine = when (Prefs.sttEngine(ctx)) { "vosk" -> "Pragon's offline engine"; "android" -> "Android's recognizer"; else -> "automatic" }
                val model = if (SherpaStt.hasModel(ctx)) "The offline speech model is installed (${SherpaStt.state})." else "No offline speech model is installed yet."
                ok("Speech engine: $engine. $model", "Speech")
            }
        }
    }

    private fun features(ctx: Context, v: String): CommandExecutor.Res {
        when (v) {
            "all" -> Prefs.setShowAllFeatures(ctx, true)
            "available" -> Prefs.setShowAllFeatures(ctx, false)
        }
        return ok(Features.spoken(ctx), "Features")
    }

    private fun llm(ctx: Context, v: String): CommandExecutor.Res = when (v) {
        "parallel_on" -> { Prefs.setParallelLlm(ctx, true); ok("Parallel models on. I run two at once only when the phone has memory to spare.", "Models") }
        "chat_gpu" -> { Prefs.setChatGpu(ctx, true); OnDeviceLlm.reloadChat(); ok("The chat model will use the GPU from its next load, with the processor as a backup.", "Models") }
        "chat_cpu" -> { Prefs.setChatGpu(ctx, false); OnDeviceLlm.reloadChat(); ok("The chat model will use the processor from its next load.", "Models") }
        "parallel_off" -> { Prefs.setParallelLlm(ctx, false); ok("Parallel models off. They take turns.", "Models") }
        else -> ok(LlmHub.status(ctx), "Models")
    }

    private fun info(ctx: Context, v: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        val now = Date()
        return when (v) {
            "time" -> ok("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(now) + ", $me.", "Time")
            "date" -> ok("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now) + ", $me.", "Date")
            "storage" -> try {
                val st = StatFs(Environment.getDataDirectory().path)
                val free = st.availableBytes / 1_073_741_824.0
                val total = st.totalBytes / 1_073_741_824.0
                ok(String.format(Locale.US, "You have %.1f gigabytes free out of %.0f, %s.", free, total, me), "Storage")
            } catch (e: Exception) { no("I couldn't read the storage figures.") }
            else -> no("I don't know that one.")
        }
    }

    private fun notesFile(ctx: Context) = File(ctx.filesDir, "pragon_notes.txt")

    private fun note(ctx: Context, v: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        val f = notesFile(ctx)
        return when {
            v.startsWith("add:") -> {
                val t = v.removePrefix("add:").trim().replace('\n', ' ')
                if (t.isEmpty()) return no("What should the note say?")
                f.appendText(SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(Date()) + " | " + t + "\n")
                ok("Noted, $me: $t.", "Notes")
            }
            v == "read" -> {
                val lines = if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList()
                if (lines.isEmpty()) ok("You have no notes, $me.", "Notes")
                else ok("You have ${lines.size} note" + (if (lines.size > 1) "s" else "") + ". " + lines.takeLast(5).joinToString(". ") { it.substringAfter("| ") } + ".", "Notes")
            }
            v == "clear" -> { if (f.isFile) f.delete(); ok("All notes cleared, $me.", "Notes") }
            else -> no("I don't know that notes command.")
        }
    }

    private val JOKES = listOf(
        "Why don't programmers like nature? It has too many bugs.",
        "I told my phone I needed space. It sent me to the storage settings.",
        "Why did the smartphone go to therapy? It had too many unresolved notifications.",
        "There are ten kinds of people: those who understand binary, and those who don't.",
        "My battery and I have a lot in common. We both run out at the worst time.",
        "Why was the computer cold? It left its Windows open.",
    )
    private val FACTS = listOf(
        "Honey never spoils; edible honey has been found in ancient tombs.",
        "A day on Venus is longer than its year.",
        "Octopuses have three hearts.",
        "Bananas are berries, but strawberries are not.",
        "Sound travels about four times faster in water than in air.",
    )

    private fun fun_(ctx: Context, v: String): CommandExecutor.Res {
        val me = Prefs.callMe(ctx)
        return when {
            v == "coin" -> ok((if (rnd.nextBoolean()) "Heads" else "Tails") + ", $me.", "Coin")
            v == "dice" -> ok("You rolled a ${rnd.nextInt(6) + 1}, $me.", "Dice")
            v == "joke" -> ok(JOKES[rnd.nextInt(JOKES.size)], "Joke")
            v == "fact" -> ok(FACTS[rnd.nextInt(FACTS.size)], "Fact")
            v.startsWith("random:") -> {
                val p = v.split(":")
                val a = p.getOrNull(1)?.toLongOrNull() ?: return no("Which numbers?")
                val b = p.getOrNull(2)?.toLongOrNull() ?: return no("Which numbers?")
                val lo = minOf(a, b); val hi = maxOf(a, b)
                ok("${lo + (rnd.nextDouble() * (hi - lo + 1)).toLong()}, $me.", "Random")
            }
            else -> no("I don't know that one.")
        }
    }
}
