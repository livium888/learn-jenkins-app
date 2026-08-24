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
    /** Books whose saved text is mostly empty lines, and the share of it that is. */
    val needsTidying: Map<Long, Float> = emptyMap(),
    val tidying: Long? = null,
    val tidyResult: String? = null,
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

    /**
     * Looks for books saved before their text was being cleaned.
     *
     * Only offered where it would actually change something: a book that is a third empty lines is
     * losing real pages to nothing, and one that is not is left alone.
     */
    fun checkForBlankSpace(sources: List<Source>) {
        viewModelScope.launch {
            val found = mutableMapOf<Long, Float>()
            for (source in sources) {
                val ratio = runCatching { repository.blankRatio(source) }.getOrDefault(0f)
                if (ratio >= BLANK_RATIO_THRESHOLD) found[source.id] = ratio
            }
            _uiState.update { it.copy(needsTidying = found) }
        }
    }

    /** Cleans one book's saved text in place, carrying every remembered position across with it. */
    fun tidy(source: Source) {
        _uiState.update { it.copy(tidying = source.id, tidyResult = null) }
        viewModelScope.launch {
            val removed = runCatching {
                repository.tidyExistingText(source) { offsets ->
                    readingChecks.remapOffsets(source.id) { offsets.map(it) }
                }
            }.getOrDefault(0)
            _uiState.update {
                it.copy(
                    tidying = null,
                    needsTidying = it.needsTidying - source.id,
                    tidyResult = if (removed > 0) {
                        "Tidied \"${source.title}\" - removed ${removed / 1000}k characters of empty space."
                    } else {
                        "\"${source.title}\" had nothing to tidy."
                    },
                )
            }
        }
    }

    fun dismissTidyResult() {
        _uiState.update { it.copy(tidyResult = null) }
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

/** A book with this share of empty lines is losing whole pages to nothing. */
private const val BLANK_RATIO_THRESHOLD = 0.33f
