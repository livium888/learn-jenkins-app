package com.flashcardreader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These questions become scheduled cards, which is what makes validation worth testing properly.
 * A bad one doesn't merely waste a moment - it teaches something false and then keeps coming back
 * to teach it again. Every case below is a way the model can hand back something plausible-looking
 * that must not survive.
 */
class ReadingCheckTest {

    private val passage = """
        The lamplighter went along the street at dusk, tilting his pole to each wick in turn.
        He had walked the same route for thirty years, and knew which lamps guttered in a wind
        from the east. The boys followed him for the first few corners, then lost interest.
    """.trimIndent()

    private fun reply(
        question: String = "Why did the lamplighter know which lamps would gutter?",
        answer: String = "He had walked the same route for thirty years",
        distractors: String = """["He was told by the boys","He tested them each morning","He had read it in a ledger"]""",
        evidence: String = "He had walked the same route for thirty years",
    ) = """{"question":"$question","answer":"$answer","distractors":$distractors,"evidence":"$evidence"}"""

    @Test
    fun `accepts a well-formed question`() {
        val check = ReadingCheck.parse(reply(), passage)
        assertNotNull(check)
        assertEquals("He had walked the same route for thirty years", check!!.correctAnswer)
        assertEquals(3, check.distractors.size)
    }

    @Test
    fun `reads JSON out of a fenced or chatty reply`() {
        // Models wrap JSON in prose or a ```json fence. Rejecting a good answer over its
        // packaging would be its own bug.
        val wrapped = "Sure! Here's the question:\n```json\n${reply()}\n```\nHope that helps."
        assertNotNull(ReadingCheck.parse(wrapped, passage))
    }

    @Test
    fun `rejects a reply that is not JSON at all`() {
        assertNull(ReadingCheck.parse("I'm sorry, I can't do that.", passage))
        assertNull(ReadingCheck.parse("", passage))
    }

    @Test
    fun `rejects evidence the passage never contained`() {
        // The single most important case: this is what stops a question about something the book
        // never said from being asked, marked wrong, and then scheduled for review.
        val invented = reply(evidence = "The lamplighter had once been a sailor in the merchant navy")
        assertNull(ReadingCheck.parse(invented, passage))
    }

    @Test
    fun `accepts evidence that differs only in whitespace or quote style`() {
        // The passage is re-wrapped for the screen and models normalise punctuation silently.
        // Being strict here would reject good questions for cosmetic reasons.
        val rewrapped = reply(evidence = "He had walked   the same route\\nfor thirty years")
        assertNotNull(ReadingCheck.parse(rewrapped, passage))
    }

    @Test
    fun `rejects the wrong number of distractors`() {
        assertNull(ReadingCheck.parse(reply(distractors = """["only one"]"""), passage))
        assertNull(
            ReadingCheck.parse(
                reply(distractors = """["a","b","c","d"]"""),
                passage,
            ),
        )
    }

    @Test
    fun `rejects a distractor that is really the correct answer`() {
        // Two right answers makes the question unanswerable, and marks a correct reader wrong.
        val duplicated = reply(
            distractors = """["He was told by the boys","he had walked the same route for thirty years","A ledger"]""",
        )
        assertNull(ReadingCheck.parse(duplicated, passage))
    }

    @Test
    fun `rejects duplicate distractors`() {
        val repeated = reply(distractors = """["The boys told him","The boys told him","A ledger"]""")
        assertNull(ReadingCheck.parse(repeated, passage))
    }

    @Test
    fun `rejects empty fields`() {
        assertNull(ReadingCheck.parse(reply(question = ""), passage))
        assertNull(ReadingCheck.parse(reply(answer = ""), passage))
        assertNull(ReadingCheck.parse(reply(evidence = ""), passage))
    }

    @Test
    fun `rejects evidence too short to be a real citation`() {
        // A two-word "quote" appears in almost any passage by chance, so it proves nothing.
        assertNull(ReadingCheck.parse(reply(evidence = "the street"), passage))
    }

    @Test
    fun `appearsIn is not fooled by an empty passage`() {
        assertTrue(!ReadingCheck.appearsIn("He had walked the same route", ""))
    }
}

/**
 * A passage usually carries more than one idea, so the model is now asked for one question per
 * idea. That turns a single all-or-nothing validation into a batch, and the batch has its own ways
 * of going wrong: one bad question sinking three good ones, or "three ideas" that are the same
 * idea asked three times.
 */
class ReadingCheckBatchTest {

    private val passage = """
        The lamplighter went along the street at dusk, tilting his pole to each wick in turn.
        He had walked the same route for thirty years, and knew which lamps guttered in a wind
        from the east. The boys followed him for the first few corners, then lost interest.
    """.trimIndent()

    private fun question(
        q: String,
        answer: String,
        evidence: String,
        distractors: String = """["One","Two","Three"]""",
    ) = """{"question":"$q","answer":"$answer","distractors":$distractors,"evidence":"$evidence"}"""

    private val routeEvidence = "He had walked the same route for thirty years"
    private val boysEvidence = "The boys followed him for the first few corners, then lost interest"

    @Test
    fun `takes every valid question in a batch`() {
        val reply = """{"questions":[
            ${question("Why did he know the lamps?", "Thirty years on the route", routeEvidence)},
            ${question("What did the boys do?", "They lost interest", boysEvidence)}
        ]}"""
        val checks = ReadingCheck.parseAll(reply, passage)
        assertEquals(2, checks.size)
        assertEquals("Thirty years on the route", checks[0].correctAnswer)
        assertEquals("They lost interest", checks[1].correctAnswer)
    }

    @Test
    fun `one bad question does not sink the good ones`() {
        // The middle question cites something the passage never said - the exact failure the whole
        // validation exists for. It must go, and the other two must survive.
        val reply = """{"questions":[
            ${question("Why did he know the lamps?", "Thirty years", routeEvidence)},
            ${question("What colour was his coat?", "Green", "His coat was a deep bottle green")},
            ${question("What did the boys do?", "They lost interest", boysEvidence)}
        ]}"""
        val checks = ReadingCheck.parseAll(reply, passage)
        assertEquals(2, checks.size)
        assertTrue(checks.none { it.correctAnswer == "Green" })
    }

    @Test
    fun `two questions citing the same lines count as one idea`() {
        val reply = """{"questions":[
            ${question("Why did he know the lamps?", "Thirty years", routeEvidence)},
            ${question("How long had he walked it?", "Thirty years", routeEvidence)}
        ]}"""
        assertEquals(1, ReadingCheck.parseAll(reply, passage).size)
    }

    @Test
    fun `the same question twice is only asked once`() {
        val reply = """{"questions":[
            ${question("Why did he know the lamps?", "Thirty years", routeEvidence)},
            ${question("Why did he know the lamps?", "Thirty years", boysEvidence)}
        ]}"""
        assertEquals(1, ReadingCheck.parseAll(reply, passage).size)
    }

    @Test
    fun `never returns more than the ceiling asked for`() {
        val reply = """{"questions":[
            ${question("A?", "a", routeEvidence)},
            ${question("B?", "b", boysEvidence)},
            ${question("C?", "c", "The lamplighter went along the street at dusk")}
        ]}"""
        assertEquals(1, ReadingCheck.parseAll(reply, passage, limit = 1).size)
        assertEquals(3, ReadingCheck.parseAll(reply, passage, limit = 5).size)
    }

    @Test
    fun `a reply in the old single-question shape still works`() {
        // Worth keeping: the model does not always honour the schema, and a lone well-formed
        // question is still a perfectly good question.
        val reply = question("Why did he know the lamps?", "Thirty years", routeEvidence)
        assertEquals(1, ReadingCheck.parseAll(reply, passage).size)
    }

    @Test
    fun `junk gives no questions rather than a broken one`() {
        assertTrue(ReadingCheck.parseAll("sorry, I can't help with that", passage).isEmpty())
        assertTrue(ReadingCheck.parseAll("""{"questions":[]}""", passage).isEmpty())
    }
}
