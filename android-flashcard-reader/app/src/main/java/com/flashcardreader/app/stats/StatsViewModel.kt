package com.flashcardreader.app.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.repository.CalibrationLevel
import com.flashcardreader.app.data.repository.CalibrationStore
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.SharingStarted
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
)

class StatsViewModel(
    termRepository: TermRepository,
    private val calibration: CalibrationStore,
) : ViewModel() {
    val stats: StateFlow<Stats> = termRepository.observeAll()
        .map { terms ->
            val snapshot = calibration.snapshot()
            computeStats(terms).copy(calibration = snapshot.levels, calibrationNote = snapshot.note)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())
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
