package dev.chatocr.app

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ChatVideoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var task: Job? = null
    private var lease: RecognitionGate.Lease? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val pause = AtomicBoolean(false)
    private var id = ""
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("chat-video", "聊天录屏识别", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") {
            pause.set(true)
            ChatVideoRuntime.state.value = ChatVideoRuntime.state.value.copy(pausing = true)
            if (task == null) stopSelf()
            return START_NOT_STICKY
        }
        if (task?.isCompleted == false) return START_NOT_STICKY
        id = intent?.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
        try { startForeground(32, notification(ChatVideoState(sessionId = id))) }
        catch (e: Exception) { ChatVideoRuntime.state.value = ChatVideoState(sessionId = id, message = UiMessage.failure(e, "无法开始识别，请重新打开后重试")); stopSelf(); return START_NOT_STICKY }
        lease = RecognitionGate.acquire(RecognitionKind.CHAT_VIDEO)
        if (lease == null) {
            ChatVideoRuntime.state.value = ChatVideoState(sessionId = id, message = "请先暂停当前识别")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        pause.set(false)
        ChatVideoRuntime.state.value = ChatVideoState(true, id)
        task = scope.launch {
            val store = SessionStore(applicationContext)
            try {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChatOCR:video").apply { acquire(6 * 60 * 60 * 1000L) }
                val session = store.load(id)
                var lastNotification = 0L
                ChatVideoProcessor(applicationContext, store).run(session, pause::get) { state ->
                    ChatVideoRuntime.state.value = state.copy(pausing = pause.get())
                    if (SystemClock.elapsedRealtime() - lastNotification > 900) {
                        getSystemService(NotificationManager::class.java).notify(32, notification(state))
                        lastNotification = SystemClock.elapsedRealtime()
                    }
                }
            } catch (e: Exception) {
                // Reload the last commit: a failed frame must not save half-mutated dedup state.
                val message = if (e is CancellationException) "已暂停，可继续识别" else UiMessage.failure(e, "识别已暂停，请重新打开后继续")
                runCatching { store.load(id).also { it.status = "录屏已暂停"; it.error = message; store.save(it) } }
                ChatVideoRuntime.state.value = ChatVideoRuntime.state.value.copy(message = message)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                lease?.close(); lease = null
                ChatVideoRuntime.state.value = ChatVideoRuntime.state.value.copy(running = false, pausing = false)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: ChatVideoState): Notification {
        val open = PendingIntent.getActivity(this, 120, Intent(this, MainActivity::class.java).putExtra("sessionId", id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseIntent = PendingIntent.getService(this, 121, Intent(this, ChatVideoService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "chat-video").setSmallIcon(R.drawable.ic_chatocr).setContentTitle("拾语 · 聊天录屏")
            .setContentText("已整理 ${state.count} 条").setContentIntent(open).setOngoing(true)
            .setProgress(100, (state.progress * 100).toInt(), false).addAction(0, "暂停", pauseIntent).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { pause.set(true); task?.cancel(); stopSelf() }
    override fun onDestroy() {
        pause.set(true); scope.cancel()
        val held = lease
        if (task?.isCompleted == false) task?.invokeOnCompletion { held?.close() } else held?.close()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
