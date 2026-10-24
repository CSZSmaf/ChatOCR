package dev.scrolltext.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Decode the stream once. Keep at most two output buffers, never the full recording in RAM. */
class SequentialDecoder(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputEnded = false
    private var outputEnded = false
    private data class Output(val index: Int, val timeUs: Long)
    private var pending: Output? = null
    private var lastRequest = -1L
    private var lastBitmap: Bitmap? = null
    private var rotation = 0
    private var colorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC
    private var fullRange = false
    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("视频中没有可解码的图像轨道。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_ROTATION, 0) // Explicitly rotate the RGB result exactly once.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0); it.start()
            }
        } catch (e: Exception) { close(); throw e }
    }

    suspend fun frame(timeMs: Long): Bitmap {
        val target = timeMs * 1000
        if (target == lastRequest && lastBitmap != null) return lastBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
        val decoder = codec ?: error("Decoder closed")
        if (lastRequest < 0 || target < lastRequest) {
            pending?.let { decoder.releaseOutputBuffer(it.index, false) }; pending = null
            if (lastRequest >= 0) decoder.flush()
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEnded = false; outputEnded = false
            lastBitmap?.recycle(); lastBitmap = null
        }
        lastRequest = target
        var before: Output? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = pending?.also { pending = null } ?: nextOutput()
                if (next == null) {
                    if (before != null) return copyOutput(before).also { decoder.releaseOutputBuffer(before.index, false); before = null }
                    return lastBitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: error("未能解码视频画面。")
                }
                if (next.timeUs >= target) {
                    if (before != null && abs(before.timeUs - target) <= abs(next.timeUs - target)) {
                        pending = next
                        return copyOutput(before).also { decoder.releaseOutputBuffer(before.index, false); before = null }
                    }
                    before?.let { decoder.releaseOutputBuffer(it.index, false) }; before = null
                    return try { copyOutput(next) } finally { decoder.releaseOutputBuffer(next.index, false) }
                }
                before?.let { decoder.releaseOutputBuffer(it.index, false) }
                before = next
            }
        } finally { before?.let { decoder.releaseOutputBuffer(it.index, false) } }
    }

    private suspend fun nextOutput(): Output? {
        val decoder = codec ?: error("Decoder closed")
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "视频解码器长时间没有响应。" }
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 1_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) colorStandard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                // HDR/P010 and unknown color standards use the platform retriever fallback.
                check(colorStandard != MediaFormat.COLOR_STANDARD_BT2020) { "HDR 视频使用兼容解码。" }
            } else if (index >= 0) {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) return Output(index, info.presentationTimeUs)
                decoder.releaseOutputBuffer(index, false)
            }
        }
        return null
    }

    private fun copyOutput(output: Output): Bitmap {
        val image = codec!!.getOutputImage(output.index) ?: error("设备解码器不支持 YUV 图像输出。")
        val bitmap = image.use { toBitmap(it) }
        val upright = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== bitmap) bitmap.recycle() }
        lastBitmap?.recycle(); lastBitmap = upright.copy(Bitmap.Config.ARGB_8888, false)
        return upright
    }

    private fun toBitmap(image: Image): Bitmap {
        check(image.format == android.graphics.ImageFormat.YUV_420_888) { "Unsupported decoder image format" }
        val crop = image.cropRect; val width = crop.width(); val height = crop.height()
        val planes = image.planes
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        val pixels = IntArray(width * height)
        val is709 = colorStandard == MediaFormat.COLOR_STANDARD_BT709
        val yScale = if (fullRange) 256 else 298
        val rV = if (is709) (if (fullRange) 403 else 459) else (if (fullRange) 359 else 409)
        val gU = if (is709) (if (fullRange) 48 else 55) else (if (fullRange) 88 else 100)
        val gV = if (is709) (if (fullRange) 120 else 136) else (if (fullRange) 183 else 208)
        val bU = if (is709) (if (fullRange) 475 else 541) else (if (fullRange) 454 else 516)
        for (y in 0 until height) {
            val sy = y + crop.top; val rowY = sy * yPlane.rowStride
            val rowU = sy / 2 * uPlane.rowStride; val rowV = sy / 2 * vPlane.rowStride
            for (x in 0 until width) {
                val sx = x + crop.left
                val yy = ((yBuffer.get(rowY + sx * yPlane.pixelStride).toInt() and 255) - if (fullRange) 0 else 16).coerceAtLeast(0) * yScale
                val u = (uBuffer.get(rowU + sx / 2 * uPlane.pixelStride).toInt() and 255) - 128
                val v = (vBuffer.get(rowV + sx / 2 * vPlane.pixelStride).toInt() and 255) - 128
                val r = ((yy + rV * v + 128) shr 8).coerceIn(0, 255)
                val g = ((yy - gU * u - gV * v + 128) shr 8).coerceIn(0, 255)
                val b = ((yy + bU * u + 128) shr 8).coerceIn(0, 255)
                pixels[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
    override fun close() {
        lastBitmap?.recycle(); lastBitmap = null; pending = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null
        extractor.release()
    }
}
