package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Quick comprehension check") },
        text = {
            Column {
                Text("Without scrolling back: in a sentence or two, what was this last stretch about?")
                OutlinedTextField(
                    value = recall,
                    onValueChange = { recall = it },
                    label = { Text("Recall it in your own words") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Skip") } },
    )
}
