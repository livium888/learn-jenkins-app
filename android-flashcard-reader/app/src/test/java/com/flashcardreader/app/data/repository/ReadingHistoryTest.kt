package com.flashcardreader.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading history used to be destroyed at midnight - and destroyed by the getters, so opening
 * Progress just after midnight was what erased the night before. These pin down the replacement.
 */
class ReadingHistoryTest {

    private fun day(d: Long, open: Long, read: Long) = ReadingDay(d, open, read)

    @Test
    fun `a finished day is filed, not discarded`() {
        val history = ReadingHistory.rolledInto(emptyList(), day(100, open = 600, read = 400))
        assertEquals(1, history.size)
        assertEquals(400, history.single().readSeconds)
    }

    @Test
    fun `filing the same day twice replaces it rather than duplicating it`() {
        var history = ReadingHistory.rolledInto(emptyList(), day(100, 600, 400))
        history = ReadingHistory.rolledInto(history, day(100, 900, 700))
        assertEquals(1, history.size)
        assertEquals(700, history.single().readSeconds)
    }

    @Test
    fun `a day with nothing in it is not recorded`() {
        assertEquals(emptyList<ReadingDay>(), ReadingHistory.rolledInto(emptyList(), day(100, 0, 0)))
    }

    @Test
    fun `history is capped, oldest dropped first`() {
        var history = emptyList<ReadingDay>()
        for (d in 1L..(ReadingHistory.MAX_DAYS + 10)) {
            history = ReadingHistory.rolledInto(history, day(d, 600, 300))
        }
        assertEquals(ReadingHistory.MAX_DAYS, history.size)
        assertEquals("the oldest days are the ones dropped", 11L, history.first().epochDay)
    }

    @Test
    fun `a round trip through storage keeps every day`() {
        val history = listOf(day(10, 600, 400), day(11, 300, 120), day(12, 60, 0))
        assertEquals(history, ReadingHistory.parse(ReadingHistory.serialize(history)))
    }

    @Test
    fun `a corrupted row costs that day, not the whole history`() {
        val raw = "10,600,400;nonsense;12,300,120;13,not-a-number,5"
        val parsed = ReadingHistory.parse(raw)
        assertEquals(listOf(10L, 12L), parsed.map { it.epochDay })
    }

    @Test
    fun `empty storage parses to nothing rather than throwing`() {
        assertEquals(emptyList<ReadingDay>(), ReadingHistory.parse(""))
    }

    @Test
    fun `the last thirty days counts days read, not days opened`() {
        val history = listOf(
            day(70, 600, 0), // opened, never actually read
            day(95, 600, 400),
            day(99, 600, 300),
            day(100, 600, 200),
        )
        assertEquals(3, ReadingHistory.daysReadIn(history, todayEpochDay = 100, days = 30))
        assertEquals(900, ReadingHistory.readSecondsIn(history, todayEpochDay = 100, days = 30))
        // A window of 2 is today and yesterday - days 99 and 100, both read.
        assertEquals(2, ReadingHistory.daysReadIn(history, todayEpochDay = 100, days = 2))
        // A window of 1 is today alone.
        assertEquals(1, ReadingHistory.daysReadIn(history, todayEpochDay = 100, days = 1))
        assertEquals(200, ReadingHistory.readSecondsIn(history, todayEpochDay = 100, days = 1))
    }

    @Test
    fun `attention is withheld until the day is long enough to mean anything`() {
        assertNull("a ten-second day says nothing", day(1, open = 10, read = 10).attentionPct)
        assertEquals(50, day(1, open = 600, read = 300).attentionPct)
        assertEquals(100, day(1, open = 600, read = 600).attentionPct)
        // Verified reading can never exceed time open, but clamp rather than show 130%.
        assertTrue(day(1, open = 600, read = 900).attentionPct!! <= 100)
    }
}
