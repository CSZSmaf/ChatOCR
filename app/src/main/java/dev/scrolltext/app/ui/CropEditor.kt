package dev.scrolltext.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.scrolltext.core.Crop
import kotlin.math.*

internal val CropSaver = listSaver<Crop, Double>(save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { Crop(it[0], it[1], it[2], it[3]) })

@Composable fun CropEditor(bitmap: Bitmap, crop: Crop, onChange: (Crop) -> Unit, modifier: Modifier = Modifier, height: Dp = 340.dp) {
    val state by rememberUpdatedState(crop)
    val callback by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        val aspect = bitmap.width.toFloat() / bitmap.height
        val width = min(maxWidth.value, maxHeight.value * aspect).dp
        Box(Modifier.width(width).height(width / aspect)) {
            Image(bitmap.asImageBitmap(), "录屏选区预览", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                var start = Offset.Zero
                var initial = state
                var mode = 4
                detectDragGestures(onDragStart = { p ->
                    initial = state; start = p
                    val corners = listOf(Offset(state.left.toFloat() * size.width, state.top.toFloat() * size.height),
                        Offset(state.right.toFloat() * size.width, state.top.toFloat() * size.height),
                        Offset(state.right.toFloat() * size.width, state.bottom.toFloat() * size.height),
                        Offset(state.left.toFloat() * size.width, state.bottom.toFloat() * size.height))
                    val nearest = corners.indices.minBy { (p - corners[it]).getDistance() }
                    mode = if ((p - corners[nearest]).getDistance() < 42.dp.toPx()) nearest else 4
                }, onDrag = { change, _ ->
                    change.consume()
                    val dx = (change.position.x - start.x) / size.width
                    val dy = (change.position.y - start.y) / size.height
                    val c = initial
                    val next = when (mode) {
                        0 -> c.copy(left = (c.left + dx).coerceIn(0.0, c.right - .1), top = (c.top + dy).coerceIn(0.0, c.bottom - .1))
                        1 -> c.copy(right = (c.right + dx).coerceIn(c.left + .1, 1.0), top = (c.top + dy).coerceIn(0.0, c.bottom - .1))
                        2 -> c.copy(right = (c.right + dx).coerceIn(c.left + .1, 1.0), bottom = (c.bottom + dy).coerceIn(c.top + .1, 1.0))
                        3 -> c.copy(left = (c.left + dx).coerceIn(0.0, c.right - .1), bottom = (c.bottom + dy).coerceIn(c.top + .1, 1.0))
                        else -> {
                            val mx = dx.toDouble().coerceIn(-c.left, 1 - c.right); val my = dy.toDouble().coerceIn(-c.top, 1 - c.bottom)
                            Crop(c.left + mx, c.top + my, c.right + mx, c.bottom + my)
                        }
                    }
                    callback(next)
                })
            }) {
                val left = crop.left.toFloat() * size.width; val top = crop.top.toFloat() * size.height
                val right = crop.right.toFloat() * size.width; val bottom = crop.bottom.toFloat() * size.height
                val shade = Color.Black.copy(alpha = .55f)
                drawRect(shade, Offset.Zero, Size(size.width, top))
                drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
                drawRect(Color(0xFF9ADB89), Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
                listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom)).forEach {
                    drawCircle(Color.White, 6.dp.toPx(), it); drawCircle(Color(0xFF23654A), 6.dp.toPx(), it, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}
