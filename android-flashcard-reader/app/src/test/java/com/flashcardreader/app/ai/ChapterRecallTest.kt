package com.flashcardreader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterRecallTest {

    /** Six well-separated paragraphs, so anchors can be far enough apart to be a real sequence. */
    private val anchors = listOf(
        "The mill at Ashby had stood empty for nineteen years before anyone thought to ask why.",
        "Wages in the valley were set not by the owners but by the price of bread in the town.",
        "Every winter the river froze, and every winter the looms stopped for want of power.",
        "The commissioners arrived in the spring and stayed for eleven weeks without writing a word.",
        "What the report finally said was that the valley had been poor before the mill was built.",
        "By then the families it described had already gone north to the coalfields for work.",
    )

    private val filler = "Some further discussion followed, of no great consequence to the argument, " +
        "recorded here only because the clerk was paid by the page and wrote at length about it. "

    private val chapter = anchors.joinToString("") { "$it $filler$filler$filler" }

    private fun json(
        propositions: List<Triple<String, Boolean, String>> = defaultPropositions,
        hints: List<Pair<String, String>> = defaultHints,
    ): String {
        val props = propositions.joinToString(",") { (text, said, evidence) ->
            """{"text":${q(text)},"said":$said,"evidence":${q(evidence)}}"""
        }
        val hs = hints.joinToString(",") { (hint, anchor) ->
            """{"hint":${q(hint)},"anchor":${q(anchor)}}"""
        }
        return """{"propositions":[$props],"hints":[$hs]}"""
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    private val defaultPropositions = listOf(
        Triple("The mill stood empty for a long time", true, anchors[0]),
        Triple("Wages tracked the price of bread", true, anchors[1]),
        Triple("The river froze in winter", true, anchors[2]),
        Triple("The commissioners worked quickly and reported within a month", false, ""),
        Triple("The mill was built to relieve poverty that already existed", false, ""),
        Triple("The families stayed in the valley after the report", false, ""),
    )

    private val defaultHints = listOf(
        "An empty mill nobody questioned" to anchors[0],
        "Bread, not owners, set the wages" to anchors[1],
        "Winter stopped the looms" to anchors[2],
        "Commissioners who wrote nothing" to anchors[3],
        "A report that blamed the past" to anchors[4],
    )

    @Test
    fun `a good batch parses, and hint order comes from the book`() {
        val recall = ChapterRecall.parse(json(), chapter)
        assertNotNull(recall)
        assertEquals(6, recall!!.propositions.size)
        assertEquals(5, recall.hints.size)
        assertEquals(
            "positions must be strictly increasing - that is the whole point",
            recall.hints.map { it.position }.sorted(),
            recall.hints.map { it.position },
        )
    }

    @Test
    fun `a said proposition whose evidence is not in the chapter is rejected`() {
        val bad = defaultPropositions.toMutableList()
        bad[0] = Triple("The mill stood empty", true, "The mill was demolished in the autumn of that year.")
        assertNull(ChapterRecall.parse(json(propositions = bad), chapter))
    }

    @Test
    fun `a not-said proposition that is actually in the chapter is rejected`() {
        // Marking something the chapter did say as not-said would mark a correct answer wrong.
        val bad = defaultPropositions.toMutableList()
        bad[3] = Triple(anchors[3], false, "")
        assertNull(ChapterRecall.parse(json(propositions = bad), chapter))
    }

    @Test
    fun `a set that is all one kind is rejected`() {
        val allSaid = anchors.take(6).mapIndexed { i, a -> Triple("Claim $i", true, a) }
        assertNull(ChapterRecall.parse(json(propositions = allSaid), chapter))
    }

    @Test
    fun `hints listed out of order are rejected`() {
        val shuffled = listOf(defaultHints[2], defaultHints[0], defaultHints[1], defaultHints[3], defaultHints[4])
        assertNull(ChapterRecall.parse(json(hints = shuffled), chapter))
    }

    @Test
    fun `hints anchored to the same stretch are rejected`() {
        // Five quotes from one paragraph are not five steps in an argument.
        val crowded = listOf(
            "a" to anchors[0],
            "b" to filler.trim(),
            "c" to anchors[1],
            "d" to anchors[2],
            "e" to anchors[3],
        )
        assertNull(ChapterRecall.parse(json(hints = crowded), chapter))
    }

    @Test
    fun `too few hints is rejected`() {
        assertNull(ChapterRecall.parse(json(hints = defaultHints.take(3)), chapter))
    }

    @Test
    fun `a hint anchored to text that is not in the chapter is rejected`() {
        val bad = defaultHints.toMutableList()
        bad[1] = "Invented" to "This sentence appears nowhere in the chapter whatsoever, not once."
        assertNull(ChapterRecall.parse(json(hints = bad), chapter))
    }

    @Test
    fun `junk is rejected rather than throwing`() {
        assertNull(ChapterRecall.parse("not json at all", chapter))
        assertNull(ChapterRecall.parse("""{"propositions":[]}""", chapter))
    }
}
