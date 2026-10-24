package dev.chatocr.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException

/** Only curated messages reach the UI; diagnostic details stay in the device log. */
object UiMessage {
    // Keys also cover messages saved by earlier app versions.
    private val messages = mapOf(
        "请先结束采集" to "请先结束采集",
        "请先暂停录屏识别" to "请先暂停录屏识别",
        "请先允许悬浮窗权限" to "请允许悬浮窗权限",
        "请选择至少半秒的片段" to "请选择至少半秒的片段",
        "请选择至少半秒的视频片段与有效正文区域。" to "请选择至少半秒的片段，并框选正文",
        "图片较多，请导出 ZIP 阅读包" to "请使用 ZIP 导出全部图片",
        "聊天选区无效" to "请重新框选聊天消息",
        "选区太小，请扩大正文区域。" to "请扩大正文选区",
        "原录屏不可用，请重新导入录屏" to "请重新导入原录屏",
        "原录屏已改变，请重新导入" to "请重新导入原录屏",
        "可用空间不足 150 MB，请释放空间后继续" to "请释放至少 150 MB 空间后继续",
        "空间不足，请先释放存储" to "请释放存储空间后继续",
        "请选择卷读导出的完整 ZIP 阅读包" to "请选择完整的 ZIP 阅读包",
        "暂不支持此阅读包版本" to "请更新应用后导入阅读包",
        "阅读包过大，请按文章分开导入" to "请按文章分开导入",
        "来源画面缺失，请重新采集后导出" to "请导出 TXT 或 JSON 保存文字",
        "图片缺失，请检查原图后重试" to "请导出 TXT 或 JSON 保存文字",
        "选区内没有清晰的公式。" to "请框选清晰的公式",
        "选区为空。" to "请框选识别区域",
        "模型解包失败，请检查剩余空间。" to "请释放存储空间后重试",
        "无法读取录屏时长，请选择手机相册中可正常播放的视频。" to "请选择相册中可播放的录屏",
        "没有识别到正文。请检查选区、文字大小与视频清晰度。" to "请框选清晰的正文后重新提取",
        "访问权限已失效，请重新选择文件或开启权限" to "请重新选择文件并授权访问",
        "文件无法打开，请重新选择" to "请重新选择文件",
        "已暂停，可继续识别" to "已暂停 · 可继续识别",
        "已暂停，草稿已保存，可继续提取。" to "草稿已保存 · 可继续提取",
        "识别已暂停，请重新打开后继续" to "请重新打开后继续识别",
        "保存失败，请检查剩余空间后重试" to "请检查存储空间后重新保存",
        "保存失败，请检查手机剩余空间。可从上次自动保存处继续。" to "请检查存储空间后继续提取",
        "导入的是未完成的文章草稿，续提取需重新导入原录屏。" to "继续提取请导入原录屏"
    )

    fun failure(error: Throwable, fallback: String): String {
        if (error is CancellationException) throw error
        Log.w("Shiyu", fallback, error)
        return display(error.message) ?: when (error) {
            is SecurityException -> "请重新选择文件并授权访问"
            is FileNotFoundException -> "请重新选择文件"
            else -> fallback
        }
    }

    fun stored(message: String?, fallback: String): String = display(message) ?: fallback

    private fun display(message: String?): String? {
        if (message == null) return null
        messages[message]?.let { return it }
        if (message in messages.values) return message
        val gaps = Regex("([0-9]+) 处画面衔接需要核对").matchEntire(message)
        return gaps?.let { "前后内容待校对 ${it.groupValues[1]} 处" }
    }
}
