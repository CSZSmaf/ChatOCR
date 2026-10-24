package dev.chatocr.core

import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatExport {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    fun json(session: Session): String {
        var time: String? = null
        val ordered = session.ordered().map { m ->
            if (m.kind == Kind.GAP) time = null
            if (m.kind == Kind.TIME) time = m.displayText
            m.copy(visibleTimeContext = time)
        }
        return gson.toJson(session.copy(messages = ordered.toMutableList(), video = session.video?.copy(uri = "")))
    }
    fun fromJson(json: String): Session = gson.fromJson(json, Session::class.java).also {
        require(it.schemaVersion == 1) { "不支持的存档版本" }
        require(it.id.matches(Regex("[a-zA-Z0-9-]+"))) { "无效存档编号" }
    }
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    fun speaker(m: Message, s: Session): String = m.sender?.takeIf { it.isNotBlank() } ?: when (m.side) {
        Side.LEFT -> if (s.config.group) "群成员（未确认）" else s.config.leftName
        Side.RIGHT -> s.config.rightName
        Side.SYSTEM -> "时间 / 系统"
        Side.UNKNOWN -> "发送方待确认"
    }
    fun text(session: Session): String = buildString {
        appendLine(session.config.title); appendLine("${session.config.platform} · ${session.count()} 条 · 拾语整理")
        appendLine("时间以聊天画面显示为准。\n")
        session.ordered().forEach { m ->
            appendLine(if (m.kind == Kind.TIME || m.kind == Kind.GAP) "—— ${m.displayText} ——" else "${speaker(m, session)}：${m.displayText}")
            if (!m.reviewed && m.flags.isNotEmpty()) appendLine("[待校对：${m.flags.joinToString("；")}]")
            appendLine()
        }
    }
    fun html(session: Session, evidence: Boolean = false, mediaSource: ((Message) -> String?)? = null): String = buildString {
        val title = escape(session.config.title)
        val frameTimes = session.frames.associate { it.id to it.videoTimeMs }
        val frameNumbers = session.frames.mapIndexed { index, frame -> frame.id to index + 1 }.toMap()
        append("""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:"><title>$title</title><style>
            *{box-sizing:border-box}body{margin:0;background:#f6f4ee;color:#20352e;font:16px/1.75 system-ui,sans-serif}main{max-width:840px;margin:auto;padding:52px 24px}header{border-bottom:1px solid #d4dcd2;padding-bottom:28px;margin-bottom:36px}.eyebrow{color:#236b5c;font-size:14px}h1{font-size:32px;line-height:1.35;font-weight:600}small,.meta{color:#20352e;font-size:14px}.row{display:flex;flex-direction:column;align-items:flex-start;margin:22px 0}.row.right{align-items:flex-end}.bubble{white-space:pre-wrap;overflow-wrap:anywhere;max-width:88%;padding:14px 20px;background:#fff;border:1px solid #e3e7df;border-radius:4px 18px 18px 18px}.right .bubble{background:#ddebe1;border-radius:18px 4px 18px 18px}.name{font-size:14px;margin:0 4px 6px}.time{text-align:center;color:#20352e;font-size:14px;margin:28px 0}.gap{background:#f7e7cd;padding:16px;border-radius:12px}.flag{font-size:14px;color:#75471c}a{color:#236b5c}details{font-size:14px}footer{border-top:1px solid #d4dcd2;margin-top:40px;padding-top:20px;font-size:14px;color:#20352e}@media print{body{background:white}.row{break-inside:avoid}}
            </style></head><body><main><header><div class="eyebrow">拾语</div><h1>$title</h1>""".trimIndent())
        val day = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date(session.createdAt))
        append("<div class=meta>${escape(session.config.platform)} · ${session.count()} 条记录 · 整理于 $day</div></header>")
        session.ordered().forEach { m ->
            when (m.kind) {
                Kind.TIME -> append("<div class=time>${escape(m.displayText)}</div>")
                Kind.GAP -> append("<div class=gap>${escape(m.displayText)}</div>")
                else -> {
                    append("<article class=\"row ${if (m.side == Side.RIGHT) "right" else "left"}\"><div class=name>${escape(speaker(m, session))}</div>")
                    if (m.kind != Kind.MEDIA || m.media == null || (m.editedText != null && m.editedText != m.text)) append("<div class=bubble>${escape(m.displayText)}</div>")
                    if (m.kind == Kind.MEDIA) {
                        val source = mediaSource?.invoke(m) ?: if (evidence) m.media?.file else null
                        if (source != null) append("<img src=\"${escape(source)}\" alt=\"图片 / 表情的截图\" style=\"max-width:80%;max-height:420px;object-fit:contain;border-radius:12px\">")
                    }
                    if (!m.reviewed && m.flags.isNotEmpty()) append("<div class=flag>待校对 · ${escape(m.flags.joinToString(" / "))}</div>")
                    if (evidence && m.evidence.isNotEmpty()) {
                        append("<details><summary>查看来源画面</summary>")
                        m.evidence.forEach { e ->
                            val videoTime = frameTimes[e.frameId]
                            val label = videoTime?.let { "录屏 ${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}.${(it % 1000).toString().padStart(3, '0')}" } ?: frameNumbers[e.frameId]?.let { "原图 $it" } ?: "原图"
                            if (e.frameId.matches(Regex("[a-zA-Z0-9-]+"))) append("<a href=\"frames/${e.frameId}.jpg\">${escape(label)}</a> ")
                        }
                        append("</details>")
                    }
                    append("</article>")
                }
            }
        }
        append("<footer>由拾语整理。时间以聊天画面为准，图片为屏幕截图。</footer></main></body></html>")
    }
}
