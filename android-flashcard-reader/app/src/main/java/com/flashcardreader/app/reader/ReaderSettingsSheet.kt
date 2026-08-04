package com.flashcardreader.app.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.theme.ReaderFont
import com.flashcardreader.app.theme.ReaderPalette
import com.flashcardreader.app.theme.ReaderTypography
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton

/** Typography/theme controls - font, size, line height, light/sepia/dark. */
@Composable
fun ReaderSettingsSheet(
    typography: ReaderTypography,
    onChange: (ReaderTypography) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss) {
        Text("Reading settings", style = MaterialTheme.typography.titleLarge)

        Label("Font")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ReaderFont.values().forEach { font ->
                FilterChip(
                    selected = typography.font == font,
                    onClick = { onChange(typography.copy(font = font)) },
                    label = { Text(font.label) },
                )
            }
        }

        Label("Text size: ${typography.fontSizeSp.toInt()}sp")
        Slider(
            value = typography.fontSizeSp,
            onValueChange = { onChange(typography.copy(fontSizeSp = it)) },
            valueRange = 12f..28f,
        )

        Label("Line spacing: ×${"%.1f".format(typography.lineHeightMultiplier)}")
        Slider(
            value = typography.lineHeightMultiplier,
            onValueChange = { onChange(typography.copy(lineHeightMultiplier = it)) },
            valueRange = 1.0f..2.2f,
        )

        Label("Theme")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ReaderPalette.values().forEach { palette ->
                FilterChip(
                    selected = typography.palette == palette,
                    onClick = { onChange(typography.copy(palette = palette)) },
                    label = { Text(palette.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Justify text", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = typography.justify,
                onCheckedChange = { onChange(typography.copy(justify = it)) },
            )
        }

        Label("Margins: ×${"%.1f".format(typography.marginScale)}")
        Slider(
            value = typography.marginScale,
            onValueChange = { onChange(typography.copy(marginScale = it)) },
            valueRange = 0.5f..2.5f,
        )

        Label("Letter spacing: ${(typography.letterSpacingEm * 100).toInt()}")
        Slider(
            value = typography.letterSpacingEm,
            onValueChange = { onChange(typography.copy(letterSpacingEm = it)) },
            valueRange = 0f..0.2f,
        )

        TextButton(onClick = {
            onChange(
                typography.copy(
                    font = ReaderFont.SANS,
                    justify = false,
                    lineHeightMultiplier = 1.8f,
                    letterSpacingEm = 0.12f,
                    fontSizeSp = maxOf(typography.fontSizeSp, 20f),
                ),
            )
        }) {
            Text("Apply dyslexia-friendly preset")
        }

        val systemBrightness = typography.brightness < 0f
        Label(if (systemBrightness) "Brightness: system" else "Brightness: ${(typography.brightness * 100).toInt()}%")
        Slider(
            value = if (systemBrightness) 0.5f else typography.brightness,
            onValueChange = { onChange(typography.copy(brightness = it.coerceIn(0.05f, 1f))) },
            valueRange = 0.05f..1f,
        )
        if (!systemBrightness) {
            TextButton(onClick = { onChange(typography.copy(brightness = -1f)) }) {
                Text("Use system brightness")
            }
        }

        Label(if (typography.warmth <= 0f) "Night warmth: off" else "Night warmth: ${(typography.warmth * 100).toInt()}%")
        Slider(
            value = typography.warmth,
            onValueChange = { onChange(typography.copy(warmth = it)) },
            valueRange = 0f..1f,
        )

        PrimaryButton(text = "Done", onClick = onDismiss)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}
