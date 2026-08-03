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
) {
    val fontSize get() = fontSizeSp.sp
    val lineHeight get() = (fontSizeSp * lineHeightMultiplier).sp
}
