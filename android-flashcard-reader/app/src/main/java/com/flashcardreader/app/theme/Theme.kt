package com.flashcardreader.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF3B82F6))
private val DarkColors = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF7FB2F0))

@Composable
fun FlashcardReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
