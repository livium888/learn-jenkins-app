package com.flashcardreader.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

enum class ReaderPalette { LIGHT, SEPIA, DARK }

@Immutable
data class ReaderColors(val background: Color, val text: Color, val accent: Color)

fun colorsFor(palette: ReaderPalette): ReaderColors = when (palette) {
    ReaderPalette.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF3B82F6))
    ReaderPalette.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF4B3A26), Color(0xFF8C6D46))
    ReaderPalette.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE6E1DA), Color(0xFF7FB2F0))
}

enum class ReaderFont(val family: FontFamily, val label: String) {
    SERIF(FontFamily.Serif, "Serif"),
    SANS(FontFamily.SansSerif, "Sans-serif"),
}

/** User-adjustable reading typography, persisted via DataStore (see ReaderPrefs). */
data class ReaderTypography(
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.5f,
    val palette: ReaderPalette = ReaderPalette.LIGHT,
    /** Justify text to both margins (like a printed book) vs. ragged-right. */
    val justify: Boolean = true,
    /** Horizontal page margin as a multiple of the 24dp base (0.5 = tight, 2.0 = wide). */
    val marginScale: Float = 1f,
    /** Screen brightness override 0..1, or negative to follow the system brightness. */
    val brightness: Float = -1f,
    /** Night warmth: strength 0..1 of a warm overlay that cuts blue light (0 = off). */
    val warmth: Float = 0f,
) {
    val fontSize get() = fontSizeSp.sp
    val lineHeight get() = (fontSizeSp * lineHeightMultiplier).sp
    val horizontalMarginDp get() = (24f * marginScale)
}
