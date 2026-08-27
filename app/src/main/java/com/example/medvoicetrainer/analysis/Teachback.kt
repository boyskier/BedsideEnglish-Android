package com.example.medvoicetrainer.analysis

import android.content.Context
import org.json.JSONObject

/**
 * Ported from app/analysis/teachback.py — Lecture Teach-back (Feynman mode): personas,
 * lecture condensing, dynamic case building. See PromptBuilder for the shared case-dict shape.
 */

data class TeachbackPersona(
    val id: String,
    val name: String,
    val description: String,
    val coachingMode: Boolean,
    val kickoffText: String?,
    val evalTemplate: String,
    val personaTemplate: String
)

object Teachback {

    /** Material longer than this (chars) almost certainly wants condensing before a voice session. */
    const val CONDENSE_RECOMMENDED_OVER = 12_000

    /** Cap on stored/injected material so a raw-material session can't blow up the live prompt. */
    const val MAX_MATERIAL_CHARS = 24_000

    val CONDENSE_INSTRUCTIONS = """
        You are helping a first-year medical student prepare to TEACH a lecture aloud in English (Feynman technique). Condense the lecture material below into a compact teaching outline, in English, under 600 words, with exactly these sections:

        TOPIC: one line naming the topic.
        BIG PICTURE: 2-3 sentences a student should open with.
        KEY CONCEPTS: 5-10 numbered concepts; for each, 1-2 plain-English sentences capturing the mechanism or idea (keep essential technical terms, define them briefly).
        KEY FACTS & NUMBERS: the must-know values, classifications, or named items as short bullets.
        LIKELY EXAM QUESTIONS: 6-10 short questions an examiner would ask, ordered from basic recall to deeper 'why/what-if'.

        Output plain text only (no markdown tables, no commentary before or after).
    """.trimIndent()

    /** Teach-back personas from assets/cases/teachback/, sorted by name. */
    fun loadPersonas(context: Context): List<TeachbackPersona> {
        val personas = mutableListOf<TeachbackPersona>()
        val files = try {
            context.assets.list("cases/teachback")?.toList() ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        for (fname in files) {
            if (!fname.endsWith(".json")) continue
            try {
                val text = context.assets.open("cases/teachback/$fname").bufferedReader().use { it.readText() }
                val data = JSONObject(text)
                if (data.has("persona_template") && data.optString("persona_template").isNotEmpty()) {
                    personas.add(
                        TeachbackPersona(
                            id = data.optString("id", "teachback"),
                            name = data.optString("name", ""),
                            description = data.optString("description", ""),
                            coachingMode = data.optBoolean("coaching_mode", false),
                            kickoffText = if (data.has("kickoff_text")) data.optString("kickoff_text") else null,
                            evalTemplate = data.optString("eval_template", "teachback"),
                            personaTemplate = data.optString("persona_template", "")
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // skip malformed persona file
            }
        }
        personas.sortBy { it.name }
        return personas
    }

    /**
     * Dynamic case JSON compatible with SessionBase/PromptBuilder. The material is substituted
     * into the persona (str.replace semantics — lecture text routinely contains braces, so this
     * is a literal substring replace, not a format template) and stored as `lecture_material` so
     * post-session analysis can see it as ground truth.
     */
    fun buildTeachbackCase(persona: TeachbackPersona, material: String): JSONObject {
        val trimmedMaterial = material.trim().take(MAX_MATERIAL_CHARS)
        val personaText = persona.personaTemplate.replace("{material}", trimmedMaterial)

        val case = JSONObject()
        case.put("id", persona.id)
        case.put("system", "teachback")
        case.put("difficulty", "beginner")
        case.put("eval_template", persona.evalTemplate)
        case.put("learner_level", "preclinical")
        case.put("coaching_mode", persona.coachingMode)
        case.put("patient_name", persona.name.ifEmpty { "Teach-back" })
        case.put("chief_complaint", "Lecture teach-back — explain your own study material aloud")
        case.put("persona_override", personaText)
        case.put("kickoff_text", persona.kickoffText)
        case.put("lecture_material", trimmedMaterial)
        case.put("suggested_questions", org.json.JSONArray())

        val teachingMoves = JSONObject()
        teachingMoves.put("name", "Teaching moves")
        teachingMoves.put(
            "phrases",
            org.json.JSONArray(
                listOf(
                    "The big picture is…",
                    "In simple terms, that means…",
                    "Think of it like…  (analogy)",
                    "The reason this happens is…",
                    "Let me put that another way.",
                    "To sum up the whole topic…"
                )
            )
        )
        case.put("phrase_categories", org.json.JSONArray().put(teachingMoves))
        return case
    }

    fun loadTeachbackEval(context: Context): JSONObject? {
        return try {
            val text = context.assets.open("eval/teachback.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /** The full prompt the student pastes into their own AI subscription (zero API cost path). */
    fun buildClipboardPrompt(material: String): String {
        return "$CONDENSE_INSTRUCTIONS\n\nLECTURE MATERIAL:\n${material.trim()}"
    }

    /**
     * Condense the lecture with the user's configured feedback backend. Returns the outline text.
     * Callers should catch exceptions the same way the analysis call is handled (missing key etc).
     */
    suspend fun condenseViaApi(
        backend: String,
        apiKey: String,
        model: String,
        material: String
    ): String {
        val prompt = "$CONDENSE_INSTRUCTIONS\n\n" +
            "Return ONLY a valid JSON object: {\"outline\": \"<the plain-text outline>\"}\n\n" +
            "LECTURE MATERIAL:\n${material.trim()}"
        val rawText = AnalysisEngine.generateContent(backend, apiKey, model, prompt)
        val outline = try {
            JSONObject(rawText).optString("outline", "")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
        return outline.ifBlank { rawText }.trim()
    }
}
