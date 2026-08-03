package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TxtParser : FileDocumentParser {
    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
                ?: throw IllegalStateException("Could not open $displayName")
            ParsedDocument(title = displayName.substringBeforeLast('.'), text = text)
        }
}
