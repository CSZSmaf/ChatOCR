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
        assertEquals("无法打开", UiMessage.failure(IllegalStateException(internal), "无法打开"))
        assertEquals("请重新打开", UiMessage.stored(internal, "请重新打开"))
        assertEquals("图片较多，请导出 ZIP 阅读包", UiMessage.failure(IllegalStateException("图片较多，请导出 ZIP 阅读包"), "无法导出"))
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
