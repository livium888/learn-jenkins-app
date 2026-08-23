package com.flashcardreader.app.reader

import android.app.Activity
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.theme.FontLoader
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.theme.ReaderColors
import com.flashcardreader.app.theme.colorsFor
import kotlinx.coroutines.delay
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
    val creditSeconds by viewModel.creditBalance.collectAsStateWithLifecycle()
    val typography = state.typography
    val colors = colorsFor(typography.palette)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Resolve the reader font: system fonts are instant; accessibility fonts (OpenDyslexic,
    // Atkinson) download on first use and fall back to sans-serif until ready.
    val fontFamily by produceState(initialValue = typography.font.family, typography.font) {
        value = FontLoader.familyFor(context, typography.font)
    }

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var flashcardPrefill by remember { mutableStateOf<FlashcardPrefill?>(null) }
    // Absolute char ranges (into the full book text) of the word being pressed / selected.
    var pressingRange by remember { mutableStateOf<IntRange?>(null) }
    var selectedRange by remember { mutableStateOf<IntRange?>(null) }
    var autoScroll by remember { mutableStateOf(false) }
    var autoScrollSpeed by remember { mutableStateOf(4f) }

    // Keep the screen awake while reading; restore normal behaviour on leaving.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Apply the chosen brightness override (negative = follow system); restore on leave.
    DisposableEffect(activity, typography.brightness) {
        val window = activity?.window
        if (window != null) {
            val lp = window.attributes
            lp.screenBrightness =
                if (typography.brightness in 0f..1f) typography.brightness
                else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
        onDispose {
            val w = activity?.window
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    // One-time jump to the resume position once the book has loaded.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.initialChunkIndex > 0) {
            listState.scrollToItem(state.initialChunkIndex)
        }
    }

    // Hands-free auto-scroll: advance a few pixels each frame; stop at the end of the book.
    LaunchedEffect(autoScroll, autoScrollSpeed) {
        if (autoScroll) {
            while (true) {
                val consumed = listState.scrollBy(autoScrollSpeed)
                if (consumed == 0f) { autoScroll = false; break }
                delay(16)
            }
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
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Contents and bookmarks")
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
                    autoScroll = autoScroll,
                    onToggleAutoScroll = { autoScroll = !autoScroll },
                    focusEnabled = viewModel.focusEnabled,
                    creditSeconds = creditSeconds,
                    creditIdle = state.creditIdle,
                    speed = autoScrollSpeed,
                    onSpeedChange = { autoScrollSpeed = it },
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
            fontFamily = fontFamily,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            letterSpacing = typography.letterSpacing,
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

        // Focus Gate: measure genuine reading. repeatOnLifecycle(RESUMED) means switching apps or
        // turning the screen off stops accrual for free, and cancelling clears part-read progress.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(listState) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var last = SystemClock.elapsedRealtime()
                try {
                    while (true) {
                        delay(CREDIT_TICK_MS)
                        val now = SystemClock.elapsedRealtime()
                        // Clamp so a coroutine resuming late can't hand over a huge delta.
                        val delta = (now - last).coerceAtMost(CREDIT_TICK_MS * 2)
                        last = now
                        // Read live state: `state` here would be captured stale for the whole loop.
                        val live = viewModel.uiState.value
                        // A Compose dialog keeps the reader RESUMED, so a prompt covering the text
                        // must explicitly stop accrual - as must split-screen beside the blocked app.
                        // Worked out by the same function that decides what to draw, so a question
                        // that is merely prepared can never stop the reading that makes it due.
                        val overlays = readerOverlays(
                            pendingFlashcards = live.pendingFlashcards.size,
                            pendingChecks = live.pendingChecks.size,
                            checkDue = live.checkDue,
                            inMultiWindow = activity?.isInMultiWindowMode == true,
                        )
                        viewModel.onReadingTick(
                            focusedChunk = if (overlays.coversText) -1 else centreChunkIndex(listState),
                            elapsedMs = delta,
                        )
                    }
                } finally {
                    viewModel.onReadingPaused()
                }
            }
        }

        // Only *human* touches count as being present. listState.interactionSource emits for real
        // drags and presses but never for programmatic scrolling, which is exactly the distinction
        // that stops a phone left face-up on auto-scroll from farming credit.
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start || interaction is PressInteraction.Press) {
                    viewModel.onReadingInteraction()
                }
            }
        }

        Box(Modifier.fillMaxSize().padding(padding).background(colors.background)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = typography.horizontalMarginDp.dp, vertical = 24.dp),
            ) {
                itemsIndexed(state.chunks, key = { _, chunk -> chunk.startChar }) { index, chunk ->
                    val chapter = chunk.chapterTitle
                    if (chapter != null) {
                        ChapterDivider(chapter, colors)
                    } else if (index > 0) {
                        // Draw a numbered page-break line wherever the text crosses a page boundary.
                        val prevPage = state.chunks[index - 1].startChar / PAGE_CHARS
                        val thisPage = chunk.startChar / PAGE_CHARS
                        if (thisPage > prevPage) PageBreak(thisPage + 1, colors)
                    }
                    ChunkText(
                        chunk = chunk,
                        style = style,
                        accent = colors.accent,
                        selecting = pressingRange,
                        selected = selectedRange,
                        onSelecting = { viewModel.onReadingInteraction(); pressingRange = it },
                        onClear = { viewModel.onReadingInteraction(); selectedRange = null; pressingRange = null },
                        onSelectPhrase = { phrase, range ->
                            selectedRange = range
                            pressingRange = null
                            val cleaned = phrase.trim()
                            val existing = state.terms.find { it.normalizedText == cleaned.lowercase() }
                            val sentence = ContextExtractor.sentenceAround(state.fullText, range.first, range.last + 1)
                            flashcardPrefill = FlashcardPrefill(cleaned, existing?.definition.orEmpty(), sentence)
                        },
                    )
                }
            }

            // Night warmth: a non-interactive warm overlay that cuts blue light.
            if (typography.warmth > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color(0xFFFF6A00).copy(alpha = (typography.warmth * 0.5f).coerceIn(0f, 0.5f))),
                )
            }
        }
    }

    val overlays = readerOverlays(
        pendingFlashcards = state.pendingFlashcards.size,
        pendingChecks = state.pendingChecks.size,
        checkDue = state.checkDue,
        // Only about what is drawn here; split-screen changes nothing on screen.
        inMultiWindow = false,
    )

    state.pendingFlashcards.firstOrNull()?.takeIf { overlays.showFlashcard }?.let { match ->
        FlashcardDialog(
            term = match.term,
            contextSentence = match.contextSentence,
            onAnswered = viewModel::answerFlashcard,
        )
    }

    // An AI question about the passage just read takes precedence over the generic recall prompt:
    // it asks about the actual text, so the generic one would only be a weaker duplicate.
    val readingCheck = state.pendingChecks.getOrNull(state.checkIndex)
    if (readingCheck != null && overlays.showReadingCheck) {
        ReadingCheckDialog(
            check = readingCheck,
            onAnswered = viewModel::onReadingCheckAnswered,
            onSkip = viewModel::dismissReadingCheck,
            onReject = viewModel::rejectReadingCheck,
            // Only labelled when there is more than one, so a single question stays unadorned.
            position = if (state.pendingChecks.size > 1) state.checkIndex + 1 else null,
            total = state.pendingChecks.size,
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            typography = typography,
            onChange = viewModel::updateTypography,
            onDismiss = { showSettings = false },
            sourceId = state.source?.id ?: 0L,
            readingCheckReport = viewModel::readingCheckReport,
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

/** A visual page break: a thin rule across the column with the page number centered on it. */
@Composable
private fun PageBreak(pageNumber: Int, colors: ReaderColors) {
    val line = colors.text.copy(alpha = 0.18f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            pageNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
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

/**
 * Bottom bar: an auto-scroll play/pause, the current chapter, "page N of M · %", and a scrubber
 * to jump anywhere in the book. While auto-scrolling, a speed slider appears.
 */
@Composable
private fun ReaderProgressBar(
    totalChars: Int,
    currentChar: Int,
    chapterTitle: String?,
    background: Color,
    onColor: Color,
    autoScroll: Boolean,
    onToggleAutoScroll: () -> Unit,
    focusEnabled: Boolean,
    creditSeconds: Long,
    creditIdle: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onScrub: (Float) -> Unit,
) {
    val fraction = if (totalChars > 0) (currentChar.toFloat() / totalChars).coerceIn(0f, 1f) else 0f
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown = scrub ?: fraction
    val totalPages = max(1, ceil(totalChars.toDouble() / PAGE_CHARS).toInt())
    val page = ((shown * totalChars) / PAGE_CHARS).toInt() + 1
    val muted = onColor.copy(alpha = 0.7f)

    Surface(color = background) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleAutoScroll) {
                    Icon(
                        if (autoScroll) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (autoScroll) "Pause auto-scroll" else "Start auto-scroll",
                        tint = if (autoScroll) MaterialTheme.colorScheme.primary else muted,
                    )
                }
                Text(
                    chapterTitle.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (focusEnabled) {
                    // The whole point of earning is being able to watch it happen, so the balance
                    // lives here, where the reading is - not buried in a settings screen.
                    Text(
                        if (creditIdle) "paused · tap to resume" else "${creditSeconds / 60} min banked",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (creditIdle) muted else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Text(
                    "Page ${page.coerceAtMost(totalPages)} of $totalPages · ${(shown * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
            if (autoScroll) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", style = MaterialTheme.typography.labelSmall, color = muted)
                    Slider(
                        value = speed,
                        onValueChange = onSpeedChange,
                        valueRange = 1f..14f,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
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
        if (chapters.isEmpty() && bookmarks.isEmpty()) {
            Text("Contents", style = MaterialTheme.typography.titleLarge)
            Text(
                "This book didn't include a chapter list, and you haven't bookmarked anything yet. " +
                    "Tap the bookmark icon while reading to save a spot here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
 * One block of the book. Selection works like native text selection: long-press a word to grab
 * it, then keep your finger down and drag across adjacent words to extend the highlight over a
 * whole name or phrase; lift to tag it. A plain tap clears the highlight. [selecting] is the live
 * highlight during a drag; [selected] is the committed one. Ranges are absolute offsets into the
 * full book text and are intersected with this chunk's own slice for rendering.
 */
@Composable
private fun ChunkText(
    chunk: TextChunk,
    style: TextStyle,
    accent: Color,
    selecting: IntRange?,
    selected: IntRange?,
    onSelecting: (IntRange?) -> Unit,
    onClear: () -> Unit,
    onSelectPhrase: (text: String, range: IntRange) -> Unit,
) {
    var layout by remember(chunk.startChar) { mutableStateOf<TextLayoutResult?>(null) }
    // Word ranges (local to this chunk) anchoring the current drag selection.
    var anchor by remember(chunk.startChar, chunk.text) { mutableStateOf<IntRange?>(null) }
    var cursor by remember(chunk.startChar, chunk.text) { mutableStateOf<IntRange?>(null) }

    fun abs(local: IntRange) = (chunk.startChar + local.first)..(chunk.startChar + local.last)
    fun wordAt(offset: Offset): IntRange? = layout?.let { wordRangeAt(chunk.text, it.getOffsetForPosition(offset)) }

    val annotated = remember(chunk.text, chunk.startChar, selecting, selected, accent) {
        buildAnnotatedString {
            append(chunk.text)
            fun applyAbsolute(range: IntRange?, alpha: Float) {
                if (range == null) return
                val start = (range.first - chunk.startChar).coerceIn(0, chunk.text.length)
                val end = (range.last + 1 - chunk.startChar).coerceIn(0, chunk.text.length)
                if (start < end) addStyle(SpanStyle(background = accent.copy(alpha = alpha)), start, end)
            }
            applyAbsolute(selected, 0.32f)
            applyAbsolute(selecting, 0.24f)
        }
    }

    Text(
        text = annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(chunk.startChar, chunk.text) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val w = wordAt(offset)
                        anchor = w
                        cursor = w
                        if (w != null) onSelecting(abs(w))
                    },
                    onDrag = { change, _ ->
                        val a = anchor
                        val w = wordAt(change.position)
                        if (a != null && w != null) {
                            cursor = w
                            onSelecting(abs(minOf(a.first, w.first)..maxOf(a.last, w.last)))
                        }
                    },
                    onDragEnd = {
                        val a = anchor
                        val c = cursor
                        if (a != null && c != null) {
                            val lo = minOf(a.first, c.first)
                            val hi = maxOf(a.last, c.last)
                            val text = chunk.text.substring(lo, (hi + 1).coerceAtMost(chunk.text.length))
                            onSelectPhrase(text, (chunk.startChar + lo)..(chunk.startChar + hi))
                        }
                        onSelecting(null)
                        anchor = null
                        cursor = null
                    },
                    onDragCancel = {
                        onSelecting(null)
                        anchor = null
                        cursor = null
                    },
                )
            }
            .pointerInput(chunk.startChar) {
                detectTapGestures(onTap = { onClear() })
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

/** How often the Focus Gate credit tracker samples the viewport. */
private const val CREDIT_TICK_MS = 500L

/**
 * The chunk under the middle of the viewport, or -1 if there is none.
 *
 * Deliberately *one* chunk rather than everything visible: `visibleItemsInfo` counts an item as
 * visible from a single pixel, so on a tall screen several chunks would each bank the same minute.
 * Because the list renders exactly one item per chunk, LazyListItemInfo.index is the chunk index.
 */
private fun centreChunkIndex(listState: LazyListState): Int {
    val info = listState.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return -1
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    items.firstOrNull { centre >= it.offset && centre < it.offset + it.size }?.let { return it.index }
    // Nothing spans the midpoint (very short chunks with gaps): fall back to the tallest one.
    return items.maxByOrNull { it.size }?.index ?: -1
}
