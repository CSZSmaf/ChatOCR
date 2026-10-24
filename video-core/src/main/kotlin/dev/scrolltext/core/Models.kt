package dev.scrolltext.core

import kotlin.math.*

data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = max(0.0, right - left)
    val height get() = max(0.0, bottom - top)
    val centerY get() = (top + bottom) / 2
    fun shift(dy: Double) = copy(top = top + dy, bottom = bottom + dy)
    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun horizontalOverlap(other: Box): Double = max(0.0, min(right, other.right) - max(left, other.left)) / max(1.0, min(width, other.width))
    fun intersectionRatio(other: Box): Double {
        val area = max(0.0, min(right, other.right) - max(left, other.left)) * max(0.0, min(bottom, other.bottom) - max(top, other.top))
        return area / max(1.0, width * height)
    }
}

enum class BlockKind { TEXT, FORMULA, GAP }

/** Box is in the source frame, not document coordinates. Source is a lossless formula crop. */
data class FormulaPart(val marker: String, val box: Box, val source: String = "")

data class Observation(
    val text: String,
    val box: Box,
    val confidence: Double,
    val timeMs: Long,
    val source: String = "",
    val kind: BlockKind = BlockKind.TEXT,
    val clipped: Boolean = false,
    val sharpness: Double = 1.0,
    val needsReview: Boolean = false,
    val formulaParts: List<FormulaPart> = emptyList(),
)

data class Reading(val text: String, var weight: Double = 0.0, var count: Int = 0)

data class ArticleLine(
    val id: Int,
    var text: String,
    var box: Box,
    var confidence: Double,
    var source: String,
    var timeMs: Long,
    var kind: BlockKind = BlockKind.TEXT,
    var observations: Int = 1,
    var needsReview: Boolean = false,
    var clipped: Boolean = false,
    var edited: Boolean = false,
    var sourceQuality: Double = 0.0,
    val readings: MutableList<Reading> = mutableListOf(),
    var formulaParts: List<FormulaPart> = emptyList(),
)

data class Crop(val left: Double = .04, val top: Double = .13, val right: Double = .96, val bottom: Double = .9) {
    fun valid() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right - left >= .1 && bottom - top >= .1
}

fun normalizedText(text: String) = text.lowercase().filter { it.isLetterOrDigit() || it in "=+-∫∑√" }

fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (abs(a.length - b.length) > max(a.length, b.length) * .65) return 0.0
    var prev = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val row = IntArray(b.length + 1); row[0] = i + 1
        for (j in b.indices) row[j + 1] = min(min(row[j] + 1, prev[j + 1] + 1), prev[j] + if (a[i] == b[j]) 0 else 1)
        prev = row
    }
    return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
}
