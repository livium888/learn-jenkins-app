package com.flashcardreader.app.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the FontFamily for a reader font. System fonts return instantly; accessibility fonts
 * (OpenDyslexic, Atkinson Hyperlegible) are downloaded once to app storage on first use and
 * cached thereafter - no bundled font binaries and no Google Play Services, just an HTTP fetch
 * like the rest of the app (Gutenberg, dictionary, AI). If a download fails the reader falls back
 * to a clean sans-serif, so it degrades gracefully and never crashes.
 */
object FontLoader {
    suspend fun familyFor(context: Context, font: ReaderFont): FontFamily {
        val url = font.downloadUrl ?: return font.family
        return withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val file = File(dir, "${font.name}.font")
            if (!file.exists() || file.length() == 0L) {
                runCatching { download(url, file) }.onFailure { file.delete() }
            }
            if (file.exists() && file.length() > 0L) {
                runCatching { FontFamily(Font(file)) }.getOrDefault(FontFamily.SansSerif)
            } else {
                FontFamily.SansSerif
            }
        }
    }

    private fun download(urlStr: String, dest: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 40000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
    }
}
