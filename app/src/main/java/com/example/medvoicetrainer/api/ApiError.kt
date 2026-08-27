package com.example.medvoicetrainer.api

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A safe, user-facing summary of a provider failure.
 *
 * API providers commonly put an entire JSON response in their error body. Keeping that response
 * out of [message] makes it impossible for a UI caller to accidentally render it in a chat,
 * session status, or toast.
 */
enum class ApiFailureKind {
    AUTHENTICATION,
    QUOTA,
    MODEL_UNAVAILABLE,
    INVALID_REQUEST,
    TEMPORARY_SERVICE,
    NETWORK,
    UNKNOWN,
}

class ApiRequestException(
    val provider: String,
    val statusCode: Int?,
    val kind: ApiFailureKind,
    /**
     * True when this failure already represents an *exhausted* provider-side fallback chain — a
     * call that internally tried every model it had (see GeminiService.evaluateSessionWithUsage
     * walking GEMINI_FREE_TIER_MODEL_CHAIN, each model attempted twice). An outer retry layer must
     * not re-run such a call: it doubles an already minute-long attempt sequence without adding a
     * single new chance of success.
     */
    val exhaustedFallbackChain: Boolean = false,
) : Exception(ApiError.messageFor(provider, kind))

object ApiError {
    fun fromHttpResponse(provider: String, statusCode: Int, responseBody: String): ApiRequestException =
        ApiRequestException(provider, statusCode, classify(statusCode, responseBody))

    /** For WebSocket events, which carry a provider error object rather than an HTTP response. */
    fun fromProviderMessage(provider: String, rawMessage: String): ApiRequestException =
        ApiRequestException(provider, null, classify(null, rawMessage))

    fun allModelsUnavailable(provider: String = "Gemini"): ApiRequestException =
        ApiRequestException(provider, 429, ApiFailureKind.QUOTA, exhaustedFallbackChain = true)

    fun unexpectedResponse(provider: String): ApiRequestException =
        ApiRequestException(provider, null, ApiFailureKind.UNKNOWN)

    /** Safe to show directly in UI. Never returns an API response body or raw JSON. */
    fun userMessage(throwable: Throwable, fallback: String = "The AI request couldn't be completed. Please try again."): String =
        when (throwable) {
            is ApiRequestException -> throwable.message ?: fallback
            else -> when {
                throwable is UnknownHostException || throwable is SocketTimeoutException || throwable is IOException ->
                    messageFor("The AI service", ApiFailureKind.NETWORK)
                looksLikeApiFailure(throwable.message.orEmpty()) ->
                    messageFor(inferProvider(throwable.message.orEmpty()), classify(null, throwable.message.orEmpty()))
                else -> fallback
            }
        }

    fun isQuota(throwable: Throwable): Boolean =
        throwable is ApiRequestException && throwable.kind == ApiFailureKind.QUOTA

    fun isTemporary(throwable: Throwable): Boolean = when (throwable) {
        is ApiRequestException -> throwable.kind in setOf(ApiFailureKind.QUOTA, ApiFailureKind.TEMPORARY_SERVICE, ApiFailureKind.NETWORK)
        else -> throwable is IOException || (looksLikeApiFailure(throwable.message.orEmpty()) &&
            classify(null, throwable.message.orEmpty()) in setOf(ApiFailureKind.QUOTA, ApiFailureKind.TEMPORARY_SERVICE, ApiFailureKind.NETWORK))
    }

    internal fun messageFor(provider: String, kind: ApiFailureKind): String = when (kind) {
        ApiFailureKind.AUTHENTICATION -> "$provider could not verify the API key. Check it in Preferences."
        ApiFailureKind.QUOTA -> "$provider API usage limit has been reached. Try again later, use another model/provider, or check your API plan."
        ApiFailureKind.MODEL_UNAVAILABLE -> "$provider model is unavailable. Choose another model in Preferences and try again."
        ApiFailureKind.INVALID_REQUEST -> "$provider rejected this request. Check the selected model and try again."
        ApiFailureKind.TEMPORARY_SERVICE -> "$provider is temporarily unavailable. Please try again shortly."
        ApiFailureKind.NETWORK -> "Couldn't reach $provider. Check your internet connection and try again."
        ApiFailureKind.UNKNOWN -> "$provider returned an unexpected error. Please try again."
    }

    private fun classify(statusCode: Int?, raw: String): ApiFailureKind {
        val text = raw.lowercase()
        return when {
            statusCode == 401 || statusCode == 403 || listOf("unauthenticated", "unauthorized", "invalid_api_key", "api key not valid", "authentication").any(text::contains) -> ApiFailureKind.AUTHENTICATION
            statusCode == 429 || listOf("resource_exhausted", "resourceexhausted", "insufficient_quota", "rate limit", "rate_limit", "too many requests", "quota").any(text::contains) -> ApiFailureKind.QUOTA
            statusCode == 404 || listOf("model_not_found", "model not found", "not_found", "no longer available").any(text::contains) -> ApiFailureKind.MODEL_UNAVAILABLE
            statusCode == 400 || listOf("invalid_request", "invalid argument", "bad request").any(text::contains) -> ApiFailureKind.INVALID_REQUEST
            statusCode != null && statusCode >= 500 || listOf("unavailable", "overloaded", "high demand", "internal error", "service unavailable").any(text::contains) -> ApiFailureKind.TEMPORARY_SERVICE
            listOf("timeout", "timed out", "connection reset", "connection refused", "unknownhost").any(text::contains) -> ApiFailureKind.NETWORK
            else -> ApiFailureKind.UNKNOWN
        }
    }

    private fun looksLikeApiFailure(text: String): Boolean {
        val lower = text.lowercase()
        return lower.trimStart().startsWith("{") || lower.contains("http 4") || lower.contains("http 5") ||
            listOf("resource_exhausted", "quota", "rate limit", "invalid_api_key", "unauthenticated", "overloaded").any(lower::contains)
    }

    private fun inferProvider(text: String): String = when {
        text.contains("openai", ignoreCase = true) -> "OpenAI"
        text.contains("claude", ignoreCase = true) || text.contains("anthropic", ignoreCase = true) -> "Claude"
        text.contains("azure", ignoreCase = true) -> "Azure Speech"
        else -> "The AI service"
    }
}
