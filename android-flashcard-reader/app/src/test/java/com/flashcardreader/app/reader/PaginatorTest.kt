package com.flashcardreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's whole measurement story now rests on these pages being exact and contiguous: a
 * page's word count prices its dwell, its text is what a question gets written from, and its
 * boundaries decide how much of a chapter was read. A page lost at a block boundary would be text
 * the reader silently never gets credit for, and a gap between pages would be text nobody is ever
 * shown - neither of which is visible by looking at a phone.
 */
class PaginatorTest {

    /** Lines of fixed height, one per [charsPerLine] characters - a stand-in for real layout. */
    private fun lines(from: Int, to: Int, charsPerLine: Int = 10, lineHeight: Float = 10f): List<LineBox> {
        val out = mutableListOf<LineBox>()
        var start = from
        var index = 0
        while (start < to) {
            val end = minOf(start + charsPerLine, to)
            out.add(LineBox(start, end, top = index * lineHeight, bottom = (index + 1) * lineHeight))
            start = end
            index++
        }
        return out
    }

    @Test
    fun `pages tile the text with no gaps and no overlaps`() {
        val pages = Paginator.paginate(
            textLength = 1000,
            pageHeightPx = 50f, // five 10px lines, 10 chars each = 50 chars a page
            blockChars = 300,
        ) { from, to -> lines(from, to) }

        assertEquals("first page starts at the beginning", 0, pages.first().startChar)
        assertEquals("last page ends at the end", 1000, pages.last().endChar)
        for (i in 1 until pages.size) {
            assertEquals(
                "page ${i - 1} must end exactly where page $i begins",
                pages[i - 1].endChar,
                pages[i].startChar,
            )
        }
        assertTrue("every page holds text", pages.all { it.length > 0 })
    }

    @Test
    fun `block boundaries do not produce a short page`() {
        // The bug this guards: a naive implementation ends a page wherever the measuring window
        // ends, leaving a stunted page every few pages - visible, and it skews every per-page
        // number the app computes.
        val pages = Paginator.paginate(
            textLength = 2000,
            pageHeightPx = 50f,
            blockChars = 220, // deliberately not a multiple of the 50-char page
        ) { from, to -> lines(from, to) }

        val fullPages = pages.dropLast(1)
        assertTrue("expected several pages", fullPages.size > 10)
        assertTrue(
            "every page except the last should be a full page: ${fullPages.map { it.length }.distinct()}",
            fullPages.all { it.length == 50 },
        )
    }

    @Test
    fun `the same text paginates identically whatever the block size`() {
        val byBlock = listOf(150, 220, 500, 5000).map { block ->
            Paginator.paginate(2000, 50f, block) { from, to -> lines(from, to) }
        }
        for (i in 1 until byBlock.size) {
            assertEquals("block size must not change where pages fall", byBlock[0], byBlock[i])
        }
    }

    @Test
    fun `a chapter always starts its own page`() {
        val chapterStarts = setOf(120, 380)
        val pages = Paginator.paginate(
            textLength = 1000,
            pageHeightPx = 50f,
            blockChars = 300,
            forcedBreaks = chapterStarts,
        ) { from, to -> lines(from, to) }

        for (start in chapterStarts) {
            assertTrue(
                "a page must begin at chapter offset $start: ${pages.map { it.startChar }}",
                pages.any { it.startChar == start },
            )
        }
        // And still no gaps once forced breaks are in play.
        for (i in 1 until pages.size) assertEquals(pages[i - 1].endChar, pages[i].startChar)
    }

    @Test
    fun `a page taller than the measuring window still makes progress`() {
        // Degenerate, but an infinite loop here would hang the reader on open, so it is worth
        // pinning down: the block is smaller than a single page.
        val pages = Paginator.paginate(
            textLength = 500,
            pageHeightPx = 10_000f,
            blockChars = 100,
        ) { from, to -> lines(from, to) }

        assertTrue("must terminate and cover the text", pages.isNotEmpty())
        assertEquals(0, pages.first().startChar)
        assertEquals(500, pages.last().endChar)
    }

    @Test
    fun `empty or unmeasurable text yields no pages rather than crashing`() {
        assertTrue(Paginator.paginate(0, 50f, 300) { _, _ -> emptyList() }.isEmpty())
        assertTrue(Paginator.paginate(100, 0f, 300) { from, to -> lines(from, to) }.isEmpty())
        assertTrue(Paginator.paginate(100, 50f, 300) { _, _ -> emptyList() }.isEmpty())
    }

    @Test
    fun `finding the page for an offset is exact at the edges`() {
        val pages = listOf(Page(0, 50), Page(50, 100), Page(100, 137))
        assertEquals(0, Paginator.pageIndexForOffset(pages, 0))
        assertEquals(0, Paginator.pageIndexForOffset(pages, 49))
        assertEquals(1, Paginator.pageIndexForOffset(pages, 50))
        assertEquals(2, Paginator.pageIndexForOffset(pages, 136))
        // Past the end and before the start both clamp rather than throwing.
        assertEquals(2, Paginator.pageIndexForOffset(pages, 9_999))
        assertEquals(0, Paginator.pageIndexForOffset(pages, -5))
        assertEquals(0, Paginator.pageIndexForOffset(emptyList(), 42))
    }
}

/** The chapter-heading case, which is the one that silently loses a line if it is got wrong. */
class PaginatorChapterTest {

    private fun lines(from: Int, to: Int, charsPerLine: Int = 10, lineHeight: Float = 10f): List<LineBox> {
        val out = mutableListOf<LineBox>()
        var start = from
        var index = 0
        while (start < to) {
            val end = minOf(start + charsPerLine, to)
            out.add(LineBox(start, end, top = index * lineHeight, bottom = (index + 1) * lineHeight))
            start = end
            index++
        }
        return out
    }

    @Test
    fun `a chapter's first page holds less text, because the heading takes room`() {
        val pages = Paginator.paginate(
            textLength = 600,
            pageHeightPx = 50f, // 5 lines
            blockChars = 300,
            forcedBreaks = setOf(200),
            chapterPageHeightPx = 30f, // 3 lines once the heading is drawn
        ) { from, to -> lines(from, to) }

        val chapterPage = pages.first { it.startChar == 200 }
        assertEquals("the chapter's opening page should hold three lines, not five", 30, chapterPage.length)
        val ordinary = pages.first { it.startChar == 0 }
        assertEquals("pages without a heading are unaffected", 50, ordinary.length)
    }

    @Test
    fun `shrinking the chapter page still leaves the text contiguous`() {
        val pages = Paginator.paginate(
            textLength = 900,
            pageHeightPx = 50f,
            blockChars = 250,
            forcedBreaks = setOf(150, 400, 620),
            chapterPageHeightPx = 20f,
        ) { from, to -> lines(from, to) }

        assertEquals(0, pages.first().startChar)
        assertEquals(900, pages.last().endChar)
        for (i in 1 until pages.size) assertEquals(pages[i - 1].endChar, pages[i].startChar)
        for (start in listOf(150, 400, 620)) {
            assertTrue("chapter at $start must open a page", pages.any { it.startChar == start })
        }
    }
}
