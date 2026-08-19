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
    private const val ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 700L

    /**
     * One page of results plus the address of the page after it.
     *
     * Gutendex answers 32 books at a time and names the next page in the response. Reading only the
     * first page - and discarding that link - is what made a 70,000-book catalogue stop dead at 32.
     */
    data class Page(val books: List<GutenbergBook>, val next: String?)

    /** Search by title/author. A blank query returns the most-downloaded books (a "popular" list). */
    suspend fun search(query: String): Page = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) BASE else "$BASE?search=${URLEncoder.encode(query, "UTF-8")}"
        parsePage(httpGetString(url))
    }

    /**
     * Browse by subject or bookshelf rather than title. Gutendex's `topic` matches subjects and
     * shelves, so "adventure" finds the adventure shelf instead of books with it in the title -
     * which is what makes browsing 70,000 books possible without knowing a title up front.
     */
    suspend fun browseTopic(topic: String): Page = withContext(Dispatchers.IO) {
        parsePage(httpGetString("$BASE?topic=${URLEncoder.encode(topic, "UTF-8")}"))
    }

    /** Fetches a page named by a previous [Page.next]. */
    suspend fun page(url: String): Page = withContext(Dispatchers.IO) { parsePage(httpGetString(url)) }

    /** Downloads the book's EPUB to [dest]; throws on a network or HTTP error. */
    suspend fun download(book: GutenbergBook, dest: File) = downloadUrl(book.epubUrl, dest)

    /** Fetches any catalogue's EPUB to [dest]; shared by every free-book source. */
    suspend fun downloadUrl(epubUrl: String, dest: File) = withContext(Dispatchers.IO) {
        val conn = (URL(epubUrl).openConnection() as HttpURLConnection).apply {
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

    /**
     * Gutendex is a free, community-run service in front of Project Gutenberg, and it is genuinely
     * slow or unreachable from time to time. One quick retry turns most of those blips into a
     * successful search; a failure that survives the retry gets an error that says what actually
     * happened rather than a bare timeout.
     */
    private fun httpGetString(urlStr: String): String {
        var lastError: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return requestOnce(urlStr)
            } catch (e: IOException) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        throw IOException(
            "Project Gutenberg's search service didn't respond. It's a free community service " +
                "that's occasionally down - Standard Ebooks and Wikisource still work.",
            lastError,
        )
    }

    private fun requestOnce(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
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

    private fun parsePage(json: String): Page {
        val root = JSONObject(json)
        // Gutendex sends JSON null for the last page, which optString would hand back as "null".
        val next = root.optString("next", "").takeIf { it.isNotBlank() && it != "null" }
        return Page(parseBooks(root), next)
    }

    private fun parseBooks(root: JSONObject): List<GutenbergBook> {
        val results = root.optJSONArray("results") ?: return emptyList()
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
