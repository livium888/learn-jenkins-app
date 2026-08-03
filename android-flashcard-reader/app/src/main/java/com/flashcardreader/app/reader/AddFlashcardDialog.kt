package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Tap a word in the reader (or use the "+" button for anything not on screen) and write
 * your own definition here - no copy/paste round trip into another app. If the word is
 * already tracked, [prefilledDefinition] carries its existing definition so this doubles
 * as an edit screen.
 *
 * For a NEW card with a [contextSentence], this frames capture as a *guess from context*
 * rather than a lookup - the pretesting / errorful-generation effect: attempting the
 * meaning yourself first (even if unsure or wrong) measurably improves later memory.
 */
@Composable
fun AddFlashcardDialog(
    prefilledText: String,
    prefilledDefinition: String = "",
    contextSentence: String = "",
    onDismiss: () -> Unit,
    onSave: (term: String, definition: String) -> Unit,
) {
    var term by remember { mutableStateOf(prefilledText) }
    var definition by remember { mutableStateOf(prefilledDefinition) }
    val isNew = prefilledDefinition.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add flashcard" else "Edit flashcard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Word or phrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isNew && contextSentence.isNotBlank()) {
                    Text(
                        text = "“$contextSentence”",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    )
                    Text(
                        "Guess the meaning from context first — attempting it yourself helps it stick.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text(if (isNew && contextSentence.isNotBlank()) "Your guess at the meaning" else "What does it mean? (your own words)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (term.isNotBlank()) onSave(term.trim(), definition.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
