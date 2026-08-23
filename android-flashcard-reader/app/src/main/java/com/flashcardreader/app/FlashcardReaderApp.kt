package com.flashcardreader.app

import android.app.Application
import com.flashcardreader.app.ai.QuestionFeedback
import com.flashcardreader.app.data.db.AppDatabase
import com.flashcardreader.app.data.repository.BackupRepository
import com.flashcardreader.app.data.repository.CalibrationStore
import com.flashcardreader.app.diagnostics.CrashLog
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.IntervalFormat
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import com.flashcardreader.app.data.repository.ReviewHistory
import com.flashcardreader.app.data.repository.ReadingLog
import com.flashcardreader.app.data.repository.TermRepository
import com.flashcardreader.app.focus.CreditBank
import com.flashcardreader.app.focus.FocusPrefs
import com.flashcardreader.app.theme.ReaderPrefs

/** Simple manual DI container - the app is small enough not to need Hilt/Dagger yet. */
class FlashcardReaderApp : Application() {

    /** Records the last crash to a file so it can be read off the phone rather than guessed at. */
    val crashLog by lazy { CrashLog(this) }

    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else can throw - a handler registered after the failing
        // code would miss exactly the crashes worth catching.
        crashLog.install(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        // So the "next due" labels on the rating buttons describe the schedule that will actually
        // be applied, rather than FSRS's published defaults. Cheap: Fsrs only holds a lambda here,
        // and the weights behind it are not read until a label or a review asks for them.
        IntervalFormat.scheduler = fsrs
    }

    val database by lazy { AppDatabase.get(this) }
    val calibration by lazy { CalibrationStore(this) }
    val reviewHistory by lazy { ReviewHistory(this, database.reviewLogDao()) }

    /** Scheduled with weights fitted to this device's own review history, when there are any. */
    private val fsrs = Fsrs(fittedInitialStability = { reviewHistory.fittedInitialStability() })

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

    /** Questions marked as bad, kept so the prompt that writes them can be tuned from evidence. */
    val questionFeedback by lazy { QuestionFeedback(this) }

    /** Words, questions and review history together - everything a new phone cannot recreate. */
    val backupRepository by lazy {
        BackupRepository(
            termRepository,
            database.readingCheckDao(),
            database.reviewLogDao(),
            database.sourceDao(),
        )
    }
}
