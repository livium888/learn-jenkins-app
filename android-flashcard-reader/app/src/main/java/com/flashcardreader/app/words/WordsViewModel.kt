package com.flashcardreader.app.words

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the "My Words" screen: the whole global flashcard list, editable and deletable. */
class WordsViewModel(private val termRepository: TermRepository) : ViewModel() {

    val terms: StateFlow<List<Term>> = termRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateDefinition(term: Term, definition: String) {
        viewModelScope.launch { termRepository.updateDefinition(term, definition) }
    }

    fun delete(term: Term) {
        viewModelScope.launch { termRepository.delete(term) }
    }
}
