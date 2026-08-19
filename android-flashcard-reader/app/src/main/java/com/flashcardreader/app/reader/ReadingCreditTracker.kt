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
    private val credited = HashSet<Int>()

    /**
     * Pages read properly *this session*, including ones already paid for previously. Not persisted
     * and not restored: a fresh sitting with a familiar book is still a sitting spent reading.
     */
    private val readThisSession = HashSet<Int>()
    private var lastInteractionAt = 0L
    private var bucketWords = 0f

    /** Pages already paid for in an earlier session, loaded from the book's sidecar. */
    fun restore(previouslyCredited: Set<Int>) {
        credited.addAll(previouslyCredited)
    }

    val creditedChunks: Set<Int> get() = credited

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

        // Paying out is the part that stays once-ever, and the part the rate cap governs.
        if (focusedChunk in credited || bucketWords < wordCount) {
            return CreditTick(readChunk = read, readWords = readWords)
        }

        bucketWords -= wordCount
        credited.add(focusedChunk)
        return CreditTick(
            creditedChunk = focusedChunk,
            creditedWords = wordCount,
            readChunk = read,
            readWords = readWords,
        )
    }

    /** The fastest this page could plausibly have been read, in ms. */
    private fun requiredDwellMs(words: Int, cap: Int): Long =
        (words * 60_000L / cap).coerceAtLeast(MIN_DWELL_MS)

    companion object {
        /** Skip blank-line and one-word chunks; chunkText can emit a chunk that is just a newline. */
        const val MIN_WORDS = 5
        const val MIN_DWELL_MS = 1_500L
        /** An ordinary reading pace, used to price a page in seconds of earned access. */
        const val TYPICAL_WPM = 240
        /** Stops the bucket hoarding budget during a long dwell and then paying out in a burst. */
        const val MAX_BUCKET_WORDS = 1_500f

        /** What a page of [words] is worth, in seconds of reading value. */
        fun contentValueSeconds(words: Int): Long = words * 60L / TYPICAL_WPM
    }
}
