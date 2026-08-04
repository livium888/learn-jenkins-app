package com.flashcardreader.app.gutenberg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.gutenberg.GutenbergBook
import com.flashcardreader.app.data.gutenberg.GutenbergClient
import com.flashcardreader.app.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GutenbergUiState(
    val loading: Boolean = false,
    val results: List<GutenbergBook> = emptyList(),
    val error: String? = null,
    /** Id of the book currently downloading, or null. */
    val downloadingId: Long? = null,
    /** Ids already added to the library this session. */
    val addedIds: Set<Long> = emptySet(),
    val message: String? = null,
)

class GutenbergViewModel(private val libraryRepository: LibraryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(GutenbergUiState())
    val uiState: StateFlow<GutenbergUiState> = _uiState

    init {
        search("")
    }

    /** Blank query loads the most-downloaded ("popular") books. */
    fun search(query: String) {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val books = GutenbergClient.search(query)
                _uiState.update { it.copy(loading = false, results = books) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Couldn't reach Project Gutenberg. Check your connection.")
                }
            }
        }
    }

    fun download(book: GutenbergBook) {
        if (_uiState.value.downloadingId != null || book.id in _uiState.value.addedIds) return
        _uiState.update { it.copy(downloadingId = book.id, error = null) }
        viewModelScope.launch {
            try {
                libraryRepository.importFromGutenberg(book)
                _uiState.update {
                    it.copy(downloadingId = null, addedIds = it.addedIds + book.id, message = "Added “${book.title}”")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(downloadingId = null, error = "Couldn't add that book: ${e.message}") }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
