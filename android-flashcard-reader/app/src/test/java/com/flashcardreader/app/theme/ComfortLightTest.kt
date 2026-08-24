package com.flashcardreader.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortLightTest {

    private fun at(hour: Int, minute: Int = 0, max: Float = 1f) =
        ComfortLight.warmthAt(hour * 60 + minute, maximum = max)

    @Test
    fun `daytime is not warmed at all`() {
        assertEquals(0f, at(9), 0.0001f)
        assertEquals(0f, at(13), 0.0001f)
        assertEquals(0f, at(19, 59), 0.0001f)
    }

    @Test
    fun `it ramps rather than switching on`() {
        // 20:00 starts at nothing, 21:00 is halfway to full, 22:00 is full.
        assertEquals(0f, at(20), 0.0001f)
        assertEquals(0.5f, at(21), 0.01f)
        assertEquals(1f, at(22), 0.0001f)
    }

    @Test
    fun `the window carries on through midnight`() {
        // This is the case the arithmetic has to get right: 23:30 and 01:00 are both night.
        assertEquals(1f, at(23, 30), 0.0001f)
        assertEquals(1f, at(0, 30), 0.0001f)
        assertEquals(1f, at(3), 0.0001f)
        assertEquals(1f, at(6, 59), 0.0001f)
    }

    @Test
    fun `morning ends it`() {
        assertEquals(0f, at(7), 0.0001f)
        assertEquals(0f, at(8), 0.0001f)
    }

    @Test
    fun `the reader's chosen strength is the ceiling, not a starting point`() {
        assertEquals(0.4f, ComfortLight.warmthAt(23 * 60, maximum = 0.4f), 0.0001f)
        assertEquals(0.2f, ComfortLight.warmthAt(21 * 60, maximum = 0.4f), 0.01f)
        // Turned off entirely means off, whatever the hour.
        assertEquals(0f, ComfortLight.warmthAt(23 * 60, maximum = 0f), 0.0001f)
    }

    @Test
    fun `custom hours are honoured`() {
        // Someone who goes to bed late: start at 22, full by 23, done at 5.
        val late = { h: Int -> ComfortLight.warmthAt(h * 60, 1f, startHour = 22, fullHour = 23, endHour = 5) }
        assertEquals(0f, late(21), 0.0001f)
        assertEquals(0f, late(22), 0.0001f)
        assertEquals(1f, late(23), 0.0001f)
        assertEquals(1f, late(4), 0.0001f)
        assertEquals(0f, late(6), 0.0001f)
    }

    @Test
    fun `a minute outside the day does not throw or wrap wrongly`() {
        assertTrue(ComfortLight.warmthAt(-30, 1f) in 0f..1f)
        assertTrue(ComfortLight.warmthAt(5_000, 1f) in 0f..1f)
    }
}
