package com.flashcardreader.app.data.repository

import android.content.Context
import java.time.LocalDate

/**
 * How long a book was open, how much of that was genuine reading, and now the days before today.
 *
 * The gap between the two numbers is the honest picture of a doom-scrolling session, and the app
 * already knew it - `ReadingCreditTracker` has been computing the second one all along.
 *
 * "Read" here means text that passed the anti-fake rules: centred in the viewport, dwelt on long
 * enough for its length, within a global rate cap, and with a real finger on the screen. So the
 * comparison is meaningful rather than flattering.
 *
 * Past days used to be deleted at midnight, and deleted by the *getters* - which the Progress
 * screen calls during composition, so reading until 23:58 and opening Progress at 00:01 meant that
 * looking at yesterday's number was what destroyed it. Today's totals are now derived without
 * writing anything, and the finished day is filed into [ReadingHistory] rather than zeroed.
 */
class ReadingLog(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val today: Long get() = LocalDate.now().toEpochDay()

    /** Seconds with a book open in the foreground today. */
    val openSecondsToday: Long get() = if (storedDay == today) prefs.getLong(KEY_OPEN, 0L) else 0L

    /** Seconds of that which was verified reading today. */
    val readSecondsToday: Long get() = if (storedDay == today) prefs.getLong(KEY_READ, 0L) else 0L

    /**
     * Share of time-open that was real reading, 0..100, or null when too little time has passed
     * for the number to mean anything. A ratio off ten seconds is noise, not a finding.
     */
    val attentionPct: Int?
        get() = ReadingDay(today, openSecondsToday, readSecondsToday).attentionPct

    /** Every day kept, oldest first, today included when it has anything in it. */
    fun history(): List<ReadingDay> {
        val past = ReadingHistory.parse(prefs.getString(KEY_HISTORY, "").orEmpty())
        val open = openSecondsToday
        val read = readSecondsToday
        if (open <= 0 && read <= 0) return past
        return ReadingHistory.rolledInto(past, ReadingDay(today, open, read))
    }

    fun addOpen(seconds: Long) = add(KEY_OPEN, seconds)

    fun addRead(seconds: Long) = add(KEY_READ, seconds)

    private fun add(key: String, seconds: Long) {
        if (seconds <= 0) return
        rollIfNeeded()
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + seconds).apply()
    }

    private val storedDay: Long get() = prefs.getLong(KEY_DAY, 0L)

    /**
     * Files the finished day into the history and starts a fresh one.
     *
     * Called only when something is about to be *written*. Reading a total never mutates anything,
     * which is the whole point of the change.
     */
    private fun rollIfNeeded() {
        val today = this.today
        val stored = storedDay
        if (stored == today) return
        val finished = ReadingDay(stored, prefs.getLong(KEY_OPEN, 0L), prefs.getLong(KEY_READ, 0L))
        val history = ReadingHistory.rolledInto(
            ReadingHistory.parse(prefs.getString(KEY_HISTORY, "").orEmpty()),
            finished,
        )
        prefs.edit()
            .putString(KEY_HISTORY, ReadingHistory.serialize(history))
            .putLong(KEY_DAY, today)
            .putLong(KEY_OPEN, 0L)
            .putLong(KEY_READ, 0L)
            .apply()
    }

    private companion object {
        const val PREFS = "reading_log"
        const val KEY_DAY = "day"
        const val KEY_OPEN = "open_seconds"
        const val KEY_READ = "read_seconds"
        const val KEY_HISTORY = "history"
    }
}
