package com.flashcardreader.app.reader

import com.flashcardreader.app.data.fsrs.Rating
import org.junit.Assert.assertEquals
import org.junit.Test

class PassScoreTest {

    /** Six claims, three of which the chapter really made. */
    private val said = setOf(0, 2, 4)
    private val total = 6

    @Test
    fun `judging every claim right scores one`() {
        assertEquals(1.0, PassScore.claimScore(chosen = said, said = said, total = total), 0.0001)
    }

    @Test
    fun `leaving a false claim untapped counts as getting it right`() {
        // Noticing what a chapter did not say is half of having read it.
        assertEquals(1.0, PassScore.claimScore(chosen = setOf(0, 2, 4), said = said, total = total), 0.0001)
    }

    @Test
    fun `tapping everything is not a strategy`() {
        val all = (0 until total).toSet()
        assertEquals(0.5, PassScore.claimScore(all, said, total), 0.0001)
        // And tapping nothing scores exactly the same - neither is a way through.
        assertEquals(0.5, PassScore.claimScore(emptySet(), said, total), 0.0001)
    }

    @Test
    fun `both halves weigh the same`() {
        assertEquals(0.5, PassScore.combined(claims = 1.0, order = 0.0), 0.0001)
        assertEquals(0.5, PassScore.combined(claims = 0.0, order = 1.0), 0.0001)
    }

    @Test
    fun `the rating comes from the score, never from the reader`() {
        assertEquals(Rating.GOOD, PassScore.rating(1.0))
        assertEquals(Rating.GOOD, PassScore.rating(PassScore.GOOD_AT))
        assertEquals(Rating.HARD, PassScore.rating(PassScore.GOOD_AT - 0.01))
        assertEquals(Rating.HARD, PassScore.rating(PassScore.HARD_AT))
        assertEquals(Rating.AGAIN, PassScore.rating(PassScore.HARD_AT - 0.01))
        assertEquals(Rating.AGAIN, PassScore.rating(0.0))
    }

    @Test
    fun `a perfect claim sweep with a shuffled order is not a pass`() {
        // Recognising the claims while having no idea of the sequence lands on HARD, not GOOD.
        val combined = PassScore.combined(claims = 1.0, order = HintOrder.score(listOf(4, 3, 2, 1, 0)))
        assertEquals(Rating.HARD, PassScore.rating(combined))
    }
}
