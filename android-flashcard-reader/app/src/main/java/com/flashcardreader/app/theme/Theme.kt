package com.flashcardreader.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Calm paper" design language: a warm off-white light mode and a warm near-black dark
 * mode, one confident blue accent, and a calm green reserved for the "I knew it" signal.
 * The palette is intentionally low-chroma so book text and the review moment stay the
 * focus - chrome recedes, content leads.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E64E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE3FF),
    onPrimaryContainer = Color(0xFF00174A),
    secondary = Color(0xFF5A5D6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE1F3),
    onSecondaryContainer = Color(0xFF171A2B),
    tertiary = Color(0xFF3F6836),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC0EFB0),
    onTertiaryContainer = Color(0xFF002200),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE7E2D6),
    onSurfaceVariant = Color(0xFF4A473E),
    outline = Color(0xFF7C796E),
    outlineVariant = Color(0xFFCEC9BC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002A76),
    primaryContainer = Color(0xFF0E45A4),
    onPrimaryContainer = Color(0xFFDBE3FF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F3),
    tertiary = Color(0xFFA4D395),
    onTertiary = Color(0xFF0F3900),
    tertiaryContainer = Color(0xFF275021),
    onTertiaryContainer = Color(0xFFC0EFB0),
    background = Color(0xFF14130F),
    onBackground = Color(0xFFE8E2D9),
    surface = Color(0xFF1C1B17),
    onSurface = Color(0xFFE8E2D9),
    surfaceVariant = Color(0xFF4A473E),
    onSurfaceVariant = Color(0xFFCEC9BC),
    outline = Color(0xFF979488),
    outlineVariant = Color(0xFF4A473E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Softer, more generous corner radii than Material's defaults - the "paper card" feel. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * A tightened type hierarchy: confident, slightly heavier titles for clear structure and
 * roomy line heights for calm reading. Only the roles we lean on are overridden; the rest
 * fall back to Material 3 defaults.
 */
private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    )
}

@Composable
fun FlashcardReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
