package com.example.medvoicetrainer.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ClaudeService {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    private fun configureTimeouts(conn: HttpURLConnection) {
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
    }

    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Claude API key is empty.")
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        configureTimeouts(conn)

        val payload = buildPayload(model, prompt, systemPrompt)
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(payload.toString())
        writer.flush()
        writer.close()

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optJSONObject("usage")?.let { meta ->
                val input = meta.optInt("input_tokens", 0)
                val output = meta.optInt("output_tokens", 0)
                val cached = meta.optInt("cache_read_input_tokens", 0)
                com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                    "claude", model, "generation", input, output,
                    com.example.medvoicetrainer.analysis.CostTracker.computeAnalysisCost(
                        "claude", input, output, cached, model
                    ),
                    false,
                )
            }
            val contentArray = responseJson.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                return@withContext contentArray.getJSONObject(0).getString("text")
            }
            throw ApiError.unexpectedResponse("Claude")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("Claude", responseCode, errorText)
        }
    }

    /** Same as [generateContent] but also returns normalized token usage for cost tracking. */
    suspend fun generateContentWithUsage(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Pair<String, LlmUsage> = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Claude API key is empty.")
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        configureTimeouts(conn)

        val payload = buildPayload(model, prompt, systemPrompt, temperature = 0.1, maxTokens = maxTokens)
        val writer = OutputStreamWriter(conn.outputStream)

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            val contentArray = responseJson.optJSONArray("content")
            val usageJson = responseJson.optJSONObject("usage")
            val usage = LlmUsage(
                inputTokens = usageJson?.optInt("input_tokens", 0) ?: 0,
                outputTokens = usageJson?.optInt("output_tokens", 0) ?: 0,
                cachedTokens = usageJson?.optInt("cache_read_input_tokens", 0) ?: 0,
                modelUsed = model
            )
            if (contentArray != null && contentArray.length() > 0) {
                return@withContext contentArray.getJSONObject(0).getString("text") to usage
            }
            throw ApiError.unexpectedResponse("Claude")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("Claude", responseCode, errorText)
        }
    }

    suspend fun evaluateSession(
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = com.example.medvoicetrainer.analysis.EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): String {
        val (systemPrompt, promptText) = com.example.medvoicetrainer.analysis.EvalPromptBuilder
            .buildEvalPrompts(transcript, caseJson, nativeLanguage, analysisDomain, rubricContext)
        return generateContent(apiKey, model, promptText, systemPrompt)
    }

    /** Same as [evaluateSession] but also returns normalized token usage for cost tracking. */
    suspend fun evaluateSessionWithUsage(
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = com.example.medvoicetrainer.analysis.EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): Pair<String, LlmUsage> {
        val (systemPrompt, promptText) = com.example.medvoicetrainer.analysis.EvalPromptBuilder
            .buildEvalPrompts(transcript, caseJson, nativeLanguage, analysisDomain, rubricContext)
        return generateContentWithUsage(apiKey, model, promptText, systemPrompt)
    }

    /**
     * The long-standing output cap. Korean CPX grading asks for a verdict on ~40 checklist lines
     * in Korean, which runs well past it, so that one caller passes a larger cap explicitly.
     */
    const val DEFAULT_MAX_TOKENS = 4096

    internal fun buildPayload(
        model: String,
        prompt: String,
        systemPrompt: String?,
        temperature: Double? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("max_tokens", maxTokens)
        temperature?.let { payload.put("temperature", it.coerceIn(0.0, 1.0)) }
        if (!systemPrompt.isNullOrBlank()) {
            payload.put("system", systemPrompt)
        }

        val messagesArray = JSONArray()
        val messageObj = JSONObject()
        messageObj.put("role", "user")
        messageObj.put("content", prompt)
        messagesArray.put(messageObj)
        payload.put("messages", messagesArray)
        return payload
    }
}
