package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Rating

/**
 * The reading interruption: "what does this word mean?" Blocks continuing
 * until answered - self-graded, same as Anki's Again/Hard/Good/Easy, which
 * feeds straight back into the term's FSRS schedule.
 *
 * [contextSentence], when available, is the actual sentence the word was
 * just met in - shown as a retrieval cue before the reveal, not just an
 * isolated word/definition pair. This leans on encoding specificity /
 * context-dependent memory: recall is easier and deeper when the retrieval
 * context echoes the context the word was encountered in.
 */
@Composable
fun FlashcardDialog(term: Term, contextSentence: String = "", onAnswered: (Rating) -> Unit) {
    var revealed by remember(term.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* not dismissible - must answer to keep reading */ },
        title = { Text("What does \"${term.displayText}\" mean?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (contextSentence.isNotBlank()) {
                    Text(
                        text = "“$contextSentence”",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    )
                }
                Text("Try to recall your own definition before revealing it.")
                if (revealed) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(term.definition.ifBlank { "(no definition saved)" })
                }
            }
        },
        confirmButton = {
            if (!revealed) {
                Button(onClick = { revealed = true }) { Text("Reveal") }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Did you know it?", modifier = Modifier.padding(bottom = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onAnswered(Rating.AGAIN) }, modifier = Modifier.fillMaxWidth()) { Text("Forgot it") }
                        OutlinedButton(onClick = { onAnswered(Rating.HARD) }, modifier = Modifier.fillMaxWidth()) { Text("Hard") }
                        OutlinedButton(onClick = { onAnswered(Rating.GOOD) }, modifier = Modifier.fillMaxWidth()) { Text("Good") }
                        OutlinedButton(onClick = { onAnswered(Rating.EASY) }, modifier = Modifier.fillMaxWidth()) { Text("Easy") }
                    }
                }
            }
        },
    )
}
