package com.example.medvoicetrainer.voice

/**
 * Billable realtime usage reported by the provider.
 *
 * Counts are already cumulative in the way the provider bills a completed response. For Gemini
 * Live that means prompt counts include the active conversation context reprocessed for that
 * turn. Callers must sum completed-response records, not try to reconstruct context from audio
 * duration.
 */
data class VoiceApiUsage(
    val provider: String,
    val model: String,
    val inputTextTokens: Int = 0,
    val inputAudioTokens: Int = 0,
    val outputTextTokens: Int = 0,
    val outputAudioTokens: Int = 0,
    val cachedInputTextTokens: Int = 0,
    val cachedInputAudioTokens: Int = 0,
    val thinkingTokens: Int = 0,
) {
    val inputTokens: Int
        get() = inputTextTokens + inputAudioTokens

    val outputTokens: Int
        get() = outputTextTokens + outputAudioTokens + thinkingTokens

    operator fun plus(other: VoiceApiUsage): VoiceApiUsage {
        require(provider.equals(other.provider, ignoreCase = true))
        return copy(
            model = other.model.ifBlank { model },
            inputTextTokens = inputTextTokens + other.inputTextTokens,
            inputAudioTokens = inputAudioTokens + other.inputAudioTokens,
            outputTextTokens = outputTextTokens + other.outputTextTokens,
            outputAudioTokens = outputAudioTokens + other.outputAudioTokens,
            cachedInputTextTokens = cachedInputTextTokens + other.cachedInputTextTokens,
            cachedInputAudioTokens = cachedInputAudioTokens + other.cachedInputAudioTokens,
            thinkingTokens = thinkingTokens + other.thinkingTokens,
        )
    }
}
