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
        chunk: Int,
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
}
