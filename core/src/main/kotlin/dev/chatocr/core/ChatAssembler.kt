package dev.chatocr.core

import kotlin.math.abs

data class MergeResult(val added: Int, val gap: Boolean, val aligned: Boolean)

/** Spatial deduplication, never global string uniqueness. Original OCR is retained on edits. */
class ChatAssembler(private val session: Session) {
    private val index = SpatialMessageIndex(session.messages)
    fun merge(candidates: List<Candidate>, frameId: String, height: Float, contentShift: Float? = null): MergeResult {
        if (candidates.isEmpty()) return MergeResult(0, false, false)
        val previous = session.lastAnchors
        val offsets = mutableListOf<Pair<Float, Int>>()
        val localLinks = mutableListOf<Pair<Candidate, Float>>()
        for (c in candidates.filter { it.kind != Kind.MEDIA && it.flags.none { f -> f.startsWith("画面边缘") } }) {
            val matches = previous.filter { !it.partial && it.side == c.side && normalized(it.text) == normalized(c.text) }
            // Repeated strings cannot independently anchor a viewport.
            if (matches.size == 1 && candidates.count { it.side == c.side && normalized(it.text) == normalized(c.text) } == 1) {
                offsets += (matches[0].centerY - c.box.centerY) to normalized(c.text).length
                if (normalized(c.text).length >= 8) localLinks += c to (matches[0].centerY - c.box.centerY)
            }
        }
        val best = offsets.maxByOrNull { pair -> offsets.count { abs(it.first - pair.first) < 14 } }
        val inliers = if (best == null) emptyList() else offsets.filter { abs(it.first - best.first) < 14 }
        val textOffset = if (inliers.size >= 2) inliers.map { it.first }.sorted().let { it[it.size / 2] } else null
        val projected = contentShift?.let { session.offset - it }
        val lineOffset = if (textOffset == null && projected == null) stableLineOffset(candidates, previous) else null
        val reflowOffset = if (textOffset == null && projected == null && localLinks.size >= 2) {
            val links = localLinks.sortedBy { it.first.box.centerY }
            val values = links.map { it.second }.sorted()
            if (values.last() - values.first() < height * .35f && links.zipWithNext().all { (a, b) ->
                a.first.box.centerY + a.second < b.first.box.centerY + b.second }) values[values.size / 2] else null
        } else null
        val anchorOffset = when {
            textOffset != null -> textOffset
            projected != null -> projected
            lineOffset != null -> lineOffset
            reflowOffset != null -> reflowOffset
            else -> null
        }
        var gap = false
        if (previous.isNotEmpty() && anchorOffset == null) {
            gap = true
            session.segment += 1
            val oldest = session.messages.minOfOrNull { it.y } ?: 0f
            val newest = session.messages.maxOfOrNull { it.y } ?: 0f
            val gapY: Float
            if (session.config.direction == Direction.OLDER) {
                gapY = oldest - 90
                session.offset = gapY - height - 90
            } else {
                gapY = newest + 90
                session.offset = gapY + 90
            }
            session.messages += Message(text = ChatPresentation.gapText, side = Side.SYSTEM,
                kind = Kind.GAP, y = gapY, segment = session.segment, flags = mutableListOf("连续性待核对"))
        } else if (anchorOffset != null) session.offset = anchorOffset
        var added = 0
        val used = mutableSetOf<String>()
        val nextAnchors = mutableListOf<Anchor>()
        for (c in candidates) {
            // A downloaded sticker can grow in place. Nearby exact, unique long messages
            // retain their old positions even when the upper and lower page move differently.
            val local = if (anchorOffset != null) localLinks.minByOrNull { abs(it.first.box.centerY - c.box.centerY) } else null
            val offset = if (local != null && abs(local.first.box.centerY - c.box.centerY) < height * .4f &&
                abs(local.second - session.offset) in 30f..(height * .35f)) local.second else session.offset
            val y = c.box.centerY + offset
            val top = c.box.top + offset; val bottom = c.box.bottom + offset
            val partial = c.flags.any { it.startsWith("画面边缘") }
            nextAnchors += Anchor(c.text, c.side, y, partial || c.flags.any { it.startsWith("未定位") })
            fun overlap(m: Message): Boolean {
                val oldTop = m.extentTop ?: (m.y - (m.evidence.firstOrNull()?.box?.height ?: 36f) / 2)
                val oldBottom = m.extentBottom ?: (m.y + (m.evidence.firstOrNull()?.box?.height ?: 36f) / 2)
                val intersection = minOf(oldBottom, bottom) - maxOf(oldTop, top)
                if ((partial || m.flags.any { it.startsWith("画面边缘") }) && (m.kind == Kind.MEDIA || c.kind == Kind.MEDIA) &&
                    minOf(oldBottom - oldTop, bottom - top) < 45) return intersection > -12
                return intersection > 0 && intersection > minOf(oldBottom - oldTop, bottom - top) * .72f - 10f
            }
            val sides = if (c.side == Side.UNKNOWN && anchorOffset != null) listOf(Side.UNKNOWN, Side.LEFT, Side.RIGHT) else listOf(c.side, Side.UNKNOWN).distinct()
            val pool = sides.flatMap { index.overlapping(session.segment, it, top - 26, bottom + 26) }.filter { it.id !in used &&
                (it.side == c.side || anchorOffset != null && (c.side == Side.UNKNOWN || it.side == Side.UNKNOWN)) &&
                (it.kind == c.kind || (anchorOffset != null && setOf(it.kind, c.kind) == setOf(Kind.TEXT, Kind.MEDIA))) &&
                (abs(it.y - y) < 26 || ((partial || it.flags.any { flag -> flag.startsWith("画面边缘") } || it.kind == Kind.MEDIA && c.kind == Kind.MEDIA) && overlap(it))) }
            fun sameBubble(m: Message): Boolean {
                if (anchorOffset == null) return false
                val bounds = m.evidence.maxByOrNull { it.box.width * it.box.height }?.box ?: return false
                val oldTop = m.extentTop ?: m.y - bounds.height / 2
                val oldBottom = m.extentBottom ?: m.y + bounds.height / 2
                val intersection = minOf(oldBottom, bottom) - maxOf(oldTop, top)
                val union = maxOf(oldBottom, bottom) - minOf(oldTop, top)
                if (m.kind == Kind.MEDIA && c.kind == Kind.MEDIA) return intersection > minOf(oldBottom - oldTop, bottom - top) * .6f &&
                    abs(bounds.left - c.box.left) < 80 && abs(bounds.right - c.box.right) < 80
                val oldUnlocated = m.flags.any { it.startsWith("未定位") }
                val newUnlocated = c.flags.any { it.startsWith("未定位") }
                if (oldUnlocated || newUnlocated) {
                    if (oldUnlocated == newUnlocated || '\n' in m.text || '\n' in c.text || m.kind != Kind.TEXT || c.kind != Kind.TEXT) return false
                    if (minOf(m.text.length, c.text.length) < maxOf(m.text.length, c.text.length) * .6f) return false
                    return if (newUnlocated) c.box.left >= bounds.left - 12 && c.box.right <= bounds.right + 12 && top >= oldTop - 12 && bottom <= oldBottom + 12
                    else bounds.left >= c.box.left - 12 && bounds.right <= c.box.right + 12 && oldTop >= top - 12 && oldBottom <= bottom + 12
                }
                val tolerance = if (m.kind != c.kind) 45 else 25
                return intersection / union > .60f && abs(bounds.left - c.box.left) < tolerance && abs(bounds.right - c.box.right) < tolerance
            }
            fun matchRank(m: Message): Int = when {
                normalized(m.text) == normalized(c.text) -> 0
                similar(m.text, c.text) -> 1
                sameBubble(m) -> 2
                else -> 3
            }
            // Prefer an existing textual match over a closer contradictory OCR observation.
            val existing = pool.minWithOrNull(compareBy<Message> { matchRank(it) }.thenBy { abs(it.y - y) })
            val oldPartial = existing?.flags?.any { it.startsWith("画面边缘") } == true
            fun containedFragment(m: Message): Boolean {
                if (anchorOffset == null) return false
                val bounds = m.evidence.maxByOrNull { it.box.width * it.box.height }?.box ?: return false
                val oldUnlocated = m.flags.any { it.startsWith("未定位") }
                val newUnlocated = c.flags.any { it.startsWith("未定位") }
                return when {
                    oldUnlocated && oldPartial && !newUnlocated -> bounds.left >= c.box.left - 16 && bounds.right <= c.box.right + 16 && bounds.height < c.box.height * .65f
                    newUnlocated && partial && !oldUnlocated -> c.box.left >= bounds.left - 16 && c.box.right <= bounds.right + 16 && c.box.height < bounds.height * .65f
                    else -> false
                }
            }
            val edgeMatch = existing != null && (partial || oldPartial) && overlap(existing) &&
                ((c.flags.none { it.startsWith("未定位") } && existing.flags.none { it.startsWith("未定位") }) || containedFragment(existing))
            val compatible = existing != null && (matchRank(existing) < 3 || edgeMatch) &&
                (existing.side == c.side || containedFragment(existing) || anchorOffset != null && overlap(existing) &&
                    (c.side == Side.UNKNOWN || existing.side == Side.UNKNOWN) && (sameBubble(existing) || normalized(existing.text) == normalized(c.text)))
            if (compatible) {
                existing!!
                val recoveredText = existing.kind == Kind.MEDIA && c.kind == Kind.TEXT && !partial
                val locatedText = existing.flags.any { it.startsWith("未定位") } && c.flags.none { it.startsWith("未定位") }
                if (lineOffset != null && "多行文字对齐，请核对" !in existing.flags) existing.flags += "多行文字对齐，请核对"
                if (existing.side == Side.UNKNOWN && c.side != Side.UNKNOWN) {
                    existing.side = c.side; existing.flags.removeAll { it == "发送方待确认" }; index.update(existing)
                }
                c.recognitionAlternatives.forEach { if (it !in existing.variants && existing.variants.size < 8) existing.variants += it }
                c.flags.filter { it.startsWith("两次识别") }.forEach { if (it !in existing.flags) existing.flags += it }
                used += existing.id
                val originalKey = normalized(existing.text); val currentKey = normalized(c.text)
                existing.observations.putIfAbsent(originalKey, 1)
                // OCR noise may produce indefinitely many unique strings during a long session.
                // Retain a bounded vote set and never let clipped observations defeat a full read.
                if (!partial || oldPartial) {
                    if (currentKey !in existing.observations && existing.observations.size >= 8) {
                        existing.observations.filterKeys { it != originalKey }.minByOrNull { it.value }?.key?.let { existing.observations.remove(it) }
                    }
                    existing.observations[currentKey] = ((existing.observations[currentKey] ?: 0) + 1).coerceAtMost(1_000_000)
                }
                if (existing.text != c.text && normalized(existing.text) != normalized(c.text)) {
                    if (existing.text !in existing.variants && existing.variants.size < 8) existing.variants += existing.text
                    if (c.text !in existing.variants && existing.variants.size < 8) existing.variants += c.text
                    if (!partial && !oldPartial && existing.kind == Kind.TEXT && c.kind == Kind.TEXT && "多帧文字不一致" !in existing.flags) existing.flags += "多帧文字不一致"
                    val oldQuality = existing.recognitionConfidence ?: 0f
                    val moreVotes = (existing.observations[currentKey] ?: 0) > (existing.observations[originalKey] ?: 0)
                    if (!partial && c.kind != Kind.MEDIA && (oldPartial || recoveredText || c.confidence > oldQuality + .06f || moreVotes && c.confidence >= oldQuality - .08f)) {
                        existing.text = c.text; existing.recognitionConfidence = c.confidence
                    }
                }
                if (!partial && normalized(existing.text) == currentKey) existing.recognitionConfidence = maxOf(existing.recognitionConfidence ?: 0f, c.confidence)
                if (existing.evidence.none { it.frameId == frameId }) {
                    if (!partial && oldPartial && existing.evidence.size >= 4) existing.evidence.removeAt(existing.evidence.lastIndex)
                    if (existing.evidence.size < 4) existing.evidence += Evidence(frameId, c.box)
                }
                if (existing.sender == null && c.sender != null) existing.sender = c.sender
                if (!partial) {
                    existing.flags.removeAll { it.startsWith("画面边缘") }
                    if (oldPartial || recoveredText || locatedText) {
                        existing.y = y; existing.extentTop = top; existing.extentBottom = bottom; existing.kind = c.kind
                        if (c.kind == Kind.TEXT) {
                            existing.flags.removeAll { it.startsWith("非文字") }
                            existing.flags.removeAll { it.startsWith("图片或表情") }
                            existing.media = null
                        }
                        if (c.flags.none { it.startsWith("未定位") }) existing.flags.removeAll { it.startsWith("未定位") }
                        index.update(existing)
                    }
                }
            } else {
                val flags = c.flags.toMutableList()
                if (lineOffset != null) flags += "多行文字对齐，请核对"
                if (existing != null) flags += "同位置识别不同，可能重复"
                val m = Message(text = c.text, side = c.side, kind = c.kind, y = y, segment = session.segment,
                    sender = c.sender, flags = flags, evidence = mutableListOf(Evidence(frameId, c.box)),
                    extentTop = top, extentBottom = bottom, observations = mutableMapOf(normalized(c.text) to 1),
                    variants = c.recognitionAlternatives.take(8).toMutableList(), recognitionConfidence = c.confidence)
                session.messages += m
                index.update(m)
                used += m.id
                added++
            }
        }
        if (anchorOffset != null) {
            // A clipped/missed outline can expose individual lines of an already observed
            // multiline bubble. Attach those contained readings to the complete message.
            val fragments = session.messages.filter { it.kind == Kind.TEXT && !it.reviewed && it.editedText == null &&
                it.flags.any { f -> f.startsWith("未定位") } }
            for (fragment in fragments) {
                val bounds = fragment.evidence.firstOrNull()?.box ?: continue
                val top = fragment.extentTop ?: continue; val bottom = fragment.extentBottom ?: continue
                val possible = listOf(Side.LEFT, Side.RIGHT, Side.SYSTEM).flatMap { index.overlapping(fragment.segment, it, top, bottom) }
                val target = possible.singleOrNull { m ->
                    if (m.id == fragment.id || m.kind != Kind.TEXT || m.flags.any { it.startsWith("未定位") }) false
                    else {
                        val b = m.evidence.maxByOrNull { it.box.width * it.box.height }?.box
                        b != null && bounds.left >= b.left - 15 && bounds.right <= b.right + 15 &&
                            top >= (m.extentTop ?: Float.MAX_VALUE) - 15 && bottom <= (m.extentBottom ?: -Float.MAX_VALUE) + 15 &&
                            normalized(fragment.text).isNotEmpty() && normalized(m.text).contains(normalized(fragment.text))
                    }
                } ?: continue
                for (e in fragment.evidence) if (target.evidence.size < 4 && e !in target.evidence) target.evidence += e
                index.remove(fragment); session.messages.remove(fragment)
            }
        }
        if (session.config.group && anchorOffset != null) {
            // A nickname can enter the crop one frame before its bubble. Once its full
            // layout is observed, move that clipped observation to sender provenance.
            for (target in session.messages.filter { it.sender != null && it.flags.none { f -> f.startsWith("画面边缘") } &&
                it.id in used }) {
                val top = target.extentTop ?: continue
                val left = target.evidence.firstOrNull()?.box?.left ?: continue
                val names = index.overlapping(target.segment, target.side, top - 55, top + 3).filter { m ->
                    m.id != target.id && !m.reviewed && m.editedText == null && m.kind == Kind.TEXT &&
                        normalized(m.text) == normalized(target.sender.orEmpty()) &&
                        m.flags.any { it.startsWith("未定位") } &&
                        m.evidence.firstOrNull()?.box?.let { it.height < 42 && abs(it.left - left) < 45 } == true &&
                        top - (m.extentBottom ?: m.y) in -3f..45f
                }
                if (names.isNotEmpty()) {
                    target.senderEvidence = (target.senderEvidence.orEmpty() + names.flatMap { it.evidence }).distinct().take(4)
                    names.forEach { index.remove(it); session.messages.remove(it) }
                }
            }
        }
        session.lastAnchors = nextAnchors
        session.updatedAt = System.currentTimeMillis()
        return MergeResult(added, gap, anchorOffset != null)
    }
    /** A fallback for independently noisy multiline bubbles, never global text deduplication. */
    private fun stableLineOffset(candidates: List<Candidate>, previous: List<Anchor>): Float? {
        fun keys(text: String) = text.lineSequence().map(::normalized).filter { it.length >= 8 }.distinct().toList()
        val oldKeys = previous.map { if (it.partial) emptyList() else keys(it.text) }
        val newKeys = candidates.map { keys(it.text) }
        val links = mutableListOf<Pair<Int, Float>>()
        candidates.forEachIndexed { i, c ->
            if (c.kind != Kind.TEXT || c.flags.any { it.startsWith("画面边缘") }) return@forEachIndexed
            val hits = newKeys[i].filter { key -> candidates.indices.count { candidates[it].side == c.side && key in newKeys[it] } == 1 }
                .flatMap { key -> previous.indices.filter { previous[it].side == c.side && key in oldKeys[it] }.takeIf { it.size == 1 }.orEmpty() }.distinct()
            if (hits.size != 1) return@forEachIndexed
            val old = previous[hits.single()]
            // Repeated templates with changed amounts, dates or IDs must remain separate.
            if (digits.findAll(normalized(old.text)).map { it.value }.toList() != digits.findAll(normalized(c.text)).map { it.value }.toList()) return@forEachIndexed
            if (similar(old.text, c.text)) links += hits.single() to (old.centerY - c.box.centerY)
        }
        val offsets = links.filter { link -> links.count { it.first == link.first } == 1 }.map { it.second }
        val best = offsets.maxByOrNull { value -> offsets.count { abs(it - value) < 14 } } ?: return null
        val inliers = offsets.filter { abs(it - best) < 14 }
        if (inliers.size < 3 || offsets.any { value -> abs(value - best) >= 28 && offsets.count { abs(it - value) < 14 } >= inliers.size }) return null
        return inliers.sorted()[inliers.size / 2]
    }
    companion object {
        private val whitespace = Regex("\\s+")
        private val digits = Regex("\\d+")
        fun normalized(s: String) = s.replace(whitespace, "").lowercase()
            .replace('“', '"').replace('”', '"').replace('，', ',').replace('：', ':').replace('？', '?').replace('！', '!')
        fun similar(a: String, b: String): Boolean {
            val x = normalized(a); val y = normalized(b)
            if (x == y) return true
            if (minOf(x.length, y.length) < 8) return false
            if (x.contains(y) || y.contains(x)) return minOf(x.length, y.length).toDouble() / maxOf(x.length, y.length) > .65
            if (abs(x.length - y.length) > 2) return false
            var prev = IntArray(y.length + 1) { it }
            for (i in x.indices) {
                val next = IntArray(y.length + 1); next[0] = i + 1
                for (j in y.indices) next[j + 1] = minOf(next[j] + 1, prev[j + 1] + 1, prev[j] + if (x[i] == y[j]) 0 else 1)
                prev = next
            }
            return prev.last().toDouble() / maxOf(x.length, y.length) <= .14
        }
    }
}
