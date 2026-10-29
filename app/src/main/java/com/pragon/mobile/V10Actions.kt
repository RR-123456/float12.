package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/** v10 actions that have no home in CommandExecutor yet: messages with auto-send, speed report, offline status, help. */
object V10Actions {

    val NAMES = setOf("send_message", "metrics", "offline", "help")

    private fun ok(m: String, l: String = "") = CommandExecutor.Res(true, m, label = l)
    private fun no(m: String) = CommandExecutor.Res(false, m)

    fun run(ctx: Context, action: String, value: String): CommandExecutor.Res = when (action) {
        "metrics" -> ok(Metrics.spoken(ctx), "Speed")
        "help" -> ok(Features.spoken(ctx), "Help")
        "offline" -> offline(ctx, value)
        "send_message" -> sendMessage(ctx, value)
        else -> no("I don't know how to do '$action'.")
    }


    private fun offline(ctx: Context, v: String): CommandExecutor.Res = when (v) {
        "status" -> ok(Offline.spokenStatus(ctx), "Offline")
        "on" -> { Prefs.setOfflineOnly(ctx, true); ok("Always-offline lock is on, ${Prefs.callMe(ctx)}. I stay on the phone even when there's internet.", "Offline") }
        "off" -> { Prefs.setOfflineOnly(ctx, false); ok("Lock off, ${Prefs.callMe(ctx)}. I follow your connection by myself now.", "Offline") }
        "warm" -> {
            if (Offline.routerFile(ctx) == null) no("The command model isn't installed yet. Say download the model, or copy it into the models folder.")
            else { OnDeviceLlm.warmup(ctx); ok("Loading the command model.", "Offline") }
        }
        "download" -> {
            if (Offline.routerFile(ctx) != null) ok("The command model is already installed.", "Offline")
            else if (Offline.downloading) ok("The model is already downloading: ${Offline.dlState}.", "Offline")
            else { Offline.download(ctx, Prefs.modelUrl(ctx)); ok("Downloading the command model, about 280 megabytes, once. Use Wi-Fi. Check progress in Settings.", "Offline") }
        }
        else -> no("I don't know that offline command.")
    }

    /** value = "contact|text|channel". Opens the chat with the text ready, then presses Send if you allowed it. */
    private fun sendMessage(ctx: Context, v: String): CommandExecutor.Res {
        val p = v.split("|")
        val who = p.getOrElse(0) { "" }.trim()
        val text = p.getOrElse(1) { "" }.trim()
        val ch = p.getOrElse(2) { "" }.trim().lowercase()
        if (who.isEmpty()) return no("Who should I message?")
        if (text.isEmpty()) return no("What should the message say?")
        val a = JSONObject().put("action", "message").put("name", who).put("text", text).put("channel", ch)
        val o = ContactsTool.act(ctx, a, PMode.MASTER)
        if (!o.ok) return CommandExecutor.Res(false, o.msg, label = o.label)
        val telegram = ch == "telegram" || ch == "tg"
        if (telegram || !Prefs.autoSend(ctx)) return CommandExecutor.Res(true, o.msg, label = o.label)
        val svc = PragonAccessibilityService.instance
            ?: return CommandExecutor.Res(true, o.msg + " Turn on the accessibility service and I'll press send for you.", label = o.label)
        return if (svc.clickSend(7000)) CommandExecutor.Res(true, "Sent to $who on ${o.label.ifBlank { "your phone" }}.", label = o.label)
        else CommandExecutor.Res(true, o.msg + " I couldn't find the send button, so tap it yourself.", label = o.label)
    }
}
