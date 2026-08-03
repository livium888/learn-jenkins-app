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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.IntervalFormat
import com.flashcardreader.app.data.fsrs.Rating

/**
 * The reading interruption / review card. Self-graded (Again/Hard/Good/Easy), feeding the
 * term's FSRS schedule.
 *
 * Retrieval cue alternates for interleaving / varied practice, which builds more flexible,
 * transferable memory than a single fixed cue:
 *  - Definition recall: "what does X mean?" using the book sentence as context.
 *  - Cloze recall: the book sentence with X blanked out - recall the missing word.
 * A card with no usable context sentence always uses definition recall.
 */
@Composable
fun FlashcardDialog(term: Term, contextSentence: String = "", onAnswered: (Rating) -> Unit) {
    var revealed by remember(term.id) { mutableStateOf(false) }
    // Effortful retrieval: producing the answer yourself (typing it) before revealing
    // encodes far better than passively recognizing it (generation + production effect).
    // Kept optional - you can still Reveal without typing - so it nudges without blocking.
    var typed by remember(term.id) { mutableStateOf("") }

    // Alternate cue by review count so a given card isn't always the same question type.
    // reps is the count BEFORE this review, so a NEW card (0) starts with definition recall.
    val cloze = remember(term.id, contextSentence) {
        if (term.reps % 2 == 1) clozeSentence(contextSentence, term.displayText) else null
    }
    val isCloze = cloze != null

    AlertDialog(
        onDismissRequest = { /* not dismissible - must answer to keep reading */ },
        title = { Text(if (isCloze) "Fill in the blank" else "What does \"${term.displayText}\" mean?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCloze) {
                    Text(
                        text = "“$cloze”",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    )
                    if (!revealed) {
                        Text("Recall the missing word.")
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Your answer (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (typed.isNotBlank()) Text("You wrote: $typed", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(term.displayText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        if (term.definition.isNotBlank()) Text(term.definition)
                    }
                } else {
                    if (contextSentence.isNotBlank()) {
                        Text(
                            text = "“$contextSentence”",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        )
                    }
                    if (!revealed) {
                        Text("Recall what it means before revealing.")
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Your answer (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (typed.isNotBlank()) Text("You wrote: $typed", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(term.definition.ifBlank { "(no definition saved)" })
                    }
                }
            }
        },
        confirmButton = {
            if (!revealed) {
                Button(onClick = { revealed = true }) { Text("Reveal") }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Did you know it?", modifier = Modifier.padding(bottom = 4.dp))
                    // Each button shows when you'd next see this word if you pick it, so the
                    // spaced-repetition consequence of each rating is visible before tapping.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onAnswered(Rating.AGAIN) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Forgot it  ·  ${IntervalFormat.nextLabel(term, Rating.AGAIN)}")
                        }
                        OutlinedButton(onClick = { onAnswered(Rating.HARD) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Hard  ·  ${IntervalFormat.nextLabel(term, Rating.HARD)}")
                        }
                        OutlinedButton(onClick = { onAnswered(Rating.GOOD) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Good  ·  ${IntervalFormat.nextLabel(term, Rating.GOOD)}")
                        }
                        OutlinedButton(onClick = { onAnswered(Rating.EASY) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Easy  ·  ${IntervalFormat.nextLabel(term, Rating.EASY)}")
                        }
                    }
                }
            }
        },
    )
}

/**
 * Blanks every whole-word occurrence of [word] in [sentence] with an underscore blank,
 * for cloze recall. Returns null if there's no sentence or the word doesn't appear in it
 * (in which case the caller falls back to definition recall).
 */
private fun clozeSentence(sentence: String, word: String): String? {
    if (sentence.isBlank() || word.isBlank()) return null
    val regex = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE)
    if (!regex.containsMatchIn(sentence)) return null
    return regex.replace(sentence, " _____ ").trim()
}
