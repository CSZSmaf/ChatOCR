package dev.chatocr.app

import android.graphics.Bitmap
import dev.chatocr.core.*
import java.util.UUID

/** Both screen capture and imported recordings use the same OCR, spatial merge and evidence. */
class ChatFrameProcessor(context: android.content.Context, private val session: Session, private val store: SessionStore,
    private val dataLock: Any = Any()) : AutoCloseable {
    private val ocr = PageOcr(context)
    private val assembler = ChatAssembler(session)
    data class Result(val count: Int, val merge: MergeResult)

    suspend fun process(bitmap: Bitmap, shift: Float?, videoTimeMs: Long? = null): Result {
        val candidates = ocr.read(bitmap, session.config.group)
        val frameId = UUID.randomUUID().toString()
        val height = bitmap.height * 1000f / bitmap.width
        store.saveFrame(session.id, frameId, bitmap)
        store.saveObservation(session.id, frameId, height, shift, candidates)
        return synchronized(dataLock) {
            val merge = assembler.merge(candidates, frameId, height, shift)
            session.frames += FrameRecord(frameId, "frames/$frameId.jpg", System.currentTimeMillis(),
                bitmap.width, bitmap.height, candidates.size, videoTimeMs)
            session.sampledFrames++
            store.saveMedia(session, frameId, bitmap)
            Result(candidates.size, merge)
        }
    }
    override fun close() = ocr.close()
}
