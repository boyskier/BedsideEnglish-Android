package com.example.medvoicetrainer.voice

internal const val DEFAULT_OPENAI_REALTIME_MODEL = "gpt-realtime-2.1"
internal const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-3.1-flash-live-preview"

/**
 * The preferences screen also stores text-analysis models. A generic model cannot be used on a
 * realtime endpoint, so only preserve an explicitly selected realtime/live model.
 */
internal fun normalizeOpenAIRealtimeModel(model: String): String {
    if (model.isBlank()) return DEFAULT_OPENAI_REALTIME_MODEL
    val trimmed = model.trim()
    val prefixClean = if (trimmed.startsWith("models/", ignoreCase = true)) trimmed.substring(7) else trimmed
    val withoutQuery = prefixClean.substringBefore('?').substringBefore('#').trim()
    val cleaned = withoutQuery.filter { !it.isWhitespace() }
    if (cleaned.isEmpty()) return DEFAULT_OPENAI_REALTIME_MODEL
    return cleaned.takeIf {
        it.contains("realtime", ignoreCase = true) &&
            !it.contains("..") &&
            !it.contains("whisper", ignoreCase = true) &&
            !it.contains("tts", ignoreCase = true) &&
            !it.contains("embedding", ignoreCase = true) &&
            it.all { char -> char.isLetterOrDigit() || char in "-._:/" }
    } ?: DEFAULT_OPENAI_REALTIME_MODEL
}

internal fun normalizeGeminiLiveModel(model: String): String {
    if (model.isBlank()) return DEFAULT_GEMINI_LIVE_MODEL
    val trimmed = model.trim()
    val prefixClean = if (trimmed.startsWith("models/", ignoreCase = true)) trimmed.substring(7) else trimmed
    val withoutQuery = prefixClean.substringBefore('?').substringBefore('#').trim()
    val cleaned = withoutQuery.filter { !it.isWhitespace() }
    if (cleaned.isEmpty() || cleaned.contains("<") || cleaned.contains(">")) return DEFAULT_GEMINI_LIVE_MODEL
    return cleaned.takeIf {
        (it.contains("live", ignoreCase = true) ||
            it.contains("native-audio", ignoreCase = true) ||
            it.contains("bidi", ignoreCase = true)) &&
            !it.contains("..") &&
            !it.contains("embedding", ignoreCase = true) &&
            !it.contains("aqa", ignoreCase = true) &&
            it.all { char -> char.isLetterOrDigit() || char in "-._:/" }
    } ?: DEFAULT_GEMINI_LIVE_MODEL
}

/**
 * Ordered live-model candidates for one connection attempt, deduplicated while preserving order.
 * A model that previously reached setupComplete ([lastGood]) is tried first so a mid-session
 * reconnect skips the dead candidates; then the user's [requested] model; then the known-good
 * static chain, so a retired or not-yet-enabled preview name at the head can never brick voice.
 * Mirrors app/voice/gemini_client.py's _candidate_models().
 */
internal fun orderedGeminiLiveCandidates(
    requested: String,
    lastGood: String?,
    staticChain: List<String>,
): List<String> {
    val ordered = LinkedHashSet<String>()
    val cleanLastGood = lastGood?.trim()?.lowercase()
    if (!cleanLastGood.isNullOrBlank()) ordered.add(cleanLastGood)
    val cleanRequested = requested.trim().lowercase()
    if (cleanRequested.isNotBlank()) ordered.add(cleanRequested)
    for (item in staticChain) {
        val cleanItem = item.trim().lowercase()
        if (cleanItem.isNotBlank()) {
            ordered.add(cleanItem)
        }
    }
    return ordered.toList()
}

/** Collect provider transcript deltas and emit exactly one final turn. */
internal class TurnTranscriptBuffer {
    private val text = StringBuilder()

    fun append(delta: String?) {
        if (delta.isNullOrEmpty()) return
        val clean = delta
            .filter { char -> char >= ' ' || char == '\n' || char == '\t' || char == '\r' }
            .replace("\r\n", "\n")
            .replace("\r", "")
        if (clean.isEmpty()) return
        if (text.length + clean.length > 50_000) {
            if (text.length < 50_000) {
                text.append(clean.take(50_000 - text.length))
            }
        } else {
            text.append(clean)
        }
    }

    fun take(finalText: String? = null): String {
        val explicit = finalText.orEmpty().trim()
        val buffered = text.toString().trim()
        text.setLength(0)
        if (explicit.isEmpty()) return buffered
        if (buffered.isEmpty()) return explicit
        if (explicit.lowercase().startsWith(buffered.lowercase())) {
            return buffered + explicit.substring(buffered.length)
        }
        if (buffered.lowercase().startsWith(explicit.lowercase())) {
            return buffered
        }
        return "$buffered $explicit"
    }

    fun clear() {
        text.setLength(0)
    }

    fun isNotEmpty(): Boolean = text.isNotBlank()
}
