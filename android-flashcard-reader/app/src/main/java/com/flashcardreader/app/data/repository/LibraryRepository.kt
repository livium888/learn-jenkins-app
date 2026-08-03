package com.flashcardreader.app.data.repository

import android.content.Context
import android.net.Uri
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.SourceType
import com.flashcardreader.app.data.parser.EpubParser
import com.flashcardreader.app.data.parser.MobiParser
import com.flashcardreader.app.data.parser.ParserRegistry
import com.flashcardreader.app.data.parser.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    suspend fun delete(source: Source) {
        File(source.textFilePath).delete()
        sourceDao.delete(source.id)
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
        persist(title = parsed.title, type = type, originUri = uri.toString(), text = parsed.text)
    }

    private suspend fun persist(title: String, type: SourceType, originUri: String, text: String): Source {
        val file = File(cacheDir, "${UUID.randomUUID()}.txt")
        file.writeText(text)
        val source = Source(
            title = title,
            type = type,
            originUri = originUri,
            textFilePath = file.absolutePath,
            addedAt = System.currentTimeMillis(),
        )
        val id = sourceDao.insert(source)
        return source.copy(id = id)
    }
}
