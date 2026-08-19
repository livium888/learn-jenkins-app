package com.flashcardreader.app.data.repository

import android.content.Context
import java.time.LocalDate

/**
 * Today's two numbers: how long the reader was open, and how much of that was genuine reading.
 *
 * The gap between them is the honest picture of a doom-scrolling session, and the app already knew
 * it — `ReadingCreditTracker` has been computing the second number all along and nothing has ever
 * shown it. A quiz interrupts; a number just tells you the truth and costs nothing, works offline,
 * and needs no AI. Both are worth having.
 *
 * "Read" here means text that passed the anti-fake rules: centred in the viewport, dwelt on long
 * enough for its length, within a global rate cap, and with a real finger on the screen. So the
 * comparison is meaningful rather than flattering.
 */
class ReadingLog(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Seconds with a book open in the foreground today. */
    val openSecondsToday: Long get() = rolled().getLong(KEY_OPEN, 0L)

    /** Seconds of that which was verified reading today. */
    val readSecondsToday: Long get() = rolled().getLong(KEY_READ, 0L)

    /**
     * Share of time-open that was real reading, 0..100, or null when too little time has passed
     * for the number to mean anything. A ratio off ten seconds is noise, not a finding.
     */
    val attentionPct: Int?
        get() {
            val open = openSecondsToday
            if (open < MIN_MEANINGFUL_SECONDS) return null
            return (readSecondsToday * 100 / open).toInt().coerceIn(0, 100)
        }

    fun addOpen(seconds: Long) {
        if (seconds <= 0) return
        val prefs = rolled()
        prefs.edit().putLong(KEY_OPEN, prefs.getLong(KEY_OPEN, 0L) + seconds).apply()
    }

    fun addRead(seconds: Long) {
        if (seconds <= 0) return
        val prefs = rolled()
        prefs.edit().putLong(KEY_READ, prefs.getLong(KEY_READ, 0L) + seconds).apply()
    }

    /** Zeroes both counters when the day turns over, so today is always today. */
    private fun rolled(): android.content.SharedPreferences {
        val today = LocalDate.now().toEpochDay()
        if (prefs.getLong(KEY_DAY, 0L) != today) {
            prefs.edit().putLong(KEY_DAY, today).putLong(KEY_OPEN, 0L).putLong(KEY_READ, 0L).apply()
        }
        return prefs
    }

    private companion object {
        const val PREFS = "reading_log"
        const val KEY_DAY = "day"
        const val KEY_OPEN = "open_seconds"
        const val KEY_READ = "read_seconds"

        /** Below a minute open, the ratio says more about rounding than about attention. */
        const val MIN_MEANINGFUL_SECONDS = 60L
    }
}
