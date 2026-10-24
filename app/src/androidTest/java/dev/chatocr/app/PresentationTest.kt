package dev.chatocr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.scrolltext.app.data.Article
import dev.scrolltext.app.data.ArticleStore
import dev.scrolltext.app.data.JobConfig
import dev.scrolltext.core.Crop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationTest {
    @Test fun internalErrorsNeverBecomeReaderMessages() {
        val internal = "sha256=abcdef0123456789 content://private/file /data/user/0/dev.chatocr.app"
        assertEquals("请重新打开", UiMessage.failure(IllegalStateException(internal), "请重新打开"))
        assertEquals("请重新打开", UiMessage.stored(internal, "请重新打开"))
        assertEquals("请使用 ZIP 导出全部图片", UiMessage.failure(IllegalStateException("图片较多，请导出 ZIP 阅读包"), "请重新导出"))
        val hint = UiMessage.failure(IllegalStateException("保存失败，请检查剩余空间后重试"), "请重试")
        assertEquals("请检查存储空间后重新保存", hint)
        assertEquals(hint, UiMessage.stored(hint, "请重试"))
        assertEquals(hint, UiMessage.stored("保存失败，请检查剩余空间后重试", "请重试"))
        assertEquals("前后内容待校对 3 处", UiMessage.stored("3 处画面衔接需要核对", "请重试"))
        assertEquals("请重试", UiMessage.stored("3 处画面衔接需要核对 /data/user/0/private", "请重试"))
    }

    @Test fun cancellationStillPropagates() {
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        try {
            UiMessage.failure(cancellation, "请重试")
            fail("Cancellation should propagate")
        } catch (actual: kotlinx.coroutines.CancellationException) { assertSame(cancellation, actual) }
    }

    @Test fun exportedArticlesHaveReadableSafeNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val article = Article(title = "旅行/聊天\n记录", config = JobConfig("", "旅行/聊天\n记录", Crop(), 0, 1000))
        val store = ArticleStore(context)
        assertEquals("拾语-旅行_聊天_记录.txt", store.exportName(article, "txt"))
        assertFalse(store.exportName(article, "txt").contains(article.id.take(8)))
        val exported = store.export(article, "txt")
        try { assertEquals(store.exportName(article, "txt"), exported.name); assertTrue(exported.readText().contains(article.title)) }
        finally { exported.delete() }
    }
}
