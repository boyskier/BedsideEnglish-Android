package com.example.medvoicetrainer.reporting

import android.content.Context
import java.io.File

/**
 * The durable hand-off between a report dialog and [ReportUploadWorker].
 *
 * The payload is a file rather than worker input [androidx.work.Data] because a 4 000-character AI
 * response in a non-Latin script exceeds WorkManager's 10 KB Data ceiling, and going over it makes
 * enqueueing throw — the one thing a submit tap must never do. A file also survives process death,
 * so a queued report still reaches the endpoint after the app is killed.
 */
internal object PendingReportQueue {
    private const val DIRECTORY = "pending_reports"

    /** Report ids are UUIDs, but this is what turns one into a file name, so keep it defensive. */
    private val SAFE_NAME = Regex("[^A-Za-z0-9-]")

    /** Returns the queue file name, or null when the payload could not be stored. */
    fun write(context: Context, reportId: String, payload: String): String? = try {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val fileName = SAFE_NAME.replace(reportId, "_").take(100) + ".json"
        File(directory, fileName).writeText(payload)
        fileName
    } catch (_: Exception) {
        null
    }

    /** Returns null once the report has been delivered and its file removed. */
    fun read(context: Context, fileName: String): String? = try {
        val file = File(File(context.filesDir, DIRECTORY), fileName)
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null
    }

    fun delete(context: Context, fileName: String) {
        try {
            File(File(context.filesDir, DIRECTORY), fileName).delete()
        } catch (_: Exception) {
            // A payload that outlives its upload is a few stale KB, not a fault worth crashing on.
        }
    }
}
