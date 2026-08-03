package com.flashcardreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(val queue: List<Term> = emptyList(), val loading: Boolean = true)

/**
 * Standalone review queue for due flashcards that haven't naturally reappeared
 * in anything you're currently reading. This is what keeps the spacing
 * schedule honest even if a word never resurfaces in your current book.
 */
class ReviewViewModel(private val termRepository: TermRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val due = termRepository.allTerms().filter { termRepository.isDue(it, now) }
            _uiState.update { it.copy(queue = due, loading = false) }
        }
    }

    fun answerCurrent(rating: Rating) {
        val term = _uiState.value.queue.firstOrNull() ?: return
        viewModelScope.launch {
            termRepository.submitReview(term, rating)
            _uiState.update { it.copy(queue = it.queue.drop(1)) }
        }
    }
}
