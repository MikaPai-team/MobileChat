package com.mobilechat.app.ui

import android.os.Build
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun MobileTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val accent = if (dark) Color(0xFF82ADFF) else Color(0xFF3478F6)
    val colors = if (dark) darkColorScheme(primary = accent, background = Color(0xFF121214), surface = Color(0xFF1C1C1F),
        onBackground = Color(0xFFF2F2F7), onSurface = Color(0xFFF2F2F7), surfaceContainer = Color(0xFF242428), onSurfaceVariant = Color(0xFFAAAAB2))
    else lightColorScheme(primary = accent, background = Color(0xFFF7F7F7), surface = Color.White,
        onBackground = Color(0xFF1F232B), onSurface = Color(0xFF1F232B), surfaceContainer = Color(0xFFEEEEF2), onSurfaceVariant = Color(0xFF696A75))
    val controller = remember(dark) { ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light, keyColor = accent) }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MiuixTheme(controller = controller) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

@Composable
fun Modifier.chrome(backdrop: Backdrop?, glass: Boolean, shape: Shape = RoundedCornerShape(24.dp)): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val background = MaterialTheme.colorScheme.background
    val dark = surface.luminance() < 0.5f
    val hardware = LocalView.current.isHardwareAccelerated
    return if (glass && backdrop != null && Build.VERSION.SDK_INT >= 33 && hardware) {
        // Clip the effect itself: the backdrop's padded blur extends beyond its content layer.
        clip(shape).drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
            blur(16.dp.toPx())
            colorControls(saturation = 1.2f)
        }, highlight = { if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight },
            onDrawBehind = { drawRect(background) },
            onDrawSurface = { drawRect(surface.copy(alpha = if (dark) 0.46f else 0.32f)) })
    } else background(surface, shape)
}
