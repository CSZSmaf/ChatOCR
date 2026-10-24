package dev.scrolltext.app.processing

import dev.chatocr.app.UiMessage

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import dev.chatocr.app.MainActivity
import dev.chatocr.app.RecognitionGate
import dev.chatocr.app.RecognitionKind
import dev.chatocr.app.R
import dev.scrolltext.app.data.ArticleStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

object JobBus { val state = MutableStateFlow(ProcessingState()) }

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var task: Job? = null
    private var lock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private var recognitionLease: RecognitionGate.Lease? = null
    private var currentArticleId = ""
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("processing", "录屏转文章", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { if (task == null) stopSelf() else task?.cancel(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("id") ?: run { stopSelf(); return START_NOT_STICKY }
        currentArticleId = id
        // Fulfil the foreground-start contract even when we reject an overlapping request.
        try { startForeground(31, notification("准备识别…", 0)) }
        catch (e: Exception) {
            recognitionLease?.close(); recognitionLease = null
            JobBus.state.value = ProcessingState(false, id, message = UiMessage.failure(e, "无法开始识别，请重新打开后重试"))
            stopSelf(); return START_NOT_STICKY
        }
        recognitionLease = RecognitionGate.acquire(RecognitionKind.ARTICLE)
        if (recognitionLease == null) {
            JobBus.state.value = ProcessingState(false, id, message = "请先结束聊天采集或公式识别")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScrollText:ocr").apply { acquire(6 * 60 * 60 * 1000L) }
        JobBus.state.value = ProcessingState(true, id, message = "准备识别…")
        task = scope.launch {
            val store = ArticleStore(applicationContext)
            val article = runCatching { store.load(id) }.getOrElse {
                JobBus.state.value = ProcessingState(false, id, message = UiMessage.failure(it, "无法读取文章，请重新打开后重试"))
                stopSelf(); return@launch
            }
            try {
                VideoProcessor(applicationContext, store).run(article) { state ->
                    JobBus.state.value = state
                    val now = System.currentTimeMillis()
                    if (now - lastNotification > 800) {
                        getSystemService(NotificationManager::class.java).notify(31, notification(state.message, (state.progress * 100).toInt()))
                        lastNotification = now
                    }
                }
            } catch (e: CancellationException) {
                article.status = "cancelled"; article.message = "已暂停，草稿已保存，可继续提取。"
                runCatching { store.save(article) }.onFailure { article.message = "保存失败，请检查手机剩余空间。可从上次自动保存处继续。" }
                JobBus.state.value = JobBus.state.value.copy(running = false, message = article.message)
            } catch (e: Exception) {
                // A storage/source write can fail after consensus was mutated. Keep the last
                // committed article and its matching checkpoint, never persist half a frame.
                val saved = runCatching { store.load(id) }.getOrDefault(article)
                saved.status = "failed"; saved.message = UiMessage.failure(e, "识别已暂停，请重新打开后继续")
                runCatching { store.save(saved) }
                JobBus.state.value = JobBus.state.value.copy(running = false, message = saved.message)
            } finally {
                JobBus.state.value = JobBus.state.value.copy(running = false)
                stopForeground(STOP_FOREGROUND_REMOVE); if (lock?.isHeld == true) lock?.release()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 100, Intent(this, MainActivity::class.java)
            .putExtra("articleId", currentArticleId).putExtra("library", "articles")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 101, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "processing").setSmallIcon(R.drawable.ic_scrolltext).setContentTitle("拾语 · 正在提取文章")
            .setContentText(message).setContentIntent(open).setOngoing(true).setProgress(100, progress, progress == 0)
            .addAction(0, "暂停", cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopSelf() }
    override fun onDestroy() {
        scope.cancel(); if (lock?.isHeld == true) lock?.release()
        val lease = recognitionLease; recognitionLease = null
        // Cancellation may still be unwinding a native OCR call. Keep ownership until it exits.
        if (task?.isCompleted == false) task?.invokeOnCompletion { lease?.close() } else lease?.close()
        JobBus.state.value = JobBus.state.value.copy(running = false)
        super.onDestroy()
    }
}
