package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.theme.ReaderFont
import com.flashcardreader.app.theme.ReaderPalette
import com.flashcardreader.app.theme.ReaderTypography

/** Typography/theme controls - font, size, line height, light/sepia/dark. */
@Composable
fun ReaderSettingsSheet(
    typography: ReaderTypography,
    onChange: (ReaderTypography) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Font")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.values().forEach { font ->
                        FilterChip(
                            selected = typography.font == font,
                            onClick = { onChange(typography.copy(font = font)) },
                            label = { Text(font.label) },
                        )
                    }
                }

                Text("Text size: ${typography.fontSizeSp.toInt()}sp")
                Slider(
                    value = typography.fontSizeSp,
                    onValueChange = { onChange(typography.copy(fontSizeSp = it)) },
                    valueRange = 12f..28f,
                )

                Text("Line spacing: ×${"%.1f".format(typography.lineHeightMultiplier)}")
                Slider(
                    value = typography.lineHeightMultiplier,
                    onValueChange = { onChange(typography.copy(lineHeightMultiplier = it)) },
                    valueRange = 1.0f..2.2f,
                )

                Text("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderPalette.values().forEach { palette ->
                        FilterChip(
                            selected = typography.palette == palette,
                            onClick = { onChange(typography.copy(palette = palette)) },
                            label = { Text(palette.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        modifier = Modifier.padding(8.dp),
    )
}
