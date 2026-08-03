package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB is just a zip of XHTML + an OPF manifest describing reading order.
 * We read the whole archive into memory (fine for typical ebook sizes),
 * resolve the spine (correct page order - NOT just alphabetical zip order),
 * and concatenate each chapter's text with paragraph breaks preserved.
 */
class EpubParser : FileDocumentParser {

    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument =
        withContext(Dispatchers.IO) {
            val entries = readZipEntries(context, uri)

            val containerXml = entries["META-INF/container.xml"]
                ?: throw IllegalStateException("Not a valid EPUB: missing container.xml")
            val opfPath = findOpfPath(containerXml)
            val opfBytes = entries[opfPath]
                ?: throw IllegalStateException("Not a valid EPUB: missing OPF at $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val (title, spineHrefs) = parseOpf(opfBytes)

            val sb = StringBuilder()
            for (href in spineHrefs) {
                val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val chapterBytes = entries[path] ?: continue
                val doc = Jsoup.parse(String(chapterBytes, Charsets.UTF_8))
                doc.select("script, style").remove()
                val chapterText = doc.body()?.text().orEmpty()
                if (chapterText.isNotBlank()) {
                    sb.append(chapterText).append("\n\n")
                }
            }

            ParsedDocument(
                title = title.ifBlank { displayName.substringBeforeLast('.') },
                text = sb.toString().trim(),
            )
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

    /** Hardened against XXE - EPUBs are arbitrary user-supplied files, so external entities/DTDs are disabled. */
    private fun safeDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun findOpfPath(containerXml: ByteArray): String {
        val doc = safeDocumentBuilder().parse(ByteArrayInputStream(containerXml))
        val rootfile = doc.getElementsByTagName("rootfile").item(0) as? Element
            ?: throw IllegalStateException("Not a valid EPUB: missing <rootfile>")
        return rootfile.getAttribute("full-path")
    }

    /** Returns (book title, spine item hrefs in reading order). */
    private fun parseOpf(opfBytes: ByteArray): Pair<String, List<String>> {
        val doc = safeDocumentBuilder().parse(ByteArrayInputStream(opfBytes))

        val title = doc.getElementsByTagName("dc:title").item(0)?.textContent
            ?: doc.getElementsByTagName("title").item(0)?.textContent
            ?: ""

        val manifest = mutableMapOf<String, String>() // id -> href
        val manifestNodes = doc.getElementsByTagName("item")
        for (i in 0 until manifestNodes.length) {
            val el = manifestNodes.item(i) as Element
            manifest[el.getAttribute("id")] = el.getAttribute("href")
        }

        val spineHrefs = mutableListOf<String>()
        val spineNodes = doc.getElementsByTagName("itemref")
        for (i in 0 until spineNodes.length) {
            val el = spineNodes.item(i) as Element
            val idref = el.getAttribute("idref")
            manifest[idref]?.let { spineHrefs.add(it) }
        }

        return title to spineHrefs
    }
}
