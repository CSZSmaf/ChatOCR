package dev.chatocr.core

import kotlin.math.abs

object BubbleDetector {
    /** Detects solid chat bubbles, conservatively. Unassigned OCR remains reviewable. */
    fun detect(pixels: IntArray, width: Int, height: Int): List<Box> {
        require(pixels.size == width * height)
        fun channel(c: Int, shift: Int) = (c shr shift) and 255
        fun distance(a: Int, b: Int) = maxOf(abs(channel(a, 16) - channel(b, 16)), abs(channel(a, 8) - channel(b, 8)), abs(channel(a, 0) - channel(b, 0)))
        fun key(c: Int) = ((c shr 19) and 31) * 1024 + ((c shr 11) and 31) * 32 + ((c shr 3) and 31)
        val histogram = HashMap<Int, Int>()
        val representative = HashMap<Int, Int>()
        pixels.forEach { p -> val k = key(p); histogram[k] = (histogram[k] ?: 0) + 1; representative[k] = p }
        val background = representative[histogram.maxByOrNull { it.value }?.key] ?: return emptyList()
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val result = mutableListOf<Box>()
        for (start in pixels.indices) {
            if (visited[start] || distance(pixels[start], background) < 12) continue
            val color = pixels[start]
            var head = 0; var tail = 1; queue[0] = start; visited[start] = true
            var left = width; var right = 0; var top = height; var bottom = 0
            while (head < tail) {
                val p = queue[head++]; val x = p % width; val y = p / width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x < width - 1) p + 1 else -1, p - width, p + width)) {
                    if (n < 0 || n >= pixels.size || visited[n]) continue
                    if (distance(pixels[n], color) <= 18 && distance(pixels[n], background) >= 12) {
                        visited[n] = true; queue[tail++] = n
                    }
                }
            }
            val bw = right - left + 1; val bh = bottom - top + 1
            val clipped = top <= 1 || bottom >= height - 2
            if (bw < width * .065 || bh < (if (clipped) 2f else width * .035f) || bw > width * .88 || bh > height * .8) continue
            if (left < width * .085 || right > width * .925) continue // exclude avatars / screen edges
            if (tail.toFloat() / (bw * bh) < .48f) continue
            val scale = 1000f / width
            result += Box(left * scale, top * scale, (right + 1) * scale, (bottom + 1) * scale)
        }
        // Nested components from media content are not additional messages.
        return result.filter { box -> result.none { other -> other !== box && other.contains(box.centerX, box.centerY) && other.width * other.height > box.width * box.height } }
    }
}
