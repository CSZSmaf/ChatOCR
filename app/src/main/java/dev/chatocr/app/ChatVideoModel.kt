package dev.chatocr.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.chatocr.core.*
import dev.scrolltext.app.ImportedVideo
import dev.scrolltext.app.processing.VideoFrames
import kotlinx.coroutines.*

class ChatVideoModel(application: Application) : AndroidViewModel(application) {
    var video by mutableStateOf<ImportedVideo?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var previewTime by mutableLongStateOf(0); private set
    private var previewJob: Job? = null
    fun importVideo(uri: Uri) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                video = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0).substringBeforeLast('.') else "聊天录屏"
                    } ?: "聊天录屏"
                    VideoFrames(context, uri).use { ImportedVideo(uri, name, it.durationMs, thumbnail(it.frame(0))) }
                }
                previewTime = 0
            } catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "无法打开录屏，请选择可正常播放的视频") }
            finally { busy = false }
        }
    }
    private fun thumbnail(bitmap: Bitmap): Bitmap = if (bitmap.width <= 720) bitmap else
        Bitmap.createScaledBitmap(bitmap, 720, (bitmap.height * 720f / bitmap.width).toInt(), true).also { bitmap.recycle() }
    fun seek(time: Long) {
        val current = video ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { VideoFrames(getApplication(), current.uri).use { thumbnail(it.frame(time)) } }
                video = current.copy(preview = bitmap); previewTime = time
            } catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "无法预览，请重新选择录屏") }
        }
    }
    fun dismiss() { previewJob?.cancel(); video = null; error = null }
    fun start(config: CaptureConfig, source: ChatVideoSource, opened: (String) -> Unit) {
        if (busy || RecognitionGate.state.value != null) { error = "请先暂停当前识别"; return }
        busy = true; previewJob?.cancel()
        viewModelScope.launch {
            try {
                require(source.endMs - source.startMs >= 500) { "请选择至少半秒的片段" }
                val session = Session(config = config, video = source, status = "录屏待识别")
                withContext(Dispatchers.IO) { SessionStore(getApplication()).save(session) }
                launchService(session.id)
                video = null; opened(session.id)
            } catch (e: Exception) { if (e is CancellationException) throw e; error = UiMessage.failure(e, "无法开始识别，请重试") }
            finally { busy = false }
        }
    }
    fun resume(id: String) {
        if (busy || RecognitionGate.state.value != null) { error = "请先暂停当前识别"; return }
        runCatching { launchService(id) }.onFailure { error = UiMessage.failure(it, "无法继续识别，请重新打开后重试") }
    }
    private fun launchService(id: String) {
        ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ChatVideoService::class.java).putExtra("sessionId", id))
    }
    fun pause() { getApplication<Application>().startService(Intent(getApplication(), ChatVideoService::class.java).setAction("pause")) }
}
