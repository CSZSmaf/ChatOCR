package dev.chatocr.core

import kotlin.math.abs

/** Distinguish message typography from text inside a shared screenshot. Coordinates have width 1000. */
object ChatLayout {
    data class Regions(val text: List<Box>, val media: List<Box>, val sides: Map<Box, Side>)

    fun classify(pixels: IntArray, width: Int, height: Int, regions: List<Box>, lines: List<OcrLine>): Regions {
        require(pixels.size == width * height)
        val fontHeights = lines.filter { it.text.trim().length >= 2 && it.box.height in 24f..80f }
            .map { it.box.height }.sorted()
        val fontHeight = fontHeights.getOrNull(fontHeights.size / 2) ?: 36f
        val text = mutableListOf<Box>(); val media = mutableListOf<Box>(); val sides = mutableMapOf<Box, Side>()
        fun distance(a: Int, b: Int) = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
            abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
        val background = pixels.asSequence().groupingBy { it and 0x00f8f8f8 }.eachCount().maxBy { it.value }.key or 0x00040404
        for (box in regions) {
            val left = (box.left * width / 1000).toInt().coerceIn(0, width - 1)
            val right = (box.right * width / 1000).toInt().coerceIn(left + 1, width)
            val top = (box.top * width / 1000).toInt().coerceIn(0, height - 1)
            val bottom = (box.bottom * width / 1000).toInt().coerceIn(top + 1, height)
            val colors = HashMap<Int, Int>()
            for (y in top until bottom) for (x in left until right) {
                val key = pixels[y * width + x] and 0x00f8f8f8
                colors[key] = (colors[key] ?: 0) + 1
            }
            var mode = (colors.maxByOrNull { it.value }?.key ?: 0) or 0x00040404
            // Foreground text strokes can form connected components of their own. A region
            // whose dominant color is the page background is not a bubble container.
            val sameBackground = distance(mode, background) < 7
            if (sameBackground && (box.height < 65 || box.width < 150)) continue
            if (sameBackground) {
                // Compression spreads a blue bubble across several bins while its narrow
                // outside margin stays one uniform bin. Rank the actual foreground here.
                mode = (colors.filterKeys { distance(it or 0x00040404, background) >= 7 }.maxByOrNull { it.value }?.key ?: mode) or 0x00040404
            }
            var flatPixels = 0
            val inset = minOf(2, (right - left - 1) / 4, (bottom - top - 1) / 4)
            for (y in top + inset until bottom - inset) for (x in left + inset until right - inset)
                if (distance(pixels[y * width + x], mode) < 45) flatPixels++
            val flat = distance(mode, background) >= 7 && flatPixels.toFloat() / ((right - left - 2 * inset) * (bottom - top - 2 * inset)) > .50f
            val r = mode shr 16 and 255; val g = mode shr 8 and 255; val b = mode and 255
            val outgoingColor = flat && (g > r + 30 && g > b + 15 || b > r + 40 && g > r + 20)
            val side = if (outgoingColor && box.right > 800) Side.RIGHT else ChatParser.bubbleSide(box)
            sides[box] = side
            val inside = lines.filter { box.contains(it.box.centerX, it.box.centerY) }
            val chatGlyphs = inside.any { it.box.height >= maxOf(22f, fontHeight * .67f) }
            val screenshot = box.height > box.width * 1.25f && inside.size >= 3 && inside.count { it.box.height < fontHeight * .6f } >= 2
            val unreadBubble = inside.isEmpty() && flat && box.height < maxOf(150f, fontHeight * 3)
            if (!screenshot && (flat && chatGlyphs || unreadBubble)) text += box
            else {
                if (side == Side.UNKNOWN && box.left < 400 && box.right < 550) sides[box] = Side.LEFT
                if (sides[box] != Side.UNKNOWN || box.top >= 36 && box.bottom <= height * 1000f / width - 36) media += box
            }
        }
        return Regions(text, media, sides)
    }
}
