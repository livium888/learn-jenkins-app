package com.flashcardreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.theme.ReaderPrefs
import com.flashcardreader.app.theme.ReaderTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One item in the scrolling reader; [startChar] is this slice's offset into the full book text. */
data class TextChunk(val startChar: Int, val text: String) {
    val endChar: Int get() = startChar + text.length
}

data class ReaderUiState(
    val source: Source? = null,
    val fullText: String = "",
    val terms: List<Term> = emptyList(),
    val chunks: List<TextChunk> = emptyList(),
    /** Which chunk to scroll to on open, to resume where the reader left off. */
    val initialChunkIndex: Int = 0,
    /** Due flashcards to answer before continuing. Shown as a blocking dialog. */
    val pendingFlashcards: List<TermMatch> = emptyList(),
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

    init {
        viewModelScope.launch {
            val source = libraryRepository.getSource(sourceId)
            val text = source?.let { libraryRepository.readText(it) } ?: ""
            val terms = termRepository.allTerms()
            val typography = readerPrefs.typography.first()
            val chunks = chunkText(text)
            val startIndex = source?.let { src ->
                chunks.indexOfFirst { it.endChar > src.lastPositionChar }.let { if (it < 0) 0 else it }
            } ?: 0
            _uiState.update {
                it.copy(
                    source = source, fullText = text, terms = terms, chunks = chunks,
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
     */
    fun onVisibleRange(firstVisible: Int, lastVisible: Int) {
        val state = _uiState.value
        if (state.chunks.isEmpty()) return

        if (firstVisible != lastPersistedChunk) {
            lastPersistedChunk = firstVisible
            state.chunks.getOrNull(firstVisible)?.let { persistPosition(it.startChar) }
        }

        if (state.pendingFlashcards.isNotEmpty()) return // one quiz at a time

        val now = System.currentTimeMillis()
        val due = mutableListOf<TermMatch>()
        for (idx in firstVisible..lastVisible) {
            if (idx in scannedChunks) continue
            val chunk = state.chunks.getOrNull(idx) ?: continue
            scannedChunks.add(idx)
            val chunkDue = scanner.findDueMatches(chunk.text, state.terms, now)
            logOccurrences(chunk, chunkDue)
            due.addAll(chunkDue)
        }
        if (due.isNotEmpty()) {
            val seen = HashSet<Long>()
            val queue = due.filter { seen.add(it.term.id) }
            _uiState.update { it.copy(pendingFlashcards = queue) }
        }
    }

    private fun logOccurrences(chunk: TextChunk, dueMatches: List<TermMatch>) {
        val state = _uiState.value
        val source = state.source ?: return
        val dueIds = dueMatches.map { it.term.id }.toSet()
        val all = scanner.findAllMatches(chunk.text, state.terms)
        viewModelScope.launch {
            for (m in all) {
                termRepository.logOccurrence(
                    termId = m.term.id,
                    sourceId = source.id,
                    charOffset = chunk.startChar + m.range.first,
                    triggeredReview = m.term.id in dueIds,
                )
            }
        }
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
 */
fun chunkText(full: String): List<TextChunk> {
    if (full.isEmpty()) return listOf(TextChunk(0, ""))
    val maxLen = 1600
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
        chunks.add(TextChunk(i, full.substring(i, end)))
        i = end
    }
    return chunks
}
