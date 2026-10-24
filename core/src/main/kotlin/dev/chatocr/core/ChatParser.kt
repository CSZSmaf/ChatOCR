package dev.chatocr.core

import kotlin.math.abs

/** Input coordinates use a logical width of 1000. Bubble bounds come from image pixels. */
object ChatParser {
    private val clock = Regex("^(?:(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\\d{2,4}[年/.-]\\d{1,2}[月/.-]\\d{1,2}日?|\\d{1,2}月\\d{1,2}日)\\s*)?(?:凌晨|早上|上午|中午|下午|晚上)?\\s*\\d{1,2}[:：]\\d{2}$")
    private val date = Regex("^(今天|昨天|前天|星期[一二三四五六日天]|\\d{2,4}年\\d{1,2}月\\d{1,2}日)$")
    fun parse(lines: List<OcrLine>, bubbles: List<Box>, height: Float, group: Boolean): List<Candidate> {
        val remaining = lines.filter { it.text.isNotBlank() && it.box.height > 0 }.sortedBy { it.box.top }.toMutableList()
        val result = mutableListOf<Candidate>()
        for (bubble in bubbles.sortedBy { it.top }) {
            // OCR line boxes and downsampled bubble edges can disagree by a few pixels.
            // The small vertical allowance joins cut glyphs without reaching the nickname row.
            val inside = remaining.filter { it.box.centerX in bubble.left..bubble.right &&
                it.box.centerY in (bubble.top - 12)..(bubble.bottom + 12) }
            remaining.removeAll(inside.toSet())
            val side = if (bubble.left < 160 && bubble.right < 940) Side.LEFT else if (bubble.right > 840 && bubble.left > 60) Side.RIGHT else Side.UNKNOWN
            // Group names are outside the bubble, immediately above its leading edge.
            // Never absorb timestamps or text belonging to another bubble as a name.
            val name = if (group && side == Side.LEFT) remaining.filter { line ->
                line.box.bottom <= bubble.top + 3 && bubble.top - line.box.bottom in -3f..42f &&
                    abs(line.box.left - bubble.left) < 45 && line.box.height < 40 &&
                    line.text.length <= 32 && !clock.matches(line.text.trim()) && !date.matches(line.text.trim()) &&
                    bubbles.none { it.contains(line.box.centerX, line.box.centerY) }
            }.maxByOrNull { it.box.bottom } else null
            if (name != null) remaining.remove(name)
            val text = inside.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left }).joinToString("\n") { it.text.trim() }
            val flags = mutableListOf<String>()
            // The app's scroll viewport may end slightly inside the selected crop (padding above
            // its input bar). Leave a small guard band so cut bubbles there can still be completed.
            if (bubble.top < 36 || bubble.bottom > height - 36) flags += "画面边缘，可能不完整"
            if (side == Side.UNKNOWN) flags += "发送方待确认"
            if (group && side == Side.LEFT) flags += if (name == null) "群成员待确认" else "群成员昵称来自识别，请核对"
            if (inside.isEmpty()) flags += "非文字内容，请查看原图"
            result += Candidate(text.ifEmpty { "[非文字内容 · 查看原图]" }, side, if (inside.isEmpty()) Kind.MEDIA else Kind.TEXT, bubble, flags, name?.text?.trim())
        }
        // Unassigned lines are never silently discarded. Identity is only a layout inference.
        for (line in remaining) {
            val t = line.text.trim()
            val centered = abs(line.box.centerX - 500) < 105
            val isTime = centered && (clock.matches(t) || date.matches(t))
            val system = centered && (t.contains("撤回") || t.contains("以下是新消息") || t.contains("加入了群聊"))
            val side = when {
                isTime || system -> Side.SYSTEM
                line.box.right < 680 && line.box.left < 240 -> Side.LEFT
                line.box.left > 320 && line.box.right > 760 -> Side.RIGHT
                else -> Side.UNKNOWN
            }
            val flags = mutableListOf<String>()
            if (!isTime && !system) {
                flags += "未定位气泡，请核对分段和发送方"
                if (line.box.top < 36 || line.box.bottom > height - 36) flags += "画面边缘，可能不完整"
            }
            result += Candidate(t, side, if (isTime) Kind.TIME else Kind.TEXT, line.box, flags)
        }
        return result.sortedBy { it.box.top }
    }
}
