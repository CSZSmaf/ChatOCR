package dev.chatocr.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.chatocr.core.*
import kotlinx.coroutines.tasks.await

data class OcrReadingComparison(val original: String, val refined: String, val originalQuality: Double,
    val refinedQuality: Double, val minConfidence: Float, val glyphHeight: Float, val selected: Boolean)
data class OcrTiming(val pageMs: Long, val refinementMs: Long, val totalMs: Long, val bubbles: Int, val refined: Int, val atlasPixels: Int,
    val prepareMs: Long = 0, val cacheHit: Boolean = false)

class PageOcr(context: Context,
    private val comparison: ((OcrReadingComparison) -> Unit)? = null) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val chineseRuntime = lazy { ChatTextRecognizer(context.applicationContext) }
    private val chinese by chineseRuntime
    var lastTiming = OcrTiming(0, 0, 0, 0, 0, 0)
        private set

    suspend fun read(bitmap: Bitmap, group: Boolean): List<Candidate> {
        val started = SystemClock.elapsedRealtime()
        val scale = 1000f / bitmap.width
        val small = Bitmap.createScaledBitmap(bitmap, 180, (bitmap.height * 180f / bitmap.width).toInt().coerceAtLeast(1), true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val solids = BubbleDetector.detect(pixels, small.width, small.height)
        val foreground = MediaRegionDetector.detect(pixels, small.width, small.height, includeSolid = true)
        val regions = foreground.flatMap { region ->
            // Solid interiors are more stable than compressed, feathered outside borders.
            val inside = solids.filter { region.contains(it.centerX, it.centerY) && it.width >= region.width * .65f }
            val whole = inside.filter { it.height >= region.height * .75f }.maxByOrNull { it.width * it.height }
            val rows = inside.filter { it.height > region.height * .15f }.sortedBy { it.top }
            when {
                whole != null -> listOf(whole)
                region.height < region.width * 1.25f && rows.size >= 2 && rows.zipWithNext().all { (a, b) -> a.bottom <= b.top } &&
                    rows.sumOf { it.height.toDouble() } > region.height * .5 -> rows
                else -> listOf(region)
            }
        } + solids.filter { box -> foreground.none { it.contains(box.centerX, box.centerY) } }
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { b -> OcrLine(line.text, Box(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale), line.confidence) }
        }.toMutableList()
        val layout = ChatLayout.classify(pixels, small.width, small.height, regions, lines)
        small.recycle()
        val boxes = layout.text; val mediaBoxes = layout.media
        val alternatives = HashMap<Box, List<String>>()
        val pageFinished = SystemClock.elapsedRealtime()
        fun quality(parts: List<OcrLine>) = if (parts.isEmpty()) 0.0 else parts.sumOf { it.confidence.toDouble() * it.text.length } / parts.sumOf { it.text.length }.coerceAtLeast(1)
        fun reading(parts: List<OcrLine>) = parts.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left }).joinToString("\n") { it.text.trim() }
        val sizes = lines.map { it.box.height / scale }.filter { it in 12f..80f }.sorted()
        val glyphHeight = sizes.getOrNull(sizes.size / 2) ?: bitmap.width * .04f
        for (box in boxes) {
            val original = lines.filter { box.contains(it.box.centerX, it.box.centerY) }
            val found = chinese.bubble(bitmap, box, glyphHeight)
            val selected = found.isNotEmpty() && (quality(found) >= .80 || quality(found) >= quality(original) + .1)
            val oldText = reading(original); val newText = reading(found)
            comparison?.invoke(OcrReadingComparison(oldText, newText, quality(original), quality(found),
                original.minOfOrNull { it.confidence } ?: 0f, glyphHeight, selected))
            if (selected) {
                lines.removeAll { box.contains(it.box.centerX, it.box.centerY) }; lines += found
                if (oldText.isNotBlank() && ChatAssembler.normalized(oldText) != ChatAssembler.normalized(newText)) alternatives[box] = listOf(oldText, newText)
            }
        }
        // Dates, nicknames and system notices use the same recognizer with their detected row bounds.
        for (line in lines.toList()) {
            if (boxes.any { it.contains(line.box.centerX, line.box.centerY) } || mediaBoxes.any { it.contains(line.box.centerX, line.box.centerY) }) continue
            val fresh = chinese.line(bitmap, line.box)
            if (fresh != null && fresh.confidence >= .85f) { lines.remove(line); lines += fresh.copy(box = line.box) }
        }
        val mediaNames = mutableSetOf<OcrLine>()
        val media = mediaBoxes.map { box ->
            val side = layout.sides[box] ?: ChatParser.bubbleSide(box)
            val name = if (group && side == Side.LEFT) lines.filter { line ->
                line.box.bottom <= box.top + 3 && box.top - line.box.bottom in -3f..42f &&
                    kotlin.math.abs(line.box.left - box.left) < 45 && line.box.height < 40 && line.text.length <= 32 &&
                    boxes.none { it.contains(line.box.centerX, line.box.centerY) } && mediaBoxes.none { it.contains(line.box.centerX, line.box.centerY) }
            }.maxByOrNull { it.box.bottom } else null
            if (name != null) mediaNames += name
            Candidate("[图片 / 表情]", side, Kind.MEDIA, box, buildList {
                add("图片或表情包，边界请核对")
                if (box.top < 36 || box.bottom > bitmap.height * scale - 36) add("画面边缘，可能不完整")
                if (side == Side.UNKNOWN) add("发送方待确认")
                if (group && side == Side.LEFT) add(if (name == null) "群成员待确认" else "群成员昵称来自识别，请核对")
            }, sender = name?.text?.trim())
        }
        lastTiming = OcrTiming(pageFinished - started, SystemClock.elapsedRealtime() - pageFinished,
            SystemClock.elapsedRealtime() - started, boxes.size, boxes.size, 0)
        return (ChatParser.parse(lines.filter { line -> line !in mediaNames && mediaBoxes.none { it.contains(line.box.centerX, line.box.centerY) } },
            boxes, bitmap.height * scale, group, layout.sides).map { candidate ->
            val variants = alternatives[candidate.box].orEmpty()
            val confidence = quality(lines.filter { candidate.box.contains(it.box.centerX, it.box.centerY) }).toFloat()
            candidate.copy(recognitionAlternatives = variants, confidence = confidence,
                flags = candidate.flags + if (candidate.kind == Kind.TEXT && confidence < .85f) listOf("两次识别文字不同，请核对") else emptyList())
        } + media).sortedBy { it.box.top }
    }
    override fun close() { recognizer.close(); if (chineseRuntime.isInitialized()) chinese.close() }
    companion object {
        fun gray(bitmap: Bitmap): GrayFrame {
            val w = 96; val h = (bitmap.height * 96f / bitmap.width).toInt().coerceAtLeast(1)
            val source = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val values = IntArray(w * h)
            // Average all source pixels in each cell. Point sampling aliases narrow Chinese
            // strokes and makes two overlapping scroll frames look unrelated.
            for (y in 0 until h) for (x in 0 until w) {
                val y0 = y * bitmap.height / h; val y1 = maxOf(y0 + 1, (y + 1) * bitmap.height / h).coerceAtMost(bitmap.height)
                val x0 = x * bitmap.width / w; val x1 = maxOf(x0 + 1, (x + 1) * bitmap.width / w).coerceAtMost(bitmap.width)
                var sum = 0L
                for (sy in y0 until y1) for (sx in x0 until x1) {
                    val c = source[sy * bitmap.width + sx]
                    sum += (c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11
                }
                values[y * w + x] = (sum / ((y1 - y0) * (x1 - x0) * 100)).toInt()
            }
            return GrayFrame(w, h, values)
        }
        fun difference(a: GrayFrame, b: GrayFrame): Double {
            if (a.width != b.width || a.height != b.height) return 255.0
            var total = 0L
            a.values.indices.forEach { total += kotlin.math.abs(a.values[it] - b.values[it]) }
            return total.toDouble() / a.values.size
        }
    }
}
