package com.flashcardreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HintOrderTest {

    @Test
    fun `the right order scores one`() {
        assertEquals(1.0, HintOrder.score(listOf(0, 1, 2, 3, 4)), 0.0001)
        assertTrue(HintOrder.isPerfect(listOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun `one adjacent swap costs one pair, not the whole attempt`() {
        // Scoring by absolute position would call this 60% wrong; it is one mistake.
        assertEquals(0.75, HintOrder.score(listOf(0, 2, 1, 3, 4)), 0.0001)
        assertFalse(HintOrder.isPerfect(listOf(0, 2, 1, 3, 4)))
    }

    @Test
    fun `a hint dragged to the front breaks one junction, not the rest of the run`() {
        // (4,0) is wrong; (0,1) (1,2) (2,3) are all still right. One mistake, one pair.
        assertEquals(0.75, HintOrder.score(listOf(4, 0, 1, 2, 3)), 0.0001)
        // Two separate misplacements do cost two.
        assertEquals(0.5, HintOrder.score(listOf(1, 0, 3, 2, 4)), 0.0001)
    }

    @Test
    fun `backwards scores zero`() {
        assertEquals(0.0, HintOrder.score(listOf(4, 3, 2, 1, 0)), 0.0001)
    }

    @Test
    fun `degenerate attempts do not divide by zero`() {
        assertEquals(1.0, HintOrder.score(listOf(0)), 0.0001)
        assertEquals(0.0, HintOrder.score(emptyList()), 0.0001)
    }
}
