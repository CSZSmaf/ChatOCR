package dev.chatocr.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.scrolltext.app.data.*
import dev.scrolltext.app.processing.JobBus
import dev.scrolltext.app.processing.ProcessingService
import dev.scrolltext.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MergeIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun draft() = Article(title = "迁入校验", status = "complete",
        config = JobConfig("content://old-app/video", "迁入校验", Crop(), 0, 2000, false),
        lines = listOf(ArticleLine(1, "已校对的文字", Box(0.0, 0.0, 80.0, 40.0), .98, "source.png", 100,
            needsReview = false, edited = true, readings = mutableListOf(Reading("原始识别文字", 1.0, 2)))))

    @Test fun oldBundleImportsAsNewArticleWithOriginalEvidenceAndEdits() {
        val store = ArticleStore(context)
        val original = draft()
        store.save(original)
        val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.WHITE)
        store.source(original.id, "source.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val before = store.toJson(store.load(original.id)).toString()
        val sourceBytes = store.source(original.id, "source.png").readBytes()
        val zip = store.export(original, "zip")
        var imported: Article? = null
        try {
            val restored = zip.inputStream().use(store::importArchive).also { imported = it }
            assertNotEquals(original.id, restored.id)
            assertEquals("imported", restored.status)
            assertEquals("", restored.config.uri)
            assertNull(restored.checkpoint)
            assertEquals(original.lines, restored.lines)
            assertArrayEquals(sourceBytes, store.source(restored.id, "source.png").readBytes())
            assertEquals(before, store.toJson(store.load(original.id)).toString())
            assertEquals(restored.lines, store.load(restored.id).lines)
            assertEquals("content", FileProvider.getUriForFile(context, "${context.packageName}.files", zip).scheme)
        } finally {
            store.folder(original.id).deleteRecursively()
            imported?.let { store.folder(it.id).deleteRecursively() }
            zip.delete()
        }
    }

    @Test fun malformedOrIncompleteBundlesLeaveLibraryUnchanged() {
        val store = ArticleStore(context)
        val before = store.list().map { it.id }.toSet()
        fun bundle(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            } }
        }.toByteArray()
        val evidence = store.toJson(draft()).also { it.getJSONObject("config").remove("uri") }.toString().toByteArray()
        val badInputs = listOf(
            bundle(listOf("../escaped.txt" to byteArrayOf(1))),
            bundle(listOf("evidence.json" to evidence)), // Missing referenced source.
            bundle(listOf("evidence.json" to "{broken".toByteArray())),
            bundle(listOf("evidence.json" to store.toJson(draft()).put("schema", 999).toString().toByteArray())),
        )
        badInputs.forEach { bytes ->
            assertTrue(runCatching { store.importArchive(ByteArrayInputStream(bytes)) }.isFailure)
            assertEquals(before, store.list().map { it.id }.toSet())
        }
        assertFalse(File(context.cacheDir, "escaped.txt").exists())
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("article-import-") })
    }

    @Test fun taskOwnershipPreventsOverlapAndStaleRelease() {
        assertNull(RecognitionGate.state.value)
        val chat = requireNotNull(RecognitionGate.acquire(RecognitionKind.CHAT))
        try {
            assertNull(RecognitionGate.acquire(RecognitionKind.ARTICLE))
            assertNull(RecognitionGate.acquire(RecognitionKind.FORMULA))
        } finally { chat.close() }
        val article = requireNotNull(RecognitionGate.acquire(RecognitionKind.ARTICLE))
        try {
            chat.close() // A stale owner must not unlock a different task.
            assertEquals(RecognitionKind.ARTICLE, RecognitionGate.state.value)
            assertNull(RecognitionGate.acquire(RecognitionKind.CHAT))
        } finally { article.close() }
        assertNull(RecognitionGate.state.value)
    }

    @Test fun oneLauncherAndOfflinePermissions() {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(1, launchers.size)
        assertEquals("dev.chatocr.app.MainActivity", launchers.single().activityInfo.name)
        val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test fun videoServiceRejectsStartWhileChatOwnsRecognizer() {
        val store = ArticleStore(context)
        val item = draft().copy(lines = emptyList())
        store.save(item)
        val lease = requireNotNull(RecognitionGate.acquire(RecognitionKind.CHAT))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java).putExtra("id", item.id))
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (JobBus.state.value.articleId != item.id && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
                assertEquals(item.id, JobBus.state.value.articleId)
                assertFalse(JobBus.state.value.running)
                assertEquals(RecognitionKind.CHAT, RecognitionGate.state.value)
                assertEquals(item.lines, store.load(item.id).lines)
            }
        } finally { lease.close(); store.folder(item.id).deleteRecursively() }
    }
}
