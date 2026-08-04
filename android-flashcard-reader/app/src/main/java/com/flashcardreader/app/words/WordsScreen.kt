package com.flashcardreader.app.words

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.IntervalFormat
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen(viewModel: WordsViewModel, onBack: () -> Unit) {
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Term?>(null) }

    Scaffold(
        topBar = { AppTopBar(title = "My Words (${terms.size})", onBack = onBack) },
    ) { padding ->
        if (terms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No words yet.\nWhile reading, long-press a word to save it as a flashcard.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight + 4.dp),
            ) {
                items(terms, key = { it.id }) { term ->
                    WordCard(term = term, onEdit = { editing = term }, onDelete = { viewModel.delete(term) })
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
private fun WordCard(term: Term, onEdit: () -> Unit, onDelete: () -> Unit) {
    val stats = buildString {
        append(IntervalFormat.dueLabel(term))
        append(" · ${term.reps} ")
        append(if (term.reps == 1) "review" else "reviews")
        if (term.lapses > 0) {
            append(" · ${term.lapses} ")
            append(if (term.lapses == 1) "lapse" else "lapses")
        }
    }
    val badges = buildString {
        if (term.curious) append("★ ")
        if (term.hyperMiss) append("⚠ ")
    }

    Surface(
        onClick = onEdit,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$badges${term.displayText}", style = MaterialTheme.typography.titleMedium)
                Text(
                    term.definition.ifBlank { "(no definition — tap to add one)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (term.selfNote.isNotBlank()) {
                    Text(
                        "“${term.selfNote}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stats, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete word", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
