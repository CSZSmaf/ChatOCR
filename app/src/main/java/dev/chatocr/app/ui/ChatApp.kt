package dev.chatocr.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.scrolltext.app.AppViewModel
import dev.scrolltext.app.processing.JobBus
import dev.scrolltext.app.ui.ScrollTextApp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.chatocr.app.*
import dev.chatocr.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatApp(store: SessionStore, revision: Int, notice: String, overlayAllowed: () -> Boolean,
    grantOverlay: () -> Unit, startCapture: (CaptureConfig, String?) -> Unit, endCapture: () -> Unit,
    export: (String, String) -> Unit, batterySettings: () -> Unit, openSessionId: String? = null, openRequest: Int = 0,
    openArticleId: String? = null, openLibrary: String? = null) {
    val context = LocalContext.current
    val preferences = remember { UiPreferences(context) }
    var theme by remember { mutableStateOf(preferences.theme) }
    var textSize by remember { mutableFloatStateOf(preferences.textSize) }
    var lastSession by remember { mutableStateOf(preferences.lastSession) }
    val capture by CaptureRuntime.state.collectAsState()
    val videoModel: AppViewModel = viewModel()
    val chatVideoModel: ChatVideoModel = viewModel()
    val chatVideo by ChatVideoRuntime.state.collectAsState()
    val videoJob by JobBus.state.collectAsState()
    val recognition by RecognitionGate.state.collectAsState()
    var library by rememberSaveable { mutableStateOf(preferences.library) }
    var lastArticle by remember { mutableStateOf(preferences.lastArticle) }
    fun switchLibrary(value: String) { library = value; preferences.library = value }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            switchLibrary("articles"); videoModel.importVideo(uri)
        }
    }
    val chatVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            switchLibrary("chats"); chatVideoModel.importVideo(uri)
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { switchLibrary("articles"); videoModel.importArchive(uri) }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var createMenu by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var summaries by remember { mutableStateOf(emptyList<SessionSummary>()) }
    var loading by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var wasCapturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(id: String) { switchLibrary("chats"); selected = id; lastSession = id; preferences.lastSession = id }
    LaunchedEffect(openSessionId, openArticleId, openLibrary, openRequest) {
        when {
            openSessionId != null -> open(openSessionId)
            !openArticleId.isNullOrBlank() -> { switchLibrary("articles"); videoModel.openArticle(openArticleId) }
            openLibrary == "articles" -> { switchLibrary("articles"); videoModel.screen = if (videoJob.running) "processing" else "home" }
        }
    }
    LaunchedEffect(revision) { videoModel.refresh() }
    LaunchedEffect(capture.active) {
        if (wasCapturing && !capture.active) capture.sessionId?.let(::open)
        wasCapturing = capture.active
    }
    LaunchedEffect(revision, capture.active, chatVideo.running, refresh, selected) {
        runCatching { withContext(Dispatchers.IO) { store.list() } }.onSuccess { summaries = it; localError = "" }
            .onFailure { if (it is CancellationException) throw it; localError = UiMessage.failure(it, "无法读取收藏，请重新打开后重试") }
        loading = false
    }
    BackHandler(library == "chats" && selected != null) { selected = null }
    ChatOcrTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.safeDrawingPadding().fillMaxSize()) {
                val home: @Composable () -> Unit = {
                    val articles = library == "articles"
                    val books = if (articles) videoModel.library.map { item ->
                        LibraryBook(item.id, item.title, "录屏 · ${item.lineCount} 行", item.reviewCount,
                            recent = item.id == lastArticle, status = when (item.status) {
                                "complete", "imported" -> ""
                                else -> if (videoJob.running && videoJob.articleId == item.id) "提取中" else "待继续"
                            })
                    } else summaries.map { LibraryBook(it.id, it.title, "${it.platform} · ${it.count} 条", it.pending, it.id == lastSession, status = if (it.incomplete) "待继续" else "") }
                    ArchiveHome(books, if (articles) false else loading, library, { selected = null; videoModel.screen = "home"; switchLibrary(it) },
                        if (articles) "" else localError.ifBlank { notice },
                        open = { id -> if (articles) {
                            lastArticle = id; preferences.lastArticle = id; videoModel.openArticle(id)
                        } else open(id) },
                        create = { if (articles) videoPicker.launch(arrayOf("video/*")) else createMenu = true },
                        createEnabled = recognition == null && !videoModel.busy && !chatVideoModel.busy,
                        settings = { settings = true }) {
                        if (capture.active) LibraryTaskCard(
                            if (capture.finishing) "正在保存聊天…" else "聊天采集 · ${capture.count} 条", "结束", !capture.finishing,
                            onAction = endCapture)
                        if (chatVideoModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (chatVideo.running) LibraryTaskCard("聊天录屏 · ${(chatVideo.progress * 100).toInt()}%", "查看", progress = chatVideo.progress) { open(chatVideo.sessionId) }
                        if (videoJob.running) LibraryTaskCard("文章提取 · ${(videoJob.progress * 100).toInt()}%", "查看", progress = videoJob.progress) {
                            switchLibrary("articles"); videoModel.screen = "processing"
                        }
                    }
                }
                val id = selected
                if (library == "articles") ScrollTextApp(videoModel, textSize,
                    { textSize = it; preferences.textSize = it }, home)
                else if (id != null) key(id) { ReaderScreen(id, store, capture, refresh + revision + if (chatVideo.running) 0 else 1, localError.ifBlank { notice }, preferences, textSize,
                    back = { selected = null }, export = { export(id, it) },
                    resume = { if (it.video != null) chatVideoModel.resume(it.id) else startCapture(it.config, it.id) }, changed = { refresh++ },
                    delete = { target -> scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(target); preferences.forget(target) } }
                            .onSuccess { selected = null; lastSession = preferences.lastSession; refresh++ }
                            .onFailure { localError = UiMessage.failure(it, "删除失败，请重试") }
                    } }, endCapture = { if (chatVideo.running && chatVideo.sessionId == id) chatVideoModel.pause() else endCapture() }) }
                else home()
            }
            if (createMenu) ArchiveSheet("新建纪念册", dismiss = { createMenu = false }) {
                ActionRow("悬浮窗采集", { createMenu = false; setup = true }, icon = Icons.Outlined.FilterCenterFocus)
                ActionRow("导入聊天录屏", { createMenu = false; chatVideoPicker.launch(arrayOf("video/*")) }, icon = Icons.Outlined.VideoLibrary)
            }
            ChatVideoSheet(chatVideoModel, opened = { open(it); refresh++ })
            if (chatVideoModel.error != null && chatVideoModel.video == null) AlertDialog(onDismissRequest = { chatVideoModel.error = null },
                title = { Text("聊天录屏") }, text = { Text(chatVideoModel.error.orEmpty()) },
                confirmButton = { TextButton({ chatVideoModel.error = null }) { Text("知道了") } })
            if (setup) SetupSheet(overlayAllowed(), grantOverlay, dismiss = { setup = false }, start = { setup = false; startCapture(it, null) })
            if (settings) SettingsSheet(theme, textSize, dismiss = { settings = false },
                themeChanged = { theme = it; preferences.theme = it },
                sizeChanged = { textSize = it; preferences.textSize = it },
                guide = { settings = false; guide = true }, batterySettings = batterySettings,
                about = { settings = false; about = true },
                importArticles = { settings = false; archivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
            if (guide) GuideSheet(dismiss = { guide = false }, batterySettings)
            if (about) AboutSheet(dismiss = { about = false })
        }
    }
}
