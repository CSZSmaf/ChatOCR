@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package dev.chatocr.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chatocr.app.*
import dev.chatocr.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReaderData(val session: Session, val ordered: List<Message>)

@Composable
internal fun ReaderScreen(id: String, store: SessionStore, capture: CaptureStatus, revision: Int, notice: String,
    preferences: UiPreferences, textSize: Float, back: () -> Unit, export: (String) -> Unit,
    resume: (Session) -> Unit, changed: () -> Unit, delete: (String) -> Unit, endCapture: () -> Unit) {
    var data by remember(id) { mutableStateOf<ReaderData?>(null) }
    var error by remember(id) { mutableStateOf("") }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var searching by rememberSaveable(id) { mutableStateOf(false) }
    var onlyReview by rememberSaveable(id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf("") }
    var exportOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val video by ChatVideoRuntime.state.collectAsState()
    val recognition by RecognitionGate.state.collectAsState()
    val videoActive = video.running && video.sessionId == id
    val active = (capture.active && capture.sessionId == id) || videoActive
    LaunchedEffect(id, revision, capture.active, video.running) {
        runCatching { withContext(Dispatchers.IO) { store.load(id).let { ReaderData(it, it.ordered()) } } }
            .onSuccess { data = it; error = "" }.onFailure { if (it is CancellationException) throw it; error = UiMessage.failure(it, "请重新打开纪念册") }
    }
    val s = data?.session
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.Outlined.ArrowBack, "返回纪念册") }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(s?.config?.title ?: "纪念册", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s != null) Text("${s.config.platform} · ${if (videoActive) video.count else s.count()} 条", color = Ink, fontSize = 14.sp)
        }
        IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "关闭搜索" else "搜索聊天") }
        Box {
            IconButton({ menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "纪念册选项") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("导出纪念册") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, enabled = !active && s != null,
                    onClick = { menuOpen = false; exportOpen = true })
                DropdownMenuItem(text = { Text("删除纪念册") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, enabled = !active && s != null,
                    onClick = { menuOpen = false; confirmDelete = true })
            }
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
    if (notice.startsWith("正在导出") || notice.startsWith("导出") || notice.startsWith("删除：")) {
        if (notice.startsWith("正在导出")) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(notice, fontSize = 14.sp, lineHeight = 18.sp,
            color = if (notice.startsWith("导出：") || notice.startsWith("删除：")) MaterialTheme.colorScheme.error else Green,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    if (s == null) { if (error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val ordered = data!!.ordered
    val messages = remember(ordered, query, onlyReview) {
        ordered.filter { (!onlyReview || (!it.reviewed && it.flags.isNotEmpty())) &&
            (query.isBlank() || ChatPresentation.text(it).contains(query, true) || ChatExport.speaker(it, s).contains(query, true)) }
    }
    val stored = remember(id) { preferences.position(id) }
    val readingState = rememberLazyListState(stored.first.coerceAtMost(ordered.lastIndex.coerceAtLeast(0)), stored.second)
    val filteredState = rememberLazyListState()
    val filtered = onlyReview || query.isNotBlank()
    val listState = if (filtered) filteredState else readingState
    LaunchedEffect(id, readingState) {
        snapshotFlow { readingState.firstVisibleItemIndex to readingState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(350).collect { (index, offset) -> preferences.savePosition(id, index, offset) }
    }
    DisposableEffect(id, readingState) {
        onDispose { preferences.savePosition(id, readingState.firstVisibleItemIndex, readingState.firstVisibleItemScrollOffset) }
    }
    LaunchedEffect(query, onlyReview) { filteredState.scrollToItem(0) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (searching) TextField(query, { query = it }, placeholder = { Text("搜索文字或称呼") },
            singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(14.dp),
            colors = archiveFieldColors(), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.widthIn(max = 240.dp).selectableGroup()) {
                ReaderTab("阅读", !onlyReview, { onlyReview = false }, Modifier.weight(1f))
                ReaderTab("待校对 ${s.pending()}", onlyReview, { onlyReview = true }, Modifier.weight(1f))
            }
            if (active) TextButton(endCapture, enabled = if (videoActive) !video.pausing else !capture.finishing) { Text(if (videoActive) if (video.pausing) "保存中…" else "暂停识别" else "结束采集") }
            else if (s.video?.complete != true) TextButton({ resume(s) }, enabled = recognition == null) { Text(if (s.video != null) "继续识别" else "继续采集") }
        }
        if (videoActive) {
            LinearProgressIndicator(progress = { video.progress }, modifier = Modifier.fillMaxWidth())
            Text("已整理 ${video.count} 条 · ${videoTime(video.positionMs)}", color = Green, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (!active && video.sessionId == id && video.message.isNotBlank()) Text(video.message, color = MaterialTheme.colorScheme.error)
        if (!active && s.error != null) Text(UiMessage.stored(s.error, "请重新打开后继续识别"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        if (active && !videoActive) Text(capture.hint, color = Green, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (query.isNotBlank()) Text("${messages.size} 条结果", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    Spacer(Modifier.height(6.dp))
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (onlyReview && query.isBlank()) Icons.Outlined.TaskAlt else Icons.Outlined.Forum, null, Modifier.size(32.dp), tint = Green)
                Spacer(Modifier.height(12.dp))
                Text(when { query.isNotBlank() -> "换个关键词搜索"; onlyReview -> "待校对 0 条"; videoActive -> "正在整理…"; else -> "开始采集，整理聊天记录" }, fontFamily = FontFamily.Serif, fontSize = 18.sp)
            }
        }
        items(messages, key = { it.id }, contentType = { it.kind }) { message ->
            val edit = { if (!active) { editError = ""; editing = message } }
            when (message.kind) {
                Kind.TIME -> Text(ChatPresentation.text(message), color = Ink, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !active, onClick = edit).padding(vertical = 8.dp), textAlign = TextAlign.Center)
                Kind.GAP -> Surface(color = Peach, shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable(enabled = !active, onClick = edit)) {
                    Text(ChatPresentation.text(message), Modifier.padding(14.dp), fontSize = 14.sp, color = Amber)
                }
                else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.side == Side.RIGHT) Alignment.End else Alignment.Start) {
                    Text(ChatExport.speaker(message, s), color = Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(5.dp))
                    if (message.kind == Kind.MEDIA) message.media?.let { MediaMessage(store, id, it.file, !active, edit) }
                    if (message.kind != Kind.MEDIA || message.media == null || (message.editedText != null && message.editedText != message.text)) Surface(color = if (message.side == Side.RIGHT) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (message.side == Side.RIGHT) MaterialTheme.colorScheme.onPrimaryContainer else Ink,
                        shape = RoundedCornerShape(if (message.side == Side.RIGHT) 18.dp else 5.dp, if (message.side == Side.RIGHT) 5.dp else 18.dp, 18.dp, 18.dp),
                        modifier = Modifier.widthIn(max = 340.dp).clickable(enabled = !active, onClick = edit)) {
                        Text(ChatPresentation.text(message), Modifier.padding(14.dp, 11.dp), fontSize = textSize.sp, lineHeight = (textSize * 1.6f).sp)
                    }
                    if (!onlyReview && !message.reviewed && message.flags.isNotEmpty()) Text("待校对", color = Amber,
                        fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if (exportOpen) ArchiveSheet("导出", dismiss = { exportOpen = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("zip" to "ZIP · 含原图", "html" to "HTML · 阅读版", "json" to "JSON · 结构化", "txt" to "TXT · 纯文字").forEach { (format, label) ->
                ActionRow(label, { exportOpen = false; export(format) }, recommended = format == "zip")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这本纪念册？") },
        text = { Text("将删除这本纪念册的文字和图片。") },
        confirmButton = { TextButton({ confirmDelete = false; delete(id) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
    editing?.let { message ->
        val remaining = ordered.count { !it.reviewed && it.flags.isNotEmpty() && it.id != message.id }
        EditSheet(message, s, store, saving, editError, remaining,
            dismiss = { if (!saving) editing = null }, save = { text, sender, next ->
                if (!saving) scope.launch {
                    saving = true; editError = ""
                    runCatching {
                        withContext(Dispatchers.IO) {
                            store.requireIdle(id)
                            val fresh = store.load(id); val m = fresh.messages.first { it.id == message.id }
                            m.editedText = text; m.sender = sender.takeIf { it.isNotBlank() }; m.reviewed = true
                            fresh.updatedAt = System.currentTimeMillis(); store.save(fresh)
                            ReaderData(fresh, fresh.ordered())
                        }
                    }.onSuccess { fresh ->
                        data = fresh
                        val index = fresh.ordered.indexOfFirst { it.id == message.id }
                        editing = if (next) (fresh.ordered.drop(index + 1) + fresh.ordered.take(index))
                            .firstOrNull { !it.reviewed && it.flags.isNotEmpty() } else null
                        changed()
                    }.onFailure { editError = UiMessage.failure(it, "请检查存储空间后重新保存") }
                    saving = false
                }
            })
    }
}

@Composable
private fun ReaderTab(label: String, selected: Boolean, choose: () -> Unit, modifier: Modifier) {
    Column(modifier.selectable(selected, role = Role.Tab, onClick = choose), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Green else Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
        }
        Box(Modifier.width(28.dp).height(2.dp).background(if (selected) Green else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}
