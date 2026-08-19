package com.flashcardreader.app.data.books

import com.flashcardreader.app.data.gutenberg.GutenbergBook
import com.flashcardreader.app.data.gutenberg.GutenbergClient

/** The existing Gutendex client, presented through the shared catalogue interface. */
class GutenbergCatalog : BookCatalog {

    override val displayName = "Project Gutenberg"

    override val blurb = "Over 70,000 books. Browse a shelf below if you're not after a title."

    /** The only source here that matches real bookshelves rather than words in titles. */
    override val supportsBrowse = true

    override suspend fun diagnose(): CatalogDiagnosis = try {
        val books = search("")
        val (probes, download) = runStandardProbes()
        CatalogDiagnosis(
            name = displayName,
            endpoint = "https://gutendex.com/books",
            ok = books.isNotEmpty(),
            itemCount = books.size,
            note = if (books.isEmpty()) "reachable but returned nothing" else "",
            sampleTitles = books.take(3).map { "${it.title} — ${it.author}" },
            probes = probes,
            download = download,
        )
    } catch (e: Exception) {
        CatalogDiagnosis(displayName, "https://gutendex.com/books", false, 0, e.message ?: "failed")
    }

    /** Where the next page of the current search lives, or null at the end of the results. */
    private var nextPage: String? = null

    override suspend fun browse(topic: String): List<RemoteBook> =
        GutenbergClient.browseTopic(topic).take()

    override suspend fun search(query: String): List<RemoteBook> =
        GutenbergClient.search(query).take()

    override suspend fun more(): List<RemoteBook> {
        val url = nextPage ?: return emptyList()
        return GutenbergClient.page(url).take()
    }

    /** Keeps the cursor and hands back the books, so no call site can forget to do both. */
    private fun GutenbergClient.Page.take(): List<RemoteBook> {
        nextPage = next
        return books.map { it.toRemote() }
    }

    private fun GutenbergBook.toRemote() = RemoteBook(
        id = "gutenberg:$id",
        title = title,
        author = author,
        epubUrl = epubUrl,
        sourceName = displayName,
        language = language,
    )
}
