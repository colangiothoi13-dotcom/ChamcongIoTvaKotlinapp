package vn.chamcong.iot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Colors used by the product's employee/admin visual language. */
object AppColorTokens {
    val green = Color(0xFF05AA59)
    val greenDark = Color(0xFF078B4B)
    val greenSoft = Color(0xFFE1F5E9)
    val orange = Color(0xFFFF9800)
    val blue = Color(0xFF168FE0)
    val pink = Color(0xFFE83E7A)
    val purple = Color(0xFF8C3FC7)
    val page = Color(0xFFF7F7F7)
    val ink = Color(0xFF17191B)
    val muted = Color(0xFF777A7D)
}

private val lightColors = lightColorScheme(
    primary = AppColorTokens.green,
    onPrimary = Color.White,
    primaryContainer = AppColorTokens.greenSoft,
    onPrimaryContainer = Color(0xFF075E34),
    secondary = Color(0xFF69716D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEEEE),
    onSecondaryContainer = Color(0xFF2B302D),
    tertiary = AppColorTokens.orange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBCB),
    onTertiaryContainer = Color(0xFF6B3A00),
    error = Color(0xFFD92D55),
    onError = Color.White,
    background = AppColorTokens.page,
    onBackground = AppColorTokens.ink,
    surface = Color(0xFFFFFFFF),
    onSurface = AppColorTokens.ink,
    surfaceVariant = Color(0xFFF0F1F1),
    onSurfaceVariant = AppColorTokens.muted,
    outline = Color(0xFFD6D8D7),
    outlineVariant = Color(0xFFE5E6E6)
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

private val appTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 31.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val appShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp)
)

@Composable
fun ChamCongTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColors else lightColors
    MaterialTheme(
        colorScheme = colors,
        typography = appTypography,
        shapes = appShapes,
        content = content
    )
}
