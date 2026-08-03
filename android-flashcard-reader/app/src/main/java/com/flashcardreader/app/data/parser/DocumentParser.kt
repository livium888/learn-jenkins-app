package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap

data class ParsedDocument(val title: String, val text: String)

/**
 * Parses a whole book file (opened via the Storage Access Framework, never
 * copy/paste) into plain reflowable text for the reader + term scanner.
 */
interface FileDocumentParser {
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
            "txt", "text" -> TxtParser()
            "epub" -> EpubParser()
            "pdf" -> PdfParser()
            "mobi", "azw", "azw3", "kfx" -> MobiParser()
            else -> null
        }
    }
}
