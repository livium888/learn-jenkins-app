package com.flashcardreader.app.books

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.books.CustomFeed
import com.flashcardreader.app.data.books.KnownCatalogs
import com.flashcardreader.app.data.books.RemoteBook
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

/** Browse and add free books from any of the available catalogues. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
                // Sources that hand over a whole catalogue at once are read once and then answered
                // from memory, so new titles published since would never appear. This asks again.
                IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing) {
                    if (state.refreshing) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for new titles")
                    }
                }
                IconButton(onClick = { viewModel.runDiagnostics() }) {
                    Icon(Icons.Filled.BugReport, contentDescription = "Test every source")
                }
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
            // Say how much this source actually holds. A short list looks the same whether the
            // catalogue is small, the search was narrow, or the read was cut short - and without
            // this, "why is it only showing fifteen books?" cannot be answered from the screen.
            state.matchedTotal?.let { total ->
                Text(
                    if (state.partialRead) {
                        "$total titles so far — some of this catalogue's feeds didn't answer. " +
                            "Tap the bug icon for what happened."
                    } else {
                        "$total titles"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.partialRead) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            }

            // Shelves, not keywords: the way in when you don't already know a title. Shown only
            // where the source really files books by subject - on a catalogue that doesn't, tapping
            // "Adventure" would just look for that word in titles and come back empty, which reads
            // as a broken app rather than an inapplicable control.
            if (state.supportsBrowse) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.screen, vertical = Spacing.tight),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                ) {
                    state.topics.forEach { topic ->
                        FilterChip(
                            selected = topic == state.activeTopic,
                            onClick = { query = ""; viewModel.browseTopic(topic) },
                            label = { Text(topic) },
                        )
                    }
                }
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
                            "The books themselves are free — it's this catalogue's own address that " +
                                "asked who you are. Library and Calibre catalogues use the login you " +
                                "already have with them.",
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
                    // "Not found" with no way forward is what makes browsing feel like guessing,
                    // so offer names that are certain to return something.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                        modifier = Modifier.padding(Spacing.screen),
                    ) {
                        Text(
                            "Nothing matched that.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (state.supportsBrowse) "Pick a shelf above, or start with one of these:"
                            else "This source matches titles and authors. Try one of these:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                        ) {
                            state.suggestedAuthors.forEach { author ->
                                SuggestionChip(
                                    onClick = { query = author; viewModel.search(author) },
                                    label = { Text(author) },
                                )
                            }
                        }
                    }
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
                            isNew = book.id in state.newIds,
                            onDownload = { viewModel.download(book) },
                        )
                    }
                    // Reaching this row is the signal to fetch the next page: scrolling to the
                    // bottom is what "show me more" looks like, and asking for a tap there would
                    // just be a button that says what the scroll already said.
                    item(key = "more") {
                        LaunchedEffect(state.results.size, state.endReached) {
                            if (!state.endReached) viewModel.loadMore()
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(Spacing.gap),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                state.loadingMore -> CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                                state.endReached -> Text(
                                    "That's everything — ${state.results.size} titles",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
            existing = state.customFeeds,
            onRemove = viewModel::removeCustomFeed,
        )
    }

    if (state.diagnosing) {
        AppDialog(onDismiss = {}, dismissible = false) {
            Text("Testing every source…", style = MaterialTheme.typography.titleLarge)
            Text(
                "Running the same searches against every catalogue and trying a real download. " +
                    "Give it a minute — it is doing real work, not pretending.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator()
        }
    }

    state.diagnostics?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = { viewModel.dismissDiagnostics() })
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

/**
 * The result of probing every source, in plain text you can copy out.
 *
 * This exists because the machine these catalogues are wired up on cannot reach any of them, so the
 * only honest way to learn how they behave is to have a real phone ask and report back. Copying the
 * report out beats another round of guessing at what a source returns.
 */
@Composable
private fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AppDialog(onDismiss = onDismiss) {
        Text("Source report", style = MaterialTheme.typography.titleLarge)
        Text(
            "What each catalogue returned for the same searches, and whether the first book really " +
                "downloads. Copy this out if something here is wrong.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        ) {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.gap),
            )
        }
        PrimaryButton(
            text = "Copy report",
            onClick = { clipboard.setText(AnnotatedString(report)) },
        )
        OutlineButton(text = "Close", onClick = onDismiss)
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddCatalogDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit,
    existing: List<CustomFeed>,
    onRemove: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text("Add a catalogue", style = MaterialTheme.typography.titleLarge)
        Text(
            "Paste the address of an OPDS catalogue — the format used by many public libraries, " +
                "Feedbooks, and Calibre if you run your own server. Or start from one of these:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Typing an OPDS URL from memory is nobody's idea of a good time, and a typo looks exactly
        // like a dead catalogue. These fill the fields in; the source report says whether they work.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            KnownCatalogs.suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = {
                        name = suggestion.name
                        url = suggestion.url
                        note = suggestion.note
                    },
                    label = { Text(suggestion.name) },
                )
            }
        }
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Text(
            KnownCatalogs.SELF_HOSTED_HINT,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            text = "Add catalogue",
            enabled = url.isNotBlank(),
            onClick = { onAdd(name, url, user, pass) },
        )

        // Catalogues you have added, and a way to take one away. Without this a feed could be
        // added and never listed or removed - a dead one stayed in the picker for good.
        if (existing.isNotEmpty()) {
            Text("Your catalogues", style = MaterialTheme.typography.labelLarge)
            existing.forEach { feed ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(feed.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            feed.url,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onRemove(feed.url) }) { Text("Remove") }
                }
            }
        }
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}

@Composable
private fun BookRow(
    book: RemoteBook,
    downloading: Boolean,
    added: Boolean,
    isNew: Boolean,
    onDownload: () -> Unit,
) {
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
                // Quiet on purpose: it marks what arrived, it doesn't compete with the title.
                if (isNew) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "New",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
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
