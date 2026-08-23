package com.flashcardreader.app

import android.app.Application
import com.flashcardreader.app.data.db.AppDatabase
import com.flashcardreader.app.data.repository.CalibrationStore
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import com.flashcardreader.app.data.repository.ReviewHistory
import com.flashcardreader.app.data.repository.ReadingLog
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.focus.CreditBank
import com.flashcardreader.app.focus.FocusPrefs
import com.flashcardreader.app.theme.ReaderPrefs

/** Simple manual DI container - the app is small enough not to need Hilt/Dagger yet. */
class FlashcardReaderApp : Application() {
    val database by lazy { AppDatabase.get(this) }
    val calibration by lazy { CalibrationStore(this) }
    val reviewHistory by lazy { ReviewHistory(this, database.reviewLogDao()) }

    /** Scheduled with weights fitted to this device's own review history, when there are any. */
    private val fsrs by lazy { Fsrs(fittedInitialStability = reviewHistory.fittedInitialStability()) }

    val termRepository by lazy {
        TermRepository(database.termDao(), database.occurrenceDao(), calibration, fsrs, reviewHistory)
    }
    val libraryRepository by lazy { LibraryRepository(this, database.sourceDao()) }
    val readerPrefs by lazy { ReaderPrefs(this) }
    val focusPrefs by lazy { FocusPrefs(this) }
    val creditBank by lazy { CreditBank(this) }
    val readingCheckRepository by lazy {
        ReadingCheckRepository(database.readingCheckDao(), fsrs, reviewHistory)
    }
    val readingLog by lazy { ReadingLog(this) }
}
