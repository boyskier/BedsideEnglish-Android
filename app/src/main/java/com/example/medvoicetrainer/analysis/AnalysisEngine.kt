package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.api.ClaudeService
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.api.LlmUsage
import com.example.medvoicetrainer.api.OpenAIService
import com.example.medvoicetrainer.api.ApiError
import com.example.medvoicetrainer.api.ApiRequestException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object AnalysisEngine {

    fun sanitizeInstruction(text: String?): String? {
        if (text == null) return null
        return text.replace("@@SYSTEM_OVERRIDE@@", "").trim()
    }

    // Providers surface transient overload as an error message rather than a typed exception
    // (e.g. Gemini's "HTTP 503: {... \"status\": \"UNAVAILABLE\" ...}"), so this matches on
    // message text. None of the three services otherwise retry a plain transient failure —
    // GeminiService's own model-fallback loop only treats 404/429/quota as fallback-worthy — so
    // without this, a single "high demand" blip surfaces straight to the user instead of clearing
    // itself on a short retry.
    private val TRANSIENT_ERROR_MARKERS = listOf(
        "503", "unavailable", "overloaded", "high demand", "429", "too many requests",
        "resource_exhausted", "timed out", "timeout", "connection reset", "connection refused"
    )
    private const val MAX_TRANSIENT_RETRIES = 1
    private const val RETRY_BACKOFF_MS = 1500L
    private const val SESSION_ANALYSIS_TIMEOUT_MS = 120_000L

    internal fun isTransientError(e: Exception): Boolean {
        // Some provider calls already walk their own fallback chain internally — Gemini's session
        // analysis tries every model in GEMINI_FREE_TIER_MODEL_CHAIN, each one twice, before it
        // gives up. That failure is transient by kind (quota), so retrying it here used to re-run
        // the whole chain: up to 14 requests, then 14 more, inside a 120s budget that it can only
        // exhaust. Honour the provider's own "I am out of options" signal instead.
        if ((e as? ApiRequestException)?.exhaustedFallbackChain == true) return false
        return ApiError.isTemporary(e) || TRANSIENT_ERROR_MARKERS.any { it in e.message?.lowercase().orEmpty() }
    }

    private suspend fun <T> withTransientRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (attempt >= MAX_TRANSIENT_RETRIES || !isTransientError(e)) throw e
                attempt++
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    suspend fun generateContent(
        backend: String,
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null
    ): String {
        val cleanPrompt = sanitizeInstruction(prompt) ?: ""
        val cleanSys = sanitizeInstruction(systemInstruction)
        return withTransientRetry {
            when (backend) {
                "claude" -> ClaudeService.generateContent(apiKey, model, cleanPrompt, cleanSys)
                "openai" -> OpenAIService.generateContent(apiKey, model, cleanPrompt, cleanSys)
                "gemini" -> GeminiService.generateContent(apiKey, model, cleanPrompt, cleanSys)
                else -> GeminiService.generateContent(apiKey, model, cleanPrompt, cleanSys)
            }
        }
    }

    suspend fun evaluateSession(
        backend: String,
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): String {
        val cleanTranscript = sanitizeInstruction(transcript) ?: ""
        val cleanRubric = sanitizeInstruction(rubricContext) ?: ""
        return withTransientRetry {
            when (backend) {
                "claude" -> ClaudeService.evaluateSession(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                "openai" -> OpenAIService.evaluateSession(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                "gemini" -> GeminiService.evaluateSession(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                else -> GeminiService.evaluateSession(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
            }
        }
    }

    /**
     * Same as [evaluateSession] but also returns normalized token usage, matching
     * app/analysis/analysis_providers.py's `call_analysis(...) -> (raw_text, usage)` contract.
     * Lets CostTracker.kt price a session from exact provider-reported counts instead of the
     * char-count estimate fallback.
     *
     * [analysisDomain] ("clinical" or "everyday") branches the requested JSON schema/system
     * prompt so survival/lounge sessions are scored on the everyday rubric instead of the
     * clinical one — see EvalPromptBuilder.kt and ScoreDomains.kt.
     */
    suspend fun evaluateSessionWithUsage(
        backend: String,
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): Pair<String, LlmUsage> {
        val cleanTranscript = sanitizeInstruction(transcript) ?: ""
        val cleanRubric = sanitizeInstruction(rubricContext) ?: ""
        return withTimeoutOrNull(SESSION_ANALYSIS_TIMEOUT_MS) {
            withTransientRetry {
                when (backend) {
                    "claude" -> ClaudeService.evaluateSessionWithUsage(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                    "openai" -> OpenAIService.evaluateSessionWithUsage(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                    "gemini" -> GeminiService.evaluateSessionWithUsage(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                    else -> GeminiService.evaluateSessionWithUsage(apiKey, model, cleanTranscript, caseJson, nativeLanguage, analysisDomain, cleanRubric)
                }
            }
        } ?: throw Exception("Analysis timed out after two minutes. Your transcript is saved; please retry from History.")
    }
}
