package dev.scrolltext.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.scrolltext.app.data.*
import dev.scrolltext.app.ocr.FormulaEngine
import dev.scrolltext.app.ocr.PageOcr
import dev.scrolltext.app.processing.VideoProcessor
import dev.scrolltext.app.processing.VideoFrames
import dev.scrolltext.app.processing.SequentialDecoder
import dev.scrolltext.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoRegressionTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun asset(name: String) = instrument.context.assets.open(name)
    private fun cleanLatex(s: String) = s.replace(Regex("[{}\\s]"), "").replace("\\left", "").replace("\\right", "")

    @Test fun localFormulaModelActuallyRecognizesFraction() = runBlocking {
        val bitmap = asset("formula.png").use { BitmapFactory.decodeStream(it) }
        FormulaEngine(context).use { engine ->
            val result = engine.recognize(bitmap)
            File(context.filesDir, "formula-result.txt").writeText(result.latex)
            assertTrue("Decoder must terminate", result.complete)
            assertEquals("\\fracx^2a^2-\\fracy^2b^2=1", cleanLatex(result.latex))
        }
        bitmap.recycle()
    }

    @Test fun localDetectorSeparatesFormulaFromChineseBody() = runBlocking {
        val bitmap = asset("article-page.png").use { BitmapFactory.decodeStream(it) }
        PageOcr(context, true).use { engine ->
            val rows = engine.recognize(bitmap, 0, 1.0)
            File(context.filesDir, "page-ocr.txt").writeText(rows.joinToString("\n") { "${it.kind}: ${it.text}" })
            assertTrue(rows.any { it.kind == BlockKind.FORMULA && cleanLatex(it.text).contains("\\fracx^2a^2") })
            assertTrue(rows.any { it.text.contains("这是正文结尾") })
        }
        bitmap.recycle()
    }

    @Test fun scrollingVideoPreservesCoverageOrderAndRepeatedOriginalSentence() = runBlocking {
        val file = File(context.filesDir, "scroll-demo.mp4")
        asset("scroll-demo.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val truth = JSONObject(asset("truth.json").bufferedReader().use { it.readText() })
        val c = truth.getJSONArray("crop")
        val config = JobConfig(Uri.fromFile(file).toString(), "变速与回滑 · 实测文章", Crop(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)), 0, (truth.getDouble("duration") * 1000).toLong(), true)
        val article = Article(title = config.title, config = config)
        val store = ArticleStore(context); store.save(article)
        VideoProcessor(context, store).run(article) { }
        File(context.filesDir, "pipeline-result.json").writeText(store.toJson(article).toString(2))
        assertEquals("complete", article.status)
        assertEquals("Synthetic video has continuous overlap", 0, article.lines.count { it.kind == BlockKind.GAP })
        val sentences = truth.getJSONArray("sentences")
        val recognized = article.lines.filter { it.kind == BlockKind.TEXT }
        val matches = (0 until sentences.length()).map { i ->
            val n = normalizedText(sentences.getString(i))
            recognized.maxOfOrNull { similarity(n, normalizedText(it.text)) } ?: 0.0
        }
        assertTrue("All expected sentences recovered: $matches", matches.all { it > .85 })
        val repeated = recognized.count { similarity(normalizedText(it.text), normalizedText("这是一句故意重复的原文。")) > .9 }
        assertEquals("Two actual repeated sentences should survive", 2, repeated)
        assertTrue("No fixed header", recognized.none { it.text.contains("固定标题") || it.text.contains("导航栏") })
        assertTrue("Duplicate observations merged", article.duplicates > 10)
        assertTrue("Formula retained", article.lines.any { it.kind == BlockKind.FORMULA })
        assertEquals("Overlapping frames must only decode this distinct formula once", 1L, article.metrics["formulaDecodes"])
        assertTrue("No pending formula markers", article.lines.none { it.text.contains("待识别公式") })
        val reloaded = store.load(article.id)
        assertTrue("Formula evidence survives save/reload", reloaded.lines.any { it.formulaParts.any { p -> p.source.endsWith(".png") } })
        assertTrue("Final sentence at end", recognized.last().text.contains("这是正文结尾"))
        assertEquals("No extra edge fragments or duplicate lines", sentences.length(), recognized.size)
        for (i in 0 until sentences.length()) {
            assertTrue("Reading order at line $i", similarity(normalizedText(sentences.getString(i)), normalizedText(recognized[i].text)) > .85)
        }
        val zip = store.export(article, "zip")
        java.util.zip.ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry("article.html")); assertNotNull(archive.getEntry("katex/katex.min.js"))
            assertTrue(archive.entries().asSequence().any { it.name.startsWith("sources/") })
        }
    }

    @Test fun sequentialDecoderMatchesPlatformFramesAndSupportsBackwardSeek() = runBlocking {
        val file = File(context.cacheDir, "decoder-test.mp4")
        asset("scroll-demo.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = Uri.fromFile(file)
        SequentialDecoder(context, uri).use { sequential ->
            VideoFrames(context, uri).use { platform ->
                for (time in listOf(0L, 2000L, 6000L, 3000L, 12599L)) {
                    val actual = sequential.frame(time); val expected = platform.frame(time)
                    assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
                    var difference = 0L; var samples = 0
                    for (y in 0 until actual.height step 7) for (x in 0 until actual.width step 7) {
                        val a = actual.getPixel(x,y); val e = expected.getPixel(x,y)
                        for (shift in listOf(0,8,16)) { difference += kotlin.math.abs(((a shr shift) and 255) - ((e shr shift) and 255)); samples++ }
                    }
                    assertTrue("Frame $time differs by ${difference.toDouble()/samples} levels", difference.toDouble()/samples < 3.0)
                    actual.recycle(); expected.recycle()
                }
            }
        }
    }

}
