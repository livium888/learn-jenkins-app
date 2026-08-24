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
    /** Subjects the catalogue files this book under, when it publishes any. */
    val subjects: List<String> = emptyList(),
)

/** A searchable source of free books. */
interface BookCatalog {
    val displayName: String

    /** A one-line note shown under the picker, so each source's character is obvious. */
    val blurb: String

    /** A blank query should return something browsable rather than nothing. */
    suspend fun search(query: String): List<RemoteBook>

    /**
     * Whether [browse] means anything here. Only true where the catalogue can match real subjects;
     * elsewhere a subject would just be matched against titles, which finds almost nothing. The UI
     * hides the shelves when this is false rather than offering a control that quietly does nothing.
     */
    val supportsBrowse: Boolean get() = false

    /** Browse a subject shelf. Only meaningful when [supportsBrowse] is true. */
    suspend fun browse(topic: String): List<RemoteBook> = search(topic)

    /**
     * Throws away anything held in memory so the next search asks the source again.
     *
     * Most sources query live and have nothing to forget, which is why this does nothing by
     * default. A whole-catalogue source like OPDS is the exception: it reads the feed once and
     * answers from memory, so without this it would keep showing the same titles all session no
     * matter what the catalogue published in the meantime.
     */
    /**
     * The next slice of whatever the last [search] or [browse] returned, or empty when there is no
     * more to give.
     *
     * Every source here answers in pages - Gutendex 32 at a time, Wikisource 30, an OPDS catalogue
     * as much as we choose to show at once. Without this, the end of the first page was the end of
     * the catalogue, and the other 69,000 books were unreachable from inside the app.
     */
    suspend fun more(): List<RemoteBook> = emptyList()

    fun invalidate() {}

    /**
     * How many titles the current search or shelf matched, or null where the source answers page
     * by page and cannot know.
     *
     * Shown to the reader because "why is this only fifteen books?" is otherwise unanswerable from
     * the screen: a short list looks identical whether the catalogue is small, the crawl was cut
     * short, or the search was narrow.
     */
    val matchedTotal: Int? get() = null

    /** True when the last read of this source was cut short, so [matchedTotal] is a floor. */
    val readWasCutShort: Boolean get() = false

    /**
     * Everything this source holds, or null if it can't say.
     *
     * Only a source that hands over a complete catalogue can answer this. A live-query source like
     * Gutenberg or Wikisource has no "contents" - it has whatever you asked for - so "what arrived
     * since last time?" isn't a question it can be asked, and it returns null rather than pretending
     * a first search result is a new publication.
     */
    suspend fun wholeCatalogue(): List<RemoteBook>? = null

    /**
     * Probes the source and reports exactly what happened. This exists because the build
     * environment cannot reach any of these hosts, so the only way to learn how they really behave
     * is to ask a real device and have it report back.
     */
    suspend fun diagnose(): CatalogDiagnosis
}

/** What a source actually did when probed: where it went, what came back, and a sample. */
data class CatalogDiagnosis(
    val name: String,
    val endpoint: String,
    val ok: Boolean,
    val itemCount: Int,
    val note: String,
    val sampleTitles: List<String> = emptyList(),
    /** One line per example search: what was asked, and what came back. */
    val probes: List<String> = emptyList(),
    /** Whether the first result would really download and open. */
    val download: String = "",
) {
    fun asText(): String = buildString {
        appendLine(if (ok) "[OK] $name" else "[FAIL] $name")
        appendLine("  endpoint: $endpoint")
        appendLine("  items: $itemCount")
        if (note.isNotBlank()) appendLine("  note: $note")
        sampleTitles.take(3).forEach { appendLine("  sample: $it") }
        probes.forEach { appendLine("  search: $it") }
        if (download.isNotBlank()) appendLine("  download: $download")
    }
}

/**
 * The same set of example searches run against every source, so the results are comparable rather
 * than anecdotal - a title, an author, a word that appears in many titles, a subject shelf - plus a
 * real download attempt on the first book found.
 *
 * These queries are chosen to be answerable by any public-domain catalogue worth including: if
 * "Austen" returns nothing, the source is broken or empty, not merely lacking that book.
 */
internal suspend fun BookCatalog.runStandardProbes(
    credentials: Credentials? = null,
): Pair<List<String>, String> {
    val queries = listOf(
        "" to "(blank - the default list)",
        "Frankenstein" to "exact title",
        "Austen" to "author surname",
        "Sherlock" to "word inside titles",
    )
    val lines = mutableListOf<String>()
    var firstBook: RemoteBook? = null

    for ((query, label) in queries) {
        val result = runCatching { search(query) }
        result.onSuccess { books ->
            if (firstBook == null) firstBook = books.firstOrNull()
            lines += "\"$query\" $label -> ${books.size}" +
                books.take(2).joinToString("") { " | ${it.title}" }
        }.onFailure { lines += "\"$query\" $label -> FAILED: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}" }
    }

    if (supportsBrowse) {
        val shelf = runCatching { browse("Adventure") }
        shelf.onSuccess { books ->
            if (firstBook == null) firstBook = books.firstOrNull()
            lines += "shelf \"Adventure\" -> ${books.size}" + books.take(2).joinToString("") { " | ${it.title}" }
        }.onFailure { lines += "shelf \"Adventure\" -> FAILED: ${it.message}" }
    } else {
        lines += "shelves: not supported here (subjects would only be matched against titles)"
    }

    val download = firstBook?.let { book ->
        "${book.title}: " + BookHttp.probe(book.epubUrl, credentials)
    } ?: "nothing to test - no book was returned"

    return lines to download
}

/**
 * Thrown when a catalogue exists but won't talk to us without an account - a library card or a
 * Calibre login. Separate from a generic failure because the fix is completely different: sign in,
 * rather than check your connection.
 *
 * Raised only on a genuine 401 challenge. A bare 403 is *not* this: a server that refuses an
 * unfamiliar client returns 403 with no way to log in, and calling that "you need an account" sends
 * you looking for a sign-up page that doesn't exist. That mistake happened here already.
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

    /** What an OPDS client asks for. Some catalogues serve HTML to anything that doesn't say. */
    private const val ACCEPT_FEED =
        "application/atom+xml, application/atom+xml;profile=opds-catalog, application/xml;q=0.9, */*;q=0.8"

    /**
     * Turns a refusal into the right kind of error.
     *
     * 401 is a real challenge - there is an account to sign in to. 403 usually is not: it is a
     * server declining the client outright, and there is nothing to sign in to. Reporting the
     * second as the first is what made this app tell someone to find a login page that does not
     * exist, so the two stay separate here.
     */
    private fun refusal(conn: HttpURLConnection, code: Int, catalogName: String, url: String): Nothing {
        val challenged = code == 401 || conn.getHeaderField("WWW-Authenticate") != null
        if (challenged) throw CatalogAuthRequired(catalogName)
        throw IOException("HTTP $code (refused, no sign-in offered) from $url")
    }

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
            setRequestProperty("Accept", ACCEPT_FEED)
            if (credentials != null) {
                val raw = "${credentials.username}:${credentials.password}"
                val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) refusal(conn, code, catalogName, urlStr)
            if (code !in 200..299) throw IOException("HTTP $code from $urlStr")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Checks whether a book link would actually download, and says so in one line.
     *
     * Listing a book and being able to read it are different things: a link can 404, redirect to a
     * sign-in page, or serve HTML with an EPUB's file extension. So this reads the first bytes and
     * looks for the ZIP signature every EPUB starts with - the difference between "the catalogue
     * says this book exists" and "this book opens".
     */
    suspend fun probe(
        urlStr: String,
        credentials: Credentials? = null,
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
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
            if (code !in 200..299) return@withContext "HTTP $code"
            val head = ByteArray(4)
            val read = conn.inputStream.use { it.read(head) }
            val isZip = read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            val type = conn.contentType?.substringBefore(';').orEmpty()
            val size = conn.contentLength.takeIf { it > 0 }?.let { " ${it / 1024} KB" }.orEmpty()
            if (isZip) "downloads OK ($type$size)" else "NOT an epub - served $type$size"
        } catch (e: Exception) {
            "download failed: ${e.message.orEmpty().ifBlank { e::class.java.simpleName }}"
        } finally {
            conn.disconnect()
        }
    }
}
