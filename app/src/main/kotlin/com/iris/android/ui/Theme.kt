package com.iris.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF00FF41)
val AccentDim = Color(0x1F00FF41)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFEF4444)
val Cyan = Color(0xFF22D3EE)
val Bg = Color(0xFF000000)
val Panel = Color(0xFF18181B)
val TextPrimary = Color(0xFFF4F4F5)
val TextSecondary = Color(0xFFA1A1AA)
val TextMuted = Color(0xFF71717A)

private val scheme = darkColorScheme(
    primary = Accent,
    background = Bg,
    surface = Panel,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun IrisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
