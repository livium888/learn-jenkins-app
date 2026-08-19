package com.flashcardreader.app.data.books

import com.flashcardreader.app.data.gutenberg.GutenbergBook
import com.flashcardreader.app.data.gutenberg.GutenbergClient

/** The existing Gutendex client, presented through the shared catalogue interface. */
class GutenbergCatalog : BookCatalog {

    override val displayName = "Project Gutenberg"

    override val blurb = "Over 70,000 books. Browse a shelf below if you're not after a title."

    /** Gutenberg can match real bookshelves, so a genre word returns that shelf, not title hits. */
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
