@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import org.json.JSONObject
import java.io.File

/**
 * v10: the on-device models, run with Google's LiteRT-LM (CPU). Nothing here touches the network.
 *
 *  ROUTER  FunctionGemma 270M. Reads what you said and picks ONE tool from ToolCatalog (or none). Used only when the built-in
 *          phrase rules did not understand you, so ordinary commands never wait for a model.
 *  CHAT    Optional second model (chat.litertlm) for free conversation. Without it, offline Pragon answers "I can't chat offline".
 *
 * Each model loads lazily the first time it is needed (a few seconds), stays in memory while you use it, and is released after
 * [IDLE_MS] of silence so it doesn't hold RAM. Say "load the model" before you need it to skip the first-use wait.
 */
object OnDeviceLlm {

    private const val IDLE_MS = 5 * 60_000L

    @Volatile var routerState = "not loaded"      // "not loaded", "loading", "ready", "error: ..."
    @Volatile var chatState = "not loaded"
    /** v12: which processor the chat model last loaded on ("GPU", "CPU", or why the GPU was skipped). */
    @Volatile var chatBackend = "CPU"

    /** Drops the chat model so the next use loads it on the processor chosen now. */
    fun reloadChat() { synchronized(chat) { close(chat) } }

    class Call(val name: String, val args: Map<String, String>)

    private class Slot(val label: String) {
        @Volatile var engine: Engine? = null
        @Volatile var path: String = ""
        @Volatile var lastUse = 0L
    }

    private val router = Slot("router")
    private val chat = Slot("chat")
    /** v11: one lock PER model (was one for both). The router and the chat model load and run side by side without waiting for each other. */
    private val janitorLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var janitor = false

    private fun setState(slot: Slot, s: String) { if (slot === router) routerState = s else chatState = s }

    /** v11: before loading a model on a phone that is short of RAM, let go of the OTHER model if it has been idle for a while. */
    private fun makeRoom(ctx: Context, mine: Slot) {
        val other = if (mine === router) chat else router
        if (other.engine == null) return
        if (LlmHub.freeRamMb(ctx) >= LlmHub.MIN_FREE_MB) return
        if (Metrics.now() - other.lastUse < 30_000L) return          // in use a moment ago: keep it, the two will take turns on the CPU
        synchronized(other) { close(other) }
    }

    private fun ensure(ctx: Context, slot: Slot, file: File): Engine? {
        val quick = slot.engine
        if (quick != null && slot.path == file.absolutePath) { slot.lastUse = Metrics.now(); return quick }
        makeRoom(ctx, slot)          // holds no lock of its own while it takes the other model's lock: no lock-order deadlock
        return load(ctx, slot, file)
    }

    private fun load(ctx: Context, slot: Slot, file: File): Engine? = synchronized(slot) {
        val have = slot.engine
        if (have != null && slot.path == file.absolutePath) { slot.lastUse = Metrics.now(); return have }
        close(slot)
        setState(slot, "loading")
        val t0 = Metrics.now()
        try {
            // v12: the chat model can run on the GPU so the CPU stays free for speech and the router. Any GPU failure falls back to CPU.
            var e: Engine? = null
            if (slot === chat && Prefs.chatGpu(ctx)) {
                try {
                    val g = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU(), cacheDir = ctx.cacheDir.path))
                    g.initialize()
                    e = g
                    chatBackend = "GPU"
                } catch (t: Throwable) { chatBackend = "CPU (GPU failed: ${t.message ?: t.javaClass.simpleName})" }
            }
            if (e == null) {
                val cpu = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path))
                cpu.initialize()
                e = cpu
                if (slot === chat && !chatBackend.startsWith("CPU (")) chatBackend = "CPU"
            }
            slot.engine = e
            slot.path = file.absolutePath
            slot.lastUse = Metrics.now()
            setState(slot, "ready")
            Metrics.add("load_" + slot.label, Metrics.now() - t0)
            startJanitor()
            e
        } catch (t: Throwable) {
            setState(slot, "error: " + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    private fun close(slot: Slot) {
        if (slot === chat) { try { chatConv?.close() } catch (t: Throwable) { }; chatConv = null; chatTurns = 0 }
        try { slot.engine?.close() } catch (t: Throwable) { }
        slot.engine = null
        slot.path = ""
        setState(slot, "not loaded")
    }

    private fun startJanitor() {
        synchronized(janitorLock) {
            if (janitor) return
            janitor = true
        }
        main.postDelayed(object : Runnable {
            override fun run() {
                val now = Metrics.now()
                for (s in listOf(router, chat)) synchronized(s) { if (s.engine != null && now - s.lastUse > IDLE_MS) close(s) }
                val keep = router.engine != null || chat.engine != null
                synchronized(janitorLock) { janitor = keep }
                if (keep) main.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    /** Frees everything now (called when the phone is low on memory). */
    fun release() { synchronized(router) { close(router) }; synchronized(chat) { close(chat) } }

    /**
     * Loads the models in the background so the first command isn't slow. Safe to call often.
     * The router always; the chat model too, at the same time on its own thread, but only when the phone has the RAM for both.
     */
    fun warmup(ctx: Context) {
        val app = ctx.applicationContext
        val f = Offline.routerFile(app)
        if (f != null && router.engine == null) Thread { ensure(app, router, f) }.start()
        val cf = Offline.chatFile(app)
        if (cf != null && chat.engine == null && LlmHub.canRunParallel(app)) Thread { ensure(app, chat, cf) }.start()
    }

    // ── routing ──────────────────────────────────────────────────────────

    private const val ROUTER_SYSTEM =
        "You control an Android phone. Call exactly one function that matches the user's request. " +
            "If no function fits, answer with no function call."

    /** One ToolCatalog tool as a LiteRT-LM OpenApiTool. The model only PROPOSES a call; we run it ourselves after checking it. */
    private class CatalogTool(private val t: ToolCatalog.Tool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = ToolCatalog.schemaJson(t)
        override fun execute(paramsJsonString: String): String = "{}"   // never called: automaticToolCalling = false
    }

    /** Blocking (call from a worker thread). null = the model is missing, failed, or chose no tool. */
    fun route(ctx: Context, utterance: String): Call? {
        val file = Offline.routerFile(ctx) ?: return null
        val engine = ensure(ctx, router, file) ?: return null
        return try {
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(ROUTER_SYSTEM),
                tools = ToolCatalog.tools.map { tool(CatalogTool(it)) },
                automaticToolCalling = false,
                samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1),
            )
            engine.createConversation(cfg).use { conv ->
                val msg = conv.sendMessage(utterance)
                val tc = msg.toolCalls.firstOrNull() ?: return null
                Call(tc.name, toStringMap(tc.arguments))
            }
        } catch (t: Throwable) {
            setState(router, "error: " + (t.message ?: t.javaClass.simpleName))
            null
        } finally {
            router.lastUse = Metrics.now()
        }
    }

    /** Arguments arrive as a map (or JSON text, depending on the runtime version); both are flattened to strings. */
    private fun toStringMap(raw: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(k: Any?, v: Any?) {
            if (k == null || v == null) return
            var s = v.toString()
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            out[k.toString()] = s
        }
        when (raw) {
            null -> {}
            is Map<*, *> -> for ((k, v) in raw) put(k, v)
            else -> try {
                val j = JSONObject(raw.toString())
                for (k in j.keys()) put(k, j.get(k))
            } catch (e: Exception) { }
        }
        return out
    }

    // ── optional offline chat ────────────────────────────────────────────

    private var chatConv: com.google.ai.edge.litertlm.Conversation? = null
    private var chatTurns = 0

    /** Blocking. null = no chat model installed or it failed. Keeps the last few turns so follow-ups make sense. */
    fun chat(ctx: Context, text: String, maxTokens: Int = 256): String? {
        val file = Offline.chatFile(ctx) ?: return null
        val engine = ensure(ctx, chat, file) ?: return null
        return try {
            synchronized(chat) {
                if (chatConv == null || chatTurns >= 8) {
                    try { chatConv?.close() } catch (t: Throwable) { }
                    chatConv = engine.createConversation(ConversationConfig(
                        systemInstruction = Contents.of("You are Pragon, a calm, brief phone assistant. Answer in one to three short spoken sentences, no lists."),
                        maxOutputToken = maxTokens,
                    ))
                    chatTurns = 0
                }
                chatTurns++
                chatConv!!.sendMessage(text).toString().trim().ifEmpty { null }
            }
        } catch (t: Throwable) {
            setState(chat, "error: " + (t.message ?: t.javaClass.simpleName))
            chatConv = null
            null
        } finally {
            chat.lastUse = Metrics.now()
        }
    }
}
