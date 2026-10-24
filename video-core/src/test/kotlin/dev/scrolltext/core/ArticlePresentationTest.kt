package dev.scrolltext.core

import kotlin.test.*

class ArticlePresentationTest {
    @Test fun legacyGapDisplayPreservesEditedAndRecognizedContent() {
        val original = "此处画面无法可靠衔接，请回看录屏补充。"
        val gap = ArticleLine(1, original, Box(0.0, 0.0, 1.0, 1.0), 0.0, "", 1000,
            BlockKind.GAP, needsReview = true)
        assertTrue(ArticleExport.markdown("文章", listOf(gap)).contains(ArticleExport.gapText))
        assertTrue(ArticleExport.html("文章", listOf(gap)).contains(ArticleExport.gapText))
        assertEquals(original, gap.text)
        assertTrue(gap.needsReview)
        assertEquals(original, ArticleExport.displayText(gap.copy(edited = true)))
        assertEquals(original, ArticleExport.displayText(gap.copy(kind = BlockKind.TEXT)))
        val formula = gap.copy(kind = BlockKind.FORMULA, text = "\\text{公式识别失败，请校对原图}")
        assertEquals("\\text{对照原图校对公式}", ArticleExport.displayText(formula))
        assertEquals(formula.text, ArticleExport.displayText(formula.copy(edited = true)))
        assertEquals(formula.text, ArticleExport.displayText(formula.copy(kind = BlockKind.TEXT)))
    }
}
