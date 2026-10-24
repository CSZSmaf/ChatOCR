@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.chatocr.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chatocr.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SetupSheet(overlay: Boolean, grant: () -> Unit, dismiss: () -> Unit, start: (CaptureConfig) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }; var left by rememberSaveable { mutableStateOf("对方") }
    var right by rememberSaveable { mutableStateOf("我") }; var platform by rememberSaveable { mutableStateOf("微信") }
    var group by rememberSaveable { mutableStateOf(false) }; var older by rememberSaveable { mutableStateOf(true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    ArchiveSheet("新建采集", dismiss, footer = {
        Button({ if (!overlay) grant() else start(CaptureConfig(title.ifBlank { "$platform · ${date(System.currentTimeMillis())}" }, platform, left.ifBlank { "对方" }, right.ifBlank { "我" }, group, if (older) Direction.OLDER else Direction.NEWER)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(16.dp)) { Text(if (overlay) "开启悬浮窗" else "允许悬浮窗") }
    }) {
            TextField(title, { title = it.take(80) }, label = { Text("名称（选填）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = archiveFieldColors())
            ChoiceGroup(listOf("微信" to "微信", "QQ" to "QQ", "其他" to "其他"), platform, { platform = it })
            ChoiceGroup(listOf("single" to "单聊", "group" to "群聊"), if (group) "group" else "single", { group = it == "group" })
            TextButton({ advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "收起设置" else "更多设置")
                Spacer(Modifier.weight(1f))
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
            if (advanced) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(left, { left = it.take(40) }, label = { Text("左侧称呼") }, modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = archiveFieldColors())
                TextField(right, { right = it.take(40) }, label = { Text("右侧称呼") }, modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = archiveFieldColors())
              }
            Text("浏览方向", style = MaterialTheme.typography.labelLarge, color = Ink)
            ChoiceGroup(listOf("older" to "最近 → 更早", "newer" to "更早 → 最近"), if (older) "older" else "newer", { older = it == "older" })
            }
    }
}

@Composable
internal fun GuideSheet(dismiss: () -> Unit, batterySettings: () -> Unit) {
    ArchiveSheet("使用说明", dismiss) {
        Text("聊天录屏：新建纪念册 → 导入聊天录屏，框选消息后整理。可暂停并继续，图片和表情保存静态截图。", style = MaterialTheme.typography.bodyMedium)
        Text("文章：切换到文章，导入滚动录屏，框选正文后提取。旧卷读文章可在设置中导入 ZIP 阅读包。", style = MaterialTheme.typography.bodyMedium)
            listOf("1. 开始" to "新建采集，允许悬浮窗和整个屏幕共享。进入聊天页，点“开始识别”。",
                "2. 选范围" to "只框住消息，避开标题和输入框。拖动悬浮窗左侧可移位；“更多”可重调范围。",
                "3. 滚动" to "每滑半屏停留 1～2 秒，保留重叠。跳页先暂停，换聊天先结束。",
                "4. 保存" to "点“结束”保存；点击消息可校对。导出 ZIP 包含原图、HTML、JSON 和 TXT。",
                "后台运行" to "保持亮屏，允许通知和后台运行。锁屏或进程退出后，需重新授权继续。",
                "识别范围" to "图片、语音和视频只保留可见画面，不恢复原始附件。复杂背景、群昵称和引用需核对，未显示的时间不推断。"
            ).forEach { (title, body) -> Column { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.height(6.dp)); Text(body, color = Ink, fontSize = 14.sp, lineHeight = 22.sp) } }
            ActionRow("后台运行设置", batterySettings, icon = Icons.Outlined.BatteryStd)
            Text("卸载前请导出纪念册和文章。", color = Ink, style = MaterialTheme.typography.bodyMedium)
    }
}


private fun date(time: Long) = SimpleDateFormat("MM.dd", Locale.ROOT).format(Date(time))
