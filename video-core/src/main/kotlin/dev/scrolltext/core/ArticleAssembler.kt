package dev.scrolltext.core

import kotlin.math.*

/** Spatial consensus, not a global set of strings. Repeated sentences at distinct positions survive. */
class ArticleAssembler(initial: List<ArticleLine> = emptyList(), duplicates: Int = 0) {
    val lines = initial.toMutableList()
    var duplicateObservations = duplicates; private set
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1
    private val byId = mutableMapOf<Int, ArticleLine>()
    private val normalized = mutableMapOf<Int, String>()
    private val exactIndex = mutableMapOf<String, MutableSet<Int>>()
    private val gramIndex = mutableMapOf<String, MutableSet<Int>>()
    private val spatialIndex = mutableMapOf<Int, MutableSet<Int>>()
    private var tallest = 0.0
    var comparisonCount = 0L; private set

    init { initial.forEach(::index) }

    private fun bucket(y: Double) = floor(y / 128).toInt()
    // Anchor matching may ignore punctuation; consensus must distinguish 1.0 from 10
    // and < from >. Keep the winning text verbatim and flag supported alternatives.
    private fun readingKey(text: String) = text.filterNot { it.isWhitespace() }
    private fun index(line: ArticleLine) {
        byId[line.id] = line
        val n = normalizedText(line.text); normalized[line.id] = n
        spatialIndex.getOrPut(bucket(line.box.centerY)) { mutableSetOf() }.add(line.id)
        tallest = max(tallest, line.box.height)
        if (line.kind == BlockKind.TEXT && line.formulaParts.isEmpty()) {
            exactIndex.getOrPut(n) { mutableSetOf() }.add(line.id)
            n.windowed(2).toSet().forEach { gramIndex.getOrPut(it) { mutableSetOf() }.add(line.id) }
        }
    }
    private fun unindex(line: ArticleLine) {
        val n = normalized[line.id] ?: return
        fun remove(map: MutableMap<String, MutableSet<Int>>, key: String) {
            map[key]?.let { it.remove(line.id); if (it.isEmpty()) map.remove(key) }
        }
        remove(exactIndex, n); n.windowed(2).toSet().forEach { remove(gramIndex, it) }
        val b = bucket(line.box.centerY)
        spatialIndex[b]?.let { it.remove(line.id); if (it.isEmpty()) spatialIndex.remove(b) }
    }
    private fun nearby(y: Double, radius: Double): Sequence<ArticleLine> =
        (bucket(y - radius)..bucket(y + radius)).asSequence().flatMap { spatialIndex[it].orEmpty().asSequence() }.mapNotNull { byId[it] }

    /** Geometry bounds ordinary matching cost independently of the length of the document. */
    fun hasTextOverlap(observations: List<Observation>, offset: Double): Boolean = observations.any { o ->
        !o.clipped && o.kind == BlockKind.TEXT && normalizedText(o.text).length >= 6 &&
            nearby(o.box.centerY + offset, max(14.0, o.box.height * .6)).any { l ->
                l.kind == BlockKind.TEXT && l.box.horizontalOverlap(o.box) > .65 &&
                    abs(l.box.centerY - o.box.centerY - offset) < max(14.0, min(l.box.height, o.box.height) * .6) &&
                    similarity(normalized.getValue(l.id), normalizedText(o.text)) >= .8
            }
    }

    fun locate(observations: List<Observation>, predicted: Double? = null, viewport: Double = 1000.0): Double? {
        val votes = mutableListOf<Pair<Double, String>>()
        for (o in observations.filter { !it.clipped && it.kind == BlockKind.TEXT && it.formulaParts.isEmpty() }) {
            val n = normalizedText(o.text)
            if (n.length < 6) continue
            val exact = exactIndex[n].orEmpty().mapNotNull { byId[it] }.filter { it.box.horizontalOverlap(o.box) > .65 }
            // Numbered/repeated headings can differ by a single symbol. Prefer exact identity;
            // approximate anchors need a clear margin over the runner-up across the whole article.
            val matches = if (exact.isNotEmpty()) exact.filter { predicted == null || abs(it.box.centerY - o.box.centerY - predicted) < viewport * .4 }
                else {
                    // Any >= .84 edit-similarity rival of a >= 6 character anchor shares a
                    // bigram. Keep rivals across the whole article, not just the viewport.
                    val ids = n.windowed(2).flatMap { gramIndex[it].orEmpty() }.toSet()
                    val ranked = ids.asSequence().mapNotNull { byId[it] }.filter { it.box.horizontalOverlap(o.box) > .65 }
                        .filter { abs(normalized.getValue(it.id).length - n.length) <= max(normalized.getValue(it.id).length, n.length) * .16 }
                        .map { comparisonCount++; it to similarity(normalized.getValue(it.id), n) }.sortedByDescending { it.second }.take(2).toList()
                    if (ranked.isNotEmpty() && ranked[0].second >= .9 && (ranked.size == 1 || ranked[0].second - ranked[1].second >= .06))
                        listOf(ranked[0].first).filter { predicted == null || abs(it.box.centerY - o.box.centerY - predicted) < viewport * .4 }
                    else emptyList()
                }
            // Ambiguous repeated headings are never used as standalone anchors.
            if (matches.size == 1) votes += (matches[0].box.centerY - o.box.centerY) to n
        }
        if (votes.isEmpty()) return null
        val best = votes.maxBy { (v, _) -> votes.count { abs(it.first - v) < 12 } }
        val cluster = votes.filter { abs(it.first - best.first) < 12 }.distinctBy { it.second }
        if (cluster.size < 2) return null
        return cluster.map { it.first }.sorted()[cluster.size / 2]
    }

    fun add(observations: List<Observation>, offset: Double): Set<String> {
        val used = mutableSetOf<Int>()
        val changed = mutableSetOf<Int>()
        for (o in observations.sortedBy { it.box.top }) {
            if (o.text.isBlank()) continue
            val box = o.box.shift(offset)
            val n = normalizedText(o.text)
            val matched = nearby(box.centerY, max(10.0, max(tallest, box.height) * .65)).filter { it.id !in used && (it.kind == o.kind ||
                ((it.clipped || o.clipped) && (it.kind == BlockKind.FORMULA || o.kind == BlockKind.FORMULA) &&
                    max(box.intersectionRatio(it.box), it.box.intersectionRatio(box)) > .65)) &&
                abs(it.box.centerY - box.centerY) < max(10.0, if (it.clipped || o.clipped) max(it.box.height, box.height) * .65 else min(it.box.height, box.height) * .48) && it.box.horizontalOverlap(box) > .45 }
                .map { comparisonCount++; it to similarity(normalized.getValue(it.id), n) }
                .filter { (l, sim) -> l.edited || sim > .56 || ((l.clipped || o.clipped) &&
                    (sim > .32 || box.intersectionRatio(l.box) > .65 || l.box.intersectionRatio(box) > .65)) }
                .maxByOrNull { it.second }?.first
            val quality = (o.confidence.coerceIn(.1, 1.0) * .65 + o.sharpness.coerceIn(0.0, 1.0) * .35) * if (o.clipped) .25 else 1.0
            if (matched == null) {
                val line = ArticleLine(nextId++, o.text, box, o.confidence, o.source, o.timeMs, o.kind,
                    needsReview = o.needsReview || o.clipped || o.confidence < .72, clipped = o.clipped, sourceQuality = quality,
                    readings = mutableListOf(Reading(o.text, quality, 1)), formulaParts = o.formulaParts)
                lines += line; index(line); changed += line.id
            } else {
                used += matched.id; duplicateObservations++; matched.observations++
                if (matched.edited) continue
                unindex(matched)
                val identity = readingKey(o.text)
                val reading = matched.readings.find { readingKey(it.text) == identity }
                    ?: Reading(o.text).also { matched.readings += it }
                reading.count++; reading.weight += quality
                val winner = matched.readings.maxBy { it.weight }
                // Even a single digit or sign can change the meaning of a long sentence.
                val disagreement = matched.readings.any { readingKey(it.text) != readingKey(winner.text) && it.weight > winner.weight * .3 }
                if (quality > matched.sourceQuality || readingKey(matched.text) != readingKey(winner.text)) {
                    if (identity == readingKey(winner.text)) {
                        matched.text = o.text; matched.source = o.source; matched.timeMs = o.timeMs; matched.kind = o.kind
                        matched.sourceQuality = quality; matched.confidence = o.confidence; matched.box = box; matched.clipped = o.clipped
                        matched.formulaParts = o.formulaParts
                    }
                }
                matched.needsReview = o.needsReview || matched.clipped || matched.confidence < .72 || disagreement
                index(matched); changed += matched.id
            }
        }
        return changed.mapNotNull { byId[it]?.source }.toSet()
    }

    fun gap(at: Double, timeMs: Long) {
        val line = ArticleLine(nextId++, "此处画面无法可靠衔接，请回看录屏补充。", Box(0.0, at, 1.0, at + 1), 0.0, "", timeMs, BlockKind.GAP, needsReview = true)
        lines += line; index(line)
    }

    fun sorted() = lines.sortedWith(compareBy<ArticleLine> { it.box.top }.thenBy { it.box.left })
}
