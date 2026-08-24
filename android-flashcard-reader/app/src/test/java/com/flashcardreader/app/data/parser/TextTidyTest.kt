package com.flashcardreader.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this exists for: pages that are mostly empty.
 *
 * A great many EPUBs draw a gap with `<p>&nbsp;</p>`, and a non-breaking space is not whitespace
 * as far as trim() is concerned - so the paragraph reads as real, survives, and takes a blank line
 * with it. A few hundred of those and the book loses whole pages to nothing.
 *
 * The offsets matter as much as the text. Char offsets are how this app remembers reading position,
 * bookmarks, which passage a question came from, and which text has already earned Focus Gate time.
 * Cleaning the text without carrying those with it would scatter every one of them.
 */
class TextTidyTest {

    @Test
    fun `an invisible character does not count as content`() {
        assertTrue(TextTidy.isBlank("\u00A0"))
        assertTrue(TextTidy.isBlank("  \u200B \u00A0\t"))
        assertTrue(TextTidy.isBlank("\uFEFF"))
        assertFalse(TextTidy.isBlank("\u00A0a"))
        assertTrue("a plain empty string is blank", TextTidy.isBlank(""))
    }

    @Test
    fun `a run of blank lines becomes one paragraph break`() {
        val messy = "First.\n\n\u00A0\n\n\u00A0\n\n\u00A0\n\nSecond."
        assertEquals("First.\n\nSecond.", TextTidy.tidy(messy).text)
    }

    @Test
    fun `a single newline inside a paragraph is kept`() {
        assertEquals("one\ntwo", TextTidy.tidy("one\ntwo").text)
        assertEquals("one\n\ntwo", TextTidy.tidy("one\n\ntwo").text)
    }

    @Test
    fun `invisible characters inside a line are cleaned without eating the words`() {
        // A no-break space stands in for a space; a zero-width one stands in for nothing.
        assertEquals("a b", TextTidy.tidy("a\u00A0b").text)
        assertEquals("ab", TextTidy.tidy("a\u200Bb").text)
        assertEquals("ab", TextTidy.tidy("a\uFEFFb").text)
    }

    @Test
    fun `trailing space and a trailing blank tail are removed`() {
        assertEquals("text", TextTidy.tidy("text   \n\n\u00A0\n \n").text)
    }

    @Test
    fun `text that is already clean is returned untouched`() {
        val clean = "A paragraph.\n\nAnother one.\n\nA third."
        val result = TextTidy.tidy(clean)
        assertEquals(clean, result.text)
        assertEquals(0, result.removedChars)
        // And every offset maps to itself, so nothing is disturbed by tidying a tidy book.
        for (i in clean.indices) assertEquals(i, result.offsets.map(i))
    }

    @Test
    fun `every remembered position still points at the same words`() {
        val messy = "Chapter one.\n\n\u00A0\n\n\u00A0\n\nThe lamplighter went by.\n\n\u00A0\n\nThen dusk."
        val result = TextTidy.tidy(messy)

        for (phrase in listOf("Chapter one.", "The lamplighter went by.", "Then dusk.")) {
            val oldAt = messy.indexOf(phrase)
            val newAt = result.offsets.map(oldAt)
            assertEquals(
                "\"$phrase\" must still start where the map says it does",
                phrase,
                result.text.substring(newAt, newAt + phrase.length),
            )
        }
    }

    @Test
    fun `a position inside removed space lands on the next real text`() {
        val messy = "One.\n\n\u00A0\n\n\u00A0\n\nTwo."
        val result = TextTidy.tidy(messy)
        val insideTheGap = messy.indexOf("\u00A0")
        val mapped = result.offsets.map(insideTheGap)
        assertTrue("must stay inside the tidied text", mapped in 0..result.text.length)
        assertEquals("One.\n\nTwo.", result.text)
    }

    @Test
    fun `mapping is monotonic, so nothing ever moves backwards past anything else`() {
        val messy = "a\u00A0b\n\n\u00A0\n\n\n\nc   \nd\u200Be"
        val result = TextTidy.tidy(messy)
        var previous = -1
        for (i in messy.indices) {
            val mapped = result.offsets.map(i)
            assertTrue("offset $i mapped backwards", mapped >= previous)
            assertTrue("offset $i mapped past the end", mapped <= result.text.length)
            previous = mapped
        }
    }

    @Test
    fun `the blank ratio spots a damaged book and leaves a good one alone`() {
        val damaged = "Text.\n\u00A0\n\u00A0\n\u00A0\n\u00A0\n\u00A0\nMore."
        val healthy = "A paragraph.\n\nAnother one.\n\nA third one here."
        assertTrue("damaged book should read as mostly blank", TextTidy.blankRatio(damaged) > 0.5f)
        assertTrue("healthy book should not", TextTidy.blankRatio(healthy) < 0.5f)
    }

    @Test
    fun `empty input does not explode`() {
        val result = TextTidy.tidy("")
        assertEquals("", result.text)
        assertEquals(0, result.offsets.map(0))
        assertEquals(0f, TextTidy.blankRatio(""), 0f)
    }
}
