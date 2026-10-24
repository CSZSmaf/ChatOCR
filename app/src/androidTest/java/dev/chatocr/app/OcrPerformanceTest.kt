package dev.chatocr.app

import android.graphics.*
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatocr.core.ChatAssembler
import dev.chatocr.core.ChatExport
import dev.chatocr.core.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrPerformanceTest {
    @Test fun repeatedRecognitionKeepsNativeMemoryBounded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.rgb(237, 237, 237))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(149, 236, 105); canvas.drawRoundRect(320f, 100f, 940f, 300f, 16f, 16f, paint)
        paint.color = Color.BLACK; paint.textSize = 44f
        canvas.drawText("明天下午一起出发", 350f, 170f, paint)
        canvas.drawText("会议号 1024", 350f, 240f, paint)
        val timings = mutableListOf<OcrTiming>()
        try {
            PageOcr(context).use { ocr ->
                repeat(2) { ocr.read(image, false) }
                System.gc()
                val before = Debug.getNativeHeapAllocatedSize()
                repeat(20) {
                    val result = ocr.read(image, false).filter { it.kind == Kind.TEXT }
                    assertEquals(1, result.size)
                    assertEquals("明天下午一起出发会议号1024", ChatAssembler.normalized(result.single().text))
                    timings += ocr.lastTiming
                }
                System.gc()
                assertTrue("Native inference memory grew", Debug.getNativeHeapAllocatedSize() - before < 32L * 1024 * 1024)
            }
            PageOcr(context).use { assertTrue(it.read(image, false).any { c -> "1024" in c.text }) }
        } finally {
            image.recycle()
            File(context.getExternalFilesDir(null), "chat-recognition-timing.json").writeText(ChatExport.gson.toJson(timings))
        }
    }
}
