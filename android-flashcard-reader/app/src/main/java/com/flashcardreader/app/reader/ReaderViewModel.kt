package com.flashcardreader.app.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.ai.AiPrefs
import com.flashcardreader.app.ai.QuestionFeedback
import com.flashcardreader.app.data.db.entities.ReviewContext
import com.flashcardreader.app.ai.GeminiTutor
import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.Bookmark
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.OccurrenceLog
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import com.flashcardreader.app.data.repository.ReadingLog
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.focus.CreditBank
import com.flashcardreader.app.focus.FocusPrefs
import com.flashcardreader.app.theme.ReaderPrefs
import com.flashcardreader.app.theme.ReaderTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item in the scrolling reader; [startChar] is this slice's offset into the full book text.
 * [chapterTitle] is set on the chunk that begins a chapter, so the reader can draw a divider there.
 */
data class TextChunk(
    val startChar: Int,
    val text: String,
    val chapterTitle: String? = null,
    /** Words in this chunk, used by the Focus Gate credit tracker to size a plausible dwell time. */
    val wordCount: Int = 0,
) {
    val endChar: Int get() = startChar + text.length
}

/** One in-book search hit: the match's char offset and a snippet of the text around it. */
data class SearchHit(val offset: Int, val snippet: String)

data class ReaderUiState(
    val source: Source? = null,
    val fullText: String = "",
    val terms: List<Term> = emptyList(),
    val chunks: List<TextChunk> = emptyList(),
    /** Table of contents (title + char offset), empty if the book had none. */
    val chapters: List<Chapter> = emptyList(),
    /** User bookmarks for this book, newest first. */
    val bookmarks: List<Bookmark> = emptyList(),
    /** Current in-book search query and its hits (offset + surrounding snippet). */
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    /** Char offset the reader is currently at (top of the viewport), for the progress bar. */
    val currentCharOffset: Int = 0,
    /** Which chunk to scroll to on open, to resume where the reader left off. */
    val initialChunkIndex: Int = 0,
    /** Due flashcards to answer before continuing. Shown as a blocking dialog. */
    val pendingFlashcards: List<TermMatch> = emptyList(),
    /** True when it's time for a comprehension free-recall check (after a stretch of reading). */
    val pendingComprehension: Boolean = false,
    val typography: ReaderTypography = ReaderTypography(),
    val loading: Boolean = true,
    /** True when Focus Gate accrual has paused because nobody has touched the screen for a while. */
    val creditIdle: Boolean = false,
    /**
     * Questions about the passage just read, waiting to be asked - one per idea the AI found in
     * it. Empty when there are none.
     */
    val pendingChecks: List<ReadingCheck> = emptyList(),
    /** Which of [pendingChecks] is on screen. */
    val checkIndex: Int = 0,
    /** True once enough verified reading has happened for [pendingChecks] to be asked. */
    val checkDue: Boolean = false,
    /** Plain-language status of the reading checks, so a silent feature can be diagnosed. */
    val checkStatus: String = "",
    /** Seconds of genuinely-read text banked this session, for the "open vs read" mirror. */
    val verifiedSeconds: Long = 0,
)

class ReaderViewModel(
    private val sourceId: Long,
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val termRepository: TermRepository,
    private val readingCheckRepository: ReadingCheckRepository,
    private val readingLog: ReadingLog,
    private val readerPrefs: ReaderPrefs,
    private val focusPrefs: FocusPrefs,
    private val creditBank: CreditBank,
    private val scanner: TermScanner = TermScanner(Fsrs()),
    private val feedback: QuestionFeedback? = null,
) : ViewModel() {

    private val aiPrefs = AiPrefs(context)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    /** Per-session anti-fake reading tracker; decides when a page has genuinely been read. */
    private val creditTracker = ReadingCreditTracker(
        capWpm = focusPrefs.maxWpm,
        idleTimeoutMs = focusPrefs.idleTimeoutSeconds * 1000L,
    )

    /** Live Focus Gate balance, for the reader's earned-credit indicator. */
    val creditBalance: StateFlow<Long> = creditBank.observeBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Whether Focus Gate is on at all - the indicator stays hidden when it isn't. */
    val focusEnabled: Boolean get() = focusPrefs.enabled

    /** Chunks already scanned for due terms, so continued scrolling doesn't rescan them. */
    private val scannedChunks = mutableSetOf<Int>()
    private var lastPersistedChunk = -1

    /** Char offset of the last comprehension prompt, so we prompt again after a stretch of reading. */
    private var lastRecallOffset = 0
    private val recallThresholdChars = 12000

    /**
     * Words of *verified* reading banked since the last check, and the chunks they came from.
     *
     * Deliberately not a timer. "Every four minutes" on the clock is exactly what doom-scrolling
     * defeats - leave the book open and the clock runs on regardless. Credited words only accrue
     * when a page was genuinely read (see ReadingCreditTracker), so skimming a chapter in ten
     * seconds earns no check at all, because nothing was read to ask about.
     */
    /** Open/read time waiting to be written out - see flushReadingLog. */
    private var pendingOpenMs = 0L
    private var pendingReadSeconds = 0L

    private var wordsSinceCheck = 0
    private val chunksSinceCheck = linkedSetOf<Int>()
    private var checkJob: Job? = null
    private var generatedForWindow = false

    /** Row id of the question currently on offer, so answering it doesn't have to search for it. */
    private var pendingCheckIds: List<Long?> = emptyList()

    /**
     * Whether the questions on screen came round again rather than being written from the passage
     * just read. A delayed answer is much stronger evidence than an immediate one, so which of the
     * two this is has to be recorded, not inferred later.
     */
    private var pendingAreRevisits = false

    /**
     * Questions from earlier in *this* book that have come due again.
     *
     * Vocabulary re-fires the moment its word reappears in the text, but comprehension questions
     * used to wait in the review screen and never surface while reading - so a question about
     * chapter two was never asked again during chapter nine, which is exactly where it belongs.
     * Loaded once on open, and offered between newly written ones.
     */
    private var dueFromThisBook: MutableList<com.flashcardreader.app.data.db.entities.ReadingCheckCard> = mutableListOf()

    /** Words of genuine reading between checks, from the user's chosen interval. */
    private val checkThresholdWords: Int
        get() = aiPrefs.readingCheckMinutes * ReadingCreditTracker.TYPICAL_WPM

    /** The in-flight in-book search, cancelled when a new query arrives. */
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val source = libraryRepository.getSource(sourceId)
            val text = source?.let { libraryRepository.readText(it) } ?: ""
            val terms = termRepository.allTerms()
            val typography = readerPrefs.typography.first()
            val chapters = source?.let { libraryRepository.readChapters(it) } ?: emptyList()
            val bookmarks = source?.let { libraryRepository.readBookmarks(it) } ?: emptyList()
            // Pages already paid for in a previous session must never earn again.
            source?.let { creditTracker.restore(libraryRepository.readCreditedChunks(it)) }
            // Questions this book has already produced, which are due to be asked again.
            dueFromThisBook = runCatching {
                val now = System.currentTimeMillis()
                readingCheckRepository.forSource(sourceId)
                    .filter { it.due == null || it.due <= now }
                    .filter { it.reps > 0 }
                    .toMutableList()
            }.getOrDefault(mutableListOf())
            // Chunking walks the whole book string; keep it off the main thread so a large
            // book doesn't hitch on open.
            val chunks = withContext(Dispatchers.Default) { chunkText(text, chapters) }
            val startIndex = source?.let { src ->
                chunks.indexOfFirst { it.endChar > src.lastPositionChar }.let { if (it < 0) 0 else it }
            } ?: 0
            val startOffset = chunks.getOrNull(startIndex)?.startChar ?: 0
            lastRecallOffset = startOffset
            _uiState.update {
                it.copy(
                    source = source, fullText = text, terms = terms, chunks = chunks,
                    chapters = chapters, bookmarks = bookmarks, currentCharOffset = startOffset,
                    initialChunkIndex = startIndex, typography = typography, loading = false,
                )
            }
        }
    }

    /**
     * Called as the reader scrolls, with the range of currently-visible chunk indices.
     * Persists the reading position and scans any newly-revealed chunk for due terms -
     * this is the scroll-mode replacement for the old "landed on a page" trigger: a due
     * word fires its flashcard the moment it scrolls into view.
     *
     * The regex scan (and the occurrence-log DB writes) run on a background dispatcher so
     * scrolling stays smooth as the tracked-word list grows. The chunks to scan are claimed
     * synchronously here (adding them to [scannedChunks]) so overlapping scroll callbacks
     * never schedule the same chunk twice; the results are posted back on the main thread.
     */
    fun onVisibleRange(firstVisible: Int, lastVisible: Int) {
        val state = _uiState.value
        if (state.chunks.isEmpty()) return

        if (firstVisible != lastPersistedChunk) {
            lastPersistedChunk = firstVisible
            state.chunks.getOrNull(firstVisible)?.let {
                persistPosition(it.startChar)
                _uiState.update { s -> s.copy(currentCharOffset = it.startChar) }
            }
        }

        if (state.pendingFlashcards.isNotEmpty() || state.pendingComprehension) return // one prompt at a time

        // Claim the not-yet-scanned chunks now (set.add returns false if already claimed),
        // so a burst of scroll callbacks doesn't scan the same chunk on several threads.
        val toScan = ArrayList<Int>()
        for (idx in firstVisible..lastVisible) {
            if (scannedChunks.add(idx)) toScan.add(idx)
        }
        val firstStart = state.chunks.getOrNull(firstVisible)?.startChar ?: 0
        if (toScan.isEmpty()) {
            maybePromptComprehension(firstStart)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val due = ArrayList<TermMatch>()
            val source = _uiState.value.source
            val logs = ArrayList<OccurrenceLog>()
            for (idx in toScan) {
                val chunk = state.chunks.getOrNull(idx) ?: continue
                val result = scanner.scan(chunk.text, state.terms, now)
                due.addAll(result.due)
                if (source != null) {
                    val dueIds = result.due.mapTo(HashSet()) { it.term.id }
                    for (m in result.all) {
                        logs.add(
                            OccurrenceLog(
                                termId = m.term.id,
                                sourceId = source.id,
                                charOffset = chunk.startChar + m.range.first,
                                triggeredReview = m.term.id in dueIds,
                            ),
                        )
                    }
                }
            }
            // One batched write for the whole visible range instead of an insert per occurrence.
            termRepository.logOccurrences(logs)
            withContext(Dispatchers.Main) {
                // Re-check on the main thread: another scan may have queued a prompt meanwhile.
                if (_uiState.value.pendingFlashcards.isNotEmpty() || _uiState.value.pendingComprehension) {
                    return@withContext
                }
                if (due.isNotEmpty()) {
                    val seen = HashSet<Long>()
                    val queue = due.filter { seen.add(it.term.id) }
                    _uiState.update { it.copy(pendingFlashcards = queue) }
                } else {
                    // No flashcard due here - after a stretch of new reading, prompt a comprehension
                    // free-recall ("what was this about?"), the strongest study technique for prose.
                    maybePromptComprehension(firstStart)
                }
            }
        }
    }

    /** Prompts a free-recall check once a stretch of new text has been read past the last one. */
    private fun maybePromptComprehension(firstStart: Int) {
        if (firstStart - lastRecallOffset >= recallThresholdChars) {
            lastRecallOffset = firstStart
            _uiState.update { it.copy(pendingComprehension = true) }
        }
    }

    fun dismissComprehension() {
        onReadingInteraction()
        _uiState.update { it.copy(pendingComprehension = false) }
    }

    /**
     * Reports a real human touch (drag, tap, long-press). Deliberately NOT called for programmatic
     * scrolling: auto-scroll must not look like a person, or a phone left face-up would farm credit.
     */
    fun onReadingInteraction() {
        creditTracker.noteInteraction(android.os.SystemClock.elapsedRealtime())
    }

    /** Clears part-read progress when the reader leaves the foreground, and banks the tally. */
    fun onReadingPaused() {
        creditTracker.onPaused()
        // Flush on the way out too: leaving after 40 seconds must still count those 40 seconds.
        flushReadingLog()
    }

    /** Writes the accumulated open/read time out, keeping whatever didn't round to a whole second. */
    private fun flushReadingLog() {
        val openSeconds = pendingOpenMs / 1000
        if (openSeconds > 0) {
            readingLog.addOpen(openSeconds)
            pendingOpenMs -= openSeconds * 1000
        }
        if (pendingReadSeconds > 0) {
            readingLog.addRead(pendingReadSeconds)
            pendingReadSeconds = 0
        }
    }

    /**
     * Drives the Focus Gate credit tracker once per tick. [focusedChunk] is the chunk centred in the
     * viewport, or -1 when nothing should accrue (a prompt covers the text, or we're in split-screen).
     */
    fun onReadingTick(focusedChunk: Int, elapsedMs: Long) {
        val state = _uiState.value
        val words = state.chunks.getOrNull(focusedChunk)?.wordCount ?: 0
        // The tracker always runs. It used to be skipped unless Focus Gate was on, which made
        // "what did you actually read" a Focus Gate internal - but it is the app's only honest
        // measure of reading, and the reading checks and the stats mirror both need it whether or
        // not anyone is blocking Instagram. Only the *banking* of credit is Focus Gate's business.
        val result = creditTracker.tick(
            nowMs = android.os.SystemClock.elapsedRealtime(),
            focusedChunk = focusedChunk,
            wordCount = words,
            elapsedMs = elapsedMs,
        )
        // Every tick is time the book was open, whether or not it was time spent reading. That
        // denominator is the whole point of the comparison. Accumulated in memory and flushed in
        // batches: a preferences write every single second, forever, to count seconds would be a
        // silly way to spend someone's battery, and integer-dividing each tick would quietly lose
        // the remainder every time.
        pendingOpenMs += elapsedMs
        if (pendingOpenMs >= FLUSH_EVERY_MS) flushReadingLog()
        if (result.idle != state.creditIdle) {
            _uiState.update { it.copy(creditIdle = result.idle) }
        }
        val source = state.source ?: return

        // Paying out and having read are now separate: a re-read page still counts as reading even
        // though it can never earn twice. The checks follow "read", the credit bank follows "paid".
        if (result.creditedChunk != null) {
            val earnedSeconds = ReadingCreditTracker.contentValueSeconds(result.creditedWords)
            val credited = creditTracker.creditedChunks.toSet()
            viewModelScope.launch {
                if (focusPrefs.enabled) creditBank.earnFromReading(earnedSeconds)
                libraryRepository.saveCreditedChunks(source, credited)
            }
        }

        val readChunk = result.readChunk ?: return
        val readSeconds = ReadingCreditTracker.contentValueSeconds(result.readWords)
        pendingReadSeconds += readSeconds
        _uiState.update { it.copy(verifiedSeconds = it.verifiedSeconds + readSeconds) }

        chunksSinceCheck.add(readChunk)
        wordsSinceCheck += result.readWords
        maybePrepareReadingCheck()
        // Crossing the threshold has to be a state change, not something the UI asks about. A
        // plain field would be read once when the question arrived - three quarters of the way
        // through the stretch, so not yet due - and nothing would ever recompose to ask again.
        if (!_uiState.value.checkDue && wordsSinceCheck >= checkThresholdWords) {
            _uiState.update { it.copy(checkDue = true) }
        }
    }

    /**
     * Writes the next question in the background, before it is needed.
     *
     * Generation starts at [PREPARE_FRACTION] of the way to the threshold so the question is
     * already in hand when the moment arrives: the dialog opens instantly instead of parking a
     * spinner in the middle of someone's reading. If it isn't ready, or the model's answer failed
     * validation, the round is simply skipped - reading is never blocked or interrupted by a wait.
     */
    private fun maybePrepareReadingCheck() {
        if (generatedForWindow || checkJob?.isActive == true) return
        if (!aiPrefs.readingChecks) return
        if (sourceId in aiPrefs.excludedSources) {
            setCheckStatus("Reading checks are switched off for this book.")
            return
        }
        if (wordsSinceCheck < checkThresholdWords * PREPARE_FRACTION) return

        // A question already written for this book and now due again is free - no API call, no
        // wait, and it is the one thing that actually tests whether chapter two stuck. Checked
        // before the key is, because re-asking an existing question needs no key at all.
        dueFromThisBook.removeFirstOrNull()?.let { revisit ->
            generatedForWindow = true
            pendingCheckIds = listOf(revisit.id)
            pendingAreRevisits = true
            _uiState.update {
                it.copy(
                    pendingChecks = listOf(
                        ReadingCheck(
                            question = revisit.question,
                            correctAnswer = revisit.correctAnswer,
                            distractors = revisit.wrongOptions,
                            evidence = revisit.evidence,
                        ),
                    ),
                    checkIndex = 0,
                    checkStatus = "",
                )
            }
            return
        }

        // Say why nothing is happening. A feature that stays silent when misconfigured is
        // indistinguishable from one that is broken, and there is no way for anyone to tell which.
        if (!aiPrefs.isReady) {
            setCheckStatus(
                if (aiPrefs.apiKey.isBlank()) {
                    "Reading checks need a Gemini API key in AI tutor settings."
                } else {
                    "Reading checks need the AI tutor switched on as well."
                },
            )
            return
        }
        generatedForWindow = true
        val passage = passageSinceLastCheck()
        val offset = _uiState.value.chunks.getOrNull(chunksSinceCheck.firstOrNull() ?: 0)?.startChar ?: 0
        checkJob = viewModelScope.launch {
            val result = GeminiTutor.generateReadingChecks(context, passage)
            val checks = result.getOrNull().orEmpty()
            if (checks.isEmpty()) {
                // One failure used to disable checks for the whole session, because this flag was
                // set on the way in and only ever cleared by answering a question that never came.
                generatedForWindow = false
                setCheckStatus(
                    "Couldn't write a question: " +
                        (result.exceptionOrNull()?.message ?: "the AI didn't answer").take(160),
                )
                return@launch
            }
            // Saved before being asked, so every question is kept and scheduled even if this
            // batch is skipped - the point is that they come back, not that they are answered now.
            pendingAreRevisits = false
            pendingCheckIds = checks.map {
                runCatching { readingCheckRepository.save(it, sourceId, offset) }.getOrNull()
            }
            _uiState.update { it.copy(pendingChecks = checks, checkIndex = 0, checkStatus = "") }
        }
    }

    /** Shows a one-off note about the checks, without nagging on every single tick. */
    private fun setCheckStatus(message: String) {
        if (_uiState.value.checkStatus == message) return
        _uiState.update { it.copy(checkStatus = message) }
    }

    fun dismissCheckStatus() {
        _uiState.update { it.copy(checkStatus = "") }
    }

    /**
     * A plain-language account of why a question has or hasn't appeared. This exists for the same
     * reason the book-source report does: nothing here can reach the Gemini API, so without the
     * app saying what it is doing, "no question appeared" has a dozen indistinguishable causes.
     */
    fun readingCheckReport(): String = buildString {
        appendLine("Reading checks")
        appendLine("  switched on: ${aiPrefs.readingChecks}")
        appendLine("  AI tutor on: ${aiPrefs.enabled}")
        appendLine("  API key set: ${aiPrefs.apiKey.isNotBlank()}")
        appendLine("  model: ${aiPrefs.model}")
        appendLine("  this book excluded: ${sourceId in aiPrefs.excludedSources}")
        appendLine("  interval: ${aiPrefs.readingCheckMinutes} min = $checkThresholdWords words")
        appendLine("  words read since last check: $wordsSinceCheck")
        appendLine("  pages read this stretch: ${chunksSinceCheck.size}")
        appendLine("  questions ready: ${_uiState.value.pendingChecks.size}")
        appendLine("  question due: ${_uiState.value.checkDue}")
        val status = _uiState.value.checkStatus
        if (status.isNotBlank()) appendLine("  last problem: $status")
    }

    /**
     * The text to ask about: exactly the chunks that were credited, in reading order.
     *
     * Only credited chunks are ever sent. Text that was scrolled past is not something the reader
     * read, so asking about it would be unfair - and it keeps the upload to the smallest thing that
     * answers the question, which matters when the upload is someone's book.
     */
    private fun passageSinceLastCheck(): String {
        val chunks = _uiState.value.chunks
        return chunksSinceCheck.sorted()
            .mapNotNull { chunks.getOrNull(it)?.text }
            .joinToString("\n\n")
            .take(MAX_PASSAGE_CHARS)
    }

    /**
     * Records the answer and moves to the next question in the batch, or back to the book.
     *
     * The window only reopens once the last one is done, so a batch counts as a single
     * interruption rather than resetting the clock after each question.
     */
    fun onReadingCheckAnswered(correct: Boolean) {
        val state = _uiState.value
        val cardId = pendingCheckIds.getOrNull(state.checkIndex)
        val more = state.checkIndex + 1 < state.pendingChecks.size
        if (more) {
            _uiState.update { it.copy(checkIndex = it.checkIndex + 1) }
        } else {
            clearCheckWindow()
        }
        if (cardId == null) return
        val answeredIn = if (pendingAreRevisits) ReviewContext.REVISIT else ReviewContext.READING
        viewModelScope.launch {
            val card = readingCheckRepository.byId(cardId) ?: return@launch
            readingCheckRepository.answer(card, correct, context = answeredIn)
        }
    }

    /**
     * Throws the question away as a bad one, keeping a copy of it so the prompt can be tuned.
     *
     * The alternative - skip it and let it come back on a schedule - would mean a question that is
     * wrong or unanswerable gets asked repeatedly, which is worse than not asking at all.
     */
    fun rejectReadingCheck() {
        val state = _uiState.value
        val check = state.pendingChecks.getOrNull(state.checkIndex) ?: return
        val cardId = pendingCheckIds.getOrNull(state.checkIndex)
        val book = state.source?.title.orEmpty()
        // Only this question goes; the others in the batch were about different ideas and may be
        // perfectly good.
        val more = state.checkIndex + 1 < state.pendingChecks.size
        if (more) {
            _uiState.update { it.copy(checkIndex = it.checkIndex + 1) }
        } else {
            clearCheckWindow()
        }
        viewModelScope.launch {
            feedback?.record(check, book = book)
            if (cardId != null) {
                readingCheckRepository.byId(cardId)?.let { readingCheckRepository.discard(it) }
            }
        }
    }

    /**
     * Dismissed without answering. Skips the whole batch, not just the question on screen: someone
     * who wants to get back to the book should not have to skip three times. The questions are all
     * kept and stay due on the review screen.
     */
    fun dismissReadingCheck() {
        onReadingInteraction()
        clearCheckWindow()
    }

    private fun clearCheckWindow() {
        wordsSinceCheck = 0
        chunksSinceCheck.clear()
        generatedForWindow = false
        pendingCheckIds = emptyList()
        pendingAreRevisits = false
        _uiState.update { it.copy(pendingChecks = emptyList(), checkIndex = 0, checkDue = false) }
    }

    /**
     * Bookmarks the current reading position, or removes an existing bookmark near it (so the
     * same top-bar action both adds and clears). The label is a short preview of the text there.
     */
    fun toggleBookmarkAtCurrent() {
        val source = _uiState.value.source ?: return
        val offset = _uiState.value.currentCharOffset
        val existing = _uiState.value.bookmarks.firstOrNull { kotlin.math.abs(it.offset - offset) < 200 }
        val newList = if (existing != null) {
            _uiState.value.bookmarks - existing
        } else {
            (_uiState.value.bookmarks + Bookmark(offset, snippetAt(offset), System.currentTimeMillis()))
                .sortedByDescending { it.createdAt }
        }
        _uiState.update { it.copy(bookmarks = newList) }
        viewModelScope.launch { libraryRepository.saveBookmarks(source, newList) }
    }

    fun removeBookmark(bookmark: Bookmark) {
        val source = _uiState.value.source ?: return
        val newList = _uiState.value.bookmarks - bookmark
        _uiState.update { it.copy(bookmarks = newList) }
        viewModelScope.launch { libraryRepository.saveBookmarks(source, newList) }
    }

    /** True when there's a bookmark within a screen's-worth of the current position. */
    fun isBookmarkedNear(offset: Int): Boolean =
        _uiState.value.bookmarks.any { kotlin.math.abs(it.offset - offset) < 200 }

    /**
     * Finds every occurrence of [query] in the book (case-insensitive), capped so a very common
     * word can't build a huge list, each with a snippet of surrounding text for the results list.
     *
     * The scan walks the whole book, so it runs on a background dispatcher to keep typing in the
     * search box responsive; the query is reflected immediately and results arrive when ready.
     * A new keystroke cancels the previous, still-running scan.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
            return
        }
        _uiState.update { it.copy(searchQuery = query) }
        val text = _uiState.value.fullText
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val whitespace = Regex("\\s+")
            val hits = ArrayList<SearchHit>()
            var idx = text.indexOf(query, 0, ignoreCase = true)
            while (idx >= 0 && hits.size < 300) {
                val start = (idx - 30).coerceAtLeast(0)
                val end = (idx + query.length + 30).coerceAtMost(text.length)
                val snippet = text.substring(start, end).replace(whitespace, " ").trim()
                hits.add(SearchHit(idx, snippet))
                idx = text.indexOf(query, idx + query.length, ignoreCase = true)
            }
            _uiState.update { it.copy(searchResults = hits) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    private fun snippetAt(offset: Int): String {
        val text = _uiState.value.fullText
        if (offset < 0 || offset >= text.length) return "Bookmark"
        val end = (offset + 60).coerceAtMost(text.length)
        return text.substring(offset, end).replace(Regex("\\s+"), " ").trim().ifEmpty { "Bookmark" }
    }

    /** Banks credit for a cloze card whose typed answer was actually correct (capped per day). */
    fun earnFromCard(term: Term) {
        viewModelScope.launch { creditBank.earnFromCard(term.id) }
    }

    fun answerFlashcard(rating: Rating, confidence: Confidence) {
        onReadingInteraction()
        val match = _uiState.value.pendingFlashcards.firstOrNull() ?: return
        viewModelScope.launch {
            val updated = termRepository.submitReview(match.term, rating, confidence, ReviewContext.READING)
            _uiState.update { s ->
                s.copy(
                    pendingFlashcards = s.pendingFlashcards.drop(1),
                    terms = s.terms.map { if (it.id == updated.id) updated else it },
                )
            }
        }
    }

    fun createFlashcard(selectedText: String, definition: String) {
        viewModelScope.launch {
            val term = termRepository.createOrGetTerm(selectedText, definition)
            if (definition.isNotBlank() && definition != term.definition) {
                termRepository.updateDefinition(term, definition)
            }
            _uiState.update { it.copy(terms = termRepository.allTerms()) }
        }
    }

    fun updateTypography(typography: ReaderTypography) {
        _uiState.update { it.copy(typography = typography) }
        viewModelScope.launch { readerPrefs.update(typography) }
    }

    private fun persistPosition(charOffset: Int) {
        val source = _uiState.value.source ?: return
        viewModelScope.launch { libraryRepository.updatePosition(source, charOffset) }
    }
}

/**
 * Splits the whole book into lazy-list-sized chunks. Splits on paragraph breaks where
 * they exist (PDFs), and caps chunk length (~1600 chars) at a word boundary otherwise
 * (EPUB/MOBI chapter text arrives as long blobs), so no single list item is huge.
 *
 * Chapter boundaries force a chunk break, and the chunk that begins a chapter is tagged with
 * its title so the reader can render a divider there.
 */
/** How much open/read time to accumulate before writing it to disk. */
private const val FLUSH_EVERY_MS = 30_000L

/** Start writing the question this far into the stretch, so it is ready before it is wanted. */
private const val PREPARE_FRACTION = 0.75

/** Bounds what leaves the device, and what the model has to hold in its head at once. */
private const val MAX_PASSAGE_CHARS = 6_000

fun chunkText(full: String, chapters: List<Chapter> = emptyList()): List<TextChunk> {
    if (full.isEmpty()) return listOf(TextChunk(0, ""))
    val maxLen = 1600
    val chapterAt = HashMap<Int, String>()
    val boundaries = java.util.TreeSet<Int>()
    for (c in chapters) {
        if (c.charOffset in 0 until full.length) {
            boundaries.add(c.charOffset)
            // First title wins if two chapters share an offset.
            chapterAt.putIfAbsent(c.charOffset, c.title)
        }
    }
    val chunks = ArrayList<TextChunk>()
    var i = 0
    val n = full.length
    while (i < n) {
        var end = full.indexOf('\n', i).let { if (it == -1) n else it + 1 }
        if (end - i > maxLen) {
            var cut = i + maxLen
            val space = full.lastIndexOf(' ', cut)
            if (space > i) cut = space + 1
            end = cut
        }
        // Never let a chunk span into the next chapter: cut at the boundary so that chapter's
        // text starts a fresh, tagged chunk.
        val nextBoundary = boundaries.higher(i)
        if (nextBoundary != null && nextBoundary < end) end = nextBoundary
        val body = full.substring(i, end)
        chunks.add(TextChunk(i, body, chapterAt[i], countWords(body)))
        i = end
    }
    return chunks
}

/** Counts whitespace-delimited words. Cheap, and only ever run while chunking off the main thread. */
private fun countWords(text: String): Int {
    var count = 0
    var inWord = false
    for (c in text) {
        if (c.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}
