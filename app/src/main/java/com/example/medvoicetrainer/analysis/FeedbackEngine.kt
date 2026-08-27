package com.example.medvoicetrainer.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object FeedbackEngine {
    fun mockEverydayResult(): Map<String, Any?> {
        return mapOf(
            "overall_scores" to mapOf(
                "naturalness" to 7.5,
                "interaction" to 7.0,
                "comprehension_repair" to 7.0,
                "fluency" to 6.5
            ),
            "checklist_results" to emptyList<Any>(),
            "corrections" to listOf(
                mapOf(
                    "turn_index" to 1,
                    "original" to "I want this one.",
                    "corrected" to "I'll go with this one, thanks.",
                    "explanation" to "This is a natural, polite choice in an everyday interaction.",
                    "category" to "register"
                )
            ),
            "anki_cards" to listOf(
                mapOf(
                    "front" to "Choose something naturally",
                    "back" to "I'll go with this one, thanks.",
                    "tags" to listOf("everyday-english", "interaction")
                )
            ),
            "soap_note" to null,
            "summary_feedback" to "You completed the everyday interaction clearly. Practise one shorter, more natural version, then reuse it in a different real-life scene."
        )
    }

    fun mockLockedDemoResult(everyday: Boolean = false): Map<String, Any?> {
        return mapOf(
            "_demo" to true,
            "overall_scores" to emptyMap<String, Any>(),
            "checklist_results" to emptyList<Any>(),
            "soap_note" to null,
            "corrections" to emptyList<Any>(),
            "anki_cards" to emptyList<Any>(),
            "shadowing_items" to emptyList<Any>(),
            "summary_feedback" to if (everyday) {
                "This was a guided sample scenario with prepared lines. It is not an assessment " +
                    "of your English. Add a free Gemini key to practise with your own voice and " +
                    "receive everyday-speaking feedback."
            } else {
                "This was a guided sample scenario. The doctor and patient lines were prepared " +
                    "examples, so these are not results for your own English. Add a free Gemini " +
                    "key to practise with your voice and receive AI feedback."
            }
        )
    }

    fun mockRun(caseDataJson: String, lockedDemo: Boolean = false): Map<String, Any?> {
        val isEveryday = try {
            val el = Json.parseToJsonElement(caseDataJson).jsonObject
            val tags = el["tags"]?.jsonPrimitive?.content ?: ""
            tags.contains("everyday-english") || tags.contains("everyday") || (el["id"]?.jsonPrimitive?.content?.startsWith("everyday_") == true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            false
        }

        if (lockedDemo) {
            return mockLockedDemoResult(isEveryday)
        }
        return mockEverydayResult()
    }

    /**
     * Faithful port of app/analysis/feedback_engine.py's `_compute_self_delta`.
     *
     * Per-metric (AI score − student self-score), tolerant of the fluency-key
     * alias: a `fluency` AI metric falls back to a `communication_fluency`
     * self-score (and vice-versa) when the exact key is absent. Metrics with no
     * comparable self-score, or non-numeric values on either side, are skipped.
     * Iteration order follows `aiScores` so the output order matches Python.
     * Rounding is Python's half-to-even (`round(x, 1)`) via [pyRound] — golden-vector
     * backed via `feedback_engine.golden.json`.
     *
     * Wiring: called from `FeedbackScreen`'s Self-Assessment section when the learner submits
     * their sliders (post-summary, matching feedback_window.py's client-side delta tab — the
     * AI analysis itself still runs with `selfScores = null`, so the delta is computed on-device
     * from the already-returned AI scores rather than re-analysing). The pre-analysis prompt path
     * (`AnalysisPromptBuilder`) can still receive self-scores separately if a future flow collects
     * them before analysis; both mirror Python's `feedback_engine` delta behaviour.
     */
    fun computeSelfDelta(
        aiScores: Map<String, Any?>,
        selfScores: Map<String, Any?>,
    ): Map<String, Double> {
        val delta = LinkedHashMap<String, Double>()
        for ((key, aiVal) in aiScores) {
            val aiF = asFloat(aiVal) ?: continue
            var selfVal = selfScores[key]
            if (selfVal == null && (key == "fluency" || key == "communication_fluency")) {
                selfVal = selfScores[if (key == "fluency") "communication_fluency" else "fluency"]
            }
            if (selfVal == null) continue
            val selfF = asFloat(selfVal) ?: continue
            delta[key] = pyRound(aiF - selfF, 1)
        }
        return delta
    }

    private fun asFloat(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
