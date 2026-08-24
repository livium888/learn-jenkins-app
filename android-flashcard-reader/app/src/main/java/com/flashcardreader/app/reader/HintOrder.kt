package com.flashcardreader.app.reader

/**
 * Scores an attempt at putting a chapter's hints back into the order the chapter made them.
 *
 * This is Franklin's exercise, from his Autobiography: reduce each sentence to a short hint, jumble
 * the hints "into confusion", and weeks later reduce them back to the best order, "to teach him
 * method in the arrangement of thoughts". What supports it experimentally is not Franklin - it is
 * the generation effect: producing the structure beats recognising it.
 *
 * Scoring is by *adjacent pairs*, not by absolute position. Getting one hint wrong shifts every
 * hint after it, so position-matching would score a single slip as near-total failure and the
 * rating would swing wildly on a small mistake. Counting how many consecutive pairs are still in
 * the right relative order degrades the way a reader would expect.
 */
object HintOrder {

    /**
     * Fraction of adjacent pairs that are in the right order, 0..1.
     *
     * [attempt] holds the hints' true indices in the order the reader placed them.
     */
    fun score(attempt: List<Int>): Double {
        if (attempt.size < 2) return if (attempt.isEmpty()) 0.0 else 1.0
        val pairs = attempt.size - 1
        val right = (0 until pairs).count { attempt[it] < attempt[it + 1] }
        return right.toDouble() / pairs
    }

    /** True when [attempt] is exactly the order the chapter made them. */
    fun isPerfect(attempt: List<Int>): Boolean = attempt == attempt.sorted()
}
