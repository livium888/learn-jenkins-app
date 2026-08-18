package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashcardreader.app.ai.AiPrefs
import com.flashcardreader.app.ai.GeminiTutor
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.IntervalFormat
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing
import com.flashcardreader.app.ui.TonalButton
import kotlinx.coroutines.launch

/**
 * The reading-interruption / review card - the emotional centre of the app, so it gets the
 * most care: a calm eyebrow label, a large prompt, the book sentence set as a soft quote,
 * a segmented confidence control that never squashes, and a colour-coded 2x2 rating grid
 * whose four choices each show when you'd next see the word.
 *
 * Retrieval cue alternates for interleaving / varied practice:
 *  - Definition recall: "what does X mean?" using the book sentence as context.
 *  - Cloze recall: the book sentence with X blanked out - recall the missing word.
 */
@Composable
fun FlashcardDialog(
    term: Term,
    contextSentence: String = "",
    sourceLabel: String = "",
    remaining: Int? = null,
    onExit: (() -> Unit)? = null,
    /**
     * When true, a correctly *typed* cloze answer earns Focus Gate credit. The self-rating never
     * earns anything - that is the whole point, since tapping "Good" four times in a second is
     * exactly the cheat this closes.
     */
    earnMode: Boolean = false,
    onEarned: (Term) -> Unit = {},
    onAnswered: (Rating, Confidence) -> Unit,
) {
    var revealed by remember(term.id) { mutableStateOf(false) }
    var typed by remember(term.id) { mutableStateOf("") }
    var wasCorrect by remember(term.id) { mutableStateOf<Boolean?>(null) }
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

    // In the reader a due word must be answered before continuing (onExit == null, non-dismissible).
    // In the standalone Review, onExit is provided so back-press, tapping outside, and the ✕ all
    // leave the queue - no trap.
    AppDialog(onDismiss = onExit ?: {}, dismissible = onExit != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = (if (isCloze) "Fill the blank" else "Recall").uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (remaining != null && remaining > 0) {
                Text(
                    "$remaining left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onExit != null) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = "Close review")
                }
            }
        }
        Text(
            text = if (isCloze) "What word is missing?" else "What does “${term.displayText}” mean?",
            style = MaterialTheme.typography.titleLarge,
        )

        // --- The book sentence, set as a soft quote. ---
        val sentence = if (isCloze) cloze else contextSentence.takeIf { it.isNotBlank() }
        if (sentence != null) {
            QuoteCard(sentence)
            if (sourceLabel.isNotBlank()) {
                Text(
                    "You read this in “$sourceLabel”",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!revealed) {
            // A card can only earn when there is something objective to check against: a cloze has
            // exactly one right answer, a free-form definition does not.
            val earning = earnMode && isCloze
            Text(
                when {
                    earning -> "Type the missing word to earn reading time."
                    isCloze -> "Say the missing word, then reveal."
                    else -> "Try to recall it before revealing."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(if (earning) "Your answer" else "Your answer (optional)") },
                singleLine = isCloze,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("How sure are you?", style = MaterialTheme.typography.labelLarge)
            ConfidenceSegmented(selected = confidence, onSelect = { confidence = it })

            PrimaryButton(
                text = if (earning) "Check answer" else "Reveal answer",
                enabled = !earning || typed.isNotBlank(),
                onClick = {
                    if (earning) {
                        val correct = AnswerMatcher.isCorrect(typed, term.displayText)
                        wasCorrect = correct
                        if (correct) onEarned(term)
                    }
                    revealed = true
                },
            )
        } else {
            // --- Revealed answer ---
            if (typed.isNotBlank()) {
                Text(
                    "You wrote: $typed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            wasCorrect?.let { correct ->
                Text(
                    if (correct) "Correct - time banked." else "Not quite - no time banked this once.",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (correct) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (isCloze) {
                Text(
                    term.displayText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (term.definition.isNotBlank()) Text(term.definition, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    term.definition.ifBlank { "(no definition saved yet)" },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (confidence == Confidence.CONFIDENT) {
                Text(
                    "You felt sure — if you were off, this correction will stick harder.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- Optional AI tutor ---
            if (aiReady) {
                AiTutorBlock(
                    result = aiResult,
                    loading = aiLoading,
                    onCheck = {
                        aiLoading = true
                        scope.launch {
                            val r = GeminiTutor.evaluateGuess(context, term.displayText, contextSentence, typed)
                            aiResult = r.getOrElse { "Couldn't reach the AI: ${it.message}" }
                            aiLoading = false
                        }
                    },
                )
            }

            Text("How well did you know it?", style = MaterialTheme.typography.labelLarge)
            // Colour-coded 2x2 grid; each cell shows the next interval so the spaced-repetition
            // consequence is visible before you tap. "Good" is the emphasised, recommended choice.
            val scheme = MaterialTheme.colorScheme
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    RatingCell("Forgot", IntervalFormat.nextLabel(term, Rating.AGAIN), scheme.errorContainer, scheme.onErrorContainer) {
                        onAnswered(Rating.AGAIN, confidence)
                    }
                    RatingCell("Hard", IntervalFormat.nextLabel(term, Rating.HARD), scheme.surfaceVariant, scheme.onSurfaceVariant) {
                        onAnswered(Rating.HARD, confidence)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    RatingCell("Good", IntervalFormat.nextLabel(term, Rating.GOOD), scheme.primary, scheme.onPrimary) {
                        onAnswered(Rating.GOOD, confidence)
                    }
                    RatingCell("Easy", IntervalFormat.nextLabel(term, Rating.EASY), scheme.tertiaryContainer, scheme.onTertiaryContainer) {
                        onAnswered(Rating.EASY, confidence)
                    }
                }
            }
        }
    }
}

/** The book sentence rendered as a calm, indented quote. */
@Composable
private fun QuoteCard(sentence: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "“$sentence”",
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** One cell of the 2x2 self-grade grid: a bold rating word over its next-review interval. */
@Composable
private fun RowScope.RatingCell(
    label: String,
    interval: String,
    container: Color,
    onContainer: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = onContainer,
        modifier = Modifier.weight(1f).heightIn(min = 70.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(interval, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/** A full-width segmented control for the three confidence levels - equal widths, no squash. */
@Composable
private fun ConfidenceSegmented(selected: Confidence, onSelect: (Confidence) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Confidence.values().forEach { level ->
                val active = level == selected
                Surface(
                    onClick = { onSelect(level) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = level.name.lowercase().replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** The "check my guess with AI" affordance and its result / loading state. */
@Composable
private fun AiTutorBlock(result: String?, loading: Boolean, onCheck: () -> Unit) {
    when {
        result != null -> Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            Text("Asking the tutor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> TonalButton(text = "Check my guess with AI", onClick = onCheck)
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
