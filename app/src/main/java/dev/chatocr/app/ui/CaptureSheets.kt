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
        listOf(
            "悬浮窗采集" to "新建纪念册 → 悬浮窗采集。授权后进入聊天页，点“开始识别”并框选消息。每滑半屏停留 1～2 秒，点“结束”保存。",
            "聊天录屏" to "新建纪念册 → 导入聊天录屏。选择平台和浏览方向，框选消息，点“整理成纪念册”。图片和表情随文字保存为截图。",
            "文章提取" to "切换到“文章”，导入录屏并框选正文，选择文字或文字与公式，点“提取文章”。",
            "校对与保存" to "聊天中点击消息；文章中打开“校对”并选择段落。对照原图修改后，打开导出菜单保存。ZIP 阅读包包含原图和阅读页面。"
        ).forEach { (title, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
        Text("采集时保持亮屏。录屏识别支持暂停和继续。", style = MaterialTheme.typography.bodyMedium, color = Ink)
        ActionRow("后台运行设置", batterySettings, icon = Icons.Outlined.BatteryStd)
    }
}


private fun date(time: Long) = SimpleDateFormat("MM.dd", Locale.ROOT).format(Date(time))
