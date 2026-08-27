package com.example.medvoicetrainer.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DebriefCorrection(
    val original: String,
    val corrected: String,
    val explanation: String,
    val category: String
)

@Serializable
data class DebriefCommitment(
    val text: String,
    val focus_area: String
)

@Serializable
data class DebriefInsights(
    val corrections: List<DebriefCorrection> = emptyList(),
    val commitments: List<DebriefCommitment> = emptyList(),
    val key_insights: List<String> = emptyList(),
    val reflection_quality: String = "",
    val reflection_summary: String = "",
    val extracted_from_turns: Int = 0
)

object DebriefEngine {

    val MIN_LEARNER_TURNS = 2
    val MIN_LEARNER_CHARS = 60

    private val EXTRACTION_PROMPT = """
You are a medical education data analyst. Below is a Socratic debrief conversation between an AI attending-physician tutor and a medical student (an international medical graduate improving their spoken English), which took place right after a simulated clinical encounter.

Mine the STUDENT's messages for durable learning signal.

=== DEBRIEF CONVERSATION ===
{chat_text}

=== FEEDBACK THE STUDENT RECEIVED BEFORE THE DEBRIEF (context only) ===
{analysis_summary}

Return ONLY valid JSON with exactly this schema:
{
  "corrections": [
    {
      "original": "<exact flawed English the STUDENT wrote/said in the debrief>",
      "corrected": "<natural professional English version>",
      "explanation": "<one sentence: why the corrected form is better>",
      "category": "<grammar|vocabulary|natural-phrasing|register>"
    }
  ],
  "commitments": [
    {
      "text": "<one concrete, observable behavior the student agreed or resolved to do differently in a future encounter, phrased as an imperative, e.g. 'Ask about the patient's own ideas and concerns before presenting the plan'>",
      "focus_area": "<communication|clinical_reasoning|language|empathy|structure>"
    }
  ],
  "key_insights": ["<up to 3 short takeaways the student reached during the debrief>"],
  "reflection_quality": "<low|medium|high — how specifically and honestly the student reflected>",
  "reflection_summary": "<1-2 sentences summarizing what the student realized>"
}

Rules:
- corrections: only REAL English-language mistakes present in the student's debrief messages (max 5, most impactful first). Never invent one; return [] if their English was clean. Ignore casual chat register — the debrief is informal, so only flag wording a US colleague would actually find wrong or confusing.
- commitments: only include intentions the STUDENT expressed or explicitly agreed to (max 3). A tutor suggestion the student ignored is NOT a commitment. Each must be checkable from a future transcript.
- Write commitments in English regardless of the language the student used.
- If the conversation contains no usable signal, return the schema with empty lists.
    """.trimIndent()

    fun formatChat(messages: List<Pair<String, String>>): String {
        return messages.filter { it.second.isNotBlank() }.joinToString("\n") { (role, text) ->
            if (role == "user") "STUDENT: ${text.trim()}" else "TUTOR: ${text.trim()}"
        }
    }

    fun learnerEngagement(messages: List<Pair<String, String>>, kickoffMessage: String = ""): Pair<Int, Int> {
        var turns = 0
        var chars = 0
        for ((role, text) in messages) {
            if (role != "user") continue
            val trimmed = text.trim()
            if (trimmed.isEmpty() || (kickoffMessage.isNotBlank() && trimmed == kickoffMessage.trim())) continue
            turns++
            chars += trimmed.length
        }
        return Pair(turns, chars)
    }

    fun worthExtracting(messages: List<Pair<String, String>>, kickoffMessage: String = ""): Boolean {
        val (turns, chars) = learnerEngagement(messages, kickoffMessage)
        return turns >= MIN_LEARNER_TURNS && chars >= MIN_LEARNER_CHARS
    }

    fun buildExtractionPrompt(messages: List<Pair<String, String>>, analysisSummaryJson: String?): String {
        var chatText = formatChat(messages)
        if (chatText.length > 12000) {
            chatText = "…(earlier turns truncated)…\n" + chatText.takeLast(12000)
        }
        val summary = analysisSummaryJson ?: "(not available)"
        return EXTRACTION_PROMPT.replace("{chat_text}", chatText.ifEmpty { "(empty)" })
            .replace("{analysis_summary}", summary)
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun sanitizeInsights(rawJson: String): DebriefInsights {
        var cleanJson = rawJson.replace(Regex("```[a-zA-Z]*\n?"), "").replace("```", "").trim()
        val firstBrace = cleanJson.indexOf('{')
        val lastBrace = cleanJson.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace >= firstBrace) {
            cleanJson = cleanJson.substring(firstBrace, lastBrace + 1)
        }
        return try {
            jsonParser.decodeFromString(DebriefInsights.serializer(), cleanJson)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            DebriefInsights()
        }
    }

    suspend fun extractDebriefInsights(
        backend: String,
        apiKey: String,
        modelId: String,
        messages: List<Pair<String, String>>,
        analysisSummaryJson: String?
    ): DebriefInsights {
        val prompt = buildExtractionPrompt(messages, analysisSummaryJson)
        val rawJson = AnalysisEngine.generateContent(backend, apiKey, modelId, prompt)
        return sanitizeInsights(rawJson)
    }

}
