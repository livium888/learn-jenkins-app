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
                    val stripper = PDFTextStripper().apply {
                        // Order text by on-page position, not PDF draw order - fixes jumbled
                        // output on many real-world PDFs (footnotes, headers, loose layouts).
                        sortByPosition = true
                    }
                    val raw = stripper.getText(document)
                    val text = reflow(raw)

                    // Image-only / scanned PDFs have no text layer: PDFBox returns (almost)
                    // nothing. Fail with a clear message instead of opening a blank reader.
                    val pages = document.numberOfPages.coerceAtLeast(1)
                    if (text.length < pages * 2 && text.length < 200) {
                        throw IllegalStateException(
                            "This PDF looks scanned (images with no text layer), so there's no text to read. " +
                                "Try an EPUB, or a PDF exported with real text.",
                        )
                    }

                    ParsedDocument(
                        title = displayName.substringBeforeLast('.'),
                        text = text,
                    )
                }
            } ?: throw IllegalStateException("Could not open $displayName")
        }

    /**
     * Turns PDFBox's per-visual-line output into reflowable prose:
     *  - joins words hyphenated across a line break ("compre-\nhension" -> "comprehension"),
     *  - collapses the single newlines that PDFBox inserts at every line end into spaces,
     *  - keeps blank-line gaps as real paragraph breaks.
     */
    private fun reflow(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val deHyphenated = normalized.replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2")
        // Collapse a single newline (not part of a blank-line paragraph break) into a space.
        val reflowed = deHyphenated.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
        return reflowed.replace(Regex("[ \\t]+"), " ").trim()
    }
}
