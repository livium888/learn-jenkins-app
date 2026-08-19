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
        return buildList {
            add(GutenbergCatalog())
            add(OpdsCatalog.standardEbooks())
            add(WikisourceCatalog { dictionary.readingLanguage })
            custom.forEach { feed ->
                add(OpdsCatalog(feed.name, "Your own catalogue", feed.url))
            }
        }
    }
}
