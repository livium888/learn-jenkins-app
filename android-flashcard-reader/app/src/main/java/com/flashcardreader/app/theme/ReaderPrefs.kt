package com.flashcardreader.app.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reader_prefs")

/** Persists the user's typography/theme choices across app restarts. */
class ReaderPrefs(private val context: Context) {
    private object Keys {
        val FONT = stringPreferencesKey("font")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PALETTE = stringPreferencesKey("palette")
        val JUSTIFY = booleanPreferencesKey("justify")
        val MARGIN = floatPreferencesKey("margin_scale")
    }

    val typography: Flow<ReaderTypography> = context.dataStore.data.map { prefs ->
        ReaderTypography(
            font = prefs[Keys.FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
            fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18f,
            lineHeightMultiplier = prefs[Keys.LINE_HEIGHT] ?: 1.5f,
            palette = prefs[Keys.PALETTE]?.let { runCatching { ReaderPalette.valueOf(it) }.getOrNull() } ?: ReaderPalette.LIGHT,
            justify = prefs[Keys.JUSTIFY] ?: true,
            marginScale = prefs[Keys.MARGIN] ?: 1f,
        )
    }

    suspend fun update(typography: ReaderTypography) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT] = typography.font.name
            prefs[Keys.FONT_SIZE] = typography.fontSizeSp
            prefs[Keys.LINE_HEIGHT] = typography.lineHeightMultiplier
            prefs[Keys.PALETTE] = typography.palette.name
            prefs[Keys.JUSTIFY] = typography.justify
            prefs[Keys.MARGIN] = typography.marginScale
        }
    }
}
