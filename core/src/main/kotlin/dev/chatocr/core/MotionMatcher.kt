package dev.chatocr.core

import kotlin.math.abs

data class GrayFrame(val width: Int, val height: Int, val values: IntArray)
object MotionMatcher {
    /** Returns movement of content in pixels, or null when overlap is not reliable. */
    fun shift(previous: GrayFrame, current: GrayFrame, maxFraction: Float = .65f): Int? {
        if (previous.width != current.width || previous.height != current.height) return null
        val w = current.width; val h = current.height
        if (w < 12 || h < 30) return null
        val scored = mutableListOf<Pair<Int, Double>>()
        val limit = (h * maxFraction.coerceIn(.02f, .65f)).toInt()
        for (dy in -limit..limit) {
            var total = 0.0; var count = 0
            for (y in maxOf(2, -dy + 2) until minOf(h - 2, h - dy - 2) step 2) {
                for (x in 6 until w - 6 step 2) {
                    val p = y * w + x; val q = (y + dy) * w + x
                    // Weight text edges; plain background cannot establish continuity.
                    val edge = abs(previous.values[p] - previous.values[p - w]) + abs(previous.values[p] - previous.values[p - 1])
                    if (edge < 20) continue
                    total += abs(previous.values[p] - current.values[q]); count++
                }
            }
            if (count > 60) scored += dy to total / count
        }
        val best = scored.minByOrNull { it.second } ?: return null
        val rival = scored.filter { abs(it.first - best.first) > 5 }.minOfOrNull { it.second } ?: 255.0
        return if (best.second < 24 && rival - best.second > 4) best.first else null
    }
}
