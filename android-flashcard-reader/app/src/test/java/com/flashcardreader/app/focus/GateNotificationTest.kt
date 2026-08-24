package com.flashcardreader.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification line is deliberately coarse, and these pin down why.
 *
 * The obvious design - a live mm:ss counter - was rejected: a total rising while you read invites
 * reading at the counter, and a smooth countdown while you spend manufactures urgency. Whole
 * minutes are the compromise, and the test that matters is the last one: the text has to hold
 * still for a whole minute, or the shade is being redrawn every second by the one-second poll.
 */
class GateNotificationTest {

    @Test
    fun `an empty balance says so, whatever is in front`() {
        assertEquals("Nothing banked - reading earns time.", GateNotification.text(0, null))
        assertEquals("Nothing banked - reading earns time.", GateNotification.text(0, "Instagram"))
        assertEquals("Nothing banked - reading earns time.", GateNotification.text(-5, null))
    }

    @Test
    fun `idle shows what is banked, in whole minutes`() {
        assertEquals("12 minutes banked.", GateNotification.text(725, null))
        assertEquals("1 minute banked.", GateNotification.text(60, null))
        assertEquals("Under a minute banked.", GateNotification.text(59, null))
    }

    @Test
    fun `spending names the app and what is left`() {
        assertEquals("3 minutes left in Instagram.", GateNotification.text(220, "Instagram"))
        assertEquals("1 minute left in Instagram.", GateNotification.text(119, "Instagram"))
        assertEquals("Under a minute left in Instagram.", GateNotification.text(30, "Instagram"))
    }

    @Test
    fun `seconds never show, so the poll cannot redraw the shade every second`() {
        // The service polls at 1Hz while a gated app is in front and posts only on a change of
        // text. Walking a whole minute of spending must produce exactly one distinct line.
        val minute = (179L downTo 120L).map { GateNotification.text(it, "Instagram") }.distinct()
        assertEquals("a minute of spending must be one unchanging line", 1, minute.size)
        assertEquals("2 minutes left in Instagram.", minute.single())

        // And across a longer stretch, one line per minute - not one per second.
        val tenMinutes = (600L downTo 1L).map { GateNotification.text(it, "Instagram") }.distinct()
        assertTrue("got ${tenMinutes.size} distinct lines over ten minutes", tenMinutes.size <= 11)
    }
}
