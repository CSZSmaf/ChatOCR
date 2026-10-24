package dev.chatocr.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutSheet(dismiss: () -> Unit) {
    var licenses by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val license = selected
    when {
        license != null -> {
            val text by produceState("正在打开…", license.second) {
                value = withContext(Dispatchers.IO) {
                    runCatching { context.assets.open("licenses/${license.second}").bufferedReader().use { it.readText() } }
                        .getOrDefault("请重新打开许可文件")
                }
            }
            ArchiveSheet(license.first, { selected = null }) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink) }
            }
        }
        licenses -> ArchiveSheet("软件许可", { licenses = false }) {
            Column {
                listOf("拾语 · AGPL-3.0" to "Shiyu-AGPL-3.0.txt",
                    "ONNX Runtime" to "ONNXRuntime-MIT.txt",
                    "RapidLaTeXOCR" to "RapidLaTeXOCR-MIT.txt",
                    "Pix2Text" to "Pix2Text-MIT.txt",
                    "Ultralytics" to "Ultralytics-AGPL-3.0.txt",
                    "KaTeX" to "KaTeX-MIT.txt").forEach { item ->
                    ActionRow(item.first, { selected = item })
                }
            }
        }
        else -> ArchiveSheet("关于", dismiss) {
            Text("拾语 $version", style = MaterialTheme.typography.titleLarge, color = Ink)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("© 2026 Yijin Wang", color = Ink)
                    Text("CHN_wangyijin@proton.me", color = Ink)
                }
            }
            ActionRow("软件许可", { licenses = true })
        }
    }
}
