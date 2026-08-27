package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.api.OpenAIService
import org.json.JSONArray

/**
 * Ported from app/analysis/shadowing_engine.py — shadowing material generator.
 *
 * 1. Picks 1-3 student turns where language improvement would have the most impact.
 * 2. Rewrites each with a domain-specific clinical or everyday-speaking prompt.
 * 3. Offers TTS synthesis so the student can *listen then imitate*.
 *
 * The text generation is free-choice: whichever feedback backend is configured, via a small
 * fast/cheap model per provider (not the main analysis model). TTS synthesis is attempted
 * lazily by the caller (e.g. on button click).
 */

data class ShadowingItem(
    val turnIndex: Int?,
    val studentSaid: String,
    val idealVersion: String,
    val why: String
)

object ShadowingEngine {

    private val CLINICAL_SHADOW_PROMPT = """
        You are a senior attending physician and expert medical English coach.

        Read the following transcript from a medical student's practice session.
        Select the 1 to 3 student turns where improving the English would make the
        BIGGEST difference to how professional and clear they sound.

        For each selected turn, write an ideal attending-level restatement:
        - Natural, empathetic, confident English (not textbook stiff).
        - Keep each restatement under 60 words.
        - Prioritise turns with grammar errors, Konglish, awkward phrasing, or missed
          rapport-building opportunities.

        Return ONLY a valid JSON array, nothing else:
        [
          {
            "turn_index": <int from the transcript>,
            "student_said": "<exact student text>",
            "ideal_version": "<ideal attending restatement>",
            "why": "<one sentence: what makes the ideal version better>"
          }
        ]

        Transcript:
        {transcript_text}
    """.trimIndent()

    private val EVERYDAY_SHADOW_PROMPT = """
        You are an expert coach for practical, everyday spoken English for adults.

        Read this transcript from an ordinary real-life conversation. Select the 1 to
        3 USER turns where a small change would most improve real-world communication.

        For each selected turn, write a natural alternative that a person could
        actually say in that moment:
        - Preserve the user's intended meaning and the immediate conversational job.
        - A short answer, clarification request, polite refusal, or exit can be ideal;
          never make it longer merely to sound advanced.
        - Prefer common, easy-to-retrieve wording over idioms or impressive vocabulary.
        - Keep each alternative under 35 words and do not introduce medical content.

        Return ONLY a valid JSON array, nothing else:
        [
          {
            "turn_index": <int from the transcript>,
            "student_said": "<exact user text>",
            "ideal_version": "<natural everyday alternative>",
            "why": "<one sentence: why it fits this real-life moment better>"
          }
        ]

        Transcript:
        {transcript_text}
    """.trimIndent()

    private fun modelFor(backend: String): String = when (backend) {
        "gemini" -> "gemini-3.1-flash-lite"
        "openai" -> "gpt-4o-mini"
        else -> "claude-haiku-4-5-20251001"
    }

    /** Generate 1-3 shadowing items from the session transcript. Returns [] on failure. */
    suspend fun generateShadowingItems(
        transcript: List<Map<String, Any?>>?,
        backend: String = "claude",
        apiKey: String,
        domain: String = "clinical"
    ): List<ShadowingItem> {
        return try {
            if (transcript == null) return emptyList()

            val userTurns = transcript.filter { it["role"] == "user" && it.containsKey("text") }
            if (userTurns.size < 2) return emptyList()

            val transcriptTextParts = transcript.mapIndexedNotNull { i, t ->
                val role = t["role"]?.toString() ?: return@mapIndexedNotNull null
                val text = t["text"]?.toString() ?: return@mapIndexedNotNull null
                val turnIdx = t["turn_index"] ?: i
                "[$turnIdx] ${role.uppercase()}: $text"
            }
            val transcriptText = transcriptTextParts.joinToString("\n")
            val promptTemplate = if (domain.lowercase() in setOf("everyday", "survival")) EVERYDAY_SHADOW_PROMPT else CLINICAL_SHADOW_PROMPT
            val prompt = promptTemplate.replace("{transcript_text}", transcriptText.take(4500))

            var raw = if (backend == "gemini") {
                com.example.medvoicetrainer.api.withGeminiModelFallback(
                    modelFor(backend),
                    com.example.medvoicetrainer.api.GEMINI_COVERAGE_MODEL_CHAIN
                ) { m -> AnalysisEngine.generateContent(backend, apiKey, m, prompt) }
            } else {
                AnalysisEngine.generateContent(backend, apiKey, modelFor(backend), prompt)
            }.trim()
            if (raw.startsWith("```")) {
                raw = raw.split("```").getOrElse(1) { raw }
                if (raw.startsWith("json")) raw = raw.substring(4)
            }
            val items = JSONArray(raw.trim())
            val valid = mutableListOf<ShadowingItem>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val ideal = item.optString("ideal_version", "")
                if (ideal.isEmpty()) continue
                valid.add(
                    ShadowingItem(
                        turnIndex = if (item.has("turn_index") && !item.isNull("turn_index")) item.optInt("turn_index") else null,
                        studentSaid = item.optString("student_said", "").trim(),
                        idealVersion = ideal.trim(),
                        why = item.optString("why", "").trim()
                    )
                )
            }
            valid.take(3)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Synthesize speech via OpenAI tts-1. Returns (PCM16 mono bytes, sample_rate=24000), or
     * null on failure. Input is capped at 500 chars, same limit as the Python original.
     */
    suspend fun synthesizeOpenai(apiKey: String, text: String): Pair<ByteArray, Int>? {
        if (apiKey.isBlank()) return null
        return try {
            OpenAIService.synthesizeSpeech(apiKey, text.take(500)) to 24000
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /** Synthesize speech via Gemini's audio-output modality. Returns (bytes, sample_rate), or null on failure. */
    suspend fun synthesizeGemini(apiKey: String, text: String): Pair<ByteArray, Int>? {
        if (apiKey.isBlank()) return null
        return GeminiService.synthesizeSpeech(apiKey, text)
    }

    /**
     * Dispatch TTS synthesis by backend setting. "windows" (the Python default, via pyttsx3)
     * has no Android equivalent — callers should route that case to a native
     * android.speech.tts.TextToSpeech implementation instead of calling this for it.
     */
    suspend fun synthesizeTts(backend: String, openAiApiKey: String, geminiApiKey: String, text: String): Pair<ByteArray, Int>? {
        return when (backend) {
            "openai" -> synthesizeOpenai(openAiApiKey, text)
            "gemini" -> synthesizeGemini(geminiApiKey, text)
            else -> null
        }
    }
}
