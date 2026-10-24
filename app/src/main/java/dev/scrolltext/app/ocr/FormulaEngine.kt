package dev.scrolltext.app.ocr

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import dev.scrolltext.core.Box
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

data class FormulaRegion(val box: Box, val score: Double, val display: Boolean)
data class FormulaResult(val latex: String, val complete: Boolean)

/** Local ONNX port of RapidLaTeXOCR preprocessing/greedy decoding, with Pix2Text MFD.
 * See THIRD_PARTY_NOTICES.md for model provenance and upstream licenses. */
class FormulaEngine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        val folder = File(context.filesDir, "models-v1").apply { mkdirs() }
        val path = File(folder, name)
        if (!path.exists()) {
            val tmp = File(folder, "$name.part")
            context.assets.open("models/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(path)) { "模型解包失败，请检查剩余空间。" }
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            env.createSession(path.absolutePath, options)
        }
    }
    private val vocabulary by lazy {
        val vocab = JSONObject(context.assets.open("models/tokenizer.json").bufferedReader().use { it.readText() }).getJSONObject("model").getJSONObject("vocab")
        vocab.keys().asSequence().associate { vocab.getInt(it) to it }
    }

    fun detect(bitmap: Bitmap): List<FormulaRegion> {
        val size = 768
        val ratio = min(size.toDouble() / bitmap.width, size.toDouble() / bitmap.height)
        val rw = (bitmap.width * ratio).roundToInt(); val rh = (bitmap.height * ratio).roundToInt()
        val px = (size - rw) / 2; val py = (size - rh) / 2
        val letterbox = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(letterbox).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(bitmap, null, Rect(px, py, px + rw, py + rh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(size * size); letterbox.getPixels(pixels, 0, size, 0, 0, size, size); letterbox.recycle()
        val values = FloatArray(3 * size * size)
        for (i in pixels.indices) {
            values[i] = Color.red(pixels[i]) / 255f
            values[i + pixels.size] = Color.green(pixels[i]) / 255f
            values[i + pixels.size * 2] = Color.blue(pixels[i]) / 255f
        }
        val proposals = mutableListOf<FormulaRegion>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session("mfd.onnx").run(mapOf("images" to input)).use { result ->
                val out = result[0] as OnnxTensor
                val n = out.info.shape[2].toInt()
                val data = FloatArray(6 * n); out.floatBuffer.get(data)
                for (i in 0 until n) {
                    val score = max(data[4 * n + i], data[5 * n + i]).toDouble()
                    if (score < .4) continue
                    val cx = data[i]; val cy = data[n + i]; val w = data[2 * n + i]; val h = data[3 * n + i]
                    val box = Box(((cx - w / 2 - px) / ratio - 2).coerceIn(0.0, bitmap.width.toDouble()),
                        ((cy - h / 2 - py) / ratio - 2).coerceIn(0.0, bitmap.height.toDouble()),
                        ((cx + w / 2 - px) / ratio + 2).coerceIn(0.0, bitmap.width.toDouble()),
                        ((cy + h / 2 - py) / ratio + 2).coerceIn(0.0, bitmap.height.toDouble()))
                    if (box.width >= 8 && box.height >= 6) proposals += FormulaRegion(box, score, data[5 * n + i] > data[4 * n + i])
                }
            }
        }
        val selected = mutableListOf<FormulaRegion>()
        for (p in proposals.sortedByDescending { it.score }) {
            if (selected.none { iou(p.box, it.box) > .45 }) selected += p
        }
        return selected.sortedBy { it.box.top }
    }

    suspend fun recognize(bitmap: Bitmap): FormulaResult {
        currentCoroutineContext().ensureActive()
        var original = fit(pad(bitmap))
        var width = original.width; var height = original.height
        var inputBitmap: Bitmap? = null
        try {
            for (iteration in 0 until 10) {
                currentCoroutineContext().ensureActive()
                val resized = resizeGray(original, width.coerceAtLeast(1), height.coerceAtLeast(1), lanczos = width <= original.width)
                val bounded = fit(resized)
                val padded = pad(bounded)
                if (bounded !== original) bounded.recycle()
                if (resized !== original && resized !== bounded) resized.recycle()
                inputBitmap?.recycle(); inputBitmap = padded
                val newWidth = tensor(padded).use { input ->
                    session("image_resizer.onnx").run(mapOf("input" to input)).use { output ->
                        val buffer = (output[0] as OnnxTensor).floatBuffer
                        var best = 0; var maxValue = Float.NEGATIVE_INFINITY
                        for (i in 0 until buffer.remaining()) { val v = buffer[i]; if (v > maxValue) { best = i; maxValue = v } }
                        ((best + 1) * 32).coerceIn(32, 672)
                    }
                }
                if (newWidth == padded.width) break
                height = max(1, (height.toDouble() * newWidth / padded.width).toInt()); width = newWidth
            }
            val tokens = mutableListOf(1L)
            var complete = false
            tensor(inputBitmap!!).use { input ->
                session("encoder.onnx").run(mapOf("input" to input)).use { encoded ->
                    val encodedTensor = encoded[0] as OnnxTensor
                    for (i in 0 until 512) {
                        currentCoroutineContext().ensureActive()
                        OnnxTensor.createTensor(env, LongBuffer.wrap(tokens.toLongArray()), longArrayOf(1, tokens.size.toLong())).use { x ->
                            OnnxTensor.createTensor(env, arrayOf(BooleanArray(tokens.size) { true })).use { mask ->
                                session("decoder.onnx").run(mapOf("x" to x, "mask" to mask, "context" to encodedTensor)).use { prediction ->
                                    val logits = (prediction[0] as OnnxTensor).floatBuffer
                                    val offset = logits.remaining() - 8000
                                    var best = 0; var value = Float.NEGATIVE_INFINITY
                                    for (k in 0 until 8000) { val v = logits[offset + k]; if (v > value) { value = v; best = k } }
                                    tokens += best.toLong(); complete = best == 2
                                }
                            }
                        }
                        if (complete) break
                    }
                }
            }
            val latex = tokens.drop(1).filter { it > 2 }.joinToString("") { vocabulary[it.toInt()] ?: "" }
                .replace("Ġ", " ").replace("[PAD]", "").trim()
            return FormulaResult(latex, complete)
        } finally { original.recycle(); inputBitmap?.recycle() }
    }

    private fun tensor(image: Bitmap): OnnxTensor {
        val px = IntArray(image.width * image.height); image.getPixels(px, 0, image.width, 0, 0, image.width, image.height)
        val values = FloatArray(px.size) { (Color.red(px[it]) / 255f - .7931f) / .1738f }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 1, image.height.toLong(), image.width.toLong()))
    }

    /** Contrast normalization, dark-mode inversion, ink bounding box, white multiple-of-32 padding. */
    private fun pad(image: Bitmap): Bitmap {
        val w = image.width; val h = image.height
        val pixels = IntArray(w * h); image.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(pixels.size) { val p = pixels[it]; ((Color.red(p) * .299 + Color.green(p) * .587 + Color.blue(p) * .114)).roundToInt() }
        val lo = gray.min(); val hi = gray.max()
        require(hi - lo > 8) { "选区内没有清晰的公式。" }
        for (i in gray.indices) gray[i] = (gray[i] - lo) * 255 / (hi - lo)
        if (gray.average() < 128) for (i in gray.indices) gray[i] = 255 - gray[i]
        var left = w; var top = h; var right = 0; var bottom = 0
        for (y in 0 until h) for (x in 0 until w) if (gray[y * w + x] < 128) {
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }
        require(right >= left && bottom >= top) { "选区为空。" }
        val cw = right - left + 1; val ch = bottom - top + 1
        val pw = ((cw + 31) / 32) * 32; val ph = ((ch + 31) / 32) * 32
        val output = IntArray(pw * ph) { Color.WHITE }
        for (y in 0 until ch) for (x in 0 until cw) { val v = gray[(y + top) * w + x + left]; output[y * pw + x] = Color.rgb(v, v, v) }
        return Bitmap.createBitmap(output, pw, ph, Bitmap.Config.ARGB_8888)
    }

    private fun fit(image: Bitmap): Bitmap {
        val ratio = min(1.0, min(672.0 / image.width, 192.0 / image.height))
        if (ratio == 1.0) return image
        val result = resizeGray(image, max(1, (image.width * ratio).toInt()), max(1, (image.height * ratio).toInt()), lanczos = false)
        image.recycle(); return result
    }

    /** Scale-aware filtering preserves thin fraction bars during reduction, as upstream PIL does. */
    private fun resizeGray(image: Bitmap, width: Int, height: Int, lanczos: Boolean): Bitmap {
        if (width == image.width && height == image.height) return image
        data class Taps(val indices: IntArray, val weights: DoubleArray)
        fun taps(input: Int, output: Int): List<Taps> {
            val scale = input.toDouble() / output
            val filterScale = max(1.0, scale)
            val support = (if (lanczos) 3.0 else 1.0) * filterScale
            fun kernel(value: Double): Double {
                val x = abs(value)
                if (!lanczos) return max(0.0, 1.0 - x)
                if (x < 1e-8) return 1.0
                if (x >= 3.0) return 0.0
                return sin(PI * x) * sin(PI * x / 3.0) / (PI * PI * x * x / 3.0)
            }
            return (0 until output).map { i ->
                val center = (i + .5) * scale
                val left = max(0, (center - support + .5).toInt())
                val right = min(input, (center + support + .5).toInt())
                val indices = IntArray(max(1, right - left)) { (left + it).coerceAtMost(input - 1) }
                val weights = DoubleArray(indices.size) { kernel((indices[it] + .5 - center) / filterScale) }
                val sum = weights.sum()
                for (j in weights.indices) weights[j] /= sum
                Taps(indices, weights)
            }
        }
        val source = IntArray(image.width * image.height)
        image.getPixels(source, 0, image.width, 0, 0, image.width, image.height)
        val horizontal = IntArray(width * image.height)
        val xTaps = taps(image.width, width); val yTaps = taps(image.height, height)
        for (y in 0 until image.height) for (x in 0 until width) {
            val tap = xTaps[x]; var v = 0.0
            for (k in tap.indices.indices) v += (source[y * image.width + tap.indices[k]] and 255) * tap.weights[k]
            horizontal[y * width + x] = v.roundToInt().coerceIn(0,255)
        }
        val output = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val tap = yTaps[y]; var v = 0.0
            for (k in tap.indices.indices) v += horizontal[tap.indices[k] * width + x] * tap.weights[k]
            val gray = v.roundToInt().coerceIn(0,255)
            output[y * width + x] = Color.rgb(gray,gray,gray)
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
    private fun iou(a: Box, b: Box): Double {
        val intersection = max(0.0, min(a.right, b.right) - max(a.left, b.left)) * max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection / max(1.0, a.width * a.height + b.width * b.height - intersection)
    }
    override fun close() { sessions.values.forEach { it.close() }; sessions.clear() }
}
