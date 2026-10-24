package dev.chatocr.core

import kotlin.math.abs

/** Conservative connected foreground regions for photos/stickers without solid bubble borders. */
object MediaRegionDetector {
    fun detect(pixels: IntArray, width: Int, height: Int): List<Box> {
        require(pixels.size == width * height)
        fun key(c: Int) = ((c ushr 20) and 15) * 256 + ((c ushr 12) and 15) * 16 + ((c ushr 4) and 15)
        val histogram = pixels.asSequence().groupingBy { key(it) }.eachCount()
        val background = histogram.maxByOrNull { it.value }?.key ?: return emptyList()
        val bg = pixels.first { key(it) == background }
        fun different(c: Int) = maxOf(abs(((c shr 16) and 255) - ((bg shr 16) and 255)),
            abs(((c shr 8) and 255) - ((bg shr 8) and 255)), abs((c and 255) - (bg and 255))) > 24
        val mask = BooleanArray(pixels.size) { different(pixels[it]) }
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val result = mutableListOf<Box>()
        for (start in pixels.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0; var tail = 1; queue[0] = start; visited[start] = true
            var left = width; var right = 0; var top = height; var bottom = 0
            while (head < tail) {
                val p = queue[head++]; val x = p % width; val y = p / width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val q = ny * width + nx
                    if (!visited[q] && mask[q]) { visited[q] = true; queue[tail++] = q }
                }
            }
            val w = right - left + 1; val h = bottom - top + 1
            val clipped = top <= 1 || bottom >= height - 2
            if (left < width * .09 || right > width * .925 || w < width * .14 || h < (if (clipped) 2.0 else width * .12) ||
                w > width * .82 || h > height * .75 || tail < w * h * .38) continue
            val colors = HashMap<Int, Int>()
            var saturated = 0
            for (y in top..bottom) for (x in left..right) {
                val c = pixels[y * width + x]; val k = key(c); colors[k] = (colors[k] ?: 0) + 1
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (maxOf(r, g, b) - minOf(r, g, b) > 40) saturated++
            }
            // A cut row of anti-aliased Chinese glyphs is not a picture fragment.
            if (clipped && h < width * .12 && saturated < w * h * .4) continue
            // Uniform text bubbles and single text strokes are not photo regions.
            if (colors.size < 5 || (colors.values.maxOrNull() ?: 0) > w * h * .62) continue
            val scale = 1000f / width
            result += Box((left - 1).coerceAtLeast(0) * scale, (top - 1).coerceAtLeast(0) * scale,
                (right + 2).coerceAtMost(width) * scale, (bottom + 2).coerceAtMost(height) * scale)
        }
        return result.filter { b -> result.none { it !== b && it.contains(b.centerX, b.centerY) && it.width * it.height > b.width * b.height } }
    }
}
