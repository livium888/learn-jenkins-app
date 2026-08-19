package com.flashcardreader.app.books

import com.flashcardreader.app.data.books.SeenBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "New" badge is only worth having if it is right about what is new. Getting this wrong is
 * quiet in a way a crash isn't: badge everything and it means nothing, badge nothing and the
 * feature may as well not exist.
 */
class SeenBooksTest {

    private val first = listOf("a", "b", "c")

    @Test
    fun `a source you have never opened has nothing new in it`() {
        val (arrivals, record) = SeenBooks.arrivals(previous = null, ids = first)
        assertTrue("everything is new to a first-time visitor, which says nothing", arrivals.isEmpty())
        assertTrue("but it still has to be recorded", record.isNotBlank())
    }

    @Test
    fun `only books added since the last look count as new`() {
        val (_, record) = SeenBooks.arrivals(null, first)
        val (arrivals, _) = SeenBooks.arrivals(record, first + listOf("d", "e"))
        assertEquals(setOf("d", "e"), arrivals)
    }

    @Test
    fun `an unchanged catalogue reports nothing`() {
        val (_, record) = SeenBooks.arrivals(null, first)
        assertEquals(emptySet<String>(), SeenBooks.arrivals(record, first).first)
    }

    @Test
    fun `books disappearing from a catalogue does not make the rest look new`() {
        val (_, record) = SeenBooks.arrivals(null, first)
        assertEquals(emptySet<String>(), SeenBooks.arrivals(record, listOf("a")).first)
    }

    @Test
    fun `a book seen once stays seen on the next visit`() {
        val (_, first_) = SeenBooks.arrivals(null, first)
        val (_, second) = SeenBooks.arrivals(first_, first + "d")
        assertEquals(
            "'d' was already announced; announcing it again would make the badge meaningless",
            emptySet<String>(),
            SeenBooks.arrivals(second, first + "d").first,
        )
    }

    @Test
    fun `an empty catalogue is handled without blowing up`() {
        val (arrivals, record) = SeenBooks.arrivals(null, emptyList())
        assertTrue(arrivals.isEmpty())
        assertEquals("", record)
        // A source that failed once and answers properly later must not flag its whole catalogue.
        assertEquals(emptySet<String>(), SeenBooks.arrivals(record, first).first)
    }

    @Test
    fun `fingerprints are stable and distinguish real book urls`() {
        val url = "https://standardebooks.org/ebooks/george-eliot/middlemarch/downloads/x.epub"
        assertEquals(SeenBooks.fingerprint(url), SeenBooks.fingerprint(url))
        assertNotEquals(SeenBooks.fingerprint(url), SeenBooks.fingerprint(url + "2"))
    }
}
