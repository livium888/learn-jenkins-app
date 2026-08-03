package com.flashcardreader.app.reminders

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules two daily review reminders - an evening "wind-down" and a morning one - to
 * bracket sleep, when memory consolidates. A tiny SharedPreferences flag remembers whether
 * the user turned them on.
 *
 * Timing is best-effort: WorkManager batches jobs and OEM battery optimizations can delay
 * or drop them, so these are gentle nudges, not alarms.
 */
object ReminderScheduler {
    private const val PREFS = "reminder_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val EVENING = "review-reminder-evening"
    private const val MORNING = "review-reminder-morning"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(EVENING)
            workManager.cancelUniqueWork(MORNING)
            return
        }
        scheduleDaily(context, EVENING, hour = 21)
        scheduleDaily(context, MORNING, hour = 8)
    }

    private fun scheduleDaily(context: Context, name: String, hour: Int) {
        val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisUntilNext(hour), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun millisUntilNext(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
