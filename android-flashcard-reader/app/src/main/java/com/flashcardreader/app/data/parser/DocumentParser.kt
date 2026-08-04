package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap

/** One table-of-contents entry: a chapter/section title anchored at a char offset in the book text. */
data class Chapter(val title: String, val charOffset: Int, val level: Int = 0)

data class ParsedDocument(
    val title: String,
    val text: String,
    /** Chapter anchors for the table of contents + in-scroll chapter dividers. Empty if none recovered. */
    val chapters: List<Chapter> = emptyList(),
)

/**
 * Parses a whole book file (opened via the Storage Access Framework, never
 * copy/paste) into plain reflowable text for the reader + term scanner.
 * Sealed so callers can exhaustively `when` over the exact 3 supported
 * formats (PDF, EPUB, MOBI) without a fallback `else` branch.
 */
sealed interface FileDocumentParser {
    suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument
}

object ParserRegistry {
    private fun extensionOf(context: Context, uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName.isNotEmpty()) return fromName
        val mime = context.contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase() ?: ""
    }

    fun forUri(context: Context, uri: Uri, displayName: String): FileDocumentParser? {
        return when (extensionOf(context, uri, displayName)) {
            "epub" -> EpubParser()
            "pdf" -> PdfParser()
            "mobi", "azw", "azw3" -> MobiParser()
            else -> null
        }
    }
}
