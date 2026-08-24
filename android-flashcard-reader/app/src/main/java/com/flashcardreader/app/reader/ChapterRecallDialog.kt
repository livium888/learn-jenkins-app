package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.data.db.entities.ChapterRecallCard
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

/**
 * The pass over a finished chapter: two tasks, both entirely by tapping.
 *
 * **What the chapter said** is the recognition stand-in for Read-Recite-Review's recite step. 3R's
 * own recite is free recall - spoken or written - and substituting recognition is genuinely weaker;
 * the second task is the compensation, because reconstructing a sequence is generative rather than
 * recognitional. Some of the claims are things the chapter never said, which is what makes tapping
 * them all useless: noticing what a text does *not* claim is the standard way the illusion of
 * knowing is measured, and a skimmer cannot do it.
 *
 * **Putting it back in order** is Franklin's exercise from his Autobiography, where he reduced a
 * piece to short hints, jumbled them, and reassembled it weeks later "to teach him method in the
 * arrangement of thoughts". The order is not the model's opinion: each hint was anchored to a
 * sentence quoted from the chapter, and their positions in the text fixed the sequence.
 *
 * Nothing here is self-graded. The rating comes from the two scores - see [PassScore].
 */
@Composable
fun ChapterRecallDialog(
    card: ChapterRecallCard,
    onAnswered: (chosen: Set<Int>, order: List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val claims = remember(card.id) { card.claims }
    val steps = remember(card.id) { card.steps }

    // Shuffled once per showing, and held as indices into the true order so scoring never depends
    // on the text. A chapter whose hints happen to shuffle back into order is not a free pass:
    // indices are what is scored, and the reader still has to leave them where they are.
    val shuffled = remember(card.id) { steps.indices.shuffled().toMutableStateList() }
    val chosen = remember(card.id) { mutableSetOf<Int>().toMutableStateList() }
    var stage by remember(card.id) { mutableStateOf(Stage.CLAIMS) }

    AppDialog(onDismiss = onDismiss, dismissible = stage != Stage.REVIEW) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text(
                card.chapterTitle.ifBlank { "This chapter" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            when (stage) {
                Stage.CLAIMS -> {
                    Text("Which of these did the chapter actually say?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Some of them it never said. Tap the ones it did.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    claims.forEachIndexed { index, claim ->
                        TapRow(
                            text = claim.text,
                            selected = index in chosen,
                            onClick = { if (index in chosen) chosen.remove(index) else chosen.add(index) },
                        )
                    }
                    PrimaryButton(
                        text = "Next",
                        onClick = { stage = if (steps.size > 1) Stage.ORDER else Stage.REVIEW },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Not now")
                    }
                }

                Stage.ORDER -> {
                    Text("Put the chapter back in order", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Move each line until they run the way the chapter did.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    shuffled.forEachIndexed { position, trueIndex ->
                        OrderRow(
                            text = steps.getOrNull(trueIndex)?.text.orEmpty(),
                            position = position,
                            canMoveUp = position > 0,
                            canMoveDown = position < shuffled.size - 1,
                            onMoveUp = {
                                val above = shuffled[position - 1]
                                shuffled[position - 1] = shuffled[position]
                                shuffled[position] = above
                            },
                            onMoveDown = {
                                val below = shuffled[position + 1]
                                shuffled[position + 1] = shuffled[position]
                                shuffled[position] = below
                            },
                        )
                    }
                    PrimaryButton(
                        text = "Done",
                        onClick = { stage = Stage.REVIEW },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Stage.REVIEW -> {
                    Text("How it went", style = MaterialTheme.typography.headlineSmall)
                    // Feedback on every item, with the sentence it came from. Testing without
                    // feedback is worth roughly half as much as testing with it.
                    claims.forEachIndexed { index, claim ->
                        val right = (index in chosen) == claim.said
                        VerdictRow(
                            text = claim.text,
                            verdict = when {
                                claim.said && right -> "the chapter said this"
                                claim.said -> "the chapter did say this"
                                right -> "not in the chapter"
                                else -> "the chapter never said this"
                            },
                            right = right,
                            evidence = claim.evidence,
                        )
                    }
                    if (steps.size > 1) {
                        Text(
                            "The chapter's order",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = Spacing.tight),
                        )
                        steps.forEachIndexed { index, step ->
                            val placedAt = shuffled.indexOf(index)
                            VerdictRow(
                                text = "${index + 1}. ${step.text}",
                                verdict = if (placedAt == index) "where you put it" else "you had it ${placedAt + 1}",
                                right = placedAt == index,
                                evidence = "",
                            )
                        }
                    }
                    PrimaryButton(
                        text = "Finish",
                        onClick = { onAnswered(chosen.toSet(), shuffled.toList()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private enum class Stage { CLAIMS, ORDER, REVIEW }

@Composable
private fun TapRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun OrderRow(
    text: String,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${position + 1}. $text",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Row {
                // Arrows rather than drag: a long-press drag inside a scrolling dialog fights the
                // scroll, and two taps are easier than a precise drag on a phone.
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
            }
        }
    }
}

@Composable
private fun VerdictRow(text: String, verdict: String, right: Boolean, evidence: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight / 2)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Text(
            verdict,
            style = MaterialTheme.typography.labelMedium,
            color = if (right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (evidence.isNotBlank()) {
            Text(
                "“$evidence”",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A quiet note in the reader that a finished chapter has a pass waiting. */
@Composable
fun ChapterPassBanner(title: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = Spacing.tight)) {
                Text(
                    "You finished ${title.ifBlank { "a chapter" }}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "Take the pass over it when you stop reading.",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row {
                TextButton(onClick = onDismiss) { Text("Later") }
                OutlineButton(text = "Take it", onClick = onOpen)
            }
        }
    }
}
