package com.flashcardreader.app.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card half of the anti-fake promise: credit comes from producing the missing word, never from
 * tapping a rating. Forgiving of spelling, unforgiving of not knowing.
 */
class AnswerMatcherTest {

    @Test
    fun `exact and case-insensitive answers pass`() {
        assertTrue(AnswerMatcher.isCorrect("lantern", "lantern"))
        assertTrue(AnswerMatcher.isCorrect("Lantern", "lantern"))
        assertTrue(AnswerMatcher.isCorrect("  LANTERN  ", "lantern"))
    }

    @Test
    fun `punctuation and accents are ignored`() {
        assertTrue(AnswerMatcher.isCorrect("lantern.", "lantern"))
        assertTrue(AnswerMatcher.isCorrect("cafe", "café"))
        assertTrue(AnswerMatcher.isCorrect("Renée!", "renee"))
    }

    @Test
    fun `multi-word names still match`() {
        assertTrue(AnswerMatcher.isCorrect("jean valjean", "Jean Valjean"))
        assertTrue(AnswerMatcher.isCorrect("Jean  Valjean", "Jean Valjean"))
    }

    @Test
    fun `a single typo in a longer word is forgiven`() {
        assertTrue(AnswerMatcher.isCorrect("lantren", "lantern")) // transposition
        assertTrue(AnswerMatcher.isCorrect("lantrn", "lantern")) // omission
    }

    @Test
    fun `short words are not fuzzy-matched`() {
        assertFalse("cat/cot would be a different real answer", AnswerMatcher.isCorrect("cot", "cat"))
    }

    @Test
    fun `blank and wrong answers never pass`() {
        assertFalse(AnswerMatcher.isCorrect("", "lantern"))
        assertFalse(AnswerMatcher.isCorrect("   ", "lantern"))
        assertFalse(AnswerMatcher.isCorrect("something else", "lantern"))
        assertFalse(AnswerMatcher.isCorrect("lantern", ""))
    }
}
