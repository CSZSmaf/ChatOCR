package dev.chatocr.core

import org.junit.Assert.*
import org.junit.Test

class ExportPresentationTest {
    @Test fun legacyReviewCopyChangesOnlyPresentation() {
        val gap = Message(text = "画面之间缺少可靠重叠；这里可能有遗漏或重复，请核对原图。", side = Side.SYSTEM,
            kind = Kind.GAP, y = 0f, segment = 0, flags = mutableListOf("连续性待核对"))
        val record = Message(text = "明天可能不去了", side = Side.LEFT, kind = Kind.TEXT, y = 10f, segment = 0,
            flags = mutableListOf("多帧文字不一致", "两次识别文字不同，请核对", "画面边缘，可能不完整"))
        val session = Session(messages = mutableListOf(gap, record))
        val stored = ChatExport.json(session)
        assertEquals(listOf("校对文字", "补全边缘内容"), ChatPresentation.reviewHints(record))
        assertEquals(record.text, ChatPresentation.text(record))
        for (export in listOf(ChatExport.text(session), ChatExport.html(session))) {
            assertTrue(export.contains(ChatPresentation.gapText))
            assertTrue(export.contains(record.text))
            assertFalse(export.contains(gap.text))
            assertFalse(export.contains("多帧文字不一致"))
        }
        assertEquals(stored, ChatExport.json(session))
        gap.editedText = gap.text
        assertEquals(gap.editedText, ChatPresentation.text(gap))
        assertTrue(ChatExport.text(session).contains(gap.editedText!!))
        assertEquals(gap.text, ChatPresentation.text(gap.copy(kind = Kind.TEXT, editedText = null)))
    }

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
