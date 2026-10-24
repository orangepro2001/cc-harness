package com.cchharness.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Claude Code palette: warm paper tones with a terracotta accent. */
private val Terracotta = Color(0xFFD97757)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC6613F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9E9E2),
    onPrimaryContainer = Color(0xFF5A2512),
    secondary = Color(0xFF73726C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFECE1),
    onSecondaryContainer = Color(0xFF1F1E1D),
    tertiary = Color(0xFFC4871B),
    onTertiary = Color.White,
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF0EEE6),
    onSurfaceVariant = Color(0xFF73726C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F3EC),
    surfaceContainer = Color(0xFFF0EEE6),
    surfaceContainerHigh = Color(0xFFEBE8DE),
    surfaceContainerHighest = Color(0xFFE8E5DA),
    outline = Color(0xFFD3CFC2),
    outlineVariant = Color(0xFFE3DFD3),
    error = Color(0xFFC0443A),
    onError = Color.White,
    errorContainer = Color(0xFFF8E1DE),
    onErrorContainer = Color(0xFF5C1812),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8896A),
    onPrimary = Color(0xFF3A1608),
    primaryContainer = Color(0xFF3B2C27),
    onPrimaryContainer = Color(0xFFF9E9E2),
    secondary = Color(0xFFA09D93),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF1F1E1D),
    onSecondaryContainer = Color(0xFFECE9E0),
    tertiary = Color(0xFFE0A83C),
    onTertiary = Color(0xFF2E2000),
    background = Color(0xFF262624),
    onBackground = Color(0xFFECE9E0),
    surface = Color(0xFF262624),
    onSurface = Color(0xFFECE9E0),
    surfaceVariant = Color(0xFF30302E),
    onSurfaceVariant = Color(0xFFA09D93),
    surfaceContainerLowest = Color(0xFF1F1E1D),
    surfaceContainerLow = Color(0xFF2A2A28),
    surfaceContainer = Color(0xFF30302E),
    surfaceContainerHigh = Color(0xFF383836),
    surfaceContainerHighest = Color(0xFF3D3C39),
    outline = Color(0xFF4D4B46),
    outlineVariant = Color(0xFF3D3C39),
    error = Color(0xFFE8756B),
    onError = Color(0xFF3A0E0A),
    errorContainer = Color(0xFF4A2220),
    onErrorContainer = Color(0xFFF8E1DE),
)

/** Colours the Material scheme has no slot for: tool/status states and the brand accent. */
@Immutable
class HarnessColors(
    val brand: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val codeBackground: Color,
    val codeText: Color,
    val userBubble: Color,
)

private val LightExtra = HarnessColors(
    brand = Terracotta,
    ok = Color(0xFF3F8F5B),
    warn = Color(0xFFC4871B),
    bad = Color(0xFFC0443A),
    codeBackground = Color(0xFFF1EFE6),
    codeText = Color(0xFF3B3A37),
    userBubble = Color(0xFFEFECE1),
)

private val DarkExtra = HarnessColors(
    brand = Terracotta,
    ok = Color(0xFF6CBF88),
    warn = Color(0xFFE0A83C),
    bad = Color(0xFFE8756B),
    codeBackground = Color(0xFF1F1E1D),
    codeText = Color(0xFFD8D5CB),
    userBubble = Color(0xFF1F1E1D),
)

val LocalHarnessColors = staticCompositionLocalOf { LightExtra }

object Harness {
    val colors: HarnessColors
        @Composable get() = LocalHarnessColors.current
}

/** Serif headings (Claude's editorial look); body stays on the system sans for CJK legibility. */
private val Heading = FontFamily.Serif

private val HarnessTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Heading, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Heading, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

private val HarnessShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun HarnessTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalHarnessColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = HarnessTypography, shapes = HarnessShapes, content = content)
    }
}
