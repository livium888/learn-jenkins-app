package com.flashcardreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterProgressTest {

    private val bucket = 400

    private fun buckets(fromChar: Int, toChar: Int): Set<Int> =
        (fromChar / bucket..(toChar - 1) / bucket).toSet()

    @Test
    fun `the last chapter runs to the end of the book`() {
        val spans = ChapterProgress.spans(listOf("One" to 0, "Two" to 1000), textLength = 2500)
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].startChar)
        assertEquals(1000, spans[0].endChar)
        assertEquals(2500, spans[1].endChar)
    }

    @Test
    fun `anchors are sorted, de-duplicated and clipped to the book`() {
        // A part title and its first chapter can share an offset; an offset past the end is junk.
        val spans = ChapterProgress.spans(
            listOf("Two" to 1000, "Part I" to 0, "One" to 0, "Bogus" to 99_999),
            textLength = 2000,
        )
        assertEquals(listOf(0, 1000), spans.map { it.startChar })
        assertEquals(listOf(0, 1), spans.map { it.index })
    }

    @Test
    fun `a chapter read end to end is finished, one read in part is not`() {
        val spans = ChapterProgress.spans(listOf("One" to 0, "Two" to 4000), textLength = 8000)
        val read = buckets(0, 4000) + buckets(4000, 5200) // all of one, 30% of two

        val finished = ChapterProgress.finished(spans, read, bucket)
        assertEquals(listOf(0), finished.map { it.index })
        assertTrue(ChapterProgress.coverage(spans[1], read, bucket) < ChapterProgress.FINISHED_FRACTION)
    }

    @Test
    fun `eighty percent is enough, because chapter anchors do not land on the prose`() {
        val spans = ChapterProgress.spans(listOf("One" to 0), textLength = 4000)
        // Skipping the epigraph at the start and the last page still counts as having read it.
        val read = buckets(400, 3600)
        assertEquals(1, ChapterProgress.finished(spans, read, bucket).size)
    }

    @Test
    fun `flinging through a book finishes nothing`() {
        val spans = ChapterProgress.spans(listOf("One" to 0, "Two" to 4000), textLength = 8000)
        assertEquals(emptyList<ChapterSpan>(), ChapterProgress.finished(spans, emptySet(), bucket))
    }

    @Test
    fun `a chapter already dealt with is not offered again`() {
        val spans = ChapterProgress.spans(listOf("One" to 0), textLength = 4000)
        val read = buckets(0, 4000)
        assertEquals(1, ChapterProgress.finished(spans, read, bucket).size)
        assertEquals(0, ChapterProgress.finished(spans, read, bucket, alreadyDone = setOf(0)).size)
    }

    @Test
    fun `a book with no chapters has no spans`() {
        assertEquals(emptyList<ChapterSpan>(), ChapterProgress.spans(emptyList(), textLength = 5000))
    }
}
