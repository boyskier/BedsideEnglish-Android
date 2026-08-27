package com.example.medvoicetrainer.reporting

import android.content.Context
import com.example.medvoicetrainer.BuildConfig
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID

enum class ContentIssueCategory(val wireValue: String, val labelKey: String) {
    MEDICAL_INACCURACY("medical_inaccuracy", "content_report.category.medical"),
    TYPO_OR_GRAMMAR("typo_or_grammar", "content_report.category.typo"),
    INCONSISTENT_CONTENT("inconsistent_content", "content_report.category.inconsistent"),
    INAPPROPRIATE_CONTENT("inappropriate_content", "content_report.category.inappropriate"),
    OTHER("other", "content_report.category.other"),
}

data class ContentIssueReport(
    val category: ContentIssueCategory,
    val contentType: String,
    val contentId: String,
    val contentTitle: String,
    val surface: String,
    val optionalNote: String = "",
    val reportId: String = UUID.randomUUID().toString(),
    val appVersion: String = BuildConfig.VERSION_NAME,
)

/**
 * Sends a reference to bundled learning content through the existing AI-report web app.
 * The case body is deliberately omitted: a stable content id is enough to locate bundled data,
 * and not transmitting it prevents custom/imported patient material from leaking into reports.
 */
class ContentIssueReportClient(
    private val endpoint: String = BuildConfig.AI_REPORT_ENDPOINT,
    private val httpClient: OkHttpClient = defaultClient,
) {
    /**
     * Validates the report and hands delivery to [ReportUploadWorker], returning at once.
     *
     * A caller on the UI thread must use this rather than [submit]: the endpoint routinely takes
     * the better part of ten seconds, which is time the learner has no reason to spend staring at
     * a spinner. Failure means the report was malformed and nothing was queued.
     */
    fun submitInBackground(context: Context, report: ContentIssueReport): Result<Unit> = runCatching {
        validate(report)
        ReportUploadWorker.enqueue(
            context = context,
            reportId = report.reportId,
            payload = reportJson(report).toString(),
            endpoint = endpoint,
        )
    }

    /** Sends the report and waits for the endpoint's verdict. */
    suspend fun submit(report: ContentIssueReport): Result<Unit> =
        runCatching { validate(report) }.mapCatching {
            ReportTransport.post(endpoint, reportJson(report).toString(), httpClient).getOrThrow()
        }

    private fun validate(report: ContentIssueReport) {
        ReportTransport.requireSupportedEndpoint(endpoint)
        require(report.contentId.isNotBlank()) { "Content id cannot be blank" }
    }

    internal fun reportJson(report: ContentIssueReport): JSONObject = JSONObject()
        .put("reportType", REPORT_TYPE)
        .put("reportId", report.reportId.trim().take(REPORT_ID_MAX))
        .put("contentType", report.contentType.trim().take(CONTENT_TYPE_MAX))
        .put("contentId", report.contentId.trim().take(CONTENT_ID_MAX))
        .put("contentTitle", report.contentTitle.trim().take(CONTENT_TITLE_MAX))
        .put("issueCategory", report.category.wireValue)
        .put("surface", report.surface.trim().take(SURFACE_MAX))
        .put("optionalNote", report.optionalNote.trim().take(OPTIONAL_NOTE_MAX))
        .put("appVersion", report.appVersion.trim().take(APP_VERSION_MAX))

    companion object {
        const val OPTIONAL_NOTE_MAX = 500
        internal const val REPORT_TYPE = "content_issue"
        private const val REPORT_ID_MAX = 100
        private const val CONTENT_TYPE_MAX = 50
        private const val CONTENT_ID_MAX = 100
        private const val CONTENT_TITLE_MAX = 200
        private const val SURFACE_MAX = 50
        private const val APP_VERSION_MAX = 30
        private val defaultClient = ReportTransport.sharedClient
    }
}
