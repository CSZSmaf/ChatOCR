@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.scrolltext.app.ui

import dev.chatocr.app.UiMessage

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.webkit.*
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.scrolltext.app.AppViewModel
import dev.chatocr.app.RecognitionGate
import dev.chatocr.app.RecognitionKind
import dev.chatocr.app.ui.ArchiveSheet
import dev.chatocr.app.ui.ActionRow
import dev.chatocr.app.ui.ChoiceGroup
import dev.chatocr.app.ui.archiveFieldColors
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.scrolltext.app.data.*
import dev.scrolltext.app.ocr.FormulaEngine
import dev.scrolltext.app.processing.JobBus
import dev.scrolltext.core.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun ScrollTextApp(model: AppViewModel, textSize: Float, onTextSize: (Float) -> Unit,
    home: @Composable () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when (model.screen) {
                "import" -> ImportScreen(model) { config ->
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    model.start(config)
                }
                "processing" -> ProcessingScreen(model)
                "reader" -> ReaderScreen(model, textSize, onTextSize)
                else -> home()
            }
        }
        if (model.busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)).clickable {}, Alignment.Center) {
            Surface(shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Spacer(Modifier.width(16.dp)); Text("正在读取…")
            } }
        }
    }
    model.error?.let { message -> AlertDialog(onDismissRequest = { model.error = null }, title = { Text("操作提示") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error = null }) { Text("知道了") } }) }
    BackHandler(model.screen != "home") { model.screen = "home"; model.refresh() }
}

@Composable private fun TopBar(title: String, onBack: () -> Unit, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        action()
    }
}

@Composable private fun ImportScreen(model: AppViewModel, start: (JobConfig) -> Unit) {
    val video = model.video ?: return
    var crop by rememberSaveable(video.uri.toString(), stateSaver = CropSaver) { mutableStateOf(Crop()) }
    var title by rememberSaveable(video.uri.toString()) { mutableStateOf(video.title) }
    var formulas by rememberSaveable { mutableStateOf(false) }
    var expandedCrop by remember { mutableStateOf(false) }
    var trim by remember { mutableStateOf(false) }
    var previewTime by rememberSaveable(video.uri.toString()) { mutableFloatStateOf(0f) }
    var range by rememberSaveable(video.uri.toString(), stateSaver = androidx.compose.runtime.saveable.listSaver<ClosedFloatingPointRange<Float>,Float>(
        save = { listOf(it.start, it.endInclusive) }, restore = { it[0]..it[1] })) { mutableStateOf(0f..video.duration.toFloat()) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("导入录屏", { model.screen = "home" })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选取正文区域", style = MaterialTheme.typography.titleLarge)
                    Text("避开标题栏和底栏", color = Ink, fontSize = 14.sp)
                }
                IconButton(onClick = { expandedCrop = true }) { Icon(Icons.Rounded.OpenInFull, "放大选区", tint = Green) }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Colors.surfaceContainerLow) {
                Column(Modifier.padding(10.dp)) {
                    CropEditor(video.preview, crop, { crop = it }, height = 266.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { crop = Crop() }) { Text("重置", fontSize = 14.sp) }
                        TextButton(onClick = { crop = Crop(0.0, 0.0, 1.0, 1.0) }) { Text("完整画面", fontSize = 14.sp) }
                        TextButton(onClick = { expandedCrop = true }) { Text("调整", fontSize = 14.sp) }
                    }
                }
            }
            Column {
                Row(Modifier.fillMaxWidth()) { Text("预览", Modifier.weight(1f), fontSize = 14.sp, color = Ink); Text(ArticleExport.timeLabel(previewTime.toLong()) + " / " + ArticleExport.timeLabel(video.duration), color = Green, fontSize = 14.sp) }
                Slider(value = previewTime, onValueChange = { previewTime = it }, valueRange = 0f..video.duration.toFloat(), onValueChangeFinished = { model.seekPreview(previewTime.toLong()) })
            }
            Text("识别内容", style = MaterialTheme.typography.titleMedium)
            ChoiceGroup(listOf("text" to "仅文字", "formulas" to "文字与公式"), if (formulas) "formulas" else "text", { formulas = it == "formulas" })
            if (formulas) Text("公式识别较慢，完成后请校对。", style = MaterialTheme.typography.bodySmall, color = Ink)
            TextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("文章标题") }, singleLine = true, colors = archiveFieldColors(), shape = RoundedCornerShape(14.dp))
            TextButton(onClick = { trim = !trim }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Rounded.ContentCut, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(if (trim) "收起片段选择" else "只提取部分片段")
            }
            if (trim) Column {
                Text("${ArticleExport.timeLabel(range.start.toLong())} – ${ArticleExport.timeLabel(range.endInclusive.toLong())}", fontSize = 14.sp, color = Ink)
                RangeSlider(value = range, onValueChange = { if (it.endInclusive - it.start >= 500) range = it }, valueRange = 0f..video.duration.toFloat())
            }
            Spacer(Modifier.height(6.dp))
        }
        Surface(color = Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Button(onClick = { start(JobConfig(video.uri.toString(), title, crop, range.start.toLong(), range.endInclusive.toLong(), formulas)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp), enabled = !model.busy) {
                    Icon(Icons.Rounded.TextSnippet, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("提取文章", fontSize = 16.sp)
                    Spacer(Modifier.weight(1f)); Text(ArticleExport.timeLabel((range.endInclusive - range.start).toLong()), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                }
            }
        }
    }
    if (expandedCrop) Dialog(onDismissRequest = { expandedCrop = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Paper) {
            Column {
                TopBar("精细调整选区", { expandedCrop = false })
                BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    CropEditor(video.preview, crop, { crop = it }, height = maxHeight)
                }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text("左右边界", fontSize = 14.sp, color = Ink)
                    RangeSlider(crop.left.toFloat()..crop.right.toFloat(), { if (it.endInclusive - it.start >= .1) crop = crop.copy(left = it.start.toDouble(), right = it.endInclusive.toDouble()) })
                    Text("上下边界", fontSize = 14.sp, color = Ink)
                    RangeSlider(crop.top.toFloat()..crop.bottom.toFloat(), { if (it.endInclusive - it.start >= .1) crop = crop.copy(top = it.start.toDouble(), bottom = it.endInclusive.toDouble()) })
                    Button(onClick = { expandedCrop = false }, Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 52.dp)) { Text("使用此选区") }
                }
            }
        }
    }
}

@Composable private fun ProcessingScreen(model: AppViewModel) {
    val state by JobBus.state.collectAsStateWithLifecycle()
    var elapsed by remember { mutableLongStateOf(state.elapsedMs) }
    LaunchedEffect(state.elapsedMs, state.running) {
        elapsed = state.elapsedMs
        while (state.running) { delay(1000); elapsed += 1000 }
    }
    TopBar("正在提取", { model.screen = "home" })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(160.dp), Alignment.Center) {
            CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp, trackColor = Colors.outlineVariant)
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${(state.progress * 100).toInt()}%", fontSize = 36.sp, fontWeight = FontWeight.Light); Text("处理进度", color = Ink, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(32.dp)); Text("提取文章", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp)); Text(state.message.ifBlank { "启动本地识别…" }, color = Green, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        Surface(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("${state.frames}", "识别画面"); Stat("${state.lines}", "文章行数"); Stat(ArticleExport.timeLabel(elapsed), "已用时间")
        } }
        Spacer(Modifier.height(22.dp)); OutlinedButton(onClick = model::cancel) { Text("暂停并保存") }
    }
}
@Composable private fun Stat(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold); Text(label, fontSize = 14.sp, color = Ink) } }

@Composable private fun ReaderScreen(model: AppViewModel, textSize: Float, onTextSize: (Float) -> Unit) {
    val article = model.article ?: return
    val context = LocalContext.current
    val fontSize = textSize.toInt()
    val dark = Paper.luminance() < .5f
    var appearance by remember { mutableStateOf(false) }
    var tab by rememberSaveable(article.id) { mutableIntStateOf(0) }
    var onlyReview by rememberSaveable(article.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArticleLine?>(null) }
    var export by remember { mutableStateOf(false) }
    val review = article.lines.count { it.needsReview }
    TopBar(article.title, { model.screen = "home"; model.refresh() }) {
        IconButton(onClick = { appearance = true }) { Icon(Icons.Rounded.TextFields, "阅读设置") }
        IconButton(onClick = { export = true }) { Icon(Icons.Rounded.IosShare, "导出文章") }
    }
    if (article.status != "complete" && article.status != "imported") {
        Surface(color = Colors.surfaceContainerLow) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(UiMessage.stored(article.message, "已保留草稿，可继续提取"), fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = model::resume) { Text(if (article.checkpoint != null) "继续提取" else "重新提取") }
        } }
    }
    if (article.status == "imported" && article.message.isNotBlank()) Text(UiMessage.stored(article.message, "阅读包已导入"),
        color = Orange, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("阅读") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (review > 0) "待校对 $review" else "校对") })
    }
    if (tab == 0) Column(Modifier.fillMaxSize()) {
        if (review > 0) Surface(onClick = { tab = 1; onlyReview = true }, color = Colors.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FactCheck, null, Modifier.size(18.dp), tint = Orange); Spacer(Modifier.width(8.dp))
                Text("$review 处内容待核对", fontSize = 14.sp, color = Orange, modifier = Modifier.weight(1f))
                Text("去校对", fontSize = 14.sp, color = Orange); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = Orange)
            }
        }
        ReaderWeb(article, model.store, Modifier.weight(1f).fillMaxWidth(), fontSize, dark)
    }
    else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyReview, { onlyReview = it }); Text("只看待核对内容", fontSize = 14.sp); Spacer(Modifier.weight(1f)); Spacer(Modifier.width(0.dp))
        }
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = if (onlyReview) article.lines.filter { it.needsReview } else article.lines
            if (rows.isEmpty()) item { Text("暂无待校对内容", color = Ink, modifier = Modifier.padding(20.dp)) }
            items(rows, key = { it.id }) { line ->
                Surface(onClick = { editing = line }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (line.needsReview) Colors.tertiaryContainer else Colors.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(when (line.kind) { BlockKind.FORMULA -> "公式"; BlockKind.GAP -> "缺少内容"; else -> if (line.edited) "已编辑" else "正文" }, fontSize = 14.sp, color = if (line.needsReview) Orange else Green)
                            Spacer(Modifier.weight(1f)); Text(ArticleExport.timeLabel(line.timeMs), fontSize = 14.sp, color = Ink)
                        }
                        Text(line.text, fontSize = 15.sp, lineHeight = 24.sp)
                        if (line.source.isNotEmpty()) SourceImage(model.store.source(article.id, line.source), Modifier.fillMaxWidth().heightIn(max = 120.dp))
                        if (line.needsReview) Text(if (line.clipped) "边缘内容可能不完整" else "请核对原图", fontSize = 14.sp, color = Orange)
                    }
                }
            }
        }
    }
    editing?.let { line -> EditDialog(model, article, line, onDismiss = { editing = null }) }
    if (export) ExportDialog(model, article) { export = false }
    if (appearance) ArchiveSheet("阅读设置", { appearance = false }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("阅读字号", Modifier.weight(1f)); Text(fontSize.toString(), color = Ink)
        }
        Slider(textSize, onTextSize, valueRange = 14f..22f, steps = 7)
    }
}

@Composable private fun SourceImage(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    bitmap?.let { Image(it.asImageBitmap(), "该段原始画面", modifier) }
}

@Composable private fun ReaderWeb(article: Article, store: ArticleStore, modifier: Modifier, fontSize: Int, dark: Boolean) {
    val html = remember(article, fontSize, dark) { ArticleExport.html(article.title, article.lines, fontSize, dark, showHeader = false) { line ->
        val name = store.readingSource(line)
        if (name.isNotEmpty()) "https://scrolltext.local/source/$name" else null
    } }
    val pageColor = Paper.toArgb()
    key(article.id) { AndroidView(modifier = modifier.clipToBounds(), factory = { context ->
        WebView(context).apply {
            setBackgroundColor(pageColor)
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.scheme == "https" && url.host == "scrolltext.local" && url.path?.startsWith("/source/") == true) {
                        val name = url.lastPathSegment.orEmpty()
                        return runCatching { WebResourceResponse(if (name.endsWith(".png")) "image/png" else "image/jpeg", null,
                            store.source(article.id, name).inputStream()) }.getOrNull()
                    }
                    return null
                }
            }
        }
    }, update = { view -> if (view.tag != html) { view.loadDataWithBaseURL("file:///android_asset/web/", html, "text/html", "UTF-8", null); view.tag = html } }, onRelease = { it.destroy() }) }
}

@Composable private fun EditDialog(model: AppViewModel, article: Article, line: ArticleLine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognition by RecognitionGate.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(line.text) }
    var formula by remember { mutableStateOf(line.kind == BlockKind.FORMULA) }
    var recognizing by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var formulaCrop by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(Crop(0.0, 0.0, 1.0, 1.0)) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, line.source) { if (line.source.isNotEmpty()) value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(model.store.source(article.id, line.source).absolutePath) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = Paper) {
            Column {
                TopBar("校对", onDismiss) { IconButton(onClick = { delete = true }) { Icon(Icons.Rounded.DeleteOutline, "删除此行") } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("原始画面 · ${ArticleExport.timeLabel(line.timeMs)}", color = Ink, fontSize = 14.sp)
                    bitmap?.let { if (formulaCrop) CropEditor(it, crop, { c -> crop = c }) else Image(it.asImageBitmap(), "原始画面", Modifier.fillMaxWidth().heightIn(max = 230.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (article.config.uri.isNotBlank()) OutlinedButton(onClick = { playback = true }) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(17.dp)); Text("回看录屏") }
                        if (bitmap != null) OutlinedButton(onClick = { formulaCrop = !formulaCrop }, enabled = !recognizing && recognition == null) { Text(if (formulaCrop) "取消框选" else "框选公式") }
                    }
                    if (formulaCrop && bitmap != null) Button(onClick = {
                        val lease = RecognitionGate.acquire(RecognitionKind.FORMULA)
                        if (lease == null) { model.error = "请先结束聊天采集或暂停文章识别"; return@Button }
                        recognizing = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    val image = bitmap!!
                                    val l = (crop.left * image.width).toInt(); val t = (crop.top * image.height).toInt()
                                    val cut = android.graphics.Bitmap.createBitmap(image, l, t, ((crop.right - crop.left) * image.width).toInt().coerceIn(1, image.width - l), ((crop.bottom - crop.top) * image.height).toInt().coerceIn(1, image.height - t))
                                    try { FormulaEngine(context).use { it.recognize(cut) } } finally { if (cut !== image) cut.recycle() }
                                }
                                text = result.latex; formula = true; formulaCrop = false
                            } catch (e: Exception) { model.error = UiMessage.failure(e, "操作未完成，请重试") }
                            finally { recognizing = false; lease.close() }
                        }
                    }, enabled = !recognizing && recognition == null) { Text(if (recognizing) "正在识别公式…" else "将选区转为 LaTeX") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(formula, { formula = it }); Spacer(Modifier.width(8.dp)); Text("独立公式（LaTeX）", fontSize = 14.sp) }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 5, label = { Text(if (formula) "公式（LaTeX）" else "识别文字") }, shape = RoundedCornerShape(14.dp))
                    if (line.readings.size > 1) {
                        Text("其他画面的识别结果", fontSize = 14.sp, color = Ink)
                        line.readings.filterNot { it.text.contains("待识别公式") }.take(4).forEach { r -> TextButton(onClick = { text = r.text }) { Text(r.text, maxLines = 3) } }
                    }
                }
                Button(onClick = { model.editLine(line.id, text, if (formula) BlockKind.FORMULA else BlockKind.TEXT, true); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(22.dp).height(52.dp), enabled = text.isNotBlank() && !recognizing) { Text("保存并确认") }
            }
        }
    }
    if (playback) Dialog(onDismissRequest = { playback = false }) {
        Surface(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(12.dp)) {
            AndroidView(factory = { ctx -> VideoView(ctx).apply {
                setVideoURI(Uri.parse(article.config.uri)); setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { seekTo(line.timeMs.toInt()); start() }
                setOnErrorListener { _, _, _ -> model.error = "原始录屏已移动或不再可访问。"; playback = false; true }
            } }, modifier = Modifier.fillMaxWidth().height(420.dp), onRelease = { it.stopPlayback() })
            TextButton(onClick = { playback = false }) { Text("关闭播放") }
        } }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这行内容？") }, text = { Text("只修改提取的文章，原始录屏保留。") },
        confirmButton = { TextButton(onClick = { model.removeLine(line.id); onDismiss() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable private fun ExportDialog(model: AppViewModel, article: Article, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pending?.let(::File)
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: error("无法写入所选位置")
                }
                onDismiss()
            } catch (e: Exception) { model.error = "导出失败：" + UiMessage.failure(e, "请重新选择保存位置后重试") }
            finally { busy = false; pending = null }
        } else pending = null
    }
    fun export(format: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                pending = withContext(Dispatchers.IO) { model.store.export(article, format).absolutePath }
                save.launch(model.store.exportName(article, format))
            } catch (e: Exception) { model.error = UiMessage.failure(e, "操作未完成，请重试") }
            finally { busy = false }
        }
    }
    ArchiveSheet("导出文章", { if (!busy) onDismiss() }, closeEnabled = !busy) {
        if (article.lines.any { it.needsReview }) Text("含待校对内容", color = Orange)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column {
            ActionRow("ZIP 阅读包", { export("zip") }, recommended = true)
            ActionRow("Markdown", { export("md") })
            ActionRow("纯文本", { export("txt") })
            ActionRow("分享阅读包", {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { model.store.export(article, "zip") }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享文章"))
                        } catch (e: Exception) { model.error = UiMessage.failure(e, "操作未完成，请重试") }
                        finally { busy = false }
                    }
                }
            })
        }
    }
}
