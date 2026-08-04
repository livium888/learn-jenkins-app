package com.flashcardreader.app.data.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A public-domain book from the Project Gutenberg catalogue (via Gutendex) that has an EPUB. */
data class GutenbergBook(
    val id: Long,
    val title: String,
    val author: String,
    val epubUrl: String,
    val downloadCount: Int,
    val language: String,
)

/**
 * Thin client over Gutendex (gutendex.com), a free JSON API over the Project Gutenberg catalogue.
 * No SDK or new dependency - plain HttpURLConnection + org.json, the same approach as the Gemini
 * tutor. Every Gutenberg book is public domain; we only surface entries that offer an EPUB, which
 * flows straight into the app's existing EPUB import + reader pipeline.
 */
object GutenbergClient {
    private const val BASE = "https://gutendex.com/books"

    /** Search by title/author. A blank query returns the most-downloaded books (a "popular" list). */
    suspend fun search(query: String): List<GutenbergBook> = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) BASE else "$BASE?search=${URLEncoder.encode(query, "UTF-8")}"
        parseBooks(httpGetString(url))
    }

    /** Downloads the book's EPUB to [dest]; throws on a network or HTTP error. */
    suspend fun download(book: GutenbergBook, dest: File) = withContext(Dispatchers.IO) {
        val conn = (URL(book.epubUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("Download failed (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetString(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("Project Gutenberg search failed (HTTP ${conn.responseCode})")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseBooks(json: String): List<GutenbergBook> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val books = ArrayList<GutenbergBook>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val formats = o.optJSONObject("formats") ?: continue
            val epub = epubUrlFrom(formats) ?: continue
            val authors = o.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0) {
                authors.getJSONObject(0).optString("name", "Unknown")
            } else {
                "Unknown"
            }
            val langs = o.optJSONArray("languages")
            val language = if (langs != null && langs.length() > 0) langs.optString(0, "") else ""
            books.add(
                GutenbergBook(
                    id = o.optLong("id"),
                    title = o.optString("title", "Untitled"),
                    author = author,
                    epubUrl = epub,
                    downloadCount = o.optInt("download_count", 0),
                    language = language,
                ),
            )
        }
        return books
    }

    /** Gutendex format keys look like "application/epub+zip"; pick the first EPUB URL present. */
    private fun epubUrlFrom(formats: JSONObject): String? {
        val keys = formats.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("application/epub+zip")) {
                val url = formats.optString(key, "")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }
}
