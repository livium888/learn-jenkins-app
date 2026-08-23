package com.flashcardreader.app.reader

/**
 * One laid-out line of text: where it begins and ends in the book, and its vertical extent in
 * pixels measured from the top of the block it was laid out in.
 */
data class LineBox(
    val startChar: Int,
    val endChar: Int,
    val top: Float,
    val bottom: Float,
)

/** A page of the book: the half-open char range `[startChar, endChar)`. */
data class Page(val startChar: Int, val endChar: Int) {
    val length: Int get() = endChar - startChar
}

/** The pages found in one measured block, and where measuring should resume. */
data class PaginationStep(val pages: List<Page>, val nextStartChar: Int)

/**
 * Turns laid-out lines into pages.
 *
 * This is the whole reason the reader can now say exactly what was on screen. While the reader
 * scrolled, "which text are you reading" was a guess - the chunk nearest the middle of the
 * viewport won the whole tick, chunks were two or three screens tall, and time spent straddling a
 * boundary went to one side of it. A page is not a guess: it is exactly the text in front of you,
 * so its word count, its dwell, and the passage a question gets written from are all exact.
 *
 * Deliberately pure: measuring text needs Compose, a font resolver and a screen, none of which
 * exist where this is written. Handed line boxes, the page-breaking itself is arithmetic, and
 * arithmetic can be tested. See PaginatorTest.
 */
object Paginator {

    /**
     * Breaks one block's worth of measured lines into pages.
     *
     * The last page of a non-final block is deliberately **not** emitted: more lines might have
     * fitted on it had the block been longer, so it is left for the next block to lay out from
     * scratch. That is what stops a short page appearing every time the measuring window moves.
     */
    fun pagesFromLines(
        lines: List<LineBox>,
        pageHeightPx: Float,
        blockIsFinal: Boolean,
        forcedBreaks: Set<Int> = emptySet(),
        /**
         * Height available on a page that opens a chapter. Smaller, because the chapter heading is
         * drawn above the text there - and a page laid out as though the heading were not there
         * would push its last line off the bottom of the screen, silently losing it.
         */
        chapterPageHeightPx: Float = pageHeightPx,
    ): PaginationStep {
        if (lines.isEmpty()) return PaginationStep(emptyList(), 0)

        val pages = mutableListOf<Page>()
        var pageStart = lines.first().startChar
        var pageTop = lines.first().top
        var limit = if (pageStart in forcedBreaks) chapterPageHeightPx else pageHeightPx

        for (line in lines) {
            // A chapter starts its own page, the way it does in a printed book - and because a
            // chapter that begins halfway down a page makes "how much of this chapter did you
            // read" much harder to answer honestly.
            val forced = line.startChar in forcedBreaks && line.startChar > pageStart
            val overflows = line.bottom - pageTop > limit && line.startChar > pageStart
            if (forced || overflows) {
                pages.add(Page(pageStart, line.startChar))
                pageStart = line.startChar
                pageTop = line.top
                limit = if (pageStart in forcedBreaks) chapterPageHeightPx else pageHeightPx
            }
        }

        val tailEnd = lines.last().endChar
        if (blockIsFinal) {
            if (tailEnd > pageStart) pages.add(Page(pageStart, tailEnd))
            return PaginationStep(pages, tailEnd)
        }

        // Nothing completed: one page needs more text than the measuring window holds. Emitting
        // the block as a page keeps this moving rather than looping forever on the same offset.
        if (pages.isEmpty()) return PaginationStep(listOf(Page(pageStart, tailEnd)), tailEnd)

        return PaginationStep(pages, pageStart)
    }

    /**
     * Lays out a whole book, a block at a time.
     *
     * [measure] is given an absolute char range and returns its lines with absolute offsets;
     * heights may be relative to the start of that range. [blockChars] must comfortably exceed one
     * page, or every block ends before a page does.
     */
    inline fun paginate(
        textLength: Int,
        pageHeightPx: Float,
        blockChars: Int,
        forcedBreaks: Set<Int> = emptySet(),
        chapterPageHeightPx: Float = pageHeightPx,
        onProgress: (Float) -> Unit = {},
        measure: (fromChar: Int, toChar: Int) -> List<LineBox>,
    ): List<Page> {
        if (textLength <= 0 || pageHeightPx <= 0f) return emptyList()
        val pages = mutableListOf<Page>()
        var start = 0
        while (start < textLength) {
            val end = minOf(start + blockChars, textLength)
            val lines = measure(start, end)
            if (lines.isEmpty()) break
            val step = pagesFromLines(
                lines, pageHeightPx, blockIsFinal = end >= textLength, forcedBreaks, chapterPageHeightPx,
            )
            pages.addAll(step.pages)
            // Guaranteed forward progress: pagesFromLines never returns a resume point at or
            // before the block start unless the block itself was empty.
            start = if (step.nextStartChar > start) step.nextStartChar else end
            onProgress(start.toFloat() / textLength)
        }
        return pages
    }

    /** The page containing [charOffset], or the nearest one. Pages are ordered and contiguous. */
    fun pageIndexForOffset(pages: List<Page>, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        var low = 0
        var high = pages.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val page = pages[mid]
            when {
                charOffset < page.startChar -> high = mid - 1
                charOffset >= page.endChar -> low = mid + 1
                else -> return mid
            }
        }
        return low.coerceIn(0, pages.lastIndex)
    }
}
