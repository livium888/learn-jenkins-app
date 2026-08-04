package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton

private data class ReadingPrompt(val title: String, val question: String, val fieldLabel: String)

/**
 * Rotates among the three highest-leverage "think about what you just read" moves so the same
 * gentle pause trains more than one skill:
 *  - free recall of the prose (the strongest technique in the testing-effect literature),
 *  - elaborative interrogation (connect it to what you already know),
 *  - self-explanation (say why it's true / why it matters).
 * Deliberately reading-first: it rides the existing after-a-stretch nudge rather than adding a
 * separate drill, and it's always skippable.
 */
private val READING_PROMPTS = listOf(
    ReadingPrompt(
        "Quick recall",
        "Without scrolling back: in a sentence or two, what was this last stretch about?",
        "Recall it in your own words",
    ),
    ReadingPrompt(
        "Make a connection",
        "How does what you just read connect to something you already know or have read before?",
        "Draw the connection",
    ),
    ReadingPrompt(
        "Explain it",
        "In your own words, why might this be true — or why does it matter?",
        "Explain your thinking",
    ),
)

@Composable
fun ComprehensionDialog(onDone: () -> Unit) {
    var answer by remember { mutableStateOf("") }
    val prompt = remember { READING_PROMPTS.random() }

    AppDialog(onDismiss = onDone) {
        Text(prompt.title, style = MaterialTheme.typography.titleLarge)
        Text(
            prompt.question,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text(prompt.fieldLabel) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Done", onClick = onDone)
        OutlineButton(text = "Skip", onClick = onDone)
    }
}
