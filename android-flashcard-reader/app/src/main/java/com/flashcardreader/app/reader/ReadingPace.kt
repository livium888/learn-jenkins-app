package com.flashcardreader.app.reader

import com.flashcardreader.app.data.repository.ReadingDay
import com.flashcardreader.app.data.repository.ReadingHistory

/**
 * How long the rest of a chapter, or the rest of a book, is likely to take.
 *
 * The number that makes this worth having is the pace, and this app is in an unusually good
 * position to know it. A typical e-reader infers your speed from how often pages turn, which counts
 * a page you flicked past and a page you left open while making tea. Here the reading tracker has
 * already thrown both of those out - the words counted are the ones that sat in view long enough
 * for their length, with a finger on the screen. So this is the pace you actually read at.
 *
 * Android-free so the arithmetic and the wording can be tested on the JVM.
 */
object ReadingPace {

    /**
     * Words a minute to assume until enough has been read to know better.
     *
     * The published average for silent reading of non-fiction is around 238 words a minute
     * (Brysbaert's meta-analysis of 190 studies), which is a far better starting guess than a round
     * number - and it is replaced by the reader's own as soon as there is enough to go on.
     */
    const val ASSUMED_WPM = 238

    /** Below this there is not enough verified reading for a personal pace to mean anything. */
    const val MIN_WORDS_FOR_PACE = 2_000

    /** Days of history to average over. Long enough to be stable, short enough to follow a change. */
    const val PACE_WINDOW_DAYS = 30

    /** Sane bounds - a corrupt row must not produce "3 seconds left in this book". */
    const val MIN_WPM = 60
    const val MAX_WPM = 800

    /** A reader's measured pace, and whether it is theirs or still the assumed one. */
    data class Pace(val wordsPerMinute: Int, val measured: Boolean)

    fun paceFrom(history: List<ReadingDay>, todayEpochDay: Long): Pace {
        val words = ReadingHistory.readWordsIn(history, todayEpochDay, PACE_WINDOW_DAYS)
        val seconds = ReadingHistory.readSecondsIn(history, todayEpochDay, PACE_WINDOW_DAYS)
        if (words < MIN_WORDS_FOR_PACE || seconds <= 0) return Pace(ASSUMED_WPM, measured = false)
        val wpm = (words * 60 / seconds).toInt()
        if (wpm !in MIN_WPM..MAX_WPM) return Pace(ASSUMED_WPM, measured = false)
        return Pace(wpm, measured = true)
    }

    /** Minutes [words] would take at this pace, rounded to whole minutes and never negative. */
    fun minutesFor(words: Int, pace: Pace): Int =
        if (words <= 0) 0 else Math.round(words.toFloat() / pace.wordsPerMinute)

    /**
     * How many words are left, estimated from characters.
     *
     * Counting the words in the rest of a chapter on every page turn would walk tens of thousands
     * of characters for a number that is approximate anyway. The book's own average word length,
     * measured once, turns a character count into a word count accurately enough for a figure that
     * is rounded to the minute.
     */
    fun wordsRemaining(charsRemaining: Int, charsPerWord: Float): Int {
        if (charsRemaining <= 0) return 0
        val perWord = if (charsPerWord > 0f) charsPerWord else 5.7f
        return (charsRemaining / perWord).toInt()
    }

    /**
     * The line shown under the page: "12 min left in this chapter".
     *
     * Says "left in this chapter" rather than a bare number because the two horizons answer
     * different questions - whether to carry on to the end of a section, or whether to start a book
     * at all - and a figure with no noun attached answers neither.
     */
    fun label(minutes: Int, scope: String): String = when {
        minutes <= 0 -> "Under a minute left $scope"
        minutes == 1 -> "1 min left $scope"
        minutes < 60 -> "$minutes min left $scope"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            val hourPart = if (hours == 1) "1 hr" else "$hours hrs"
            if (rest == 0) "$hourPart left $scope" else "$hourPart $rest min left $scope"
        }
    }

    const val IN_CHAPTER = "in this chapter"
    const val IN_BOOK = "in this book"
}
