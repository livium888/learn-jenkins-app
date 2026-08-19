package com.flashcardreader.app.books

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.books.RemoteBook
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

/** Browse and add free books from any of the available catalogues. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseBooksScreen(viewModel: BrowseBooksViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var showAddCatalog by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val text = state.message ?: state.error
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "Free books", onBack = onBack) {
                IconButton(onClick = { showAddCatalog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a catalogue")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // One chip per catalogue. Adding a source later adds a chip here and nothing else.
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen, vertical = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                state.catalogNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = index == state.selected,
                        onClick = { viewModel.selectCatalog(index) },
                        label = { Text(name) },
                    )
                }
            }
            if (state.blurb.isNotBlank()) {
                Text(
                    state.blurb,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search title or author") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    IconButton(onClick = { viewModel.search(query) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(Spacing.screen),
            )

            when {
                state.loading -> Center { CircularProgressIndicator() }
                state.needsLogin -> Center {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            "This catalogue needs a sign-in",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "The books are still free — the catalogue itself is what's behind an " +
                                "account. Standard Ebooks offers it to Patrons Circle supporters: " +
                                "sign in with your patron email as the username and leave the " +
                                "password blank. Library and Calibre catalogues use their own logins.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        PrimaryButton(text = "Sign in", onClick = { showSignIn = true })
                    }
                }
                state.error != null && state.results.isEmpty() -> Center {
                    Text(
                        state.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                state.results.isEmpty() -> Center {
                    Text(
                        "No books found. Try another title or author.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.tight + 4.dp),
                ) {
                    items(state.results, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            downloading = state.downloadingId == book.id,
                            added = book.id in state.addedIds,
                            onDownload = { viewModel.download(book) },
                        )
                    }
                }
            }
        }
    }

    if (showAddCatalog) {
        AddCatalogDialog(
            onDismiss = { showAddCatalog = false },
            onAdd = { name, url, user, pass ->
                showAddCatalog = false
                viewModel.addCustomFeed(name, url, user, pass)
            },
        )
    }

    if (showSignIn) {
        SignInDialog(
            catalogName = state.catalogNames.getOrNull(state.selected).orEmpty(),
            onDismiss = { showSignIn = false },
            onSignIn = { user, pass ->
                showSignIn = false
                viewModel.signIn(user, pass)
            },
        )
    }
}

/** Basic-auth sign-in for a catalogue that requires an account. */
@Composable
private fun SignInDialog(
    catalogName: String,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text("Sign in to $catalogName", style = MaterialTheme.typography.titleLarge)
        Text(
            "Stored encrypted on this device only, and never included in a backup.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username or email") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (leave blank if none)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Sign in", enabled = user.isNotBlank(), onClick = { onSignIn(user, pass) })
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}

/**
 * Adds any OPDS catalogue by URL. OPDS is the standard many libraries and Calibre speak, so this
 * is how new sources get added without waiting for an app update.
 */
@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text("Add a catalogue", style = MaterialTheme.typography.titleLarge)
        Text(
            "Paste the address of an OPDS catalogue — the format used by many public libraries, " +
                "Standard Ebooks, Feedbooks, and Calibre if you run your own server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("https://…/opds") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (optional)") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = "Add catalogue",
            enabled = url.isNotBlank(),
            onClick = { onAdd(name, url, user, pass) },
        )
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}

@Composable
private fun BookRow(book: RemoteBook, downloading: Boolean, added: Boolean, onDownload: () -> Unit) {
    Surface(
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
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                downloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                added -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "Added",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Add, contentDescription = "Add to library")
                }
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
