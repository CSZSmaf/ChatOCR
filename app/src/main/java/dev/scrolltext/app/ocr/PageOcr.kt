package dev.scrolltext.app.ocr

import android.content.Context
import android.graphics.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.scrolltext.core.*
import dev.scrolltext.app.processing.RunMetrics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class PageOcr(context: Context, private val formulas: Boolean, private val metrics: RunMetrics = RunMetrics(), private val deferFormulas: Boolean = false) : AutoCloseable {
    private val text = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val formula by lazy { FormulaEngine(context) }
    private val formulaCache = mutableMapOf<String, FormulaResult>()

    suspend fun recognize(image: Bitmap, timeMs: Long, quality: Double, onMessage: (String) -> Unit = {}): List<Observation> {
        currentCoroutineContext().ensureActive()
        val regions = if (formulas) metrics.measure("formulaDetection") { formula.detect(image) } else emptyList()
        val masked = if (regions.isEmpty()) image else image.copy(Bitmap.Config.ARGB_8888, true).also { copy ->
            val canvas = Canvas(copy)
            for (region in regions) {
                val b = region.box
                val bg = image.getPixel(max(0, b.left.toInt() - 3), max(0, b.top.toInt() - 3))
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), Paint().apply { color = bg })
            }
        }
        val result = try { metrics.measureSuspending("textRecognition") { text.process(InputImage.fromBitmap(masked, 0)).await() } } finally { if (masked !== image) masked.recycle() }
        val pieces = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            Observation(line.text, Box(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()),
                line.confidence.toDouble().takeIf { it > 0.0 && it <= 1.0 } ?: .75, timeMs,
                sharpness = quality, clipped = r.top <= 3 || r.bottom >= image.height - 3)
        }.toMutableList()
        for ((index, region) in regions.withIndex()) {
            currentCoroutineContext().ensureActive(); onMessage("识别公式 ${index + 1} / ${regions.size}")
            val b = region.box
            val marker = "\\text{待识别公式}"
            val value = if (deferFormulas) marker else {
                val cut = Bitmap.createBitmap(image, b.left.toInt(), b.top.toInt(), b.width.toInt().coerceAtLeast(1), b.height.toInt().coerceAtLeast(1))
                val recognized = try {
                val signature = signature(cut)
                formulaCache[signature]?.also { metrics.add("formulaCacheHits") } ?: metrics.measureSuspending("formulaRecognition") {
                    metrics.add("formulaDecodes"); formula.recognize(cut).also { formulaCache[signature] = it }
                }
                } finally { if (cut !== image) cut.recycle() }
                recognized.latex.ifBlank { "\\text{待核对公式}" }
            }
            pieces += Observation(if (region.display) value else "\$$value\$", b, region.score, timeMs,
                kind = if (region.display) BlockKind.FORMULA else BlockKind.TEXT, clipped = b.top <= 3 || b.bottom >= image.height - 3,
                sharpness = quality, needsReview = true,
                formulaParts = if (deferFormulas) listOf(FormulaPart(marker, b)) else emptyList())
        }
        val rows = mutableListOf<MutableList<Observation>>()
        for (piece in pieces.sortedBy { it.box.centerY }) {
            val row = if (piece.kind == BlockKind.FORMULA) null else rows.lastOrNull { r ->
                r.none { it.kind == BlockKind.FORMULA } && abs(r.map { it.box.centerY }.average() - piece.box.centerY) <
                    max(piece.box.height, r.maxOf { it.box.height }) * .48 }
            if (row != null) row += piece else rows += mutableListOf(piece)
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.box.left }
            val parts = mutableListOf<FormulaPart>()
            val string = buildString {
                for (p in ordered) {
                    if (isNotEmpty() && !(last().code > 127 && p.text.firstOrNull()?.code?.let { it > 127 } == true)) append(' ')
                    var value = p.text
                    for (part in p.formulaParts) {
                        val marker = "\\text{待识别公式${parts.size + 1}}"
                        value = value.replace(part.marker, marker); parts += part.copy(marker = marker)
                    }
                    append(value)
                }
            }
            val first = ordered.first()
            first.copy(text = string, box = ordered.map { it.box }.reduce { a, b -> a.union(b) },
                confidence = ordered.minOf { it.confidence }, clipped = ordered.any { it.clipped }, needsReview = ordered.any { it.needsReview }, formulaParts = parts)
        }.sortedBy { it.box.top }
    }

    private fun signature(b: Bitmap): String {
        // Exact normalized pixels: do not reuse a visually similar but mathematically different equation.
        val pixels = IntArray(b.width * b.height); b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (p in pixels) { digest.update((p shr 16).toByte()); digest.update((p shr 8).toByte()); digest.update(p.toByte()) }
        return "${b.width}x${b.height}:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
    override fun close() { text.close(); if (formulas) formula.close() }
}
