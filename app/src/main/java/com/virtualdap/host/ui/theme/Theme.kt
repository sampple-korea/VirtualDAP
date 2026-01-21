package com.virtualdap.host.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Premium Dark Colors
val Black = Color(0xFF050505)
val DarkGray = Color(0xFF121212)
val SurfaceGray = Color(0xFF1E1E1E)
val PrimaryGold = Color(0xFFD4AF37) // Premium Gold
val SecondaryPurple = Color(0xFFBB86FC)
val ErrorRed = Color(0xFFCF6679)
val SuccessGreen = Color(0xFF03DAC5)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGold,
    secondary = SecondaryPurple,
    tertiary = SuccessGreen,
    background = Black,
    surface = SurfaceGray,
    onPrimary = Black,
    onSecondary = Black,
    onTertiary = Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

// Force Dark Theme for Premium Feel
@Composable
fun VirtualDAPTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(), // Default for now
        content = content
    )
}
