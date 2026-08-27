package com.example.medvoicetrainer.reporting

import android.content.Context
import com.example.medvoicetrainer.BuildConfig
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID

enum class AiReportCategory(val wireValue: String, val labelKey: String) {
    HARMFUL_OR_DANGEROUS("harmful_or_dangerous", "ai_report.category.harmful"),
    MEDICAL_MISINFORMATION("medical_misinformation", "ai_report.category.medical"),
    SEXUAL_CONTENT("sexual_content", "ai_report.category.sexual"),
    HATE_OR_HARASSMENT("hate_or_harassment", "ai_report.category.harassment"),
    DECEPTIVE_CONTENT("deceptive_content", "ai_report.category.deceptive"),
    OTHER("other", "ai_report.category.other"),
}

data class AiResponseReport(
    val category: AiReportCategory,
    val surface: String,
    val aiResponse: String,
    val provider: String,
    val model: String,
    val optionalNote: String = "",
    val reportId: String = UUID.randomUUID().toString(),
    val appVersion: String = BuildConfig.VERSION_NAME,
)

/**
 * Dedicated, user-triggered reporting transport. This is intentionally separate from Telemetry:
 * it sends only the one AI output the learner selected, never a full transcript, audio, API key,
 * account identifier, or the telemetry installation id.
 */
class AiResponseReportClient(
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
    fun submitInBackground(context: Context, report: AiResponseReport): Result<Unit> = runCatching {
        validate(report)
        ReportUploadWorker.enqueue(
            context = context,
            reportId = report.reportId,
            payload = reportJson(report).toString(),
            endpoint = endpoint,
        )
    }

    /** Sends the report and waits for the endpoint's verdict. */
    suspend fun submit(report: AiResponseReport): Result<Unit> =
        runCatching { validate(report) }.mapCatching {
            ReportTransport.post(endpoint, reportJson(report).toString(), httpClient).getOrThrow()
        }

    private fun validate(report: AiResponseReport) {
        ReportTransport.requireSupportedEndpoint(endpoint)
        require(report.aiResponse.isNotBlank()) { "AI response cannot be blank" }
    }

    internal fun reportJson(report: AiResponseReport): JSONObject = JSONObject()
        .put("reportId", report.reportId.trim().take(REPORT_ID_MAX))
        .put("category", report.category.wireValue)
        .put("surface", report.surface.trim().take(SURFACE_MAX))
        .put("provider", report.provider.trim().take(PROVIDER_MAX))
        .put("model", report.model.trim().take(MODEL_MAX))
        .put("appVersion", report.appVersion.trim().take(APP_VERSION_MAX))
        .put("aiResponse", report.aiResponse.trim().take(AI_RESPONSE_MAX))
        .put("optionalNote", report.optionalNote.trim().take(OPTIONAL_NOTE_MAX))

    companion object {
        const val AI_RESPONSE_MAX = 4_000
        const val OPTIONAL_NOTE_MAX = 500
        private const val REPORT_ID_MAX = 100
        private const val SURFACE_MAX = 50
        private const val PROVIDER_MAX = 30
        private const val MODEL_MAX = 100
        private const val APP_VERSION_MAX = 30
        private val defaultClient = ReportTransport.sharedClient
    }
}
