package dev.scrolltext.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.scrolltext.app.data.*
import dev.scrolltext.app.processing.VideoProcessor
import dev.scrolltext.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoResumeTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun config(math: Boolean = false): JobConfig {
        val file = File(context.filesDir, "resume-demo.mp4")
        instrument.context.assets.open("scroll-demo.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        return JobConfig(Uri.fromFile(file).toString(), "断点恢复实测", Crop(0.0, 100.0/1120, 1.0, 1020.0/1120), 0, 12600, math)
    }
    private class SimulatedLoss : RuntimeException()

    @Test fun durableCheckpointContinuesWithoutDuplicateVotes() = runBlocking {
        val store = ArticleStore(context); val config = config()
        val uninterrupted = Article(title = "完整处理对照", config = config)
        VideoProcessor(context, store).run(uninterrupted) { }
        val interrupted = Article(title = "中断恢复结果", config = config); store.save(interrupted)
        try {
            VideoProcessor(context, store).run(interrupted) { state ->
                if (state.frames >= 9 && state.message.startsWith("分析滚动位置")) throw SimulatedLoss()
            }
            fail("Must interrupt before completion")
        } catch (_: SimulatedLoss) { }
        // Discard all in-memory state, including newer votes after the last durable checkpoint.
        val freshStore = ArticleStore(context); val restored = freshStore.load(interrupted.id)
        assertTrue(restored.checkpoint!!.timeMs > 0)
        val savedTime = restored.checkpoint!!.timeMs
        VideoProcessor(context, freshStore).run(restored) { }
        assertEquals(uninterrupted.lines.map { it.text }, restored.lines.map { it.text })
        assertEquals(uninterrupted.lines.map { it.observations }, restored.lines.map { it.observations })
        assertEquals(uninterrupted.lines.map { it.source }, restored.lines.map { it.source })
        assertEquals(uninterrupted.duplicates, restored.duplicates)
        assertEquals(uninterrupted.frames, restored.frames)
        assertEquals(savedTime, restored.metrics["resumedAtVideoMs"])
        assertNull(restored.checkpoint)
        File(context.filesDir, "stability-resume.json").writeText(JSONObject().put("savedTimeMs", savedTime)
            .put("baseline", store.toJson(uninterrupted)).put("resumed", store.toJson(restored)).toString(2))
    }

    @Test fun formulaStageResumesWithoutDecodingVideoAgain() = runBlocking {
        val store = ArticleStore(context)
        val interrupted = Article(title = "公式阶段恢复", config = config(true)); store.save(interrupted)
        try {
            VideoProcessor(context, store).run(interrupted) { if (it.message.startsWith("整理公式")) throw SimulatedLoss() }
            fail("Must stop before formula inference")
        } catch (_: SimulatedLoss) { }
        val restored = ArticleStore(context).load(interrupted.id)
        assertEquals("formulas", restored.checkpoint!!.stage)
        val count = restored.metrics["decodedFrames"]
        VideoProcessor(context, store).run(restored) { }
        assertEquals(count, restored.metrics["decodedFrames"])
        assertEquals(1L, restored.metrics["formulaDecodes"])
        assertFalse(restored.lines.any { it.text.contains("待识别公式") })
        assertEquals(19, restored.lines.count { it.kind == BlockKind.TEXT })
        assertEquals(1, restored.lines.count { it.kind == BlockKind.FORMULA })
        assertTrue(restored.lines.filter { it.kind == BlockKind.FORMULA }.all { it.needsReview })
    }

}
