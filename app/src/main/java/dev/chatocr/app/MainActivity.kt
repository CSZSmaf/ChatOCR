package dev.chatocr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.chatocr.app.ui.ChatApp
import dev.chatocr.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private var pendingSession: String? = null
    private var exportId: String? = null
    private var exportFormat = "zip"
    private var revision = androidx.compose.runtime.mutableIntStateOf(0)
    private var notice = androidx.compose.runtime.mutableStateOf("")
    private var openSession = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var openArticle = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var openLibrary = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var openRequest = androidx.compose.runtime.mutableIntStateOf(0)
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision.intValue++
        if (Settings.canDrawOverlays(this)) notice.value = "已允许悬浮窗"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (pendingSession != null) requestProjection() }
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingSession; pendingSession = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && id != null) {
            val service = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_INIT)
                .putExtra("resultCode", result.resultCode).putExtra("projection", result.data).putExtra("sessionId", id)
            runCatching { ContextCompat.startForegroundService(this, service) }
                .onSuccess { notice.value = "进入聊天页，点“开始识别”" }
                .onFailure { notice.value = UiMessage.failure(it, "请重新授权屏幕共享后采集") }
        } else notice.value = "采集前请开启屏幕共享"
        revision.intValue++
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val id = exportId ?: return@registerForActivityResult
            val format = exportFormat
            lifecycleScope.launch {
                notice.value = "正在导出…"
                runCatching {
                    withContext(Dispatchers.IO) {
                        val session = store.load(id)
                        contentResolver.openOutputStream(uri, "wt")?.use { store.export(session, format, it) } ?: error("无法打开保存位置")
                    }
                }.onSuccess { notice.value = "导出完成" }
                    .onFailure { notice.value = "导出：" + UiMessage.failure(it, "请重新选择保存位置") }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        openSession.value = intent.getStringExtra("sessionId")
        openArticle.value = intent.getStringExtra("articleId")
        openLibrary.value = intent.getStringExtra("library")
        pendingSession = savedInstanceState?.getString("pendingSession")
        exportId = savedInstanceState?.getString("exportId"); exportFormat = savedInstanceState?.getString("exportFormat") ?: "zip"
        setContent {
            ChatApp(store, revision.intValue, notice.value,
                overlayAllowed = { Settings.canDrawOverlays(this) },
                grantOverlay = { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                startCapture = { config, resume -> startCapture(config, resume) },
                endCapture = { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_FINISH)) },
                export = { id, format -> export(id, format) },
                batterySettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } },
                openSessionId = openSession.value, openRequest = openRequest.intValue,
                openArticleId = openArticle.value, openLibrary = openLibrary.value)
        }
    }
    override fun onResume() { super.onResume(); revision.intValue++ }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        openSession.value = intent.getStringExtra("sessionId")
        openArticle.value = intent.getStringExtra("articleId")
        openLibrary.value = intent.getStringExtra("library"); openRequest.intValue++; revision.intValue++
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingSession", pendingSession); outState.putString("exportId", exportId); outState.putString("exportFormat", exportFormat)
        super.onSaveInstanceState(outState)
    }
    private fun startCapture(config: CaptureConfig, resume: String?) {
        if (!Settings.canDrawOverlays(this)) { notice.value = "请先开启悬浮窗权限"; return }
        if (RecognitionGate.state.value != null) { notice.value = "请先结束当前采集或暂停文章识别"; return }
        if (CaptureRuntime.state.value.active) { notice.value = "请先结束当前采集"; return }
        lifecycleScope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) {
                    if (resume != null) store.load(resume) else Session(config = config).also { store.save(it) }
                }
                pendingSession = s.id
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else requestProjection()
            }.onFailure { notice.value = UiMessage.failure(it, "请重新授权屏幕共享后采集") }
        }
    }
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        projectionPermission.launch(intent)
    }
    private fun export(id: String, format: String) {
        exportId = id; exportFormat = format
        val mime = when (format) { "zip" -> "application/zip"; "html" -> "text/html"; "json" -> "application/json"; else -> "text/plain" }
        exportDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, "拾语-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())}.$format"))
    }
}
