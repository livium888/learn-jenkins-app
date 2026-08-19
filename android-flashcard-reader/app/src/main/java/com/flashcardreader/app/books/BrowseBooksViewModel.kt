package com.flashcardreader.app.books

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.books.BookCatalog
import com.flashcardreader.app.data.books.BookSourcePrefs
import com.flashcardreader.app.data.books.Catalogs
import com.flashcardreader.app.data.books.CustomFeed
import com.flashcardreader.app.data.books.RemoteBook
import com.flashcardreader.app.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val catalogNames: List<String> = emptyList(),
    val selected: Int = 0,
    val blurb: String = "",
    val loading: Boolean = false,
    val results: List<RemoteBook> = emptyList(),
    val error: String? = null,
    /** Id of the book currently downloading, or null. */
    val downloadingId: String? = null,
    /** Ids already added to the library this session. */
    val addedIds: Set<String> = emptySet(),
    val message: String? = null,
    val customFeeds: List<CustomFeed> = emptyList(),
)

/**
 * Browses the free-book catalogues. Every source implements the same [BookCatalog] interface, so
 * this screen neither knows nor cares whether it is talking to Gutenberg, an OPDS feed, or
 * Wikisource - and a catalogue the user pastes in later needs no code here at all.
 */
class BrowseBooksViewModel(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val prefs = BookSourcePrefs(context)
    private var catalogs: List<BookCatalog> = Catalogs.all(context)
    private var lastQuery = ""

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState

    init {
        refreshCatalogs()
        search("")
    }

    private fun refreshCatalogs() {
        catalogs = Catalogs.all(context)
        _uiState.update {
            it.copy(
                catalogNames = catalogs.map { c -> c.displayName },
                blurb = catalogs.getOrNull(it.selected)?.blurb.orEmpty(),
                customFeeds = prefs.customFeeds,
            )
        }
    }

    fun selectCatalog(index: Int) {
        if (index !in catalogs.indices || index == _uiState.value.selected) return
        _uiState.update { it.copy(selected = index, blurb = catalogs[index].blurb, results = emptyList()) }
        search(lastQuery)
    }

    fun search(query: String) {
        lastQuery = query
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val books = catalog.search(query)
                _uiState.update { it.copy(loading = false, results = books) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = "Couldn't reach ${catalog.displayName}. ${e.message.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    fun download(book: RemoteBook) {
        if (_uiState.value.downloadingId != null || book.id in _uiState.value.addedIds) return
        _uiState.update { it.copy(downloadingId = book.id, error = null) }
        viewModelScope.launch {
            try {
                libraryRepository.importFromCatalog(book)
                _uiState.update {
                    it.copy(
                        downloadingId = null,
                        addedIds = it.addedIds + book.id,
                        message = "Added “${book.title}”",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloadingId = null, error = "Couldn't add that book: ${e.message}")
                }
            }
        }
    }

    /** Adds a catalogue by URL - any OPDS feed, including a Calibre server you run yourself. */
    fun addCustomFeed(name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val cleanName = name.trim().ifBlank { "My catalogue" }
        prefs.add(CustomFeed(cleanName, cleanUrl))
        refreshCatalogs()
        _uiState.update { it.copy(message = "Added “$cleanName”") }
    }

    fun removeCustomFeed(url: String) {
        prefs.remove(url)
        // Selection may now point past the end of a shorter list.
        _uiState.update { it.copy(selected = 0) }
        refreshCatalogs()
        search(lastQuery)
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
