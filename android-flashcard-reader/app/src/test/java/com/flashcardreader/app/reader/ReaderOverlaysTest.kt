package com.flashcardreader.app.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cover one specific deadlock and the rules that prevent it coming back.
 *
 * A question is written at 75% of the way to the interval so it is ready the moment it is due. But
 * accrual was suppressed whenever a question merely *existed*, so from 75% onwards the reader was
 * told nothing was being read: the word count froze, never reached 100%, the question never became
 * due, and was therefore never shown or cleared. Reading stopped counting permanently, with no
 * questions and a status report stuck on the same number.
 */
class ReaderOverlaysTest {

    @Test
    fun `a prepared but not yet due question must not stop reading being counted`() {
        // The deadlock, exactly: a question is in hand, but nothing is on screen.
        val overlays = readerOverlays(
            pendingFlashcards = 0,
            pendingChecks = 2,
            checkDue = false,
            inMultiWindow = false,
        )
        assertFalse("nothing is drawn, so nothing covers the text", overlays.coversText)
        assertFalse(overlays.showReadingCheck)
    }

    @Test
    fun `a question that is due is shown and does stop accrual`() {
        val overlays = readerOverlays(
            pendingFlashcards = 0,
            pendingChecks = 1,
            checkDue = true,
            inMultiWindow = false,
        )
        assertTrue(overlays.showReadingCheck)
        assertTrue(overlays.coversText)
    }

    @Test
    fun `anything shown covers the text, and nothing shown does not`() {
        // The invariant the deadlock broke: these two answers come from one place and must agree.
        val nothing = readerOverlays(0, 0, checkDue = false, inMultiWindow = false)
        assertFalse(nothing.coversText)

        val flashcard = readerOverlays(1, 0, checkDue = false, inMultiWindow = false)
        assertTrue(flashcard.showFlashcard)
        assertTrue(flashcard.coversText)

    }

    @Test
    fun `a due flashcard wins, and hides the question behind it`() {
        val overlays = readerOverlays(
            pendingFlashcards = 1,
            pendingChecks = 1,
            checkDue = true,
            inMultiWindow = false,
        )
        assertTrue(overlays.showFlashcard)
        assertFalse("only one prompt at a time", overlays.showReadingCheck)
    }

    @Test
    fun `split-screen stops accrual without drawing anything`() {
        val overlays = readerOverlays(0, 0, checkDue = false, inMultiWindow = true)
        assertTrue(overlays.coversText)
        assertFalse(overlays.showFlashcard)
        assertFalse(overlays.showReadingCheck)
    }

    @Test
    fun `a chapter pass covers the text and stops accrual`() {
        val overlays = readerOverlays(0, 0, checkDue = false, chapterPassReady = true, inMultiWindow = false)
        assertTrue(overlays.showChapterPass)
        assertTrue(overlays.coversText)
    }

    @Test
    fun `a chapter pass never competes with a question or a card`() {
        val behindCheck = readerOverlays(
            pendingFlashcards = 0,
            pendingChecks = 1,
            checkDue = true,
            chapterPassReady = true,
            inMultiWindow = false,
        )
        assertTrue(behindCheck.showReadingCheck)
        assertFalse("only one prompt at a time", behindCheck.showChapterPass)

        val behindCard = readerOverlays(
            pendingFlashcards = 1,
            pendingChecks = 0,
            checkDue = false,
            chapterPassReady = true,
            inMultiWindow = false,
        )
        assertTrue(behindCard.showFlashcard)
        assertFalse(behindCard.showChapterPass)
    }

    @Test
    fun `no pass means nothing changes`() {
        val overlays = readerOverlays(0, 0, checkDue = false, chapterPassReady = false, inMultiWindow = false)
        assertFalse(overlays.showChapterPass)
        assertFalse(overlays.coversText)
    }
}
