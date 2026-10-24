package dev.scrolltext.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.chatocr.app.ui.Amber

// Video screens inherit the same theme as the chat library.
internal val Colors @Composable get() = MaterialTheme.colorScheme
internal val Green @Composable get() = MaterialTheme.colorScheme.primary
internal val Ink @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Paper @Composable get() = MaterialTheme.colorScheme.background
internal val Muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Orange @Composable get() = Amber
