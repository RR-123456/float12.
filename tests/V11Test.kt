import com.pragon.mobile.*

fun check(said: String, action: String?, value: String? = null) {
    val a = V11Voice.parse(said)
    val ok = if (action == null) a == null else a != null && a.action == action && (value == null || a.value == value)
    if (!ok) { println("FAIL: '$said' -> ${a?.action}/${a?.value} (wanted $action/$value)"); failed++ } else passed++
}
var failed = 0; var passed = 0

fun main() {
    check("turn off pragon contacts", "pc_toggle", "off")
    check("Hey Pragon, turn on contacts", "pc_toggle", "on")
    check("disable contacts", "pc_toggle", "off")
    check("enable my contacts list please", "pc_toggle", "on")
    check("pragon contacts off", "pc_toggle", "off")
    check("is pragon contacts on", "pc_toggle", "status")
    check("airpods status", "airpods", "status")
    check("are my airpods connected", "airpods", "status")
    check("connect my airpods", "airpods", "connect")
    check("download the speech model", "stt", "download")
    check("use offline speech", "stt", "vosk")
    check("use google speech recognition", "stt", "android")
    check("use auto speech", "stt", "auto")
    check("which speech engine are you using", "stt", "status")
    check("what works offline", "features", "here")
    check("show all features", "features", "all")
    check("go online", "offline", "off")
    check("model status", "llm", "status")
    check("turn off parallel models", "llm", "parallel_off")
    check("parallel llm on", "llm", "parallel_on")
    check("what time is it", "info", "time")
    check("what's the date", "info", "date")
    check("how much storage is left", "info", "storage")
    check("take a note buy milk", "note", "add:buy milk")
    check("note that the meeting is at five", "note", "add:the meeting is at five")
    check("read my notes", "note", "read")
    check("clear my notes", "note", "clear")
    check("flip a coin", "fun", "coin")
    check("roll the dice", "fun", "dice")
    check("pick a number between 3 and 9", "fun", "random:3:9")
    check("tell me a joke", "fun", "joke")
    check("tell me a fun fact", "fun", "fact")
    // must NOT be stolen from other features
    check("open youtube", null)
    check("call ravi", null)
    check("what is the weather", null)
    check("send message to ravi saying note that i am late", null)
    check("set a timer for ten minutes", null)
    println("V11 voice: $passed passed, $failed failed")
    if (failed > 0) System.exit(1)
}
