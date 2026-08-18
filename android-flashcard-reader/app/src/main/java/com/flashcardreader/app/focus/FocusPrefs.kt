package com.flashcardreader.app.focus

import android.content.Context

/**
 * Settings for Focus Gate: which apps are gated, how reading converts into access, and the
 * thresholds the anti-fake reading tracker uses. Same synchronous SharedPreferences style as
 * [com.flashcardreader.app.data.reference.DictionaryPrefs].
 *
 * Thresholds are exposed as settings on purpose - the right dwell floor and idle timeout can only
 * really be found by living with them for a week.
 */
class FocusPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch. Off until the user has granted usage access + overlay. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Package names of the apps that require credit to open. */
    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED, value).apply()

    /** Minutes of app access earned per minute of validated reading. */
    var minutesPerReadingMinute: Float
        get() = prefs.getFloat(KEY_RATE, 2f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

    /** Credit granted for one correctly-typed cloze answer, in seconds. */
    var secondsPerCard: Int
        get() = prefs.getInt(KEY_CARD_SECONDS, 60)
        set(value) = prefs.edit().putInt(KEY_CARD_SECONDS, value).apply()

    /** Ceiling on how much credit cards can contribute per day, in minutes ("capped" bonus). */
    var dailyCardCapMinutes: Int
        get() = prefs.getInt(KEY_CARD_CAP, 15)
        set(value) = prefs.edit().putInt(KEY_CARD_CAP, value).apply()

    /** Ceiling on total credit earnable per day, in minutes. */
    var dailyTotalCapMinutes: Int
        get() = prefs.getInt(KEY_TOTAL_CAP, 120)
        set(value) = prefs.edit().putInt(KEY_TOTAL_CAP, value).apply()

    /**
     * Fastest reading speed treated as plausible. A page must stay in view at least
     * words/maxWpm minutes to count, so flinging and fast auto-scroll earn nothing.
     */
    var maxWpm: Int
        get() = prefs.getInt(KEY_MAX_WPM, 450)
        set(value) = prefs.edit().putInt(KEY_MAX_WPM, value).apply()

    /** Accrual pauses after this long with no scrolling or touching (phone left open). */
    var idleTimeoutSeconds: Int
        get() = prefs.getInt(KEY_IDLE, 60)
        set(value) = prefs.edit().putInt(KEY_IDLE, value).apply()

    companion object {
        const val PREFS = "focus_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_RATE = "minutes_per_reading_minute"
        private const val KEY_CARD_SECONDS = "seconds_per_card"
        private const val KEY_CARD_CAP = "daily_card_cap"
        private const val KEY_TOTAL_CAP = "daily_total_cap"
        private const val KEY_MAX_WPM = "max_wpm"
        private const val KEY_IDLE = "idle_timeout"
    }
}
