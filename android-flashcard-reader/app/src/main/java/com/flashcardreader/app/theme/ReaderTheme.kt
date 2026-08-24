package com.flashcardreader.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * A reading theme.
 *
 * The names of the constants are what gets written to storage, so they never change - the [label]
 * is what a reader sees, which is why LIGHT reads as "Paper" and DARK as "Night".
 *
 * The palettes follow the conventions e-readers have settled on rather than inventing new ones:
 * paper-white, sepia, and warm and cool dark modes. None of them is copied from another app's
 * files - they are chosen here to hit the same character, because these looks work for reasons
 * that have nothing to do with branding. A dark theme's text is deliberately never pure white:
 * full-contrast white on black blooms at night and is harder to read, not easier.
 */
enum class ReaderPalette(val label: String, val isDark: Boolean) {
    LIGHT("Paper", false),
    SEPIA("Sepia", false),
    PARCHMENT("Parchment", false),
    GREY("Grey", false),
    CANDLE("Candle", true),
    COBALT("Cobalt", true),
    SLATE("Slate", true),
    DARK("Night", true),
    BLACK("Black", true),
}

@Immutable
data class ReaderColors(val background: Color, val text: Color, val accent: Color)

fun colorsFor(palette: ReaderPalette): ReaderColors = when (palette) {
    // Paper: plain white, near-black rather than black - true black on white is harsher than it
    // looks on a backlit screen.
    ReaderPalette.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF3B82F6))
    // Sepia: the classic warm page.
    ReaderPalette.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF4B3A26), Color(0xFF8C6D46))
    // Parchment: creamier and lower-contrast than sepia, for bright rooms.
    ReaderPalette.PARCHMENT -> ReaderColors(Color(0xFFFAF3E3), Color(0xFF3F3529), Color(0xFF9C7B4E))
    // Grey: a neutral, slightly dimmed page. Easier than white under harsh light.
    ReaderPalette.GREY -> ReaderColors(Color(0xFFE9E7E2), Color(0xFF2C2C2C), Color(0xFF5C7CA8))
    // Candle: a warm dark theme - amber text on near-black, the least blue light of any of them.
    ReaderPalette.CANDLE -> ReaderColors(Color(0xFF15110B), Color(0xFFE9D3A6), Color(0xFFD9A441))
    // Cobalt: a cool dark theme - deep navy rather than black, which many people find easier to
    // settle into than a pure dark page.
    ReaderPalette.COBALT -> ReaderColors(Color(0xFF0E1826), Color(0xFFC9D8EA), Color(0xFF5794E0))
    // Slate: neutral dark, no colour cast either way.
    ReaderPalette.SLATE -> ReaderColors(Color(0xFF21252B), Color(0xFFD5DAE0), Color(0xFF8FAAD0))
    // Night: the original near-black.
    ReaderPalette.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE6E1DA), Color(0xFF7FB2F0))
    // Black: true black, so an OLED screen switches those pixels off entirely.
    ReaderPalette.BLACK -> ReaderColors(Color(0xFF000000), Color(0xFFBFBFBF), Color(0xFF6FA8DC))
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
