package dev.chatocr.core

import org.junit.Assert.*
import org.junit.Test

class ExportPresentationTest {
    @Test fun sourceLinksUseReadableLabelsAndKeepOriginalTargets() {
        val frame = "4b65b828-e06a-4d4f-aac7-14787dcb5cd8"
        val video = "71c57450-851d-4240-8c8b-9be76ed0aace"
        val session = Session(config = CaptureConfig(title = "旅行聊天"))
        session.frames += FrameRecord(frame, "frames/$frame.jpg", 10000, 720, 1280, 1)
        session.frames += FrameRecord(video, "frames/$video.jpg", 12000, 720, 1280, 1, 65000)
        session.messages += Message(text = "明天见", side = Side.LEFT, kind = Kind.TEXT, y = 100f, segment = 0,
            evidence = mutableListOf(Evidence(frame, Box(1f, 2f, 30f, 40f)), Evidence(video, Box(1f, 2f, 30f, 40f))))
        val html = ChatExport.html(session, evidence = true)
        val visible = html.replace(Regex("<[^>]+>"), "")
        assertTrue(html.contains("href=\"frames/$frame.jpg\">原图 1</a>"))
        assertTrue(visible.contains("录屏 1:05.000"))
        assertFalse(visible.contains(frame)); assertFalse(visible.contains(video))
        assertEquals(frame, session.messages.single().evidence.first().frameId)
    }
}
