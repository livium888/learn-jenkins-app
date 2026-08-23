package com.flashcardreader.app.reader

import com.flashcardreader.app.data.fsrs.FsrsOptimizer
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.fsrs.ReviewSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The optimiser's whole claim is that it recovers a person's real memory strength from their review
 * history. The way to test that without waiting a month is to invent a history with a known answer
 * and check the fit finds it.
 */
class FsrsOptimizerTest {

    /** Reviews of a card whose true stability is [trueStability], sampled from the forgetting curve. */
    private fun history(
        trueStability: Double,
        rating: Rating,
        count: Int,
        seed: Int = 42,
    ): List<ReviewSample> {
        val random = Random(seed)
        return (1..count).map {
            val elapsed = random.nextDouble(0.5, trueStability * 3)
            val recalled = random.nextDouble() < FsrsOptimizer.retrievability(trueStability, elapsed)
            ReviewSample(rating, elapsed, recalled)
        }
    }

    @Test
    fun `recovers a known stability from its own forgetting curve`() {
        val fit = FsrsOptimizer.fitInitialStability(history(10.0, Rating.GOOD, 4000))
        val found = fit.perRating[Rating.GOOD]!!
        // Within 25%: sampling noise means an exact hit would be suspicious, not reassuring.
        assertTrue("expected around 10 days, fitted $found", found in 7.5..13.0)
    }

    @Test
    fun `a card that survives longer fits a larger stability`() {
        val weak = FsrsOptimizer.fitInitialStability(history(3.0, Rating.HARD, 3000)).perRating[Rating.HARD]!!
        val strong = FsrsOptimizer.fitInitialStability(history(40.0, Rating.EASY, 3000, seed = 7)).perRating[Rating.EASY]!!
        assertTrue("$strong should be well above $weak", strong > weak * 3)
    }

    @Test
    fun `each rating is fitted separately`() {
        val mixed = history(2.0, Rating.HARD, 1500) + history(30.0, Rating.EASY, 1500, seed = 9)
        val fit = FsrsOptimizer.fitInitialStability(mixed)
        assertEquals(2, fit.perRating.size)
        assertTrue(fit.perRating[Rating.EASY]!! > fit.perRating[Rating.HARD]!!)
    }

    @Test
    fun `too little history is left alone rather than fitted to noise`() {
        // Fitting five reviews would produce a confident number built on nothing, which is worse
        // than FSRS's published default - so a rating below the threshold is simply not returned.
        val fit = FsrsOptimizer.fitInitialStability(history(10.0, Rating.GOOD, 5))
        assertTrue(fit.perRating.isEmpty())
        assertFalse(fit.trustworthy)
    }

    @Test
    fun `enough history is marked trustworthy`() {
        val fit = FsrsOptimizer.fitInitialStability(history(10.0, Rating.GOOD, 200))
        assertTrue(fit.trustworthy)
        assertEquals(200, fit.samples)
    }

    @Test
    fun `nonsense intervals are discarded, not fitted`() {
        val bad = listOf(
            ReviewSample(Rating.GOOD, 0.0, true),
            ReviewSample(Rating.GOOD, -3.0, false),
            ReviewSample(Rating.GOOD, Double.NaN, true),
        )
        val fit = FsrsOptimizer.fitInitialStability(bad)
        assertEquals(0, fit.samples)
        assertTrue(fit.perRating.isEmpty())
    }

    @Test
    fun `an empty history returns nothing and claims nothing`() {
        val fit = FsrsOptimizer.fitInitialStability(emptyList())
        assertTrue(fit.perRating.isEmpty())
        assertFalse(fit.trustworthy)
    }

    @Test
    fun `a card always recalled fits a long stability`() {
        val always = (1..100).map { ReviewSample(Rating.EASY, 20.0, true) }
        val found = FsrsOptimizer.fitInitialStability(always).perRating[Rating.EASY]!!
        assertTrue("never forgotten at 20 days should fit well above 20, got $found", found > 20.0)
    }

    @Test
    fun `a card always forgotten fits a short stability`() {
        val never = (1..100).map { ReviewSample(Rating.HARD, 5.0, false) }
        val found = FsrsOptimizer.fitInitialStability(never).perRating[Rating.HARD]!!
        assertTrue("always forgotten by 5 days should fit well below 5, got $found", found < 2.0)
    }
}
