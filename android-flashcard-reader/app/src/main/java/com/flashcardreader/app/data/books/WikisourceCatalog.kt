package com.flashcardreader.app.data.books

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Wikisource, the Wikimedia library of transcribed public-domain texts.
 *
 * Unlike the other catalogues this one is properly multilingual - there is a full Wikisource per
 * language - so it follows the reading language you already chose for definitions. That is the
 * point of including it: real Spanish, French, German or Italian texts to read, rather than an
 * English-only shelf.
 *
 * Search uses the MediaWiki API; EPUBs come from Wikimedia's own export service.
 */
class WikisourceCatalog(private val languageProvider: () -> String) : BookCatalog {

    override val displayName = "Wikisource"

    override val blurb = "Transcribed texts in many languages - follows your reading language."

    override suspend fun diagnose(): CatalogDiagnosis {
        val lang = languageProvider().ifBlank { "en" }
        val endpoint = "https://$lang.wikisource.org/w/api.php (search) + ws-export.wmcloud.org (epub)"
        return try {
            val books = search("")
            val (probes, download) = runStandardProbes()
            CatalogDiagnosis(
                name = "$displayName [$lang]",
                endpoint = endpoint,
                ok = books.isNotEmpty(),
                itemCount = books.size,
                note = if (books.isEmpty()) "reachable but returned nothing" else
                    "search is per-request, so there is no fixed catalogue size",
                sampleTitles = books.take(3).map { it.title },
                probes = probes,
                download = download,
            )
        } catch (e: Exception) {
            CatalogDiagnosis("$displayName [$lang]", endpoint, false, 0, e.message ?: "failed")
        }
    }

    /** The search being continued, and where MediaWiki says the next batch of hits starts. */
    private var lastTerm = DEFAULT_BROWSE
    private var nextOffset: Int? = null

    override suspend fun search(query: String): List<RemoteBook> {
        // A blank query has no "popular" equivalent here, so offer a broad, always-populated seed.
        lastTerm = query.trim().ifEmpty { DEFAULT_BROWSE }
        nextOffset = null
        return fetch(offset = 0)
    }

    override suspend fun more(): List<RemoteBook> {
        // A batch can come back with nothing usable - chapter subpages are dropped, and a search
        // can hit thirty of them in a row. Treating that as the end of the results would strand you
        // partway through a list that still has plenty in it, so skip ahead a little before giving up.
        repeat(MAX_EMPTY_BATCHES) {
            val offset = nextOffset ?: return emptyList()
            val books = fetch(offset)
            if (books.isNotEmpty()) return books
        }
        return emptyList()
    }

    /**
     * One batch of hits. MediaWiki paginates with `sroffset` and reports where to resume in its
     * `continue` block; when that block is absent, the results are exhausted.
     */
    private suspend fun fetch(offset: Int): List<RemoteBook> {
        val lang = languageProvider().ifBlank { "en" }
        val url = "https://$lang.wikisource.org/w/api.php" +
            "?action=query&list=search&format=json&srnamespace=0&srlimit=$LIMIT" +
            "&sroffset=$offset" +
            "&srsearch=${URLEncoder.encode(lastTerm, "UTF-8")}"
        val json = BookHttp.getString(url, catalogName = displayName)
        val root = JSONObject(json)
        nextOffset = root.optJSONObject("continue")?.let { c ->
            if (c.has("sroffset")) c.optInt("sroffset") else null
        }
        val hits = root.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val title = hits.getJSONObject(i).optString("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // Subpages are usually single chapters rather than whole works.
            if (title.contains('/')) return@mapNotNull null
            RemoteBook(
                id = "wikisource:$lang:$title",
                title = title,
                author = "Wikisource · ${lang.uppercase()}",
                epubUrl = "https://ws-export.wmcloud.org/?format=epub&lang=$lang" +
                    "&page=${URLEncoder.encode(title, "UTF-8")}",
                sourceName = displayName,
                language = lang,
            )
        }
    }

    private companion object {
        const val LIMIT = 30

        /** How far to look past batches that were all subpages before calling it the end. */
        const val MAX_EMPTY_BATCHES = 3
        /** Something guaranteed to return results in every language edition. */
        const val DEFAULT_BROWSE = "novel"
    }
}
