package com.example.medvoicetrainer.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ProviderStatus {
    NOT_SET,
    VERIFYING,
    VERIFIED,
    INVALID_KEY,
    ERROR
}

data class ProviderVerificationResult(
    val status: ProviderStatus,
    val message: String = "",
    val availableModels: List<String> = emptyList()
)

object ModelsFetcher {

    /**
     * Verify API key and fetch available model IDs with zero token cost using GET models API.
     */
    suspend fun verifyAndFetchModels(provider: String, apiKey: String): ProviderVerificationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext ProviderVerificationResult(ProviderStatus.NOT_SET)
        }

        try {
            when (provider.trim().lowercase()) {
                "gemini" -> fetchGeminiModels(cleanKey)
                "openai" -> fetchOpenAiModels(cleanKey)
                "claude" -> fetchClaudeModels(cleanKey)
                else -> ProviderVerificationResult(ProviderStatus.ERROR, "Unknown provider: $provider")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val displayName = provider.trim().replaceFirstChar { it.uppercase() }.ifBlank { "AI provider" }
            val failure = ApiError.fromProviderMessage(displayName, e.message.orEmpty())
            if (failure.kind == ApiFailureKind.AUTHENTICATION) {
                ProviderVerificationResult(ProviderStatus.INVALID_KEY, failure.message.orEmpty())
            } else {
                ProviderVerificationResult(ProviderStatus.ERROR, failure.message.orEmpty())
            }
        }
    }

    private fun fetchGeminiModels(apiKey: String): ProviderVerificationResult {
        // Keep credentials out of the URL (and therefore out of proxy/history logs). Google's
        // current Gemini API guidance uses x-goog-api-key for authentication.
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val modelsArray = json.optJSONArray("models") ?: JSONArray()
            val modelsList = mutableListOf<String>()
            for (i in 0 until modelsArray.length()) {
                val item = modelsArray.optJSONObject(i) ?: continue
                val rawName = item.optString("name", "")
                val cleanName = rawName.removePrefix("models/")
                if (cleanName.isNotEmpty()) {
                    modelsList.add(cleanName)
                }
            }
            return ProviderVerificationResult(
                status = ProviderStatus.VERIFIED,
                message = "Verified (${modelsList.size} models found)",
                availableModels = modelsList.sorted()
            )
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            return verificationFailure("Gemini", code, err)
        }
    }

    private fun fetchOpenAiModels(apiKey: String): ProviderVerificationResult {
        val url = URL("https://api.openai.com/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            val modelsList = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isNotEmpty()) {
                    modelsList.add(id)
                }
            }
            return ProviderVerificationResult(
                status = ProviderStatus.VERIFIED,
                message = "Verified (${modelsList.size} models found)",
                availableModels = modelsList.sorted()
            )
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            return verificationFailure("OpenAI", code, err)
        }
    }

    private fun fetchClaudeModels(apiKey: String): ProviderVerificationResult {
        val url = URL("https://api.anthropic.com/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            val modelsList = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isNotEmpty()) {
                    modelsList.add(id)
                }
            }
            return ProviderVerificationResult(
                status = ProviderStatus.VERIFIED,
                message = "Verified (${modelsList.size} models found)",
                availableModels = modelsList.sorted()
            )
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            return verificationFailure("Claude", code, err)
        }
    }

    private fun verificationFailure(provider: String, code: Int, rawError: String): ProviderVerificationResult {
        val failure = ApiError.fromHttpResponse(provider, code, rawError)
        val status = if (failure.kind == ApiFailureKind.AUTHENTICATION) {
            ProviderStatus.INVALID_KEY
        } else {
            ProviderStatus.ERROR
        }
        return ProviderVerificationResult(status, failure.message.orEmpty())
    }

    /**
     * Filter all fetched models down to candidates suitable for post-session text/multimodal analysis.
     * Excludes realtime, tts, whisper, dall-e, embedding, etc.
     */
    fun filterAnalysisModels(provider: String, allModels: List<String>): List<String> {
        return when (provider.trim().lowercase()) {
            "gemini" -> {
                val filtered = allModels.filter { m ->
                    val lower = m.lowercase()
                    !lower.contains("embedding") &&
                    !lower.contains("aqa") &&
                    !lower.contains("imagen") &&
                    !lower.contains("veo") &&
                    !lower.contains("realtime") &&
                    !lower.contains("live") &&
                    !lower.contains("tts") &&
                    !lower.contains("audio") &&
                    !lower.contains("image") &&
                    !lower.contains("transcribe") &&
                    !lower.contains("omni") &&
                    !lower.startsWith("gemini-2.0-") &&
                    (lower.contains("gemini") || lower.contains("gemma"))
                }
                val presets = listOf(
                    "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-pro-preview",
                    "gemini-2.5-pro", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite"
                )
                (presets + filtered).distinct()
            }
            "openai" -> {
                val filtered = allModels.filter { m ->
                    val lower = m.lowercase()
                    !lower.contains("realtime") &&
                    !lower.contains("audio") &&
                    !lower.contains("whisper") &&
                    !lower.contains("dall-e") &&
                    !lower.contains("embedding") &&
                    !lower.contains("tts") &&
                    !lower.contains("babbage") &&
                    !lower.contains("davinci") &&
                    (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("chatgpt-"))
                }
                val presets = listOf("gpt-5.1", "gpt-5.6-sol", "gpt-5.6-luna")
                (presets + filtered).distinct()
            }
            "claude" -> {
                val filtered = allModels.filter { m ->
                    val lower = m.lowercase()
                    lower.contains("claude")
                }
                val presets = listOf("claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5")
                (presets + filtered).distinct()
            }
            else -> emptyList()
        }
    }

    /**
     * Filter all fetched models down to candidates suitable for bidirectional / realtime voice sessions.
     * Excludes purely text/chat models like sonnet or text-only flash/pro.
     */
    fun filterLiveVoiceModels(provider: String, allModels: List<String>): List<String> {
        return when (provider.trim().lowercase()) {
            "gemini" -> {
                val filtered = allModels.filter { m ->
                    val lower = m.lowercase()
                    (lower.contains("live") || lower.contains("native-audio") || lower.contains("bidi") || lower.contains("realtime")) &&
                        !lower.contains("translate") && !lower.contains("transcribe") && !lower.contains("tts") &&
                        !lower.startsWith("gemini-2.0-")
                }
                val presets = listOf("gemini-3.8-live", "gemini-3.8-live-extended-thinking", "gemini-3.1-flash-live-preview")
                (presets + filtered).distinct()
            }
            "openai" -> {
                val filtered = allModels.filter { m ->
                    val lower = m.lowercase()
                    lower.contains("realtime") || lower.contains("voice")
                }
                val presets = listOf("gpt-realtime-2.1", "gpt-4o-realtime-preview")
                (presets + filtered).distinct()
            }
            else -> emptyList()
        }
    }
}
