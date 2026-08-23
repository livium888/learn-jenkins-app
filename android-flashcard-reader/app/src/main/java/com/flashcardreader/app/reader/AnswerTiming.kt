package com.flashcardreader.app.reader

import com.flashcardreader.app.data.fsrs.Confidence

/**
 * Infers how sure someone was from how long they took, instead of asking them.
 *
 * The app used to ask outright, with a three-way control tapped before revealing. That answer fed
 * two genuinely useful things - the calibration card, and the hypercorrection flag for being sure
 * and wrong - but it cost a tap on every single card, and multiple choice is supposed to be one
 * tap and done.
 *
 * Answer latency is a well-established stand-in: retrieval that is fluent is fast, and retrieval
 * that is effortful or guessed is slow. It is a proxy, not a report, and it is worth being honest
 * that it is: someone re-reading the options carefully will look less sure than they are.
 *
 * Pure and separate so the thresholds are testable rather than felt.
 */
object AnswerTiming {

    /** Faster than this and the answer came without searching for it. */
    const val CONFIDENT_MS = 3_500L

    /** Slower than this and it was worked out, or guessed. */
    const val UNSURE_MS = 9_000L

    fun confidenceFor(elapsedMs: Long): Confidence = when {
        elapsedMs <= 0L -> Confidence.UNSURE
        elapsedMs < CONFIDENT_MS -> Confidence.CONFIDENT
        elapsedMs < UNSURE_MS -> Confidence.UNSURE
        else -> Confidence.GUESSING
    }
}
