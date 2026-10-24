package dev.scrolltext.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.scrolltext.core.Crop
import dev.scrolltext.core.GrayFrame
import kotlinx.coroutines.CancellationException
import kotlin.math.*

class VideoFrames(context: Context, uri: Uri, sequential: Boolean = false) : AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }
    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
    init { require(durationMs > 0) { "无法读取录屏时长，请选择手机相册中可正常播放的视频。" } }
    private var decoder = if (sequential) runCatching { SequentialDecoder(context, uri) }.getOrNull() else null
    var usedCompatibilityDecoder = sequential && decoder == null; private set
    suspend fun sample(timeMs: Long): Bitmap {
        decoder?.let {
            try { return it.frame(timeMs) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { it.close(); decoder = null; usedCompatibilityDecoder = true }
        }
        return frame(timeMs)
    }
    fun frame(timeMs: Long): Bitmap = retriever.getFrameAtTime(timeMs.coerceIn(0, durationMs - 1) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        ?: error("无法解码 ${timeMs / 1000} 秒处的画面。请将录屏转为 H.264 MP4 后重试。")
    fun crop(frame: Bitmap, crop: Crop): Bitmap {
        require(crop.valid())
        val left = (crop.left * frame.width).toInt(); val top = (crop.top * frame.height).toInt()
        val w = ((crop.right - crop.left) * frame.width).toInt().coerceAtMost(frame.width - left)
        val h = ((crop.bottom - crop.top) * frame.height).toInt().coerceAtMost(frame.height - top)
        require(w >= 32 && h >= 32) { "选区太小，请扩大正文区域。" }
        val patch = Bitmap.createBitmap(frame, left, top, w, h)
        if (w <= 1200) return patch
        val scaled = Bitmap.createScaledBitmap(patch, 1200, (h * 1200.0 / w).roundToInt(), true)
        if (patch !== frame) patch.recycle()
        return scaled
    }
    override fun close() { decoder?.close(); retriever.release() }
}

fun grayFrame(bitmap: Bitmap): GrayFrame {
    val w = 128; val h = max(8, (bitmap.height.toDouble() * w / bitmap.width).roundToInt())
    // Area averaging prevents thin Chinese strokes from aliasing while the page moves.
    // Bilinear point sampling at this reduction factor caused false scene cuts.
    val source = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val gray = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val y0 = y * bitmap.height / h; val y1 = max(y0 + 1, (y + 1) * bitmap.height / h).coerceAtMost(bitmap.height)
        val x0 = x * bitmap.width / w; val x1 = max(x0 + 1, (x + 1) * bitmap.width / w).coerceAtMost(bitmap.width)
        var sum = 0.0
        for (sy in y0 until y1) for (sx in x0 until x1) {
            val p = source[sy * bitmap.width + sx]; sum += Color.red(p) * .299 + Color.green(p) * .587 + Color.blue(p) * .114
        }
        gray[y * w + x] = (sum / ((y1 - y0) * (x1 - x0))).toFloat()
    }
    return GrayFrame(w, h, gray)
}
