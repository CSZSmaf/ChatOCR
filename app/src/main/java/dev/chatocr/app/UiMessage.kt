package dev.chatocr.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException

/** Only curated messages reach the UI; diagnostic details stay in the device log. */
object UiMessage {
    private val known = setOf(
        "请先结束采集", "请先暂停录屏识别", "请先允许悬浮窗权限",
        "请选择至少半秒的片段", "请选择至少半秒的视频片段与有效正文区域。",
        "图片较多，请导出 ZIP 阅读包", "聊天选区无效", "选区太小，请扩大正文区域。",
        "原录屏不可用，请重新导入录屏", "原录屏已改变，请重新导入",
        "可用空间不足 150 MB，请释放空间后继续", "空间不足，请先释放存储",
        "请选择卷读导出的完整 ZIP 阅读包", "暂不支持此阅读包版本",
        "阅读包过大，请按文章分开导入", "来源画面缺失，请重新采集后导出", "图片缺失，请检查原图后重试",
        "选区内没有清晰的公式。", "选区为空。", "模型解包失败，请检查剩余空间。",
        "无法读取录屏时长，请选择手机相册中可正常播放的视频。",
        "没有识别到正文。请检查选区、文字大小与视频清晰度。",
        "访问权限已失效，请重新选择文件或开启权限", "文件无法打开，请重新选择",
        "已暂停，可继续识别", "已暂停，草稿已保存，可继续提取。",
        "识别已暂停，请重新打开后继续", "保存失败，请检查剩余空间后重试",
        "导入的是未完成的文章草稿，续提取需重新导入原录屏。"
    )

    fun failure(error: Throwable, fallback: String): String {
        if (error is CancellationException) throw error
        Log.w("Shiyu", fallback, error)
        return known.firstOrNull { it == error.message } ?: when (error) {
            is SecurityException -> "访问权限已失效，请重新选择文件或开启权限"
            is FileNotFoundException -> "文件无法打开，请重新选择"
            else -> fallback
        }
    }

    fun stored(message: String?, fallback: String): String =
        message?.takeIf { it in known || it.matches(Regex("[0-9]+ 处画面衔接需要核对")) } ?: fallback
}
