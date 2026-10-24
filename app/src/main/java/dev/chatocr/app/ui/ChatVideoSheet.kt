@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.chatocr.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chatocr.app.ChatVideoModel
import dev.chatocr.core.*
import dev.scrolltext.app.ui.CropEditor
import dev.scrolltext.core.Crop

internal fun videoTime(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
internal fun ChatVideoSheet(model: ChatVideoModel, opened: (String) -> Unit) {
    val video = model.video ?: return
    var title by rememberSaveable(video.uri.toString()) { mutableStateOf(video.title.take(80)) }
    var platform by rememberSaveable { mutableStateOf("微信") }
    var group by rememberSaveable { mutableStateOf(false) }
    var older by rememberSaveable { mutableStateOf(true) }
    var leftName by rememberSaveable { mutableStateOf("对方") }
    var rightName by rememberSaveable { mutableStateOf("我") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var top by rememberSaveable { mutableFloatStateOf(.14f) }
    var bottom by rememberSaveable { mutableFloatStateOf(.88f) }
    var left by rememberSaveable { mutableFloatStateOf(0f) }
    var right by rememberSaveable { mutableFloatStateOf(1f) }
    var start by rememberSaveable { mutableFloatStateOf(0f) }
    var end by rememberSaveable { mutableFloatStateOf(video.duration.toFloat()) }
    var position by remember { mutableFloatStateOf(model.previewTime.toFloat()) }
    fun begin() = model.start(CaptureConfig(title.ifBlank { "聊天纪念册" }, platform, leftName.ifBlank { "对方" },
        rightName.ifBlank { "我" }, group, if (older) Direction.OLDER else Direction.NEWER, cropConfirmed = true),
        ChatVideoSource(video.uri.toString(), video.title, video.duration, start.toLong(), end.toLong(), left, top, right, bottom), opened)
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }
    ArchiveSheet("导入聊天录屏", dismiss = { if (!model.busy) model.dismiss() }, footer = {
        Button({ if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) else begin() },
            enabled = !model.busy && end - start >= 500, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (model.busy) "正在准备…" else "整理成纪念册")
        }
    }) {
        TextField(title, { title = it.take(80) }, label = { Text("纪念册名称") }, singleLine = true,
            colors = archiveFieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        ChoiceGroup(listOf("微信" to "微信", "QQ" to "QQ", "其他" to "其他"), platform, { platform = it })
        ChoiceGroup(listOf("single" to "单聊", "group" to "群聊"), if (group) "group" else "single", { group = it == "group" })
        Text("框选消息区域", style = MaterialTheme.typography.labelLarge)
        CropEditor(video.preview, Crop(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble()), { left = it.left.toFloat(); top = it.top.toFloat(); right = it.right.toFloat(); bottom = it.bottom.toFloat() }, height = 300.dp)
        Text("预览 ${videoTime(position.toLong())} / ${videoTime(video.duration)}", color = Ink)
        Slider(position, { position = it }, valueRange = 0f..(video.duration - 1).toFloat(), onValueChangeFinished = { model.seek(position.toLong()) })
        Text("录屏中的浏览方向", style = MaterialTheme.typography.labelLarge)
        ChoiceGroup(listOf("older" to "最近 → 更早", "newer" to "更早 → 最近"), if (older) "older" else "newer", { older = it == "older" })
        TextButton({ advanced = !advanced }) { Text(if (advanced) "收起设置" else "片段与称呼") }
        if (advanced) {
            Text("片段 ${videoTime(start.toLong())} — ${videoTime(end.toLong())}")
            RangeSlider(start..end, { start = it.start; end = it.endInclusive }, valueRange = 0f..video.duration.toFloat(),
                onValueChangeFinished = { position = start; model.seek(start.toLong()) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(leftName, { leftName = it.take(40) }, label = { Text("左侧称呼") }, singleLine = true, modifier = Modifier.weight(1f), colors = archiveFieldColors())
                TextField(rightName, { rightName = it.take(40) }, label = { Text("右侧称呼") }, singleLine = true, modifier = Modifier.weight(1f), colors = archiveFieldColors())
            }
        }
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
