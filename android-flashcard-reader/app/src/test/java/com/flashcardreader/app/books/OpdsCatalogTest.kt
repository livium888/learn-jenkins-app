package com.flashcardreader.app.books

import com.flashcardreader.app.data.books.OpdsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS parsing is the one part of the new catalogue support that can be checked without a network,
 * and it is where a silent mistake would show up as "the source is just empty".
 */
class OpdsCatalogTest {

    private val feed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dcterms="http://purl.org/dc/terms/">
          <entry>
            <title>Middlemarch</title>
            <author><name>George Eliot</name></author>
            <dcterms:language>en</dcterms:language>
            <link rel="http://opds-spec.org/acquisition/open-access"
                  type="application/epub+zip" href="/ebooks/middlemarch.epub"/>
          </entry>
          <entry>
            <title>Cover Art Only</title>
            <author><name>Nobody</name></author>
            <link rel="http://opds-spec.org/image" type="image/jpeg" href="/cover.jpg"/>
          </entry>
          <entry>
            <title>Kindle Only</title>
            <link rel="http://opds-spec.org/acquisition"
                  type="application/x-mobipocket-ebook" href="/ebooks/x.azw3"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun parse() = OpdsCatalog.parseFeed(feed, "https://example.org/feeds/opds", "Test Source")

    @Test
    fun `reads title, author and epub link`() {
        val books = parse()
        assertEquals(1, books.size)
        val book = books.first()
        assertEquals("Middlemarch", book.title)
        assertEquals("George Eliot", book.author)
        assertEquals("en", book.language)
        assertEquals("Test Source", book.sourceName)
    }

    @Test
    fun `resolves relative links against the feed url`() {
        assertEquals("https://example.org/ebooks/middlemarch.epub", parse().first().epubUrl)
    }

    @Test
    fun `skips entries with no epub to download`() {
        val titles = parse().map { it.title }
        assertTrue("cover-only entries are not books", "Cover Art Only" !in titles)
        assertTrue("non-epub formats can't be read by the app", "Kindle Only" !in titles)
    }

    @Test
    fun `plain atom entries without an acquisition rel still count`() {
        // Not every feed uses OPDS's acquisition rel; insisting on it silently drops real books.
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Persuasion</title>
                <author><name>Jane Austen</name></author>
                <link type="application/epub+zip" href="https://example.org/persuasion.epub"/>
              </entry>
            </feed>
        """.trimIndent()
        val books = OpdsCatalog.parseFeed(atom, "https://example.org", "Test")
        assertEquals(1, books.size)
        assertEquals("Persuasion", books.first().title)
    }

    @Test
    fun `prefers the real epub over kepub and azw3 on the same entry`() {
        // Catalogues offer several formats per book. Picking whichever link came first downloaded a
        // Kobo or Kindle file that the EPUB parser cannot open - a book that "adds" but won't read.
        val multi = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Dracula</title>
                <link type="application/kepub+zip" href="https://example.org/dracula.kepub.epub"/>
                <link type="application/x-mobipocket-ebook" href="https://example.org/dracula.azw3"/>
                <link type="application/epub+zip" href="https://example.org/dracula.epub"/>
              </entry>
            </feed>
        """.trimIndent()
        val book = OpdsCatalog.parseFeed(multi, "https://example.org", "Test").single()
        assertEquals("https://example.org/dracula.epub", book.epubUrl)
    }

    @Test
    fun `reads subjects so shelves can mean something`() {
        val categorised = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Treasure Island</title>
                <category term="adventure" label="Adventure"/>
                <category term="pirates"/>
                <link type="application/epub+zip" href="https://example.org/ti.epub"/>
              </entry>
            </feed>
        """.trimIndent()
        val book = OpdsCatalog.parseFeed(categorised, "https://example.org", "Test").single()
        // The human label wins where there is one; a bare term is still better than no shelf.
        assertEquals(listOf("Adventure", "pirates"), book.subjects)
    }

    @Test
    fun `finds the next page of a paginated catalogue`() {
        // Reading only page one is how a catalogue of thousands looks like a shelf of thirty.
        val page = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <link rel="self" href="/feeds/opds/all"/>
              <link rel="next" href="/feeds/opds/all?page=2"/>
              <entry>
                <title>Emma</title>
                <link type="application/epub+zip" href="/emma.epub"/>
              </entry>
            </feed>
        """.trimIndent()
        val parsed = OpdsCatalog.parsePage(page, "https://example.org/feeds/opds/all", "Test")
        assertEquals("https://example.org/feeds/opds/all?page=2", parsed.next)
        assertEquals(1, parsed.books.size)
    }

    @Test
    fun `recognises a navigation feed as an index of other feeds`() {
        // A nav feed holds no books at all. Treating it as a book list is why a source that works
        // perfectly well in a browser comes back empty here.
        val nav = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>All Ebooks</title>
                <link type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                      href="/feeds/opds/all"/>
              </entry>
              <entry>
                <title>New Releases</title>
                <link type="application/atom+xml;profile=opds-catalog"
                      href="/feeds/opds/new-releases"/>
              </entry>
            </feed>
        """.trimIndent()
        val parsed = OpdsCatalog.parsePage(nav, "https://example.org/feeds/opds", "Test")
        assertEquals(0, parsed.books.size)
        assertEquals(
            listOf("https://example.org/feeds/opds/all", "https://example.org/feeds/opds/new-releases"),
            parsed.subFeeds,
        )
    }

    @Test
    fun `an empty or broken feed yields nothing rather than throwing`() {
        assertEquals(0, OpdsCatalog.parseFeed("", "https://example.org", "Test").size)
        assertEquals(0, OpdsCatalog.parseFeed("<html><body>oops</body></html>", "https://example.org", "Test").size)
        assertEquals(null, OpdsCatalog.parsePage("", "https://example.org", "Test").next)
    }
}
