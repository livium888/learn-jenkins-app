package com.flashcardreader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These become scheduled cards written without anyone asking for them, so the bar is higher than
 * for something a person chose. A card about a word that is not in the book teaches something the
 * reader never met; a card whose wrong answers are obviously wrong is answered right every time and
 * quietly inflates every retention number in the app. Both are worse than no card at all.
 */
class VocabCardTest {

    private val passage = """
        The lamplighter went along the street at dusk, tilting his pole to each wick in turn.
        He had walked the same route for thirty years, and knew which lamps guttered in a wind
        from the east. The boys followed him for the first few corners, then lost interest.
    """.trimIndent()

    private fun reply(
        word: String = "guttered",
        definition: String = "burned unsteadily, close to going out",
        distractors: String = """["shone very brightly","were newly installed","had been extinguished on purpose"]""",
    ) = JSONObject("""{"words":[{"word":"$word","definition":"$definition","distractors":$distractors}]}""")

    @Test
    fun `accepts a well-formed card`() {
        val cards = VocabCards.parseAll(reply(), passage)
        assertEquals(1, cards.size)
        assertEquals("guttered", cards[0].word)
        assertEquals(3, cards[0].distractors.size)
    }

    @Test
    fun `rejects a word that is not in the passage`() {
        // The model inventing a word the reader never met is the failure that matters most.
        assertTrue(VocabCards.parseAll(reply(word = "peregrination"), passage).isEmpty())
    }

    @Test
    fun `matches whole words only`() {
        // "art" appears inside "started" but was never a word on the page.
        assertFalse(VocabCards.appearsAsWord("art", "He started walking"))
        assertTrue(VocabCards.appearsAsWord("art", "The art of walking"))
        // A word met at the start of a sentence is the same word.
        assertTrue(VocabCards.appearsAsWord("the", passage))
    }

    @Test
    fun `rejects the wrong number of wrong answers`() {
        assertTrue(VocabCards.parseAll(reply(distractors = """["only one"]"""), passage).isEmpty())
        assertTrue(
            VocabCards.parseAll(reply(distractors = """["a","b","c","d"]"""), passage).isEmpty(),
        )
    }

    @Test
    fun `rejects a wrong answer that is really the right one`() {
        val cards = VocabCards.parseAll(
            reply(distractors = """["burned unsteadily, close to going out","shone brightly","was replaced"]"""),
            passage,
        )
        assertTrue("an unanswerable card must not be kept", cards.isEmpty())
    }

    @Test
    fun `rejects empty pieces and absurdly long words`() {
        assertTrue(VocabCards.parseAll(reply(word = ""), passage).isEmpty())
        assertTrue(VocabCards.parseAll(reply(definition = ""), passage).isEmpty())
        assertTrue(VocabCards.parseAll(reply(word = "guttered ".repeat(20)), passage).isEmpty())
    }

    @Test
    fun `one bad card does not sink the good ones, and the limit holds`() {
        val json = JSONObject(
            """{"words":[
                {"word":"guttered","definition":"burned unsteadily","distractors":["a","b","c"]},
                {"word":"unicorn","definition":"a horse with a horn","distractors":["a","b","c"]},
                {"word":"lamplighter","definition":"someone who lit street lamps","distractors":["a","b","c"]},
                {"word":"dusk","definition":"the darker part of twilight","distractors":["a","b","c"]}
            ]}""",
        )
        val cards = VocabCards.parseAll(json, passage, limit = 2)
        assertEquals("the limit is a limit", 2, cards.size)
        assertTrue("the invented word must be gone", cards.none { it.word == "unicorn" })
    }

    @Test
    fun `no words at all is not an error`() {
        assertTrue(VocabCards.parseAll(JSONObject("""{"questions":[]}"""), passage).isEmpty())
        assertTrue(VocabCards.parseAll(JSONObject("""{"words":[]}"""), passage).isEmpty())
    }
}
