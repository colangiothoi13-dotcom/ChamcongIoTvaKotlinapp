package vn.chamcong.iot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared 4/8 dp spacing rhythm for screen gutters, cards, sections and controls.
 */
object AppSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val xxLarge = 32.dp
}

object AppTouchTarget {
    val minimum = 48.dp
    val gap = 8.dp
}

private val lightColors = lightColorScheme(
    primary = Color(0xFF0B6652),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2E8),
    onPrimaryContainer = Color(0xFF063A2E),
    secondary = Color(0xFF4B5D56),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE8E2),
    onSecondaryContainer = Color(0xFF15231D),
    tertiary = Color(0xFF795500),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6A7),
    onTertiaryContainer = Color(0xFF281A00),
    error = Color(0xFFB3261E),
    onError = Color.White,
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF17201B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201B),
    surfaceVariant = Color(0xFFE9EFEB),
    onSurfaceVariant = Color(0xFF3D4A44),
    outline = Color(0xFF66746C),
    outlineVariant = Color(0xFF87928B)
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF79DCC4),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005140),
    onPrimaryContainer = Color(0xFF9BF2D8),
    secondary = Color(0xFFBFCBC4),
    onSecondary = Color(0xFF293630),
    secondaryContainer = Color(0xFF3F4B45),
    onSecondaryContainer = Color(0xFFDBE7E0),
    tertiary = Color(0xFFF2CF73),
    onTertiary = Color(0xFF3D2F00),
    tertiaryContainer = Color(0xFF594500),
    onTertiaryContainer = Color(0xFFFFE6A7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E5E1),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE0E5E1),
    surfaceVariant = Color(0xFF3D4943),
    onSurfaceVariant = Color(0xFFC0CAC3),
    outline = Color(0xFF8A968E),
    outlineVariant = Color(0xFF58655E)
)

@Composable
fun ChamCongTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColors else lightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
