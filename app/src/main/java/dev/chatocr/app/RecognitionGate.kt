package dev.chatocr.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecognitionKind { CHAT, CHAT_VIDEO, ARTICLE, FORMULA }

/** One heavy recognizer per process. A lease is released only by its owner. */
object RecognitionGate {
    private val mutable = MutableStateFlow<RecognitionKind?>(null)
    val state = mutable.asStateFlow()
    private var owner: Lease? = null

    @Synchronized fun acquire(kind: RecognitionKind): Lease? {
        if (owner != null) return null
        return Lease().also { owner = it; mutable.value = kind }
    }

    class Lease internal constructor() : AutoCloseable {
        override fun close() = release(this)
    }

    @Synchronized private fun release(lease: Lease) {
        if (owner === lease) { owner = null; mutable.value = null }
    }
}
