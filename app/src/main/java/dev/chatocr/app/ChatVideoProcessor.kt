package dev.chatocr.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.chatocr.core.*
import dev.scrolltext.app.processing.VideoFrames
import dev.scrolltext.core.Crop
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class ChatVideoState(val running: Boolean = false, val sessionId: String = "", val progress: Float = 0f,
    val positionMs: Long = 0, val count: Int = 0, val message: String = "", val pausing: Boolean = false)
object ChatVideoRuntime { val state = MutableStateFlow(ChatVideoState()) }

class ChatVideoProcessor(private val context: Context, private val store: SessionStore) {
    suspend fun run(session: Session, shouldPause: () -> Boolean = { false }, progress: (ChatVideoState) -> Unit = {}) {
        val source = requireNotNull(session.video)
        require(source.uri.isNotBlank()) { "原录屏不可用，请重新导入录屏" }
        require(source.startMs >= 0 && source.endMs <= source.durationMs && source.endMs > source.startMs)
        if (source.complete) return
        val crop = Crop(source.left.toDouble(), source.top.toDouble(), source.right.toDouble(), source.bottom.toDouble())
        require(crop.valid()) { "聊天选区无效" }
        VideoFrames(context, Uri.parse(source.uri), sequential = true).use { frames ->
            check(kotlin.math.abs(frames.durationMs - source.durationMs) < 100) { "原录屏已改变，请重新导入" }
            suspend fun read(time: Long): Bitmap {
                val full = frames.sample(time)
                return try { frames.crop(full, crop).also { if (it !== full) full.recycle() } }
                catch (e: Throwable) { full.recycle(); throw e }
            }
            suspend fun grayAt(time: Long?): GrayFrame? = time?.let {
                val bitmap = read(it)
                try { PageOcr.gray(bitmap) } finally { bitmap.recycle() }
            }
            var previous = grayAt(source.lastAnchorMs)
            var observed = grayAt(source.lastObservedMs)
            ChatFrameProcessor(context, session, store).use { processor ->
                while (source.nextMs < source.endMs) {
                    currentCoroutineContext().ensureActive()
                    if (shouldPause()) break
                    check(context.filesDir.usableSpace >= 150L * 1024 * 1024) { "可用空间不足 150 MB，请释放空间后继续" }
                    var time = source.nextMs
                    var accepted = false
                    // Subdivide a moving interval before accepting a possible discontinuity.
                    while (!accepted) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = read(time)
                        try {
                            val gray = PageOcr.gray(bitmap)
                            val unchanged = observed?.let { PageOcr.difference(it, gray) < .7 } == true
                            val shiftPixels = if (unchanged) 0 else previous?.let {
                                MotionMatcher.shift(it, gray, .18f) ?: MotionMatcher.shift(it, gray)
                            }
                            val lastTime = source.lastObservedMs
                            val minStep = if (shiftPixels == null) 60 else 125
                            if (!unchanged && previous != null && (shiftPixels == null || kotlin.math.abs(shiftPixels) > gray.height * .18f) && lastTime != null && time - lastTime > minStep) {
                                time = lastTime + (time - lastTime) / 2
                                continue
                            }
                            // Finish an accepted native OCR call before releasing its bitmaps or saving a pause.
                            withContext(NonCancellable) {
                                if (!unchanged) {
                                    val result = processor.process(bitmap, shiftPixels?.let { it * 1000f / gray.width }, time)
                                    if (result.count > 0) { previous = gray; source.lastAnchorMs = time }
                                }
                                observed = gray; source.lastObservedMs = time; source.scannedFrames++
                                source.nextMs = if (time == source.endMs - 1) source.endMs else minOf(time + 400, source.endMs - 1)
                                session.status = "录屏识别中"; session.error = null; session.updatedAt = System.currentTimeMillis()
                                // One atomic checkpoint includes messages, anchors and next video position.
                                store.save(session)
                            }
                            accepted = true
                            progress(ChatVideoState(true, session.id,
                                (source.nextMs - source.startMs).toFloat() / (source.endMs - source.startMs), time, session.count()))
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
        source.complete = source.nextMs >= source.endMs
        session.status = if (source.complete) "已保存" else "录屏已暂停"
        store.save(session)
    }
}
