package com.flashcardreader.app.reader

/** Result of one tick: the page (if any) that just earned, its words, and whether accrual is idle. */
data class CreditTick(
    val creditedChunk: Int? = null,
    val creditedWords: Int = 0,
    /**
     * A page that has just been read properly, whether or not it earned anything.
     *
     * Separate from [creditedChunk] because "did you read this?" and "should this pay you?" are
     * different questions. A page pays out once ever, so re-reading a chapter earns nothing - which
     * is right for Focus Gate, where repeating a page would otherwise be a way to mint credit. But
     * a comprehension check has nothing to farm: re-reading is still reading, and a question about
     * it is still a fair question. Anything asking "what have they read lately" wants this one.
     */
    val readChunk: Int? = null,
    val readWords: Int = 0,
    /**
     * Milliseconds of genuine reading this tick - time actually spent, not the notional worth of
     * the words on the page.
     *
     * This used to be computed from the word count at an assumed 240 words a minute, while the
     * dwell floor let a page qualify at 450. So the fastest allowed reading credited nearly twice
     * the time it took, and Focus Gate then doubled that again. Two minutes of reading could bank
     * close to four. Time spent is the only thing worth calling time spent.
     */
    val readMs: Long = 0L,
    /** The part of [readMs] that may earn Focus Gate credit - a page pays once, ever. */
    val payableMs: Long = 0L,
    /** True when accrual is paused because nobody has touched the screen for a while. */
    val idle: Boolean = false,
)

/**
 * Decides when a page has genuinely been *read* rather than scrolled past. This is the anti-fake
 * core of Focus Gate: credit cannot be earned by flinging, auto-scrolling, dragging the scrubber,
 * or leaving the phone open on a page.
 *
 * Deliberately pure Kotlin - no Android imports, no clock of its own, no storage - because it is the
 * one piece of this feature that CI can actually verify. Everything else (services, overlays,
 * permissions) only fails on a real device; this fails in a unit test.
 *
 * Three independent rules, each closing a specific hole:
 *
 *  1. **One chunk per tick.** Only the chunk centred in the viewport accrues, so credit can never
 *     exceed real elapsed time even when several chunks are on screen (a tall screen or small font
 *     would otherwise pay three times over for the same minute).
 *  2. **A per-page dwell floor** of `words / capWpm` - a page must stay in view at least as long as
 *     the fastest plausible reader would need. This is what kills a fling.
 *  3. **A global token bucket.** Active reading mints word-budget at `capWpm`, and crediting a page
 *     spends `words` from it. This is the backstop that makes any attribution mistake harmless: no
 *     matter the scroll pattern, you can never bank more than `capWpm` worth. It is also what
 *     defeats auto-scroll properly - rule 2 alone is *not* enough, because at a large font even the
 *     slowest auto-scroll setting can creep under the per-page floor.
 *
 * Accrual also stops entirely after [idleTimeoutMs] without a *human* touch, which matters because
 * the reader holds the screen awake: a phone left face-up on auto-scroll would otherwise farm credit
 * with nobody present. Programmatic scrolling must never be reported via [noteInteraction] (note
 * that `LazyListState.isScrollInProgress` is true for it too, so the caller reports real touches only).
 *
 * [nowMs] must come from a monotonic clock (`SystemClock.elapsedRealtime`), never wall-clock, so
 * winding the system clock forward cannot mint credit.
 */
class ReadingCreditTracker(
    private val capWpm: Int,
    private val idleTimeoutMs: Long,
) {
    private val dwellMs = HashMap<Int, Long>()

    /**
     * Stretches of the *book* that have already been paid for, as fixed-size buckets of characters.
     *
     * Keyed by position in the text rather than by page number, because page numbers are not
     * stable: changing the font size re-lays the whole book out, and page 40 becomes different
     * text. Keyed by page, every re-layout would hand back a fresh, unpaid book - so changing the
     * text size twice would be a way to mint unlimited credit. Characters do not move.
     */
    private val credited = HashSet<Int>()

    /** Where each page starts and ends in the book, so a page can be mapped to its buckets. */
    private var pageStarts: IntArray = IntArray(0)
    private var pageEnds: IntArray = IntArray(0)

    /**
     * Pages read properly *this session*, including ones already paid for previously. Not persisted
     * and not restored: a fresh sitting with a familiar book is still a sitting spent reading.
     */
    private val readThisSession = HashSet<Int>()

    /** Verified milliseconds already granted per page, so one page cannot pay out forever. */
    private val verifiedMs = HashMap<Int, Long>()

    /** Pages that began earning this session, so their time keeps counting while they are read. */
    private val payableThisSession = HashSet<Int>()
    private var lastInteractionAt = 0L
    private var bucketWords = 0f

    /** Stretches already paid for in an earlier session, loaded from the book's sidecar. */
    fun restore(previouslyCredited: Set<Int>) {
        credited.addAll(previouslyCredited)
    }

    /**
     * Tells the tracker where the pages fall, so payout can be recorded against the text rather
     * than against a page number. Called again after every re-layout.
     *
     * With no page map (as in the unit tests) the page index is used as its own bucket, which
     * keeps the anti-fake rules testable without a book.
     */
    fun setPages(starts: IntArray, ends: IntArray) {
        pageStarts = starts
        pageEnds = ends
    }

    val creditedChunks: Set<Int> get() = credited

    /** The buckets of book text a page covers. */
    private fun bucketsFor(index: Int): IntRange {
        if (index !in pageStarts.indices) return index..index
        val from = pageStarts[index] / CREDIT_BUCKET_CHARS
        val to = (pageEnds[index] - 1).coerceAtLeast(pageStarts[index]) / CREDIT_BUCKET_CHARS
        return from..to
    }

    /** Called on a real touch (drag, tap, long-press) - never for programmatic/auto-scroll. */
    fun noteInteraction(nowMs: Long) {
        lastInteractionAt = nowMs
    }

    /** Clears part-read progress when the reader is backgrounded, so it can't be resumed later. */
    fun onPaused() {
        dwellMs.clear()
    }

    /**
     * Advances dwell for the page centred in the viewport and pays out if it has now been in view
     * long enough and the rate bucket can afford it. Pass [focusedChunk] = -1 when nothing should
     * accrue (a prompt covers the text, or the app is in split-screen).
     */
    fun tick(nowMs: Long, focusedChunk: Int, wordCount: Int, elapsedMs: Long): CreditTick {
        if (lastInteractionAt == 0L) return CreditTick()
        if (nowMs - lastInteractionAt > idleTimeoutMs) return CreditTick(idle = true)
        if (focusedChunk < 0) return CreditTick()

        val cap = capWpm.coerceAtLeast(1)
        bucketWords = (bucketWords + elapsedMs * cap / 60_000f).coerceAtMost(MAX_BUCKET_WORDS)

        if (wordCount < MIN_WORDS) return CreditTick()

        // Dwell is measured for every page, including ones already paid for. Skipping them meant a
        // book you had read before produced no reading signal at all - the page was in `credited`,
        // so the function returned before dwell was ever counted, and anything downstream of it
        // (comprehension checks, the time-actually-read tally) saw a reader who never read a word.
        val accumulated = (dwellMs[focusedChunk] ?: 0L) + elapsedMs
        dwellMs[focusedChunk] = accumulated
        if (accumulated < requiredDwellMs(wordCount, cap)) return CreditTick()

        val firstReadThisSession = readThisSession.add(focusedChunk)
        val read = if (firstReadThisSession) focusedChunk else null
        val readWords = if (firstReadThisSession) wordCount else 0

        // How much time this page may ever be worth. Without a ceiling, a page left open would go
        // on counting as reading; with one, an untouched screen earns what one page is worth and
        // stops. Priced at a *slow* pace so genuinely slow reading is counted in full.
        val ceilingMs = wordCount * 60_000L / SLOW_WPM
        val alreadyGiven = verifiedMs[focusedChunk] ?: 0L
        // The tick that qualifies the page hands over the dwell it took to get there; every tick
        // after that hands over only itself. Either way the total is the time really spent here.
        val wanted = if (firstReadThisSession) accumulated else elapsedMs
        val grantMs = wanted.coerceAtMost((ceilingMs - alreadyGiven).coerceAtLeast(0L))
        verifiedMs[focusedChunk] = alreadyGiven + grantMs

        // Paying out is the part that stays once-ever, and the part the rate cap governs.
        val buckets = bucketsFor(focusedChunk)
        val alreadyPaid = buckets.all { it in credited }
        var creditedChunk: Int? = null
        if (!alreadyPaid && bucketWords >= wordCount) {
            bucketWords -= wordCount
            buckets.forEach { credited.add(it) }
            payableThisSession.add(focusedChunk)
            creditedChunk = focusedChunk
        }
        return CreditTick(
            creditedChunk = creditedChunk,
            creditedWords = if (creditedChunk != null) wordCount else 0,
            readChunk = read,
            readWords = readWords,
            readMs = grantMs,
            payableMs = if (focusedChunk in payableThisSession) grantMs else 0L,
        )
    }

    /** The fastest this page could plausibly have been read, in ms. */
    private fun requiredDwellMs(words: Int, cap: Int): Long =
        (words * 60_000L / cap).coerceAtLeast(MIN_DWELL_MS)

    companion object {
        /** Skip a page holding almost nothing - a chapter end, or a page that is mostly a heading. */
        const val MIN_WORDS = 5
        const val MIN_DWELL_MS = 1_500L
        /** An ordinary reading pace, used to price a page in seconds of earned access. */
        const val TYPICAL_WPM = 240
        /** Stops the bucket hoarding budget during a long dwell and then paying out in a burst. */
        const val MAX_BUCKET_WORDS = 1_500f

        /**
         * How finely paid-for text is recorded, in characters.
         *
         * Small enough that a page maps to only a few buckets, so re-laying the book out cannot
         * hand back much unpaid text; large enough that a book's record stays small.
         */
        const val CREDIT_BUCKET_CHARS = 400

        /**
         * The slowest pace still treated as reading, used to cap what one page can ever be worth.
         *
         * Deliberately slower than anyone reads. Its job is not to judge pace - the dwell floor
         * already refuses anything implausibly fast - but to stop a page left open on a desk from
         * counting as reading all afternoon.
         */
        const val SLOW_WPM = 120

        // contentValueSeconds(words) = words * 60 / TYPICAL_WPM used to live here, and was how
        // both the "actually read" tally and the Focus Gate balance were priced. It is deleted
        // rather than left unused: it credited what a page was notionally worth at 240 words a
        // minute while the dwell floor let a page qualify at 450, so the fastest allowed reading
        // was paid nearly twice the time it took. Time spent is measured now - see CreditTick.readMs.
    }
}
