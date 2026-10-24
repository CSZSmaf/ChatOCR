package dev.scrolltext.app.processing

import android.os.Debug
import android.os.SystemClock

/** Wall times include suspensions/model loading; PSS is sampled, not an allocation profiler. */
class RunMetrics(previous: Map<String, Long> = emptyMap()) {
    private val started = SystemClock.elapsedRealtime()
    private val previousMs = previous["totalMs"] ?: 0
    val values = previous.toMutableMap()
    val elapsedMs get() = previousMs + SystemClock.elapsedRealtime() - started
    // Keep timers outside the large video coroutine: inlining their long-valued
    // try/finally blocks produced invalid wide-register moves on API 37.
    fun <T> measure(stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            add(stage + "Ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
        }
    }
    suspend fun <T> measureSuspending(stage: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            add(stage + "Ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
        }
    }
    fun add(name: String, amount: Long = 1) { values[name] = (values[name] ?: 0) + amount }
    fun snapshot(): Map<String, Long> {
        values["totalMs"] = elapsedMs
        values["sampledPeakPssKb"] = maxOf(values["sampledPeakPssKb"] ?: 0, Debug.getPss())
        return values.toMap()
    }
}
