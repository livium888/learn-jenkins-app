package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
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
) {
    // Shuffled once per showing, so position never gives the answer away and never moves under a
    // finger mid-tap.
    val options = remember(check) { (check.distractors + check.correctAnswer).shuffled() }
    var chosen by remember(check) { mutableStateOf<String?>(null) }
    val answered = chosen != null
    val correct = chosen == check.correctAnswer

    AppDialog(onDismiss = onSkip, dismissible = !answered) {
        Text(
            "WHAT YOU JUST READ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    onClick = { if (!answered) chosen = option },
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
            PrimaryButton(text = "Keep reading", onClick = { onAnswered(correct) })
        } else {
            OutlineButton(text = "Skip", onClick = onSkip)
        }
    }
}

private enum class OptionState { UNANSWERED, CORRECT, WRONG, MUTED }

@Composable
private fun OptionRow(text: String, state: OptionState, onClick: () -> Unit) {
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
            .selectable(selected = state == OptionState.CORRECT, onClick = onClick),
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
