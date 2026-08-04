package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * EPUB is just a zip of XHTML + an OPF manifest describing reading order.
 * We read the whole archive into memory (fine for typical ebook sizes),
 * resolve the spine (correct page order - NOT just alphabetical zip order),
 * and concatenate each chapter's text with paragraph breaks preserved.
 *
 * All XML here (container.xml, the OPF, and the chapters) is parsed with Jsoup
 * rather than javax.xml's DocumentBuilder. Android's built-in DOM parser is
 * inconsistent across OS versions and rejects perfectly valid EPUBs with errors
 * like "the parser does not support specification 'unknown' version '0.0'";
 * Jsoup is lenient about XML declarations / DOCTYPEs / namespaces and never
 * resolves external entities or DTDs, so it also sidesteps XXE entirely.
 */
class EpubParser : FileDocumentParser {

    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument =
        withContext(Dispatchers.IO) {
            val entries = readZipEntries(context, uri)

            val containerXml = entries["META-INF/container.xml"]
                ?: throw IllegalStateException("Not a valid EPUB: missing container.xml")
            val opfPath = findOpfPath(containerXml)
                ?: throw IllegalStateException("Not a valid EPUB: no OPF rootfile in container.xml")
            val opfBytes = entries[opfPath] ?: entries[decode(opfPath)]
                ?: throw IllegalStateException("Not a valid EPUB: missing OPF at $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val (title, spineHrefs) = parseOpf(opfBytes)

            val sb = StringBuilder()
            // cleanHref (relative to opfDir) -> char offset where that file's text starts.
            val spineOffsets = LinkedHashMap<String, Int>()
            // cleanHref -> the file's first heading, a good fallback chapter title.
            val headingByHref = HashMap<String, String>()
            for (href in spineHrefs) {
                val cleanHref = href.substringBefore('#')
                val path = if (opfDir.isEmpty()) cleanHref else "$opfDir/$cleanHref"
                val chapterBytes = entries[path] ?: entries[decode(path)] ?: continue
                val doc = Jsoup.parse(String(chapterBytes, Charsets.UTF_8))
                doc.select("script, style").remove()
                val chapterText = blockText(doc)
                if (chapterText.isNotBlank()) {
                    spineOffsets[cleanHref] = sb.length
                    doc.body()?.selectFirst("h1, h2, h3, h4, h5, h6")?.text()
                        ?.takeIf { it.isNotBlank() }?.let { headingByHref[cleanHref] = it }
                    sb.append(chapterText).append("\n\n")
                }
            }

            val chapters = buildChapters(entries, opfBytes, opfDir, spineOffsets, headingByHref)

            ParsedDocument(
                title = title.ifBlank { displayName.substringBeforeLast('.') },
                text = sb.toString().trim(),
                chapters = chapters,
            )
        }

    /**
     * Recovers the table of contents, preferring the book's own navigation and falling back
     * gracefully so a book without one still gets per-section anchors:
     *  1. EPUB 3 nav document (`<nav epub:type="toc">`), else
     *  2. EPUB 2 NCX (`toc.ncx`), else
     *  3. one entry per spine file, titled by its first heading (or "Section N").
     * Every entry is mapped to the char offset where that file's text begins.
     */
    private fun buildChapters(
        entries: Map<String, ByteArray>,
        opfBytes: ByteArray,
        opfDir: String,
        spineOffsets: Map<String, Int>,
        headingByHref: Map<String, String>,
    ): List<Chapter> {
        if (spineOffsets.isEmpty()) return emptyList()

        // Resolve a TOC href (possibly with an anchor and its own relative base) to a spine offset.
        fun offsetFor(rawSrc: String): Int? {
            val clean = rawSrc.substringBefore('#').substringAfterLast('/')
            if (clean.isEmpty()) return null
            spineOffsets.entries.firstOrNull { it.key.substringAfterLast('/') == clean }?.let { return it.value }
            return null
        }

        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())

        // 1) EPUB 3 nav document.
        val navHref = opf.getElementsByTag("item").firstOrNull {
            it.attr("properties").split(" ").contains("nav")
        }?.attr("href")
        if (!navHref.isNullOrBlank()) {
            val navPath = if (opfDir.isEmpty()) navHref else "$opfDir/$navHref"
            val navBytes = entries[navPath] ?: entries[decode(navPath)]
            if (navBytes != null) {
                val navDoc = Jsoup.parse(String(navBytes, Charsets.UTF_8))
                val links = navDoc.select("nav a[href]").ifEmpty { navDoc.select("a[href]") }
                val chapters = links.mapNotNull { a ->
                    val off = offsetFor(a.attr("href")) ?: return@mapNotNull null
                    a.text().takeIf { it.isNotBlank() }?.let { Chapter(it, off) }
                }
                dedup(chapters)?.let { return it }
            }
        }

        // 2) EPUB 2 NCX.
        val ncxHref = opf.getElementsByTag("item").firstOrNull {
            it.attr("media-type") == "application/x-dtbncx+xml" || it.attr("href").endsWith(".ncx")
        }?.attr("href")
        if (!ncxHref.isNullOrBlank()) {
            val ncxPath = if (opfDir.isEmpty()) ncxHref else "$opfDir/$ncxHref"
            val ncxBytes = entries[ncxPath] ?: entries[decode(ncxPath)]
            if (ncxBytes != null) {
                val ncxDoc = Jsoup.parse(String(ncxBytes, Charsets.UTF_8), "", Parser.xmlParser())
                val chapters = ncxDoc.getElementsByTag("navPoint").mapNotNull { np ->
                    val label = np.selectFirst("navLabel > text")?.text()
                        ?: np.selectFirst("text")?.text() ?: return@mapNotNull null
                    val src = np.selectFirst("content")?.attr("src") ?: return@mapNotNull null
                    val off = offsetFor(src) ?: return@mapNotNull null
                    label.takeIf { it.isNotBlank() }?.let { Chapter(it, off) }
                }
                dedup(chapters)?.let { return it }
            }
        }

        // 3) Fallback: one entry per spine file.
        var n = 0
        val chapters = spineOffsets.entries.map { (href, offset) ->
            n++
            Chapter(headingByHref[href] ?: "Section $n", offset)
        }
        return dedup(chapters) ?: emptyList()
    }

    /** Sort by position, drop duplicate offsets, and require at least 2 anchors to be worth a TOC. */
    private fun dedup(chapters: List<Chapter>): List<Chapter>? {
        val sorted = chapters.sortedBy { it.charOffset }
            .distinctBy { it.charOffset }
        return if (sorted.size >= 2) sorted else null
    }

    private fun readZipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        result[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return result
    }

    /** Path of the OPF package document, read from container.xml's <rootfile full-path="…">. */
    private fun findOpfPath(containerXml: ByteArray): String? {
        val doc = Jsoup.parse(String(containerXml, Charsets.UTF_8), "", Parser.xmlParser())
        return doc.getElementsByTag("rootfile").firstOrNull()
            ?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
    }

    /** Returns (book title, spine item hrefs in reading order). */
    private fun parseOpf(opfBytes: ByteArray): Pair<String, List<String>> {
        val doc = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())

        val title = doc.getElementsByTag("dc:title").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?: doc.getElementsByTag("title").firstOrNull()?.text().orEmpty()

        val manifest = HashMap<String, String>() // id -> href
        for (item in doc.getElementsByTag("item")) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

        val spineHrefs = mutableListOf<String>()
        for (itemref in doc.getElementsByTag("itemref")) {
            manifest[itemref.attr("idref")]?.let { spineHrefs.add(it) }
        }

        return title to spineHrefs
    }

    /** EPUB hrefs may be percent-encoded (e.g. spaces as %20) while zip entry names are literal. */
    private fun decode(path: String): String =
        runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
}

/**
 * Extracts readable text while PRESERVING paragraph breaks: each block element becomes
 * its own paragraph separated by a blank line, instead of Jsoup's flat body().text()
 * that collapses a whole chapter into one wall of text. Falls back to flat text if the
 * chapter has no recognizable block elements. Shared by the EPUB and MOBI parsers.
 */
internal fun blockText(doc: org.jsoup.nodes.Document): String {
    val blocks = doc.body()?.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")
    if (blocks.isNullOrEmpty()) return doc.body()?.text().orEmpty()
    val sb = StringBuilder()
    for (block in blocks) {
        // Skip a block whose text is already fully contained in an ancestor we'll emit
        // (e.g. <blockquote><p>…</p></blockquote>) to avoid duplicating the same prose.
        if (block.parents().any { it.tagName() in BLOCK_TAGS }) continue
        val t = block.text().trim()
        if (t.isNotEmpty()) sb.append(t).append("\n\n")
    }
    val result = sb.toString().trim()
    return result.ifEmpty { doc.body()?.text().orEmpty() }
}

private val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote")
private val HEADING_TAGS = setOf("h1", "h2", "h3")

/**
 * Like [blockText], but also records a [Chapter] at every top-level heading (h1-h3) with its
 * offset into the returned text - used by the MOBI parser (which has no NCX/nav) to recover a
 * table of contents from the book's own headings. Returns (text, chapters); chapters is empty
 * if fewer than two headings are found (not enough for a useful TOC).
 */
internal fun blockTextWithChapters(doc: org.jsoup.nodes.Document): Pair<String, List<Chapter>> {
    val blocks = doc.body()?.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")
    if (blocks.isNullOrEmpty()) return (doc.body()?.text().orEmpty()) to emptyList()
    val sb = StringBuilder()
    val chapters = ArrayList<Chapter>()
    for (block in blocks) {
        if (block.parents().any { it.tagName() in BLOCK_TAGS }) continue
        val t = block.text().trim()
        if (t.isEmpty()) continue
        val tag = block.tagName().lowercase()
        if (tag in HEADING_TAGS) {
            chapters.add(Chapter(t, sb.length, level = tag.removePrefix("h").toIntOrNull() ?: 1))
        }
        sb.append(t).append("\n\n")
    }
    val text = sb.toString().trim()
    if (text.isEmpty()) return (doc.body()?.text().orEmpty()) to emptyList()
    val toc = chapters.distinctBy { it.charOffset }
    return text to (if (toc.size >= 2) toc else emptyList())
}
