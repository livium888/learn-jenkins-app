package com.flashcardreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.parser.Chapter
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.Bookmark
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.OccurrenceLog
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.theme.ReaderPrefs
import com.flashcardreader.app.theme.ReaderTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item in the scrolling reader; [startChar] is this slice's offset into the full book text.
 * [chapterTitle] is set on the chunk that begins a chapter, so the reader can draw a divider there.
 */
data class TextChunk(val startChar: Int, val text: String, val chapterTitle: String? = null) {
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
)

class ReaderViewModel(
    private val sourceId: Long,
    private val libraryRepository: LibraryRepository,
    private val termRepository: TermRepository,
    private val readerPrefs: ReaderPrefs,
    private val scanner: TermScanner = TermScanner(Fsrs()),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    /** Chunks already scanned for due terms, so continued scrolling doesn't rescan them. */
    private val scannedChunks = mutableSetOf<Int>()
    private var lastPersistedChunk = -1

    /** Char offset of the last comprehension prompt, so we prompt again after a stretch of reading. */
    private var lastRecallOffset = 0
    private val recallThresholdChars = 12000

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
        _uiState.update { it.copy(pendingComprehension = false) }
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

    fun answerFlashcard(rating: Rating, confidence: Confidence) {
        val match = _uiState.value.pendingFlashcards.firstOrNull() ?: return
        viewModelScope.launch {
            val updated = termRepository.submitReview(match.term, rating, confidence)
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
        chunks.add(TextChunk(i, full.substring(i, end), chapterAt[i]))
        i = end
    }
    return chunks
}
