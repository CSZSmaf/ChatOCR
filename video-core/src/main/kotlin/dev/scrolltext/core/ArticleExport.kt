package dev.scrolltext.core

object ArticleExport {
    fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** Join wrapped prose geometrically; explicit gaps, equations and larger paragraph spaces remain. */
    fun paragraphs(lines: List<ArticleLine>): List<String> {
        val sorted = lines.sortedWith(compareBy<ArticleLine> { it.box.top }.thenBy { it.box.left })
        val result = mutableListOf<String>()
        var paragraph = StringBuilder()
        var previous: ArticleLine? = null
        fun flush() { if (paragraph.isNotEmpty()) { result += paragraph.toString(); paragraph = StringBuilder() } }
        for (line in sorted) {
            if (line.kind != BlockKind.TEXT) {
                flush()
                result += if (line.kind == BlockKind.FORMULA) "\$\$\n${line.text}\n\$\$" else "> [待补充 · ${timeLabel(line.timeMs)}] ${line.text}"
                previous = null; continue
            }
            val p = previous
            val gap = if (p != null) line.box.top - p.box.bottom else 0.0
            val newParagraph = p == null || line.edited || p.edited || gap > maxOf(line.box.height, p.box.height) * .95 ||
                line.box.left - p.box.left > line.box.height * 1.3 ||
                Regex("^(第.{1,8}[章节]|[一二三四五六七八九十]+[、.]|[0-9]+[.、]\\s|[•●◆])").containsMatchIn(line.text) ||
                line.box.height > p.box.height * 1.35 || p.box.height > line.box.height * 1.35
            if (newParagraph) flush()
            if (paragraph.isNotEmpty() && paragraph.last().isAsciiWord() && line.text.firstOrNull()?.isAsciiWord() == true) paragraph.append(' ')
            paragraph.append(line.text.trim()); previous = line
        }
        flush(); return result
    }

    private fun Char.isAsciiWord() = code < 128 && (isLetterOrDigit() || this in ",.;:?!")
    fun timeLabel(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
    fun hasMath(line: ArticleLine) = line.kind == BlockKind.FORMULA || line.formulaParts.isNotEmpty() || Regex("\\$[^$]+\\$").containsMatchIn(line.text)
    fun markdown(title: String, lines: List<ArticleLine>) = "# $title\n\n" +
        (if (lines.any { it.needsReview && hasMath(it) }) "> 公式为未经校对的 LaTeX 识别候选，请对照原图核对符号。\n\n" else "") +
        paragraphs(lines).joinToString("\n\n") + "\n"

    fun html(title: String, lines: List<ArticleLine>, fontSize: Int = 18, dark: Boolean = false, showHeader: Boolean = true, sourceUrl: (ArticleLine) -> String? = { null }): String {
        val body = buildString {
            val group = mutableListOf<ArticleLine>()
            fun flush() {
                for (p in paragraphs(group)) {
                    if (p.startsWith("\$\$\n")) append("<div class=equation>" + escapeHtml(p) + "</div>")
                    else append("<p>" + escapeHtml(p).replace("\n", "<br>") + "</p>")
                }
                group.clear()
            }
            for (line in lines) {
                val source = if (line.needsReview && hasMath(line)) sourceUrl(line) else null
                if (source == null) group += line else {
                    flush()
                    append("<figure><img src=\"${escapeHtml(source)}\" alt=\"含公式内容的原始画面\"><figcaption>公式待校对 · 显示原图</figcaption>")
                    append("<details class=candidate><summary>查看公式识别结果</summary><pre>${escapeHtml(line.text)}</pre></details></figure>")
                }
            }
            flush()
        }
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title>
<link rel="stylesheet" href="katex/katex.min.css"><style>body{margin:0;background:${if(dark) "#171c19" else "#f8f7f3"};color:${if(dark) "#e1e7db" else "#252d28"};font:${fontSize.coerceIn(14,26)}px/1.95 system-ui,sans-serif}main{max-width:700px;margin:auto;padding:30px 24px 60px}h1{font-size:1.5em;line-height:1.5;letter-spacing:-.025em;font-weight:650;margin:14px 0 26px;padding-bottom:24px;border-bottom:1px solid ${if(dark) "#465349" else "#e1e7dc"}}p{overflow-wrap:anywhere;margin:1.1em 0}.equation{overflow-x:auto;padding:14px 12px;background:${if(dark) "#26352c" else "#f3f5ec"};border-radius:12px;margin:24px 0}.katex{font-size:1.1em}small{color:${if(dark) "#e1e7db" else "#252d28"};font-size:14px}img{max-width:100%}@media print{body{background:white;color:black}main{max-width:none}}</style></head><body><main>${if (showHeader) "<h1>${escapeHtml(title)}</h1>" else ""}$body</main>
<style>figure{margin:24px 0;padding:14px;background:${if(dark) "#26352c" else "#f3f5ec"};border-radius:12px}figure img{display:block;max-height:240px;object-fit:contain;margin:auto;border-radius:4px}figcaption,summary{font-size:14px;color:${if(dark) "#e1e7db" else "#252d28"};margin-top:12px}pre{white-space:pre-wrap;font-size:14px;overflow-wrap:anywhere}summary{cursor:pointer}</style><script src="katex/katex.min.js"></script><script src="katex/contrib/auto-render.min.js"></script><script>if(window.renderMathInElement)renderMathInElement(document.querySelector('main'),{delimiters:[{left:'$$$$',right:'$$$$',display:true},{left:'$',right:'$',display:false}],throwOnError:false,trust:false,strict:'ignore',ignoredClasses:['candidate']});</script></body></html>""".replace("'$$$$'", "'\$\$'")
    }
}
