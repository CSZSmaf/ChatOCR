package dev.chatocr.app

import android.net.Uri
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ChatVideoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = SessionStore(context)
    private fun draft(platform: String, group: Boolean, direction: Direction): Session {
        val file = File(context.filesDir, "test-chat-$platform.mp4")
        instrumentation.context.assets.open("chat-video/$platform.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Session(config = CaptureConfig(title = "录屏测试 · $platform", platform = if (group) "QQ" else "微信", group = group, direction = direction),
            video = ChatVideoSource(Uri.fromFile(file).toString(), "$platform.mp4", 16000, 0, 16000, top = 180f / 1280, bottom = 1126f / 1280)).also(store::save)
    }
    private fun report(name: String, session: Session) {
        File(context.getExternalFilesDir(null), "v050-$name.json").writeText(ChatExport.json(session))
    }
    private fun verify(session: Session) {
        report(session.config.platform, session)
        assertTrue(session.video!!.complete)
        assertTrue(session.frames.all { it.videoTimeMs != null })
        val ids = session.ordered().mapNotNull { Regex("消息\\s*(\\d{3})").find(it.text)?.groupValues?.get(1)?.toInt() }
        assertEquals("Message order/coverage: $ids", (1..18).filter { it !in listOf(4, 5, 7, 14) }, ids)
        assertEquals(2, session.messages.count { ChatAssembler.normalized(it.text) == "好的" })
        assertEquals("Source image regions", 2, session.messages.count { it.kind == Kind.MEDIA && it.media != null })
        assertEquals("Nicknames should belong to messages", 18, session.count())
        assertTrue("Unexpected gaps", session.messages.none { it.kind == Kind.GAP })
        assertTrue("Static frames should skip OCR", session.video!!.scannedFrames > session.sampledFrames)
    }
    @Test fun wechatVideoKeepsOrderRepeatedMessagesAndPictures() = runBlocking {
        val session = draft("wechat", false, Direction.OLDER)
        ChatVideoProcessor(context, store).run(session)
        verify(session)
        val media = session.messages.first { it.media != null }
        media.editedText = "我们当时发的表情"; media.reviewed = true; store.save(session)
        val zipBytes = ByteArrayOutputStream(); store.export(store.load(session.id), "zip", zipBytes)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
        assertTrue(entries.keys.contains(media.media!!.file))
        val html = entries.getValue("聊天纪念册.html").toString(Charsets.UTF_8)
        assertTrue(html.contains("<img") && html.contains("录屏") && html.contains("我们当时发的表情"))
        val json = ChatExport.fromJson(entries.getValue("聊天记录.json").toString(Charsets.UTF_8))
        assertEquals("", json.video!!.uri)
        assertEquals(media.text, json.messages.first { it.id == media.id }.text)
        val standalone = ByteArrayOutputStream(); store.export(session, "html", standalone)
        assertTrue(standalone.toString("UTF-8").contains("data:image/jpeg;base64,"))
        File(context.getExternalFilesDir(null), "v050-chat-album.zip").writeBytes(zipBytes.toByteArray())
    }
    @Test fun qqPauseReloadAndContinueMatchesUninterruptedRun() = runBlocking {
        val full = draft("qq", true, Direction.NEWER)
        ChatVideoProcessor(context, store).run(full)
        verify(full)
        val partial = draft("qq", true, Direction.NEWER)
        var pause = false
        ChatVideoProcessor(context, store).run(partial, { pause }) { if (it.positionMs >= 5200) pause = true }
        assertFalse(partial.video!!.complete)
        val resumed = store.load(partial.id)
        val first = resumed.messages.first { it.kind == Kind.TEXT }
        first.editedText = "测试校订"; first.reviewed = true; store.save(resumed)
        ChatVideoProcessor(context, SessionStore(context)).run(resumed)
        verify(resumed)
        fun signature(s: Session) = s.ordered().map { listOf(it.text, it.side, it.kind, it.y, it.sender, it.variants, it.observations, it.flags) }
        assertEquals(signature(full), signature(resumed))
        assertEquals(full.frames.map { it.videoTimeMs }, resumed.frames.map { it.videoTimeMs })
        assertEquals("测试校订", store.load(resumed.id).messages.first { it.id == first.id }.editedText)
        report("resume", resumed)
    }
    @Test fun serviceRejectsOverlappingRecognitionWithoutTouchingArchive() = runBlocking {
        val draft = draft("qq", true, Direction.NEWER)
        val before = File(store.directory(draft.id), "session.json").readBytes()
        val lease = requireNotNull(RecognitionGate.acquire(RecognitionKind.CHAT))
        try {
            instrumentation.runOnMainSync {
                ContextCompat.startForegroundService(context, Intent(context, ChatVideoService::class.java).putExtra("sessionId", draft.id))
            }
            withTimeout(10000) { while (ChatVideoRuntime.state.value.sessionId != draft.id || ChatVideoRuntime.state.value.message.isBlank()) delay(100) }
            assertFalse(ChatVideoRuntime.state.value.running)
            assertEquals(RecognitionKind.CHAT, RecognitionGate.state.value)
            assertArrayEquals(before, File(store.directory(draft.id), "session.json").readBytes())
        } finally { lease.close() }
    }
    @Test fun fiveMinuteStationaryRecordingSkipsOcrAndKeepsBoundedHeap() = runBlocking {
        val file = File(context.filesDir, "test-chat-stationary.mp4")
        instrumentation.context.assets.open("chat-video/stationary-5min.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val session = Session(config = CaptureConfig(title = "五分钟静止录屏"), video = ChatVideoSource(Uri.fromFile(file).toString(), "static.mp4", 300000, 0, 300000))
        store.save(session)
        val started = android.os.SystemClock.elapsedRealtime()
        var peak = 0L
        ChatVideoProcessor(context, store).run(session) {
            peak = maxOf(peak, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue(session.video!!.complete)
        assertTrue(session.video!!.scannedFrames >= 750)
        assertTrue("Static video should OCR at most two frames", session.sampledFrames <= 2)
        assertTrue("Working heap should stay below 160 MiB", peak < 160L * 1024 * 1024)
        File(context.getExternalFilesDir(null), "v050-static-metrics.json").writeText("""{"videoMs":300000,"elapsedMs":$elapsed,"peakJavaHeapBytes":$peak,"scannedFrames":${session.video!!.scannedFrames},"ocrFrames":${session.sampledFrames}}""")
    }
}
