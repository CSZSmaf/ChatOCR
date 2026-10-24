@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.chatocr.app.ui

import dev.chatocr.app.UiMessage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chatocr.app.SessionStore
import dev.chatocr.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EditSheet(message: Message, session: Session, store: SessionStore, saving: Boolean, error: String,
    remaining: Int, dismiss: () -> Unit, save: (String, String, Boolean) -> Unit) {
    key(message.id) {
        var text by rememberSaveable { mutableStateOf(ChatPresentation.text(message)) }
        var sender by rememberSaveable { mutableStateOf(ChatExport.speaker(message, session)) }
        var frameIndex by remember { mutableIntStateOf(message.evidence.indexOfFirst { it.frameId == message.media?.frameId }.coerceAtLeast(0)) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var imageError by remember { mutableStateOf("") }
        var full by remember { mutableStateOf(false) }
        var showReadings by remember { mutableStateOf(false) }
        val evidence = message.evidence.getOrNull(frameIndex)
        LaunchedEffect(evidence?.frameId, full) {
            bitmap = null; imageError = ""
            if (evidence != null) runCatching {
                withContext(Dispatchers.IO) {
                    val source = BitmapFactory.decodeFile(store.frameFile(session.id, evidence.frameId).absolutePath) ?: error("来源画面无法读取")
                    if (full) source else {
                        val scale = source.width / 1000f; val box = evidence.box
                        val l = (box.left * scale - 18).toInt().coerceIn(0, source.width - 1)
                        val t = (box.top * scale - 18).toInt().coerceIn(0, source.height - 1)
                        val r = (box.right * scale + 18).toInt().coerceIn(l + 1, source.width)
                        val b = (box.bottom * scale + 18).toInt().coerceIn(t + 1, source.height)
                        Bitmap.createBitmap(source, l, t, r - l, b - t).also { if (it !== source) source.recycle() }
                    }
                }
            }.onSuccess { bitmap = it }.onFailure { imageError = UiMessage.failure(it, "请重新打开原图") }
        }
        ArchiveSheet("校对", dismiss, closeEnabled = !saving, footer = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button({ save(text, sender, remaining > 0) }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(16.dp)) {
                    Text(if (saving) "保存中…" else if (remaining > 0) "保存并下一条" else "保存")
                }
                if (remaining > 0) TextButton({ save(text, sender, false) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("保存并返回") }
            }
        }) {
                if (message.flags.isNotEmpty()) Surface(color = Peach, shape = RoundedCornerShape(10.dp)) {
                    Text(ChatPresentation.reviewHints(message).joinToString(" · "), color = Amber, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
                if (evidence != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原图 ${frameIndex + 1}/${message.evidence.size}", Modifier.weight(1f), color = Ink, fontSize = 14.sp)
                        TextButton({ frameIndex = (frameIndex + 1) % message.evidence.size }, enabled = message.evidence.size > 1) { Text("下一张", fontSize = 14.sp) }
                        TextButton({ full = !full }) { Text(if (full) "局部" else "全图", fontSize = 14.sp) }
                    }
                    session.frames.firstOrNull { it.id == evidence.frameId }?.videoTimeMs?.let {
                        Text("录屏 ${videoTime(it)}", color = Ink, style = MaterialTheme.typography.labelMedium)
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                        bitmap?.let { Image(it.asImageBitmap(), if (full) "完整来源画面" else "这条消息的原图放大", Modifier.fillMaxWidth().heightIn(min = 60.dp, max = if (full) 340.dp else 180.dp), contentScale = ContentScale.Fit) }
                            ?: Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { Text(imageError.ifBlank { "正在打开原图…" }, fontSize = 14.sp, color = Ink) }
                    }
                    }
                }
                TextField(text, { text = it }, label = { Text("校订文字") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
                    enabled = !saving, shape = RoundedCornerShape(14.dp), colors = archiveFieldColors())
                TextField(sender, { sender = it }, label = { Text("称呼 / 群成员") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    enabled = !saving, shape = RoundedCornerShape(14.dp), colors = archiveFieldColors())
                TextButton({ showReadings = !showReadings }) { Text(if (showReadings) "收起识别原文" else "识别原文与备选", fontSize = 14.sp) }
                if (showReadings) {
                    Text("识别原文：${ChatPresentation.text(message.copy(editedText = null))}", color = Ink, fontSize = 14.sp)
                    val variants = message.variants.filter { it != message.text }
                    if (variants.isNotEmpty()) Text("其他识别结果：\n${variants.joinToString("\n / \n")}", color = Ink, fontSize = 14.sp)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
    }
}
