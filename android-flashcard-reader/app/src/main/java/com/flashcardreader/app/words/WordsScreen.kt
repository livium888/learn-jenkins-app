package com.flashcardreader.app.words

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.IntervalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen(viewModel: WordsViewModel, onBack: () -> Unit) {
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Term?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Words (${terms.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Text("<") } },
            )
        },
    ) { padding ->
        if (terms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No words yet. While reading, long-press a word to save it as a flashcard.",
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(terms, key = { it.id }) { term ->
                    WordRow(term = term, onEdit = { editing = term }, onDelete = { viewModel.delete(term) })
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { term ->
        EditWordDialog(
            term = term,
            onDismiss = { editing = null },
            onSave = { definition, selfNote, curious ->
                viewModel.updateMeta(term, definition, selfNote, curious)
                editing = null
            },
        )
    }
}

@Composable
private fun WordRow(term: Term, onEdit: () -> Unit, onDelete: () -> Unit) {
    val stats = buildString {
        append(IntervalFormat.dueLabel(term))
        append(" · ${term.reps} ")
        append(if (term.reps == 1) "review" else "reviews")
        if (term.lapses > 0) {
            append(" · ${term.lapses} ")
            append(if (term.lapses == 1) "lapse" else "lapses")
        }
    }
    ListItem(
        headlineContent = { Text(if (term.curious) "★ ${term.displayText}" else term.displayText) },
        supportingContent = {
            Column {
                Text(term.definition.ifBlank { "(no definition - tap to add one)" })
                if (term.selfNote.isNotBlank()) {
                    Text("“${term.selfNote}”", style = MaterialTheme.typography.bodySmall)
                }
                Text(stats, style = MaterialTheme.typography.labelSmall)
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete word") }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    )
}
