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
 * Two things about real OPDS feeds decide whether this works at all, and getting either wrong looks
 * exactly like "the source is empty":
 *
 *  - **Feeds are paginated.** A catalogue of thousands of books arrives a page at a time, linked by
 *    `rel="next"`. Reading only the first page gives you a few dozen books and no hint that the rest
 *    exist.
 *  - **Not every feed lists books.** A *navigation* feed lists other feeds ("All Ebooks", "New
 *    Releases", one per subject). Parsing one for entries with EPUBs finds nothing at all.
 *
 * So this crawls: seed URLs first, following `next` links to the end of each catalogue and stepping
 * into sub-feeds only when a feed turned out to hold no books, all under a hard request budget.
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

    /** Every URL that actually served books, in the order they were read. */
    var loadedFrom: List<String> = emptyList()
        private set

    /** What each request did - the raw material of the on-device diagnostics report. */
    private var trace: List<String> = emptyList()

    /** True when the crawl stopped early - a budget ran out, or an address failed. */
    private var incomplete = false

    /**
     * Subjects only exist if the feed publishes `<category>` on its entries. Many Calibre servers
     * don't, so this is discovered from the feed rather than assumed, and stays false until a load
     * proves otherwise - the UI hides the shelves rather than offering a control that does nothing.
     */
    override var supportsBrowse: Boolean = false
        private set

    /** Subjects actually present in this catalogue, commonest first. */
    var availableTopics: List<String> = emptyList()
        private set

    override suspend fun diagnose(): CatalogDiagnosis = try {
        val books = load()
        val (probes, download) = runStandardProbes(credentials())
        CatalogDiagnosis(
            name = displayName,
            endpoint = loadedFrom.firstOrNull() ?: feedUrls.first(),
            ok = books.isNotEmpty(),
            itemCount = books.size,
            note = buildString {
                append(trace.joinToString("; "))
                if (books.isNotEmpty() && incomplete) {
                    append(" | incomplete - some addresses failed or the crawl budget ran out")
                }
                if (supportsBrowse) append(" | shelves: ${availableTopics.take(6).joinToString(", ")}")
            },
            sampleTitles = books.take(3).map { "${it.title} — ${it.author}" },
            probes = probes,
            download = download,
        )
    } catch (e: CatalogUnavailable) {
        CatalogDiagnosis(displayName, feedUrls.joinToString(" | "), false, 0, e.attempts.joinToString("; "))
    } catch (e: Exception) {
        CatalogDiagnosis(displayName, feedUrls.first(), false, 0, e.message ?: e::class.java.simpleName)
    }

    /**
     * Everything the current search matched. The screen is handed one page at a time from here, so
     * a catalogue of thousands stays scrollable rather than arriving all at once.
     */
    private var matched: List<RemoteBook> = emptyList()
    private var served = 0

    override suspend fun search(query: String): List<RemoteBook> {
        val all = load()
        val q = query.trim()
        matched = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
            }
        }
        served = 0
        return more()
    }

    override suspend fun browse(topic: String): List<RemoteBook> {
        val all = load()
        val t = topic.trim()
        // A shelf name is a prefix of the catalogue's own wording as often as it is the whole of it
        // ("Adventure" vs "Adventure stories"), so match on containment rather than demanding the
        // subject be spelled exactly our way.
        matched = all.filter { book -> book.subjects.any { it.contains(t, ignoreCase = true) } }
        served = 0
        return more()
    }

    override val matchedTotal: Int? get() = matched.size.takeIf { cached != null }

    override val readWasCutShort: Boolean get() = incomplete

    /** The next page of the current match, straight from memory - the feed is already read. */
    override suspend fun more(): List<RemoteBook> {
        if (served >= matched.size) return emptyList()
        val page = matched.subList(served, minOf(served + PAGE, matched.size)).toList()
        served += page.size
        return page
    }

    /** Fetches the whole catalogue once, then answers from memory. */
    private suspend fun load(): List<RemoteBook> = cached ?: crawl().also { cached = it }

    /** An OPDS feed is a complete catalogue, so it can be asked what it holds. */
    override suspend fun wholeCatalogue(): List<RemoteBook> = load()

    /** Drops the held catalogue so the next search re-reads the feed and picks up new titles. */
    override fun invalidate() {
        cached = null
        matched = emptyList()
        served = 0
        trace = emptyList()
        loadedFrom = emptyList()
    }

    /**
     * Breadth-first over the feed graph, under a fixed request budget.
     *
     * Pagination (`next`) is followed first, because it is the same catalogue continued. Sub-feeds
     * are followed afterwards.
     *
     * Sub-feeds used to be entered *only* when a feed held no books of its own, on the theory that
     * a feed with books is a shelf rather than an index. Real catalogues are not that tidy: a feed
     * that lists a dozen recent titles and also links to the rest is common, and under the old rule
     * that dozen was the entire catalogue as far as this app was concerned. Standard Ebooks showed
     * fifteen books and would load no more. Entering both costs requests, which is what the budget
     * is for.
     */
    private suspend fun crawl(): List<RemoteBook> {
        val found = LinkedHashMap<String, RemoteBook>()
        val notes = mutableListOf<String>()
        val servedBooks = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val frontier = ArrayDeque(feedUrls)
        var requests = 0
        var sawAuthFailure = false
        var failures = 0

        while (frontier.isNotEmpty() && requests < MAX_REQUESTS && found.size < MAX_BOOKS) {
            val url = frontier.removeFirst()
            if (!visited.add(url)) continue
            requests++
            val page = try {
                parsePage(BookHttp.getString(url, credentials(), displayName), url, displayName)
            } catch (e: CatalogAuthRequired) {
                sawAuthFailure = true
                failures++
                notes += "${short(url)} -> asked for a login (401)"
                continue
            } catch (e: Exception) {
                failures++
                notes += "${short(url)} -> ${e.message.orEmpty().ifBlank { "failed" }}"
                continue
            }

            page.books.forEach { found.putIfAbsent(it.id, it) }
            notes += "${short(url)} -> ${page.books.size} books" +
                if (page.books.isEmpty() && page.subFeeds.isNotEmpty()) " (index of ${page.subFeeds.size} feeds)" else ""
            if (page.books.isNotEmpty()) servedBooks += url

            // Continue this catalogue to its end before looking anywhere else.
            page.next?.let { if (it !in visited) frontier.addFirst(it) }
            // Then follow where it points, whether or not it also carried books of its own.
            page.subFeeds.filterNot { it in visited }.take(MAX_SUBFEEDS).forEach(frontier::addLast)
        }

        val books = found.values.toList()
        trace = notes
        // "Some titles are missing" is only worth saying when something actually went wrong or a
        // budget ran out. A small catalogue that was read to the end is complete, not partial, and
        // warning about it would be noise on every self-hosted Calibre library.
        incomplete = failures > 0 || frontier.isNotEmpty()
        loadedFrom = servedBooks
        // Catalogues file books under full Library-of-Congress headings, e.g. "England -- Social
        // life and customs -- 19th century -- Fiction". Those are precise and completely unusable
        // as a row of chips, so only the short, single-idea subjects become shelves - and a subject
        // only one book carries isn't a shelf either.
        availableTopics = books.flatMap { it.subjects }
            .filter { it.length <= MAX_SHELF_NAME && !it.contains("--") }
            .groupingBy { it }.eachCount()
            .filterValues { it >= MIN_BOOKS_PER_SHELF }
            .entries.sortedByDescending { it.value }.map { it.key }
        supportsBrowse = availableTopics.size >= MIN_TOPICS

        if (books.isNotEmpty()) return books
        if (sawAuthFailure) throw CatalogAuthRequired(displayName)
        throw CatalogUnavailable(displayName, notes)
    }

    /** Trims a URL down to something readable in an error message. */
    private fun short(url: String): String = url.substringAfter("://").take(70)

    companion object {
        /** How many books reach the screen at once. The rest arrive as you scroll. */
        private const val PAGE = 60

        /**
         * Hard ceilings so a mis-linked feed can never turn into an unbounded crawl.
         *
         * Raised from 24 along with entering sub-feeds unconditionally: a catalogue filed by
         * subject now costs one request per shelf, and 24 ran out inside the first index. Still a
         * fixed bound - the crawl stops at whichever of these three limits it reaches first, and
         * [incomplete] says so afterwards rather than pretending the result is the whole library.
         */
        private const val MAX_REQUESTS = 80
        /**
         * How many sub-feeds to take from any one feed.
         *
         * A catalogue that files by subject can publish dozens; taking them all from the first
         * index would spend the whole budget before reaching anything else.
         */
        private const val MAX_SUBFEEDS = 40
        private const val MAX_BOOKS = 4_000

        /** One stray keyword isn't a shelf system, but three real subjects already are one. */
        private const val MIN_TOPICS = 3

        /** Longer than this and it's a cataloguing heading, not a shelf anyone would tap. */
        private const val MAX_SHELF_NAME = 28
        private const val MIN_BOOKS_PER_SHELF = 2

        /**
         * Standard Ebooks publishes an OPDS root that links to everything else, so start there and
         * let the feed say where its books are rather than betting on a path that may have moved.
         * The direct paths follow as fallbacks.
         */
        val STANDARD_EBOOKS_FEEDS = listOf(
            "https://standardebooks.org/feeds/opds",
            "https://standardebooks.org/feeds/opds/all",
            "https://standardebooks.org/feeds/atom/all",
            "https://standardebooks.org/feeds/atom/new-releases",
        )

        fun standardEbooks(credentials: () -> Credentials? = { null }) = OpdsCatalog(
            displayName = "Standard Ebooks",
            blurb = "Beautifully typeset public-domain classics.",
            feedUrls = STANDARD_EBOOKS_FEEDS,
            credentials = credentials,
        )

        /** One fetched feed document: the books on it, the next page, and any feeds it points to. */
        data class OpdsPage(
            val books: List<RemoteBook>,
            val next: String?,
            val subFeeds: List<String>,
        )

        /** Convenience for the common case - just the books on one page. */
        fun parseFeed(xml: String, baseUrl: String, sourceName: String): List<RemoteBook> =
            parsePage(xml, baseUrl, sourceName).books

        /**
         * Pulls books, pagination and sub-feeds out of an OPDS/Atom document. Deliberately separate
         * from the network call so the parsing - the part most likely to be wrong, and the part I
         * cannot exercise without a connection - is covered by unit tests.
         */
        fun parsePage(xml: String, baseUrl: String, sourceName: String): OpdsPage {
            // Jsoup is already a dependency (EPUB parsing) and handles Atom fine in XML mode.
            val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
            val entries = doc.select("entry")

            val books = entries.mapNotNull { entry ->
                val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null

                val links = entry.select("link")
                // Prefer the exact EPUB media type. Catalogues also offer Kobo's kepub and Kindle's
                // azw3 on the same entry, and picking whichever came first downloaded a file the
                // parser can't open. Any other epub-ish type is still better than nothing.
                val href = (
                    links.firstOrNull { it.attr("type").trim().equals(EPUB_TYPE, ignoreCase = true) }
                        ?: links.firstOrNull { it.attr("type").contains("epub", ignoreCase = true) }
                    )?.absUrl("href").orEmpty()
                if (href.isEmpty()) return@mapNotNull null

                RemoteBook(
                    id = "opds:$href",
                    title = title,
                    author = entry.select("author > name").firstOrNull()?.text()?.trim().orEmpty(),
                    epubUrl = href,
                    sourceName = sourceName,
                    language = entry.selectFirst("dcterms|language")?.text()?.trim().orEmpty(),
                    // OPDS puts subjects in <category>; label is the human name, term the code.
                    subjects = entry.select("category").mapNotNull { c ->
                        (c.attr("label").ifBlank { c.attr("term") }).trim().takeIf { it.isNotEmpty() }
                    }.distinct(),
                )
            }

            // Pagination lives on the feed, not on an entry - checking the parent keeps a book's own
            // links from ever being mistaken for the next page and sending the crawl in a circle.
            val next = doc.select("link[rel=next]")
                .firstOrNull { it.parent()?.nodeName()?.lowercase() != "entry" }
                ?.absUrl("href")
                ?.takeIf { it.isNotBlank() && it != baseUrl }

            // Entries in a navigation feed link to other feeds rather than to files.
            val subFeeds = entries
                .flatMap { it.select("link") }
                .filter { it.attr("type").contains("atom+xml", ignoreCase = true) }
                .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
                .distinct()

            return OpdsPage(books, next, subFeeds)
        }

        private const val EPUB_TYPE = "application/epub+zip"
    }
}
