package com.orbit.ai.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.orbit.ai.OrbitApp
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Shows one notification with the text it was given. Used for reminders, timers and daily routines. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "ORBIT"
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.success() // user denied notifications; nothing we can show
        val n = NotificationCompat.Builder(applicationContext, OrbitApp.CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        return Result.success()
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_TITLE = "title"

        /**
         * One-shot notification at [atMillis]. WorkManager can fire a little late when the phone is in
         * battery saver or Doze; for exact timers the system Clock app is used first (see ActionExecutor).
         */
        fun scheduleOnce(context: Context, atMillis: Long, title: String, text: String) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_TEXT to text, KEY_TITLE to title))
                .addTag("reminder")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun scheduleDaily(context: Context, routineId: Long, hour: Int, minute: Int, text: String) {
            val now = Calendar.getInstance()
            val first = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(first.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_TEXT to text, KEY_TITLE to "ORBIT routine"))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("routine-$routineId", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancelRoutine(context: Context, routineId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("routine-$routineId")
        }
    }
}
