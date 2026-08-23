package com.flashcardreader.app.data.repository

import android.content.Context
import android.net.Uri
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.SourceType
import com.flashcardreader.app.data.books.RemoteBook
import com.flashcardreader.app.data.gutenberg.GutenbergBook
import com.flashcardreader.app.data.gutenberg.GutenbergClient
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.data.parser.EpubParser
import com.flashcardreader.app.data.parser.MobiParser
import com.flashcardreader.app.data.parser.ParserRegistry
import com.flashcardreader.app.data.parser.PdfParser
import com.flashcardreader.app.data.parser.WebArticleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A reader bookmark: a char offset into the book plus a short preview label. */
data class Bookmark(val offset: Int, val label: String, val createdAt: Long)

/**
 * Owns the imported reading library (PDF / EPUB / MOBI only). Every book's
 * parsed plain text is cached once to app-private storage so the original
 * file only needs to be re-read from its Uri at import time - never on every
 * open.
 */
class LibraryRepository(
    private val context: Context,
    private val sourceDao: SourceDao,
) {
    private val cacheDir: File by lazy {
        File(context.filesDir, "sources").apply { mkdirs() }
    }

    fun observeAll(): Flow<List<Source>> = sourceDao.observeAll()

    suspend fun getSource(id: Long): Source? = sourceDao.getById(id)

    /** Every book, once, for screens that need to put titles against ids. */
    suspend fun allSources(): List<Source> = sourceDao.snapshot()

    suspend fun readText(source: Source): String = withContext(Dispatchers.IO) {
        File(source.textFilePath).readText()
    }

    suspend fun updatePosition(source: Source, charOffset: Int) {
        sourceDao.update(source.copy(lastPositionChar = charOffset))
    }

    /** Table-of-contents anchors for a book, read from its sidecar file. Empty if none were recovered. */
    suspend fun readChapters(source: Source): List<Chapter> = withContext(Dispatchers.IO) {
        val file = tocFile(source)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Chapter(o.getString("title"), o.getInt("offset"), o.optInt("level", 0))
            }
        }.getOrDefault(emptyList())
    }

    /** Bookmarks for a book, newest first, read from its sidecar file. */
    suspend fun readBookmarks(source: Source): List<Bookmark> = withContext(Dispatchers.IO) {
        val file = bookmarksFile(source)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.getInt("offset"), o.getString("label"), o.optLong("createdAt", 0L))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveBookmarks(source: Source, bookmarks: List<Bookmark>) = withContext(Dispatchers.IO) {
        val file = bookmarksFile(source)
        if (bookmarks.isEmpty()) {
            file.delete()
            return@withContext
        }
        val arr = JSONArray()
        for (b in bookmarks) {
            arr.put(JSONObject().put("offset", b.offset).put("label", b.label).put("createdAt", b.createdAt))
        }
        file.writeText(arr.toString())
    }

    /**
     * Indices of chunks that have already earned Focus Gate reading credit for this book, so the
     * same page can never be farmed twice. Stored in a sidecar beside the cached text, like the
     * table of contents and bookmarks.
     */
    suspend fun readCreditedChunks(source: Source): Set<Int> = withContext(Dispatchers.IO) {
        val file = creditedFile(source)
        if (!file.exists()) return@withContext emptySet()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun saveCreditedChunks(source: Source, credited: Set<Int>) = withContext(Dispatchers.IO) {
        val file = creditedFile(source)
        if (credited.isEmpty()) {
            file.delete()
            return@withContext
        }
        val arr = JSONArray()
        for (i in credited.sorted()) arr.put(i)
        file.writeText(arr.toString())
    }

    suspend fun delete(source: Source) {
        File(source.textFilePath).delete()
        pageCacheFile(source).delete()
        tocFile(source).delete()
        bookmarksFile(source).delete()
        creditedFile(source).delete()
        sourceDao.delete(source.id)
    }

    private fun pageCacheFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.pagecache")

    private fun tocFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.toc.json")

    private fun bookmarksFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.bookmarks.json")

    private fun creditedFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.credited.json")

    /**
     * Re-paginating a whole book (walking every character, measuring line
     * breaks) is real work - fine once, wasteful every time you reopen the
     * same book with unchanged font/size/line-height/screen size. [signature]
     * identifies that combination; a mismatch (different font size, rotated
     * device, etc.) just means a cache miss, not a correctness problem.
     */
    suspend fun loadCachedPageOffsets(source: Source, signature: String): List<Pair<Int, Int>>? =
        withContext(Dispatchers.IO) {
            val file = pageCacheFile(source)
            if (!file.exists()) return@withContext null
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != signature) return@withContext null
            val offsets = lines.drop(1).mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size != 2) return@mapNotNull null
                val start = parts[0].toIntOrNull() ?: return@mapNotNull null
                val end = parts[1].toIntOrNull() ?: return@mapNotNull null
                start to end
            }
            offsets.takeIf { it.isNotEmpty() }
        }

    suspend fun savePageOffsetsCache(source: Source, signature: String, offsets: List<Pair<Int, Int>>) {
        withContext(Dispatchers.IO) {
            pageCacheFile(source).bufferedWriter().use { writer ->
                writer.write(signature)
                writer.newLine()
                for ((start, end) in offsets) {
                    writer.write("$start,$end")
                    writer.newLine()
                }
            }
        }
    }

    /**
     * Downloads a Project Gutenberg book's EPUB and runs it through the exact same import
     * pipeline as a picked file: the EPUB is fetched to a temp file, parsed (title, text,
     * chapters), cached, and inserted as an ordinary Source.
     */
    suspend fun importFromGutenberg(book: GutenbergBook): Source = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("gutenberg", ".epub", cacheDir)
        try {
            GutenbergClient.download(book, temp)
            importFromFile(Uri.fromFile(temp), "${book.title}.epub")
        } finally {
            temp.delete()
        }
    }

    /**
     * Downloads a book from any of the free catalogues and runs it through the exact same import
     * pipeline as a picked file, so a Standard Ebooks or Wikisource title behaves identically to
     * one you opened yourself - including the size and parse guard-rails.
     */
    suspend fun importFromCatalog(book: RemoteBook): Source = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("catalog", ".epub", cacheDir)
        try {
            GutenbergClient.downloadUrl(book.epubUrl, temp)
            importFromFile(Uri.fromFile(temp), "${book.title}.epub")
        } finally {
            temp.delete()
        }
    }

    /**
     * Imports a book file the user picked via the system file picker (SAF) - never copy/paste.
     *
     * Books are untrusted input parsed by hand-rolled/native decoders, so this guards against a
     * malformed or oversized file taking the app down: the raw file is size-capped before parsing,
     * parse failures (including [OutOfMemoryError]) are turned into a friendly message instead of a
     * crash, and the extracted text is length-capped so one pathological book can't exhaust memory.
     */
    suspend fun importFromFile(uri: Uri, displayName: String): Source = withContext(Dispatchers.IO) {
        val parser = ParserRegistry.forUri(context, uri, displayName)
            ?: throw IllegalArgumentException("Unsupported file type: $displayName (only PDF, EPUB, and MOBI are supported)")

        val sizeBytes = fileSizeBytes(uri)
        if (sizeBytes > MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "That file is too large to import (${sizeBytes / (1024 * 1024)} MB). " +
                    "The limit is ${MAX_FILE_BYTES / (1024 * 1024)} MB.",
            )
        }

        val parsed = try {
            parser.parse(context, uri, displayName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("That file is too large or complex to open on this device.")
        } catch (e: IllegalStateException) {
            // The parsers throw this deliberately, with a message that says what is actually wrong:
            // Kindle DRM, an unsupported MOBI compression scheme, a PDF with no text layer. Those
            // were being replaced with "it may be corrupted", which sends someone off to repair a
            // file that is not broken. Anything a parser states on purpose is passed through.
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't read that file - it may be corrupted or password-protected.")
        }

        // Keep the beginning if a book is absurdly long; the reader ignores chapter anchors past the end.
        val text = if (parsed.text.length > MAX_TEXT_CHARS) parsed.text.substring(0, MAX_TEXT_CHARS) else parsed.text
        val type = when (parser) {
            is EpubParser -> SourceType.EPUB
            is PdfParser -> SourceType.PDF
            is MobiParser -> SourceType.MOBI
        }
        persist(title = parsed.title, type = type, originUri = uri.toString(), text = text, chapters = parsed.chapters)
    }

    /**
     * Imports a web page by URL: fetches it, extracts the readable article ("reader mode"), and
     * saves the text as an ordinary [Source] so it reads exactly like a book. The text is a local
     * snapshot - it stays available offline even if the page later changes or disappears.
     */
    suspend fun importFromUrl(url: String): Source = withContext(Dispatchers.IO) {
        val parsed = try {
            WebArticleExtractor.fetch(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't fetch that page - check the link and your connection.")
        }
        if (parsed.text.isBlank()) {
            throw IllegalStateException(
                "Couldn't find readable text on that page - it may need a login, or load its content with JavaScript.",
            )
        }
        val text = if (parsed.text.length > MAX_TEXT_CHARS) parsed.text.substring(0, MAX_TEXT_CHARS) else parsed.text
        persist(title = parsed.title, type = SourceType.WEB, originUri = url, text = text, chapters = parsed.chapters)
    }

    /** File size in bytes via the content provider, or -1 if it can't be determined. */
    private fun fileSizeBytes(uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private suspend fun persist(
        title: String,
        type: SourceType,
        originUri: String,
        text: String,
        chapters: List<Chapter>,
    ): Source {
        val file = File(cacheDir, "${UUID.randomUUID()}.txt")
        file.writeText(text)
        val source = Source(
            title = title,
            type = type,
            originUri = originUri,
            textFilePath = file.absolutePath,
            addedAt = System.currentTimeMillis(),
        )
        if (chapters.isNotEmpty()) {
            val arr = JSONArray()
            for (c in chapters) {
                arr.put(JSONObject().put("title", c.title).put("offset", c.charOffset).put("level", c.level))
            }
            File("${file.absolutePath.removeSuffix(".txt")}.toc.json").writeText(arr.toString())
        }
        val id = sourceDao.insert(source)
        return source.copy(id = id)
    }

    private companion object {
        /** Reject a source file bigger than this before parsing (guards the hand-rolled decoders). */
        const val MAX_FILE_BYTES = 100L * 1024 * 1024

        /** Cap on extracted characters kept (~24 MB of text); far beyond any normal book. */
        const val MAX_TEXT_CHARS = 12_000_000
    }
}
