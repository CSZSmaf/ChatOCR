package dev.chatocr.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Explicit surface roles keep dialogs, menus and sheets in the same palette.
private val Light = lightColorScheme(
    primary = Color(0xFF236B5C), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDECE4), onPrimaryContainer = Color(0xFF204F41), inversePrimary = Color(0xFFA5D4BD),
    secondary = Color(0xFF52675C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDE6), onSecondaryContainer = Color(0xFF30483B),
    tertiary = Color(0xFF805B27), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E8CE), onTertiaryContainer = Color(0xFF75501D),
    background = Color(0xFFF8F7F3), onBackground = Color(0xFF252D28),
    surface = Color(0xFFFCFCF9), onSurface = Color(0xFF252D28),
    surfaceVariant = Color(0xFFEDEFE8), onSurfaceVariant = Color(0xFF252D28), surfaceTint = Color(0xFF236B5C),
    surfaceDim = Color(0xFFE0E4DB), surfaceBright = Color(0xFFFCFCF9),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3ED),
    surfaceContainer = Color(0xFFEDEFE8), surfaceContainerHigh = Color(0xFFE8EBE3), surfaceContainerHighest = Color(0xFFE1E5DC),
    outline = Color(0xFF7C8980), outlineVariant = Color(0xFFDEE3DA),
    inverseSurface = Color(0xFF2D3630), inverseOnSurface = Color(0xFFF1F4ED),
    error = Color(0xFFA43D35), onError = Color.White,
    errorContainer = Color(0xFFFBE5E1), onErrorContainer = Color(0xFF742A23), scrim = Color(0xFF101B15)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA5D4BD), onPrimary = Color(0xFF113A2E),
    primaryContainer = Color(0xFF294E3D), onPrimaryContainer = Color(0xFFD4EADB), inversePrimary = Color(0xFF236B5C),
    secondary = Color(0xFFB5C9BB), onSecondary = Color(0xFF24392D),
    secondaryContainer = Color(0xFF334539), onSecondaryContainer = Color(0xFFD9E6DB),
    tertiary = Color(0xFFE9C48E), onTertiary = Color(0xFF452F11),
    tertiaryContainer = Color(0xFF493A24), onTertiaryContainer = Color(0xFFE9C48E),
    background = Color(0xFF171C19), onBackground = Color(0xFFE7ECE6),
    surface = Color(0xFF1C231F), onSurface = Color(0xFFE7ECE6),
    surfaceVariant = Color(0xFF2A332D), onSurfaceVariant = Color(0xFFE7ECE6), surfaceTint = Color(0xFFA5D4BD),
    surfaceDim = Color(0xFF171C19), surfaceBright = Color(0xFF353F38),
    surfaceContainerLowest = Color(0xFF121713), surfaceContainerLow = Color(0xFF222A25),
    surfaceContainer = Color(0xFF27302A), surfaceContainerHigh = Color(0xFF2D3730), surfaceContainerHighest = Color(0xFF354138),
    outline = Color(0xFF87988C), outlineVariant = Color(0xFF3C4940),
    inverseSurface = Color(0xFFE2E9DF), inverseOnSurface = Color(0xFF29352C),
    error = Color(0xFFF1ADA4), onError = Color(0xFF5D211C),
    errorContainer = Color(0xFF572C26), onErrorContainer = Color(0xFFF7CBC3), scrim = Color.Black
)

private val ArchiveTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp)
)

@Composable
fun ChatOcrTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    DisposableEffect(view, dark) {
        fun updateBars() {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                @Suppress("DEPRECATION")
                window.navigationBarColor = scheme.background.toArgb()
            }
        }
        // Reapply after a modal window releases focus and restores system bars.
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) updateBars() }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(listener)
        updateBars()
        onDispose { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
    }
    MaterialTheme(colorScheme = scheme, typography = ArchiveTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp)), content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Sheet: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val Peach: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
internal val Amber: Color @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer
