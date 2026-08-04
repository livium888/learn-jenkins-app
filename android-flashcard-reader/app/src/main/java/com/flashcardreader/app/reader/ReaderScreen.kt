package com.flashcardreader.app.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.theme.ReaderColors
import com.flashcardreader.app.theme.colorsFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** What the add/edit-flashcard dialog is currently prefilled with, or null if closed. */
private data class FlashcardPrefill(val term: String, val definition: String, val contextSentence: String = "")

private const val PAGE_CHARS = 1500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val typography = state.typography
    val colors = colorsFor(typography.palette)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var flashcardPrefill by remember { mutableStateOf<FlashcardPrefill?>(null) }
    // Absolute char ranges (into the full book text) of the word being pressed / selected.
    var pressingRange by remember { mutableStateOf<IntRange?>(null) }
    var selectedRange by remember { mutableStateOf<IntRange?>(null) }

    // One-time jump to the resume position once the book has loaded.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.initialChunkIndex > 0) {
            listState.scrollToItem(state.initialChunkIndex)
        }
    }

    fun jumpToOffset(offset: Int) {
        val idx = chunkIndexForOffset(state.chunks, offset)
        scope.launch { listState.scrollToItem(idx) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.source?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search in book")
                    }
                    val bookmarked = viewModel.isBookmarkedNear(state.currentCharOffset)
                    IconButton(onClick = { viewModel.toggleBookmarkAtCurrent() }) {
                        Icon(
                            if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
                        )
                    }
                    if (state.chapters.isNotEmpty() || state.bookmarks.isNotEmpty()) {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Contents and bookmarks")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reading settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { flashcardPrefill = FlashcardPrefill(clipboardText(context), "") },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add flashcard")
            }
        },
        bottomBar = {
            if (!state.loading) {
                ReaderProgressBar(
                    totalChars = state.fullText.length,
                    currentChar = state.currentCharOffset,
                    chapterTitle = currentChapterTitle(state.chapters, state.currentCharOffset),
                    background = colors.background,
                    onColor = colors.text,
                    onScrub = { fraction -> jumpToOffset((fraction * state.fullText.length).toInt()) },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding).background(colors.background))
            return@Scaffold
        }

        val style = TextStyle(
            fontFamily = typography.font.family,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            color = colors.text,
            textAlign = if (typography.justify) TextAlign.Justify else TextAlign.Start,
        )

        // Report the visible chunk range to the ViewModel as the user scrolls, so it can
        // save the reading position and pop a flashcard when a due word scrolls into view.
        LaunchedEffect(listState, state.chunks) {
            snapshotFlow {
                val info = listState.layoutInfo.visibleItemsInfo
                if (info.isEmpty()) null else info.first().index to info.last().index
            }
                .distinctUntilChanged()
                .collect { range -> range?.let { viewModel.onVisibleRange(it.first, it.second) } }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.background),
            contentPadding = PaddingValues(horizontal = typography.horizontalMarginDp.dp, vertical = 24.dp),
        ) {
            itemsIndexed(state.chunks, key = { _, chunk -> chunk.startChar }) { _, chunk ->
                chunk.chapterTitle?.let { ChapterDivider(it, colors) }
                ChunkText(
                    chunk = chunk,
                    style = style,
                    accent = colors.accent,
                    pressing = pressingRange,
                    selected = selectedRange,
                    onPress = { pressingRange = it },
                    onClear = { selectedRange = null },
                    onSelectWord = { word, range ->
                        selectedRange = range
                        val existing = state.terms.find { it.normalizedText == word.lowercase() }
                        val sentence = ContextExtractor.sentenceAround(state.fullText, range.first, range.last + 1)
                        flashcardPrefill = FlashcardPrefill(word, existing?.definition.orEmpty(), sentence)
                    },
                )
            }
        }
    }

    state.pendingFlashcards.firstOrNull()?.let { match ->
        FlashcardDialog(term = match.term, contextSentence = match.contextSentence, onAnswered = viewModel::answerFlashcard)
    }

    if (state.pendingComprehension && state.pendingFlashcards.isEmpty()) {
        ComprehensionDialog(onDone = viewModel::dismissComprehension)
    }

    if (showSettings) {
        ReaderSettingsSheet(
            typography = typography,
            onChange = viewModel::updateTypography,
            onDismiss = { showSettings = false },
        )
    }

    if (showSearch) {
        SearchDialog(
            initialQuery = state.searchQuery,
            results = state.searchResults,
            onSearch = viewModel::search,
            onJump = { offset -> showSearch = false; jumpToOffset(offset) },
            onDismiss = { showSearch = false },
        )
    }

    if (showToc) {
        ContentsDialog(
            chapters = state.chapters,
            bookmarks = state.bookmarks,
            currentOffset = state.currentCharOffset,
            onJump = { offset -> showToc = false; jumpToOffset(offset) },
            onRemoveBookmark = viewModel::removeBookmark,
            onDismiss = { showToc = false },
        )
    }

    flashcardPrefill?.let { prefill ->
        AddFlashcardDialog(
            prefilledText = prefill.term,
            prefilledDefinition = prefill.definition,
            contextSentence = prefill.contextSentence,
            onDismiss = { flashcardPrefill = null },
            onSave = { term, definition ->
                viewModel.createFlashcard(term, definition)
                flashcardPrefill = null
            },
        )
    }
}

/** A calm landmark where a chapter begins: its title over a short accent rule. */
@Composable
private fun ChapterDivider(title: String, colors: ReaderColors) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(40.dp).height(3.dp).background(colors.accent))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
    }
}

/** Bottom bar: current chapter, "page N of M · %", and a scrubber to jump anywhere in the book. */
@Composable
private fun ReaderProgressBar(
    totalChars: Int,
    currentChar: Int,
    chapterTitle: String?,
    background: Color,
    onColor: Color,
    onScrub: (Float) -> Unit,
) {
    val fraction = if (totalChars > 0) (currentChar.toFloat() / totalChars).coerceIn(0f, 1f) else 0f
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown = scrub ?: fraction
    val totalPages = max(1, ceil(totalChars.toDouble() / PAGE_CHARS).toInt())
    val page = ((shown * totalChars) / PAGE_CHARS).toInt() + 1
    val muted = onColor.copy(alpha = 0.7f)

    Surface(color = background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapterTitle.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Page ${page.coerceAtMost(totalPages)} of $totalPages · ${(shown * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
            Slider(
                value = shown,
                onValueChange = { scrub = it },
                onValueChangeFinished = { scrub?.let(onScrub); scrub = null },
            )
        }
    }
}

/**
 * Contents + bookmarks: tap a bookmark or chapter to jump; the current chapter is highlighted,
 * and bookmarks can be removed inline.
 */
@Composable
private fun ContentsDialog(
    chapters: List<Chapter>,
    bookmarks: List<com.flashcardreader.app.data.repository.Bookmark>,
    currentOffset: Int,
    onJump: (Int) -> Unit,
    onRemoveBookmark: (com.flashcardreader.app.data.repository.Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss) {
        if (bookmarks.isNotEmpty()) {
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            bookmarks.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { onJump(b.offset) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "“${b.label}…”",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                        )
                    }
                    IconButton(onClick = { onRemoveBookmark(b) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (chapters.isNotEmpty()) {
            Text("Contents", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            val currentIdx = chapters.indexOfLast { it.charOffset <= currentOffset }
            chapters.forEachIndexed { i, c ->
                val active = i == currentIdx
                Surface(
                    onClick = { onJump(c.charOffset) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        c.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(
                            start = (12 + c.level.coerceIn(0, 3) * 14).dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                    )
                }
            }
        }
    }
}

/** Search-in-book: type a query, run it, then tap any result snippet to jump there. */
@Composable
private fun SearchDialog(
    initialQuery: String,
    results: List<SearchHit>,
    onSearch: (String) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    AppDialog(onDismiss = onDismiss) {
        Text("Search in book", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Find a word or phrase") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Search", enabled = query.isNotBlank(), onClick = { onSearch(query.trim()) })
        if (results.isNotEmpty()) {
            Text(
                "${results.size}${if (results.size >= 300) "+" else ""} matches",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results.forEach { hit ->
                Surface(
                    onClick = { onJump(hit.offset) },
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "…${hit.snippet}…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

/** Last chapter whose start is at or before [offset] - the chapter the reader is currently in. */
private fun currentChapterTitle(chapters: List<Chapter>, offset: Int): String? =
    chapters.lastOrNull { it.charOffset <= offset }?.title

/** Index of the last chunk starting at or before [offset] (binary search; chunks are sorted). */
private fun chunkIndexForOffset(chunks: List<TextChunk>, offset: Int): Int {
    if (chunks.isEmpty()) return 0
    var lo = 0
    var hi = chunks.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (chunks[mid].startChar <= offset) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/**
 * One block of the book. Long-press a word to highlight it and open its flashcard; a plain
 * tap clears the highlight. Highlight ranges arrive as absolute offsets into the full book
 * text and are intersected with this chunk's own slice for rendering.
 */
@Composable
private fun ChunkText(
    chunk: TextChunk,
    style: TextStyle,
    accent: Color,
    pressing: IntRange?,
    selected: IntRange?,
    onPress: (IntRange?) -> Unit,
    onClear: () -> Unit,
    onSelectWord: (word: String, range: IntRange) -> Unit,
) {
    var layout by remember(chunk.startChar) { mutableStateOf<TextLayoutResult?>(null) }

    val annotated = remember(chunk.text, chunk.startChar, pressing, selected, accent) {
        buildAnnotatedString {
            append(chunk.text)
            fun applyAbsolute(range: IntRange?, alpha: Float) {
                if (range == null) return
                val start = (range.first - chunk.startChar).coerceIn(0, chunk.text.length)
                val end = (range.last + 1 - chunk.startChar).coerceIn(0, chunk.text.length)
                if (start < end) addStyle(SpanStyle(background = accent.copy(alpha = alpha)), start, end)
            }
            applyAbsolute(selected, 0.32f)
            applyAbsolute(pressing, 0.18f)
        }
    }

    Text(
        text = annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(chunk.startChar, chunk.text) {
                detectTapGestures(
                    onPress = { offset ->
                        val abs = layout?.let { l ->
                            wordRangeAt(chunk.text, l.getOffsetForPosition(offset))
                                ?.let { (chunk.startChar + it.first)..(chunk.startChar + it.last) }
                        }
                        onPress(abs)
                        tryAwaitRelease()
                        onPress(null)
                    },
                    onTap = { onClear() },
                    onLongPress = { offset ->
                        val local = layout?.let { wordRangeAt(chunk.text, it.getOffsetForPosition(offset)) }
                        if (local != null) {
                            val word = chunk.text.substring(local.first, (local.last + 1).coerceAtMost(chunk.text.length))
                            onSelectWord(word, (chunk.startChar + local.first)..(chunk.startChar + local.last))
                        }
                    },
                )
            },
    )
}

/** The char range (first..last inclusive) of the word at [index], or null on whitespace/punctuation. */
private fun wordRangeAt(text: String, index: Int): IntRange? {
    if (text.isEmpty()) return null
    val i = index.coerceIn(0, text.length - 1)
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''
    if (!isWordChar(text[i])) return null
    var start = i
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = i
    while (end < text.length - 1 && isWordChar(text[end + 1])) end++
    return start..end
}

private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData? = clipboard?.primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
}
