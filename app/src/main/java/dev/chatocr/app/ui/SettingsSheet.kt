@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.chatocr.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsSheet(theme: String, textSize: Float, dismiss: () -> Unit, themeChanged: (String) -> Unit,
    sizeChanged: (Float) -> Unit, guide: () -> Unit, batterySettings: () -> Unit, importArticles: () -> Unit, about: () -> Unit) {
    ArchiveSheet("设置", dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("外观", style = MaterialTheme.typography.labelLarge, color = Ink)
            ChoiceGroup(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), theme, themeChanged)
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读字号", style = MaterialTheme.typography.labelLarge, color = Ink, modifier = Modifier.weight(1f))
                    Text(textSize.toInt().toString(), style = MaterialTheme.typography.labelLarge, color = Green)
                }
                Slider(textSize, sizeChanged, valueRange = 14f..22f, steps = 7)
                Text("阅读文字预览", fontSize = textSize.sp, lineHeight = (textSize * 1.65f).sp)
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
            Column {
                ActionRow("导入阅读包", importArticles, icon = Icons.Outlined.FolderOpen)
                ActionRow("使用说明", guide, icon = Icons.Outlined.HelpOutline)
                ActionRow("后台运行设置", batterySettings, icon = Icons.Outlined.BatteryStd)
                ActionRow("关于", about, icon = Icons.Outlined.Info)
            }
        }
    }
}
