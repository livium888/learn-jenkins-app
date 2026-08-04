package com.flashcardreader.app.data.reference

import android.content.Context

/**
 * Remembers which language edition of Wiktionary/Wikipedia the free "Look it up" should query,
 * so a reader of French books gets French definitions, etc. Stored in this app's private prefs.
 */
class DictionaryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANG, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    companion object {
        private const val KEY_LANG = "lang"

        /** (Wikimedia language code, display name) pairs offered in the picker. */
        val LANGUAGES = listOf(
            "en" to "English",
            "fr" to "French",
            "es" to "Spanish",
            "de" to "German",
            "it" to "Italian",
        )

        fun labelFor(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: "English"
    }
}
