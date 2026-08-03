package com.flashcardreader.app.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints

/** A page is just a slice of the source's full text, by character offset. */
data class Page(val startChar: Int, val endChar: Int)

/**
 * Splits the full source text into screen-sized pages given the current
 * viewport and typography, so the reader shows true pages (like a real
 * e-reader) instead of one long scroll. Re-run whenever font size, viewport,
 * or theme typography changes.
 *
 * Text is measured in chunks (not all at once) since a 500-page book as a
 * single Compose text layout would be wasteful; each chunk is generously
 * sized so a page boundary is essentially never lost mid-chunk on a phone
 * screen. This is a first-pass pagination strategy - fine for continuous
 * prose, but it does not do anything special for images/tables (not
 * expected in these text sources anyway).
 */
object ReaderPaginator {
    private const val CHUNK_SIZE = 8000

    fun paginate(
        text: String,
        measure: (String, Constraints) -> TextLayoutResult,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): List<Page> {
        if (text.isEmpty()) return listOf(Page(0, 0))
        val pages = mutableListOf<Page>()
        var offset = 0
        val constraints = Constraints(maxWidth = maxWidthPx)

        while (offset < text.length) {
            val chunkEnd = minOf(offset + CHUNK_SIZE, text.length)
            val chunk = text.substring(offset, chunkEnd)
            val layout = measure(chunk, constraints)

            var fitLines = 0
            for (i in 0 until layout.lineCount) {
                if (layout.getLineBottom(i) <= maxHeightPx) fitLines = i + 1 else break
            }
            if (fitLines == 0) fitLines = 1 // always make progress even if a single line overflows

            val pageEndInChunk = if (fitLines >= layout.lineCount) {
                chunk.length
            } else {
                layout.getLineEnd(fitLines - 1, visibleEnd = true)
            }

            val absoluteEnd = offset + pageEndInChunk.coerceAtLeast(1)
            pages.add(Page(offset, absoluteEnd))
            offset = absoluteEnd
        }
        return pages
    }
}
