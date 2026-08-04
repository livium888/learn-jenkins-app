package com.flashcardreader.app.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.ConfirmDialog
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSource: (Long) -> Unit,
    onOpenReview: () -> Unit,
    onOpenWords: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenGutenberg: () -> Unit,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Source?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            viewModel.importFile(uri, name)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "My Library") {
                IconButton(onClick = { showUrlDialog = true }) { Icon(Icons.Filled.Link, contentDescription = "Add from web") }
                IconButton(onClick = onOpenGutenberg) { Icon(Icons.Filled.CloudDownload, contentDescription = "Free books") }
                IconButton(onClick = onOpenStats) { Icon(Icons.Filled.Info, contentDescription = "Progress") }
                IconButton(onClick = onOpenWords) { Icon(Icons.Filled.List, contentDescription = "My words") }
                IconButton(onClick = onOpenReview) { Icon(Icons.Filled.Refresh, contentDescription = "Review due cards") }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    openDocument.launch(
                        arrayOf(
                            "application/pdf",
                            "application/epub+zip",
                            "application/x-mobipocket-ebook",
                            "*/*",
                        ),
                    )
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add book") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (uiState.importing) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            if (sources.isEmpty() && !uiState.importing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No books yet.\nTap “Add book” to open a PDF, EPUB, or MOBI,\nthe 🔗 icon to save a web article, or the ☁ icon to browse free classics.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.tight + 4.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        SourceCard(source = source, onOpen = { onOpenSource(source.id) }, onDelete = { pendingDelete = source })
                    }
                }
            }
        }
    }

    pendingDelete?.let { source ->
        ConfirmDialog(
            title = "Delete this book?",
            message = "“${source.title}” will be removed from your library. Words you tagged from it are kept.",
            onConfirm = { viewModel.deleteSource(source); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showUrlDialog) {
        AddFromWebDialog(
            onDismiss = { showUrlDialog = false },
            onAdd = { url ->
                showUrlDialog = false
                viewModel.importFromUrl(url)
            },
        )
    }
}

/** Prompts for a web-page URL to fetch, extract into readable text, and save like a book. */
@Composable
private fun AddFromWebDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text("Add from web", style = MaterialTheme.typography.titleLarge)
        Text(
            "Paste a link to an article. We’ll save a clean, readable copy you can read and tag " +
                "offline — just like a book. Works best on normal article pages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = "Save article",
            enabled = url.isNotBlank(),
            onClick = { if (url.isNotBlank()) onAdd(url.trim()) },
        )
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}

@Composable
private fun SourceCard(source: Source, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    source.type.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return uri.lastPathSegment ?: "Untitled"
}
