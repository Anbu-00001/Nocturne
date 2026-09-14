package io.github.anbu00001.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.anbu00001.nocturne.core.glance.SessionKind

// Dark only, on purpose: this app is mostly opened at night, and the screen is itself the light
// source it measures.
private val NightColors = darkColorScheme(
    primary = Color(0xFFB9B2E8),
    onPrimary = Color(0xFF1B1840),
    secondary = Color(0xFF8FB3C9),
    background = Color(0xFF0B0E17),
    onBackground = Color(0xFFD9DCE6),
    surface = Color(0xFF0B0E17),
    onSurface = Color(0xFFD9DCE6),
    surfaceVariant = Color(0xFF1A1F2C),
    onSurfaceVariant = Color(0xFFA3A9B8),
    outlineVariant = Color(0xFF2A3040),
    error = Color(0xFFE8A3A3),
)

@Composable
fun NocturneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightColors, content = content)
}

fun kindColor(kind: SessionKind): Color = when (kind) {
    SessionKind.GLANCE_NO_UNLOCK -> Color(0xFF7C8FB0)
    SessionKind.GLANCE_UNLOCKED -> Color(0xFFA99FE0)
    SessionKind.LOCKED_EXTENDED -> Color(0xFF5E6573)
    SessionKind.SHORT -> Color(0xFFC9A96E)
    SessionKind.EXTENDED -> Color(0xFFD9826B)
}
