package dev.chatocr.core

/** A still frame is preferred, while animation cannot postpone recognition indefinitely. */
class StableFrameGate(private val stableMs: Long = 450, private val maxWaitMs: Long = 1600) {
    private var firstAt: Long? = null
    private var changedAt = 0L
    fun offer(now: Long, changed: Boolean) {
        if (firstAt == null) { firstAt = now; changedAt = now }
        if (changed) changedAt = now
    }
    fun ready(now: Long): Boolean = firstAt?.let { now - changedAt >= stableMs || now - it >= maxWaitMs } == true
    fun reset() { firstAt = null }
}
