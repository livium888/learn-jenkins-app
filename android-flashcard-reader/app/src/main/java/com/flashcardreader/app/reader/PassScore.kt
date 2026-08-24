package com.flashcardreader.app.reader

import com.flashcardreader.app.data.fsrs.Rating

/**
 * Turns a finished chapter pass into a rating, without asking the reader how they think they did.
 *
 * Same principle as the comprehension questions: the rating is evidence, not self-report. Two tasks
 * are scored and averaged - recognising what the chapter actually said, and putting its steps back
 * in order - because either alone is gameable. Tapping every claim scores well on the first and
 * tells you nothing; a lucky shuffle scores well on the second.
 */
object PassScore {

    /** At or above this, the chapter held together well enough to space it out. */
    const val GOOD_AT = 0.8

    /** Below [GOOD_AT] but at or above this: shaky, bring it back sooner. */
    const val HARD_AT = 0.5

    /**
     * How many claims were judged correctly, as a fraction.
     *
     * [chosen] is what the reader tapped; [said] is which of them the chapter really made. Both are
     * indices into the same list. Leaving a false claim untapped counts, exactly as tapping a true
     * one does - noticing what a chapter did *not* say is half of having read it.
     */
    fun claimScore(chosen: Set<Int>, said: Set<Int>, total: Int): Double {
        if (total <= 0) return 0.0
        val right = (0 until total).count { (it in chosen) == (it in said) }
        return right.toDouble() / total
    }

    /** The two halves weigh the same; neither is worth more than the other. */
    fun combined(claims: Double, order: Double): Double = (claims + order) / 2

    fun rating(combined: Double): Rating = when {
        combined >= GOOD_AT -> Rating.GOOD
        combined >= HARD_AT -> Rating.HARD
        else -> Rating.AGAIN
    }
}
