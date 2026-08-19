package com.flashcardreader.app.data.books

import android.util.Base64
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

/**
 * Thrown when a catalogue exists but won't talk to us without an account - a library card, a
 * Calibre login, or a Standard Ebooks patron email. Separate from a generic failure because the
 * fix is completely different: sign in, rather than check your connection.
 */
class CatalogAuthRequired(val catalogName: String) : IOException("$catalogName needs a login")

/**
 * Thrown when every known address for a catalogue failed. Carries what was tried and what came
 * back, so the screen can show something actionable rather than a shrug.
 */
class CatalogUnavailable(
    val catalogName: String,
    val attempts: List<String>,
) : IOException("$catalogName didn't respond:\n" + attempts.joinToString("\n"))

/** Shared plain-HTTP helper - same no-dependency approach as the rest of the app's networking. */
internal object BookHttp {
    private const val USER_AGENT = "FlashcardReader/1.0 (personal reading app)"

    suspend fun getString(
        urlStr: String,
        credentials: Credentials? = null,
        catalogName: String = "This catalogue",
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (credentials != null) {
                val raw = "${credentials.username}:${credentials.password}"
                val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw CatalogAuthRequired(catalogName)
            if (code !in 200..299) throw IOException("HTTP $code from $urlStr")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
