package dev.chatocr.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.chatocr.core.*
import kotlinx.coroutines.tasks.await
import android.os.SystemClock

enum class RefinementPolicy { ALL, OPTIMIZED }
data class OcrReadingComparison(val original: String, val refined: String, val originalQuality: Double,
    val refinedQuality: Double, val minConfidence: Float, val glyphHeight: Float, val selected: Boolean)
data class OcrTiming(val pageMs: Long, val refinementMs: Long, val totalMs: Long, val bubbles: Int, val refined: Int, val atlasPixels: Int,
    val prepareMs: Long = 0, val cacheHit: Boolean = false)

class PageOcr(private val policy: RefinementPolicy = RefinementPolicy.OPTIMIZED,
    private val comparison: ((OcrReadingComparison) -> Unit)? = null) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    var lastTiming = OcrTiming(0, 0, 0, 0, 0, 0)
        private set
    private val histogram = IntArray(32768)
    private val grayLevels = IntArray(256) { contrast ->
        val v = (255 - ((contrast - 8).coerceAtLeast(0) * 1.4f).toInt()).coerceIn(0, 255)
        Color.rgb(v, v, v)
    }
    suspend fun read(bitmap: Bitmap, group: Boolean): List<Candidate> {
        val started = SystemClock.elapsedRealtime()
        var refinedCount = 0; var atlasPixels = 0; var prepareMs = 0L
        val scale = 1000f / bitmap.width
        val small = Bitmap.createScaledBitmap(bitmap, 180, (bitmap.height * 180f / bitmap.width).toInt().coerceAtLeast(1), true)
        val pixels = IntArray(small.width * small.height); small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val solidBoxes = BubbleDetector.detect(pixels, small.width, small.height)
        val mediaBoxes = MediaRegionDetector.detect(pixels, small.width, small.height).filter { media ->
            val clipped = media.top < 36 || media.bottom > bitmap.height * scale - 36
            !clipped || solidBoxes.none { b -> b.left <= media.left + 6 && b.right >= media.right - 6 &&
                b.top <= media.top + 6 && b.bottom >= media.bottom - 6 && b.width > media.width * 1.2f }
        }
        val boxes = solidBoxes.filter { b ->
            mediaBoxes.none { it.contains(b.centerX, b.centerY) }
        }
        small.recycle()
        // Extend each solid bubble's background sideways before OCR. This removes rounded
        // bubble edges which the recognizer can hallucinate as '|' without changing text pixels.
        // Source evidence always remains the original bitmap.
        val input = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(input); val paint = Paint()
        val interiors = boxes.mapNotNull { box ->
            val l = (box.left / scale).toInt().coerceIn(0, bitmap.width - 1)
            val r = (box.right / scale).toInt().coerceIn(l + 1, bitmap.width)
            val t = (box.top / scale).toInt().coerceIn(0, bitmap.height - 1)
            val b = (box.bottom / scale).toInt().coerceIn(t + 1, bitmap.height)
            val inset = minOf(7, (b - t) / 8, (r - l) / 8)
            if (inset < 1) return@mapNotNull null
            paint.color = bitmap.getPixel((l + r) / 2, (t + inset).coerceAtMost(b - 1))
            canvas.drawRect(0f, t.toFloat(), bitmap.width.toFloat(), b.toFloat(), paint)
            Rect(l + inset, if (t < 8) t else t + inset, r - inset, if (b > bitmap.height - 8) b else b - inset)
        }
        interiors.forEach { canvas.drawBitmap(bitmap, it, it, null) }
        val text = try { recognizer.process(InputImage.fromBitmap(input, 0)).await() } finally { input.recycle() }
        val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            line.boundingBox?.let { b -> OcrLine(line.text, Box(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale), line.confidence) }
        }.toMutableList()
        val alternatives = HashMap<Box, List<String>>()
        val pageFinished = SystemClock.elapsedRealtime()
        fun quality(parts: List<OcrLine>) = if (parts.isEmpty()) 0.0 else parts.sumOf { it.confidence.toDouble() * it.text.length } / parts.sumOf { it.text.length }.coerceAtLeast(1)
        fun reading(parts: List<OcrLine>) = parts.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left }).joinToString("\n") { it.text.trim() }
        // Preserve every tile and its geometry: selective repacking changed OCR results
        // on the regression set. Optimize pixel preparation, not the observed evidence.
        val selectedBoxes = boxes
        // Re-read solid bubbles in a padded atlas. Small Chinese glyphs are enlarged, dark / blue
        // backgrounds become white, and avatars / rounded tails cannot masquerade as characters.
        // Keep the full-page pass for timestamps, nicknames and undetected layouts.
        if (selectedBoxes.isNotEmpty()) {
            data class Tile(val original: Rect, val destination: Rect, val bubble: Box)
            val tiles = mutableListOf<Tile>(); var cursor = 24
            for (box in selectedBoxes) {
                val l = (box.left / scale).toInt().coerceIn(0, bitmap.width - 1)
                val r = (box.right / scale).toInt().coerceIn(l + 1, bitmap.width)
                val t = (box.top / scale).toInt().coerceIn(0, bitmap.height - 1)
                val b = (box.bottom / scale).toInt().coerceIn(t + 1, bitmap.height)
                val inset = minOf(18, (r - l) / 5, (b - t) / 5)
                val source = Rect(l + inset, if (t < 8) t else t + inset, r - inset, if (b > bitmap.height - 8) b else b - inset)
                if (source.width() < 8 || source.height() < 6) continue
                val dest = Rect(24, cursor, 24 + (source.width() * 2f).toInt(), cursor + (source.height() * 2f).toInt())
                if (dest.bottom > 6000) break // Bound memory even for malformed detections.
                tiles += Tile(source, dest, box); cursor = dest.bottom + 48
            }
            if (tiles.isNotEmpty()) {
                val atlas = Bitmap.createBitmap(tiles.maxOf { it.destination.right } + 24, cursor, Bitmap.Config.ARGB_8888)
                refinedCount = tiles.size; atlasPixels = atlas.width * atlas.height
                try {
                    val prepareStarted = SystemClock.elapsedRealtime()
                    val ac = Canvas(atlas); ac.drawColor(Color.WHITE)
                    val filter = Paint(Paint.FILTER_BITMAP_FLAG)
                    for (tile in tiles) {
                        val src = tile.original; val values = IntArray(src.width() * src.height())
                        bitmap.getPixels(values, 0, src.width(), src.left, src.top, src.width(), src.height())
                        val bg = backgroundColor(values, policy == RefinementPolicy.OPTIMIZED)
                        val br = Color.red(bg); val bgc = Color.green(bg); val bb = Color.blue(bg)
                        for (i in values.indices) {
                            val color = values[i]
                            val contrast = maxOf(kotlin.math.abs(Color.red(color) - br), kotlin.math.abs(Color.green(color) - bgc), kotlin.math.abs(Color.blue(color) - bb))
                            values[i] = if (policy == RefinementPolicy.OPTIMIZED) grayLevels[contrast] else {
                                val v = (255 - ((contrast - 8).coerceAtLeast(0) * 1.4f).toInt()).coerceIn(0, 255)
                                Color.rgb(v, v, v)
                            }
                        }
                        val patch = Bitmap.createBitmap(values, src.width(), src.height(), Bitmap.Config.ARGB_8888)
                        try { ac.drawBitmap(patch, null, tile.destination, filter) } finally { patch.recycle() }
                    }
                    prepareMs = SystemClock.elapsedRealtime() - prepareStarted
                    val refined = recognizer.process(InputImage.fromBitmap(atlas, 0)).await()
                    val atlasLines = refined.textBlocks.flatMap { it.lines }
                    for (tile in tiles) {
                        val found = atlasLines.mapNotNull { line ->
                            val bounds = line.boundingBox ?: return@mapNotNull null
                            val dest = tile.destination; val src = tile.original
                            if (!dest.contains(bounds.centerX(), bounds.centerY())) return@mapNotNull null
                            val sx = src.width().toFloat() / dest.width(); val sy = src.height().toFloat() / dest.height()
                            OcrLine(line.text, Box((src.left + (bounds.left - dest.left) * sx) * scale,
                                (src.top + (bounds.top - dest.top) * sy) * scale, (src.left + (bounds.right - dest.left) * sx) * scale,
                                (src.top + (bounds.bottom - dest.top) * sy) * scale), line.confidence)
                        }
                        if (found.isNotEmpty()) {
                            val original = lines.filter { tile.bubble.contains(it.box.centerX, it.box.centerY) }
                            val oldText = reading(original); val newText = reading(found)
                            comparison?.invoke(OcrReadingComparison(oldText, newText, quality(original), quality(found), original.minOfOrNull { it.confidence } ?: 0f,
                                (original.minOfOrNull { it.box.height } ?: 0f) / scale, true))
                            if (ChatAssembler.normalized(oldText) != ChatAssembler.normalized(newText) && oldText.isNotBlank())
                                alternatives[tile.bubble] = listOf(oldText, newText)
                            // Confidence ranks two observations; it is not a guarantee of correctness.
                            if (original.isEmpty() || quality(found) >= quality(original)) {
                                lines.removeAll { tile.bubble.contains(it.box.centerX, it.box.centerY) }
                                lines += found
                            }
                        }
                    }
                } finally { atlas.recycle() }
            }
        }
        lastTiming = OcrTiming(pageFinished - started, SystemClock.elapsedRealtime() - pageFinished, SystemClock.elapsedRealtime() - started, boxes.size, refinedCount, atlasPixels, prepareMs)
        val mediaNames = mutableSetOf<OcrLine>()
        val media = mediaBoxes.map { box ->
            val side = when { box.left < 160 && box.right < 940 -> Side.LEFT; box.right > 840 && box.left > 60 -> Side.RIGHT; else -> Side.UNKNOWN }
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
        return (ChatParser.parse(lines.filter { line -> line !in mediaNames && mediaBoxes.none { it.contains(line.box.centerX, line.box.centerY) } }, boxes, bitmap.height * scale, group).map { candidate ->
            val variants = alternatives[candidate.box].orEmpty()
            if (variants.isEmpty()) candidate else candidate.copy(recognitionAlternatives = variants, flags = candidate.flags + "两次识别文字不同，请核对")
        } + media).sortedBy { it.box.top }
    }
    private fun backgroundColor(values: IntArray, optimized: Boolean): Int {
        if (optimized) {
            histogram.fill(0)
            var winner = 0; var most = 0; var tied = false
            for (i in values.indices step 3) {
                val c = values[i]
                val key = ((c ushr 9) and 0x7c00) or ((c ushr 6) and 0x03e0) or ((c ushr 3) and 0x001f)
                val n = ++histogram[key]
                if (n > most) { winner = key; most = n; tied = false }
                else if (n == most && key != winner) tied = true
            }
            if (!tied) return ((winner and 0x7c00) shl 9) or ((winner and 0x03e0) shl 6) or ((winner and 0x001f) shl 3)
        }
        // Keep the original tie-breaking behavior for unusual multicolor regions.
        val counts = HashMap<Int, Int>()
        for (i in values.indices step 3) { val color = values[i] and 0x00f8f8f8; counts[color] = (counts[color] ?: 0) + 1 }
        return counts.maxBy { it.value }.key
    }
    override fun close() = recognizer.close()
    companion object {
        fun gray(bitmap: Bitmap): GrayFrame {
            val small = Bitmap.createScaledBitmap(bitmap, 96, (bitmap.height * 96f / bitmap.width).toInt().coerceAtLeast(1), true)
            val pixels = IntArray(small.width * small.height); small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            for (i in pixels.indices) { val c = pixels[i]; pixels[i] = (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100 }
            val frame = GrayFrame(small.width, small.height, pixels); small.recycle(); return frame
        }
        fun difference(a: GrayFrame, b: GrayFrame): Double {
            if (a.width != b.width || a.height != b.height) return 255.0
            var total = 0L
            a.values.indices.forEach { total += kotlin.math.abs(a.values[it] - b.values[it]) }
            return total.toDouble() / a.values.size
        }
    }
}
