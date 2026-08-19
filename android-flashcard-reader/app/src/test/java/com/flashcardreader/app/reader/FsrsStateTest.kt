package com.flashcardreader.app.reader

import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.FsrsState
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.fsrs.fsrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehension questions were put on the same schedule as vocabulary by pulling the scheduler out
 * onto plain state. That refactor is only safe if it changed nothing: a Term reviewed today must
 * come back on exactly the day it always would have. These tests are the proof of neutrality.
 */
class FsrsStateTest {

    private val fsrs = Fsrs()
    private val now = 1_750_000_000_000L

    private fun term() = Term(
        id = 1,
        normalizedText = "lantern",
        displayText = "lantern",
        definition = "a lamp in a case",
        createdAt = now,
    )

    @Test
    fun `scheduling a bare state matches scheduling a Term`() {
        for (rating in Rating.values()) {
            val viaTerm = fsrs.review(term(), rating, now)
            val viaState = fsrs.review(term().fsrsState(), rating, now)

            assertEquals("difficulty for $rating", viaTerm.difficulty, viaState.difficulty, 0.0)
            assertEquals("stability for $rating", viaTerm.stability, viaState.stability, 0.0)
            assertEquals("due for $rating", viaTerm.due, viaState.due)
            assertEquals("state for $rating", viaTerm.state, viaState.state)
            assertEquals("reps for $rating", viaTerm.reps, viaState.reps)
            assertEquals("lapses for $rating", viaTerm.lapses, viaState.lapses)
        }
    }

    @Test
    fun `a new card answered correctly is scheduled into the future`() {
        val next = fsrs.review(FsrsState(), Rating.GOOD, now)
        assertTrue("a correct answer must push the card out", (next.due ?: 0) > now)
        assertEquals(CardState.REVIEW, next.state)
        assertEquals(1, next.reps)
        assertEquals(0, next.lapses)
    }

    @Test
    fun `getting it wrong counts a lapse and brings it back sooner`() {
        val learned = fsrs.review(FsrsState(), Rating.EASY, now)
        val lapsed = fsrs.review(learned, Rating.AGAIN, now + 5 * 86_400_000L)

        assertEquals(1, lapsed.lapses)
        assertEquals(CardState.RELEARNING, lapsed.state)
        assertTrue(
            "a missed card must come back sooner than one that was known",
            (lapsed.due ?: 0) - (now + 5 * 86_400_000L) < (learned.due ?: 0) - now,
        )
    }

    @Test
    fun `a card never reviewed is due, one scheduled ahead is not`() {
        assertTrue(fsrs.isDue(null, now))
        assertTrue(fsrs.isDue(now - 1, now))
        assertTrue(!fsrs.isDue(now + 86_400_000L, now))
    }
}
