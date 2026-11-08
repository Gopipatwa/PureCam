package com.purecam.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Overlay colours that must stay readable on top of any camera image (white on black scrims),
 * plus semantic recording/level colours. Everything else comes from the Material 3 color scheme.
 */
object CamColors {
    val Record = Color(0xFFE53935)
    val Ok = Color(0xFF66BB6A)
    val OnScrim = Color.White
    val Scrim = Color.Black

    val Accent: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary
}

/** Material 3 theme. On Android 12+ it uses Material You colours taken from the wallpaper. */
@Composable
fun PureCamTheme(content: @Composable () -> Unit) {
    val base = if (Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        darkColorScheme(primary = Color(0xFFFFC107), onPrimary = Color.Black)
    }
    MaterialTheme(
        colorScheme = base.copy(background = Color.Black),
        content = content,
    )
}
