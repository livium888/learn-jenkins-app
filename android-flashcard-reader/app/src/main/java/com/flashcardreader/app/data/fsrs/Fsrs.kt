package com.flashcardreader.app.data.fsrs

import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import kotlin.math.exp
import kotlin.math.pow

/** How the user answered a flashcard, same 4-point scale Anki/FSRS use. */
enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

/** How sure the user felt before revealing - used to detect high-confidence errors. */
enum class Confidence { GUESSING, UNSURE, CONFIDENT }

/**
 * Kotlin implementation of the FSRS (Free Spaced Repetition Scheduler) algorithm
 * from the open-spaced-repetition project (https://github.com/open-spaced-repetition).
 *
 * FSRS replaces naive "quiz every time you see it" with a model of each card's
 * Difficulty and Stability, and predicts Retrievability - the probability you'd
 * still recall it right now - using a power-law forgetting curve. A card is
 * only "due" once predicted retrievability drops to [requestRetention] (default
 * 90%), which is what lets the reader skip re-testing a word you just saw two
 * pages ago (massed repetition) while still catching it before you'd actually
 * forget it.
 *
 * The weights below are FSRS's published default parameters, meant as a cold
 * start. FSRS is designed to be periodically re-fit ("optimized") against a
 * user's actual review history for materially better scheduling once there's
 * enough data (a few hundred reviews) - that optimizer is not implemented here
 * yet and is a good follow-up once real usage data exists.
 */
class Fsrs(private val requestRetention: Double = 0.9) {

    companion object {
        // Power-law forgetting curve constants (fit so that R(t=S) == 0.9).
        private const val DECAY = -0.5
        private val FACTOR = 19.0 / 81.0

        // FSRS-4.5 default parameter weights (w0..w18).
        private val W = doubleArrayOf(
            0.4072, 1.1829, 3.1262, 15.4722, 7.2102, 0.5316, 1.0651, 0.0234,
            1.616, 0.1544, 1.0824, 1.9813, 0.0953, 0.2975, 2.2042, 0.2407,
            2.9466, 0.5034, 0.6567,
        )
    }

    /** Predicted probability of recall right now, given elapsed days since last review. */
    fun retrievability(stability: Double, elapsedDays: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    /** Days until retrievability would decay to [requestRetention]. */
    private fun nextIntervalDays(stability: Double): Double =
        (stability / FACTOR) * (requestRetention.pow(1.0 / DECAY) - 1.0)

    private fun initStability(rating: Rating): Double = W[rating.value - 1].coerceAtLeast(0.1)

    private fun initDifficulty(rating: Rating): Double =
        (W[4] - (rating.value - 3) * W[5]).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, rating: Rating): Double {
        val delta = d - W[6] * (rating.value - 3)
        val reverted = W[7] * initDifficulty(Rating.EASY) + (1 - W[7]) * delta
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun nextStabilityOnRecall(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) W[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) W[16] else 1.0
        return s * (
            1 + exp(W[8]) *
                (11 - d) *
                s.pow(-W[9]) *
                (exp((1 - r) * W[10]) - 1) *
                hardPenalty *
                easyBonus
            )
    }

    private fun nextStabilityOnLapse(d: Double, s: Double, r: Double): Double =
        W[11] * d.pow(-W[12]) * ((s + 1).pow(W[13]) - 1) * exp((1 - r) * W[14])

    /**
     * Apply a review (the user's answer to a flashcard) and return the updated
     * card state, including the new due date.
     */
    fun review(term: Term, rating: Rating, now: Long): Term {
        val next = review(term.fsrsState(), rating, now)
        return term.copy(
            difficulty = next.difficulty,
            stability = next.stability,
            due = next.due,
            lastReviewedAt = next.lastReviewedAt,
            reps = next.reps,
            lapses = next.lapses,
            state = next.state,
        )
    }

    /**
     * The scheduler itself, over nothing but scheduling state.
     *
     * Kept separate from [Term] because a vocabulary word is no longer the only thing worth
     * spacing: a reading-comprehension question earns a place on the same schedule, and it must
     * *not* be a Term - Terms exist to be matched against book text (see TermScanner), and a
     * question about a passage must never be searched for inside prose.
     */
    fun review(card: FsrsState, rating: Rating, now: Long): FsrsState {
        val elapsedDays = card.lastReviewedAt?.let { (now - it) / 86_400_000.0 } ?: 0.0

        val newDifficulty: Double
        val newStability: Double
        val newState: CardState

        if (card.state == CardState.NEW) {
            newDifficulty = initDifficulty(rating)
            newStability = initStability(rating)
            newState = if (rating == Rating.AGAIN) CardState.LEARNING else CardState.REVIEW
        } else {
            val r = retrievability(card.stability, elapsedDays)
            newDifficulty = nextDifficulty(card.difficulty, rating)
            newStability = if (rating == Rating.AGAIN) {
                nextStabilityOnLapse(card.difficulty, card.stability, r)
            } else {
                nextStabilityOnRecall(card.difficulty, card.stability, r, rating)
            }
            newState = if (rating == Rating.AGAIN) CardState.RELEARNING else CardState.REVIEW
        }

        val intervalDays = nextIntervalDays(newStability).coerceAtLeast(1.0 / 1440.0)
        val dueAt = now + (intervalDays * 86_400_000.0).toLong()

        return FsrsState(
            difficulty = newDifficulty,
            stability = newStability,
            due = dueAt,
            lastReviewedAt = now,
            reps = card.reps + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
            state = newState,
        )
    }

    /** True if this term should interrupt reading and show its flashcard right now. */
    fun isDue(term: Term, now: Long): Boolean = term.due == null || term.due <= now

    /** True if any scheduled card has come around again. Null due = never reviewed, so due now. */
    fun isDue(due: Long?, now: Long): Boolean = due == null || due <= now
}

/**
 * The scheduling fields FSRS actually operates on, shared by everything that can be spaced.
 * Anything reviewable carries one of these; what the card *says* is the card's own business.
 */
data class FsrsState(
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    /** Epoch millis of the next scheduled review. Null = never reviewed yet (due immediately). */
    val due: Long? = null,
    val lastReviewedAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW,
)

/** A Term's scheduling state, so the shared scheduler can be handed one. */
fun Term.fsrsState(): FsrsState = FsrsState(
    difficulty = difficulty,
    stability = stability,
    due = due,
    lastReviewedAt = lastReviewedAt,
    reps = reps,
    lapses = lapses,
    state = state,
)
