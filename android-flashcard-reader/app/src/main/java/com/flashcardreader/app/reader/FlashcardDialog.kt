package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flashcardreader.app.ai.AiPrefs
import com.flashcardreader.app.ai.GeminiTutor
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.IntervalFormat
import com.flashcardreader.app.data.fsrs.Rating
import kotlinx.coroutines.launch

/**
 * The reading interruption / review card. Self-graded (Again/Hard/Good/Easy), feeding the
 * term's FSRS schedule.
 *
 * Housed in a roomy, non-dismissible sheet (a wide Surface in a Dialog) rather than a
 * cramped AlertDialog, so the prompt, your answer, the confidence chips, the AI feedback,
 * and the rating buttons all get breathing room in a single scrollable column.
 *
 * Retrieval cue alternates for interleaving / varied practice:
 *  - Definition recall: "what does X mean?" using the book sentence as context.
 *  - Cloze recall: the book sentence with X blanked out - recall the missing word.
 */
@Composable
fun FlashcardDialog(term: Term, contextSentence: String = "", onAnswered: (Rating, Confidence) -> Unit) {
    var revealed by remember(term.id) { mutableStateOf(false) }
    var typed by remember(term.id) { mutableStateOf("") }
    var confidence by remember(term.id) { mutableStateOf(Confidence.UNSURE) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiReady = remember { AiPrefs(context).isReady }
    var aiLoading by remember(term.id) { mutableStateOf(false) }
    var aiResult by remember(term.id) { mutableStateOf<String?>(null) }

    // Adaptive difficulty (85%-rule spirit): easier recognition cue while learning, harder
    // production cue (cloze) once the word is established.
    val useCloze = term.state == CardState.REVIEW && term.reps >= 2
    val cloze = remember(term.id, contextSentence, useCloze) {
        if (useCloze) clozeSentence(contextSentence, term.displayText) else null
    }
    val isCloze = cloze != null

    Dialog(
        onDismissRequest = { /* must answer to continue */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (isCloze) "Fill in the blank" else "What does “${term.displayText}” mean?",
                    style = MaterialTheme.typography.titleLarge,
                )

                // --- Prompt + your answer / the revealed answer ---
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sentence = if (isCloze) cloze else contextSentence.takeIf { it.isNotBlank() }
                    if (sentence != null) {
                        Text(
                            text = "“$sentence”",
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        )
                    }
                    if (!revealed) {
                        Text(
                            if (isCloze) "Recall the missing word." else "Recall what it means before revealing.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Your answer (optional)") },
                            singleLine = isCloze,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (typed.isNotBlank()) {
                            Text("You wrote: $typed", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        if (isCloze) {
                            Text(term.displayText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            if (term.definition.isNotBlank()) Text(term.definition)
                        } else {
                            Text(term.definition.ifBlank { "(no definition saved)" })
                        }
                    }
                }

                // --- Confidence (pre-reveal) / hypercorrection note (post-reveal) ---
                if (!revealed) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How sure are you?", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Confidence.values().forEach { level ->
                                FilterChip(
                                    selected = confidence == level,
                                    onClick = { confidence = level },
                                    label = { Text(level.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                )
                            }
                        }
                    }
                } else if (confidence == Confidence.CONFIDENT) {
                    AssistChip(
                        onClick = {},
                        label = { Text("You felt sure — if you were wrong, this one will stick") },
                    )
                }

                // --- Optional AI tutor: evaluate the guess ---
                if (revealed && aiReady) {
                    if (aiResult == null && !aiLoading) {
                        OutlinedButton(
                            onClick = {
                                aiLoading = true
                                scope.launch {
                                    val result = GeminiTutor.evaluateGuess(context, term.displayText, contextSentence, typed)
                                    aiResult = result.getOrElse { "Couldn't reach the AI: ${it.message}" }
                                    aiLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Check my guess with AI") }
                    }
                    if (aiLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                            Text("Asking the tutor…")
                        }
                    }
                    aiResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }

                // --- Actions ---
                if (!revealed) {
                    Button(
                        onClick = { revealed = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) { Text("Reveal") }
                } else {
                    Text("How well did you know it?", style = MaterialTheme.typography.labelLarge)
                    // Each rating shows when you'd next see the word, so the spaced-repetition
                    // consequence of the choice is visible before you tap.
                    RatingButton("Forgot it", IntervalFormat.nextLabel(term, Rating.AGAIN), filled = true) { onAnswered(Rating.AGAIN, confidence) }
                    RatingButton("Hard", IntervalFormat.nextLabel(term, Rating.HARD)) { onAnswered(Rating.HARD, confidence) }
                    RatingButton("Good", IntervalFormat.nextLabel(term, Rating.GOOD)) { onAnswered(Rating.GOOD, confidence) }
                    RatingButton("Easy", IntervalFormat.nextLabel(term, Rating.EASY)) { onAnswered(Rating.EASY, confidence) }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(label: String, interval: String, filled: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = { Text("$label   ·   $interval") }
    val modifier = Modifier.fillMaxWidth()
    val padding = PaddingValues(vertical = 12.dp)
    if (filled) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) { content() }
    }
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
    return regex.replace(sentence, " _____ ").trim()
}
