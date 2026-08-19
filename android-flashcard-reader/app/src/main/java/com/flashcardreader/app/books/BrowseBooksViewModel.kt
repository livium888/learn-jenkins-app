package com.flashcardreader.app.books

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.books.BookCatalog
import com.flashcardreader.app.data.books.BookSourcePrefs
import com.flashcardreader.app.data.books.CatalogAuthRequired
import com.flashcardreader.app.data.books.CatalogCredentials
import com.flashcardreader.app.data.books.CatalogUnavailable
import com.flashcardreader.app.data.books.Catalogs
import com.flashcardreader.app.data.books.Credentials
import com.flashcardreader.app.data.books.Discovery
import com.flashcardreader.app.data.books.OpdsCatalog
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
    /** Set when the selected catalogue answered "who are you?" - the fix is signing in, not retrying. */
    val needsLogin: Boolean = false,
    /** Subject shelves and author suggestions - the way in when you don't know a title. */
    val topics: List<String> = Discovery.topics,
    val suggestedAuthors: List<String> = Discovery.authors,
    val activeTopic: String? = null,
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
    private val logins = CatalogCredentials(context)
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
        _uiState.update {
            it.copy(selected = index, blurb = catalogs[index].blurb, results = emptyList(), needsLogin = false)
        }
        search(lastQuery)
    }

    fun search(query: String) {
        lastQuery = query
        _uiState.update { it.copy(activeTopic = null) }
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        _uiState.update { it.copy(loading = true, error = null, needsLogin = false) }
        viewModelScope.launch {
            try {
                val books = catalog.search(query)
                _uiState.update { it.copy(loading = false, results = books, needsLogin = false) }
            } catch (e: CatalogAuthRequired) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        needsLogin = true,
                        error = "${catalog.displayName} needs you to sign in.",
                    )
                }
            } catch (e: CatalogUnavailable) {
                // Name every address tried and what it said - otherwise this is unfixable guesswork.
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = "${catalog.displayName} didn't respond.\n" +
                            e.attempts.joinToString("\n") { attempt -> "• $attempt" },
                    )
                }
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

    /** Browses a subject shelf rather than searching for words in a title. */
    fun browseTopic(topic: String) {
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        lastQuery = ""
        _uiState.update { it.copy(loading = true, error = null, needsLogin = false, activeTopic = topic) }
        viewModelScope.launch {
            try {
                val books = catalog.browse(topic)
                _uiState.update { it.copy(loading = false, results = books) }
            } catch (e: CatalogAuthRequired) {
                _uiState.update {
                    it.copy(loading = false, needsLogin = true, error = "${catalog.displayName} needs you to sign in.")
                }
            } catch (e: CatalogUnavailable) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = "${catalog.displayName} didn't respond.\n" +
                            e.attempts.joinToString("\n") { attempt -> "• $attempt" },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Couldn't reach ${catalog.displayName}.")
                }
            }
        }
    }

    /** The feed URL of the selected catalogue, when it is one that can take a login. */
    private fun selectedFeedUrl(): String? =
        (catalogs.getOrNull(_uiState.value.selected) as? OpdsCatalog)?.feedUrl

    /** Saves a login for the selected catalogue and retries the search. */
    fun signIn(username: String, password: String) {
        val url = selectedFeedUrl() ?: return
        logins.put(url, Credentials(username.trim(), password))
        // Credentials are read through a lambda, so rebuild so the catalogue picks them up.
        refreshCatalogs()
        search(lastQuery)
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
    fun addCustomFeed(name: String, url: String, username: String = "", password: String = "") {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val cleanName = name.trim().ifBlank { "My catalogue" }
        prefs.add(CustomFeed(cleanName, cleanUrl))
        if (username.isNotBlank()) logins.put(cleanUrl, Credentials(username.trim(), password))
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
