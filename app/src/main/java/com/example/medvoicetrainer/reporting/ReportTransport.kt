package com.example.medvoicetrainer.reporting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The endpoint understood the request and refused it, so resending the same payload cannot help. */
internal class ReportRejectedException(message: String) : IllegalStateException(message)

/**
 * The one HTTP path shared by the report clients and [ReportUploadWorker].
 *
 * The Apps Script endpoint is slow by construction: an idle container has to cold-start, `/exec`
 * answers with a 302 to script.googleusercontent.com (so one submission is two round trips across
 * two hosts), and doPost then makes several Sheets round trips before replying. Ten seconds is an
 * ordinary result rather than a fault, which is why no screen waits for it — see [ReportUploadWorker].
 */
internal object ReportTransport {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Shared so a retried upload can reuse the connection and TLS session of an earlier attempt. */
    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Generous, because nothing is waiting on this: a slow cold start should be absorbed here
        // rather than turn into a retry that pays for another cold start.
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun requireSupportedEndpoint(endpoint: String) {
        require(
            endpoint.startsWith("https://") ||
                endpoint.startsWith("http://127.0.0.1") ||
                endpoint.startsWith("http://localhost")
        ) { "Report endpoint must use HTTPS" }
    }

    /**
     * Failures carry [ReportRejectedException] when the payload itself is the problem; every other
     * exception is transient and worth another attempt.
     */
    suspend fun post(
        endpoint: String,
        payload: String,
        httpClient: OkHttpClient = sharedClient,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireSupportedEndpoint(endpoint)

            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "Report endpoint returned HTTP ${response.code}"
                    // 4xx will never accept this payload; 5xx is worth retrying.
                    if (response.code in 400..499) throw ReportRejectedException(message)
                    throw IllegalStateException(message)
                }
                val responseJson = response.body?.string().orEmpty()
                if (!JSONObject(responseJson).optBoolean("ok", false)) {
                    throw ReportRejectedException("Report endpoint rejected the report")
                }
            }
        }
    }
}
