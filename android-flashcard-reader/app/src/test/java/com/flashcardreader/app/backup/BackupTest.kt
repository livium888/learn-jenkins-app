package com.flashcardreader.app.backup

import com.flashcardreader.app.data.repository.ImportedCardIds
import com.flashcardreader.app.data.repository.RestoreResult
import com.flashcardreader.app.stats.describeRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup grew from "words" to "words, questions and the whole review history", and both of the
 * parts that are easy to get quietly wrong are covered here: the id remapping that keeps an
 * imported history from being merged into the local one, and the sentence that tells you what
 * actually came back.
 */
class BackupTest {

    @Test
    fun `the same imported card always gets the same local id`() {
        val ids = ImportedCardIds()
        val first = ids.idFor(42, "TERM")
        assertEquals("a card's reviews must stay grouped together", first, ids.idFor(42, "TERM"))
        assertEquals(first, ids.idFor(42, "TERM"))
    }

    @Test
    fun `different cards never share an id`() {
        val ids = ImportedCardIds()
        val a = ids.idFor(1, "TERM")
        val b = ids.idFor(2, "TERM")
        // Same original id, different kind: two genuinely different cards on the source device.
        val c = ids.idFor(1, "READING_CHECK")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(b, c)
    }

    @Test
    fun `every imported id is negative, so it can never collide with a local one`() {
        val ids = ImportedCardIds()
        // Room hands out ids counting up from 1, so anything below zero is permanently unclaimed.
        for (original in 0L..200L) {
            assertTrue("id for $original must be negative", ids.idFor(original, "TERM") < 0)
        }
    }

    @Test
    fun `a restore reports each kind of thing it put back`() {
        assertEquals(
            "Restored 3 words, 2 questions and 10 past reviews",
            describeRestore(RestoreResult(words = 3, questions = 2, reviews = 10)),
        )
        assertEquals(
            "Restored 1 word and 1 question",
            describeRestore(RestoreResult(words = 1, questions = 1)),
        )
        assertEquals("Restored 5 questions", describeRestore(RestoreResult(questions = 5)))
    }

    @Test
    fun `a restore that added nothing says so`() {
        assertEquals("Nothing new to restore", describeRestore(RestoreResult()))
    }

    @Test
    fun `skipped history is always mentioned, added or not`() {
        // This is the case most likely to look like data loss, so it must never be silent.
        assertTrue(
            describeRestore(RestoreResult(words = 4, historySkipped = true))
                .contains("review history was left alone"),
        )
        assertTrue(
            describeRestore(RestoreResult(historySkipped = true))
                .contains("review history was left alone"),
        )
    }
}
