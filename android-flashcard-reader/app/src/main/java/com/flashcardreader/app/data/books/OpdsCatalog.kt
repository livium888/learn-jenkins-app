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
 * A catalogue may publish its feed at more than one path, and sites reorganise them. So rather than
 * betting on a single URL, [feedUrls] is tried in order and the first one that answers wins. If they
 * all fail the error names each URL and what it returned, because "couldn't reach it" with no detail
 * is impossible to act on - for the user or for whoever fixes it next.
 */
class OpdsCatalog(
    override val displayName: String,
    override val blurb: String,
    val feedUrls: List<String>,
    private val credentials: () -> Credentials? = { null },
) : BookCatalog {

    constructor(
        displayName: String,
        blurb: String,
        feedUrl: String,
        credentials: () -> Credentials? = { null },
    ) : this(displayName, blurb, listOf(feedUrl), credentials)

    /** The first URL, used as the identity of this catalogue for stored logins. */
    val feedUrl: String get() = feedUrls.first()

    private var cached: List<RemoteBook>? = null

    override suspend fun search(query: String): List<RemoteBook> {
        val all = cached ?: fetchFeed().also { cached = it }
        val q = query.trim()
        if (q.isEmpty()) return all.take(LIMIT)
        return all
            .filter { it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true) }
            .take(LIMIT)
    }

    private suspend fun fetchFeed(): List<RemoteBook> {
        val attempts = mutableListOf<String>()
        var sawAuthFailure = false
        for (url in feedUrls) {
            try {
                val books = parseFeed(BookHttp.getString(url, credentials(), displayName), url, displayName)
                if (books.isNotEmpty()) return books
                attempts += "$url returned no books"
            } catch (e: CatalogAuthRequired) {
                sawAuthFailure = true
                attempts += "$url needs a login"
            } catch (e: Exception) {
                attempts += "$url: ${e.message.orEmpty().ifBlank { "failed" }}"
            }
        }
        if (sawAuthFailure) throw CatalogAuthRequired(displayName)
        throw CatalogUnavailable(displayName, attempts)
    }

    companion object {
        private const val LIMIT = 60

        /**
         * Standard Ebooks publishes its catalogue as feeds, but the exact path has moved around and
         * not every one is open to non-browser clients. Try the documented variants in turn rather
         * than assuming; the books themselves are public domain and freely downloadable.
         */
        val STANDARD_EBOOKS_FEEDS = listOf(
            "https://standardebooks.org/feeds/opds/all",
            "https://standardebooks.org/feeds/atom/all",
            "https://standardebooks.org/feeds/atom/new-releases",
            "https://standardebooks.org/feeds/opds/new-releases",
        )

        fun standardEbooks(credentials: () -> Credentials? = { null }) = OpdsCatalog(
            displayName = "Standard Ebooks",
            blurb = "Beautifully typeset public-domain classics.",
            feedUrls = STANDARD_EBOOKS_FEEDS,
            credentials = credentials,
        )

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

                // Any link offering an EPUB will do. Plain Atom feeds don't always carry OPDS's
                // "acquisition" rel, and insisting on it silently drops perfectly good books.
                val href = entry.select("link")
                    .firstOrNull { it.attr("type").contains("epub", ignoreCase = true) }
                    ?.absUrl("href").orEmpty()
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
    }
}
