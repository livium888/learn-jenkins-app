package com.flashcardreader.app.data.repository

import android.content.Context
import android.net.Uri
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.SourceType
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.data.parser.EpubParser
import com.flashcardreader.app.data.parser.MobiParser
import com.flashcardreader.app.data.parser.ParserRegistry
import com.flashcardreader.app.data.parser.PdfParser
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

    suspend fun delete(source: Source) {
        File(source.textFilePath).delete()
        pageCacheFile(source).delete()
        tocFile(source).delete()
        bookmarksFile(source).delete()
        sourceDao.delete(source.id)
    }

    private fun pageCacheFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.pagecache")

    private fun tocFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.toc.json")

    private fun bookmarksFile(source: Source): File =
        File("${source.textFilePath.removeSuffix(".txt")}.bookmarks.json")

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

    /** Imports a book file the user picked via the system file picker (SAF) - never copy/paste. */
    suspend fun importFromFile(uri: Uri, displayName: String): Source = withContext(Dispatchers.IO) {
        val parser = ParserRegistry.forUri(context, uri, displayName)
            ?: throw IllegalArgumentException("Unsupported file type: $displayName (only PDF, EPUB, and MOBI are supported)")
        val parsed = parser.parse(context, uri, displayName)
        val type = when (parser) {
            is EpubParser -> SourceType.EPUB
            is PdfParser -> SourceType.PDF
            is MobiParser -> SourceType.MOBI
        }
        persist(title = parsed.title, type = type, originUri = uri.toString(), text = parsed.text, chapters = parsed.chapters)
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
}
