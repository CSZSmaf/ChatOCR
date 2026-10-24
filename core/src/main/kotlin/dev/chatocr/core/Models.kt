package dev.chatocr.core

import java.util.UUID

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY get() = (top + bottom) / 2
    val centerX get() = (left + right) / 2
    val height get() = bottom - top
    val width get() = right - left
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}
enum class Side { LEFT, RIGHT, SYSTEM, UNKNOWN }
enum class Kind { TEXT, TIME, MEDIA, GAP }
enum class Direction { OLDER, NEWER }
data class CaptureConfig(
    val title: String = "新的聊天纪念册",
    val platform: String = "微信",
    val leftName: String = "对方",
    val rightName: String = "我",
    val group: Boolean = false,
    val direction: Direction = Direction.OLDER,
    var cropTop: Float = .14f,
    var cropBottom: Float = .88f,
    val intervalMs: Long = 1100,
    var cropConfirmed: Boolean = false
)
data class OcrLine(val text: String, val box: Box, val confidence: Float = 0f)
data class Candidate(val text: String, val side: Side, val kind: Kind, val box: Box, val flags: List<String> = emptyList(), val sender: String? = null,
    val recognitionAlternatives: List<String> = emptyList())
data class Evidence(val frameId: String, val box: Box)
data class MediaCrop(val file: String, val frameId: String, val box: Box)
data class Message(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var side: Side,
    var kind: Kind,
    var y: Float,
    val segment: Int,
    var sender: String? = null,
    var visibleTimeContext: String? = null,
    var editedText: String? = null,
    var reviewed: Boolean = false,
    val variants: MutableList<String> = mutableListOf(),
    val observations: MutableMap<String, Int> = mutableMapOf(),
    var extentTop: Float? = null,
    var extentBottom: Float? = null,
    val flags: MutableList<String> = mutableListOf(),
    val evidence: MutableList<Evidence> = mutableListOf(),
    var media: MediaCrop? = null,
    var senderEvidence: List<Evidence>? = null
) { val displayText get() = editedText ?: text }
data class FrameRecord(val id: String, val file: String, val capturedAt: Long, val width: Int, val height: Int, val messageCount: Int,
    val videoTimeMs: Long? = null)
/** Video position is source evidence, never a message's sent time. Nullable on legacy albums. */
data class ChatVideoSource(
    val uri: String, val name: String, val durationMs: Long, val startMs: Long, val endMs: Long,
    val left: Float = 0f, val top: Float = .14f, val right: Float = 1f, val bottom: Float = .88f,
    var nextMs: Long = startMs, var lastObservedMs: Long? = null, var lastAnchorMs: Long? = null,
    var complete: Boolean = false, var scannedFrames: Int = 0
)
data class Anchor(val text: String, val side: Side, val centerY: Float)
data class Session(
    val schemaVersion: Int = 1,
    val coordinateSpace: String = "cropped_image_width_1000",
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    val config: CaptureConfig = CaptureConfig(),
    val messages: MutableList<Message> = mutableListOf(),
    val frames: MutableList<FrameRecord> = mutableListOf(),
    var offset: Float = 0f,
    var segment: Int = 0,
    var lastAnchors: List<Anchor> = emptyList(),
    var status: String = "已保存",
    var error: String? = null,
    var sampledFrames: Int = 0,
    val video: ChatVideoSource? = null
) {
    fun ordered() = messages.sortedWith(compareBy<Message> { it.y }.thenBy { it.id })
    fun count() = messages.count { it.kind == Kind.TEXT || it.kind == Kind.MEDIA }
    fun pending() = messages.count { !it.reviewed && it.flags.isNotEmpty() }
}
