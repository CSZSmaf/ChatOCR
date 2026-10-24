package dev.chatocr.core

/** Reader copy is separate from the recognition flags used for merging. */
object ChatPresentation {
    const val gapText = "回看前后画面，补全这段记录。"
    private const val legacyGap = "画面之间缺少可靠重叠；这里可能有遗漏或重复，请核对原图。"

    fun text(message: Message): String = when {
        message.editedText != null -> message.displayText
        message.kind == Kind.GAP && message.text == legacyGap -> gapText
        message.kind == Kind.MEDIA && message.text == "[非文字内容 · 查看原图]" -> "[查看原图]"
        else -> message.text
    }

    fun reviewHints(message: Message): List<String> = message.flags.map { flag ->
        when (flag) {
            "画面边缘，可能不完整" -> "补全边缘内容"
            "未定位气泡，请核对分段和发送方" -> "核对分段与称呼"
            "两次识别文字不同，请核对", "多帧文字不一致" -> "校对文字"
            "多行文字对齐，请核对" -> "核对消息顺序"
            "同位置识别不同，可能重复" -> "核对重复消息"
            "群成员待确认", "群成员昵称来自识别，请核对" -> "确认群成员"
            "发送方待确认" -> "确认发送方"
            "非文字内容，请查看原图" -> "查看原图"
            "图片或表情包，边界请核对" -> "核对图片范围"
            "连续性待核对" -> "核对前后内容"
            "时间文字待核对" -> "校对时间"
            else -> "对照原图校对"
        }
    }.distinct()
}
