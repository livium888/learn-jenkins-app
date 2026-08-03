package com.flashcardreader.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.flashcardreader.app.R
import com.flashcardreader.app.data.db.AppDatabase

/**
 * Fires on the evening + morning schedule (see ReminderScheduler): if any flashcards are
 * due, posts a notification nudging a quick review. Reviewing just before sleep and again
 * after waking rides sleep-dependent memory consolidation.
 */
class ReviewReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dueCount = AppDatabase.get(applicationContext).termDao().getDue(System.currentTimeMillis()).size
        if (dueCount > 0) notifyDue(applicationContext, dueCount)
        return Result.success()
    }

    private fun notifyDue(context: Context, count: Int) {
        val channelId = "review-reminders"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Review reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$count word${if (count == 1) "" else "s"} due for review")
            .setContentText("A quick review now helps them stick through sleep.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        // On Android 13+ posting requires the runtime permission; skip silently if not granted.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(context).notify(count.hashCode(), notification)
    }
}
