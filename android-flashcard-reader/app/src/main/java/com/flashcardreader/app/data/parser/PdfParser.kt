package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF is fixed-layout, not reflowable, so there is no "correct" paragraph
 * structure to recover - we extract the raw text stream page by page and let
 * the reader reflow it. This works well for novel-like single-column PDFs.
 * Scanned PDFs (image-only, no text layer) and complex multi-column layouts
 * (e.g. academic papers) will extract poorly or come out empty/jumbled; a
 * page-image fallback view for those is a reasonable follow-up but is not
 * implemented here.
 */
class PdfParser : FileDocumentParser {
    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val text = PDFTextStripper().getText(document)
                    ParsedDocument(
                        title = displayName.substringBeforeLast('.'),
                        text = text,
                    )
                }
            } ?: throw IllegalStateException("Could not open $displayName")
        }
}
