package com.example.medvoicetrainer.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object OpenAIService {
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
            throw IllegalArgumentException("OpenAI API key is empty.")
        }

        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        configureTimeouts(conn)

        val payload = JSONObject()
        payload.put("model", model)
        
        if (prompt.contains("JSON") || prompt.contains("json") || systemPrompt?.contains("JSON") == true) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }

        val messagesArray = JSONArray()
        
        if (systemPrompt != null) {
            val systemMessageObj = JSONObject()
            systemMessageObj.put("role", "system")
            systemMessageObj.put("content", systemPrompt)
            messagesArray.put(systemMessageObj)
        }

        val userMessageObj = JSONObject()
        userMessageObj.put("role", "user")
        userMessageObj.put("content", prompt)
        messagesArray.put(userMessageObj)
        
        payload.put("messages", messagesArray)

        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(payload.toString())
        writer.flush()
        writer.close()

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optJSONObject("usage")?.let { meta ->
                val input = meta.optInt("prompt_tokens", 0)
                val output = meta.optInt("completion_tokens", 0)
                val cached = meta.optJSONObject("prompt_tokens_details")
                    ?.optInt("cached_tokens", 0) ?: 0
                com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                    "openai", model, "generation", input, output,
                    com.example.medvoicetrainer.analysis.CostTracker.computeAnalysisCost(
                        "openai", input, output, cached, model
                    ),
                    false,
                )
            }
            val choicesArray = responseJson.optJSONArray("choices")
            if (choicesArray != null && choicesArray.length() > 0) {
                val messageObj = choicesArray.getJSONObject(0).optJSONObject("message")
                return@withContext messageObj?.getString("content") ?: throw Exception("Empty content in response")
            }
            throw ApiError.unexpectedResponse("OpenAI")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("OpenAI", responseCode, errorText)
        }
    }

    /** Same as [generateContent] but also returns normalized token usage for cost tracking. */
    suspend fun generateContentWithUsage(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String? = null
    ): Pair<String, LlmUsage> = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("OpenAI API key is empty.")
        }

        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        configureTimeouts(conn)

        val payload = JSONObject()
        payload.put("model", model)

        if (prompt.contains("JSON") || prompt.contains("json") || systemPrompt?.contains("JSON") == true) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            val systemMessageObj = JSONObject()
            systemMessageObj.put("role", "system")
            systemMessageObj.put("content", systemPrompt)
            messagesArray.put(systemMessageObj)
        }
        val userMessageObj = JSONObject()
        userMessageObj.put("role", "user")
        userMessageObj.put("content", prompt)
        messagesArray.put(userMessageObj)
        payload.put("messages", messagesArray)

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            val usageJson = responseJson.optJSONObject("usage")
            val usage = LlmUsage(
                inputTokens = usageJson?.optInt("prompt_tokens", 0) ?: 0,
                outputTokens = usageJson?.optInt("completion_tokens", 0) ?: 0,
                cachedTokens = 0,
                modelUsed = model
            )
            val choicesArray = responseJson.optJSONArray("choices")
            if (choicesArray != null && choicesArray.length() > 0) {
                val messageObj = choicesArray.getJSONObject(0).optJSONObject("message")
                val content = messageObj?.getString("content") ?: throw Exception("Empty content in response")
                return@withContext content to usage
            }
            throw ApiError.unexpectedResponse("OpenAI")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("OpenAI", responseCode, errorText)
        }
    }

    /** Transcribe a WAV audio clip to text using gpt-4o-mini-transcribe. */
    suspend fun transcribeAudio(apiKey: String, wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("OpenAI API key is empty.")
        }
        val boundary = "----MedVoiceTrainerBoundary${System.currentTimeMillis()}"
        val conn = URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true

        conn.outputStream.use { out ->
            fun writeField(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write("$value\r\n".toByteArray())
            }
            writeField("model", "gpt-4o-mini-transcribe")
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"dictation.wav\"\r\n".toByteArray())
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wavBytes)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val transcript = JSONObject(responseText).optString("text", "").trim()
            val seconds = wavDurationSeconds(wavBytes)
            val inputTokens = (seconds * 10.0).toInt().coerceAtLeast(0)
            val outputTokens = (transcript.length / 4).coerceAtLeast(0)
            com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                provider = "openai",
                model = "gpt-4o-mini-transcribe",
                operation = "transcription",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = (inputTokens * 1.25 + outputTokens * 5.00) / 1_000_000.0,
                // This endpoint does not return usage metadata in its JSON response.
                estimated = true,
            )
            return@withContext transcript
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("OpenAI", responseCode, errorText)
        }
    }

    /** Synthesize speech via tts-1, returning raw PCM16 mono bytes at 24kHz. */
    suspend fun synthesizeSpeech(apiKey: String, text: String, voice: String = "onyx"): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("OpenAI API key is empty.")
        }
        val conn = URL("https://api.openai.com/v1/audio/speech").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val payload = JSONObject()
        payload.put("model", "tts-1")
        payload.put("voice", voice)
        payload.put("input", text)
        payload.put("response_format", "pcm")

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val audio = conn.inputStream.use { it.readBytes() }
            com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                provider = "openai",
                model = "tts-1",
                operation = "tts",
                inputTokens = 0,
                outputTokens = 0,
                costUsd = text.length * 15.00 / 1_000_000.0,
                // tts-1 is billed directly per character; the request text is the exact metric.
                estimated = false,
            )
            return@withContext audio
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
            throw ApiError.fromHttpResponse("OpenAI", responseCode, errorText)
        }
    }

    private fun wavDurationSeconds(bytes: ByteArray): Double {
        if (bytes.size < 44) return 0.0
        fun littleEndianInt(offset: Int): Int {
            if (offset + 3 >= bytes.size) return 0
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }
        val byteRate = littleEndianInt(28)
        val dataBytes = littleEndianInt(40)
        return if (byteRate > 0 && dataBytes > 0) dataBytes.toDouble() / byteRate else 0.0
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
}
