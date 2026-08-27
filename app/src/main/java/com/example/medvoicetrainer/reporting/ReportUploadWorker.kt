package com.example.medvoicetrainer.reporting

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Delivers a user report to the Apps Script endpoint away from any screen.
 *
 * A submission takes several seconds that the learner has no reason to watch (see
 * [ReportTransport] for where that time goes), and the old in-dialog call was also cancelled with
 * the composition — backgrounding the app mid-submit silently lost the report. WorkManager holds
 * the request across process death and retries it when the network comes back, so the dialog can
 * confirm immediately and the report still arrives.
 */
class ReportUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_PAYLOAD_FILE) ?: return Result.failure()
        val endpoint = inputData.getString(KEY_ENDPOINT).orEmpty()
        // No payload means an earlier attempt already delivered this report and cleaned up.
        val payload = PendingReportQueue.read(applicationContext, fileName) ?: return Result.success()

        val result = ReportTransport.post(endpoint, payload)
        val error = result.exceptionOrNull()

        return when {
            result.isSuccess -> {
                PendingReportQueue.delete(applicationContext, fileName)
                Result.success()
            }
            // The endpoint refused this payload, or it was never sendable. Retrying cannot change
            // either answer, so drop it rather than hold disk and battery for a week.
            error is ReportRejectedException || error is IllegalArgumentException -> {
                PendingReportQueue.delete(applicationContext, fileName)
                Result.failure()
            }
            runAttemptCount + 1 >= MAX_ATTEMPTS -> {
                PendingReportQueue.delete(applicationContext, fileName)
                Result.failure()
            }
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_PAYLOAD_FILE = "payload_file"
        private const val KEY_ENDPOINT = "endpoint"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L

        /**
         * Process-lifetime scope: queueing must outlive the dialog that started it, which is
         * exactly what the dialog's own composition-bound scope could not promise.
         */
        private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Returns as soon as the hand-off is scheduled. Writing the payload and WorkManager's own
         * database insert are both small, but they are still disk I/O and the caller is a button
         * tap, so they run off the main thread.
         */
        fun enqueue(context: Context, reportId: String, payload: String, endpoint: String) {
            val appContext = context.applicationContext
            queueScope.launch {
                val fileName = PendingReportQueue.write(appContext, reportId, payload) ?: return@launch
                val input = Data.Builder()
                    .putString(KEY_PAYLOAD_FILE, fileName)
                    .putString(KEY_ENDPOINT, endpoint)
                    .build()
                val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
                    .setInputData(input)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()

                // KEEP, keyed by report id: a double tap cannot produce two rows in the sheet.
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "report-upload-$reportId",
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
