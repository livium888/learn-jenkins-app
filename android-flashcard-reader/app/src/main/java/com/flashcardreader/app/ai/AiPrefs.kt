package com.flashcardreader.app.ai

import android.content.Context

/**
 * Settings for the optional AI tutor. Bring-your-own-key: the Gemini API key is entered by
 * the user and stored only in this app's private storage on the device - it is never
 * committed to the repo or sent anywhere but Google's API. The tutor is off by default and
 * only ever called on an explicit tap.
 */
class AiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    // Defaults on: providing a key is itself the opt-in, so the tutor is ready as soon as a
    // key exists. The toggle stays as an explicit off switch that keeps the key.
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var myLanguage: String
        get() = prefs.getString(KEY_LANG, DEFAULT_LANGUAGE)!!.ifBlank { DEFAULT_LANGUAGE }
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    var promptTemplate: String
        get() = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT)!!.ifBlank { DEFAULT_PROMPT }
        set(value) = prefs.edit().putString(KEY_PROMPT, value).apply()

    /** True only when the tutor is switched on AND a key is present. */
    val isReady: Boolean get() = enabled && apiKey.isNotBlank()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "language"
        private const val KEY_PROMPT = "prompt"

        const val DEFAULT_MODEL = "gemini-1.5-flash"
        const val DEFAULT_LANGUAGE = "English"

        /** Placeholders {word} {sentence} {my_guess} {my_language} are filled at call time. */
        const val DEFAULT_PROMPT =
            "You are a concise, encouraging vocabulary tutor for someone learning while reading.\n\n" +
                "Word: {word}\n" +
                "Sentence it appeared in: \"{sentence}\"\n" +
                "The learner's guess at its meaning: \"{my_guess}\"\n" +
                "The learner's language: {my_language}\n\n" +
                "First, evaluate their guess in one or two sentences - say what's right and what's " +
                "missing (be kind but precise). Then give the correct meaning of the word AS USED IN " +
                "THIS SENTENCE, briefly. If the word is not in {my_language}, include the {my_language} " +
                "translation. Keep the whole reply short and plain - no headings or lists."
    }
}
