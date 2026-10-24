package dev.scrolltext.core

import kotlin.test.*
import kotlin.math.*
import kotlin.random.Random

class ReconstructionTest {
    private fun frame(page: FloatArray, y: Int, w: Int = 96, h: Int = 160) = GrayFrame(w, h, page.copyOfRange(y * w, (y + h) * w))
    private fun observation(text: String, y: Double, time: Long = 0, confidence: Double = .95, clipped: Boolean = false) =
        Observation(text, Box(10.0, y, 410.0, y + 24), confidence, time, "source-$time.jpg", clipped = clipped)

    @Test fun `long document matching only visits spatial neighbors`() {
        val a = ArticleAssembler()
        val page = (0 until 10000).map { observation("记录编号${it}以及独立的正文内容", it * 40.0) }
        a.add(page, 0.0)
        val before = a.comparisonCount
        a.add(page.takeLast(15), 0.0)
        assertEquals(10000, a.lines.size)
        assertEquals(15, a.duplicateObservations)
        assertTrue(a.comparisonCount - before < 100, "A viewport must not scan 10000 old rows")
        val offset = 9980 * 40.0
        assertEquals(offset, a.locate(page.drop(9980).take(10).map { it.copy(box = it.box.shift(-offset)) }, offset))
    }
    @Test fun `restored assembly preserves votes provenance and line identities`() {
        val a = ArticleAssembler()
        a.add(listOf(observation("第一个句子用于重建位置", 20.0), observation("第二个句子含有不同的文字", 80.0)), 300.0)
        a.gap(500.0, 2000)
        val restored = ArticleAssembler(a.sorted().map { it.copy(readings = it.readings.map { r -> r.copy() }.toMutableList()) }, a.duplicateObservations)
        val next = listOf(observation("第一个句子用于重建位置", 40.0, 3000), observation("第二个句子含有不同的文字", 100.0, 3000), observation("恢复之后新出现的句子", 280.0, 3000))
        a.add(next, 280.0); restored.add(next, 280.0)
        assertEquals(a.sorted(), restored.sorted())
        assertEquals(a.duplicateObservations, restored.duplicateObservations)
        assertEquals(4, restored.lines.map { it.id }.distinct().size)
    }
    @Test fun `one different number in a long sentence requires review`() {
        val a = ArticleAssembler()
        a.add(listOf(observation("本次实验总共记录了12345个有效样本并完成全部分析", 20.0)), 0.0)
        a.add(listOf(observation("本次实验总共记录了12346个有效样本并完成全部分析", 20.0, 1000)), 0.0)
        assertEquals(1, a.lines.size)
        assertTrue(a.lines.single().needsReview)
    }
    @Test fun `decimal points case and inequality signs remain distinct readings`() {
        for ((first, second) in listOf("测量结果为1.0千米" to "测量结果为10千米", "条件满足 x < 2" to "条件满足 x > 2", "variable A is known" to "variable a is known")) {
            val a = ArticleAssembler()
            a.add(listOf(observation(first, 20.0)), 0.0)
            a.add(listOf(observation(second, 20.0, 1000)), 0.0)
            assertEquals(1, a.lines.size)
            assertEquals(2, a.lines.single().readings.size)
            assertTrue(a.lines.single().needsReview)
        }
    }

    @Test fun `motion handles pause variable speed and reverse scroll`() {
        val rng = Random(123)
        val page = FloatArray(96 * 1400) { rng.nextFloat() * 255 }
        val positions = listOf(0, 0, 7, 43, 121, 199, 149, 149, 220, 270, 339)
        var estimate = 0.0
        for ((a, b) in positions.zipWithNext()) {
            val m = MotionEstimator.estimate(frame(page, a), frame(page, b))
            assertTrue(m.reliable, "$a -> $b (${m.score})")
            assertEquals((b - a).toDouble(), m.dy, 1.0)
            estimate += m.dy
        }
        assertEquals(339.0, estimate, 1.0)
    }
    @Test fun `motion tolerates brightness change`() {
        val rng = Random(7); val data = FloatArray(96 * 800) { rng.nextFloat() * 180 + 30 }
        val a = frame(data, 100); val b = frame(data, 134)
        val lit = GrayFrame(b.width, b.height, b.pixels.map { it * .8f + 20 }.toFloatArray())
        val motion = MotionEstimator.estimate(a, lit)
        assertTrue(motion.reliable); assertEquals(34.0, motion.dy, 1.0)
    }
    @Test fun `scene cut and empty content are rejected`() {
        val a = FloatArray(96 * 400) { Random(10 + it).nextFloat() * 255 }
        val b = FloatArray(96 * 400) { Random(302 + it).nextFloat() * 255 }
        assertFalse(MotionEstimator.estimate(frame(a, 0), frame(b, 0)).reliable)
        val empty = GrayFrame(96, 160, FloatArray(96 * 160) { 255f })
        assertFalse(MotionEstimator.estimate(empty, empty).reliable)
    }
    @Test fun `overlapping forward and reverse frames collapse only same physical lines`() {
        val assembler = ArticleAssembler()
        val page = (0..25).map { observation("文章的第${it}行包含独立测试内容", it * 42.0) }
        for ((t, offset) in listOf(0.0, 130.0, 280.0, 150.0, 430.0, 600.0).withIndex()) {
            val view = page.filter { it.box.top >= offset && it.box.bottom < offset + 500 }.map { it.copy(box = it.box.shift(-offset), timeMs = t * 1000L) }
            assembler.add(view, offset)
        }
        assertEquals(26, assembler.lines.size)
        assertEquals(page.map { it.text }, assembler.sorted().map { it.text })
        assertTrue(assembler.duplicateObservations > 10)
    }
    @Test fun `genuine repeated sentence at another position survives`() {
        val assembler = ArticleAssembler()
        assembler.add(listOf(observation("这是一句刻意重复的原文。", 0.0), observation("这是一句刻意重复的原文。", 400.0)), 0.0)
        assembler.add(listOf(observation("这是一句刻意重复的原文。", 100.0, 1000)), 300.0)
        assertEquals(2, assembler.lines.size)
        assertEquals(1, assembler.duplicateObservations)
    }
    @Test fun `OCR disagreement stays reviewable with source provenance`() {
        val assembler = ArticleAssembler()
        assembler.add(listOf(observation("实验结果精确可靠", 40.0, 0, .45)), 0.0)
        assembler.add(listOf(observation("实验结果准确可靠", 41.0, 1000, .98)), 0.0)
        val row = assembler.lines.single()
        assertEquals("实验结果准确可靠", row.text)
        assertEquals("source-1000.jpg", row.source)
        assertTrue(row.needsReview); assertEquals(2, row.readings.size)
    }
    @Test fun `full line replaces clipped low quality reading`() {
        val assembler = ArticleAssembler()
        assembler.add(listOf(observation("一段被截断的文", 40.0, 0, .5, true)), 0.0)
        assembler.add(listOf(observation("一段被截断的文字终于完整", 41.0, 1000, .96)), 0.0)
        assertEquals(1, assembler.lines.size)
        assertEquals("一段被截断的文字终于完整", assembler.lines.single().text)
        assertFalse(assembler.lines.single().clipped)
    }
    @Test fun `two unique anchors relocalize a backscroll`() {
        val assembler = ArticleAssembler()
        assembler.add(listOf(observation("这是第一个稳定的长句锚点", 350.0), observation("这是第二个独立的长句锚点", 410.0)), 0.0)
        assertEquals(300.0, assembler.locate(listOf(observation("这是第一个稳定的长句锚点", 50.0), observation("这是第二个独立的长句锚点", 110.0))))
        assertNull(assembler.locate(listOf(observation("这是第一个稳定的长句锚点", 50.0))))
    }
    @Test fun `edge fragments with completely wrong OCR do not become extra article lines`() {
        val a = ArticleAssembler()
        a.add(listOf(observation("原图可以帮助我们发现识别错误", 1204.0)), 0.0)
        a.add(listOf(observation("百囡工N叶壬门告功口样而", 1203.0, 3000, .13, true).copy(box = Box(29.0, 1203.0, 458.0, 1221.0))), 0.0)
        assertEquals(1, a.lines.size)
        assertEquals("原图可以帮助我们发现识别错误", a.lines.single().text)
        val b = ArticleAssembler()
        b.add(listOf(observation("LtHt7BAIltHhmtz", 1284.0, 0, .2, true).copy(box = Box(83.0, 1284.0, 459.0, 1300.0))), 0.0)
        b.add(listOf(observation("每段结果都能够回到对应的视频", 1287.0, 1000).copy(box = Box(36.0, 1287.0, 464.0, 1316.0))), 0.0)
        assertEquals(1, b.lines.size)
        assertEquals("每段结果都能够回到对应的视频", b.lines.single().text)
    }
    @Test fun `uncertain segment is never silently joined`() {
        val assembler = ArticleAssembler(); assembler.add(listOf(observation("前一段正文", 0.0)), 0.0)
        assembler.gap(100.0, 9000)
        assembler.add(listOf(observation("后一段正文", 0.0)), 150.0)
        val text = ArticleExport.markdown("标题", assembler.sorted())
        assertTrue(text.contains("待补充")); assertTrue(text.contains("00:09"))
    }
    @Test fun `exports preserve formulas and escape untrusted OCR markup`() {
        val rows = listOf(ArticleLine(1, "<script>alert(1)</script> 行内 \$x^2\$", Box(0.0, 0.0, 500.0, 30.0), .9, "", 0),
            ArticleLine(2, "\\frac{a}{b}", Box(0.0, 50.0, 500.0, 80.0), .9, "", 0, BlockKind.FORMULA))
        val html = ArticleExport.html("<标题>", rows)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;标题&gt;"))
        assertTrue(html.contains("left:'\$\$'"))
        assertTrue(ArticleExport.markdown("标题", rows).contains("\$\$\n\\frac{a}{b}\n\$\$"))
    }
    @Test fun `english wraps keep word boundaries Chinese wraps join without spaces`() {
        val en = listOf(ArticleLine(1, "The first line", Box(0.0, 0.0, 500.0, 30.0), .9, "", 0), ArticleLine(2, "continues here.", Box(0.0, 38.0, 500.0, 68.0), .9, "", 0))
        assertEquals(listOf("The first line continues here."), ArticleExport.paragraphs(en))
        assertEquals(listOf("这是中文没有空格"), ArticleExport.paragraphs(en.mapIndexed { i, a -> a.copy(text = if (i == 0) "这是中文" else "没有空格") }))
    }
    @Test fun `crop validation rejects NaNs and inverted coordinates`() {
        assertFalse(Crop(Double.NaN, 0.0, 1.0, 1.0).valid()); assertFalse(Crop(.8, .2, .3, .8).valid()); assertTrue(Crop().valid())
    }
    @Test fun `formula evidence follows best spatial observation without merging repeated equations`() {
        val marker = "\\text{待识别公式1}"
        fun formula(y: Double, file: String, clipped: Boolean = false) = Observation(marker, Box(10.0,y,400.0,y+60),
            .95, 0, source = "$file.jpg", kind = BlockKind.FORMULA, clipped = clipped, needsReview = true,
            formulaParts = listOf(FormulaPart(marker, Box(10.0,y,400.0,y+60), "$file.png")))
        val a = ArticleAssembler()
        a.add(listOf(formula(100.0, "partial", true), formula(500.0, "other")), 0.0)
        a.add(listOf(formula(101.0, "complete")), 0.0)
        assertEquals(2, a.lines.size)
        assertEquals("complete.png", a.sorted()[0].formulaParts.single().source)
        assertEquals("other.png", a.sorted()[1].formulaParts.single().source)
        assertTrue(a.lines.all { it.needsReview })
    }
    @Test fun `numbered similar headings anchor to exact identities`() {
        val a = ArticleAssembler()
        a.add((1..5).map { observation("第${it}个公式用于验证数学排版", it * 250.0) }, 0.0)
        val rows = listOf(observation("第3个公式用于验证数学排版", 50.0), observation("第4个公式用于验证数学排版", 300.0))
        assertEquals(700.0, a.locate(rows, 650.0, 920.0))
        assertNull(a.locate(listOf(observation("第六个公式用于验证数学排版", 50.0), observation("第七个公式用于验证数学排版", 300.0)), null, 920.0))
    }
    @Test fun `clipped plain OCR inside a formula is replaced by its full evidence`() {
        val a = ArticleAssembler()
        a.add(listOf(observation("lim sinx", 100.0, clipped = true).copy(box = Box(30.0,100.0,260.0,120.0))), 0.0)
        a.add(listOf(Observation("\\text{待识别公式1}", Box(20.0,99.0,300.0,180.0), .95, 0,
            kind = BlockKind.FORMULA, formulaParts = listOf(FormulaPart("\\text{待识别公式1}", Box(20.0,99.0,300.0,180.0), "formula.png")))), 0.0)
        assertEquals(1, a.lines.size); assertEquals(BlockKind.FORMULA, a.lines.single().kind)
    }
    @Test fun `unverified math uses evidence images and verified math uses typesetting`() {
        val line = ArticleLine(1,"x=2",Box(0.0,0.0,100.0,30.0), .9,"source.jpg",0,BlockKind.FORMULA,needsReview = true)
        val draft = ArticleExport.html("测试",listOf(line),sourceUrl = { "sources/source.jpg" })
        assertTrue(draft.contains("<figure>")); assertTrue(draft.contains("src=\"sources/source.jpg\""))
        assertTrue(draft.contains("显示原图"))
        val verified = ArticleExport.html("测试",listOf(line.copy(needsReview = false)),sourceUrl = { "sources/source.jpg" })
        assertFalse(verified.contains("<figure>")); assertTrue(verified.contains("<div class=equation>"))
    }
}
