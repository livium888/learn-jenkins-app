package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.data.reference.DictionaryClient
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import kotlinx.coroutines.launch

/**
 * Confirm and define a word or phrase captured in the reader (a name/place is selected there by
 * long-pressing and dragging across the words, so it arrives here already whole). If the term is
 * already tracked, [prefilledDefinition] carries its definition so this doubles as an edit screen.
 *
 * Capture is framed as a *guess from context* (pretesting / errorful-generation effect), and a
 * free "Look it up" can seed a definition from Wiktionary/Wikipedia, still editable.
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

    val scope = rememberCoroutineScope()
    var looking by remember { mutableStateOf(false) }
    var lookupNote by remember { mutableStateOf<String?>(null) }

    AppDialog(onDismiss = onDismiss) {
        Text(if (isNew) "Add flashcard" else "Edit flashcard", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            label = { Text("Word or phrase") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isNew && contextSentence.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "“$contextSentence”",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            Text(
                "Guess the meaning from context first — attempting it yourself helps it stick.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = definition,
            onValueChange = { definition = it },
            label = {
                Text(if (isNew && contextSentence.isNotBlank()) "Your guess at the meaning" else "What does it mean? (your own words)")
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (looking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Looking it up…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(
                    onClick = {
                        looking = true
                        lookupNote = null
                        scope.launch {
                            val found = DictionaryClient.lookup(term.trim())
                            if (found != null) {
                                definition = if (found.length > 400) found.take(400).trimEnd() + "…" else found
                            } else {
                                lookupNote = "No definition found — write your own."
                            }
                            looking = false
                        }
                    },
                    enabled = term.isNotBlank(),
                ) { Text("Look it up") }
            }
        }
        lookupNote?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        PrimaryButton(
            text = "Save",
            enabled = term.isNotBlank(),
            onClick = { if (term.isNotBlank()) onSave(term.trim(), definition.trim()) },
        )
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}
