package dev.chatocr.core

import kotlin.math.floor

/** An overlap index. It deliberately includes extent, not just the centre of clipped bubbles. */
internal class SpatialMessageIndex(messages: List<Message>) {
    private data class Key(val segment: Int, val side: Side, val bucket: Int)
    private val buckets = HashMap<Key, MutableSet<String>>()
    private val records = HashMap<String, Message>()
    private val membership = HashMap<String, List<Key>>()
    init { messages.forEach { update(it) } }
    private fun bucket(y: Float) = floor(y / 160f).toInt()
    fun update(message: Message) {
        membership.remove(message.id)?.forEach { key ->
            buckets[key]?.let { it.remove(message.id); if (it.isEmpty()) buckets.remove(key) }
        }
        records[message.id] = message
        val halfHeight = (message.evidence.firstOrNull()?.box?.height ?: 36f) / 2
        val top = message.extentTop ?: (message.y - halfHeight)
        val bottom = message.extentBottom ?: (message.y + halfHeight)
        val keys = (bucket(top)..bucket(bottom)).map { Key(message.segment, message.side, it) }
        membership[message.id] = keys
        keys.forEach { buckets.getOrPut(it) { HashSet() }.add(message.id) }
    }
    fun overlapping(segment: Int, side: Side, top: Float, bottom: Float): List<Message> =
        (bucket(top)..bucket(bottom)).flatMap { buckets[Key(segment, side, it)].orEmpty() }.distinct().mapNotNull { records[it] }
    fun remove(message: Message) {
        membership.remove(message.id)?.forEach { key ->
            buckets[key]?.let { it.remove(message.id); if (it.isEmpty()) buckets.remove(key) }
        }
        records.remove(message.id)
    }
}
