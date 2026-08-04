package com.flashcardreader.app.words

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.TonalButton
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

    AppDialog(onDismiss = onDismiss) {
        Text(term.displayText, style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = definition,
            onValueChange = { definition = it },
            label = { Text("What does it mean?") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = selfNote,
            onValueChange = { selfNote = it },
            label = { Text("Why does this matter to you? (optional)") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Curious about this", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = curious, onCheckedChange = { curious = it })
        }

        if (aiReady) {
            when {
                aiResult != null -> Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(aiResult!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
                aiLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text("Asking the tutor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> TonalButton(text = "Ask AI to check this", onClick = {
                    aiLoading = true
                    aiResult = null
                    scope.launch {
                        val r = GeminiTutor.evaluateGuess(context, term.displayText, "", definition)
                        aiResult = r.getOrElse { "Couldn't reach the AI: ${it.message}" }
                        aiLoading = false
                    }
                })
            }
        }

        PrimaryButton(text = "Save", onClick = { onSave(definition.trim(), selfNote.trim(), curious) })
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}
