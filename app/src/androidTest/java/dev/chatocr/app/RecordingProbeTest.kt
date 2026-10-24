package dev.chatocr.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.*
import dev.scrolltext.app.processing.VideoFrames
import dev.scrolltext.core.Crop
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit local inputs only. Reports stay in the device's app-specific files directory. */
@RunWith(AndroidJUnit4::class)
class RecordingProbeTest {
    @Test fun inspectRecording() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("recordingName")
        assumeTrue("Explicit recording required", name != null)
        require(name!!.matches(Regex("[a-zA-Z0-9._-]+\\.mp4")))
        val tag = args.getString("reportTag") ?: "probe"
        require(tag.matches(Regex("[a-zA-Z0-9_-]+")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val input = File(directory, name)
        require(input.isFile)
        val platform = args.getString("platform") ?: "微信"
        val crop = Crop(0.0, args.getString("cropTop")?.toDouble() ?: .14,
            1.0, args.getString("cropBottom")?.toDouble() ?: .88)
        val output = File(directory, tag).apply { mkdirs() }
        val config = CaptureConfig(title = "Local recording probe", platform = platform,
            group = args.getString("group") == "true", direction = Direction.NEWER)
        val samples = mutableListOf<Map<String, Any>>()
        VideoFrames(context, Uri.fromFile(input)).use { frames ->
            val comparisons = mutableListOf<OcrReadingComparison>()
            PageOcr(context, comparison = { comparisons += it }).use { ocr ->
                for (time in listOf(0L, frames.durationMs / 3, frames.durationMs * 2 / 3, frames.durationMs - 1)) {
                    comparisons.clear()
                    val full = frames.frame(time)
                    val bitmap = frames.crop(full, crop)
                    try {
                        val started = SystemClock.elapsedRealtime()
                        val candidates = ocr.read(bitmap, config.group)
                        samples += mapOf("timeMs" to time, "candidates" to candidates,
                            "elapsedMs" to SystemClock.elapsedRealtime() - started, "timing" to ocr.lastTiming, "comparisons" to comparisons.toList())
                        File(output, "frame-$time.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
                    } finally { if (bitmap !== full) bitmap.recycle(); full.recycle() }
                }
            }
            File(output, "samples.json").writeText(ChatExport.gson.toJson(samples))
            if (args.getString("sampleOnly") == "true") return@use
            val store = SessionStore(context)
            val session = Session(config = config, video = ChatVideoSource(Uri.fromFile(input).toString(), name,
                frames.durationMs, 0, frames.durationMs, top = crop.top.toFloat(), bottom = crop.bottom.toFloat()))
            val started = SystemClock.elapsedRealtime()
            store.save(session)
            try {
                val alignment = mutableListOf<Map<String, Any>>()
                ChatVideoProcessor(context, store).run(session) { state ->
                    alignment += mapOf("timeMs" to state.positionMs, "offset" to session.offset, "anchors" to session.lastAnchors)
                }
                File(output, "alignment.json").writeText(ChatExport.gson.toJson(alignment))
                File(output, "session.json").writeText(ChatExport.json(session))
                File(output, "album.html").outputStream().use { store.export(session, "html", it) }
                if (args.getString("saveFrames") == "true") File(store.directory(session.id), "frames").copyRecursively(File(output, "frames"), overwrite = true)
                File(output, "metrics.json").writeText(ChatExport.gson.toJson(mapOf(
                    "elapsedMs" to SystemClock.elapsedRealtime() - started, "count" to session.count(),
                    "pending" to session.pending(), "frames" to session.frames.size,
                    "gaps" to session.messages.count { it.kind == Kind.GAP }, "complete" to session.video!!.complete)))
            } finally { store.delete(session.id) }
        }
    }
}
