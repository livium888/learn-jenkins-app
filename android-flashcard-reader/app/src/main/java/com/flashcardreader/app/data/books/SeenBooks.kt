package com.flashcardreader.app.data.books

import android.content.Context

/**
 * Remembers which books a source was already holding last time you looked, so the ones that turned
 * up since can be pointed out.
 *
 * Two decisions worth stating, because both are the difference between a useful badge and noise:
 *
 *  - **The first visit marks nothing new.** Everything a catalogue holds is new to someone who has
 *    never opened it, and badging all of it says nothing. So the first read is recorded silently
 *    and only later arrivals are called out.
 *  - **Ids are stored as hashes.** A book's id is its download URL, and keeping thousands of those
 *    verbatim would put a few hundred kilobytes of string into preferences for a cosmetic feature.
 *    A 64-bit hash is a fraction of that; the worst a collision can do is fail to badge one book.
 *
 * The comparison itself is a pure function in the companion, so the rules above are covered by
 * tests rather than only observable by living with the app for a week.
 */
class SeenBooks(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns the ids that weren't there last time, and records the current contents as seen.
     *
     * [ids] must be the source's *whole* catalogue, not the handful currently on screen - otherwise
     * a book that arrives while you happen to be searching for something else is quietly marked as
     * seen and never gets its badge.
     */
    fun newSince(catalogName: String, ids: List<String>): Set<String> {
        val key = KEY_PREFIX + catalogName
        val (arrivals, record) = arrivals(prefs.getString(key, null), ids)
        prefs.edit().putString(key, record).apply()
        return arrivals
    }

    /** Forgets a source entirely, so its next read counts as a first visit again. */
    fun forget(catalogName: String) {
        prefs.edit().remove(KEY_PREFIX + catalogName).apply()
    }

    companion object {
        private const val PREFS = "seen_books"
        private const val KEY_PREFIX = "seen:"
        private const val SEPARATOR = ","

        /** Bounds the stored blob. Comfortably past any catalogue this app will crawl. */
        private const val MAX_REMEMBERED = 6_000

        /**
         * Compares a catalogue against what was stored for it, returning the arrivals and the new
         * record to store. A missing [previous] means this source has never been read, which is the
         * one case that reports nothing new.
         */
        fun arrivals(previous: String?, ids: List<String>): Pair<Set<String>, String> {
            val current = LinkedHashMap<String, String>()
            ids.forEach { current.putIfAbsent(fingerprint(it), it) }
            val record = current.keys.take(MAX_REMEMBERED).joinToString(SEPARATOR)
            // An empty read means a source that failed or hasn't been crawled, not a catalogue that
            // emptied itself. Recording it would make every book look new once the source recovers,
            // so the old record stands.
            if (record.isEmpty()) return emptySet<String>() to previous.orEmpty()
            if (previous.isNullOrEmpty()) return emptySet<String>() to record
            val known = previous.split(SEPARATOR).toHashSet()
            return current.filterKeys { it !in known }.values.toSet() to record
        }

        /** FNV-1a, rendered compactly - stable across runs and cheap over a few thousand strings. */
        fun fingerprint(id: String): String {
            var hash = -0x340d631b7bdddcdbL
            for (byte in id.toByteArray(Charsets.UTF_8)) {
                hash = hash xor byte.toLong()
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(36)
        }
    }
}
