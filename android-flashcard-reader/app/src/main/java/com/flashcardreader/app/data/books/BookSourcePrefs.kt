package com.flashcardreader.app.data.books

import android.content.Context
import com.flashcardreader.app.data.reference.DictionaryPrefs

/** A catalogue the user added themselves by pasting an OPDS URL. */
data class CustomFeed(val name: String, val url: String)

/**
 * Remembers any OPDS catalogues the user has added. This is what makes the OPDS work pay off:
 * new sources - a public library, a self-hosted Calibre server - need no new code, just a URL.
 */
class BookSourcePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var customFeeds: List<CustomFeed>
        get() = prefs.getStringSet(KEY_FEEDS, emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split(SEPARATOR, limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) CustomFeed(parts[0], parts[1]) else null
        }.sortedBy { it.name.lowercase() }
        set(value) = prefs.edit()
            .putStringSet(
                KEY_FEEDS,
                // The name is sanitised because it shares one string with the URL.
                value.map { "${it.name.replace(SEPARATOR, " ")}$SEPARATOR${it.url}" }.toSet(),
            )
            .apply()

    fun add(feed: CustomFeed) {
        customFeeds = customFeeds.filterNot { it.url == feed.url } + feed
    }

    fun remove(url: String) {
        customFeeds = customFeeds.filterNot { it.url == url }
    }

    private companion object {
        const val PREFS = "book_sources"
        const val KEY_FEEDS = "custom_feeds"
        const val SEPARATOR = "|"
    }
}

/** Assembles the catalogues on offer: the built-in ones plus anything the user added. */
object Catalogs {
    fun all(context: Context): List<BookCatalog> {
        val dictionary = DictionaryPrefs(context)
        val custom = BookSourcePrefs(context).customFeeds
        val logins = CatalogCredentials(context)
        return buildList {
            add(GutenbergCatalog())
            add(OpdsCatalog.standardEbooks { logins.get(OpdsCatalog.STANDARD_EBOOKS_FEEDS.first()) })
            add(WikisourceCatalog { dictionary.readingLanguage })
            custom.forEach { feed ->
                add(OpdsCatalog(feed.name, "Your own catalogue", feed.url) { logins.get(feed.url) })
            }
        }
    }
}

/**
 * Public OPDS catalogues offered as one-tap suggestions when adding a source.
 *
 * These are *not* built in as permanent chips, and the difference is deliberate: they are addresses
 * published for OPDS readers, but nothing here can confirm any of them still answers - so they are
 * offered as a starting point you add and test with the source report, rather than presented as
 * sources the app promises work. Everything listed is public-domain or openly licensed.
 */
object KnownCatalogs {
    data class Suggestion(val name: String, val url: String, val note: String)

    val suggestions = listOf(
        Suggestion(
            "Project Gutenberg (OPDS)",
            "https://m.gutenberg.org/ebooks.opds/",
            "Gutenberg's own feed - a second route in if the search above is timing out.",
        ),
        Suggestion(
            "Feedbooks",
            "https://catalog.feedbooks.com/catalog/index.atom",
            "Public-domain classics, well organised by subject.",
        ),
        Suggestion(
            "Internet Archive",
            "https://bookserver.archive.org/catalog/",
            "The Archive's open-access catalogue. Very large, and mixed in quality.",
        ),
        Suggestion(
            "ManyBooks",
            "https://manybooks.net/opds/index.php",
            "Long-running free-ebook library.",
        ),
        Suggestion(
            "OAPEN",
            "https://library.oapen.org/opds",
            "Open-access academic books, if you want something drier.",
        ),
    )
}
