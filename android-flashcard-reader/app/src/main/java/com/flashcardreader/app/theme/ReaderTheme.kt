package com.flashcardreader.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

enum class ReaderPalette { LIGHT, SEPIA, DARK }

@Immutable
data class ReaderColors(val background: Color, val text: Color, val accent: Color)

fun colorsFor(palette: ReaderPalette): ReaderColors = when (palette) {
    ReaderPalette.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF3B82F6))
    ReaderPalette.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF4B3A26), Color(0xFF8C6D46))
    ReaderPalette.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE6E1DA), Color(0xFF7FB2F0))
}

/**
 * A reader font. System fonts carry a ready FontFamily; accessibility fonts carry [downloadUrls]
 * and are fetched on first use (see FontLoader), falling back to [family] until/if they load.
 */
enum class ReaderFont(
    val family: FontFamily,
    val label: String,
    /**
     * Where to fetch the font, most-preferred first. The same file from independent hosts, so one
     * of them being blocked or down costs a retry rather than the font.
     */
    val downloadUrls: List<String> = emptyList(),
) {
    SERIF(FontFamily.Serif, "Serif"),
    SANS(FontFamily.SansSerif, "Sans-serif"),
    OPEN_DYSLEXIC(
        FontFamily.SansSerif,
        "OpenDyslexic",
        listOf(
            "https://cdn.jsdelivr.net/gh/antijingoist/opendyslexic/compiled/OpenDyslexic-Regular.otf",
            "https://raw.githubusercontent.com/antijingoist/opendyslexic/master/compiled/OpenDyslexic-Regular.otf",
        ),
    ),
    ATKINSON(
        FontFamily.SansSerif,
        "Atkinson",
        listOf(
            "https://cdn.jsdelivr.net/gh/google/fonts/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf",
            "https://raw.githubusercontent.com/google/fonts/main/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf",
        ),
    ),
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
    /** Extra letter spacing (in em); wider tracking measurably eases dyslexic reading. */
    val letterSpacingEm: Float = 0f,
) {
    val fontSize get() = fontSizeSp.sp
    val lineHeight get() = (fontSizeSp * lineHeightMultiplier).sp
    val horizontalMarginDp get() = (24f * marginScale)
    val letterSpacing get() = letterSpacingEm.em
}
