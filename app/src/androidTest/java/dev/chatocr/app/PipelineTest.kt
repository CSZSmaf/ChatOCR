package dev.chatocr.app

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    data class Observation(val frameId: String, val height: Float, val shift: Float?, val candidates: List<Candidate>)
    @Test fun replayEnduranceProbe() = runBlocking {
        val sourceId = InstrumentationRegistry.getArguments().getString("probeSession")
        assumeTrue("Controlled source session required", sourceId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context); val source = store.load(sourceId!!)
        val result = Session(config = source.config.copy(title = "边缘残片修复 · 受控画面回放"))
        val engine = PageOcr(); val assembler = ChatAssembler(result); var previous: GrayFrame? = null
        val args = InstrumentationRegistry.getArguments()
        val start = args.getString("startFrame")?.toInt() ?: 0
        val limit = args.getString("limitFrames")?.toInt() ?: source.frames.size
        val tag = args.getString("reportTag") ?: "current"
        val observations = mutableListOf<Observation>()
        try {
            for (frame in source.frames.drop(start).take(limit)) {
                val bitmap = BitmapFactory.decodeFile(store.frameFile(source.id, frame.id).absolutePath)
                try {
                    val gray = PageOcr.gray(bitmap)
                    val shift = previous?.let { MotionMatcher.shift(it, gray) }?.let { it * 1000f / gray.width }
                    val candidates = engine.read(bitmap, source.config.group)
                    observations += Observation(frame.id, bitmap.height * 1000f / bitmap.width, shift, candidates)
                    assembler.merge(candidates, frame.id, bitmap.height * 1000f / bitmap.width, shift)
                    result.frames += frame
                    if (candidates.isNotEmpty()) previous = gray
                } finally { bitmap.recycle() }
            }
            java.io.File(context.getExternalFilesDir(null), "probe-replay-$tag.json").writeText(ChatExport.json(result))
            val numbered = result.messages.mapNotNull { Regex("消息\\s*(\\d{5})(?!\\d)").find(it.text)?.groupValues?.get(1) }
            assertEquals("Same numbered message duplicated", numbered.toSet().size, numbered.size)
            assertTrue("Continuous fixture must not create gaps", result.messages.none { it.kind == Kind.GAP })
        } finally {
            java.io.File(context.getExternalFilesDir(null), "observations-$tag.json").writeText(ChatExport.gson.toJson(observations))
            engine.close()
        }
    }
    @Test fun replaySavedObservations() {
        val name = InstrumentationRegistry.getArguments().getString("observations")
        assumeTrue("Explicit saved OCR observations required", name != null)
        require(name!!.matches(Regex("[a-zA-Z0-9.-]+")))
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val rows = ChatExport.gson.fromJson(java.io.File(dir, name).readText(), Array<Observation>::class.java)
        val result = Session(config = CaptureConfig(direction = Direction.NEWER)); val assembler = ChatAssembler(result)
        rows.forEach { assembler.merge(it.candidates, it.frameId, it.height, it.shift) }
        java.io.File(dir, "assembled-$name").writeText(ChatExport.json(result))
        val numbered = result.messages.mapNotNull { Regex("消息\\s*(\\d{5})(?!\\d)").find(it.text)?.groupValues?.get(1) }
        assertEquals("Numbered message duplicated", numbered.toSet().size, numbered.size)
        assertTrue("Unexpected continuity gap", result.messages.none { it.kind == Kind.GAP })
    }
    @Test fun replayCapturedScrollFrames() = runBlocking {
        val sourceId = InstrumentationRegistry.getArguments().getString("replaySession")
        assumeTrue("Only runs with an explicitly selected controlled capture", sourceId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context); val source = store.load(sourceId!!)
        val result = Session(config = source.config.copy(title = "Android17 · 滚动验证纪念册"))
        val recognizer = PageOcr(); var previous: GrayFrame? = null
        try {
            for (frame in source.frames) {
                val bitmap = BitmapFactory.decodeFile(store.frameFile(source.id, frame.id).absolutePath)
                try {
                    val gray = PageOcr.gray(bitmap)
                    val shift = previous?.let { MotionMatcher.shift(it, gray) }?.let { it * 1000f / gray.width }
                    val candidates = recognizer.read(bitmap, source.config.group)
                    ChatAssembler(result).merge(candidates, frame.id, bitmap.height * 1000f / bitmap.width, shift)
                    store.frameFile(source.id, frame.id).copyTo(store.frameFile(result.id, frame.id), overwrite = false)
                    result.frames += frame
                    if (candidates.isNotEmpty()) previous = gray
                } finally { bitmap.recycle() }
            }
            result.status = "受控素材回放验证"
            store.save(result)
            java.io.File(context.getExternalFilesDir(null), "replay-result.json").writeText(ChatExport.json(result))
            val ids = result.messages.mapNotNull { Regex("消息\\s*(\\d{3})").find(it.text)?.groupValues?.get(1)?.toInt() }
            assertEquals("Numbered messages must not duplicate", ids.toSet().size, ids.size)
            assertEquals("All fully visible fixture messages should survive", (1..77).toList(), ids.filter { it in 1..77 }.sorted())
            assertTrue(result.messages.none { it.kind == Kind.GAP })
        } finally { recognizer.close() }
    }
    @Test fun bundledChineseOcrStorageAndExports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = fixture(false)
        val recognizer = PageOcr()
        try {
            val candidates = recognizer.read(bitmap, false)
            val result = candidates.joinToString("|") { it.text }
            assertTrue("OCR result: $result", result.contains("今天") && result.contains("明天"))
            assertTrue(candidates.any { it.side == Side.LEFT && it.kind == Kind.TEXT })
            assertTrue(candidates.any { it.side == Side.RIGHT && it.kind == Kind.TEXT })
            val store = SessionStore(context)
            val session = Session(config = CaptureConfig(title = "测试素材 · 离线采集", direction = Direction.NEWER))
            store.saveFrame(session.id, "test-frame", bitmap)
            ChatAssembler(session).merge(candidates, "test-frame", bitmap.height * 1000f / bitmap.width)
            session.frames += FrameRecord("test-frame", "frames/test-frame.jpg", System.currentTimeMillis(), bitmap.width, bitmap.height, candidates.size)
            store.save(session)
            val loaded = store.load(session.id)
            assertEquals(session.count(), loaded.count()); assertEquals(session.frames, loaded.frames)
            val bytes = ByteArrayOutputStream(); store.export(loaded, "zip", bytes)
            val entries = mutableListOf<String>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; entries += e.name; zip.closeEntry() } }
            assertTrue(entries.containsAll(listOf("聊天纪念册.html", "聊天记录.json", "聊天记录.txt", "frames/test-frame.jpg")))
            java.io.File(context.getExternalFilesDir(null), "pipeline-result.json").writeText(ChatExport.json(loaded))
            java.io.File(context.getExternalFilesDir(null), "fixture-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            store.delete(session.id)
        } finally { bitmap.recycle(); recognizer.close() }
    }
    @Test fun darkChatAndRepeatedShortBubbles() = runBlocking {
        val bitmap = fixture(true); val recognizer = PageOcr()
        try {
            val page = recognizer.read(bitmap, false)
            val s = Session(); val assembler = ChatAssembler(s)
            assembler.merge(page, "dark1", 2000f)
            assembler.merge(page, "dark2", 2000f, 0f)
            assertEquals(page.count { it.kind == Kind.TEXT || it.kind == Kind.MEDIA }, s.count())
            assertTrue(page.joinToString { it.text }.contains("明天"))
        } finally { bitmap.recycle(); recognizer.close() }
    }
    private fun fixture(dark: Boolean): Bitmap {
        val b = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b); canvas.drawColor(if (dark) Color.rgb(18, 18, 18) else Color.rgb(237, 237, 237))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 42f }
        val rows = listOf("今天一起去散步吧" to false, "好呀，明天也要开心" to true, "好" to false, "好" to false)
        rows.forEachIndexed { i, (text, right) ->
            val y = 100f + i * 250; val left = if (right) 250f else 125f; val end = if (right) 955f else 780f
            p.color = if (right) Color.rgb(149, 236, 105) else if (dark) Color.rgb(45, 45, 45) else Color.WHITE
            canvas.drawRoundRect(RectF(left, y, end, y + 130), 18f, 18f, p)
            p.color = if (dark && !right) Color.WHITE else Color.BLACK
            canvas.drawText(text, left + 26, y + 80, p)
        }
        return b
    }
}
