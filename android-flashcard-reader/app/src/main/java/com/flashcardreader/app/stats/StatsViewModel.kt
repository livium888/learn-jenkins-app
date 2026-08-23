package com.flashcardreader.app.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.ai.QuestionFeedback
import com.flashcardreader.app.data.repository.BackupRepository
import com.flashcardreader.app.data.repository.CalibrationLevel
import com.flashcardreader.app.data.repository.CalibrationStore
import com.flashcardreader.app.data.repository.BookMastery
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.ReadingCheckCounts
import com.flashcardreader.app.data.repository.ReviewHistory
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Aggregate learning stats, all derived from the current flashcard set. */
data class Stats(
    val total: Int = 0,
    val dueNow: Int = 0,
    val reviewedToday: Int = 0,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val relearningCount: Int = 0,
    val totalReviews: Int = 0,
    /** Rough retention proxy: 1 - lapses/reviews, in percent. Null until there are reviews. */
    val retentionPct: Int? = null,
    val curious: Int = 0,
    val hyperMiss: Int = 0,
    /** Calibration: recall accuracy per confidence level (from CalibrationStore). */
    val calibration: List<CalibrationLevel> = emptyList(),
    val calibrationNote: String? = null,
    /** Comprehension questions written from your reading: total, due now, answered, still perfect. */
    val readingChecks: Int = 0,
    val readingChecksDue: Int = 0,
    val readingChecksAnswered: Int = 0,
    val readingChecksRemembered: Int = 0,
    /** Answered right at least once, anywhere. */
    val readingChecksEverCorrect: Int = 0,
    /** Answered right when the passage wasn't the thing just read - the number that means most. */
    val readingChecksRetained: Int = 0,
    /** The same split per book, so one that stuck can be told from one that didn't. */
    val mastery: List<BookMastery> = emptyList(),
    /** How many reviews the scheduler's fitted weights were built from; 0 = FSRS defaults. */
    val scheduleFittedFrom: Int = 0,
)

class StatsViewModel(
    private val termRepository: TermRepository,
    private val calibration: CalibrationStore,
    private val readingChecks: ReadingCheckRepository,
    private val reviewHistory: ReviewHistory,
    private val backup: BackupRepository,
    private val feedback: QuestionFeedback,
    private val library: LibraryRepository,
) : ViewModel() {
    // Combined so the comprehension questions refresh with everything else - a count that only
    // updated when a *word* changed would go stale exactly when someone came to check it.
    val stats: StateFlow<Stats> = combine(
        termRepository.observeAll(),
        readingChecks.observeCount(),
    ) { terms, checkCount -> terms to checkCount }
        .map { (terms, checkCount) ->
            val snapshot = calibration.snapshot()
            val counts = runCatching { readingChecks.counts() }.getOrDefault(ReadingCheckCounts())
            computeStats(terms).copy(
                calibration = snapshot.levels,
                calibrationNote = snapshot.note,
                readingChecks = checkCount,
                readingChecksDue = counts.due,
                readingChecksAnswered = counts.answered,
                readingChecksRemembered = counts.remembered,
                readingChecksEverCorrect = counts.everCorrect,
                readingChecksRetained = counts.retained,
                mastery = runCatching {
                    readingChecks.masteryByBook(library.allSources().associate { it.id to it.title })
                }.getOrDefault(emptyList()),
                scheduleFittedFrom = reviewHistory.fittedFromReviews,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())

    suspend fun exportJson(): String = backup.exportJson()

    suspend fun importJson(json: String) = backup.importJson(json)

    /** How many questions have been flagged as bad, for the Progress screen. */
    val flaggedQuestions: Int get() = feedback.count

    fun flaggedQuestionsReport(): String = feedback.report()

    fun clearFlaggedQuestions() = feedback.clear()
}

private fun computeStats(terms: List<Term>): Stats {
    val now = System.currentTimeMillis()
    val startOfToday = LocalDate.now(ZoneId.systemDefault())
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val totalReviews = terms.sumOf { it.reps }
    val totalLapses = terms.sumOf { it.lapses }

    return Stats(
        total = terms.size,
        dueNow = terms.count { it.due == null || it.due <= now },
        reviewedToday = terms.count { (it.lastReviewedAt ?: 0) >= startOfToday },
        newCount = terms.count { it.state == CardState.NEW },
        learningCount = terms.count { it.state == CardState.LEARNING },
        reviewCount = terms.count { it.state == CardState.REVIEW },
        relearningCount = terms.count { it.state == CardState.RELEARNING },
        totalReviews = totalReviews,
        retentionPct = if (totalReviews > 0) ((1.0 - totalLapses.toDouble() / totalReviews) * 100).roundToInt().coerceIn(0, 100) else null,
        curious = terms.count { it.curious },
        hyperMiss = terms.count { it.hyperMiss },
    )
}
