package com.flashcardreader.app.reader

import com.flashcardreader.app.data.fsrs.Confidence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Confidence used to be a tap. It is now inferred from how long the answer took, so that a
 * multiple-choice card really is one tap - and the thresholds are the whole feature, so they are
 * pinned down here rather than tuned by feel.
 */
class AnswerTimingTest {

    @Test
    fun `an instant answer reads as confident`() {
        assertEquals(Confidence.CONFIDENT, AnswerTiming.confidenceFor(400))
        assertEquals(Confidence.CONFIDENT, AnswerTiming.confidenceFor(AnswerTiming.CONFIDENT_MS - 1))
    }

    @Test
    fun `a considered answer reads as unsure`() {
        assertEquals(Confidence.UNSURE, AnswerTiming.confidenceFor(AnswerTiming.CONFIDENT_MS))
        assertEquals(Confidence.UNSURE, AnswerTiming.confidenceFor(AnswerTiming.UNSURE_MS - 1))
    }

    @Test
    fun `a long deliberation reads as a guess`() {
        assertEquals(Confidence.GUESSING, AnswerTiming.confidenceFor(AnswerTiming.UNSURE_MS))
        assertEquals(Confidence.GUESSING, AnswerTiming.confidenceFor(60_000))
    }

    @Test
    fun `a missing or nonsensical time does not claim confidence`() {
        // Erring towards "unsure" matters: CONFIDENT plus a wrong answer sets the hypercorrection
        // flag, and claiming that on a card whose timing was never recorded would be a fabrication.
        assertEquals(Confidence.UNSURE, AnswerTiming.confidenceFor(0))
        assertEquals(Confidence.UNSURE, AnswerTiming.confidenceFor(-1))
    }
}
