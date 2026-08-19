package com.flashcardreader.app.data.books

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A username/password for a catalogue that requires signing in. */
data class Credentials(val username: String, val password: String)

/**
 * Stores per-catalogue logins.
 *
 * These are real account credentials - a library card, a Standard Ebooks patron email, a Calibre
 * server password - so they get the same treatment as the AI key: an AES-256 store wrapped by the
 * Android Keystore, with a fallback to private prefs if the platform can't provide one, and kept
 * out of cloud backup.
 */
class CatalogCredentials(context: Context) {
    private val prefs: SharedPreferences = secure(context) ?: plain(context)

    fun get(feedUrl: String): Credentials? {
        val user = prefs.getString(userKey(feedUrl), null) ?: return null
        if (user.isBlank()) return null
        return Credentials(user, prefs.getString(passKey(feedUrl), "").orEmpty())
    }

    fun put(feedUrl: String, credentials: Credentials) {
        prefs.edit()
            .putString(userKey(feedUrl), credentials.username)
            .putString(passKey(feedUrl), credentials.password)
            .apply()
    }

    fun clear(feedUrl: String) {
        prefs.edit().remove(userKey(feedUrl)).remove(passKey(feedUrl)).apply()
    }

    private fun userKey(url: String) = "user:$url"

    private fun passKey(url: String) = "pass:$url"

    private companion object {
        const val SECURE_FILE = "catalog_credentials_secure"
        const val PLAIN_FILE = "catalog_credentials"

        fun plain(context: Context): SharedPreferences =
            context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

        fun secure(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            null
        }
    }
}
