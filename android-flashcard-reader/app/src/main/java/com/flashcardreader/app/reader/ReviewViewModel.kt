package com.flashcardreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val queue: List<Term> = emptyList(),
    val contextSentence: String = "",
    /** Title of the book the shown sentence was read in, for the "you read this in …" cue. */
    val contextSource: String = "",
    val loading: Boolean = true,
)

/**
 * Standalone review queue for due flashcards that haven't naturally reappeared
 * in anything you're currently reading. This is what keeps the spacing
 * schedule honest even if a word never resurfaces in your current book.
 */
class ReviewViewModel(
    private val termRepository: TermRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val due = termRepository.allTerms().filter { termRepository.isDue(it, now) }
            _uiState.update { it.copy(queue = due, loading = false) }
            loadContextForCurrent()
        }
    }

    /** Looks up where the current queue item was last seen and pulls its sentence for context. */
    private fun loadContextForCurrent() {
        val term = _uiState.value.queue.firstOrNull()
        if (term == null) {
            _uiState.update { it.copy(contextSentence = "", contextSource = "") }
            return
        }
        viewModelScope.launch {
            // A *different* real sentence from your own reading each time (encoding variability).
            val occurrence = termRepository.randomOccurrence(term.id)
            val source = occurrence?.let { libraryRepository.getSource(it.sourceId) }
            val text = source?.let { libraryRepository.readText(it) }
            val sentence = if (occurrence != null && text != null) {
                ContextExtractor.sentenceAround(text, occurrence.charOffset, occurrence.charOffset + term.displayText.length)
            } else {
                ""
            }
            _uiState.update { it.copy(contextSentence = sentence, contextSource = source?.title.orEmpty()) }
        }
    }

    fun answerCurrent(rating: Rating, confidence: Confidence) {
        val term = _uiState.value.queue.firstOrNull() ?: return
        viewModelScope.launch {
            termRepository.submitReview(term, rating, confidence)
            _uiState.update { it.copy(queue = it.queue.drop(1)) }
            loadContextForCurrent()
        }
    }
}
