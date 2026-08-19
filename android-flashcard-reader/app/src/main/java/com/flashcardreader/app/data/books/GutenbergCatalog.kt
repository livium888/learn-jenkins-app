package com.flashcardreader.app.data.books

import com.flashcardreader.app.data.gutenberg.GutenbergClient

/** The existing Gutendex client, presented through the shared catalogue interface. */
class GutenbergCatalog : BookCatalog {

    override val displayName = "Project Gutenberg"

    override val blurb = "The biggest public-domain library - over 70,000 books."

    override suspend fun search(query: String): List<RemoteBook> =
        GutenbergClient.search(query).map { book ->
            RemoteBook(
                id = "gutenberg:${book.id}",
                title = book.title,
                author = book.author,
                epubUrl = book.epubUrl,
                sourceName = displayName,
                language = book.language,
            )
        }
}
