package dev.chatocr.core

import org.junit.Assert.*
import org.junit.Test

class RealLayoutRegressionTest {
    @Test fun centeredNoisyTimestampKeepsItsOwnRole() {
        val line = OcrLine("昨关 18:42", Box(410f, 300f, 580f, 343f))
        val time = ChatParser.parse(listOf(line), emptyList(), 1200f, false).single()
        assertEquals(Kind.TIME, time.kind); assertEquals(Side.SYSTEM, time.side)
        assertTrue("时间文字待核对" in time.flags)
        val bubble = Box(100f, 280f, 700f, 370f)
        val message = ChatParser.parse(listOf(line), listOf(bubble), 1200f, false).single()
        assertEquals(Kind.TEXT, message.kind); assertEquals(Side.LEFT, message.side)
    }

    @Test fun loadingMediaCanMoveOnlyTheLowerPartOfAChat() {
        val session = Session(); val assembler = ChatAssembler(session)
        fun bubble(text: String, y: Float) = Candidate(text, Side.RIGHT, Kind.TEXT, Box(400f, y, 850f, y + 80))
        val above = bubble("上面的这一条消息保持位置", 100f)
        val below = bubble("下面的这一条消息跟随图片移动", 600f)
        val short = bubble("好的", 710f)
        assembler.merge(listOf(above, below, short), "loading", 1200f)
        assembler.merge(listOf(above, below.copy(box = below.box.copy(top = 720f, bottom = 800f)),
            short.copy(box = short.box.copy(top = 830f, bottom = 910f))), "loaded", 1200f, 0f)
        assertEquals(3, session.count())
    }

    @Test fun containedLooseLinesRejoinACompleteMultilineBubble() {
        val session = Session(); val assembler = ChatAssembler(session)
        val full = Candidate("这是消息的第一行\n第二行", Side.RIGHT, Kind.TEXT, Box(120f, 200f, 850f, 340f))
        assembler.merge(listOf(full), "full", 1000f)
        val fragment = Candidate("第二行", Side.LEFT, Kind.TEXT, Box(150f, 290f, 280f, 322f), listOf("未定位气泡，请核对分段和发送方"))
        assembler.merge(listOf(fragment), "outlineLost", 1000f, 0f)
        assertEquals(1, session.count()); assertEquals(full.text, session.messages.single().text)
    }
    @Test fun animatedFramesHaveABoundedWaitAndResetAfterAcceptance() {
        val gate = StableFrameGate()
        for (time in 0L..1540L step 220) { gate.offer(time, true); assertFalse(gate.ready(time)) }
        gate.offer(1760, true); assertTrue(gate.ready(1760))
        gate.reset(); assertFalse(gate.ready(10000))
        gate.offer(10000, false); assertFalse(gate.ready(10449)); assertTrue(gate.ready(10450))
    }

    @Test fun screenshotTypographyAndColoredLongBubbleAreSeparated() {
        val width = 200; val height = 300
        val pixels = IntArray(width * height) { 0xffeeeeee.toInt() }
        val screenshot = Box(150f, 100f, 400f, 700f)
        val outgoing = Box(150f, 800f, 860f, 970f)
        fun fill(box: Box, color: Int) {
            for (y in (box.top / 5).toInt() until (box.bottom / 5).toInt())
                for (x in (box.left / 5).toInt() until (box.right / 5).toInt()) pixels[y * width + x] = color
        }
        fill(screenshot, 0xffffffff.toInt()); fill(outgoing, 0xff9cee65.toInt())
        val lines = listOf(OcrLine("图片内的小字", Box(170f, 300f, 350f, 314f)),
            OcrLine("两行较长的聊天文字", Box(180f, 830f, 800f, 870f)))
        val layout = ChatLayout.classify(pixels, width, height, listOf(screenshot, outgoing), lines)
        assertEquals(listOf(screenshot), layout.media)
        assertEquals(listOf(outgoing), layout.text)
        assertEquals(Side.RIGHT, layout.sides[outgoing])
        assertEquals(Side.RIGHT, ChatParser.parse(lines.drop(1), layout.text, 1500f, false, layout.sides).single().side)
    }

    @Test fun unreadableFrameDoesNotAddAnImageCopyOfTheSameTextBubble() {
        val session = Session(); val assembler = ChatAssembler(session)
        val box = Box(560f, 300f, 850f, 410f)
        val image = Candidate("[图片 / 表情]", Side.RIGHT, Kind.MEDIA, box, listOf("图片或表情包，边界请核对"))
        assembler.merge(listOf(image), "blur", 1000f)
        val text = Candidate("记得带雨伞", Side.RIGHT, Kind.TEXT, box)
        assembler.merge(listOf(text), "clear", 1000f, 0f)
        repeat(3) { assembler.merge(listOf(image), "moving$it", 1000f, 0f) }
        assertEquals(1, session.count()); assertEquals(text.text, session.messages.single().text)
        assertEquals(Kind.TEXT, session.messages.single().kind)
    }

    @Test fun uncertainSideCanRecoverAtTheSamePositionWhileSeparateRepeatsSurvive() {
        val session = Session(); val assembler = ChatAssembler(session)
        val first = Candidate("好的", Side.UNKNOWN, Kind.TEXT, Box(650f, 100f, 820f, 200f))
        assembler.merge(listOf(first), "uncertain", 1000f)
        val known = first.copy(side = Side.RIGHT)
        val second = known.copy(box = Box(650f, 250f, 820f, 350f))
        assembler.merge(listOf(known, second), "clear", 1000f, 0f)
        assertEquals(2, session.count()); assertTrue(session.messages.all { it.side == Side.RIGHT })
        assembler.merge(listOf(first), "jitter", 1000f, 0f)
        assertEquals(2, session.count())
    }

    @Test fun SimilarTextOnOppositeSidesRemainsSeparate() {
        val session = Session(); val assembler = ChatAssembler(session)
        val first = Candidate("好的", Side.LEFT, Kind.TEXT, Box(100f, 100f, 250f, 200f))
        assembler.merge(listOf(first), "left", 1000f)
        assembler.merge(listOf(first.copy(side = Side.RIGHT, box = Box(650f, 100f, 850f, 200f))), "right", 1000f, 0f)
        assertEquals(2, session.count())
    }
}
