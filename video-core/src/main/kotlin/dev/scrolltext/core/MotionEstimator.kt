package dev.scrolltext.core

import kotlin.math.*

/** Horizontal image gradients suppress flat margins; matching uses signed vertical translation.
 * No assumption about the direction or velocity of the user's scrolling is made. */
class GrayFrame(val width: Int, val height: Int, val pixels: FloatArray) {
    init { require(width >= 8 && height >= 8 && pixels.size == width * height) }
    val edges = FloatArray(pixels.size).also { out ->
        for (y in 0 until height) for (x in 1 until width - 1)
            out[y * width + x] = pixels[y * width + x + 1] - pixels[y * width + x - 1]
    }
    val sharpness: Double get() = edges.sumOf { (it * it).toDouble() } / edges.size
}

data class Motion(val dy: Double, val score: Double, val reliable: Boolean)

object MotionEstimator {
    fun estimate(previous: GrayFrame, current: GrayFrame): Motion {
        require(previous.width == current.width && previous.height == current.height)
        val w = previous.width; val h = previous.height
        if (min(previous.sharpness, current.sharpness) < 1.5) return Motion(0.0, 0.0, false)
        fun correlation(dy: Int, dx: Int): Double {
            val from = max(2, -dy + 2); val to = min(h - 2, h - dy - 2)
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (y in from until to step 2) for (x in 4 until w - 4 step 3) {
                val a = previous.edges[(y + dy) * w + x + dx].toDouble()
                val b = current.edges[y * w + x].toDouble()
                dot += a * b; aa += a * a; bb += b * b
            }
            return if (min(aa, bb) < 100) -1.0 else dot / sqrt(aa * bb)
        }
        val stationary = correlation(0, 0)
        if (stationary > .994) return Motion(0.0, stationary, true)
        val candidates = mutableListOf<Pair<Int, Double>>()
        val limit = (h * .82).toInt()
        // Test every row: skipping alternate rows aliases thin strokes and can miss a true match.
        for (dy in -limit..limit) candidates += dy to correlation(dy, 0)
        val peaks = candidates.sortedByDescending { it.second }.take(5)
        var bestDy = 0; var bestScore = -1.0
        for ((dy, _) in peaks) for (near in dy - 1..dy + 1) for (dx in -1..1) {
            val score = correlation(near, dx)
            if (score > bestScore) { bestScore = score; bestDy = near }
        }
        val rival = candidates.filter { abs(it.first - bestDy) > 5 }.maxOfOrNull { it.second } ?: -1.0
        return Motion(bestDy.toDouble(), bestScore, bestScore > .66 && bestScore - rival > .045)
    }
}
