package dev.chatocr.fixture

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*

class FixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(ChatView(this)) }
}
private class ChatView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scroll = 0f; private var lastY = 0f
    override fun onDraw(c: Canvas) {
        val scale = width / 1080f; c.save(); c.scale(scale, scale)
        val h = height / scale; c.drawColor(Color.rgb(237, 237, 237))
        c.save(); c.clipRect(0f, 180f, 1080f, h - 180)
        val first = ((scroll / scale - 230) / 205).toInt().coerceAtLeast(0)
        for (i in first until minOf(first + 18, 20000)) {
            val y = 230f + i * 205 - scroll / scale
            if (y + 150 < 180 || y > h - 180) continue
            val right = i % 3 == 1; val left = if (right) 220f else 125f; val end = if (right) 950f else 860f
            p.color = if (right) Color.rgb(149, 236, 105) else Color.WHITE
            c.drawRoundRect(RectF(left, y, end, y + 135), 16f, 16f, p)
            p.color = Color.rgb(33, 45, 38); p.textSize = 36f
            val line = when (i % 5) { 0 -> "记得把今天的风景留下来"; 1 -> "好呀，明天一起去散步"; 2 -> "哈哈"; 3 -> "哈哈"; else -> "这段聊天值得好好保存" }
            c.drawText(line, left + 24, y + 53, p)
            p.textSize = 29f; c.drawText("测试消息 ${i.toString().padStart(5, '0')}", left + 24, y + 102, p)
        }
        c.restore(); p.color = Color.rgb(237, 237, 237); c.drawRect(0f, 0f, 1080f, 180f, p); c.drawRect(0f, h - 180, 1080f, h, p)
        p.color = Color.rgb(30, 42, 36); p.textSize = 43f; c.drawText("演示聊天 · 仅测试素材", 180f, 108f, p)
        p.textSize = 31f; c.drawText("连续采集素材 · 20000 条编号消息", 200f, h - 70, p)
        c.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastY = e.y
            MotionEvent.ACTION_MOVE -> { scroll = (scroll + lastY - e.y).coerceIn(0f, 4_090_000f * width / 1080); lastY = e.y; invalidate() }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
