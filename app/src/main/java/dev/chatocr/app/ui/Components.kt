@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.chatocr.app.ui

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun ArchiveSheet(title: String, dismiss: () -> Unit, closeEnabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (closeEnabled) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Sheet) {
        ModalSystemBars()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(dismiss, enabled = closeEnabled) { Icon(Icons.Outlined.Close, "关闭$title", tint = Muted) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = if (footer == null) 24.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            if (footer != null) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp)) { footer() }
        }
    }
}

@Composable
private fun ModalSystemBars() {
    val view = LocalView.current
    val lightBars = Sheet.luminance() > .5f
    LaunchedEffect(view, lightBars) {
        // M3 1.3 owns a separate window and exposes no system-bar theme option.
        withFrameNanos { }
        val window = generateSequence(view as View?) { it.parent as? View }
            .mapNotNull { (it as? DialogWindowProvider)?.window }.firstOrNull()
        if (window != null) WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}

@Composable
internal fun ChoiceGroup(options: List<Pair<String, String>>, selected: String, changed: (String) -> Unit,
    modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        val widestLabel = options.maxOf { measurer.measure(AnnotatedString(it.second), labelStyle, softWrap = false).size.width }
        val itemWidth = with(LocalDensity.current) { widestLabel.toDp() } + 20.dp
        val stack = maxWidth < itemWidth * options.size + 8.dp + 4.dp * (options.size - 1)
        Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
            if (stack) Column(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (key, label) -> ChoiceItem(label, key == selected, { changed(key) }, Modifier.fillMaxWidth()) }
            } else Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (key, label) -> ChoiceItem(label, key == selected, { changed(key) }, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceItem(label: String, selected: Boolean, choose: () -> Unit, modifier: Modifier) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Ink,
        shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Box(Modifier.selectable(selected, role = Role.RadioButton, onClick = choose).heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun ActionRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, recommended: Boolean = false) {
    Surface(onClick = onClick, color = if (recommended) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (recommended) MaterialTheme.colorScheme.onPrimaryContainer else Ink,
        shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(21.dp), tint = Muted)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (recommended) Text("推荐", style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
        }
    }
}

@Composable
internal fun archiveFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Panel, unfocusedContainerColor = Panel, disabledContainerColor = Panel,
    focusedIndicatorColor = Green, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
    focusedLabelColor = Green, unfocusedLabelColor = Ink, cursorColor = Green)
