package com.flashcardreader.app.reader

import com.flashcardreader.app.data.repository.ReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPaceTest {

    private fun day(d: Long, seconds: Long, words: Long) = ReadingDay(d, seconds, seconds, words)

    @Test
    fun `with nothing read, the published average stands in`() {
        val pace = ReadingPace.paceFrom(emptyList(), todayEpochDay = 100)
        assertEquals(ReadingPace.ASSUMED_WPM, pace.wordsPerMinute)
        assertFalse("and it must not be passed off as the reader's own", pace.measured)
    }

    @Test
    fun `a little reading is not enough to claim a personal pace`() {
        // 500 words is one page-ish. Deriving "your pace" from that would be a made-up number.
        val history = listOf(day(100, seconds = 120, words = 500))
        assertFalse(ReadingPace.paceFrom(history, 100).measured)
    }

    @Test
    fun `enough reading gives the reader's own pace`() {
        // 6000 words in 1800s = 200 wpm.
        val history = listOf(day(99, 900, 3_000), day(100, 900, 3_000))
        val pace = ReadingPace.paceFrom(history, 100)
        assertTrue(pace.measured)
        assertEquals(200, pace.wordsPerMinute)
    }

    @Test
    fun `days outside the window do not count towards the pace`() {
        val history = listOf(day(1, 900, 3_000), day(2, 900, 3_000))
        // Those days are far behind the window, so there is nothing recent to measure.
        assertFalse(ReadingPace.paceFrom(history, todayEpochDay = 100).measured)
    }

    @Test
    fun `an absurd pace is refused rather than shown`() {
        // A corrupt or mis-recorded day must never produce "3 seconds left in this book".
        val tooFast = listOf(day(100, seconds = 60, words = 100_000))
        assertFalse(ReadingPace.paceFrom(tooFast, 100).measured)
        val tooSlow = listOf(day(100, seconds = 100_000, words = 3_000))
        assertFalse(ReadingPace.paceFrom(tooSlow, 100).measured)
    }

    @Test
    fun `minutes are whole and never negative`() {
        val pace = ReadingPace.Pace(200, measured = true)
        assertEquals(10, ReadingPace.minutesFor(2_000, pace))
        assertEquals(0, ReadingPace.minutesFor(0, pace))
        assertEquals(0, ReadingPace.minutesFor(-500, pace))
    }

    @Test
    fun `words left are estimated from the book's own word length`() {
        assertEquals(1_000, ReadingPace.wordsRemaining(charsRemaining = 6_000, charsPerWord = 6f))
        assertEquals(0, ReadingPace.wordsRemaining(charsRemaining = 0, charsPerWord = 6f))
        // A nonsense word length falls back rather than dividing by zero.
        assertTrue(ReadingPace.wordsRemaining(6_000, charsPerWord = 0f) > 0)
    }

    @Test
    fun `the label reads like a sentence at every scale`() {
        assertEquals("Under a minute left in this chapter", ReadingPace.label(0, ReadingPace.IN_CHAPTER))
        assertEquals("1 min left in this chapter", ReadingPace.label(1, ReadingPace.IN_CHAPTER))
        assertEquals("12 min left in this chapter", ReadingPace.label(12, ReadingPace.IN_CHAPTER))
        assertEquals("1 hr left in this book", ReadingPace.label(60, ReadingPace.IN_BOOK))
        assertEquals("1 hr 5 min left in this book", ReadingPace.label(65, ReadingPace.IN_BOOK))
        assertEquals("3 hrs 20 min left in this book", ReadingPace.label(200, ReadingPace.IN_BOOK))
    }
}
