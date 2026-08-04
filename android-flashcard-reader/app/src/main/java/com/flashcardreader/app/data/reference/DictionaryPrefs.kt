package com.flashcardreader.app.data.reference

import android.content.Context

/**
 * Remembers the two languages the free "Look it up" uses:
 *  - [readingLanguage]: the language of the book/word (so a Spanish word is looked up as Spanish),
 *  - [explanationLanguage]: the language the definition should be written in (e.g. English).
 * Together these give a language learner "Spanish word → English explanation". Stored privately.
 */
class DictionaryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)

    var readingLanguage: String
        get() = prefs.getString(KEY_READING, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_READING, value).apply()

    var explanationLanguage: String
        get() = prefs.getString(KEY_EXPLAIN, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_EXPLAIN, value).apply()

    companion object {
        private const val KEY_READING = "reading_lang"
        private const val KEY_EXPLAIN = "explain_lang"

        /** (Wikimedia language code, display name) pairs offered in the pickers. */
        val LANGUAGES = listOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
        )

        fun labelFor(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: "English"
    }
}
