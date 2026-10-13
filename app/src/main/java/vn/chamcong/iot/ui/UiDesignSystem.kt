package vn.chamcong.iot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
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

/** Matches the card, field and dialog radii in web-admin/styles.css. */
object AppRadius {
    val extraSmall = 8.dp
    val small = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val extraLarge = 24.dp
}

/** Shared semantic palette for Android and the desktop admin web. */
object AppColorTokens {
    val green = Color(0xFF087F46)
    val greenDark = Color(0xFF056736)
    val greenSoft = Color(0xFFE1F5E9)
    val orange = Color(0xFF925D06)
    val warningSoft = Color(0xFFFFF2D8)
    val blue = Color(0xFF2167AE)
    val blueSoft = Color(0xFFE9F2FF)
    val pink = Color(0xFFAD2F61)
    val purple = Color(0xFF7843A8)
    val page = Color(0xFFF6F8F7)
    val surface = Color.White
    val ink = Color(0xFF172B23)
    val muted = Color(0xFF61716A)
    val border = Color(0xFFD9E3DC)
    val borderSubtle = Color(0xFFE4EAE6)
    val error = Color(0xFFB42338)
    val errorSoft = Color(0xFFFDECEF)
    val neutral = Color(0xFF536575)
    val neutralSoft = Color(0xFFEDF2F7)
}

private val lightColors = lightColorScheme(
    primary = AppColorTokens.green,
    onPrimary = Color.White,
    primaryContainer = AppColorTokens.greenSoft,
    onPrimaryContainer = AppColorTokens.greenDark,
    secondary = AppColorTokens.muted,
    onSecondary = Color.White,
    secondaryContainer = AppColorTokens.neutralSoft,
    onSecondaryContainer = AppColorTokens.neutral,
    tertiary = AppColorTokens.orange,
    onTertiary = Color.White,
    tertiaryContainer = AppColorTokens.warningSoft,
    onTertiaryContainer = AppColorTokens.orange,
    error = AppColorTokens.error,
    onError = Color.White,
    errorContainer = AppColorTokens.errorSoft,
    onErrorContainer = AppColorTokens.error,
    background = AppColorTokens.page,
    onBackground = AppColorTokens.ink,
    surface = AppColorTokens.surface,
    onSurface = AppColorTokens.ink,
    surfaceVariant = AppColorTokens.page,
    onSurfaceVariant = AppColorTokens.muted,
    surfaceTint = AppColorTokens.green,
    surfaceContainerLowest = AppColorTokens.surface,
    surfaceContainerLow = AppColorTokens.surface,
    surfaceContainer = AppColorTokens.surface,
    surfaceContainerHigh = AppColorTokens.page,
    surfaceContainerHighest = AppColorTokens.page,
    outline = AppColorTokens.border,
    outlineVariant = AppColorTokens.borderSubtle
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF79D6A2),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF075C32),
    onPrimaryContainer = Color(0xFFC0F1D1),
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
    errorContainer = Color(0xFF7A2131),
    onErrorContainer = Color(0xFFFFDADF),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E5E1),
    surface = Color(0xFF18221C),
    onSurface = Color(0xFFE0E5E1),
    surfaceVariant = Color(0xFF3D4943),
    onSurfaceVariant = Color(0xFFC0CAC3),
    surfaceTint = Color(0xFF79D6A2),
    surfaceContainerLowest = Color(0xFF101512),
    surfaceContainerLow = Color(0xFF18221C),
    surfaceContainer = Color(0xFF1C2721),
    surfaceContainerHigh = Color(0xFF26322B),
    surfaceContainerHighest = Color(0xFF303D35),
    outline = Color(0xFF8A968E),
    outlineVariant = Color(0xFF58655E)
)

private val appTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
)

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.extraSmall),
    small = RoundedCornerShape(AppRadius.small),
    medium = RoundedCornerShape(AppRadius.medium),
    large = RoundedCornerShape(AppRadius.large),
    extraLarge = RoundedCornerShape(AppRadius.extraLarge)
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

@Preview(showBackground = true)
@Composable
private fun ChamCongThemePreview() {
    PreviewScreen { Text("Chấm công IoT", style = MaterialTheme.typography.headlineMedium) }
}
