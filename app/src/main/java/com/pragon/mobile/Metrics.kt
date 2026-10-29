package com.pragon.mobile

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.util.ArrayDeque

/**
 * v10 latency + memory log. Every stage of a voice command is timed (rules, on-device model, action, speech recognizer).
 * Say "how fast are you" to hear the averages. Nothing is stored on disk and nothing leaves the phone.
 */
object Metrics {
    private class Sample(val what: String, val ms: Long)
    private val ring = ArrayDeque<Sample>()
    private const val MAX = 200

    fun now(): Long = SystemClock.elapsedRealtime()

    fun add(what: String, ms: Long) {
        if (ms < 0) return
        synchronized(ring) {
            ring.addLast(Sample(what, ms))
            while (ring.size > MAX) ring.removeFirst()
        }
    }

    inline fun <T> time(what: String, block: () -> T): T {
        val t0 = now()
        try { return block() } finally { add(what, now() - t0) }
    }

    /** (average ms, count, worst ms) for one stage over the last samples, or null. */
    private fun stat(what: String): Triple<Long, Int, Long>? = synchronized(ring) {
        val xs = ring.filter { it.what == what }.map { it.ms }
        if (xs.isEmpty()) null else Triple(xs.sum() / xs.size, xs.size, xs.max())
    }

    /** This process's real memory use in MB (proportional set size, native model memory included). */
    fun memoryMb(c: Context): Long = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        info[0].totalPss / 1024L
    } catch (e: Exception) { Debug.getNativeHeapAllocatedSize() / 1_048_576L }

    private val LABELS = linkedMapOf(
        "stt" to "listening",
        "rules" to "phrase matching",
        "llm" to "command model",
        "act" to "doing it",
        "total" to "whole command",
    )

    fun spoken(c: Context): String {
        val parts = ArrayList<String>()
        for ((k, label) in LABELS) {
            val s = stat(k) ?: continue
            parts.add("$label ${s.first} milliseconds on average over ${s.second}")
        }
        val mem = "Pragon is using ${memoryMb(c)} megabytes of memory."
        return if (parts.isEmpty()) "I haven't timed any commands yet, ${Prefs.callMe(c)}. $mem"
        else parts.joinToString(". ") + ". $mem"
    }
}
