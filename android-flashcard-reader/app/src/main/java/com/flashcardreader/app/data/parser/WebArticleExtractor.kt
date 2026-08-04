package com.flashcardreader.app.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Turns a web page URL into clean, readable article text - the app's "reader mode". Fetches the
 * page (Jsoup, already a dependency), strips the chrome (nav, ads, sidebars, footers, scripts),
 * finds the main content, and returns a [ParsedDocument] in exactly the shape the file parsers
 * produce, so the rest of the app treats a saved article identically to a book.
 *
 * Best on server-rendered articles (blogs, news, wikis). It can't run JavaScript, so pages that
 * inject their body via JS - or hide it behind a paywall/login - may yield little or no text; the
 * caller turns that into a friendly message.
 */
object WebArticleExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 FlashcardReader/1.0"
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    private const val TIMEOUT_MS = 20_000

    /** Fetches and extracts [url] on the IO dispatcher. Throws on network/parse failure. */
    suspend fun fetch(url: String): ParsedDocument = withContext(Dispatchers.IO) {
        val doc = Jsoup.connect(normalizeUrl(url))
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .maxBodySize(MAX_BODY_BYTES)
            .followRedirects(true)
            .get()
        extract(doc)
    }

    /** Adds https:// when the user pastes a bare host (e.g. "example.com/article"). */
    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    private fun extract(doc: Document): ParsedDocument {
        val title = pickTitle(doc)
        // Drop everything that is chrome, not content, before choosing the main block.
        doc.select("script, style, noscript, nav, aside, header, footer, form, iframe, svg, button, template")
            .remove()

        val root = pickContentRoot(doc)
        val chapters = ArrayList<Chapter>()
        val sb = StringBuilder()
        for (el in root.select("h1, h2, h3, h4, p, li, blockquote, pre")) {
            val text = el.text().trim()
            if (text.isEmpty()) continue
            when (el.tagName()) {
                "h1", "h2", "h3", "h4" -> {
                    // Use headings as table-of-contents anchors, like the MOBI/EPUB parsers.
                    chapters.add(Chapter(title = text, charOffset = sb.length, level = headingLevel(el.tagName())))
                    sb.append(text).append("\n\n")
                }
                "li" -> sb.append("• ").append(text).append('\n')
                else -> sb.append(text).append("\n\n")
            }
        }

        val body = sb.toString().trim()
        // Fallback: if the structured pass found nothing, take the whole (de-chromed) body text.
        val finalText = body.ifBlank { doc.body()?.text()?.trim().orEmpty() }
        return ParsedDocument(title = title, text = finalText, chapters = chapters)
    }

    private fun headingLevel(tag: String): Int = when (tag) {
        "h1" -> 0
        "h2" -> 0
        "h3" -> 1
        else -> 2
    }

    /** Prefers the social-share title, then the page title, then the first heading. */
    private fun pickTitle(doc: Document): String {
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        doc.title().trim().takeIf { it.isNotBlank() }?.let { return it }
        doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return "Web article"
    }

    /**
     * Finds the block most likely to be the article. Prefers the semantic containers most sites
     * expose (<article>, <main>, role=main); otherwise picks the div/section carrying the most
     * paragraph text - a small stand-in for a full readability score.
     */
    private fun pickContentRoot(doc: Document): Element {
        for (selector in listOf("article", "main", "[role=main]")) {
            doc.selectFirst(selector)?.let { if (it.text().length > 200) return it }
        }
        var best: Element? = null
        var bestScore = 0
        for (el in doc.select("div, section")) {
            val paragraphs = el.getElementsByTag("p")
            if (paragraphs.size < 2) continue
            val score = paragraphs.sumOf { it.ownText().length }
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best ?: doc.body() ?: doc
    }
}
