package dev.chatocr.app

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic, reproducible layouts. These are not screenshots of the real messaging apps. */
@RunWith(AndroidJUnit4::class)
class AccuracyBenchmarkTest {
    @Test fun layoutMatrix() = runBlocking { runMatrix(listOf("今天一起去散步吧", "好", "好", "明天下午见，别忘记带伞。", "地址：人民路 18 号\n电话 13800138000", "12:30", "A | B = 3，收到！"), "accuracy") }
    @Test fun heldOutMatrix() = runBlocking { runMatrix(listOf("周六上午十点在公园门口见", "嗯嗯，我已经出门啦", "好的", "好的",
        "你说：明天去书店\n那我们下午两点见。", "会议号 1024 2048，密码 6357", "收到，谢谢你！", "价格 ¥28.50，数量 2",
        "今天也要加油！", "文件名 notes_2026-10-12.txt"), "heldout") }
    @Test fun freshMatrix() = runBlocking { runMatrix(listOf("桂花开了，周日一起吃汤圆。", "车票是 G326 次，07:46 发车", "照片已经保存到相册啦",
        "再等五分钟，我马上到。", "嗯", "嗯", "A-17 / B_09，温度 23.6℃", "第一行：记得带钥匙\n第二行：关好窗户"), "fresh") }
    private suspend fun runMatrix(texts: List<String>, prefix: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = InstrumentationRegistry.getArguments().getString("reportTag") ?: "current"
        val comparisons = mutableListOf<OcrReadingComparison>()
        val engine = PageOcr(context) { comparisons += it }
        val reports = mutableListOf<Map<String, Any>>()
        try {
            for (style in listOf("wechat", "qq", "dark")) for (group in listOf(false, true)) {
                val image = Bitmap.createBitmap(1080, 270 + texts.size * 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                val dark = style == "dark"
                canvas.drawColor(if (dark) Color.rgb(18, 18, 18) else Color.rgb(237, 237, 237))
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
                p.color = if (dark) Color.LTGRAY else Color.GRAY; p.textSize = 30f; p.textAlign = Paint.Align.CENTER
                canvas.drawText("昨天 18:30", 540f, 70f, p); p.textAlign = Paint.Align.LEFT
                val expected = texts.mapIndexed { i, text ->
                    val right = i == 3 || i == 5
                    val y = 170f + i * 240f
                    val font = if (style == "qq") 32f else 40f
                    p.textSize = font
                    val bw = (text.split('\n').maxOf { p.measureText(it) } + 56).coerceAtLeast(98f)
                    val left = if (right) 950f - bw else 125f
                    val end = left + bw
                    val bh = if ('\n' in text) 150f else 104f
                    p.color = if (right) { if (style == "qq") Color.rgb(0, 153, 245) else Color.rgb(149, 236, 105) }
                        else if (dark) Color.rgb(45, 45, 45) else Color.WHITE
                    canvas.drawRoundRect(RectF(left, y, end, y + bh), 22f, 22f, p)
                    // Tails and avatars deliberately exercise border artefacts.
                    val tail = Path().apply { if (right) { moveTo(end - 2, y + 22); lineTo(end + 10, y + 32); lineTo(end - 2, y + 42) }
                        else { moveTo(left + 2, y + 22); lineTo(left - 10, y + 32); lineTo(left + 2, y + 42) }; close() }
                    canvas.drawPath(tail, p)
                    p.color = if (dark && !right || style == "qq" && right) Color.WHITE else Color.rgb(25, 25, 25)
                    text.split('\n').forEachIndexed { line, value -> canvas.drawText(value, left + 28, y + 65 + line * 52, p) }
                    val name = if (group && !right) { if (i % 2 == 0) "小林" else "阿明" } else null
                    if (name != null) { p.textSize = 27f; p.color = if (dark) Color.LTGRAY else Color.GRAY; canvas.drawText(name, left, y - 14, p) }
                    p.color = Color.rgb(128, 153, 146)
                    canvas.drawRoundRect(RectF(if (right) 985f else 20f, y, if (right) 1060f else 95f, y + 75), 8f, 8f, p)
                    mapOf("text" to text, "side" to if (right) "RIGHT" else "LEFT", "sender" to (name ?: ""), "centerY" to (y + bh / 2) * 1000 / 1080)
                }
                val start = android.os.SystemClock.elapsedRealtime()
                comparisons.clear()
                val actual = engine.read(image, group)
                val ms = android.os.SystemClock.elapsedRealtime() - start
                File(context.getExternalFilesDir(null), "$prefix-$style-$group.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                assertTrue(actual.isNotEmpty())
                reports += mapOf("style" to style, "group" to group, "expected" to expected, "actual" to actual, "elapsedMs" to ms, "timing" to engine.lastTiming, "comparisons" to comparisons.toList())
            }
            File(context.getExternalFilesDir(null), "$prefix-$tag.json").writeText(ChatExport.gson.toJson(reports))
        } finally { engine.close() }
    }
}
