package com.flashcardreader.app.data.repository

/** One day's totals: how long a book was open, and how much of that was genuine reading. */
data class ReadingDay(
    val epochDay: Long,
    val openSeconds: Long,
    val readSeconds: Long,
) {
    /** Share of time-open that was real reading, 0..100, or null when the day is too short to say. */
    val attentionPct: Int?
        get() = if (openSeconds < MIN_MEANINGFUL_SECONDS) null
        else (readSeconds * 100 / openSeconds).toInt().coerceIn(0, 100)

    companion object {
        /** Below a minute open, the ratio says more about rounding than about attention. */
        const val MIN_MEANINGFUL_SECONDS = 60L
    }
}

/**
 * Keeps past days of reading instead of deleting them.
 *
 * The app's most distinctive measurement - verified reading minutes, as opposed to minutes with a
 * book open - used to survive exactly until midnight. Worse, the day-roll ran inside the *getters*,
 * which the Progress screen calls during composition: so reading until 23:58 and opening Progress
 * at 00:01 meant the act of looking at yesterday's number was what erased it.
 *
 * Android-free so the rolling and parsing can be tested on the JVM.
 */
object ReadingHistory {

    /** How many past days to keep. Long enough for a month's trend with room to spare. */
    const val MAX_DAYS = 90

    private const val ROW = ";"
    private const val FIELD = ","

    /** Tolerant of junk: a corrupted row costs that day, not the whole history. */
    fun parse(raw: String): List<ReadingDay> = raw
        .split(ROW)
        .mapNotNull { row ->
            val parts = row.split(FIELD)
            if (parts.size != 3) return@mapNotNull null
            val day = parts[0].toLongOrNull() ?: return@mapNotNull null
            val open = parts[1].toLongOrNull() ?: return@mapNotNull null
            val read = parts[2].toLongOrNull() ?: return@mapNotNull null
            ReadingDay(day, open.coerceAtLeast(0), read.coerceAtLeast(0))
        }
        .sortedBy { it.epochDay }

    fun serialize(days: List<ReadingDay>): String = days
        .sortedBy { it.epochDay }
        .takeLast(MAX_DAYS)
        .joinToString(ROW) { "${it.epochDay}$FIELD${it.openSeconds}$FIELD${it.readSeconds}" }

    /**
     * Files a finished day into the history, replacing any row already there for that date.
     *
     * A day with nothing in it is not recorded - a run of empty rows would only be noise on a
     * trend, and "did not open the app" is already visible as a gap.
     */
    fun rolledInto(history: List<ReadingDay>, finished: ReadingDay): List<ReadingDay> {
        if (finished.openSeconds <= 0 && finished.readSeconds <= 0) return history
        return (history.filterNot { it.epochDay == finished.epochDay } + finished)
            .sortedBy { it.epochDay }
            .takeLast(MAX_DAYS)
    }

    /** The days from [fromEpochDay] onwards, oldest first. */
    fun since(history: List<ReadingDay>, fromEpochDay: Long): List<ReadingDay> =
        history.filter { it.epochDay >= fromEpochDay }.sortedBy { it.epochDay }

    /**
     * How many of the last [days] had any verified reading at all.
     *
     * Deliberately a count and not a streak. A streak punishes one missed day and rewards opening
     * the app to keep a number alive, which is the opposite of what everything else here measures.
     */
    fun daysReadIn(history: List<ReadingDay>, todayEpochDay: Long, days: Int): Int =
        since(history, todayEpochDay - days + 1).count { it.readSeconds > 0 }

    /** Total verified reading over the last [days], in seconds. */
    fun readSecondsIn(history: List<ReadingDay>, todayEpochDay: Long, days: Int): Long =
        since(history, todayEpochDay - days + 1).sumOf { it.readSeconds }
}
