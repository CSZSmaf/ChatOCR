package dev.scrolltext.app.processing

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import dev.scrolltext.app.data.*
import dev.scrolltext.app.ocr.PageOcr
import dev.scrolltext.app.ocr.FormulaEngine
import dev.scrolltext.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.math.*

data class ProcessingState(val running: Boolean = false, val articleId: String = "", val progress: Float = 0f, val message: String = "", val frames: Int = 0, val lines: Int = 0, val elapsedMs: Long = 0)

class VideoProcessor(private val context: Context, private val store: ArticleStore) {
    suspend fun run(article: Article, update: (ProcessingState) -> Unit) {
        val resume = article.checkpoint
        val metrics = RunMetrics(if (resume != null) article.metrics else emptyMap())
        if (resume != null) { metrics.add("resumedRuns"); metrics.values["resumedAtVideoMs"] = resume.timeMs }
        val config = article.config
        val assembler = ArticleAssembler(if (resume != null) article.lines else emptyList(), if (resume != null) article.duplicates else 0)
        if (resume?.stage != "formulas") VideoFrames(context, Uri.parse(config.uri), sequential = true).use { video ->
            require(config.startMs >= 0 && config.endMs > config.startMs && config.endMs <= video.durationMs + 1)
            PageOcr(context, config.formulas, metrics, deferFormulas = true).use { ocr ->
                var previous: GrayFrame? = null
                var previousTime = resume?.timeMs ?: config.startMs
                var time = config.startMs
                val end = min(config.endMs, video.durationMs) - 1
                var step = resume?.stepMs ?: 250L
                var offset = resume?.offset ?: 0.0
                var lastOcrOffset = resume?.lastOcrOffset ?: Double.NEGATIVE_INFINITY
                var lastOcrTime = resume?.lastOcrTimeMs ?: -10000L
                var lastQuality = resume?.lastQuality ?: 0.0
                var gaps = assembler.lines.count { it.kind == BlockKind.GAP }
                var lastSavedFrames = article.frames
                var lastSavedTime = previousTime
                if (resume != null) {
                    // Reconstruct motion from one saved position. Do not replay OCR votes.
                    val original = video.sample(previousTime)
                    val cropped = try { video.crop(original, config.crop) } catch (e: Exception) { original.recycle(); throw e }
                    if (original !== cropped) original.recycle()
                    try { previous = grayFrame(cropped) } finally { cropped.recycle() }
                    time = min(end, previousTime + step)
                }
                fun report(message: String) = update(ProcessingState(true, article.id,
                    ((time - config.startMs).toDouble() / max(1, end - config.startMs)).toFloat().coerceIn(0f, .995f) * (if (config.formulas) .85f else 1f), message, article.frames, assembler.lines.size, metrics.elapsedMs))
                report("正在准备识别…")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val original = metrics.measureSuspending("decode") { video.sample(time) }
                    metrics.values["compatibilityDecoder"] = if (video.usedCompatibilityDecoder) 1 else 0
                    metrics.add("decodedFrames")
                    val image = try { metrics.measure("crop") { video.crop(original, config.crop) } } catch (e: Exception) { original.recycle(); throw e }
                    if (original !== image) original.recycle()
                    try {
                        val gray = metrics.measure("thumbnail") { grayFrame(image) }
                        val prior = previous
                        var reliable = prior == null
                        var motionScore = 0.0
                        if (prior != null && prior.width == gray.width && prior.height == gray.height) {
                            val motion = metrics.measure("motion") { MotionEstimator.estimate(prior, gray) }
                            reliable = motion.reliable; motionScore = motion.score
                            if (!reliable && time - previousTime > 85) {
                                metrics.add("retriedIntervals")
                                // Retry the interval midpoint before declaring an unrecoverable gap.
                                step = max(60L, (time - previousTime) / 2)
                                time = min(end, previousTime + step)
                                continue
                            }
                            if (reliable) {
                                val movement = motion.dy * image.height / gray.height
                                offset += movement
                                step = when {
                                    abs(movement) > image.height * .4 -> max(60, step / 2)
                                    abs(movement) < image.height * .04 -> min(600, step + 60)
                                    else -> step.coerceIn(80, 400)
                                }
                            }
                        }
                        val quality = (gray.sharpness / 800.0).coerceIn(.15, 1.0)
                        val shouldOcr = prior == null || !reliable || time == end || abs(offset - lastOcrOffset) > image.height * .22 ||
                            (time - lastOcrTime > 1300 && motionScore < .994) || (quality > lastQuality * 1.25 && time - lastOcrTime > 400)
                        if (shouldOcr) {
                            report("识别正文 · ${ArticleExport.timeLabel(time)}")
                            val observations = metrics.measureSuspending("pageOcr") { ocr.recognize(image, time, quality) { report(it) } }
                            val anchored = metrics.measure("assembly") { assembler.locate(observations, if (reliable) offset else null, image.height.toDouble()) }
                            if (anchored != null) { offset = anchored; reliable = true }
                            else if (prior != null && reliable && motionScore < .995 &&
                                observations.count { it.kind == BlockKind.TEXT && !it.clipped && normalizedText(it.text).length >= 6 } >= 3 &&
                                assembler.lines.count { it.kind == BlockKind.TEXT && normalizedText(it.text).length >= 6 } >= 3 &&
                                !assembler.hasTextOverlap(observations, offset) &&
                                abs(offset - lastOcrOffset) < image.height * .75) {
                                // A large predicted overlap should share readable text. A similar-looking
                                // layout alone cannot establish continuity across a page jump.
                                reliable = false; metrics.add("unconfirmedTextOverlap")
                            }
                            if (!reliable && prior != null) {
                                offset = max(assembler.lines.maxOfOrNull { it.box.bottom } ?: 0.0, offset + image.height) + 32
                                assembler.gap(offset - 16, time); gaps++
                            }
                            val sourced = observations.mapIndexed { i, o -> o.copy(source = "f${time}_$i.jpg",
                                formulaParts = o.formulaParts.mapIndexed { k, p -> p.copy(source = "f${time}_${i}_math$k.png") }) }
                            val used = metrics.measure("assembly") { assembler.add(sourced, offset) }
                            // Only persist thumbnails actually selected by consensus.
                            metrics.measure("saveSources") { sourced.filter { it.source in used }.forEach {
                                store.saveSource(article.id, it.source, image, it.box)
                                it.formulaParts.forEach { p -> store.saveSource(article.id, p.source, image, p.box) }
                            } }
                            article.frames++; article.lines = assembler.sorted(); article.duplicates = assembler.duplicateObservations
                            article.message = if (gaps > 0) "$gaps 处画面衔接需要核对" else ""
                            article.metrics = metrics.snapshot()
                            lastOcrTime = time; lastOcrOffset = offset; lastQuality = quality
                        }
                        article.checkpoint = ProcessingCheckpoint(timeMs = time, offset = offset, stepMs = step,
                            lastOcrTimeMs = lastOcrTime, lastOcrOffset = lastOcrOffset, lastQuality = lastQuality)
                        if ((article.frames == 1 && lastSavedFrames == 0) || article.frames - lastSavedFrames >= 8 || time - lastSavedTime >= 10000 || time == end) {
                            article.metrics = metrics.snapshot()
                            metrics.measure("checkpoint") { store.save(article); store.pruneSources(article) }
                            lastSavedFrames = article.frames; lastSavedTime = time
                        }
                        previous = gray; previousTime = time
                        if (time >= end) break
                        time = min(end, time + step)
                        report("分析滚动位置 · ${ArticleExport.timeLabel(time)}")
                    } finally { image.recycle() }
                }
            }
        }
        article.checkpoint = (article.checkpoint ?: resume)?.copy(stage = "formulas")
        article.metrics = metrics.snapshot(); store.save(article)
        // Spatial reconstruction first, then decode only the best crop for each distinct equation.
        // Detector and LaTeX models are never resident together. No approximate visual cache is used.
        val formulaCount = article.lines.sumOf { line -> line.formulaParts.count { line.text.contains(it.marker) } }
        if (formulaCount > 0) FormulaEngine(context).use { engine ->
            var index = 0
            for (line in article.lines) {
                val previousText = line.text
                for (part in line.formulaParts) {
                    if (!line.text.contains(part.marker)) continue
                    currentCoroutineContext().ensureActive()
                    update(ProcessingState(true, article.id, .85f + .14f * index / formulaCount,
                        "整理公式 ${index + 1} / $formulaCount", article.frames, article.lines.size, metrics.elapsedMs))
                    val bitmap = BitmapFactory.decodeFile(store.source(article.id, part.source).absolutePath)
                    val latex = try {
                        requireNotNull(bitmap) { "公式原图无法读取" }
                        metrics.measureSuspending("formulaRecognition") { metrics.add("formulaDecodes"); engine.recognize(bitmap) }.let {
                            if (!it.complete) metrics.add("incompleteFormulas")
                            it.latex.ifBlank { "\\text{公式待校对}" }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { metrics.add("failedFormulas"); "\\text{对照原图校对公式}" }
                    finally { bitmap?.recycle() }
                    line.text = line.text.replace(part.marker, latex); line.needsReview = true
                    index++; article.metrics = metrics.snapshot()
                }
                line.readings.replaceAll { if (it.text == previousText) Reading(line.text, it.weight, it.count) else it }
                if (line.formulaParts.isNotEmpty()) store.save(article)
            }
        }
        require(article.lines.any { it.kind != BlockKind.GAP }) { "没有识别到正文。请检查选区、文字大小与视频清晰度。" }
        article.metrics = metrics.snapshot()
        article.status = "complete"; article.checkpoint = null; store.save(article)
        store.pruneSources(article)
        update(ProcessingState(false, article.id, 1f, "文章已生成", article.frames, article.lines.size, metrics.elapsedMs))
    }
}
