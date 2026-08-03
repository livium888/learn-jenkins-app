package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Long-press-select a word/phrase in the reader, hit "Add Flashcard", and
 * write your own definition here - no copy/paste round trips into another
 * app. [prefilledText] comes from the current text selection (or the system
 * clipboard if the user used the native "Copy" action first).
 */
@Composable
fun AddFlashcardDialog(
    prefilledText: String,
    onDismiss: () -> Unit,
    onSave: (term: String, definition: String) -> Unit,
) {
    var term by remember { mutableStateOf(prefilledText) }
    var definition by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add flashcard") },
        text = {
            Column {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Word or phrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text("What does it mean? (your own words)") },
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
