package dev.chatocr.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import dev.chatocr.core.CaptureConfig
import kotlin.math.abs

class CaptureOverlay(private val context: Context, private val config: CaptureConfig,
    private val toggle: () -> Unit, private val pause: () -> Unit,
    private val finish: () -> Unit, private val cropChanged: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var main: View? = null
    private var menu: View? = null
    private var crop: View? = null
    private lateinit var label: TextView
    private val mode = UiPreferences(context).theme
    private val dark = mode == "dark" || (mode == "system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private val paper = if (dark) Color.rgb(29, 39, 32) else Color.rgb(246, 244, 238)
    private val ink = if (dark) Color.rgb(165, 212, 189) else Color.rgb(35, 107, 92)
    private val controlWidth = (56 * context.resources.configuration.fontScale.coerceIn(1f, 1.5f)).toInt()
    private val labelWidth = (136 * context.resources.configuration.fontScale.coerceIn(1f, 1.5f)).toInt()
    private val controlHeight = maxOf(56, (44 * context.resources.configuration.fontScale).toInt())
    private var params = layout(WindowManager.LayoutParams.WRAP_CONTENT, dp(controlHeight)).apply { x = dp(12); y = dp(6) }
    private fun dp(v: Int) = (v * density).toInt()
    private fun layout(w: Int, h: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0) }
    private fun background(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(18).toFloat(); setStroke(dp(1), if (dark) 0xFF52665A.toInt() else 0xFFD1DBCF.toInt())
    }
    private fun item(text: String, click: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink); isSingleLine = true
        setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { click() }
    }
    fun show() {
        if (main != null) return
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = background(paper); elevation = dp(5).toFloat() }
        label = item("开始识别") { if (config.cropConfirmed) toggle() else showCrop(startAfter = true) }.apply {
            isSingleLine = false; maxLines = 2; textSize = 14f; setPadding(dp(8), dp(3), dp(8), dp(3)); includeFontPadding = false
        }
        row.addView(label, LinearLayout.LayoutParams(dp(labelWidth), dp(controlHeight)))
        row.addView(item("结束", finish).apply { contentDescription = "结束并保存" }, LinearLayout.LayoutParams(dp(controlWidth), dp(controlHeight)))
        row.addView(item("更多") { showMenu() }.apply { contentDescription = "采集选项" }, LinearLayout.LayoutParams(dp(controlWidth), dp(controlHeight)))
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var originY = 0
        var dragged = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false
                    val location = IntArray(2); row.getLocationOnScreen(location); originY = location[1] - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragged = true
                    if (!dragged) return@setOnTouchListener true
                    val bounds = screenSize()
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(0, (bounds.x - row.width).coerceAtLeast(0))
                    // Keep the control out of the OCR region so it never covers a chat bubble.
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (bounds.y * config.cropTop - dp(controlHeight + 2) - originY).toInt().coerceAtLeast(0))
                    wm.updateViewLayout(row, params); true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) label.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                else -> false
            }
        }
        main = row; wm.addView(row, params)
    }
    fun update(recording: Boolean, count: Int, finishing: Boolean, processing: Boolean = false) {
        if (::label.isInitialized) label.text = if (finishing) "保存中…" else if (recording) "暂停识别\n$count 条 · ${if (processing) "识别中" else "可滚动"}" else if (count > 0) "继续识别\n$count 条 · 已暂停" else "开始识别"
        label.isEnabled = !finishing
    }
    private fun showMenu() {
        pause(); removeMenu()
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = background(paper); elevation = dp(5).toFloat() }
        col.addView(item("调整识别范围") { removeMenu(); showCrop() })
        col.addView(item("继续识别") { removeMenu(); toggle() })
        col.addView(item("结束并保存") { removeMenu(); finish() })
        col.addView(item("收起") { removeMenu() })
        menu = col
        wm.addView(col, layout(dp(labelWidth + controlWidth * 2), WindowManager.LayoutParams.WRAP_CONTENT).apply { x = params.x; y = params.y + dp(controlHeight + 6) })
    }
    fun showCrop(startAfter: Boolean = false) {
        if (crop != null) return
        pause(); main?.visibility = View.GONE
        val v = CropView(context, config.cropTop, config.cropBottom, screenSize().y, startAfter) { top, bottom ->
            config.cropTop = top; config.cropBottom = bottom; config.cropConfirmed = true
            crop?.let { runCatching { wm.removeView(it) } }; crop = null
            params.y = 0; main?.let { it.visibility = View.VISIBLE; wm.updateViewLayout(it, params) }
            cropChanged()
            if (startAfter) toggle()
        }
        crop = v
        wm.addView(v, layout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
    }
    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        @Suppress("DEPRECATION") return Point().also { wm.defaultDisplay.getRealSize(it) }
    }
    private fun removeMenu() { menu?.let { runCatching { wm.removeView(it) } }; menu = null }
    fun close() {
        removeMenu(); crop?.let { runCatching { wm.removeView(it) } }; crop = null
        main?.let { runCatching { wm.removeView(it) } }; main = null
    }
}

private class CropView(context: Context, var top: Float, var bottom: Float, private val screenHeight: Int, private val startAfter: Boolean, private val done: (Float, Float) -> Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var dragging = 0
    private val location = IntArray(2)
    private val buttonRect = RectF()
    init { contentDescription = "拖动上下边线框选消息，点击顶部确认"; setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val y1 = top * screenHeight - location[1]; val y2 = bottom * screenHeight - location[1]
        p.color = 0x9920352e.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), y1, p); canvas.drawRect(0f, y2, width.toFloat(), height.toFloat(), p)
        p.color = Color.rgb(172, 230, 194); p.strokeWidth = 3 * d
        canvas.drawLine(0f, y1, width.toFloat(), y1, p); canvas.drawLine(0f, y2, width.toFloat(), y2, p)
        canvas.drawCircle(width / 2f, y1, 10 * d, p); canvas.drawCircle(width / 2f, y2, 10 * d, p)
        p.color = Color.WHITE; p.textSize = 14 * d; p.textAlign = Paint.Align.CENTER
        canvas.drawText("拖动上下边线，框选聊天消息", width / 2f, (height - 36 * d), p)
        p.color = Color.rgb(35, 107, 92); buttonRect.set(width * .22f, 24 * d, width * .78f, 70 * d); canvas.drawRoundRect(buttonRect, 16 * d, 16 * d, p)
        p.color = Color.WHITE; canvas.drawText(if (startAfter) "确认范围并开始" else "保存范围", width / 2f, 53 * d, p)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y in 24 * d..70 * d && event.x in width * .22f..width * .78f) { performClick(); done(top, bottom); return true }
                dragging = if (abs(event.rawY - top * screenHeight) < abs(event.rawY - bottom * screenHeight)) 1 else 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 1) top = (event.rawY / screenHeight).coerceIn(maxOf(.09f, (location[1] + 50 * d) / screenHeight), bottom - .2f)
                else bottom = (event.rawY / screenHeight).coerceIn(top + .2f, .98f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> { dragging = 0; performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
