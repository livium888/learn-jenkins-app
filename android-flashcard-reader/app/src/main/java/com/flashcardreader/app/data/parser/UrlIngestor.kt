package com.flashcardreader.app.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Fetches a web page and extracts just the article body, stripping nav bars,
 * ads, footers, etc. This is a light heuristic (largest-text-block scoring),
 * not a full port of Mozilla's Readability - it works well for blogs/news/
 * articles/Wikipedia-style pages but won't help with paywalled or heavily
 * JS-rendered (client-side-only) pages, since we only fetch the raw HTML.
 */
class UrlIngestor(
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun ingest(url: String): ParsedDocument = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch $url: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IllegalStateException("Empty response from $url")
        }
        val doc = Jsoup.parse(html, url)
        val title = doc.title()
        val articleText = extractMainContent(doc)
        ParsedDocument(title = title.ifBlank { url }, text = articleText)
    }

    private fun extractMainContent(doc: Document): String {
        doc.select("script, style, nav, header, footer, aside, form, noscript, iframe").remove()

        // Prefer semantic containers if present and substantial.
        val semanticCandidate = doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
        if (semanticCandidate != null && semanticCandidate.text().length > 200) {
            return semanticCandidate.text()
        }

        // Otherwise score block containers by paragraph text density and pick the best.
        val candidates = doc.select("div, section, article")
        var best: Element? = null
        var bestScore = 0
        for (el in candidates) {
            val paragraphText = el.select("p").eachText().joinToString(" ")
            val score = paragraphText.length
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best?.text() ?: doc.body()?.text().orEmpty()
    }
}
