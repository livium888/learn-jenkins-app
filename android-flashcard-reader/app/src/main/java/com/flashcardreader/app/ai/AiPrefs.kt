package com.flashcardreader.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings for the optional AI tutor. Bring-your-own-key: the Gemini API key is entered by
 * the user and stored only in this app's private storage on the device - it is never
 * committed to the repo or sent anywhere but Google's API. The tutor is off by default and
 * only ever called on an explicit tap.
 *
 * The key is a credential, so the backing store is an [EncryptedSharedPreferences] file
 * (AES-256, key wrapped by the Android Keystore) instead of plaintext prefs. If the platform
 * can't provide one - some devices/emulators fail keystore init - it falls back to the old
 * private prefs so the tutor never breaks; either way the store is excluded from cloud backup
 * and device-to-device transfer (see res/xml backup rules), which was the main exposure.
 */
class AiPrefs(context: Context) {
    private val prefs: SharedPreferences = securePrefs(context) ?: plainPrefs(context)

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

    /**
     * Whether to send passages you have read to the AI so it can ask you about them.
     *
     * A separate switch from [enabled], and off by default, because it is a different bargain.
     * The word tutor sends one word and one sentence when you tap a button. This sends pages of
     * whatever you are reading, by itself, every few minutes - and some imported files are private
     * documents rather than public-domain novels. Supplying an API key must never be treated as
     * consent to upload books.
     */
    var readingChecks: Boolean
        get() = prefs.getBoolean(KEY_READING_CHECKS, false)
        set(value) = prefs.edit().putBoolean(KEY_READING_CHECKS, value).apply()

    /** Minutes of genuine reading between checks. Interruption is the whole risk, so it's tunable. */
    var readingCheckMinutes: Int
        get() = prefs.getInt(KEY_CHECK_MINUTES, DEFAULT_CHECK_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
        set(value) = prefs.edit().putInt(KEY_CHECK_MINUTES, value.coerceIn(MIN_MINUTES, MAX_MINUTES)).apply()

    /** Books excluded from reading checks, by source id - the per-book opt-out for private files. */
    var excludedSources: Set<Long>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED, value.mapTo(HashSet()) { it.toString() }).apply()

    fun setExcluded(sourceId: Long, excluded: Boolean) {
        excludedSources = if (excluded) excludedSources + sourceId else excludedSources - sourceId
    }

    /** True only when the tutor is switched on AND a key is present. */
    val isReady: Boolean get() = enabled && apiKey.isNotBlank()

    /** True when reading checks may run for this book. */
    fun checksReadyFor(sourceId: Long): Boolean =
        isReady && readingChecks && sourceId !in excludedSources

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "language"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_READING_CHECKS = "reading_checks"
        private const val KEY_CHECK_MINUTES = "reading_check_minutes"
        private const val KEY_EXCLUDED = "reading_check_excluded"

        const val DEFAULT_CHECK_MINUTES = 4
        const val MIN_MINUTES = 2
        const val MAX_MINUTES = 20

        private const val PLAIN_FILE = "ai_prefs"
        private const val SECURE_FILE = "ai_prefs_secure"

        private fun plainPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

        /**
         * The encrypted store, or null if the platform can't build one. On first success it
         * migrates a key left in the old plaintext file, verifying the encrypted write before
         * clearing the plaintext copy so a failure can never lose the user's key.
         */
        private fun securePrefs(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migratePlaintextKey(context, secure)
            secure
        } catch (e: Exception) {
            null
        }

        private fun migratePlaintextKey(context: Context, secure: SharedPreferences) {
            val plain = plainPrefs(context)
            val oldKey = plain.getString(KEY_API, "").orEmpty()
            if (oldKey.isNotBlank() && secure.getString(KEY_API, "").isNullOrBlank()) {
                secure.edit().putString(KEY_API, oldKey).commit()
                if (secure.getString(KEY_API, "") == oldKey) {
                    plain.edit().remove(KEY_API).apply()
                }
            }
        }

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
