package com.flashcardreader.app.data.books

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * A catalogue that speaks OPDS - the standard format (an Atom feed) used by Standard Ebooks,
 * Feedbooks, many public libraries, and Calibre's own server.
 *
 * Implementing it once is what makes new sources nearly free: anything with an OPDS URL can be
 * added without writing code, including a Calibre library you host yourself.
 *
 * The whole acquisition feed is fetched once per session and filtered locally rather than relying
 * on each server's search endpoint, whose support and parameter names vary. These catalogues are
 * small enough (Standard Ebooks is around a thousand books) that this is simpler and more reliable.
 */
class OpdsCatalog(
    override val displayName: String,
    override val blurb: String,
    val feedUrl: String,
    private val credentials: () -> Credentials? = { null },
) : BookCatalog {

    private var cached: List<RemoteBook>? = null

    override suspend fun search(query: String): List<RemoteBook> {
        val all = cached ?: fetchFeed().also { cached = it }
        val q = query.trim()
        if (q.isEmpty()) return all.take(LIMIT)
        return all
            .filter { it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true) }
            .take(LIMIT)
    }

    private suspend fun fetchFeed(): List<RemoteBook> =
        parseFeed(BookHttp.getString(feedUrl, credentials(), displayName), feedUrl, displayName)

    companion object {
        private const val LIMIT = 60

        /**
         * Pulls books out of an OPDS/Atom feed. Deliberately separate from the network call so the
         * parsing - the part most likely to be wrong, and the part I cannot exercise without a
         * connection - is covered by unit tests.
         */
        fun parseFeed(xml: String, baseUrl: String, sourceName: String): List<RemoteBook> {
            // Jsoup is already a dependency (EPUB parsing) and handles Atom fine in XML mode.
            val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
            return doc.select("entry").mapNotNull { entry ->
                val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null

                // Only entries that actually offer an EPUB are usable by the reader.
                val href = entry.select("link").firstOrNull { link ->
                    link.attr("type").contains("epub", ignoreCase = true) &&
                        link.attr("rel").contains("acquisition", ignoreCase = true)
                }?.absUrl("href").orEmpty()
                if (href.isEmpty()) return@mapNotNull null

                RemoteBook(
                    id = "opds:$href",
                    title = title,
                    author = entry.selectFirst("author > name")?.text()?.trim().orEmpty(),
                    epubUrl = href,
                    sourceName = sourceName,
                    language = entry.selectFirst("dcterms|language")?.text()?.trim().orEmpty(),
                )
            }
        }

        /**
         * Public-domain classics, typeset properly by volunteers - real italics and dashes instead
         * of OCR debris, and clean chapter markup, which also gives the reader better chapters and
         * the flashcards better sentences.
         */
        const val STANDARD_EBOOKS_FEED = "https://standardebooks.org/feeds/opds/all"

        /**
         * Standard Ebooks gates its catalogue behind Patrons Circle membership - the books are
         * free and public domain, but automated access to the catalogue is a supporter benefit.
         * Sign in with your patron email as the username and no password.
         */
        fun standardEbooks(credentials: () -> Credentials? = { null }) = OpdsCatalog(
            displayName = "Standard Ebooks",
            blurb = "Beautifully typeset classics. Needs a free Patrons Circle sign-in.",
            feedUrl = STANDARD_EBOOKS_FEED,
            credentials = credentials,
        )
    }
}
