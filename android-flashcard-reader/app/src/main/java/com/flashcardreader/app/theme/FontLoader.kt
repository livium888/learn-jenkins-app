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
 * The result of resolving a reader font.
 *
 * [error] is non-null when the chosen font could not be had and [family] is a stand-in. It exists
 * because the previous version swallowed every failure: picking OpenDyslexic on a phone that could
 * not reach the CDN did nothing at all - no message, no spinner, no way to retry. A setting that
 * appears to be ignored is worse than one that says why.
 */
data class LoadedFont(val family: FontFamily, val error: String? = null)

/**
 * Resolves the FontFamily for a reader font. System fonts return instantly; accessibility fonts
 * (OpenDyslexic, Atkinson Hyperlegible) are downloaded once to app storage on first use and cached
 * thereafter - no bundled font binaries and no Google Play Services, just an HTTP fetch like the
 * rest of the app.
 *
 * Each font names more than one source. They are the same file from independent hosts, so a CDN
 * that is blocked - by a network, a firewall or an outage - costs a retry rather than the feature.
 */
object FontLoader {

    suspend fun familyFor(context: Context, font: ReaderFont): FontFamily = load(context, font).family

    suspend fun load(context: Context, font: ReaderFont): LoadedFont {
        val urls = font.downloadUrls
        if (urls.isEmpty()) return LoadedFont(font.family)
        return withContext(Dispatchers.IO) {
            val file = cacheFile(context, font)
            var problem: String? = null
            if (!file.exists() || file.length() == 0L) {
                for (url in urls) {
                    val outcome = runCatching { download(url, file) }
                    if (outcome.isSuccess && file.length() > 0L) {
                        problem = null
                        break
                    }
                    file.delete()
                    problem = outcome.exceptionOrNull()?.message ?: "download failed"
                }
            }
            if (file.exists() && file.length() > 0L) {
                runCatching { LoadedFont(FontFamily(Font(file))) }.getOrElse {
                    // A truncated or corrupt file would fail identically forever; drop it so the
                    // next attempt fetches again rather than failing the same way for good.
                    file.delete()
                    LoadedFont(font.family, "the downloaded font could not be read")
                }
            } else {
                LoadedFont(font.family, problem ?: "could not be downloaded")
            }
        }
    }

    /** Throws the cached file away so the next load fetches it again. */
    fun forget(context: Context, font: ReaderFont) {
        runCatching { cacheFile(context, font).delete() }
    }

    private fun cacheFile(context: Context, font: ReaderFont): File {
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        return File(dir, "${font.name}.font")
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
