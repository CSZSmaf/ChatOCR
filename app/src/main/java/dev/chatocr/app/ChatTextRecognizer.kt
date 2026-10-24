package dev.chatocr.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import dev.chatocr.core.Box
import dev.chatocr.core.OcrLine
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/** Local PP-OCRv5 mobile recognition. BGR normalization and CTC follow RapidOCR (Apache-2.0). */
class ChatTextRecognizer(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val characters: List<String>
    init {
        val directory = File(context.filesDir, "chat-models-v1").apply { mkdirs() }
        val file = File(directory, "ch_PP-OCRv5_rec_mobile.onnx")
        if (!file.exists() || file.length() != 16631306L) {
            val temporary = File(directory, "recognizer.part")
            context.assets.open("models/${file.name}").use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.renameTo(file)) { "请释放存储空间后继续" }
        }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(file.absolutePath, options)
        }
        characters = listOf("") + requireNotNull(session.metadata.customMetadata["character"]).split('\n').dropLastWhile { it.isEmpty() } + " "
    }

    fun bubble(bitmap: Bitmap, box: Box, glyphHeight: Float): List<OcrLine> {
        val scale = bitmap.width / 1000f
        val bounds = Rect((box.left * scale).toInt().coerceIn(0, bitmap.width - 1),
            (box.top * scale).toInt().coerceIn(0, bitmap.height - 1),
            (box.right * scale).toInt().coerceIn(1, bitmap.width), (box.bottom * scale).toInt().coerceIn(1, bitmap.height))
        val inset = minOf((bitmap.width * .012f).toInt().coerceAtLeast(2), bounds.width() / 6, bounds.height() / 6)
        bounds.left += inset; bounds.right -= inset
        if (bounds.top > 8) bounds.top += inset
        if (bounds.bottom < bitmap.height - 8) bounds.bottom -= inset
        if (bounds.width() < 3 || bounds.height() < 3) return emptyList()
        val w = bounds.width(); val h = bounds.height()
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, bounds.left, bounds.top, w, h)
        val luminance = IntArray(pixels.size) { i -> val c = pixels[i]; (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100 }
        val levels = IntArray(256); luminance.forEach { levels[it]++ }
        var accumulated = 0
        val background = levels.indices.first { accumulated += levels[it]; accumulated >= pixels.size / 2 }
        val lightText = background <= 150
        val ink = BooleanArray(pixels.size) { if (lightText) luminance[it] > background + 55 else luminance[it] < background - 55 }
        val rows = IntArray(h) { y -> (0 until w).count { ink[y * w + it] } }
        val bands = mutableListOf<IntRange>()
        val minInk = maxOf(2, (w * .008f).toInt())
        val gap = maxOf(2, (glyphHeight * .22f).toInt())
        var start = -1; var last = -1
        for (y in 0 until h) {
            if (rows[y] >= minInk) { if (start < 0) start = y; last = y }
            if (start >= 0 && (y - last > gap || y == h - 1)) {
                bands += start..last; start = -1
            }
        }
        return bands.take(48).mapNotNull { band ->
            var left = w; var right = -1
            for (y in band) for (x in 0 until w) if (ink[y * w + x]) { left = minOf(left, x); right = maxOf(right, x) }
            if (right <= left) return@mapNotNull null
            val extra = ((glyphHeight * .7f - (band.last - band.first + 1)).coerceAtLeast(0f) / 2).toInt()
            val rect = Rect(bounds.left + (left - 3).coerceAtLeast(0), bounds.top + (band.first - 2 - extra).coerceAtLeast(0),
                bounds.left + (right + 4).coerceAtMost(w), bounds.top + (band.last + 3 + extra).coerceAtMost(h))
            recognize(bitmap, rect)?.takeIf { it.confidence >= .60f }
        }
    }

    fun line(bitmap: Bitmap, box: Box): OcrLine? {
        val scale = bitmap.width / 1000f
        return recognize(bitmap, Rect((box.left * scale - 2).toInt().coerceIn(0, bitmap.width - 1),
            (box.top * scale - 2).toInt().coerceIn(0, bitmap.height - 1),
            (box.right * scale + 2).toInt().coerceIn(1, bitmap.width), (box.bottom * scale + 2).toInt().coerceIn(1, bitmap.height)))
    }

    private fun recognize(bitmap: Bitmap, rect: Rect): OcrLine? {
        if (rect.width() < 2 || rect.height() < 2) return null
        val resizedWidth = ceil(rect.width().toDouble() / rect.height() * 48).toInt().coerceIn(4, 1200)
        val tensorWidth = maxOf(320, resizedWidth)
        val patch = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val resized = Bitmap.createScaledBitmap(patch, resizedWidth, 48, true)
        val pixels = IntArray(resizedWidth * 48)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, 48)
        if (resized !== patch) resized.recycle()
        if (patch !== bitmap) patch.recycle()
        val values = FloatArray(3 * 48 * tensorWidth)
        for (y in 0 until 48) for (x in 0 until resizedWidth) {
            val c = pixels[y * resizedWidth + x]; val index = y * tensorWidth + x
            values[index] = Color.blue(c) / 127.5f - 1
            values[48 * tensorWidth + index] = Color.green(c) / 127.5f - 1
            values[96 * tensorWidth + index] = Color.red(c) / 127.5f - 1
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 48, tensorWidth.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape; val classes = shape[2].toInt()
                check(classes == characters.size)
                val buffer = output.floatBuffer
                val text = StringBuilder(); var previous = -1; var confidence = 0f; var count = 0
                repeat(shape[1].toInt()) {
                    var best = 0; var probability = -Float.MAX_VALUE
                    for (i in 0 until classes) { val p = buffer.get(); if (p > probability) { best = i; probability = p } }
                    if (best != 0 && best != previous) { text.append(characters[best]); confidence += probability; count++ }
                    previous = best
                }
                if (count == 0 || text.isBlank()) return null
                val scale = 1000f / bitmap.width
                return OcrLine(text.toString(), Box(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale), confidence / count)
            }
        }
    }
    override fun close() = session.close()
}
