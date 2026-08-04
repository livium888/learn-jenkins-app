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

/**
 * A periodic comprehension check: after a stretch of reading, "in a sentence, what was
 * this about?" Free recall of prose is the single strongest study technique in the
 * testing-effect literature - it trains comprehension, not just vocabulary. Not graded
 * (there's no answer key); the value is the act of retrieval. Skippable so it nudges
 * without derailing the read.
 */
@Composable
fun ComprehensionDialog(onDone: () -> Unit) {
    var recall by remember { mutableStateOf("") }

    AppDialog(onDismiss = onDone) {
        Text("Quick comprehension check", style = MaterialTheme.typography.titleLarge)
        Text(
            "Without scrolling back: in a sentence or two, what was this last stretch about?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = recall,
            onValueChange = { recall = it },
            label = { Text("Recall it in your own words") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Done", onClick = onDone)
        OutlineButton(text = "Skip", onClick = onDone)
    }
}
