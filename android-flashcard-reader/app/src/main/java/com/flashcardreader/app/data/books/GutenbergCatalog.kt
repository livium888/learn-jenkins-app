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

    override suspend fun browse(topic: String): List<RemoteBook> =
        GutenbergClient.browseTopic(topic).map { it.toRemote() }

    override suspend fun search(query: String): List<RemoteBook> =
        GutenbergClient.search(query).map { it.toRemote() }

    private fun GutenbergBook.toRemote() = RemoteBook(
        id = "gutenberg:$id",
        title = title,
        author = author,
        epubUrl = epubUrl,
        sourceName = displayName,
        language = language,
    )
}
