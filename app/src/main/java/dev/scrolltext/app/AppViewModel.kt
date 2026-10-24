package dev.scrolltext.app

import dev.chatocr.app.UiMessage

import android.app.Application
import dev.chatocr.app.RecognitionGate
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.scrolltext.app.data.*
import dev.scrolltext.app.processing.*
import dev.scrolltext.core.*
import kotlinx.coroutines.*

data class ImportedVideo(val uri: Uri, val title: String, val duration: Long, val preview: Bitmap)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val store = ArticleStore(application)
    var library by mutableStateOf<List<ArticleSummary>>(emptyList()); private set
    var video by mutableStateOf<ImportedVideo?>(null); private set
    var article by mutableStateOf<Article?>(null); private set
    var screen by mutableStateOf("home")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var previewTime by mutableLongStateOf(0L); private set
    private var previewJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            var wasRunning = false
            JobBus.state.collect { state ->
                if (state.running) wasRunning = true
                else if (state.articleId.isNotEmpty() && (wasRunning || screen == "processing")) {
                    if (!wasRunning) error = state.message.ifBlank { "请重新开始识别" }
                    wasRunning = false; refresh(); openArticle(state.articleId)
                }
            }
        }
    }
    fun refresh() { viewModelScope.launch {
        try { library = withContext(Dispatchers.IO) { store.list() } }
        catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "请重新打开文章") }
    } }
    fun importArchive(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                article = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { store.importArchive(it) }
                        ?: error("无法读取 ZIP 文件")
                }
                screen = "reader"; refresh()
            } catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "请选择完整的 ZIP 阅读包") }
            finally { busy = false }
        }
    }
    private fun canRecognize(): Boolean {
        if (busy) return false
        if (RecognitionGate.state.value != null) { error = "请先结束聊天采集或暂停当前识别"; return false }
        return true
    }
    fun importVideo(uri: Uri) {
        if (JobBus.state.value.running) { screen = "processing"; return }
        viewModelScope.launch {
            busy = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).substringBeforeLast('.') else "我的文章"
                    } ?: "我的文章"
                    VideoFrames(context, uri).use { frames -> ImportedVideo(uri, name, frames.durationMs, preview(frames.frame(0))) }
                }
                video?.preview?.recycle(); video = imported; previewTime = 0; screen = "import"
            } catch (e: Exception) { error = UiMessage.failure(e, "请选择相册中可播放的录屏") }
            finally { busy = false }
        }
    }
    private fun preview(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 720) return bitmap
        return Bitmap.createScaledBitmap(bitmap, 720, (bitmap.height * 720.0 / bitmap.width).toInt(), true).also { bitmap.recycle() }
    }
    fun seekPreview(time: Long) {
        val current = video ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { VideoFrames(getApplication(), current.uri).use { preview(it.frame(time)) } }
                // Old preview remains valid until Compose replaces its image; GC owns its lifetime.
                video = current.copy(preview = bitmap); previewTime = time
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = UiMessage.failure(e, "请重新打开后重试") }
        }
    }
    fun start(config: JobConfig) {
        if (JobBus.state.value.running) { screen = "processing"; return }
        if (!canRecognize()) return
        busy = true
        viewModelScope.launch {
            try {
                require(config.crop.valid() && config.endMs - config.startMs >= 500) { "请选择至少半秒的视频片段与有效正文区域。" }
                val draft = Article(title = config.title.ifBlank { "我的文章" }, config = config)
                withContext(Dispatchers.IO) { store.save(draft) }
                ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ProcessingService::class.java).putExtra("id", draft.id))
                article = draft; screen = "processing"; refresh()
            } catch (e: Exception) { error = UiMessage.failure(e, "请重新打开后重试") }
            finally { busy = false }
        }
    }
    fun cancel() { getApplication<Application>().startService(Intent(getApplication(), ProcessingService::class.java).setAction("cancel")) }
    fun resume() {
        val current = article ?: return
        if (JobBus.state.value.running) { screen = "processing"; return }
        if (current.config.uri.isBlank()) { error = "继续提取请导入原录屏"; return }
        if (current.checkpoint == null) { start(current.config); return }
        if (!canRecognize()) return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    current.status = "processing"; current.message = ""; store.save(current)
                }
                ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ProcessingService::class.java).putExtra("id", current.id))
                screen = "processing"; refresh()
            } catch (e: Exception) { error = UiMessage.failure(e, "请重新打开后重试") }
            finally { busy = false }
        }
    }
    fun openArticle(id: String) {
        if (JobBus.state.value.running && JobBus.state.value.articleId == id) { screen = "processing"; return }
        viewModelScope.launch {
            try { article = withContext(Dispatchers.IO) { store.load(id) }; screen = "reader" }
            catch (e: Exception) { error = UiMessage.failure(e, "请重新打开后重试") }
        }
    }
    fun editLine(id: Int, text: String, kind: BlockKind, verified: Boolean) {
        val current = article ?: return
        val rows = current.lines.map { if (it.id == id) it.copy(text = text, kind = kind, edited = true, needsReview = !verified) else it }
        val updated = current.copy(lines = rows)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.save(updated) }; article = updated; refresh() }
            catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "请检查存储空间后重新保存") }
        }
    }
    fun removeLine(id: Int) {
        val current = article ?: return
        val updated = current.copy(lines = current.lines.filterNot { it.id == id })
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.save(updated) }; article = updated; refresh() }
            catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "请检查存储空间后重新保存") }
        }
    }
}
