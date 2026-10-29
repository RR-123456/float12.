package com.pragon.mobile

import android.content.Context

/**
 * v10: THE single command-routing layer. Everything the person says goes through here, in this order:
 *
 *   1. phrase rules (V10Voice, V9Voice, VoiceParser): instant, no model, work with no model file at all
 *   2. FunctionGemma 270M on the phone: only when (1) found nothing, the text looks like a command, and the model file is installed
 *   3. nothing matched -> the caller treats it as conversation (optional offline chat model, or a polite "can't chat offline")
 *
 * One Action comes out and goes to CommandExecutor. There is no second path, so there are no duplicate services to keep in sync.
 */
object Router {

    /** Step 1. [raw] is what was heard, [c] the cleaned command (VoiceParser.cleanCommand). Pure phrase matching. */
    fun rules(raw: String, c: String): VoiceParser.Action? =
        V11Voice.parse(raw) ?: V10Voice.parse(raw) ?: V9Voice.parse(raw) ?: VoiceParser.ollamaCommand(raw)
            ?: VoiceParser.rawAction(VoiceParser.norm(raw)) ?: VoiceParser.actionFor(c)

    /** Step 2. Blocking: runs the on-device model (a second or two on first use). null = no usable answer. */
    fun model(ctx: Context, raw: String): VoiceParser.Action? {
        if (!Offline.active(ctx)) return null
        if (Offline.routerFile(ctx) == null) return null
        if (!ToolCatalog.looksLikeCommand(raw)) return null
        // v12: a guess started while you were still speaking (Speculate) is used when the final sentence is the same.
        if (Speculate.has(raw)) Speculate.take(raw)?.let { return it.action }
        return modelNow(ctx, raw)
    }

    /** The router model itself, no checks and no cache. Also what Speculate runs early. */
    fun modelNow(ctx: Context, raw: String): VoiceParser.Action? {
        val call = Metrics.time("llm") { OnDeviceLlm.route(ctx, raw) } ?: return null
        return ToolCatalog.toAction(call.name, call.args)
    }
}
