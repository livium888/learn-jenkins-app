package com.flashcardreader.app.data.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/** How an OCR run is going, so a slow job can show progress instead of appearing to hang. */
data class OcrProgress(val page: Int, val totalPages: Int)

/**
 * Reads a scanned PDF by rasterising each page and running optical character recognition on it.
 *
 * Until now a scan was a dead end: PDFBox finds no text layer, and the import failed with "there's
 * no text to read". That rules out a large share of what people actually have - old books, library
 * copies, anything photographed rather than typeset.
 *
 * Two deliberate choices:
 *
 *  - **Pages are rasterised with Android's own [PdfRenderer]**, not a library. It has been in the
 *    platform since API 21, it is what the system PDF viewer uses, and it means no second PDF
 *    engine is carried just to turn a page into a bitmap.
 *  - **The language data is downloaded, not bundled.** `eng.traineddata` is around 15 MB, which is
 *    more than twice this entire app; shipping it inside the APK to serve the minority of imports
 *    that need it would be a poor trade. It is fetched once, on request, and kept.
 */
class PdfOcr(private val context: Context) {

    /** True when the language data has already been fetched and OCR can run offline. */
    fun isReady(language: String = DEFAULT_LANGUAGE): Boolean = trainedDataFile(language).exists()

    /**
     * Downloads the recognition data for [language]. Uses the "fast" models: markedly quicker on a
     * phone, and the accuracy difference barely shows on printed book pages.
     */
    suspend fun downloadLanguage(language: String = DEFAULT_LANGUAGE): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = trainedDataFile(language)
                if (target.exists()) return@runCatching
                target.parentFile?.mkdirs()
                val url = URL("$TESSDATA_BASE/$language.traineddata")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Couldn't download $language data (HTTP $code)")
                // Written beside the target and renamed, so an interrupted download can never be
                // mistaken for a complete one on the next run.
                val partial = File(target.parentFile, "${language}.part")
                connection.inputStream.use { input -> partial.outputStream().use(input::copyTo) }
                connection.disconnect()
                if (!partial.renameTo(target)) throw IOException("Couldn't save the $language data")
            }
        }

    /**
     * Runs OCR over every page and returns the recognised text.
     *
     * Slow by nature - seconds per page - so it reports progress and is only ever called after the
     * quick text-layer route has already come up empty.
     */
    suspend fun extract(
        uri: Uri,
        language: String = DEFAULT_LANGUAGE,
        onProgress: (OcrProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        check(isReady(language)) { "Recognition data for $language hasn't been downloaded yet." }

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Could not open the PDF")

        val tess = TessBaseAPI()
        if (!tess.init(tessRoot().absolutePath, language)) {
            tess.end()
            throw IOException("Couldn't start the text recogniser")
        }

        try {
            descriptor.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val pages = renderer.pageCount
                    buildString {
                        for (index in 0 until pages) {
                            onProgress(OcrProgress(index + 1, pages))
                            val page = renderer.openPage(index)
                            val bitmap = page.renderToBitmap()
                            page.close()
                            tess.setImage(bitmap)
                            val text = tess.getUTF8Text().orEmpty()
                            bitmap.recycle()
                            if (text.isNotBlank()) {
                                append(text.trim())
                                append("\n\n")
                            }
                        }
                    }
                }
            }
        } finally {
            tess.end()
        }
    }

    /**
     * Renders a page at a resolution OCR can actually read.
     *
     * Tesseract wants roughly 300 dpi; a PDF page is measured in 72-dpi points, hence the scale.
     * Capped because a large page at full scale is tens of megabytes of bitmap and would run a
     * phone out of memory long before it ran out of pages.
     */
    private fun PdfRenderer.Page.renderToBitmap(): Bitmap {
        val full = OCR_DPI / PDF_POINTS_PER_INCH
        val fullPixels = (width * full) * (height * full)
        // Shrink only if the page would exceed the ceiling, and shrink by the square root so both
        // dimensions come down together rather than the aspect ratio changing.
        val scale = if (fullPixels > MAX_PIXELS) full * sqrt(MAX_PIXELS / fullPixels) else full
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        // A transparent page OCRs as nothing; scans assume white paper.
        bitmap.eraseColor(Color.WHITE)
        render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun tessRoot(): File = File(context.filesDir, "tesseract")

    private fun trainedDataFile(language: String) = File(tessRoot(), "tessdata/$language.traineddata")

    companion object {
        const val DEFAULT_LANGUAGE = "eng"

        /** Roughly how big the download is, so the ask can be honest before it starts. */
        const val APPROX_DOWNLOAD_MB = 15

        private const val TESSDATA_BASE =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main"

        private const val OCR_DPI = 300f
        private const val PDF_POINTS_PER_INCH = 72f

        /** Ceiling on rendered page size, to stay well clear of an out-of-memory kill. */
        private const val MAX_PIXELS = 4_000_000
    }
}
