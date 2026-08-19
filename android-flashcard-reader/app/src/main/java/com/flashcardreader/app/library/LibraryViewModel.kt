package com.flashcardreader.app.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val importing: Boolean = false,
    val error: String? = null,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val readingChecks: ReadingCheckRepository,
) : ViewModel() {

    val sources: StateFlow<List<Source>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    fun importFile(uri: Uri, displayName: String) {
        _uiState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.importFromFile(uri, displayName) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Import failed") } }
            _uiState.update { it.copy(importing = false) }
        }
    }

    fun importFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.importFromUrl(trimmed) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Import failed") } }
            _uiState.update { it.copy(importing = false) }
        }
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            // Questions belong to the book they were written from - keeping them after it's gone
            // would mean being quizzed on a passage you can no longer look up.
            runCatching { readingChecks.deleteForSource(source.id) }
            repository.delete(source)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
