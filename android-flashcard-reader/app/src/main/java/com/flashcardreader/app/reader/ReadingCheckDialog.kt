package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

/**
 * Asks one question about the passage just read, answered with a single tap.
 *
 * Multiple choice on purpose. A typed answer would need a second round trip to grade and would put
 * a wait in the middle of someone's reading; a self-rating ("did you get it?") could be tapped
 * through without reading a word, which is the exact failure this whole feature exists to prevent.
 * A tap on the right option out of four is evidence rather than a claim, and it is graded here on
 * the device, instantly.
 *
 * On a wrong answer the correct option is revealed *together with the lines it came from*. A miss
 * is the moment the correction is most likely to stick (the hypercorrection effect), and quoting
 * the book means the correction cites its source rather than asking anyone to take the machine's
 * word for it.
 */
@Composable
fun ReadingCheckDialog(
    check: ReadingCheck,
    onAnswered: (Boolean) -> Unit,
    onSkip: () -> Unit,
    /** How many cards are left in the review queue, when shown from there. */
    remaining: Int? = null,
    /** Throws the question away as a bad one. Absent where there is nothing to throw away. */
    onReject: (() -> Unit)? = null,
    /** Which question of a batch this is, 1-based. Null when it is the only one. */
    position: Int? = null,
    total: Int = 1,
) {
    // Shuffled once per showing, so position never gives the answer away and never moves under a
    // finger mid-tap.
    val options = remember(check) { (check.distractors + check.correctAnswer).shuffled() }
    var chosen by remember(check) { mutableStateOf<String?>(null) }
    val answered = chosen != null

    AppDialog(onDismiss = onSkip, dismissible = !answered) {
        ReadingCheckBody(
            check = check,
            options = options,
            chosen = chosen,
            remaining = remaining,
            onChoose = { chosen = it },
            onContinue = { onAnswered(chosen == check.correctAnswer) },
            onSkip = onSkip,
            onReject = onReject,
            position = position,
            total = total,
        )
    }
}

/**
 * The card's contents, with no dialog around them and no state of its own.
 *
 * Split out so it can be rendered to a PNG on the JVM (see ScreenSnapshotTest): Paparazzi cannot
 * draw a real dialog window, and until now this layout had never been seen by anyone writing it -
 * only described in source and reported back from a phone.
 */
@Composable
internal fun ColumnScope.ReadingCheckBody(
    check: ReadingCheck,
    options: List<String>,
    chosen: String?,
    remaining: Int?,
    onChoose: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onReject: (() -> Unit)? = null,
    position: Int? = null,
    total: Int = 1,
) {
    val answered = chosen != null
    val correct = chosen == check.correctAnswer

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (remaining == null) "WHAT YOU JUST READ" else "FROM YOUR READING",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // A batch says how far through it you are, so three questions in a row read as one
        // interruption with an end in sight rather than an open-ended quiz.
        val counter = when {
            position != null -> "$position of $total"
            remaining != null -> "$remaining left"
            else -> null
        }
        if (counter != null) {
            Text(
                counter,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(check.question, style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
        options.forEach { option ->
            OptionRow(
                text = option,
                state = when {
                    !answered -> OptionState.UNANSWERED
                    option == check.correctAnswer -> OptionState.CORRECT
                    option == chosen -> OptionState.WRONG
                    else -> OptionState.MUTED
                },
                onClick = { if (!answered) onChoose(option) },
                // What the *reader* picked, not what was right. Announcing the correct option as
                // selected told a TalkBack user who got it wrong that they had picked it.
                picked = option == chosen,
            )
        }
    }

    if (answered) {
        Text(
            if (correct) "That's right." else "Not quite — here's where it says so:",
            style = MaterialTheme.typography.bodyMedium,
            color = if (correct) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // Shown on a miss, when it teaches; hidden on a hit, where it would just be clutter.
        if (!correct) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "“${check.evidence}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(Spacing.gap),
                )
            }
        }
        // "Next question" while a batch is still running: "Keep reading" would be a lie, and
        // being told you can go back to the book and then not going back is worse than either.
        PrimaryButton(
            text = if (position != null && position < total) "Next question" else "Keep reading",
            onClick = onContinue,
        )
        // Only offered once the answer has been revealed. Before that there is nothing to judge,
        // and a reject button would just be a second way to skip a question you found hard.
        if (onReject != null) {
            TextButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "This question doesn't work",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        OutlineButton(text = "Skip", onClick = onSkip)
    }
}

/** Renders the card in a fixed state for screenshots, with nothing wired up. */
@Composable
internal fun ReadingCheckPreviewBody(check: ReadingCheck, chosen: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        ReadingCheckBody(
            check = check,
            // Fixed order rather than shuffled: a snapshot that moves every run compares to nothing.
            options = check.distractors + check.correctAnswer,
            chosen = chosen,
            remaining = null,
            onChoose = {},
            onContinue = {},
            onSkip = {},
        )
    }
}

private enum class OptionState { UNANSWERED, CORRECT, WRONG, MUTED }

@Composable
private fun OptionRow(text: String, state: OptionState, onClick: () -> Unit, picked: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val background = when (state) {
        OptionState.UNANSWERED -> colors.surfaceVariant
        OptionState.CORRECT -> colors.primaryContainer
        OptionState.WRONG -> colors.errorContainer
        OptionState.MUTED -> colors.surfaceVariant
    }
    val foreground = when (state) {
        OptionState.UNANSWERED -> colors.onSurface
        OptionState.CORRECT -> colors.onPrimaryContainer
        OptionState.WRONG -> colors.onErrorContainer
        OptionState.MUTED -> colors.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = picked, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.gap, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
