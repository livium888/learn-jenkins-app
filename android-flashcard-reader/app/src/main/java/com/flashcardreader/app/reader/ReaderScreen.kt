package com.flashcardreader.app.reader

import android.app.Activity
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.theme.FontLoader
import com.flashcardreader.app.theme.LoadedFont
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.theme.ReaderColors
import com.flashcardreader.app.theme.colorsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Space above and below the text on every page. Used both to draw the page and to measure it -
 * one constant, because a difference between the two would mean the last line of every page is
 * laid out as fitting and then drawn off the bottom of the screen.
 */
private val PAGE_VERTICAL_PADDING = 24.dp

/**
 * Room reserved for a chapter heading on the page that opens a chapter.
 *
 * Deliberately generous - the rule and title come to about 87dp for a one-line title and more if
 * it wraps. Reserving too much only means that page holds a line or two less; reserving too little
 * pushes the page's last line off the bottom of the screen, where it is gone without a trace.
 */
private val CHAPTER_HEADING_HEIGHT = 120.dp

/** What the add/edit-flashcard dialog is currently prefilled with, or null if closed. */
private data class FlashcardPrefill(val term: String, val definition: String, val contextSentence: String = "")

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)
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
    // One page per pager page. The reader turns pages now rather than scrolling, which is what
    // lets everything downstream stop guessing: the page on screen IS the text being read, so its
    // words, its dwell and the passage a question comes from are exact rather than estimated.
    val pagerState = rememberPagerState(pageCount = { state.chunks.size })

    // Resolve the reader font: system fonts are instant; accessibility fonts (OpenDyslexic,
    // Atkinson) download on first use. A failure used to be swallowed, so choosing one of them on
    // a phone that could not reach the CDN simply did nothing; now it says so and can be retried.
    var fontAttempt by remember { mutableStateOf(0) }
    val loadedFont by produceState(
        initialValue = LoadedFont(typography.font.family),
        typography.font,
        fontAttempt,
    ) {
        value = FontLoader.load(context, typography.font)
    }
    val fontFamily = loadedFont.family

    // Chrome starts hidden. Tapping the page brings it back, and any menu you open puts it away
    // again on the way out, so the default state of the reader is always just the book.
    var chromeVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var flashcardPrefill by remember { mutableStateOf<FlashcardPrefill?>(null) }
    // Absolute char ranges (into the full book text) of the word being pressed / selected.
    var pressingRange by remember { mutableStateOf<IntRange?>(null) }
    var selectedRange by remember { mutableStateOf<IntRange?>(null) }
    // Hands-free reading turns pages on a timer instead of creeping the text upwards.
    var autoTurn by remember { mutableStateOf(false) }
    var secondsPerPage by remember { mutableStateOf(35f) }

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

    // Jump to where reading left off, once the book has been laid out. Keyed on the page count so
    // it also lands correctly after a re-layout - changing the font size must not lose your place.
    LaunchedEffect(state.paginated, state.chunks.size) {
        if (state.paginated && state.chunks.isNotEmpty()) {
            pagerState.scrollToPage(state.initialChunkIndex.coerceIn(0, state.chunks.lastIndex))
        }
    }

    // Hands-free reading: turn the page on a timer, and stop at the end of the book.
    LaunchedEffect(autoTurn, secondsPerPage, state.chunks.size) {
        if (autoTurn) {
            while (true) {
                delay((secondsPerPage * 1000).toLong())
                val next = pagerState.currentPage + 1
                if (next >= state.chunks.size) { autoTurn = false; break }
                pagerState.animateScrollToPage(next)
            }
        }
    }

    fun jumpToOffset(offset: Int) {
        val idx = chunkIndexForOffset(state.chunks, offset)
        scope.launch { pagerState.scrollToPage(idx.coerceIn(0, (state.chunks.size - 1).coerceAtLeast(0))) }
    }

    // The reader is the page, and nothing else. Chrome is off until you ask for it - one tap on
    // the text brings it back, the way an e-reader does, because a top bar and a progress bar
    // permanently parked on a phone screen is most of a paragraph's worth of book you never see.
    //
    // Drawn *over* the page rather than around it. Putting it in Scaffold's slots would change the
    // height available to the text every time the menu opened, and the height is an input to
    // pagination - so every tap would re-lay-out the book and lose your place in the process.
    val chrome: @Composable BoxScope.() -> Unit = {
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(state.source?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                    navigationIconContentColor = colors.text,
                    actionIconContentColor = colors.text,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // The add-flashcard button was a floating action button parked over the text.
                    // It is a rare action - it belongs in the menu with everything else.
                    IconButton(onClick = { flashcardPrefill = FlashcardPrefill(clipboardText(context), "") }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add flashcard")
                    }
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
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            // A finished chapter is announced whether or not the menu is up: it is rare, it is
            // dismissible, and it is the one thing here worth interrupting a hidden-chrome page for.
            state.pendingPass?.let { pass ->
                ChapterPassBanner(
                    title = pass.chapterTitle,
                    onOpen = viewModel::openChapterPass,
                    onDismiss = viewModel::dismissChapterPass,
                )
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                ReaderProgressBar(
                    pageIndex = pagerState.currentPage,
                    pageCount = state.chunks.size,
                    chapterTitle = currentChapterTitle(state.chapters, state.currentCharOffset),
                    background = colors.background,
                    onColor = colors.text,
                    autoTurn = autoTurn,
                    onToggleAutoTurn = { autoTurn = !autoTurn },
                    focusEnabled = viewModel.focusEnabled,
                    creditSeconds = creditSeconds,
                    creditIdle = state.creditIdle,
                    secondsPerPage = secondsPerPage,
                    onSecondsPerPageChange = { secondsPerPage = it },
                    onScrubToPage = { page ->
                        scope.launch { pagerState.scrollToPage(page.coerceIn(0, (state.chunks.size - 1).coerceAtLeast(0))) }
                    },
                )
            }
        }
    }

    Scaffold { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding).background(colors.background))
            return@Scaffold
        }

        // One TextStyle for measuring and for drawing. Anything set here that affects where the
        // lines fall must also appear in the pagination signature below, or the cached page breaks
        // would describe a layout that is no longer being drawn.
        val style = TextStyle(
            fontFamily = fontFamily,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            letterSpacing = typography.letterSpacing,
            color = colors.text,
            textAlign = if (typography.justify) TextAlign.Justify else TextAlign.Start,
            // Justified text without hyphenation is what produces the rivers of white space that
            // make a page look wrong: with nowhere to break a long word, the line's remaining
            // spaces have to stretch to fill the measure. Every printed book hyphenates; this is
            // the single change that most makes justified text look typeset rather than stretched.
            hyphens = Hyphens.Auto,
            // Break lines by looking at the paragraph as a whole rather than greedily line by line.
            // Slower, and worth it on a page of prose - it is what avoids a very short last line.
            lineBreak = LineBreak.Paragraph,
            // Font padding is a legacy Android quirk that adds uneven space above the first line
            // and below the last. Off, the line spacing you asked for is the spacing you get.
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )

        // Report the page on screen. Exactly one page is visible, so "what is being read" stops
        // being a range and becomes a single index - and turning a page is itself the human touch
        // that proves somebody is there, which is why it also reports an interaction.
        LaunchedEffect(pagerState, state.chunks) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    if (state.chunks.isNotEmpty()) {
                        viewModel.onReadingInteraction()
                        viewModel.onVisibleRange(page, page)
                    }
                }
        }

        // Focus Gate: measure genuine reading. repeatOnLifecycle(RESUMED) means switching apps or
        // turning the screen off stops accrual for free, and cancelling clears part-read progress.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(pagerState) {
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
                            chapterPassReady = live.openPass != null,
                            inMultiWindow = activity?.isInMultiWindowMode == true,
                        )
                        // No centre-of-viewport guess any more: the page being settled on is the
                        // page being read. While a swipe is in flight neither page is fully in
                        // view, so nothing accrues - which is right, and free.
                        val focused = when {
                            overlays.coversText -> -1
                            pagerState.isScrollInProgress -> -1
                            else -> pagerState.currentPage
                        }
                        viewModel.onReadingTick(focusedChunk = focused, elapsedMs = delta)
                    }
                } finally {
                    viewModel.onReadingPaused()
                }
            }
        }

        val measurer = rememberTextMeasurer(cacheSize = 0)
        val density = LocalDensity.current

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(colors.background)) {
            val marginDp = typography.horizontalMarginDp.dp
            val pageWidthPx = with(density) { (maxWidth - marginDp * 2).toPx().toInt() }
            val pageHeightPx = with(density) { (maxHeight - PAGE_VERTICAL_PADDING * 2).toPx() }
            // A chapter heading is drawn above the text on the page that opens a chapter, so that
            // page has less room. Laying it out as though the heading were not there would push
            // its last line off the bottom of the screen and lose it silently.
            val chapterPageHeightPx = pageHeightPx - with(density) { CHAPTER_HEADING_HEIGHT.toPx() }

            // Everything that changes where the lines fall. A mismatch is only a cache miss, so
            // being over-inclusive here costs a re-layout and being under-inclusive shows wrong pages.
            val signature = listOf(
                // v2: hyphenation and paragraph-wide line breaking change where lines fall, so
                // every cached layout from before them is wrong and must be measured again.
                "v2", pageWidthPx, pageHeightPx.toInt(), typography.font.name,
                typography.fontSize.value, typography.lineHeight.value,
                typography.letterSpacing.value, typography.justify,
                typography.horizontalMarginDp, state.fullText.length,
            ).joinToString("|")

            LaunchedEffect(signature, state.fullText) {
                if (state.fullText.isEmpty() || pageWidthPx <= 0 || pageHeightPx <= 0f) return@LaunchedEffect
                // Coalesce a run of changes into one layout. Toggling justify and then dragging a
                // margin would otherwise start a fresh pass over the whole book for each step, and
                // each pass is cancelled by the next - so none of them ever finishes.
                delay(RELAYOUT_DEBOUNCE_MS)
                viewModel.onRelayoutStarted()
                val cached = viewModel.cachedPages(signature)
                if (cached != null) {
                    viewModel.onPaginated(cached, signature)
                    return@LaunchedEffect
                }
                // Laying out a whole book is measurable work - seconds for a long one - so it runs
                // off the main thread and reports progress rather than freezing on open.
                val pages = withContext(Dispatchers.Default) {
                    val pageMeasurer = PageMeasurer(measurer, style, pageWidthPx)
                    val forced = state.chapters
                        .map { it.charOffset }
                        .filter { it in state.fullText.indices }
                        .toSet()
                    Paginator.paginate(
                        textLength = state.fullText.length,
                        pageHeightPx = pageHeightPx,
                        blockChars = PageMeasurer.BLOCK_CHARS,
                        forcedBreaks = forced,
                        chapterPageHeightPx = chapterPageHeightPx,
                        onProgress = { viewModel.onPaginationProgress(it) },
                    ) { from, to -> pageMeasurer.linesFor(state.fullText, from, to) }
                }
                viewModel.onPaginated(pages, signature)
            }

            if (!state.paginated) {
                PaginatingNotice(state.paginatingProgress, colors)
            } else {
                HorizontalPager(
                    state = pagerState,
                    // Neighbouring pages are deliberately not composed ahead: one page at a time
                    // is the whole basis of the measurement now, and the default already does this.
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val chunk = state.chunks.getOrNull(index) ?: return@HorizontalPager
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = marginDp, vertical = PAGE_VERTICAL_PADDING),
                    ) {
                        chunk.chapterTitle?.let { ChapterDivider(it, colors) }
                        ChunkText(
                            chunk = chunk,
                            style = style,
                            accent = colors.accent,
                            selecting = pressingRange,
                            selected = selectedRange,
                            onSelecting = { viewModel.onReadingInteraction(); pressingRange = it },
                            onClear = {
                                viewModel.onReadingInteraction()
                                // A tap while something is selected means "put that away", not
                                // "show me the menu" - one intent per tap.
                                if (selectedRange != null || pressingRange != null) {
                                    selectedRange = null
                                    pressingRange = null
                                } else {
                                    chromeVisible = !chromeVisible
                                }
                            },
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
            }

            // Changing anything that moves the lines re-lays-out the whole book, which takes a
            // moment on a long one. Drawn over the pages rather than under them, and last so it is
            // not hidden: silence here is what made the sliders look inert even once they worked.
            if (state.relayouting && state.paginated) {
                LinearProgressIndicator(
                    progress = { state.paginatingProgress },
                    color = colors.accent,
                    trackColor = colors.background,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }

            // Night warmth: a non-interactive warm overlay that cuts blue light.
            if (typography.warmth > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color(0xFFFF6A00).copy(alpha = (typography.warmth * 0.5f).coerceIn(0f, 0.5f))),
                )
            }

            // The menu, over the page rather than around it - see the comment where it is built.
            // Drawn last so the warm overlay tints the page and not the controls.
            chrome()
        }
    }

    val overlays = readerOverlays(
        pendingFlashcards = state.pendingFlashcards.size,
        pendingChecks = state.pendingChecks.size,
        checkDue = state.checkDue,
        chapterPassReady = state.openPass != null,
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

    state.openPass?.takeIf { overlays.showChapterPass }?.let { pass ->
        ChapterRecallDialog(
            card = pass,
            onAnswered = viewModel::answerChapterPass,
            onDismiss = viewModel::dismissChapterPass,
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            typography = typography,
            onChange = viewModel::updateTypography,
            onDismiss = { showSettings = false },
            sourceId = state.source?.id ?: 0L,
            readingCheckReport = viewModel::readingCheckReport,
            fontError = loadedFont.error,
            onRetryFont = {
                FontLoader.forget(context, typography.font)
                fontAttempt++
            },
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
/**
 * Shown while the book is being laid out against this screen.
 *
 * Only the first opening of a book at a given size pays this: the page breaks are kept, so
 * reopening it - or coming back after changing nothing - is instant. Changing the font or turning
 * the phone genuinely changes where every page falls, so that has to be paid again.
 */
@Composable
private fun PaginatingNotice(progress: Float, colors: ReaderColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                "Laying out the pages…",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Once only, for this text size. It is kept for next time.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.text.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

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
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String?,
    background: Color,
    onColor: Color,
    autoTurn: Boolean,
    onToggleAutoTurn: () -> Unit,
    focusEnabled: Boolean,
    creditSeconds: Long,
    creditIdle: Boolean,
    secondsPerPage: Float,
    onSecondsPerPageChange: (Float) -> Unit,
    onScrubToPage: (Int) -> Unit,
) {
    // Real pages now, not an estimate from a character count - so "page 40 of 312" is the book's
    // actual shape at this text size, and the scrubber lands on a page rather than near one.
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shownPage = scrub?.let { (it * lastPage).roundToInt() } ?: pageIndex
    val fraction = if (lastPage > 0) shownPage.toFloat() / lastPage else 0f
    val muted = onColor.copy(alpha = 0.7f)

    Surface(color = background) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleAutoTurn) {
                    Icon(
                        if (autoTurn) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (autoTurn) "Stop turning pages" else "Turn pages automatically",
                        tint = if (autoTurn) MaterialTheme.colorScheme.primary else muted,
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
                    "Page ${shownPage + 1} of ${pageCount.coerceAtLeast(1)} · ${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
            if (autoTurn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${secondsPerPage.roundToInt()}s a page",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                    Slider(
                        value = secondsPerPage,
                        onValueChange = onSecondsPerPageChange,
                        valueRange = 10f..120f,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }
            Slider(
                value = fraction,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onScrubToPage((it * lastPage).roundToInt()) }
                    scrub = null
                },
                enabled = lastPage > 0,
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
/**
 * How long to wait after a typography change before laying the book out again.
 *
 * Long enough that a run of changes becomes one layout, short enough not to feel like lag.
 */
private const val RELAYOUT_DEBOUNCE_MS = 250L

private const val CREDIT_TICK_MS = 500L
