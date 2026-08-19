package com.flashcardreader.app.focus

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.time.LocalDate

/**
 * The credit balance: reading earns seconds of access to gated apps, opening those apps spends them.
 *
 * Daily counters roll over at midnight. Two ceilings apply: cards can only contribute
 * [FocusPrefs.dailyCardCapMinutes] per day (so they stay a bonus rather than something to farm), and
 * everything together is capped by [FocusPrefs.dailyTotalCapMinutes].
 *
 * Reads and writes come from both the UI and the monitor service, so mutations are synchronized.
 */
class CreditBank(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val settings = FocusPrefs(context)

    val balanceSeconds: Long get() = prefs.getLong(KEY_BALANCE, 0L)

    /** Emits the balance whenever it changes, for live UI. */
    fun observeBalance(): Flow<Long> = callbackFlow {
        trySend(balanceSeconds)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BALANCE) trySend(balanceSeconds)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    /**
     * Credits validated reading time. [readingSeconds] must already have passed the anti-fake
     * checks in ReadingCreditTracker. Returns the seconds actually added after caps.
     */
    @Synchronized
    fun earnFromReading(readingSeconds: Long): Long {
        if (readingSeconds <= 0) return 0
        rollDayIfNeeded()
        val requested = (readingSeconds * settings.minutesPerReadingMinute).toLong()
        return grant(requested)
    }

    /**
     * Credits one correctly-answered cloze card. Returns seconds added (0 if this term already
     * earned today, or a ceiling is reached).
     */
    @Synchronized
    fun earnFromCard(termId: Long): Long {
        rollDayIfNeeded()
        val already = prefs.getStringSet(KEY_TERMS_TODAY, emptySet()).orEmpty()
        if (already.contains(termId.toString())) return 0
        val cardCapSeconds = settings.dailyCardCapMinutes * 60L
        val cardEarned = prefs.getLong(KEY_CARD_EARNED_TODAY, 0L)
        val room = cardCapSeconds - cardEarned
        if (room <= 0) return 0
        val granted = grant(minOf(settings.secondsPerCard.toLong(), room))
        if (granted > 0) {
            prefs.edit()
                .putStringSet(KEY_TERMS_TODAY, already + termId.toString())
                .putLong(KEY_CARD_EARNED_TODAY, cardEarned + granted)
                .apply()
        }
        return granted
    }

    /** Spends up to [seconds] of balance while a gated app is open. Returns the remaining balance. */
    @Synchronized
    fun spend(seconds: Long): Long {
        val remaining = (balanceSeconds - seconds).coerceAtLeast(0L)
        prefs.edit().putLong(KEY_BALANCE, remaining).apply()
        return remaining
    }

    /** How much more can still be earned today, in seconds. */
    @Synchronized
    fun remainingDailyAllowanceSeconds(): Long {
        rollDayIfNeeded()
        val cap = settings.dailyTotalCapMinutes * 60L
        return (cap - prefs.getLong(KEY_EARNED_TODAY, 0L)).coerceAtLeast(0L)
    }

    /** Applies the overall daily ceiling and adds to the balance. Returns what was actually added. */
    private fun grant(requested: Long): Long {
        val cap = settings.dailyTotalCapMinutes * 60L
        val earnedToday = prefs.getLong(KEY_EARNED_TODAY, 0L)
        val granted = minOf(requested, (cap - earnedToday).coerceAtLeast(0L))
        if (granted <= 0) return 0
        prefs.edit()
            .putLong(KEY_BALANCE, balanceSeconds + granted)
            .putLong(KEY_EARNED_TODAY, earnedToday + granted)
            .apply()
        return granted
    }

    /** Resets the per-day counters when the date has changed. The balance itself carries over. */
    private fun rollDayIfNeeded() {
        val today = LocalDate.now().toEpochDay()
        if (prefs.getLong(KEY_DAY, 0L) != today) {
            prefs.edit()
                .putLong(KEY_DAY, today)
                .putLong(KEY_EARNED_TODAY, 0L)
                .putLong(KEY_CARD_EARNED_TODAY, 0L)
                .remove(KEY_TERMS_TODAY)
                .apply()
        }
    }

    companion object {
        const val PREFS = "focus_credit"
        private const val KEY_BALANCE = "balance_seconds"
        private const val KEY_DAY = "day"
        private const val KEY_EARNED_TODAY = "earned_today"
        private const val KEY_CARD_EARNED_TODAY = "card_earned_today"
        private const val KEY_TERMS_TODAY = "terms_earned_today"
    }
}
