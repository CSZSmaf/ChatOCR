package dev.chatocr.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.chatocr.app.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaMessage(store: SessionStore, sessionId: String, file: String, enabled: Boolean, open: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, sessionId, file) {
        value = withContext(Dispatchers.IO) { runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val path = store.mediaFile(sessionId, file).absolutePath
            BitmapFactory.decodeFile(path, options)
            options.inJustDecodeBounds = false
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > 800)
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            BitmapFactory.decodeFile(path, options)
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), "图片 / 表情，点击校对", contentScale = ContentScale.Fit,
        modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 300.dp).clickable(enabled = enabled, onClick = open)) }
        ?: TextButton(open, enabled = enabled) { Text("查看原图") }
}
