package dev.chatocr.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.chatocr.core.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val ACTION_INIT = "dev.chatocr.INIT"
        const val ACTION_TOGGLE = "dev.chatocr.TOGGLE"
        const val ACTION_FINISH = "dev.chatocr.FINISH"
        const val CHANNEL = "capture"
        const val NOTIFICATION_ID = 21
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("ChatOCR-frames")
    private lateinit var captureHandler: Handler
    private lateinit var store: SessionStore
    private var session: Session? = null
    private var frameProcessor: ChatFrameProcessor? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: CaptureOverlay? = null
    private var width = 0; private var height = 0
    private val busy = AtomicBoolean(false)
    private val dataLock = Any()
    @Volatile private var recording = false
    @Volatile private var ending = false
    private var lastSample = 0L
    private var lastRecognition = 0L
    private var pendingBitmap: Bitmap? = null
    private var pendingGray: GrayFrame? = null
    private var stableSince = 0L
    private var previous: GrayFrame? = null
    private var lastObserved: GrayFrame? = null
    private var job: Job? = null
    private var recognitionLease: RecognitionGate.Lease? = null
    private var hint = "进入聊天页，点“开始识别”"
    private val stableTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!recording || ending) {
                pendingBitmap?.recycle(); pendingBitmap = null; pendingGray = null
            } else if (!busy.get() && pendingBitmap != null && now - stableSince >= 450 && now - lastRecognition >= (session?.config?.intervalMs ?: 1100)) {
                val accepted = pendingBitmap!!; pendingBitmap = null; pendingGray = null
                busy.set(true); lastRecognition = now
                publish()
                job = scope.launch { process(accepted) }
            }
            if (!ending) captureHandler.postDelayed(this, 180)
        }
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { finishCapture("屏幕共享已结束") }
        override fun onCapturedContentResize(w: Int, h: Int) {
            if (ending) return
            val scale = minOf(1f, 1080f / w)
            val newW = (w * scale).toInt(); val newH = (h * scale).toInt()
            if (newW != width || newH != height) {
                recording = false; hint = "请调整识别范围后继续"; publish()
                captureHandler.post {
                    resize(newW, newH)
                    scope.launch { job?.join(); previous = null; lastObserved = null }
                }
            }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible && recording) { recording = false; hint = "已暂停 · 返回聊天页继续"; publish() }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) finishCapture("采集已结束")
        }
    }
    override fun onCreate() {
        super.onCreate(); store = SessionStore(this)
        captureThread.start(); captureHandler = Handler(captureThread.looper)
        captureHandler.post(stableTick)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "聊天采集", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> if (session == null) initialize(intent)
            ACTION_TOGGLE -> toggle()
            ACTION_FINISH -> finishCapture("采集完成")
        }
        return START_NOT_STICKY // A projection token must never be reused after process death.
    }
    private fun initialize(intent: Intent) {
        recognitionLease = RecognitionGate.acquire(RecognitionKind.CHAT)
        if (recognitionLease == null) {
            // A consented foreground-service start must be acknowledged before rejection.
            runCatching { startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }
            CaptureRuntime.state.value = CaptureStatus(hint = "请先暂停文章或公式识别")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        try {
            check(Settings.canDrawOverlays(this)) { "请先允许悬浮窗权限" }
            val id = requireNotNull(intent.getStringExtra("sessionId"))
            session = store.load(id)
            frameProcessor = ChatFrameProcessor(session!!, store, dataLock)
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projection", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra("projection")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_OK), requireNotNull(data))
            requireNotNull(projection).registerCallback(callback, main)
            val wm = getSystemService(WindowManager::class.java)
            val dimensions = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
                else @Suppress("DEPRECATION") Point().also { wm.defaultDisplay.getRealSize(it) }
            val scale = minOf(1f, 1080f / dimensions.x)
            width = (dimensions.x * scale).toInt(); height = (dimensions.y * scale).toInt()
            reader = makeReader(width, height)
            display = requireNotNull(projection).createVirtualDisplay("ChatOCR", width, height, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, captureHandler)
            overlay = CaptureOverlay(this, session!!.config, { toggle() }, { pause() }, { finishCapture("采集完成") }, {
                // A crop change changes image coordinates. Join an accepted OCR frame first,
                // then reset pixel alignment on the same serial worker before saving.
                scope.launch {
                    job?.join()
                    previous = null; lastObserved = null
                    runCatching { store.save(session!!) }.onFailure { fail(it) }
                }
                hint = "范围已更新 · 点“开始识别”"; publish()
            }).also { it.show() }
            publish()
        } catch (e: Exception) { fail(e); finishCapture("请重新授权屏幕共享后采集") }
    }
    private fun makeReader(w: Int, h: Int): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
        r.setOnImageAvailableListener({ source -> receive(source) }, captureHandler)
    }
    private fun resize(w: Int, h: Int) {
        if (ending || w <= 0 || h <= 0) return
        val old = reader
        reader = makeReader(w, h); width = w; height = h
        display?.resize(w, h, resources.configuration.densityDpi)
        display?.surface = reader!!.surface
        old?.close()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && !ending) {
            val wm = getSystemService(WindowManager::class.java)
            val p = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
                else @Suppress("DEPRECATION") Point().also { wm.defaultDisplay.getRealSize(it) }
            callback.onCapturedContentResize(p.x, p.y)
        }
    }
    private fun receive(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        var bitmap: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            // Serialize frame intake with OCR. Prefetching changed sampling during
            // reverse scrolling and failed the continuous-capture regression check.
            if (!recording || ending || busy.get() || now - lastSample < 220) return
            lastSample = now
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val config = session!!.config
            val top = (image.height * config.cropTop).toInt().coerceIn(0, image.height - 1)
            val bottom = (image.height * config.cropBottom).toInt().coerceIn(top + 1, image.height)
            bitmap = Bitmap.createBitmap(full, 0, top, image.width, bottom - top)
            if (bitmap !== full) full.recycle()
            val gray = PageOcr.gray(bitmap)
            if (pendingGray == null || PageOcr.difference(pendingGray!!, gray) > .6) stableSince = now
            pendingBitmap?.recycle(); pendingBitmap = bitmap; pendingGray = gray
        } catch (e: Exception) { bitmap?.recycle(); busy.set(false); fail(e) }
        finally { image.close() }
    }
    private suspend fun process(bitmap: Bitmap) {
        try {
            val s = session ?: return
            val gray = PageOcr.gray(bitmap)
            if (lastObserved?.let { PageOcr.difference(it, gray) < .7 } == true) return
            if (filesDir.usableSpace < 150L * 1024 * 1024) {
                recording = false; hint = "已暂停 · 请释放至少 150 MB 空间"; publish(); return
            }
            val shift = previous?.let { MotionMatcher.shift(it, gray) }?.let { it * 1000f / gray.width }
            val result = frameProcessor!!.process(bitmap, shift)
            val merge = result.merge
            s.status = "采集中"; s.error = null; s.updatedAt = System.currentTimeMillis()
            store.save(s)
            lastObserved = gray
            if (result.count > 0) previous = gray
            hint = when {
                result.count == 0 -> "已保存原图 · 请框选清晰的聊天文字"
                merge.gap -> "请每次滑动半屏，停留 1～2 秒"
                else -> "已保存 ${s.count()} 条"
            }
            publish()
        } catch (e: Exception) {
            if (e !is CancellationException) fail(e)
        } finally { bitmap.recycle(); busy.set(false); publish() }
    }
    private fun fail(error: Throwable) {
        recording = false
        hint = UiMessage.failure(error, "请重新打开后继续识别")
        android.util.Log.e("ChatOCR", "Capture paused", error)
        session?.error = hint; publish()
    }
    private fun pause() { recording = false; hint = "已暂停 · 可继续识别"; publish() }
    private fun toggle() {
        if (ending || projection == null) return
        recording = !recording
        hint = if (recording) "每滑半屏，停留 1～2 秒" else "已暂停 · 点击继续"
        publish()
    }
    private fun publish() {
        val snapshot = synchronized(dataLock) {
            val s = session
            CaptureStatus(!ending || busy.get(), s?.id, recording,
                s?.count() ?: 0, s?.frames?.size ?: 0, s?.pending() ?: 0, hint, ending)
        }
        main.post {
            val s = session
            CaptureRuntime.state.value = snapshot
            overlay?.update(snapshot.recording, snapshot.count, snapshot.finishing, busy.get())
            if (s != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("sessionId", session?.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, request: Int) = PendingIntent.getService(this, request, Intent(this, CaptureService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_chatocr).setContentTitle(if (recording) "拾语 · 正在采集" else "拾语 · 采集已暂停")
            .setContentText(hint).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (recording) "暂停" else "开始", action(ACTION_TOGGLE, 1))
            .addAction(0, "结束并保存", action(ACTION_FINISH, 2)).build()
    }
    private fun finishCapture(reason: String) {
        if (ending) return
        ending = true; recording = false; hint = "正在保存…"; publish()
        // Serialize with frame acquisition, then let the accepted OCR task finish before final save.
        captureHandler.post {
            captureHandler.removeCallbacks(stableTick)
            pendingBitmap?.recycle(); pendingBitmap = null; pendingGray = null
            reader?.setOnImageAvailableListener(null, null)
            scope.launch {
                job?.join()
                val s = session
                if (s != null) {
                    s.status = "已保存"; s.updatedAt = System.currentTimeMillis()
                    runCatching { store.save(s) }.onFailure { s.error = UiMessage.failure(it, "请检查存储空间后重新保存") }
                }
                withContext(Dispatchers.Main) {
                    CaptureRuntime.state.value = CaptureStatus(false, s?.id, false, s?.count() ?: 0, s?.frames?.size ?: 0, s?.pending() ?: 0, s?.error ?: reason)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (s != null) {
                        android.widget.Toast.makeText(this@CaptureService, s.error ?: "已保存 ${s.count()} 条 · 返回拾语阅读", android.widget.Toast.LENGTH_LONG).show()
                        val open = PendingIntent.getActivity(this@CaptureService, 2,
                            Intent(this@CaptureService, MainActivity::class.java).putExtra("sessionId", s.id)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        getSystemService(NotificationManager::class.java).notify(22,
                            NotificationCompat.Builder(this@CaptureService, CHANNEL).setSmallIcon(R.drawable.ic_chatocr)
                                .setContentTitle(if (s.error == null) "纪念册已保存 · ${s.count()} 条" else "采集提示")
                                .setContentText(s.error ?: "${s.config.title} · 点击阅读或导出")
                                .setContentIntent(open).setAutoCancel(true).build())
                    }
                    stopSelf()
                }
            }
        }
    }
    override fun onDestroy() {
        recording = false; ending = true
        overlay?.close(); overlay = null
        projection?.unregisterCallback(callback)
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        runCatching { unregisterReceiver(screenReceiver) }
        frameProcessor?.close(); scope.cancel(); captureThread.quitSafely()
        recognitionLease?.close(); recognitionLease = null
        CaptureRuntime.state.value = CaptureRuntime.state.value.copy(active = false, recording = false, finishing = false)
        super.onDestroy()
    }
}
