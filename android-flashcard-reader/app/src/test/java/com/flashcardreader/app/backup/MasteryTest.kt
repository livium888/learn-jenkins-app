package com.flashcardreader.app.backup

import com.flashcardreader.app.data.repository.BookMastery
import com.flashcardreader.app.data.repository.MasteryLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole point of these levels is that they refuse to call something learned on the strength of
 * an answer given seconds after reading it. So the cases worth pinning down are the ones where a
 * looser rule would have been generous.
 */
class MasteryTest {

    private fun book(total: Int, correct: Int, retained: Int) =
        BookMastery("A book", total = total, correct = correct, retained = retained)

    @Test
    fun `answering everything right in the moment is not retention`() {
        // Every question answered correctly, none of them away from the page: the flattering case,
        // and the one the old "never missed" count would have shown as a perfect score.
        assertEquals(MasteryLevel.UNDERSTOOD, book(total = 8, correct = 8, retained = 0).level)
    }

    @Test
    fun `one delayed answer starts it sticking, but does not finish it`() {
        assertEquals(MasteryLevel.RETAINING, book(total = 8, correct = 8, retained = 1).level)
        assertEquals(MasteryLevel.RETAINING, book(total = 8, correct = 8, retained = 7).level)
    }

    @Test
    fun `retained only when every question has survived a delay`() {
        assertEquals(MasteryLevel.RETAINED, book(total = 8, correct = 8, retained = 8).level)
    }

    @Test
    fun `questions written but never answered right are untested`() {
        assertEquals(MasteryLevel.UNTESTED, book(total = 5, correct = 0, retained = 0).level)
    }

    @Test
    fun `every level has wording that says what it means without a legend`() {
        // These strings are the whole UI for this feature, so a blank or duplicated one would be
        // invisible in code review and obvious on a phone.
        val labels = MasteryLevel.values().map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertEquals(emptyList<String>(), labels.filter { it.isBlank() })
    }
}
