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
 * These are *not* built in as permanent chips, and the difference is deliberate. They are addresses
 * their libraries publish for OPDS readers, but an address is not a promise: feeds move, go behind
 * logins, and quietly stop answering. Nothing in this app can confirm one works until it is tried
 * on your own network - so adding a suggestion tests it and tells you what came back, rather than
 * adding a chip that may turn out to be dead.
 *
 * Everything listed is public-domain or openly licensed. Sites that distribute in-copyright books
 * without permission are deliberately absent and will not be added: the app is built to be run in
 * the open, and a reading habit is not worth building on something that can vanish overnight.
 * Anything not listed here can still be pasted in by hand, including your own Calibre-Web or COPS
 * server, which is the intended route for a library you already own.
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
            "Standard Ebooks",
            "https://standardebooks.org/feeds/opds",
            "Public-domain classics, carefully re-typeset. The best-made free EPUBs there are.",
        ),
        Suggestion(
            "Feedbooks (public domain)",
            "https://catalog.feedbooks.com/catalog/public_domain.atom",
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
        Suggestion(
            "Directory of Open Access Books",
            "https://directory.doabooks.org/feed/opds",
            "Peer-reviewed scholarly books, all open access.",
        ),
        Suggestion(
            "Unglue.it",
            "https://unglue.it/api/opds/",
            "Books whose rights were bought out so they could be given away.",
        ),
        Suggestion(
            "Wolne Lektury",
            "https://wolnelektury.pl/api/opds/",
            "Polish literature, free and openly licensed.",
        ),
    )

    /** The shape of a self-hosted feed, shown as a hint rather than offered as a suggestion. */
    const val SELF_HOSTED_HINT = "Your own library works too - a Calibre-Web server's feed is " +
        "usually http://your-server:8083/opds, and COPS is .../feed.php"
}
