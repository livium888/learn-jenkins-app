package com.flashcardreader.app.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.parser.OcrProgress
import com.flashcardreader.app.data.parser.PdfOcr
import com.flashcardreader.app.data.parser.ScannedPdfException
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
    /** A scanned PDF waiting on a decision: read it slowly with OCR, or give up on it. */
    val scannedPdf: ScannedPdfOffer? = null,
    /** Progress while recognising text, so a job of minutes doesn't look like a hang. */
    val ocrProgress: OcrProgress? = null,
    /** Set while fetching the recognition data the first time it is needed. */
    val downloadingOcrData: Boolean = false,
)

/** A PDF that turned out to be a scan, kept so the offer survives the dialog. */
data class ScannedPdfOffer(
    val uri: Uri,
    val displayName: String,
    val pageCount: Int,
    /** False the first time, when the recognition data still has to be fetched. */
    val dataReady: Boolean,
)

class LibraryViewModel(
    private val context: Context,
    private val repository: LibraryRepository,
    private val readingChecks: ReadingCheckRepository,
) : ViewModel() {

    private val ocr = PdfOcr(context)

    val sources: StateFlow<List<Source>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    fun importFile(uri: Uri, displayName: String) {
        _uiState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.importFromFile(uri, displayName) }
                .onFailure { e ->
                    // A scan is not a failure, it just needs a slower route - so it asks rather
                    // than reporting that the file couldn't be read.
                    if (e is ScannedPdfException) {
                        _uiState.update {
                            it.copy(
                                scannedPdf = ScannedPdfOffer(
                                    uri = uri,
                                    displayName = displayName,
                                    pageCount = e.pageCount,
                                    dataReady = ocr.isReady(),
                                ),
                            )
                        }
                    } else {
                        _uiState.update { it.copy(error = e.message ?: "Import failed") }
                    }
                }
            _uiState.update { it.copy(importing = false) }
        }
    }

    /** Runs OCR over the offered scan, downloading the recognition data first if needed. */
    fun readScannedPdf() {
        val offer = _uiState.value.scannedPdf ?: return
        _uiState.update { it.copy(scannedPdf = null, importing = true, error = null) }
        viewModelScope.launch {
            if (!ocr.isReady()) {
                _uiState.update { it.copy(downloadingOcrData = true) }
                val downloaded = ocr.downloadLanguage()
                _uiState.update { it.copy(downloadingOcrData = false) }
                if (downloaded.isFailure) {
                    _uiState.update {
                        it.copy(
                            importing = false,
                            error = downloaded.exceptionOrNull()?.message
                                ?: "Couldn't download the text recogniser.",
                        )
                    }
                    return@launch
                }
            }
            runCatching {
                repository.importScannedPdf(offer.uri, offer.displayName, ocr) { progress ->
                    _uiState.update { it.copy(ocrProgress = progress) }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Couldn't read that scan") }
            }
            _uiState.update { it.copy(importing = false, ocrProgress = null) }
        }
    }

    fun dismissScannedPdf() {
        _uiState.update { it.copy(scannedPdf = null) }
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
