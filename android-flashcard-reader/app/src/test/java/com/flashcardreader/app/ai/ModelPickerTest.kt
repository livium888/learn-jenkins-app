package com.flashcardreader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing a model used to be a hard-coded guess, and when Google retired that id the whole
 * feature failed with a 404 nobody could see. Now the API is asked what the key can call and one
 * is chosen from the answer - so the choosing is worth pinning down.
 */
class ModelPickerTest {

    @Test
    fun `prefers a flash model over a pro one`() {
        val picked = GeminiTutor.pickModel(listOf("gemini-2.5-pro", "gemini-2.5-flash"))
        assertEquals("gemini-2.5-flash", picked)
    }

    @Test
    fun `prefers the newer version when both are flash`() {
        val picked = GeminiTutor.pickModel(listOf("gemini-1.5-flash", "gemini-2.5-flash", "gemini-2.0-flash"))
        assertEquals("gemini-2.5-flash", picked)
    }

    @Test
    fun `avoids previews and experiments when a stable model exists`() {
        val picked = GeminiTutor.pickModel(
            listOf("gemini-3.0-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-exp"),
        )
        assertEquals("gemini-2.5-flash", picked)
    }

    @Test
    fun `never picks something that cannot answer a text question`() {
        val picked = GeminiTutor.pickModel(
            listOf("text-embedding-004", "gemini-2.5-flash-tts", "gemini-2.5-flash"),
        )
        assertEquals("gemini-2.5-flash", picked)
    }

    @Test
    fun `still returns something when nothing looks like flash`() {
        // Better a pro model than no question at all.
        val picked = GeminiTutor.pickModel(listOf("gemini-2.5-pro"))
        assertEquals("gemini-2.5-pro", picked)
    }

    @Test
    fun `returns null when the key can use nothing`() {
        assertNull(GeminiTutor.pickModel(emptyList()))
    }

    @Test
    fun `a future flash release is preferred without any code change`() {
        // The point of scoring on the version number rather than a list: this keeps working.
        val picked = GeminiTutor.pickModel(listOf("gemini-2.5-flash", "gemini-4.1-flash"))
        assertTrue(picked == "gemini-4.1-flash")
    }
}
