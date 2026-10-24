package dev.chatocr.core

import org.junit.Assert.*
import org.junit.Test

class ChatCoreTest {
    @Test fun uncertainSideOfCutGlyphIsRecoveredFromContainingBubble() {
        val s = Session(); val a = ChatAssembler(s)
        val partial = Candidate("残", Side.UNKNOWN, Kind.TEXT, Box(340f, 788f, 464f, 798f), listOf("画面边缘，可能不完整", "未定位气泡，请核对"))
        a.merge(listOf(partial), "cut", 800f)
        val full = Candidate("完整的聊天内容", Side.LEFT, Kind.TEXT, Box(115f, 350f, 794f, 478f))
        a.merge(listOf(full), "whole", 800f, -400f)
        assertEquals(1, s.count()); assertEquals(Side.LEFT, s.messages.single().side)
        assertEquals(full.text, s.messages.single().text)
    }
    @Test fun subpixelAlignmentOfTinyEdgeTailDoesNotLeaveMediaCopy() {
        val s = Session(); val a = ChatAssembler(s)
        val full = Candidate("聊天正文", Side.LEFT, Kind.TEXT, Box(115f, 50f, 794f, 172f))
        a.merge(listOf(full), "whole", 800f)
        val tail = Candidate("[非文字内容 · 查看原图]", Side.LEFT, Kind.MEDIA, Box(122f, 0f, 794f, 11f), listOf("画面边缘，可能不完整", "非文字内容，请查看原图"))
        a.merge(listOf(tail), "tail", 800f, -166f)
        assertEquals(1, s.count()); assertEquals(Kind.TEXT, s.messages.single().kind)
    }
    @Test fun cutGlyphBoxJustOutsideBubbleDoesNotBecomeExtraMessage() {
        val result = ChatParser.parse(listOf(OcrLine("残字", Box(140f, 787f, 240f, 799f))), listOf(Box(115f, 755f, 795f, 788f)), 810f, false)
        assertEquals(1, result.size); assertEquals("残字", result.single().text)
    }
    @Test fun paddingInsideCropStillMarksClippedBubbleForCompletion() {
        val clipped = ChatParser.parse(listOf(OcrLine("残字", Box(145f, 774f, 230f, 785f))), listOf(Box(115f, 760f, 794f, 783f)), 800f, false).single()
        assertTrue(clipped.flags.any { it.startsWith("画面边缘") })
        val s = Session(); val a = ChatAssembler(s)
        a.merge(listOf(clipped), "clipped", 800f)
        a.merge(listOf(Candidate("完整消息第二十二条", Side.LEFT, Kind.TEXT, Box(115f, 360f, 794f, 482f))), "full", 800f, -400f)
        assertEquals(1, s.count()); assertEquals("完整消息第二十二条", s.messages.single().text)
    }
    @Test fun unlocatedEdgeTextMergesOnlyIntoSpatiallyContainingBubble() {
        val s = Session(); val a = ChatAssembler(s)
        val fragment = ChatParser.parse(listOf(OcrLine("n人nA", Box(140f, 775f, 200f, 787f))), emptyList(), 800f, false).single()
        a.merge(listOf(fragment), "cut", 800f)
        val full = Candidate("哈哈\n测试消息 00033", Side.LEFT, Kind.TEXT, Box(115f, 350f, 794f, 478f))
        a.merge(listOf(full), "full", 800f, -400f)
        assertEquals(1, s.count()); assertEquals(full.text, s.messages.single().text)
        a.merge(listOf(full, full.copy(box = Box(115f, 550f, 794f, 678f))), "repeat", 800f, 0f)
        assertEquals(2, s.count())
    }
    @Test fun groupNamesAttachToBubblesButTimestampsRemainSeparate() {
        val lines = listOf(OcrLine("小林", Box(115f, 90f, 170f, 114f)),
            OcrLine("好", Box(140f, 151f, 175f, 185f)), OcrLine("昨天 12:30", Box(400f, 215f, 600f, 245f)),
            OcrLine("好", Box(140f, 286f, 175f, 315f)))
        val result = ChatParser.parse(lines, listOf(Box(110f, 130f, 250f, 205f), Box(110f, 270f, 250f, 335f)), 800f, true)
        assertEquals(3, result.size); assertEquals("小林", result.first().sender)
        assertEquals(Kind.TIME, result[1].kind); assertNull(result.last().sender)
        assertEquals(2, result.count { it.text == "好" })
    }
    @Test fun noisyObservationsAndEvidenceRemainBoundedAndKeepFullSource() {
        val session = Session(); val assembler = ChatAssembler(session)
        repeat(40) { i ->
            assembler.merge(listOf(Candidate("残片$i", Side.LEFT, Kind.TEXT, Box(100f, 0f, 600f, 50f), listOf("画面边缘，可能不完整"))), "clip$i", 800f, 0f)
        }
        assembler.merge(listOf(Candidate("这是一条完整的消息", Side.LEFT, Kind.TEXT, Box(100f, 50f, 600f, 190f))), "complete", 800f, 140f)
        assertEquals(1, session.count())
        val message = session.messages.single()
        assertEquals("这是一条完整的消息", message.text)
        assertTrue(message.observations.size <= 8); assertTrue(message.variants.size <= 8)
        assertTrue(message.evidence.size <= 4); assertTrue(message.evidence.any { it.frameId == "complete" })
    }
    @Test fun indexedLongHistoryPreservesRepeatedMessagesAndReversePass() {
        val session = Session(config = CaptureConfig(direction = Direction.NEWER))
        var assembler = ChatAssembler(session)
        fun page(start: Int) = (start until start + 8).mapIndexed { row, id ->
            c(if (id % 3 == 0) "好" else "独立消息 $id", 60f + row * 110, if (id % 2 == 0) Side.LEFT else Side.RIGHT)
        }
        val begin = System.nanoTime()
        for (p in 0 until 2500) assembler.merge(page(p * 4), "p$p", 1000f, if (p == 0) null else -440f)
        assertEquals(10004, session.count())
        assembler = ChatAssembler(session) // Restore the spatial index from a stored history.
        for (p in 2498 downTo 2480) assembler.merge(page(p * 4), "r$p", 1000f, 440f)
        assertEquals(10004, session.count()); assertTrue(session.messages.none { it.kind == Kind.GAP })
        println("10,004 messages and reverse pass: ${(System.nanoTime() - begin) / 1_000_000} ms")
    }
    private fun c(text: String, y: Float, side: Side = Side.LEFT) = Candidate(text, side, Kind.TEXT, Box(100f, y - 18, 500f, y + 18))
    @Test fun threeIndependentStableLinesAlignNoisyBubblesWithoutPixelMotion() {
        val s = Session(); val a = ChatAssembler(s)
        val old = listOf("甲", "乙", "丙").mapIndexed { i, suffix -> c("这条回复已经收到\n保留这段不变的长句$suffix", 100f + i * 200) }
        a.merge(old, "before", 800f)
        val next = old.map { c(it.text.replace("已经", "已径"), it.box.centerY - 40) }
        assertFalse(a.merge(next, "after", 800f).gap)
        assertEquals(3, s.count()); assertEquals(40f, s.offset)
        assertTrue(s.messages.all { "多行文字对齐，请核对" in it.flags && it.evidence.size == 2 })
    }
    @Test fun matchingLinesCannotHideChangedNumbers() {
        val s = Session(); val a = ChatAssembler(s)
        val old = listOf("甲", "乙", "丙").mapIndexed { i, suffix -> c("金额120元\n请按之前的约定处理$suffix", 100f + i * 200) }
        a.merge(old, "before", 800f)
        assertTrue(a.merge(old.map { it.copy(text = it.text.replace("120", "140")) }, "after", 800f).gap)
        assertEquals(6, s.count())
    }
    @Test fun twoStableLinesAreNotEnoughForFallbackAlignment() {
        val s = Session(); val a = ChatAssembler(s)
        val old = listOf("甲", "乙").mapIndexed { i, suffix -> c("这条回复已经收到\n保留这段不变的长句$suffix", 100f + i * 200) }
        a.merge(old, "before", 800f)
        assertTrue(a.merge(old.map { it.copy(text = it.text.replace("已经", "已径")) }, "after", 800f).gap)
    }
    @Test fun repeatedFootersDoNotAnchorDifferentBubbles() {
        val s = Session(); val a = ChatAssembler(s)
        val old = listOf("甲", "乙", "丙").mapIndexed { i, suffix -> c("已收到$suffix\n这是重复出现的共同尾句", 100f + i * 200) }
        a.merge(old, "before", 800f)
        assertTrue(a.merge(old.map { it.copy(text = it.text.replace("收到", "收至")) }, "after", 800f).gap)
    }
    @Test fun clippedLinesCannotEstablishFallbackAlignment() {
        val s = Session(); val a = ChatAssembler(s)
        val old = listOf("甲", "乙", "丙").mapIndexed { i, suffix -> c("这条回复已经收到\n保留这段不变的长句$suffix", 100f + i * 200) }
        a.merge(old, "before", 800f)
        assertTrue(a.merge(old.map { it.copy(text = it.text.replace("已经", "已径"), flags = listOf("画面边缘，可能不完整")) }, "after", 800f).gap)
    }
    @Test fun stationaryFramesDoNotDuplicate() {
        val s = Session(); val a = ChatAssembler(s)
        repeat(10) { a.merge(listOf(c("这是第一条完整的聊天消息", 100f), c("这是第二条完整的聊天消息", 200f)), "f$it", 1000f, 0f) }
        assertEquals(2, s.count()); assertEquals(4, s.messages[0].evidence.size)
    }
    @Test fun repeatedShortMessagesAndOppositeSidesArePreserved() {
        val s = Session(); val a = ChatAssembler(s)
        val page = listOf(c("好", 100f), c("好", 200f), c("好", 300f, Side.RIGHT))
        a.merge(page, "a", 1000f); a.merge(page, "b", 1000f, 0f)
        assertEquals(3, s.count())
    }
    @Test fun scrollingBackAndForthKeepsChronology() {
        val s = Session(); val a = ChatAssembler(s)
        val m = (0..19).map { c("今天第 $it 条消息，需要好好留存。", 80f + it * 100) }
        fun page(start: Int) = m.drop(start).take(6).map { it.copy(box = it.box.let { b -> b.copy(top = b.top - start * 100, bottom = b.bottom - start * 100) }) }
        listOf(8, 5, 2, 5, 8, 11, 14).forEachIndexed { i, p -> a.merge(page(p), "f$i", 700f) }
        assertEquals((2..19).map { "今天第 $it 条消息，需要好好留存。" }, s.ordered().map { it.text })
        assertTrue(s.messages.none { it.kind == Kind.GAP })
    }
    @Test fun noOverlapCreatesExplicitGap() {
        val s = Session(); val a = ChatAssembler(s)
        a.merge(listOf(c("hello", 100f)), "a", 1000f)
        val result = a.merge(listOf(c("other", 200f)), "b", 1000f)
        assertTrue(result.gap); assertEquals(1, s.messages.count { it.kind == Kind.GAP })
        assertEquals("other", s.ordered().first().text)
    }
    @Test fun clockInsideBubbleIsNotTimestamp() {
        val lines = listOf(OcrLine("12:30", Box(380f, 20f, 620f, 60f)), OcrLine("12:30", Box(150f, 150f, 300f, 190f)))
        val parsed = ChatParser.parse(lines, listOf(Box(110f, 120f, 420f, 220f)), 800f, false)
        assertEquals(Kind.TIME, parsed.first().kind); assertEquals(Kind.TEXT, parsed.last().kind)
    }
    @Test fun exportEscapesHtmlAndRetainsRawEdits() {
        val s = Session(config = CaptureConfig(title = "<script>alert(1)</script>"))
        s.messages += Message(text = "原文字", side = Side.LEFT, kind = Kind.TEXT, y = 1f, segment = 0, editedText = "<img src=x onerror=alert(1)>")
        assertFalse(ChatExport.html(s).contains("<script>")); assertFalse(ChatExport.html(s).contains("<img src=x"))
        val restored = ChatExport.fromJson(ChatExport.json(s))
        assertEquals("原文字", restored.messages.first().text); assertEquals(s.messages.first().editedText, restored.messages.first().editedText)
    }
    @Test fun longCaptureIsLinearInMessagesAndResumeDoesNotLoseAnchors() {
        var s = Session(config = CaptureConfig(direction = Direction.NEWER))
        repeat(300) { page ->
            if (page == 150) s = ChatExport.fromJson(ChatExport.json(s))
            val a = ChatAssembler(s)
            a.merge((page * 4 until page * 4 + 8).mapIndexed { row, id -> c("消息 $id ：今天的天气非常好，记得出门。", 80f + row * 100) }, "f$page", 1000f)
        }
        assertEquals(1204, s.count()); assertEquals(0, s.messages.count { it.kind == Kind.GAP })
    }
    @Test fun motionRejectsBlankAndRecognizesTranslation() {
        assertNull(MotionMatcher.shift(GrayFrame(40, 80, IntArray(3200) { 255 }), GrayFrame(40, 80, IntArray(3200) { 255 })))
        val data = IntArray(96 * 160) { i -> if ((i / 96) % 11 < 3 && (i % 96) in 15..75) (i * 31 + i / 96 * 17) % 180 else 240 }
        val shifted = IntArray(data.size) { i -> if (i / 96 >= 23) data[i - 23 * 96] else 240 }
        assertEquals(23, MotionMatcher.shift(GrayFrame(96, 160, data), GrayFrame(96, 160, shifted)))
    }
    @Test fun detectorFindsLightAndGreenBubbles() {
        val w = 180; val h = 300; val pixels = IntArray(w * h) { 0xffededed.toInt() }
        for (y in 20..50) for (x in 20..90) pixels[y * w + x] = 0xffffffff.toInt()
        for (y in 90..130) for (x in 60..160) pixels[y * w + x] = 0xff95ec69.toInt()
        val boxes = BubbleDetector.detect(pixels, w, h)
        assertEquals(2, boxes.size)
    }
    @Test fun clippedBubbleIsReplacedByCompleteBubbleAtSameLocation() {
        val s = Session(); val a = ChatAssembler(s)
        val clipped = Candidate("一条尚未显示完整", Side.LEFT, Kind.TEXT, Box(100f, 0f, 600f, 50f), listOf("画面边缘，可能不完整"))
        a.merge(listOf(clipped), "f1", 800f)
        val complete = Candidate("这是一条尚未显示完整的消息", Side.LEFT, Kind.TEXT, Box(100f, 50f, 600f, 190f))
        a.merge(listOf(complete), "f2", 800f, 140f)
        assertEquals(1, s.count()); assertEquals(complete.text, s.messages.single().text)
        assertTrue(s.messages.single().flags.isEmpty())
    }
    @Test fun exportChronologyAndTimeContextAreExplicit() {
        val s = Session()
        s.messages += Message(text = "较晚", side = Side.RIGHT, kind = Kind.TEXT, y = 200f, segment = 0)
        s.messages += Message(text = "昨天 12:30", side = Side.SYSTEM, kind = Kind.TIME, y = 100f, segment = 0)
        val exported = ChatExport.fromJson(ChatExport.json(s))
        assertEquals(Kind.TIME, exported.messages.first().kind)
        assertEquals("昨天 12:30", exported.messages.last().visibleTimeContext)
    }
    @Test fun clippedBubbleComponentGroupsGarbledEdgeLines() {
        val w = 180; val h = 300; val pixels = IntArray(w * h) { 0xffededed.toInt() }
        for (y in 296..299) for (x in 20..130) pixels[y * w + x] = 0xffffffff.toInt()
        val bubbles = BubbleDetector.detect(pixels, w, h)
        assertEquals(1, bubbles.size)
        val lines = listOf(OcrLine("残", Box(150f, 1650f, 240f, 1663f)), OcrLine("片", Box(300f, 1650f, 400f, 1663f)))
        val candidates = ChatParser.parse(lines, bubbles, h * 1000f / w, false)
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().flags.any { it.startsWith("画面边缘") })
    }
    @Test fun contradictoryOcrInAlignedBubbleDoesNotSpawnCopies() {
        val s = Session(); val a = ChatAssembler(s)
        val before = c("这是前一条可靠文字锚点", 100f)
        val after = c("这是后一条可靠文字锚点", 400f)
        listOf("哈哈\n测试消息007", "|哈哈\n|测试消息 007", "哈哈\n测试消息 007", "哈哈\n测试消息007").forEachIndexed { i, text ->
            a.merge(listOf(before, c(text, 230f), after), "f$i", 800f)
        }
        assertEquals(3, s.count()); assertEquals("哈哈测试消息007", ChatAssembler.normalized(s.ordered()[1].text))
        assertTrue(s.ordered()[1].variants.isNotEmpty())
    }
}
