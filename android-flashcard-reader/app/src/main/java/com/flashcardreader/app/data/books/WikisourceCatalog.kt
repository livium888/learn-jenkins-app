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

    override suspend fun search(query: String): List<RemoteBook> {
        val lang = languageProvider().ifBlank { "en" }
        // A blank query has no "popular" equivalent here, so offer a broad, always-populated seed.
        val term = query.trim().ifEmpty { DEFAULT_BROWSE }
        val url = "https://$lang.wikisource.org/w/api.php" +
            "?action=query&list=search&format=json&srnamespace=0&srlimit=$LIMIT" +
            "&srsearch=${URLEncoder.encode(term, "UTF-8")}"
        val json = BookHttp.getString(url, catalogName = displayName)
        val hits = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
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
        /** Something guaranteed to return results in every language edition. */
        const val DEFAULT_BROWSE = "novel"
    }
}
