package dev.chatocr.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.ChatExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Paired, warmed runs on identical saved fixtures; never a phone throughput claim. */
@RunWith(AndroidJUnit4::class)
class OcrPerformanceTest {
    @Test fun pairedPixelPreparation() = runBlocking {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val baseline = PageOcr(RefinementPolicy.ALL)
        val optimized = PageOcr(RefinementPolicy.OPTIMIZED)
        val rows = mutableListOf<Map<String, Any>>()
        try {
            val files = listOf("accuracy", "heldout", "fresh").flatMap { prefix ->
                listOf("wechat", "qq", "dark").flatMap { style -> listOf(false, true).map { group -> File(dir, "$prefix-$style-$group.png") to group } }
            }
            assertTrue("Run accuracy matrices before timing", files.all { it.first.exists() })
            val warm = BitmapFactory.decodeFile(files.first().first.absolutePath)
            repeat(2) { baseline.read(warm, false); optimized.read(warm, false) }
            warm.recycle()
            repeat(3) { round ->
                for ((index, entry) in files.withIndex()) {
                    val (file, group) = entry
                    val image = BitmapFactory.decodeFile(file.absolutePath)
                    try {
                        val engines = if ((round + index) % 2 == 0) listOf(baseline, optimized) else listOf(optimized, baseline)
                        val results = engines.associateWith { it.read(image, group) }
                        assertEquals("Pixel preparation changed OCR: ${file.name}", results[baseline], results[optimized])
                        rows += mapOf("round" to round, "file" to file.name, "baseline" to baseline.lastTiming, "optimized" to optimized.lastTiming,
                            "equal" to true, "baselineFirst" to (engines.first() === baseline))
                    } finally { image.recycle() }
                }
            }
        } finally {
            baseline.close(); optimized.close()
            File(dir, "performance-v03.json").writeText(ChatExport.gson.toJson(rows))
        }
    }
}
