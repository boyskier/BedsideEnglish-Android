package com.example.medvoicetrainer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Opt-in next-day practice reminder — the early-learning funnel's only day-2 return hook. A soft,
 * inexact [AlarmManager] one-shot (deliberately [AlarmManager.set], so no exact-alarm permission is
 * needed) fires [PracticeReminderReceiver], which posts a single local notification.
 *
 * Localized strings are resolved by the caller (a Compose surface that has the translator in scope)
 * and carried as intent extras, so the receiver never needs to touch the i18n layer or read
 * settings from a possibly-fresh process. Best-effort by design: a device reboot before the alarm
 * fires drops it (there is no BOOT_COMPLETED rescheduling), which is acceptable for a gentle nudge
 * the learner explicitly opted into.
 */
object PracticeReminder {
    private const val CHANNEL_ID = "practice_reminder"
    private const val NOTIFICATION_ID = 4202
    private const val REQUEST_CODE = 4202
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_BODY = "reminder_body"

    /** Schedule (or replace) the single pending reminder [delayMillis] from now. */
    fun schedule(context: Context, delayMillis: Long, title: String, body: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + delayMillis.coerceAtLeast(60_000L)
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(appContext, title, body))
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(appContext, "", ""))
    }

    private fun pendingIntent(context: Context, title: String, body: String): PendingIntent {
        val intent = Intent(context, PracticeReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Practice reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Optional daily reminders to keep up your speaking practice."
            }
        )
    }

    /** Build + post the reminder notification. Called only from [PracticeReminderReceiver]. */
    internal fun post(context: Context, title: String, body: String) {
        ensureChannel(context)
        val safeTitle = title.ifBlank { "Ready for today's practice?" }
        val safeBody = body.ifBlank { "Tap to start a session." }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()
        try {
            // No-op if POST_NOTIFICATIONS was declined on API 33+ — nothing to show, and that's fine.
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Defensive: some OEMs throw instead of silently dropping when permission is missing.
        }
    }
}

/** Receives the scheduled [PracticeReminder] alarm and posts the notification. */
class PracticeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(PracticeReminder.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(PracticeReminder.EXTRA_BODY).orEmpty()
        PracticeReminder.post(context.applicationContext, title, body)
    }
}
