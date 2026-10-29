package com.pragon.mobile

/**
 * v12: picks the right files out of a sherpa-onnx streaming-transducer model folder. No Android classes, so it is tested on a plain JVM.
 * Archives carry several precisions (encoder-...onnx and encoder-...int8.onnx). Preference: int8 encoder and joiner (smaller and faster
 * on the phone), full-precision decoder (tiny, and int8 hurts it), falling back to whatever exists.
 */
object SherpaFiles {

    class Picked(val encoder: String, val decoder: String, val joiner: String, val tokens: String)

    /** Files worth unpacking from an archive. */
    fun wanted(name: String): Boolean = name == "tokens.txt" || (name.endsWith(".onnx") && (
        name.startsWith("encoder") || name.startsWith("decoder") || name.startsWith("joiner")))

    private fun best(names: List<String>, prefix: String, preferInt8: Boolean): String? {
        val all = names.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
        if (all.isEmpty()) return null
        val int8 = all.filter { it.contains("int8") }
        val full = all.filter { !it.contains("int8") }
        val group = if (preferInt8) (int8.ifEmpty { full }) else (full.ifEmpty { int8 })
        return group.sorted().first()
    }

    /** Same sentence, ignoring case, punctuation and spacing: "Set a timer." and "set a timer" give the same key. Used by Speculate. */
    fun sentenceKey(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")

    fun pick(names: List<String>): Picked? {
        val tokens = names.firstOrNull { it == "tokens.txt" } ?: return null
        val enc = best(names, "encoder", true) ?: return null
        val dec = best(names, "decoder", false) ?: return null
        val joi = best(names, "joiner", true) ?: return null
        return Picked(enc, dec, joi, tokens)
    }
}
