package com.flashcardreader.app.reader

/** One chapter's stretch of the book, in characters. [endChar] is exclusive. */
data class ChapterSpan(
    val index: Int,
    val title: String,
    val startChar: Int,
    val endChar: Int,
) {
    val chars: Int get() = (endChar - startChar).coerceAtLeast(0)
}

/**
 * Works out which chapters have actually been read.
 *
 * "Read" here means the same thing it means everywhere else in this app - text that passed the
 * anti-fake rules in [ReadingCreditTracker], recorded as buckets of characters. Scrolling past a
 * chapter, or flinging through it, covers no buckets and finishes nothing.
 *
 * ⚠ The buckets must come from the *read* set, not the paid one. `ReadingCreditTracker` only marks
 * text as credited when the Focus Gate token bucket can afford it, so genuinely-read pages are
 * missing from `creditedChunks` whenever the rate cap bites - and a chapter you read carefully
 * would then never count as finished.
 *
 * Android-free so it can be tested on the JVM, like Paginator and ReaderOverlays.
 */
object ChapterProgress {

    /**
     * How much of a chapter must be read before it counts as finished.
     *
     * Not 100%: chapter boundaries from a table of contents rarely line up with where the prose
     * actually starts and ends - front matter, epigraphs, a heading that belongs to the next
     * section - so demanding every bucket would mean almost no chapter ever completed.
     */
    const val FINISHED_FRACTION = 0.8

    /**
     * Turns chapter anchors into spans. Each chapter runs to the start of the next one, and the
     * last runs to the end of the book.
     *
     * Anchors are sorted and de-duplicated first: a table of contents can list two entries at the
     * same offset (a part title immediately followed by its first chapter), and an empty span would
     * otherwise be "finished" by dividing by zero.
     */
    fun spans(anchors: List<Pair<String, Int>>, textLength: Int): List<ChapterSpan> {
        val sorted = anchors
            .filter { it.second in 0..textLength }
            .sortedBy { it.second }
            .distinctBy { it.second }
        return sorted.mapIndexedNotNull { i, (title, start) ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].second else textLength
            if (end <= start) null else ChapterSpan(i, title, start, end)
        }
    }

    /** The character buckets a span covers, using the same bucket size the tracker records with. */
    fun bucketsOf(span: ChapterSpan, bucketChars: Int): IntRange =
        span.startChar / bucketChars..(span.endChar - 1) / bucketChars

    /** What fraction of [span] has been genuinely read. 0 when the span holds nothing. */
    fun coverage(span: ChapterSpan, readBuckets: Set<Int>, bucketChars: Int): Double {
        val buckets = bucketsOf(span, bucketChars)
        val total = buckets.last - buckets.first + 1
        if (total <= 0) return 0.0
        return buckets.count { it in readBuckets }.toDouble() / total
    }

    /**
     * Chapters read past [threshold], in reading order.
     *
     * Callers pass the ones already dealt with in [alreadyDone] so a chapter is only ever offered
     * once - re-reading a chapter is still reading, but it is not a new event.
     */
    fun finished(
        spans: List<ChapterSpan>,
        readBuckets: Set<Int>,
        bucketChars: Int,
        alreadyDone: Set<Int> = emptySet(),
        threshold: Double = FINISHED_FRACTION,
    ): List<ChapterSpan> = spans.filter {
        it.index !in alreadyDone && coverage(it, readBuckets, bucketChars) >= threshold
    }
}
