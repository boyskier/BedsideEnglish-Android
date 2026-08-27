package com.example.medvoicetrainer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.medvoicetrainer.db.Repository
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * A non-foreground WorkManager guard for a feedback request that the ViewModel has already
 * started. Keeping an ordinary Worker active gives Android a durable owner for the user-requested
 * hand-off without declaring a data-sync foreground service. The completed Room row is the source
 * of truth, so a restarted worker can still deliver the notification after process recreation.
 */
class FeedbackCompletionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getInt(KEY_SESSION_ID, 0)
        val caseName = inputData.getString(KEY_CASE_NAME).orEmpty()
        if (sessionId <= 0) return Result.failure()

        val repository = Repository(applicationContext)
        repeat(MAX_POLLS) {
            val session = repository.getSessionById(sessionId) ?: return Result.failure()
            if (!session.summaryFeedback.isNullOrBlank()) {
                FeedbackNotification.postReady(applicationContext, caseName)
                return Result.success()
            }
            delay(POLL_INTERVAL_MS)
        }

        // The original request was interrupted (for example by process death). The transcript is
        // still durable; direct the learner to History's existing Retry analysis action.
        FeedbackNotification.postInterrupted(applicationContext, caseName)
        return Result.failure()
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_CASE_NAME = "case_name"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLLS = 150 // five minutes, below WorkManager's execution ceiling

        fun enqueue(context: Context, sessionId: Int, caseName: String) {
            val input = Data.Builder()
                .putInt(KEY_SESSION_ID, sessionId)
                .putString(KEY_CASE_NAME, caseName)
                .build()
            val request = OneTimeWorkRequestBuilder<FeedbackCompletionWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    10,
                    TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "feedback-completion-$sessionId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

object FeedbackNotification {
    const val EXTRA_OPEN_HISTORY = "open_feedback_history"
    private const val CHANNEL_ID = "feedback_ready"
    private const val READY_NOTIFICATION_ID = 4302

    fun postReady(context: Context, caseName: String) {
        val safeName = caseName.ifBlank { "Your session" }
        post(
            context,
            title = "Your session feedback is ready",
            body = "$safeName feedback is ready. Review it in History and study your corrections.",
        )
    }

    fun postInterrupted(context: Context, caseName: String) {
        val safeName = caseName.ifBlank { "Your session" }
        post(
            context,
            title = "Feedback needs another try",
            body = "$safeName was saved, but analysis was interrupted. Open History to retry it.",
        )
    }

    private fun post(context: Context, title: String, body: String) {
        ensureChannel(context)
        val openHistory = PendingIntent.getActivity(
            context,
            READY_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_HISTORY, true)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openHistory)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(READY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The in-app completion banner remains available when notification permission is off.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Feedback ready", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
