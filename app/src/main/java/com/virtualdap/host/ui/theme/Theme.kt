package com.virtualdap.host.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0D1117)
val Panel = Color(0xFF171D25)
val RaisedPanel = Color(0xFF242D37)
val Amber = Color(0xFF9FD9CE)
val Mint = Color(0xFF9FD9CE)
val Muted = Color(0xFFA6AFBC)
private val Error = Color(0xFFFF6F7D)

private val colors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    primaryContainer = Color(0xFF19352F),
    onPrimaryContainer = Color(0xFFD2EFE7),
    secondary = Mint,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF243B38),
    onSecondaryContainer = Color(0xFFCAE9E3),
    background = Ink,
    onBackground = Color(0xFFF3F1EC),
    surface = Panel,
    onSurface = Color(0xFFF3F1EC),
    surfaceVariant = RaisedPanel,
    surfaceDim = Ink,
    surfaceBright = RaisedPanel,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = RaisedPanel,
    surfaceContainerHighest = Color(0xFF2D3743),
    surfaceTint = Color.Transparent,
    onSurfaceVariant = Muted,
    error = Error,
    errorContainer = Color(0xFF3E171D),
    onErrorContainer = Color(0xFFFFD9DD),
    outline = Color(0xFF3B404A),
    outlineVariant = Color(0xFF303C48),
)

private val typography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.7).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun VirtualDAPTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
