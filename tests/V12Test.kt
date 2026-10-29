import com.pragon.mobile.*

var failed12 = 0; var passed12 = 0
fun ok12(name: String, cond: Boolean) { if (cond) passed12++ else { println("FAIL: $name"); failed12++ } }

fun main() {
    // model file picking: int8 encoder/joiner, full-precision decoder
    val all = listOf("encoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
        "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt", "README.md")
    val p = SherpaFiles.pick(all)!!
    ok12("int8 encoder", p.encoder == "encoder-epoch-99-avg-1.int8.onnx")
    ok12("fp decoder", p.decoder == "decoder-epoch-99-avg-1.onnx")
    ok12("int8 joiner", p.joiner == "joiner-epoch-99-avg-1.int8.onnx")
    ok12("tokens", p.tokens == "tokens.txt")
    // only full precision present
    val fp = SherpaFiles.pick(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))!!
    ok12("fallback encoder", fp.encoder == "encoder.onnx")
    // incomplete folders are not models
    ok12("no tokens", SherpaFiles.pick(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx")) == null)
    ok12("no joiner", SherpaFiles.pick(listOf("encoder.onnx", "decoder.onnx", "tokens.txt")) == null)
    ok12("vosk folder", SherpaFiles.pick(listOf("final.mdl", "mfcc.conf")) == null)
    // what gets unpacked
    ok12("wanted tokens", SherpaFiles.wanted("tokens.txt"))
    ok12("wanted encoder", SherpaFiles.wanted("encoder-x.int8.onnx"))
    ok12("skip wav", !SherpaFiles.wanted("0.wav"))
    ok12("skip readme", !SherpaFiles.wanted("README.md"))
    ok12("skip other onnx", !SherpaFiles.wanted("silero_vad.onnx"))
    // speculation key
    ok12("key punctuation", SherpaFiles.sentenceKey("Set a timer for ten minutes.") == SherpaFiles.sentenceKey("set a timer for ten  minutes"))
    ok12("key differs", SherpaFiles.sentenceKey("set a timer") != SherpaFiles.sentenceKey("set an alarm"))
    // chat gpu phrases
    fun v(s: String) = V11Voice.parse(s)
    ok12("chat on gpu", v("run chat on gpu")?.let { it.action == "llm" && it.value == "chat_gpu" } == true)
    ok12("chat model on cpu", v("put the chat model on the cpu")?.let { it.value == "chat_cpu" } == true)
    ok12("turn on gpu", v("turn on gpu")?.let { it.value == "chat_gpu" } == true)
    ok12("turn off the chat gpu", v("turn off the chat gpu")?.let { it.value == "chat_cpu" } == true)
    ok12("parallel still works", v("turn off parallel models")?.value == "parallel_off")
    ok12("status still works", v("model status")?.value == "status")
    println("v12: $passed12 passed, $failed12 failed")
    if (failed12 > 0) System.exit(1)
}
