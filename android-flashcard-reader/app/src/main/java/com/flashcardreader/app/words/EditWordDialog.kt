package com.flashcardreader.app.words

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.ai.AiPrefs
import com.flashcardreader.app.ai.GeminiTutor
import com.flashcardreader.app.data.db.entities.Term
import kotlinx.coroutines.launch

/**
 * Edit a saved flashcard's answer and its encoding-booster metadata:
 *  - "Why does this matter to you?" -> self-reference effect (strong encoding boost).
 *  - "Curious about this" -> flags the card to ride the curiosity/dopamine memory boost.
 */
@Composable
fun EditWordDialog(
    term: Term,
    onDismiss: () -> Unit,
    onSave: (definition: String, selfNote: String, curious: Boolean) -> Unit,
) {
    var definition by remember(term.id) { mutableStateOf(term.definition) }
    var selfNote by remember(term.id) { mutableStateOf(term.selfNote) }
    var curious by remember(term.id) { mutableStateOf(term.curious) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiReady = remember { AiPrefs(context).isReady }
    var aiLoading by remember(term.id) { mutableStateOf(false) }
    var aiResult by remember(term.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(term.displayText) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text("What does it mean?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = selfNote,
                    onValueChange = { selfNote = it },
                    label = { Text("Why does this matter to you? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Curious about this")
                    Switch(checked = curious, onCheckedChange = { curious = it })
                }

                if (aiReady) {
                    if (!aiLoading) {
                        TextButton(onClick = {
                            aiLoading = true
                            aiResult = null
                            scope.launch {
                                val result = GeminiTutor.evaluateGuess(context, term.displayText, "", definition)
                                aiResult = result.getOrElse { "Couldn't reach the AI: ${it.message}" }
                                aiLoading = false
                            }
                        }) { Text("Ask AI to check this") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                            Text("Asking the tutor…")
                        }
                    }
                    aiResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(definition.trim(), selfNote.trim(), curious) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
