package com.flashcardreader.app.data.repository

import android.content.Context
import com.flashcardreader.app.data.db.dao.ReviewLogDao
import com.flashcardreader.app.data.db.entities.CardKind
import com.flashcardreader.app.data.db.entities.ReviewLog
import com.flashcardreader.app.data.fsrs.FsrsOptimizer
import com.flashcardreader.app.data.fsrs.FsrsWeights
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.fsrs.ReviewSample

/**
 * Records every answer, and now and then re-fits the scheduler to what those answers show.
 *
 * Both card types write here - a word recalled and a comprehension question answered are both
 * evidence about the same memory - so the fit gets roughly twice the history it otherwise would.
 */
class ReviewHistory(
    context: Context,
    private val dao: ReviewLogDao,
) {
    private val weights = FsrsWeights(context)

    /** The fitted initial stability to schedule with, or empty to use FSRS's published defaults. */
    fun fittedInitialStability(): Map<Rating, Double> = weights.initialStability

    val fittedFromReviews: Int get() = weights.fittedFromReviews

    /**
     * Logs one answer. [stabilityBefore] and [difficultyBefore] are the card's state *before* the
     * answer, because that is what any later fit has to explain.
     */
    suspend fun record(
        cardId: Long,
        kind: CardKind,
        rating: Rating,
        stabilityBefore: Double,
        difficultyBefore: Double,
        lastReviewedAt: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.insert(
            ReviewLog(
                cardId = cardId,
                cardKind = kind,
                rating = rating.value,
                elapsedDays = lastReviewedAt?.let { (now - it) / 86_400_000.0 } ?: 0.0,
                stabilityBefore = stabilityBefore,
                difficultyBefore = difficultyBefore,
                wasFirstReview = lastReviewedAt == null,
                reviewedAt = now,
            ),
        )
    }

    /**
     * Re-fits if enough new history has accumulated. Returns true when the schedule actually
     * changed, so the caller can say so rather than the numbers shifting silently.
     */
    suspend fun refitIfDue(): Boolean {
        val count = dao.count()
        if (!weights.shouldRefit(count)) return false
        val samples = dao.firstAnswerOutcomes().map { outcome ->
            ReviewSample(
                firstRating = ratingOf(outcome.firstRating),
                elapsedDays = outcome.elapsedDays,
                // Anything but AGAIN counts as remembered - the same line the scheduler draws.
                recalled = outcome.laterRating != Rating.AGAIN.value,
            )
        }
        val fit = FsrsOptimizer.fitInitialStability(samples)
        if (fit.perRating.isEmpty()) return false
        weights.save(fit)
        return true
    }

    private fun ratingOf(value: Int): Rating =
        Rating.values().firstOrNull { it.value == value } ?: Rating.GOOD
}
