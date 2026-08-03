package com.flashcardreader.app.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.theme.ReaderPrefs
import com.flashcardreader.app.theme.ReaderTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val source: Source? = null,
    val fullText: String = "",
    val terms: List<Term> = emptyList(),
    val pages: List<Page> = emptyList(),
    val currentPageIndex: Int = 0,
    /** True while (re)computing page breaks - the previous page stays on screen meanwhile. */
    val pagesLoading: Boolean = false,
    /** Identifies which font/size/line-height/viewport the current [pages] were computed for. */
    val pagesSignature: String? = null,
    /** Due flashcards found on the page we're about to reveal. Non-empty = block navigation. */
    val pendingFlashcards: List<TermMatch> = emptyList(),
    val pendingTargetPageIndex: Int? = null,
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

    /** Guards against an older, slower loadPages() call overwriting a newer one's result. */
    private var pageLoadGeneration = 0

    init {
        viewModelScope.launch {
            val source = libraryRepository.getSource(sourceId)
            val text = source?.let { libraryRepository.readText(it) } ?: ""
            val terms = termRepository.allTerms()
            // One-shot read, not a continuous collect: typography changes are applied to
            // _uiState immediately by updateTypography() below, so re-observing DataStore
            // here would just re-deliver (with disk-write latency) what we already set.
            val typography = readerPrefs.typography.first()
            _uiState.update {
                it.copy(source = source, fullText = text, terms = terms, typography = typography, loading = false)
            }
        }
    }

    /**
     * Computes (or reuses a cached) page-break list for the given viewport/typography.
     * Runs the actual text measurement off the main thread and persists the result so
     * reopening this book with the same settings/screen size is instant next time -
     * without this, every open (and every settings tweak) re-measures the whole book.
     */
    fun loadPages(
        measure: (String, Constraints) -> TextLayoutResult,
        widthPx: Int,
        heightPx: Int,
        typography: ReaderTypography,
    ) {
        val state = _uiState.value
        val source = state.source ?: return
        val signature = "${typography.font.name}|${typography.fontSizeSp}|${typography.lineHeightMultiplier}|$widthPx|$heightPx"
        if (signature == state.pagesSignature && state.pages.isNotEmpty()) return
        val myGeneration = ++pageLoadGeneration

        viewModelScope.launch {
            _uiState.update { it.copy(pagesLoading = true) }
            val cached = libraryRepository.loadCachedPageOffsets(source, signature)
            val pages = if (cached != null) {
                cached.map { (start, end) -> Page(start, end) }
            } else {
                withContext(Dispatchers.Default) {
                    ReaderPaginator.paginate(state.fullText, measure, widthPx, heightPx)
                }.also { computed ->
                    libraryRepository.savePageOffsetsCache(source, signature, computed.map { it.startChar to it.endChar })
                }
            }
            if (myGeneration != pageLoadGeneration) return@launch // a newer request already superseded this one
            val startIndex = pages.indexOfFirst { it.endChar > source.lastPositionChar }
                .let { if (it < 0) 0 else it }
            _uiState.update {
                it.copy(pages = pages, currentPageIndex = startIndex, pagesLoading = false, pagesSignature = signature)
            }
            scanCurrentPageForDueTerms()
        }
    }

    private fun pageText(index: Int): String {
        val state = _uiState.value
        val page = state.pages.getOrNull(index) ?: return ""
        return state.fullText.substring(page.startChar, page.endChar)
    }

    /** Scans the page about to be shown; if due terms appear, blocks on flashcards first. */
    private fun scanCurrentPageForDueTerms() {
        val state = _uiState.value
        val text = pageText(state.currentPageIndex)
        val due = scanner.findDueMatches(text, state.terms, System.currentTimeMillis())
        if (due.isNotEmpty()) {
            _uiState.update { it.copy(pendingFlashcards = due, pendingTargetPageIndex = it.currentPageIndex) }
        }
        logAllOccurrences(text, due)
    }

    private fun logAllOccurrences(pageText: String, dueMatches: List<TermMatch>) {
        val state = _uiState.value
        val source = state.source ?: return
        val dueTermIds = dueMatches.map { it.term.id }.toSet()
        val all = scanner.findAllMatches(pageText, state.terms)
        viewModelScope.launch {
            for (match in all) {
                termRepository.logOccurrence(
                    termId = match.term.id,
                    sourceId = source.id,
                    charOffset = (state.pages.getOrNull(state.currentPageIndex)?.startChar ?: 0) + match.range.first,
                    triggeredReview = match.term.id in dueTermIds,
                )
            }
        }
    }

    fun goToNextPage() {
        val state = _uiState.value
        if (state.pendingFlashcards.isNotEmpty()) return // must answer first
        val next = state.currentPageIndex + 1
        if (next >= state.pages.size) return
        _uiState.update { it.copy(currentPageIndex = next) }
        persistPosition()
        scanCurrentPageForDueTerms()
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        if (state.pendingFlashcards.isNotEmpty()) return
        val prev = state.currentPageIndex - 1
        if (prev < 0) return
        _uiState.update { it.copy(currentPageIndex = prev) }
        persistPosition()
        scanCurrentPageForDueTerms()
    }

    fun answerFlashcard(rating: Rating) {
        val state = _uiState.value
        val match = state.pendingFlashcards.firstOrNull() ?: return
        viewModelScope.launch {
            val updated = termRepository.submitReview(match.term, rating)
            _uiState.update { s ->
                val remaining = s.pendingFlashcards.drop(1)
                val terms = s.terms.map { if (it.id == updated.id) updated else it }
                s.copy(pendingFlashcards = remaining, terms = terms)
            }
            if (_uiState.value.pendingFlashcards.isEmpty()) {
                persistPosition()
            }
        }
    }

    private fun persistPosition() {
        val state = _uiState.value
        val source = state.source ?: return
        val page = state.pages.getOrNull(state.currentPageIndex) ?: return
        viewModelScope.launch { libraryRepository.updatePosition(source, page.startChar) }
    }

    fun createFlashcard(selectedText: String, definition: String) {
        viewModelScope.launch {
            val term = termRepository.createOrGetTerm(selectedText, definition)
            // createOrGetTerm reuses an existing card if this word is already tracked
            // (without overwriting it) - if the user edited the definition, save that.
            if (definition.isNotBlank() && definition != term.definition) {
                termRepository.updateDefinition(term, definition)
            }
            _uiState.update { it.copy(terms = termRepository.allTerms()) }
        }
    }

    /**
     * Applied to the UI immediately (no waiting on disk I/O) so sliders/chips in the
     * settings sheet track the user's finger in real time; the DataStore write happens
     * in the background purely for persistence across app restarts.
     */
    fun updateTypography(typography: ReaderTypography) {
        _uiState.update { it.copy(typography = typography) }
        viewModelScope.launch { readerPrefs.update(typography) }
    }
}
