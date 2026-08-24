package com.flashcardreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are the executable version of the feature's promise: reading earns credit, faking
 * it does not. Everything else in Focus Gate (service, overlay, permissions) can only be checked on
 * a device, so this is where the anti-fake rules are actually pinned down.
 */
class ReadingCreditTrackerTest {

    private val capWpm = 400
    private val idleMs = 60_000L
    private val pageWords = 260 // a typical ~1600-char chunk

    private fun tracker() = ReadingCreditTracker(capWpm = capWpm, idleTimeoutMs = idleMs)

    /** Reads one page attentively, keeping a human present, and returns the ticks that paid out. */
    private fun readOnePage(
        t: ReadingCreditTracker,
        chunk: Int = 0,
        words: Int = pageWords,
        totalMs: Long = 90_000L,
        startAt: Long = 1_000L,
    ): List<CreditTick> {
        val results = mutableListOf<CreditTick>()
        var now = startAt
        t.noteInteraction(now)
        var elapsed = 0L
        while (elapsed < totalMs) {
            now += 500
            elapsed += 500
            // A real reader touches the screen from time to time.
            if (elapsed % 10_000L == 0L) t.noteInteraction(now)
            results += t.tick(now, chunk, words, 500)
        }
        return results
    }

    @Test
    fun `attentive reading earns credit for the page`() {
        val t = tracker()
        val paid = readOnePage(t).filter { it.creditedChunk != null }
        assertEquals("page should pay exactly once", 1, paid.size)
        assertEquals(0, paid.first().creditedChunk)
        assertEquals(pageWords, paid.first().creditedWords)
    }

    @Test
    fun `flinging through pages earns nothing`() {
        val t = tracker()
        var now = 1_000L
        t.noteInteraction(now)
        var credited = 0
        // 40 pages blur past in about 4 seconds.
        for (chunk in 0 until 40) {
            now += 100
            t.noteInteraction(now)
            if (t.tick(now, chunk, pageWords, 100).creditedChunk != null) credited++
        }
        assertEquals("a fling must never pay", 0, credited)
    }

    @Test
    fun `auto-scrolling with nobody touching the screen stops paying`() {
        val t = tracker()
        var now = 1_000L
        t.noteInteraction(now) // the tap that started auto-scroll
        var credited = 0
        var sawIdle = false
        // Auto-scroll advances the page but never reports interaction.
        for (i in 0 until 600) {
            now += 500
            val res = t.tick(now, i / 100, pageWords, 500)
            if (res.creditedChunk != null) credited++
            if (res.idle) sawIdle = true
        }
        assertTrue("should report idle once the human stops touching", sawIdle)
        assertTrue("hands-free scrolling must not keep earning", credited <= 1)
    }

    @Test
    fun `a phone left open on one page stops earning after the idle timeout`() {
        val t = tracker()
        var now = 1_000L
        t.noteInteraction(now)
        var lastIdle = false
        for (i in 0 until 400) { // 200 seconds, untouched
            now += 500
            lastIdle = t.tick(now, 0, pageWords, 500).idle
        }
        assertTrue("accrual must pause when nobody is there", lastIdle)
    }

    @Test
    fun `re-reading the same page does not pay twice`() {
        val t = tracker()
        assertEquals(1, readOnePage(t, chunk = 0).count { it.creditedChunk != null })
        val again = readOnePage(t, chunk = 0, startAt = 200_000L)
        assertEquals("a page pays once, ever", 0, again.count { it.creditedChunk != null })
    }

    @Test
    fun `pages credited in an earlier session are not paid again`() {
        val t = tracker()
        t.restore(setOf(0, 1, 2))
        val paid = readOnePage(t, chunk = 1).count { it.creditedChunk != null }
        assertEquals(0, paid)
    }

    @Test
    fun `the rate bucket caps how much can ever be banked`() {
        val t = tracker()
        var now = 1_000L
        t.noteInteraction(now)
        var wordsBanked = 0
        var elapsed = 0L
        // Dwell on each page just long enough, then move on, for 10 minutes of wall clock.
        var chunk = 0
        while (elapsed < 600_000L) {
            now += 500
            elapsed += 500
            t.noteInteraction(now)
            val res = t.tick(now, chunk, pageWords, 500)
            if (res.creditedChunk != null) {
                wordsBanked += res.creditedWords
                chunk++
            }
        }
        val ceiling = capWpm * (600_000L / 60_000L) + ReadingCreditTracker.MAX_BUCKET_WORDS
        assertTrue(
            "banked $wordsBanked words in 10 min, above the $capWpm wpm ceiling",
            wordsBanked <= ceiling,
        )
    }

    @Test
    fun `blank and tiny chunks are skipped`() {
        val t = tracker()
        val paid = readOnePage(t, chunk = 0, words = 1).count { it.creditedChunk != null }
        assertEquals(0, paid)
    }

    @Test
    fun `nothing accrues while a prompt covers the text`() {
        val t = tracker()
        var now = 1_000L
        t.noteInteraction(now)
        var credited: Int? = null
        for (i in 0 until 400) {
            now += 500
            t.noteInteraction(now)
            credited = t.tick(now, -1, pageWords, 500).creditedChunk ?: credited
        }
        assertNull("a covered page must not accrue", credited)
    }

    @Test
    fun `credit is never earned before the reader has been touched at all`() {
        val t = tracker()
        var now = 1_000L
        var credited = 0
        for (i in 0 until 400) {
            now += 500
            if (t.tick(now, 0, pageWords, 500).creditedChunk != null) credited++
        }
        assertEquals(0, credited)
    }

    @Test
    fun `a page read before still counts as reading, even though it cannot earn again`() {
        // The bug this pins down: pages credited in an earlier session are restored into the
        // tracker, and the old code returned before dwell was even measured for them. Re-open a
        // book you have read and the app saw someone who read nothing - so comprehension checks
        // never fired and the time-actually-read tally stayed at zero.
        val t = tracker()
        t.restore(setOf(0))

        val ticks = readOnePage(t, chunk = 0)

        assertNull("a page already paid for must not pay twice", ticks.firstOrNull { it.creditedChunk != null })
        val read = ticks.firstOrNull { it.readChunk != null }
        assertTrue("but it must still register as having been read", read != null)
        assertEquals(pageWords, read!!.readWords)
    }

    @Test
    fun `a page reports as read once per session, not once per tick`() {
        val t = tracker()
        val reads = readOnePage(t, chunk = 0).count { it.readChunk != null }
        assertEquals("dwelling longer must not keep re-counting the same page", 1, reads)
    }

    @Test
    fun `flinging past a page you have read before still earns no reading`() {
        // The anti-fake floor has to hold for the read signal too, or re-reading would become the
        // way to fake it: scroll a familiar book fast and collect questions you never earned.
        val t = tracker()
        t.restore(setOf(0))
        val ticks = readOnePage(t, chunk = 0, totalMs = 1_000L)
        assertNull("too fast to have been read", ticks.firstOrNull { it.readChunk != null })
    }
}

/**
 * Pages are real now, which means page numbers move: change the text size and page 40 is different
 * text. Anything recorded against a page number would therefore hand back a fresh, unpaid book on
 * every re-layout - so changing the font twice would mint unlimited Focus Gate credit. Payment is
 * recorded against position in the text instead, and this is the proof.
 */
class PaidTextSurvivesRelayoutTest {

    private val idle = 60_000L

    /** Reads [index] long enough to be paid for, and returns what it earned. */
    private fun readPage(tracker: ReadingCreditTracker, index: Int, words: Int, startMs: Long): Int {
        tracker.noteInteraction(startMs)
        var now = startMs
        var earned = 0
        repeat(40) {
            now += 1_000
            earned += tracker.tick(now, index, words, 1_000).creditedWords
        }
        return earned
    }

    @Test
    fun `re-laying the book out does not make paid text payable again`() {
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        // Three 200-word pages covering chars 0..3000.
        tracker.setPages(starts = intArrayOf(0, 1000, 2000), ends = intArrayOf(1000, 2000, 3000))
        assertTrue("reading a page should pay", readPage(tracker, 0, 200, 10_000) > 0)

        // Same book, smaller text: the same characters are now spread over more, shorter pages.
        val paid = tracker.creditedChunks
        val relaid = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        relaid.restore(paid)
        relaid.setPages(starts = intArrayOf(0, 500, 1000, 1500), ends = intArrayOf(500, 1000, 1500, 2000))

        assertEquals(
            "text already paid for must not pay again just because it moved page",
            0,
            readPage(relaid, 0, 100, 10_000),
        )
        assertEquals(0, readPage(relaid, 1, 100, 200_000))
        assertTrue(
            "text that was never read still pays",
            readPage(relaid, 2, 100, 400_000) > 0,
        )
    }

    @Test
    fun `with no page map the tracker still works page by page`() {
        // The unit tests above never set a page map, so this is the behaviour they rely on.
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        assertTrue(readPage(tracker, 7, 200, 10_000) > 0)
        assertEquals("a page pays once", 0, readPage(tracker, 7, 200, 200_000))
        assertTrue("a different page still pays", readPage(tracker, 8, 200, 400_000) > 0)
    }
}

/**
 * Reported from a phone: "I'm reading under two minutes and about 4 minutes are added."
 *
 * The cause was that time was priced from the word count at an assumed 240 words a minute, while
 * the dwell floor let a page qualify at 450 - so the fastest allowed reading was credited nearly
 * twice the time it took, and Focus Gate then doubled it again. These pin down the only property
 * that makes the number worth showing: verified reading can never exceed time actually spent.
 */
class VerifiedTimeIsRealTimeTest {

    private val idle = 600_000L

    /** Reads one page for [seconds], a tick at a time, and returns the verified milliseconds. */
    private fun read(tracker: ReadingCreditTracker, page: Int, words: Int, seconds: Int, startMs: Long): Long {
        tracker.noteInteraction(startMs)
        var now = startMs
        var verified = 0L
        repeat(seconds * 2) {
            now += 500
            verified += tracker.tick(now, page, words, 500).readMs
        }
        return verified
    }

    @Test
    fun `verified time never exceeds the time actually spent`() {
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        // 300 words qualifies after 300/450 min = 40s. Read it for exactly 60s.
        val verified = read(tracker, page = 0, words = 300, seconds = 60, startMs = 10_000)
        assertTrue("must credit something for a page genuinely read", verified > 0)
        assertTrue(
            "credited ${verified}ms for 60s of reading - it must never exceed real time",
            verified <= 60_000,
        )
    }

    @Test
    fun `reading fast is not paid as though it were slow`() {
        // The exact shape of the bug: a page read at the fastest allowed pace used to be credited
        // its worth at 240 wpm, which is 1.875x what it took.
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        val words = 450
        val secondsSpent = 60 // exactly 450 wpm
        val verified = read(tracker, page = 0, words = words, seconds = secondsSpent, startMs = 10_000)
        assertTrue(
            "credited ${verified / 1000}s for ${secondsSpent}s at the cap - the old code gave 112s",
            verified <= secondsSpent * 1000L,
        )
    }

    @Test
    fun `a page left open stops counting once it is worth no more`() {
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        // 120 words is worth at most 120/120 = one minute, however long the page stays up.
        val verified = read(tracker, page = 0, words = 120, seconds = 600, startMs = 10_000)
        assertTrue("ten minutes on one short page must not count as ten minutes", verified <= 60_000)
    }

    @Test
    fun `earning tracks the same real time, and a page still pays only once`() {
        val tracker = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        tracker.noteInteraction(10_000)
        var now = 10_000L
        var payable = 0L
        var verified = 0L
        repeat(120) {
            now += 500
            val tick = tracker.tick(now, 0, 300, 500)
            payable += tick.payableMs
            verified += tick.readMs
        }
        assertEquals("what earns and what counts as read are the same time", verified, payable)

        // A second session over the same text: still reading, but it has already been paid for.
        val later = ReadingCreditTracker(capWpm = 450, idleTimeoutMs = idle)
        later.restore(tracker.creditedChunks)
        later.noteInteraction(500_000)
        var t = 500_000L
        var laterPayable = 0L
        var laterVerified = 0L
        repeat(120) {
            t += 500
            val tick = later.tick(t, 0, 300, 500)
            laterPayable += tick.payableMs
            laterVerified += tick.readMs
        }
        assertTrue("re-reading is still reading", laterVerified > 0)
        assertEquals("but it must never pay twice", 0L, laterPayable)
    }
}
