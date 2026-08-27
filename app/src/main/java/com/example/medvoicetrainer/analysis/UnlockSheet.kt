package com.example.medvoicetrainer.analysis

/**
 * Ported from app/ui/unlock_sheet.py — the shared "add your free Gemini key" conversion
 * surface's non-UI logic. Every demo->live conversion point (feedback banner, locked tabs, the
 * decision screen, the warm-up-summary link) is meant to render the same key-entry body and
 * report the same `unlock_success` telemetry event with a `source` so conversion can be
 * attributed. Android's shared Compose connection journey lives in OnboardingScreen and calls
 * this object's normalization/validation before MainViewModel persists the verified credential.
 * Key persistence itself is handled by `Repository.saveGeminiApiKey`.
 */
object UnlockSheet {

    /**
     * Mobile clipboard contents occasionally include an environment-variable prefix or quotes
     * copied from setup documentation. Accept those harmless wrappers without changing the token
     * itself; internal whitespace is deliberately preserved so validation can reject it.
     */
    fun normalizePastedGeminiKey(value: String?): String {
        var normalized = (value ?: "").trim()
        val prefixes = listOf("GEMINI_API_KEY=", "GOOGLE_API_KEY=")
        prefixes.firstOrNull { normalized.startsWith(it, ignoreCase = true) }?.let { prefix ->
            normalized = normalized.substring(prefix.length).trim()
        }
        if (normalized.length >= 2) {
            val first = normalized.first()
            val last = normalized.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                normalized = normalized.substring(1, normalized.length - 1).trim()
            }
        }
        return normalized
    }

    /**
     * Cheap offline format check (no network) for live onboarding feedback. Google AI Studio
     * keys currently look like "AIza..." and are ~39 chars; any reasonably long space-free
     * token is also accepted so the check stays permissive if Google's format shifts.
     */
    fun looksLikeGeminiKey(value: String?): Boolean {
        val v = normalizePastedGeminiKey(value)
        if (v.isEmpty() || v.any(Char::isWhitespace)) return false
        if (v.startsWith("AIza") && v.length >= 30) return true
        return v.length >= 30
    }

    /**
     * Same cheap offline check as [looksLikeGeminiKey], for OpenAI. Current OpenAI keys start
     * with "sk-" (project keys look like "sk-proj-..."); stay permissive on any long space-free
     * token so this doesn't break if OpenAI's format shifts.
     */
    fun looksLikeOpenAiKey(value: String?): Boolean {
        val v = (value ?: "").trim()
        if (v.isEmpty() || v.any(Char::isWhitespace)) return false
        if (v.startsWith("sk-") && v.length >= 20) return true
        return v.length >= 30
    }

    /**
     * Same cheap offline check as [looksLikeGeminiKey], for Claude/Anthropic. Current Anthropic
     * keys look like "sk-ant-api03-...".
     */
    fun looksLikeClaudeKey(value: String?): Boolean {
        val v = (value ?: "").trim()
        if (v.isEmpty() || v.any(Char::isWhitespace)) return false
        if (v.startsWith("sk-ant-") && v.length >= 20) return true
        return v.length >= 30
    }

    /** Settings to flip once a key is activated, so the app switches off the demo backend. */
    val ACTIVATION_SETTINGS: Map<String, String> = mapOf(
        "voice_backend" to "gemini",
        "analysis_backend" to "gemini"
    )
}
