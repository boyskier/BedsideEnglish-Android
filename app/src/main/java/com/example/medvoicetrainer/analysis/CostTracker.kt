package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.voice.VoiceApiUsage
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.round

/**
 * Ported from app/analysis/cost_tracker.py — token counting and API cost tracking for a single
 * session (analysis backend + voice backend). Pricing constants current as of the Python source's
 * 2025-Q4/2026-Q2 comment; update alongside the Python file if rates change.
 */
object CostTracker {

    // Anthropic Claude pricing (per 1M tokens, USD) — claude-sonnet-4-6 (the analysis model).
    const val CLAUDE_INPUT_PRICE_PER_1M = 3.00
    const val CLAUDE_OUTPUT_PRICE_PER_1M = 15.00

    // Non-Claude analysis (text) pricing (per 1M tokens, USD), approximate.
    const val GEMINI_ANALYSIS_INPUT_PRICE_PER_1M = 0.50
    const val GEMINI_ANALYSIS_OUTPUT_PRICE_PER_1M = 3.00
    const val OPENAI_ANALYSIS_INPUT_PRICE_PER_1M = 1.25
    const val OPENAI_ANALYSIS_OUTPUT_PRICE_PER_1M = 10.00

    val ANALYSIS_PROVIDER_LABELS = mapOf(
        "claude" to "Claude API",
        "gemini" to "Gemini API",
        "openai" to "OpenAI API",
        "demo" to "Demo Mode (no API)"
    )
    val ANALYSIS_PROVIDER_MODELS: Map<String, String> = object : HashMap<String, String>(
        mapOf(
            "claude" to "claude-sonnet-4-6",
            "gemini" to "gemini-3.5-flash",
            "openai" to "gpt-5.1"
        )
    ) {
        override fun get(key: String): String {
            return super.get(key) ?: "claude-sonnet-4-6"
        }
    }

    // Gemini Live pricing (per 1M tokens, USD) for the default Live model. Kept in sync with the
    // matching VOICE_MODEL_PRICING entry, which is what the exact-usage path actually prices with.
    const val GEMINI_TEXT_INPUT_PRICE_PER_1M = 0.75
    const val GEMINI_AUDIO_INPUT_PRICE_PER_1M = 3.00
    const val GEMINI_TEXT_OUTPUT_PRICE_PER_1M = 4.50
    const val GEMINI_AUDIO_OUTPUT_PRICE_PER_1M = 12.00

    /**
     * Audio tokens per second of 16 kHz PCM.
     *
     * Google documents 32 tokens/second for the Gemini 3.x audio stack, while the per-minute
     * equivalents it publishes alongside the Live rates ($0.005/min in at $3/1M ≈ 27.8 tok/s,
     * $0.018/min out at $12/1M = 25 tok/s) imply something closer to 25. The 2.x Live/native-audio
     * generation is documented at 25. We therefore key the rate off the model family rather than
     * using one constant for everything, and callers that need certainty should reconcile against
     * the provider's own usage page — [estimateGeminiLiveCost] is only used when the provider did
     * not report usage metadata at all.
     */
    const val GEMINI_3X_AUDIO_SECONDS_TO_TOKENS = 32
    const val GEMINI_2X_AUDIO_SECONDS_TO_TOKENS = 25

    /** Audio tokens per second for a Live model id, defaulting to the current 3.x generation. */
    fun geminiAudioTokensPerSecond(model: String): Int {
        val clean = model.trim().removePrefix("models/").lowercase()
        return when {
            clean.isEmpty() -> GEMINI_3X_AUDIO_SECONDS_TO_TOKENS
            Regex("gemini-(1\\.|2\\.|2-|live-2)").containsMatchIn(clean) ->
                GEMINI_2X_AUDIO_SECONDS_TO_TOKENS
            else -> GEMINI_3X_AUDIO_SECONDS_TO_TOKENS
        }
    }

    // OpenAI Realtime pricing (per 1M tokens, USD) — gpt-4o-realtime-preview.
    const val OPENAI_AUDIO_INPUT_PRICE_PER_1M = 32.00
    const val OPENAI_AUDIO_OUTPUT_PRICE_PER_1M = 64.00
    const val OPENAI_TEXT_INPUT_PRICE_PER_1M = 4.00
    const val OPENAI_TEXT_OUTPUT_PRICE_PER_1M = 24.00
    const val OPENAI_AUDIO_SECONDS_TO_TOKENS = 32
    const val SPEECH_CHARS_PER_SECOND = 15

    private fun estimateOpenaiOutputAudioTokens(outputChars: Int): Int {
        if (outputChars <= 0) return 0
        val estOutputSeconds = outputChars.toDouble() / SPEECH_CHARS_PER_SECOND
        return max((estOutputSeconds * OPENAI_AUDIO_SECONDS_TO_TOKENS).toInt(), 1)
    }

    class SessionCostReport(
        val sessionId: Int? = null,
        val createdAt: String = "",
        val mode: String = "",
        val caseName: String = "",
        val voiceBackend: String = "",
        val durationSeconds: Int = 0,
        val analysisProvider: String = "claude",
        val analysisModel: String = "",
        var claudeInputTokens: Int = 0,
        var claudeOutputTokens: Int = 0,
        var claudeCachedInputTokens: Int = 0,
        var voiceAudioSeconds: Int = 0,
        var voiceTranscriptChars: Int = 0,
        var voiceEstimatedInputTokens: Int = 0,
        var voiceEstimatedOutputTokens: Int = 0,
        var claudeCostUsd: Double = 0.0,
        var voiceCostUsd: Double = 0.0,
        var totalCostUsd: Double = 0.0,
        val notes: MutableList<String> = mutableListOf()
    ) {
        fun copy(
            sessionId: Int? = this.sessionId,
            createdAt: String = this.createdAt,
            mode: String = this.mode,
            caseName: String = this.caseName,
            voiceBackend: String = this.voiceBackend,
            durationSeconds: Int = this.durationSeconds,
            analysisProvider: String = this.analysisProvider,
            analysisModel: String = this.analysisModel,
            claudeInputTokens: Int = this.claudeInputTokens,
            claudeOutputTokens: Int = this.claudeOutputTokens,
            claudeCachedInputTokens: Int = this.claudeCachedInputTokens,
            voiceAudioSeconds: Int = this.voiceAudioSeconds,
            voiceTranscriptChars: Int = this.voiceTranscriptChars,
            voiceEstimatedInputTokens: Int = this.voiceEstimatedInputTokens,
            voiceEstimatedOutputTokens: Int = this.voiceEstimatedOutputTokens,
            claudeCostUsd: Double = this.claudeCostUsd,
            voiceCostUsd: Double = this.voiceCostUsd,
            totalCostUsd: Double = this.totalCostUsd,
            notes: MutableList<String> = ArrayList(this.notes)
        ): SessionCostReport {
            val copy = SessionCostReport(
                sessionId, createdAt, mode, caseName, voiceBackend, durationSeconds,
                analysisProvider, analysisModel, claudeInputTokens, claudeOutputTokens,
                claudeCachedInputTokens, voiceAudioSeconds, voiceTranscriptChars,
                voiceEstimatedInputTokens, voiceEstimatedOutputTokens, claudeCostUsd,
                voiceCostUsd, totalCostUsd, ArrayList(notes)
            )
            return copy
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SessionCostReport) return false
            return sessionId == other.sessionId &&
                createdAt == other.createdAt &&
                mode == other.mode &&
                caseName == other.caseName &&
                voiceBackend == other.voiceBackend &&
                durationSeconds == other.durationSeconds &&
                analysisProvider == other.analysisProvider &&
                analysisModel == other.analysisModel &&
                claudeInputTokens == other.claudeInputTokens &&
                claudeOutputTokens == other.claudeOutputTokens &&
                claudeCachedInputTokens == other.claudeCachedInputTokens &&
                voiceAudioSeconds == other.voiceAudioSeconds &&
                voiceTranscriptChars == other.voiceTranscriptChars &&
                voiceEstimatedInputTokens == other.voiceEstimatedInputTokens &&
                voiceEstimatedOutputTokens == other.voiceEstimatedOutputTokens &&
                claudeCostUsd == other.claudeCostUsd &&
                voiceCostUsd == other.voiceCostUsd &&
                totalCostUsd == other.totalCostUsd &&
                notes == other.notes
        }

        override fun hashCode(): Int {
            var result = sessionId?.hashCode() ?: 0
            result = 31 * result + createdAt.hashCode()
            result = 31 * result + mode.hashCode()
            result = 31 * result + caseName.hashCode()
            result = 31 * result + voiceBackend.hashCode()
            result = 31 * result + durationSeconds
            result = 31 * result + analysisProvider.hashCode()
            result = 31 * result + analysisModel.hashCode()
            result = 31 * result + claudeInputTokens
            result = 31 * result + claudeOutputTokens
            result = 31 * result + claudeCachedInputTokens
            result = 31 * result + voiceAudioSeconds
            result = 31 * result + voiceTranscriptChars
            result = 31 * result + voiceEstimatedInputTokens
            result = 31 * result + voiceEstimatedOutputTokens
            result = 31 * result + claudeCostUsd.hashCode()
            result = 31 * result + voiceCostUsd.hashCode()
            result = 31 * result + totalCostUsd.hashCode()
            result = 31 * result + notes.hashCode()
            return result
        }

        override fun toString(): String {
            return "SessionCostReport(sessionId=$sessionId, createdAt='$createdAt', mode='$mode', caseName='$caseName', voiceBackend='$voiceBackend', durationSeconds=$durationSeconds, analysisProvider='$analysisProvider', analysisModel='$analysisModel', claudeInputTokens=$claudeInputTokens, claudeOutputTokens=$claudeOutputTokens, claudeCachedInputTokens=$claudeCachedInputTokens, voiceAudioSeconds=$voiceAudioSeconds, voiceTranscriptChars=$voiceTranscriptChars, voiceEstimatedInputTokens=$voiceEstimatedInputTokens, voiceEstimatedOutputTokens=$voiceEstimatedOutputTokens, claudeCostUsd=$claudeCostUsd, voiceCostUsd=$voiceCostUsd, totalCostUsd=$totalCostUsd, notes=$notes)"
        }
    }

    data class LlmModelPricing(
        val provider: String,
        val displayName: String,
        val inputPricePer1M: Double,
        val outputPricePer1M: Double,
        val cachePricePer1M: Double? = null,
        val aliases: List<String> = emptyList()
    )

    data class VoiceModelPricing(
        val provider: String,
        val aliases: List<String>,
        val textInputPricePer1M: Double,
        val audioInputPricePer1M: Double,
        val textOutputPricePer1M: Double,
        val audioOutputPricePer1M: Double,
        val cachedTextInputPricePer1M: Double = 0.0,
        val cachedAudioInputPricePer1M: Double = 0.0,
    )

    /**
     * Standard paid-tier list prices, verified against provider pricing on 2026-07-24.
     *
     * Cached input rates are 10% of the matching uncached rate for every Gemini 2.5-and-newer
     * model, which is what Live's implicit caching bills a context hit at. Leaving them at the
     * 0.0 default (as this table originally did for both Gemini rows) makes every cache hit look
     * free, and Live re-sends the whole conversation context every turn — so on a long session the
     * bucket that gets zeroed out is the largest one.
     */
    val VOICE_MODEL_PRICING = listOf(
        VoiceModelPricing(
            "gemini", listOf("gemini-3.1-flash-live-preview", "gemini-3.1-flash-live"),
            0.75, 3.00, 4.50, 12.00, 0.075, 0.30,
        ),
        VoiceModelPricing(
            "gemini",
            listOf(
                "gemini-2.5-flash-native-audio-preview-12-2025",
                "gemini-2.5-flash-native-audio",
                "gemini-2.5-flash-live",
                "gemini-live-2.5-flash",
            ),
            0.50, 3.00, 2.00, 12.00, 0.05, 0.30,
        ),
        VoiceModelPricing(
            "openai", listOf("gpt-realtime-2.1", "gpt-realtime-2"),
            4.00, 32.00, 24.00, 64.00, 0.40, 0.40,
        ),
        VoiceModelPricing(
            "openai", listOf("gpt-4o-realtime-preview"),
            5.00, 40.00, 20.00, 80.00, 2.50, 2.50,
        ),
    )

    /** Charges nothing, for backends that genuinely cost nothing (mock) or are unrecognized. */
    private val FREE_VOICE_PRICING =
        VoiceModelPricing("none", emptyList(), 0.0, 0.0, 0.0, 0.0)

    /**
     * Resolve list pricing for a realtime model.
     *
     * A model id the table does not know still has to price *something*, and picking whichever row
     * happens to sit first in the list silently charged 3.x rates for a 2.x session (text output
     * 4.50 vs 2.00). Unknown ids are therefore resolved by model family first, and only then by
     * the provider's newest entry. An unknown *provider* returns free pricing rather than throwing
     * — [VOICE_MODEL_PRICING].first would have raised NoSuchElementException on e.g. "mock".
     */
    fun lookupVoiceModelPricing(provider: String, model: String): VoiceModelPricing {
        val clean = model.trim().removePrefix("models/").lowercase()
        val forProvider = VOICE_MODEL_PRICING.filter { it.provider.equals(provider, ignoreCase = true) }
        if (forProvider.isEmpty()) return FREE_VOICE_PRICING
        forProvider.firstOrNull { pricing ->
            pricing.aliases.any { alias -> clean == alias || clean.startsWith("$alias-") }
        }?.let { return it }
        // Family heuristic for ids the alias list has not caught up with yet.
        if (clean.isNotEmpty()) {
            val cleanFamily = voiceModelFamilyKey(clean)
            forProvider.firstOrNull { pricing ->
                pricing.aliases.any { alias -> voiceModelFamilyKey(alias) == cleanFamily }
            }?.let { return it }
        }
        return forProvider.first()
    }

    /** "gemini-2.5-flash-native-audio-preview-12-2025" -> "gemini-2.5"; used for fuzzy matching. */
    private fun voiceModelFamilyKey(model: String): String =
        Regex("^([a-z]+)[-.]?(\\d+(?:\\.\\d+)?)").find(model)
            ?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
            ?: model

    /** List-price cost from provider-reported realtime token modalities. */
    fun computeVoiceCost(usage: VoiceApiUsage): Double {
        val pricing = lookupVoiceModelPricing(usage.provider, usage.model)
        val cachedText = minOf(
            usage.cachedInputTextTokens.coerceAtLeast(0),
            usage.inputTextTokens.coerceAtLeast(0),
        )
        val cachedAudio = minOf(
            usage.cachedInputAudioTokens.coerceAtLeast(0),
            usage.inputAudioTokens.coerceAtLeast(0),
        )
        val costPerMillion =
            (usage.inputTextTokens - cachedText).coerceAtLeast(0) * pricing.textInputPricePer1M +
                (usage.inputAudioTokens - cachedAudio).coerceAtLeast(0) * pricing.audioInputPricePer1M +
                cachedText * pricing.cachedTextInputPricePer1M +
                cachedAudio * pricing.cachedAudioInputPricePer1M +
                (usage.outputTextTokens + usage.thinkingTokens) * pricing.textOutputPricePer1M +
                usage.outputAudioTokens * pricing.audioOutputPricePer1M
        return round6(costPerMillion / 1_000_000)
    }

    val ALL_MODEL_PRICING = listOf(
        // --- Anthropic Claude (2026 & Legacy) ---
        LlmModelPricing("claude", "Claude Sonnet 5", 3.00, 15.00, 0.30, listOf("claude-sonnet-5", "claude-5-sonnet", "claude-sonnet-5-20260620")),
        LlmModelPricing("claude", "Claude Opus 4.8", 5.00, 25.00, 0.50, listOf("claude-opus-4-8", "claude-4-8-opus")),
        LlmModelPricing("claude", "Claude Fable 5", 10.00, 50.00, 1.00, listOf("claude-fable-5")),
        LlmModelPricing("claude", "Claude Haiku 4.5", 1.00, 5.00, 0.10, listOf("claude-haiku-4-5")),
        LlmModelPricing("claude", "Claude 3.7 Sonnet", 3.00, 15.00, 0.30, listOf("claude-3-7-sonnet-20250219", "claude-3-7-sonnet-latest")),
        LlmModelPricing("claude", "Claude 3.5 Sonnet", 3.00, 15.00, 0.30, listOf("claude-3-5-sonnet-20241022", "claude-3-5-sonnet-latest", "claude-sonnet-4-6")),
        LlmModelPricing("claude", "Claude 3.5 Haiku", 0.80, 4.00, 0.08, listOf("claude-3-5-haiku-20241022", "claude-3-5-haiku-latest")),
        LlmModelPricing("claude", "Claude 3 Opus", 15.00, 75.00, 1.50, listOf("claude-3-opus-20240229", "claude-3-opus-latest")),

        // --- Google Gemini (2026 & Legacy) ---
        LlmModelPricing("gemini", "Gemini 3.6 Flash", 1.50, 7.50, 0.15, listOf("gemini-3.6-flash")),
        LlmModelPricing("gemini", "Gemini 3.5 Flash", 1.50, 9.00, 0.15, listOf("gemini-3.5-flash")),
        LlmModelPricing("gemini", "Gemini 3.5 Pro", 2.50, 15.00, 0.25, listOf("gemini-3.5-pro")),
        LlmModelPricing("gemini", "Gemini 3.5 Flash-Lite", 0.30, 2.50, 0.03, listOf("gemini-3.5-flash-lite")),
        LlmModelPricing("gemini", "Gemini 3.1 Pro", 2.00, 12.00, 0.20, listOf("gemini-3.1-pro")),
        LlmModelPricing("gemini", "Gemini 3 Flash", 0.50, 3.00, 0.05, listOf("gemini-3-flash")),
        LlmModelPricing("gemini", "Gemini 3.1 Flash-Lite", 0.25, 1.50, 0.025, listOf("gemini-3.1-flash-lite")),
        LlmModelPricing("gemini", "Gemini 3.1 Flash TTS", 0.50, 10.00, 0.05, listOf("gemini-3.1-flash-tts-preview")),
        LlmModelPricing("gemini", "Gemini 2.5 Pro", 2.50, 15.00, 0.625, listOf("gemini-2.5-pro")),
        LlmModelPricing("gemini", "Gemini 2.5 Flash", 1.50, 9.00, 0.375, listOf("gemini-2.5-flash")),
        LlmModelPricing("gemini", "Gemini 2.5 Flash-Lite", 0.10, 0.40, 0.025, listOf("gemini-2.5-flash-lite")),
        LlmModelPricing("gemini", "Gemini 2.0 Flash", 0.10, 0.40, 0.025, listOf("gemini-2.0-flash", "gemini-2.0-flash-exp", "gemini-2.0-flash-001")),
        LlmModelPricing("gemini", "Gemini 2.0 Flash-Lite", 0.075, 0.30, 0.01875, listOf("gemini-2.0-flash-lite", "gemini-2.0-flash-lite-preview-02-05")),
        LlmModelPricing("gemini", "Gemini 1.5 Pro", 1.25, 5.00, 0.3125, listOf("gemini-1.5-pro", "gemini-1.5-pro-latest")),
        LlmModelPricing("gemini", "Gemini 1.5 Flash", 0.075, 0.30, 0.01875, listOf("gemini-1.5-flash", "gemini-1.5-flash-latest")),

        // --- OpenAI (2026 & Legacy) ---
        LlmModelPricing("openai", "GPT-5.6 Sol", 5.00, 30.00, 0.50, listOf("gpt-5.6-sol", "gpt-5.6")),
        LlmModelPricing("openai", "GPT-5.6 Terra", 2.50, 15.00, 0.25, listOf("gpt-5.6-terra")),
        LlmModelPricing("openai", "GPT-5.6 Luna", 1.00, 6.00, 0.10, listOf("gpt-5.6-luna")),
        LlmModelPricing("openai", "o3 Reasoning", 2.00, 8.00, 1.00, listOf("o3", "o3-2026")),
        LlmModelPricing("openai", "o3-pro", 20.00, 80.00, 10.00, listOf("o3-pro")),
        LlmModelPricing("openai", "GPT-5.1", 1.25, 10.00, 0.125, listOf("gpt-5.1", "gpt-5")),
        LlmModelPricing("openai", "GPT-4o", 2.50, 10.00, 1.25, listOf("gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-2024-11-20", "gpt-4o-latest")),
        LlmModelPricing("openai", "GPT-4o mini", 0.15, 0.60, 0.075, listOf("gpt-4o-mini", "gpt-4o-mini-2024-07-18")),
        LlmModelPricing("openai", "o1 Reasoning", 15.00, 60.00, 7.50, listOf("o1", "o1-2024-12-17", "o1-preview")),
        LlmModelPricing("openai", "o1-mini", 1.10, 4.40, 0.55, listOf("o1-mini", "o1-mini-2024-09-12")),
        LlmModelPricing("openai", "o3-mini", 1.10, 4.40, 0.55, listOf("o3-mini", "o3-mini-2025-01-31")),
        LlmModelPricing("openai", "GPT-4 Turbo", 10.00, 30.00, 5.00, listOf("gpt-4-turbo", "gpt-4-turbo-2024-04-09"))
    )

    fun lookupModelPricing(model: String): LlmModelPricing? {
        val cleanModel = model.trim().removePrefix("models/").lowercase()
        if (cleanModel.isEmpty()) return null
        // An exact alias match must win regardless of list order before falling back to the
        // prefix heuristic (for dated suffixes like "-20260620") — otherwise a bare model name
        // (e.g. "gemini-3.5-flash") and its own "-lite"/"-pro" sibling are ambiguous prefixes of
        // each other and whichever pricing entry happens to appear first in the list would
        // silently win for both, mispricing one of them.
        ALL_MODEL_PRICING.find { pricing -> pricing.aliases.any { it.lowercase() == cleanModel } }
            ?.let { return it }
        return ALL_MODEL_PRICING.find { pricing ->
            pricing.aliases.any { alias ->
                cleanModel.startsWith(alias.lowercase()) || alias.lowercase().startsWith(cleanModel)
            }
        }
    }

    /** Cached tokens billed at 10% of input price. */
    fun computeClaudeCost(inputTokens: Int, outputTokens: Int, cachedTokens: Int = 0): Double {
        val cleanInput = max(0, inputTokens)
        val cleanOutput = max(0, outputTokens)
        val cleanCached = minOf(max(0, cachedTokens), cleanInput)
        val nonCached = max(0, cleanInput - cleanCached)
        val cost = (nonCached * CLAUDE_INPUT_PRICE_PER_1M / 1_000_000
            + cleanCached * CLAUDE_INPUT_PRICE_PER_1M * 0.1 / 1_000_000
            + cleanOutput * CLAUDE_OUTPUT_PRICE_PER_1M / 1_000_000)
        return round6(cost)
    }

    fun computeAnalysisCost(provider: String, inputTokens: Int, outputTokens: Int, cachedTokens: Int = 0, model: String = ""): Double {
        val cleanInput = max(0, inputTokens)
        val cleanOutput = max(0, outputTokens)
        val cleanCached = minOf(max(0, cachedTokens), cleanInput)
        val lowerProvider = provider.lowercase()
        if (lowerProvider.contains("_local") || lowerProvider.contains("local_") || lowerProvider == "unsupported" || lowerProvider.contains("unsupported_")) {
            throw IllegalArgumentException("Unsupported analysis provider: $provider")
        }
        if (model.isNotBlank()) {
            val pricing = lookupModelPricing(model)
            if (pricing != null) {
                val nonCached = max(0, cleanInput - cleanCached)
                val cacheRate = pricing.cachePricePer1M ?: (pricing.inputPricePer1M * 0.1)
                val cost = (nonCached * pricing.inputPricePer1M / 1_000_000
                    + cleanCached * cacheRate / 1_000_000
                    + cleanOutput * pricing.outputPricePer1M / 1_000_000)
                return round6(cost)
            }
        }
        return when (lowerProvider) {
            "gemini" -> round6(
                (cleanInput * GEMINI_ANALYSIS_INPUT_PRICE_PER_1M + cleanOutput * GEMINI_ANALYSIS_OUTPUT_PRICE_PER_1M) / 1_000_000
            )
            "openai" -> round6(
                (cleanInput * OPENAI_ANALYSIS_INPUT_PRICE_PER_1M + cleanOutput * OPENAI_ANALYSIS_OUTPUT_PRICE_PER_1M) / 1_000_000
            )
            else -> computeClaudeCost(cleanInput, cleanOutput, cleanCached)
        }
    }

    /** One ordered conversation turn, as persisted in `SessionEntity.rawTranscript`. */
    data class VoiceTurn(val role: String, val text: String)

    /** Estimated billable usage for a voice session the provider never reported usage for. */
    data class VoiceCostEstimate(
        val costUsd: Double,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    private val AI_TURN_ROLES = setOf("patient", "interviewer")

    /** PromptBuilder.buildSystemPrompt's own instruction text, before the case JSON is appended. */
    const val BASE_SYSTEM_PROMPT_CHARS = 1_400

    /**
     * Characters of the Live setup message's systemInstruction — [BASE_SYSTEM_PROMPT_CHARS] plus
     * the case JSON, which averages ~4.6 KB across `data/cases/`. Only used when the caller has no
     * real case JSON to measure. Live re-bills this text on every turn.
     */
    const val DEFAULT_SYSTEM_PROMPT_CHARS = BASE_SYSTEM_PROMPT_CHARS + 4_600
    const val PROMPT_CHARS_PER_TOKEN = 4

    /** System-instruction size for a session whose case JSON we actually have on hand. */
    fun systemPromptCharsFor(caseJson: String?): Int {
        // "{}" is SessionEntity.rawCaseJson's default for sessions that never carried a case.
        val caseChars = caseJson?.length?.takeIf { it > 2 } ?: return DEFAULT_SYSTEM_PROMPT_CHARS
        return BASE_SYSTEM_PROMPT_CHARS + caseChars
    }

    /**
     * Ceiling on the context Live re-bills each turn.
     *
     * GeminiLiveClient's setup message enables `contextWindowCompression.slidingWindow`, so older
     * turns get compressed away instead of growing the prompt forever. Without a cap here a long
     * Survival "hangout" session would be estimated with unbounded quadratic growth. This is the
     * one number in the estimate that is a genuine assumption rather than a documented rate.
     */
    const val GEMINI_LIVE_CONTEXT_WINDOW_TOKENS = 32_000

    /** Learner+AI seconds per exchange, for estimating turn count when no transcript is available. */
    private const val TYPICAL_EXCHANGE_SECONDS = 20

    /**
     * Estimate a Gemini Live session's bill from its transcript and wall-clock duration.
     *
     * Two properties of Live billing that a single `duration x rate` pass (what this used to be)
     * gets structurally wrong, both of which push the estimate far too low:
     *
     * 1. **Context is re-billed every turn.** Google charges each turn for every token in the
     *    session context window — the new audio *plus* all accumulated prior turns. A session's
     *    input bill therefore grows quadratically in turn count, until the sliding window caps it.
     * 2. **The mic streams continuously.** VoiceManager's echo guard replaces audio captured while
     *    the patient is speaking with silence of the same length but still sends it, so billable
     *    input audio tracks the whole wall clock, not just the learner's speech.
     *
     * Text input (the system instruction + case JSON) is also billed on every turn and used to be
     * dropped entirely.
     *
     * Output-audio *transcription* tokens are deliberately not counted: `outputAudioTranscription`
     * is enabled on the socket, but Google bills the spoken audio rather than its transcript.
     */
    fun estimateGeminiLiveCost(
        turns: List<VoiceTurn>,
        durationSeconds: Int,
        model: String = "",
        systemPromptChars: Int = DEFAULT_SYSTEM_PROMPT_CHARS,
    ): VoiceCostEstimate {
        if (durationSeconds <= 0) return VoiceCostEstimate(0.0, 0, 0)
        val pricing = lookupVoiceModelPricing("gemini", model)
        val tokensPerSecond = geminiAudioTokensPerSecond(model)

        val aiTurnTokens = turns
            .filter { it.role.lowercase() in AI_TURN_ROLES }
            .map { speechAudioTokens(it.text.length, tokensPerSecond) }
            // A session with a duration but no AI turns in the transcript still had the model
            // listening and answering, so assume a plausible turn count rather than collapsing the
            // whole session to zero.
            .ifEmpty {
                val assumedExchanges = max(1, durationSeconds / TYPICAL_EXCHANGE_SECONDS)
                // Half the wall clock is the model talking, in a conversation with no transcript.
                val perTurnSeconds = durationSeconds.toDouble() / 2.0 / assumedExchanges
                List(assumedExchanges) { max(1, (perTurnSeconds * tokensPerSecond).toInt()) }
            }

        return walkLiveContext(
            aiTurnAudioTokens = aiTurnTokens,
            durationSeconds = durationSeconds,
            tokensPerSecond = tokensPerSecond,
            systemPromptChars = systemPromptChars,
            pricing = pricing,
        )
    }

    /**
     * The billing walk itself: for each model response, Google charges the system instruction plus
     * every audio token accumulated so far (capped by the sliding window), then the response's own
     * audio at the output rate, which in turn becomes context for the next turn.
     */
    private fun walkLiveContext(
        aiTurnAudioTokens: List<Int>,
        durationSeconds: Int,
        tokensPerSecond: Int,
        systemPromptChars: Int,
        pricing: VoiceModelPricing,
    ): VoiceCostEstimate {
        val systemTokens = max(0, systemPromptChars) / PROMPT_CHARS_PER_TOKEN
        // Every second of the session is streamed to the provider, spread evenly over the turns.
        val micTokensPerExchange =
            (durationSeconds.toDouble() * tokensPerSecond / aiTurnAudioTokens.size).toLong()

        var contextAudio = 0L
        var billedTextInput = 0L
        var billedAudioInput = 0L
        var billedAudioOutput = 0L
        for (responseTokens in aiTurnAudioTokens) {
            contextAudio += micTokensPerExchange
            billedTextInput += systemTokens
            billedAudioInput += minOf(contextAudio, GEMINI_LIVE_CONTEXT_WINDOW_TOKENS.toLong())
            billedAudioOutput += responseTokens
            contextAudio += responseTokens
        }

        val cost = (
            billedTextInput * pricing.textInputPricePer1M +
                billedAudioInput * pricing.audioInputPricePer1M +
                billedAudioOutput * pricing.audioOutputPricePer1M
            ) / 1_000_000
        return VoiceCostEstimate(
            costUsd = round6(cost),
            inputTokens = (billedTextInput + billedAudioInput).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            outputTokens = billedAudioOutput.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    /**
     * Shape for callers that only have a duration and a lump total of AI-spoken characters, with
     * no ordered transcript — the pre-session "what will this cost me" projection.
     *
     * The total is spread over the number of turns a session that long plausibly had, so the
     * compounding-context walk has something to compound over. Prefer [estimateGeminiLiveCost]
     * whenever the real turns are available; a lump total cannot tell a 3-turn session from a
     * 30-turn one, and under Live's billing those cost very different amounts.
     */
    fun computeGeminiVoiceCost(durationSeconds: Int, outputChars: Int, model: String = ""): Double {
        if (durationSeconds <= 0) return 0.0
        if (outputChars < 0) throw IllegalArgumentException("outputChars cannot be negative: $outputChars")
        val tokensPerSecond = geminiAudioTokensPerSecond(model)
        val exchanges = max(1, durationSeconds / TYPICAL_EXCHANGE_SECONDS)
        val tokensPerTurn = speechAudioTokens(outputChars / exchanges, tokensPerSecond)
        return walkLiveContext(
            aiTurnAudioTokens = List(exchanges) { tokensPerTurn },
            durationSeconds = durationSeconds,
            tokensPerSecond = tokensPerSecond,
            systemPromptChars = DEFAULT_SYSTEM_PROMPT_CHARS,
            pricing = lookupVoiceModelPricing("gemini", model),
        ).costUsd
    }

    private fun speechAudioTokens(chars: Int, tokensPerSecond: Int): Int {
        if (chars <= 0) return 0
        return max(((chars.toDouble() / SPEECH_CHARS_PER_SECOND) * tokensPerSecond).toInt(), 1)
    }

    /**
     * The original single-pass formula ported from `app/analysis/cost_tracker.py`, preserved
     * *only* so `CostTrackerGoldenTest` can keep verifying port fidelity against the frozen Python
     * vectors in `src/test/resources/golden/cost_tracker.golden.json`.
     *
     * Do not price anything with this. It bills one linear pass over the session at the 2.x
     * audio rate and charges nothing for text input, so it does not describe what Gemini Live
     * actually invoices — see [estimateGeminiLiveCost] for the model the app uses. The golden
     * vector documents what the desktop port did, not what Google charges.
     */
    internal fun computeGeminiVoiceCostPythonParity(durationSeconds: Int, outputChars: Int): Double {
        if (durationSeconds <= 0) return 0.0
        if (outputChars < 0) throw IllegalArgumentException("outputChars cannot be negative: $outputChars")
        val inputTokens = durationSeconds * GEMINI_2X_AUDIO_SECONDS_TO_TOKENS
        val outputTokens = speechAudioTokens(outputChars, GEMINI_2X_AUDIO_SECONDS_TO_TOKENS)
        val cost = (inputTokens * GEMINI_AUDIO_INPUT_PRICE_PER_1M / 1_000_000
            + outputTokens * GEMINI_AUDIO_OUTPUT_PRICE_PER_1M / 1_000_000)
        return round6(cost)
    }

    fun computeOpenaiVoiceCost(durationSeconds: Int, outputChars: Int): Double {
        if (durationSeconds <= 0) return 0.0
        val inputTokens = durationSeconds * OPENAI_AUDIO_SECONDS_TO_TOKENS
        val outputTokens = estimateOpenaiOutputAudioTokens(outputChars)
        val cost = (inputTokens * OPENAI_AUDIO_INPUT_PRICE_PER_1M / 1_000_000
            + outputTokens * OPENAI_AUDIO_OUTPUT_PRICE_PER_1M / 1_000_000)
        return round6(cost)
    }

    private fun round6(v: Double): Double = if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else round(v * 1_000_000) / 1_000_000

    /**
     * Build a SessionCostReport from a persisted session.
     *
     * A session only ever has a real analysis cost when MainViewModel.finishSession actually
     * called out to a provider (analysisApiKey non-blank) — that path is the sole writer of
     * session.claudeInputTokens/claudeOutputTokens/claudeCostUsd, and it correctly leaves them at
     * 0 for a keyless demo/mock session (see generateUnavailableEvaluation's branch). Re-estimating
     * "cost" from stored transcript/response text length regardless of whether a call happened —
     * the previous behavior here — fabricated a nonzero bill for sessions that never touched an
     * API, and always labeled the provider "claude" even when a different (or no) backend ran.
     * @param analysisUsage normalized token counts {inputTokens, outputTokens, cachedTokens} from
     *   the provider call just completed — preferred source of exact counts when building a report
     *   immediately after analysis, before the session row itself is persisted.
     */
    fun buildReportFromSession(
        session: SessionEntity,
        analysisUsage: com.example.medvoicetrainer.api.LlmUsage? = null
    ): SessionCostReport {
        val storedTokensPresent = session.claudeInputTokens > 0 || session.claudeOutputTokens > 0 || session.claudeCostUsd > 0.0
        val usedRealAnalysisApi = analysisUsage != null || storedTokensPresent
        val modelUsed = when {
            !analysisUsage?.modelUsed.isNullOrBlank() -> analysisUsage!!.modelUsed
            !session.analysisModel.isNullOrBlank() -> session.analysisModel
            else -> ""
        }
        val provider = when {
            !usedRealAnalysisApi -> "demo"
            modelUsed.isNotBlank() -> lookupModelPricing(modelUsed)?.provider ?: "claude"
            else -> "claude"
        }
        val report = SessionCostReport(
            sessionId = session.id,
            createdAt = session.createdAt,
            mode = session.mode,
            caseName = session.caseName,
            voiceBackend = session.voiceBackend,
            durationSeconds = session.durationSeconds,
            analysisProvider = provider,
            analysisModel = modelUsed
        )

        when {
            analysisUsage != null -> {
                report.claudeInputTokens = analysisUsage.inputTokens
                report.claudeOutputTokens = analysisUsage.outputTokens
                report.claudeCachedInputTokens = analysisUsage.cachedTokens
            }
            usedRealAnalysisApi -> {
                // Trust the counts MainViewModel already persisted on the session row rather than
                // re-deriving them from raw text length a second time.
                report.claudeInputTokens = session.claudeInputTokens
                report.claudeOutputTokens = session.claudeOutputTokens
                report.claudeCachedInputTokens = session.claudeCachedTokens
            }
            else -> {
                report.notes.add("Analysis was skipped — no API key was used, so no analysis cost was incurred.")
            }
        }

        report.claudeCostUsd = if (usedRealAnalysisApi) {
            computeAnalysisCost(
                report.analysisProvider,
                report.claudeInputTokens,
                report.claudeOutputTokens,
                report.claudeCachedInputTokens,
                report.analysisModel
            )
        } else {
            0.0
        }

        // Voice backend costs. The ordered turn list (not just a character total) is what the
        // Gemini estimator needs, because Live's bill depends on how many times the accumulated
        // context got re-processed, not only on how much was ultimately said.
        val transcriptTurns = mutableListOf<VoiceTurn>()
        try {
            val turns = JSONArray(session.rawTranscript)
            for (i in 0 until turns.length()) {
                val turn = turns.optJSONObject(i) ?: continue
                transcriptTurns.add(
                    VoiceTurn(turn.optString("role"), turn.optString("text", ""))
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            transcriptTurns.clear()
        }
        val transcriptChars = transcriptTurns
            .filter { it.role.lowercase() in AI_TURN_ROLES }
            .sumOf { it.text.length }
        report.voiceTranscriptChars = transcriptChars
        report.voiceAudioSeconds = report.durationSeconds

        val storedVoiceUsage = VoiceApiUsage(
            provider = report.voiceBackend.lowercase(),
            model = session.voiceModel.orEmpty(),
            inputTextTokens = session.voiceInputTextTokens,
            inputAudioTokens = session.voiceInputAudioTokens,
            outputTextTokens = session.voiceOutputTextTokens,
            outputAudioTokens = session.voiceOutputAudioTokens,
            cachedInputTextTokens = session.voiceCachedInputTextTokens,
            cachedInputAudioTokens = session.voiceCachedInputAudioTokens,
            thinkingTokens = session.voiceThinkingTokens,
        )
        when (report.voiceBackend.lowercase()) {
            "gemini" -> {
                if (session.voiceUsageExact) {
                    report.voiceEstimatedInputTokens = storedVoiceUsage.inputTokens
                    report.voiceEstimatedOutputTokens = storedVoiceUsage.outputTokens
                    report.voiceCostUsd = computeVoiceCost(storedVoiceUsage)
                } else {
                    val estimate = estimateGeminiLiveCost(
                        turns = transcriptTurns,
                        durationSeconds = report.durationSeconds,
                        model = session.voiceModel.orEmpty(),
                        systemPromptChars = systemPromptCharsFor(session.rawCaseJson),
                    )
                    report.voiceEstimatedInputTokens = estimate.inputTokens
                    report.voiceEstimatedOutputTokens = estimate.outputTokens
                    report.voiceCostUsd = estimate.costUsd
                    report.notes.add(
                        "Estimated voice cost — the provider reported no usage metadata for this " +
                            "session, so this models Live's per-turn context re-processing rather " +
                            "than measuring it. Check Google AI Studio for the billed amount."
                    )
                }
            }
            "openai" -> {
                if (session.voiceUsageExact) {
                    report.voiceEstimatedInputTokens = storedVoiceUsage.inputTokens
                    report.voiceEstimatedOutputTokens = storedVoiceUsage.outputTokens
                    report.voiceCostUsd = computeVoiceCost(storedVoiceUsage)
                } else {
                    report.voiceEstimatedInputTokens = report.durationSeconds * OPENAI_AUDIO_SECONDS_TO_TOKENS
                    report.voiceEstimatedOutputTokens = estimateOpenaiOutputAudioTokens(report.voiceTranscriptChars)
                    report.voiceCostUsd = computeOpenaiVoiceCost(report.durationSeconds, report.voiceTranscriptChars)
                    report.notes.add("Estimated voice cost — provider usage metadata was not stored for this session.")
                }
            }
            else -> {
                report.notes.add("Voice backend '${report.voiceBackend}' (mock) — no cost.")
            }
        }

        report.totalCostUsd = round6(report.claudeCostUsd + report.voiceCostUsd)
        return report
    }

    /**
     * Ballpark "what would a typical session cost me" figure shown before starting a live
     * session — answers the cost-anxiety half of "why not just use my subscription's voice mode"
     * (docs/DIFFERENTIATION_ANSWERS.md Q1). Prefers the learner's own recent real-cost history for
     * the selected voice backend (most representative of how they actually talk); falls back to a
     * fixed typical-session estimate only when there is no such history yet.
     *
     * @return the USD estimate paired with whether it came from the learner's own history (true)
     *   or the generic fallback assumption (false), so the UI can label it accordingly.
     */
    fun estimateTypicalSessionCostUsd(
        recentSessions: List<SessionEntity>,
        voiceBackend: String,
        analysisBackend: String,
        analysisModel: String,
        voiceModel: String = ""
    ): Pair<Double, Boolean> {
        // Excludes sessions cancelled before analysis (finalizeVoiceOnlySession stores only the
        // voice-audio cost, so an interrupted session's totalCostUsd is real but zero-analysis —
        // averaging those in would systematically understate the cost of a completed session).
        val historical = recentSessions
            .filter {
                it.voiceBackend.equals(voiceBackend, ignoreCase = true) &&
                    it.totalCostUsd > 0.0 &&
                    it.rawEvalJson != null
            }
            .sortedByDescending { it.createdAt }
            .take(10)
        if (historical.isNotEmpty()) {
            return historical.sumOf { it.totalCostUsd } / historical.size to true
        }

        return typicalSessionFallbackCostUsd(voiceBackend, analysisBackend, analysisModel, voiceModel) to false
    }

    /** Projection-based equivalent used by Practice so opening the picker never loads session JSON. */
    fun estimateTypicalSessionCostFromSamples(
        recentSessions: List<com.example.medvoicetrainer.db.SessionCostSample>,
        voiceBackend: String,
        analysisBackend: String,
        analysisModel: String,
        voiceModel: String = "",
    ): Pair<Double, Boolean> {
        val historical = recentSessions
            .filter {
                it.voiceBackend.equals(voiceBackend, ignoreCase = true) &&
                    it.totalCostUsd > 0.0 && it.analyzed
            }
            .sortedByDescending { it.createdAt }
            .take(10)
        if (historical.isNotEmpty()) {
            return historical.sumOf { it.totalCostUsd } / historical.size to true
        }
        return typicalSessionFallbackCostUsd(voiceBackend, analysisBackend, analysisModel, voiceModel) to false
    }

    /**
     * Fallback assumption for a learner with no cost history yet: a 5-minute practice session
     * (the app's "5-minute daily mission" framing) with a modest AI-turn transcript and one
     * analysis call. Shared by both estimateTypicalSessionCost* entry points so the number a
     * learner sees cannot depend on which screen asked for it.
     */
    private fun typicalSessionFallbackCostUsd(
        voiceBackend: String,
        analysisBackend: String,
        analysisModel: String,
        voiceModel: String,
    ): Double {
        val typicalDurationSeconds = 300
        val typicalTranscriptChars = 900
        val voiceCost = when (voiceBackend.lowercase()) {
            "gemini" -> computeGeminiVoiceCost(
                typicalDurationSeconds, typicalTranscriptChars, voiceModel
            )
            "openai" -> computeOpenaiVoiceCost(typicalDurationSeconds, typicalTranscriptChars)
            else -> 0.0
        }
        val analysisCost = if (analysisBackend.equals("demo", ignoreCase = true)) {
            0.0
        } else {
            computeAnalysisCost(analysisBackend, inputTokens = 2500, outputTokens = 900, model = analysisModel)
        }
        return round6(voiceCost + analysisCost)
    }

    fun formatReportText(report: SessionCostReport): String {
        val lines = mutableListOf(
            "=".repeat(60),
            "  Bedside English — Session API Cost Report",
            "=".repeat(60),
            "  Session ID: ${report.sessionId}",
            "  Date      : ${report.createdAt.take(19)}",
            "  Mode      : ${report.mode.replaceFirstChar { it.uppercase() }}",
            "  Case      : ${report.caseName}",
            "  Backend   : ${report.voiceBackend}",
            "  Duration  : ${report.durationSeconds / 60}m ${report.durationSeconds % 60}s",
            "",
            "── ${ANALYSIS_PROVIDER_LABELS[report.analysisProvider] ?: "Claude API"} (Post-Session Analysis) ──────────────────",
            "  Model            : ${report.analysisModel.ifEmpty { ANALYSIS_PROVIDER_MODELS[report.analysisProvider] ?: "claude-sonnet-4-6" }}",
            "  Input tokens     : ${report.claudeInputTokens}",
            "  Cached tokens    : ${report.claudeCachedInputTokens}",
            "  Output tokens    : ${report.claudeOutputTokens}",
            "  Cost             : $${"%.6f".format(report.claudeCostUsd)}",
            ""
        )

        when (report.voiceBackend.lowercase()) {
            "gemini" -> lines += listOf(
                "── Gemini Live (Voice Session) ─────────────────────────",
                "  Model            : Gemini Live (flash, native audio)",
                "  Audio seconds    : ${report.voiceAudioSeconds}s",
                "  Est. input tokens: ${report.voiceEstimatedInputTokens}",
                "  Est. output chars: ${report.voiceTranscriptChars}",
                "  Cost (estimated) : $${"%.6f".format(report.voiceCostUsd)}",
                ""
            )
            "openai" -> lines += listOf(
                "── OpenAI Realtime (Voice Session) ─────────────────────",
                "  Model            : gpt-4o-realtime-preview",
                "  Audio seconds    : ${report.voiceAudioSeconds}s",
                "  Est. input tokens: ${report.voiceEstimatedInputTokens}",
                "  Est. output tokens: ${report.voiceEstimatedOutputTokens}",
                "  Cost (estimated) : $${"%.6f".format(report.voiceCostUsd)}",
                ""
            )
            else -> lines += listOf(
                "── Voice Backend: ${report.voiceBackend} (no cost) ────────────",
                ""
            )
        }

        lines += listOf(
            "── Total ────────────────────────────────────────────────",
            "  TOTAL COST (USD) : $${"%.6f".format(report.totalCostUsd)}",
            "=".repeat(60)
        )

        if (report.notes.isNotEmpty()) {
            lines.add("\nNotes:")
            for (note in report.notes) {
                lines.add("  * $note")
            }
        }

        return lines.joinToString("\n")
    }
}
