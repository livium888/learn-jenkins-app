package com.flashcardreader.app.data.fsrs

import android.content.Context

/**
 * Remembers the initial-stability values fitted to this device's review history, and decides when
 * it is worth fitting again.
 *
 * Refitting on every answer would be wasteful and would make the schedule twitch after each review;
 * refitting never would mean the history is collected and then ignored. So it happens on a rising
 * scale - often while there is little data and each new review changes the picture, rarely once
 * there is enough that it barely moves.
 */
class FsrsWeights(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The fitted values, or empty when there has never been enough history to fit. */
    val initialStability: Map<Rating, Double>
        get() = Rating.values().mapNotNull { rating ->
            val stored = prefs.getFloat(key(rating), 0f)
            if (stored > 0f) rating to stored.toDouble() else null
        }.toMap()

    /** How many reviews the stored fit was built from - shown so the number can be judged. */
    val fittedFromReviews: Int get() = prefs.getInt(KEY_SAMPLES, 0)

    fun save(fit: FittedStability) {
        val editor = prefs.edit()
        fit.perRating.forEach { (rating, stability) -> editor.putFloat(key(rating), stability.toFloat()) }
        editor.putInt(KEY_SAMPLES, fit.samples)
        editor.putInt(KEY_LAST_FIT_AT, fit.samples)
        editor.apply()
    }

    /**
     * Whether it is time to fit again, given how many reviews exist now.
     *
     * The gap widens as history grows: at 20 reviews another 20 changes the answer materially, at
     * 2,000 it cannot.
     */
    fun shouldRefit(reviewCount: Int): Boolean {
        if (reviewCount < FsrsOptimizer.MIN_SAMPLES) return false
        val lastFitAt = prefs.getInt(KEY_LAST_FIT_AT, 0)
        val gap = when {
            reviewCount < 100 -> 25
            reviewCount < 500 -> 100
            else -> 500
        }
        return reviewCount - lastFitAt >= gap
    }

    private fun key(rating: Rating) = "$KEY_PREFIX${rating.name}"

    private companion object {
        const val PREFS = "fsrs_weights"
        const val KEY_PREFIX = "initial_stability_"
        const val KEY_SAMPLES = "fitted_from"
        const val KEY_LAST_FIT_AT = "last_fit_at"
    }
}
