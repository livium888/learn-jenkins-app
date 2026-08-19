package com.flashcardreader.app.data.books

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A book offered by one of the free catalogues, in the one shape the rest of the app cares about:
 * something with a title and a downloadable EPUB. Whatever the source, the book lands in the
 * library through the same import pipeline as a file you picked yourself.
 */
data class RemoteBook(
    /** Unique across sources, e.g. "gutenberg:1342" - used to track what's already been added. */
    val id: String,
    val title: String,
    val author: String,
    val epubUrl: String,
    val sourceName: String,
    val language: String = "",
)

/** A searchable source of free books. */
interface BookCatalog {
    val displayName: String

    /** A one-line note shown under the picker, so each source's character is obvious. */
    val blurb: String

    /** A blank query should return something browsable rather than nothing. */
    suspend fun search(query: String): List<RemoteBook>
}

/** Shared plain-HTTP helper - same no-dependency approach as the rest of the app's networking. */
internal object BookHttp {
    private const val USER_AGENT = "FlashcardReader/1.0 (personal reading app)"

    suspend fun getString(urlStr: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} from $urlStr")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
