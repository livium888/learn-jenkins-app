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
import com.flashcardreader.app.data.reference.DictionaryPrefs
import com.flashcardreader.app.data.books.OpdsCatalog
import com.flashcardreader.app.data.books.CustomFeed
import com.flashcardreader.app.data.books.RemoteBook
import com.flashcardreader.app.data.books.SeenBooks
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
    /** How many titles the current source matched, when it can say. */
    val matchedTotal: Int? = null,
    /** True when that source's read was cut short, so the count is a floor. */
    val partialRead: Boolean = false,
    /** Set when the selected catalogue answered "who are you?" - the fix is signing in, not retrying. */
    val needsLogin: Boolean = false,
    /** Subject shelves and author suggestions - the way in when you don't know a title. */
    val topics: List<String> = Discovery.topics,
    val suggestedAuthors: List<String> = Discovery.authors,
    val activeTopic: String? = null,
    /** Shelves are hidden for sources that can't really browse - see BookCatalog.supportsBrowse. */
    val supportsBrowse: Boolean = false,
    /** A probe report the user can send me, since I can't reach these hosts to test them. */
    val diagnostics: String? = null,
    val diagnosing: Boolean = false,
    /** Set while re-reading a source, so the refresh reads as deliberate rather than as a stall. */
    val refreshing: Boolean = false,
    /** Ids that weren't in this source last time you looked - badged wherever they turn up. */
    val newIds: Set<String> = emptySet(),
    /** Fetching the next page at the bottom of the list. */
    val loadingMore: Boolean = false,
    /** The source has nothing further to give, so stop asking. */
    val endReached: Boolean = false,
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
    private val seen = SeenBooks(context)
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
                supportsBrowse = catalogs.getOrNull(it.selected)?.supportsBrowse ?: false,
                customFeeds = prefs.customFeeds,
            )
        }
    }

    fun selectCatalog(index: Int) {
        if (index !in catalogs.indices || index == _uiState.value.selected) return
        _uiState.update {
            it.copy(
                selected = index,
                blurb = catalogs[index].blurb,
                supportsBrowse = catalogs[index].supportsBrowse,
                results = emptyList(),
                needsLogin = false,
                activeTopic = null,
                newIds = emptySet(),
            )
        }
        search(lastQuery)
    }

    fun search(query: String) {
        lastQuery = query
        _uiState.update { it.copy(activeTopic = null) }
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        _uiState.update { it.copy(loading = true, error = null, needsLogin = false, endReached = false) }
        viewModelScope.launch {
            try {
                val books = catalog.search(query)
                val wasRefreshing = _uiState.value.refreshing
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        results = books,
                        needsLogin = false,
                        matchedTotal = catalog.matchedTotal,
                        partialRead = catalog.readWasCutShort,
                        message = if (wasRefreshing) refreshMessage(books.size) else it.message,
                    )
                }
                syncShelves()
                markArrivals(catalog)
            } catch (e: CatalogAuthRequired) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        needsLogin = true,
                        error = "${catalog.displayName} needs you to sign in.",
                    )
                }
            } catch (e: CatalogUnavailable) {
                // Name every address tried and what it said - otherwise this is unfixable guesswork.
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = "${catalog.displayName} didn't respond.\n" +
                            e.attempts.joinToString("\n") { attempt -> "• $attempt" },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
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
        _uiState.update {
            it.copy(loading = true, error = null, needsLogin = false, activeTopic = topic, endReached = false)
        }
        viewModelScope.launch {
            try {
                val books = catalog.browse(topic)
                val wasRefreshing = _uiState.value.refreshing
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        results = books,
                        matchedTotal = catalog.matchedTotal,
                        partialRead = catalog.readWasCutShort,
                        message = if (wasRefreshing) refreshMessage(books.size) else it.message,
                    )
                }
                syncShelves()
                markArrivals(catalog)
            } catch (e: CatalogAuthRequired) {
                _uiState.update {
                    it.copy(loading = false, refreshing = false, needsLogin = true, error = "${catalog.displayName} needs you to sign in.")
                }
            } catch (e: CatalogUnavailable) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = "${catalog.displayName} didn't respond.\n" +
                            e.attempts.joinToString("\n") { attempt -> "• $attempt" },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.message ?: "Couldn't reach ${catalog.displayName}.",
                    )
                }
            }
        }
    }

    /**
     * Fetches the next page when the list runs out under your thumb.
     *
     * Sources answer in pages - Gutendex 32 at a time, Wikisource 30, an OPDS catalogue as much as
     * we choose to show - so without this the bottom of the first page was the bottom of the
     * catalogue. Results are de-duplicated by id: a source that overlaps its pages would otherwise
     * hand the list two rows with the same key, which Compose treats as a crash rather than a typo.
     */
    fun loadMore() {
        val current = _uiState.value
        if (current.loading || current.loadingMore || current.endReached) return
        val catalog = catalogs.getOrNull(current.selected) ?: return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = catalog.more()
                _uiState.update {
                    it.copy(
                        loadingMore = false,
                        endReached = next.isEmpty(),
                        results = (it.results + next).distinctBy { book -> book.id },
                    )
                }
            } catch (e: Exception) {
                // Stop asking, but say why - silently freezing at the bottom looks like the end.
                _uiState.update {
                    it.copy(
                        loadingMore = false,
                        endReached = true,
                        error = "Couldn't load more. ${e.message.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    /**
     * Asks the selected source again, instead of answering from what it said earlier.
     *
     * Only whole-catalogue sources hold anything to throw away, but the button is worth having on
     * all of them: from the outside there is no way to tell which sources cache, and "did that
     * actually re-check?" is not a question a reader should have to think about.
     */
    fun refresh() {
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        catalog.invalidate()
        _uiState.update { it.copy(refreshing = true, endReached = false) }
        val topic = _uiState.value.activeTopic
        if (topic != null) browseTopic(topic) else search(lastQuery)
    }

    /**
     * Shelves are only real if the source publishes subjects, and an OPDS catalogue can only say so
     * once it has been read. So this runs after every load: the shelf row appears when there is
     * something behind it, labelled with the catalogue's own subject names rather than ours.
     */
    private fun syncShelves() {
        val catalog = catalogs.getOrNull(_uiState.value.selected) ?: return
        val own = (catalog as? OpdsCatalog)?.availableTopics.orEmpty()
        _uiState.update {
            it.copy(
                supportsBrowse = catalog.supportsBrowse,
                topics = if (own.isNotEmpty()) own.take(MAX_SHELVES) else Discovery.topics,
            )
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

    /**
     * Probes every source and builds a plain-text report. The build environment can't reach any of
     * these hosts (the proxy refuses the connection), so this is how real behaviour gets back to
     * whoever is fixing it - rather than another round of guessing.
     */
    fun runDiagnostics() {
        _uiState.update { it.copy(diagnosing = true, diagnostics = null) }
        viewModelScope.launch {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            val report = buildString {
                appendLine("Book source report")
                appendLine("app: $version | android ${android.os.Build.VERSION.SDK_INT}")
                appendLine("reading language: ${DictionaryPrefs(context).readingLanguage}")
                appendLine()
                catalogs.forEach { catalog ->
                    append(runCatching { catalog.diagnose().asText() }
                        .getOrElse { "[FAIL] ${catalog.displayName}\n  crashed: ${it.message}\n" })
                    appendLine()
                }
            }
            _uiState.update { it.copy(diagnosing = false, diagnostics = report) }
        }
    }

    fun dismissDiagnostics() {
        _uiState.update { it.copy(diagnostics = null) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    /**
     * Works out which books weren't here last time, and keeps them badged for the visit.
     *
     * The comparison is against the source's whole catalogue rather than what's on screen: a book
     * that arrives while you happen to be searching for something else would otherwise be recorded
     * as seen without ever having been shown. Results accumulate across a refresh, so a badge
     * earned earlier in the visit doesn't vanish the moment you re-check.
     */
    private suspend fun markArrivals(catalog: BookCatalog) {
        val all = runCatching { catalog.wholeCatalogue() }.getOrNull() ?: return
        val arrivals = seen.newSince(catalog.displayName, all.map { it.id })
        if (arrivals.isEmpty()) return
        _uiState.update { it.copy(newIds = it.newIds + arrivals) }
    }

    /** Says what the refresh found, so an unchanged list still confirms it really re-checked. */
    private fun refreshMessage(count: Int): String =
        if (count == 0) "Re-checked — still nothing here" else "Re-checked — $count titles"

    private companion object {
        /** Enough shelves to browse by, few enough to scan in one swipe. */
        const val MAX_SHELVES = 14
    }
}
