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
 * Reading is the only thing that earns. Daily counters roll over at midnight, and the day's total
 * is capped by [FocusPrefs.dailyTotalCapMinutes].
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

    // There used to be a second earn path here: a correctly *typed* cloze answer banked time.
    // It only worked because typing cannot be guessed. Answers are multiple choice now, and a
    // four-option tap is right a quarter of the time by chance - so the same reward would have
    // made credit farmable by tapping. Reading is the only thing that earns, which is what the
    // gate was for in the first place.

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
                // Cleared rather than left behind: these belonged to the card earn path, which
                // is gone, and a stale key is a question someone has to answer later.
                .remove(KEY_CARD_EARNED_TODAY)
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
