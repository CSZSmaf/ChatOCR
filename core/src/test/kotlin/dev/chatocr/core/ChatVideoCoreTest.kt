package dev.chatocr.core

import org.junit.Assert.*
import org.junit.Test

class ChatVideoCoreTest {
    @Test fun legacyArchivesStayReadableAndExportedVideoUrisArePrivate() {
        val original = Session()
        val legacy = ChatExport.fromJson(ChatExport.json(original))
        assertNull(legacy.video)
        val source = ChatVideoSource("content://private/video", "video.mp4", 9000, 1000, 8000)
        val session = Session(video = source)
        session.frames += FrameRecord("one", "frames/one.jpg", 100000, 720, 1000, 1, 1400)
        val exported = ChatExport.fromJson(ChatExport.json(session))
        assertEquals("", exported.video!!.uri)
        assertEquals("content://private/video", source.uri)
        assertEquals(1400L, exported.frames.first().videoTimeMs)
        assertEquals(100000L, exported.frames.first().capturedAt)
    }
    @Test fun imageDetectorExcludesAvatarsAndUniformTextBubbles() {
        val w = 180; val h = 320
        val pixels = IntArray(w * h) { 0xffeeeeee.toInt() }
        for (y in 20..40) for (x in 20..120) pixels[y * w + x] = -1
        for (y in 60..100) for (x in 2..13) pixels[y * w + x] = (0xff000000.toInt() or ((x * 15) shl 16) or (y shl 8))
        for (y in 140..200) for (x in 24..84) pixels[y * w + x] = (0xff000000.toInt() or ((x * 2) shl 16) or (y shl 8) or 50)
        val boxes = MediaRegionDetector.detect(pixels, w, h)
        assertEquals(1, boxes.size)
        assertTrue(boxes.single().contains(300f, 900f))
    }
    @Test fun clippedGrayTextCannotBecomeASticker() {
        val w = 180; val h = 300
        val pixels = IntArray(w * h) { 0xffeeeeee.toInt() }
        for (y in 0..7) for (x in 45..110) {
            val gray = (x * 13 + y * 7) % 160
            pixels[y * w + x] = 0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        assertTrue(MediaRegionDetector.detect(pixels, w, h).isEmpty())
    }
    @Test fun clippedGroupNicknameMovesToSenderEvidenceWhenBubbleAppears() {
        val s = Session(config = CaptureConfig(group = true, direction = Direction.NEWER))
        val assembler = ChatAssembler(s)
        assembler.merge(listOf(Candidate("小林", Side.LEFT, Kind.TEXT, Box(125f, 980f, 190f, 1008f),
            listOf("未定位气泡，请核对", "画面边缘，可能不完整"))), "first", 1000f)
        assembler.merge(listOf(Candidate("明天见", Side.LEFT, Kind.TEXT, Box(125f, 850f, 600f, 940f), sender = "小林")), "second", 1000f, -160f)
        assertEquals(1, s.count())
        assertEquals("小林", s.messages.single().sender)
        assertEquals("first", s.messages.single().senderEvidence!!.single().frameId)
    }
}
