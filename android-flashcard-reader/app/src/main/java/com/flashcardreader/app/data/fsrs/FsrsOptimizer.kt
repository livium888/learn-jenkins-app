package com.flashcardreader.app.data.fsrs

import kotlin.math.ln
import kotlin.math.pow

/**
 * One recorded review, reduced to only what fitting needs.
 *
 * [elapsedDays] is the gap since that card's previous review, and [recalled] whether it was
 * remembered - anything but AGAIN.
 */
data class ReviewSample(
    val firstRating: Rating,
    val elapsedDays: Double,
    val recalled: Boolean,
)

/** The fitted result, with enough context to decide whether it is worth believing. */
data class FittedStability(
    val perRating: Map<Rating, Double>,
    val samples: Int,
) {
    /** Whether there is enough history for these numbers to beat FSRS's published defaults. */
    val trustworthy: Boolean get() = samples >= FsrsOptimizer.MIN_SAMPLES
}

/**
 * Fits *initial stability* - how long a card survives after its very first answer - to the review
 * history actually recorded on this device.
 *
 * FSRS ships default weights as a cold start, and its authors are explicit that re-fitting them to
 * a real person's history is where the accuracy comes from. Fitting all nineteen weights needs
 * gradient descent over thousands of reviews; the first four, which set initial stability per
 * rating, carry most of the early benefit and can be fitted exactly - each is a single number, so a
 * direct search over plausible values finds the best one without any optimiser machinery at all.
 *
 * The measure is log-loss against the forgetting curve: for a fitted stability S, the model predicts
 * recall probability R(t) = (1 + F·t/S)^-0.5 at t days, and the best S is the one that made the
 * outcomes actually observed least surprising. Deliberately pure Kotlin with no Android or storage
 * dependency, so the maths is covered by tests rather than only by living with it for a month.
 */
object FsrsOptimizer {

    /** Below this many reviews for a rating, the default weight is left alone. */
    const val MIN_SAMPLES = 20

    private const val DECAY = -0.5
    private val FACTOR = 19.0 / 81.0

    /** Stability is searched over this range, in days - a minute to about five years. */
    private const val MIN_STABILITY = 0.02
    private const val MAX_STABILITY = 1_800.0

    /**
     * Returns the best-fitting initial stability per rating, using only ratings with enough
     * history. Ratings without enough data are simply absent, and the caller keeps the default.
     */
    fun fitInitialStability(samples: List<ReviewSample>): FittedStability {
        val usable = samples.filter { it.elapsedDays > 0 && it.elapsedDays.isFinite() }
        val fitted = usable
            .groupBy { it.firstRating }
            .filterValues { it.size >= MIN_SAMPLES }
            .mapValues { (_, group) -> bestStability(group) }
        return FittedStability(fitted, usable.size)
    }

    /**
     * The stability that best explains one rating's outcomes.
     *
     * A coarse sweep followed by a refinement around the winner: the loss curve in S is smooth and
     * single-peaked, so this lands on the same answer a gradient method would, with none of the
     * step-size and convergence tuning that would then need its own tests.
     */
    private fun bestStability(group: List<ReviewSample>): Double {
        var best = 1.0
        var bestLoss = Double.MAX_VALUE
        var low = MIN_STABILITY
        var high = MAX_STABILITY

        repeat(REFINEMENTS) {
            val step = (high - low) / SWEEP_STEPS
            var s = low
            while (s <= high) {
                val loss = logLoss(group, s)
                if (loss < bestLoss) {
                    bestLoss = loss
                    best = s
                }
                s += step
            }
            low = (best - step).coerceAtLeast(MIN_STABILITY)
            high = (best + step).coerceAtMost(MAX_STABILITY)
        }
        return best
    }

    /** How surprised the model was by what actually happened. Lower is a better fit. */
    private fun logLoss(group: List<ReviewSample>, stability: Double): Double {
        var total = 0.0
        for (sample in group) {
            val predicted = retrievability(stability, sample.elapsedDays).coerceIn(EPSILON, 1 - EPSILON)
            total -= if (sample.recalled) ln(predicted) else ln(1 - predicted)
        }
        return total / group.size
    }

    /** The same forgetting curve the scheduler uses, kept here so the fit matches the model. */
    fun retrievability(stability: Double, elapsedDays: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    private const val SWEEP_STEPS = 240
    private const val REFINEMENTS = 4
    private const val EPSILON = 1e-6
}
