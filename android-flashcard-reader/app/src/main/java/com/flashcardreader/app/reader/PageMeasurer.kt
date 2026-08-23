package com.flashcardreader.app.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/**
 * Lays real text out against the real page, and hands [Paginator] the line boxes it needs.
 *
 * This is the half that cannot be tested here - it needs Compose, a font resolver and a screen -
 * so it is kept as thin as possible and the decisions live next door in [Paginator], where they
 * can be. All this does is measure a slice of text with the page's width and no height limit, and
 * report where each line starts, ends and sits.
 */
class PageMeasurer(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val pageWidthPx: Int,
) {

    fun linesFor(text: String, fromChar: Int, toChar: Int): List<LineBox> {
        if (fromChar >= toChar || pageWidthPx <= 0) return emptyList()
        val slice = text.substring(fromChar, toChar)
        val layout = measurer.measure(
            text = slice,
            style = style,
            // No maxHeight: the whole slice is laid out and the page breaks are worked out from
            // the line boxes. Measuring once per block rather than once per page is what keeps a
            // full book under a second instead of a minute.
            constraints = Constraints(maxWidth = pageWidthPx),
            softWrap = true,
            // The cache exists to make repeated identical measurements cheap; every slice here is
            // different and measured once, so caching them only costs memory.
            skipCache = true,
        )
        if (layout.lineCount == 0) return emptyList()

        return (0 until layout.lineCount).map { line ->
            LineBox(
                startChar = fromChar + layout.getLineStart(line),
                // visibleEnd = false keeps the trailing newline inside the line, so consecutive
                // lines meet exactly and no character falls between two pages.
                endChar = fromChar + layout.getLineEnd(line, visibleEnd = false),
                top = layout.getLineTop(line),
                bottom = layout.getLineBottom(line),
            )
        }
    }

    companion object {
        /**
         * How much text to lay out at once.
         *
         * Large enough that a page always completes inside a block (so no page is ever cut short
         * by the measuring window), small enough that one measurement is quick and its layout
         * does not sit in memory. Roughly twenty pages of a paperback.
         */
        const val BLOCK_CHARS = 24_000
    }
}
