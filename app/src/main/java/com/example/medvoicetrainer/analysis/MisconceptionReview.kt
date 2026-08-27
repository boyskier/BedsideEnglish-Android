package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MisconceptionFinding(
    val turnIndex: Int,
    val learnerClaim: String,
    val classification: String,
    val topic: String,
    val severity: String,
    val verdict: String,
    val correctConcept: String,
    val whyInThisPatient: List<String>,
    val reasoningRepair: String,
    val scoreImpact: List<String>,
    val confidence: Double,
    val deepReviewJson: String = "",
) {
    val key: String = "$turnIndex:${learnerClaim.lowercase(Locale.ROOT).trim()}"

    fun toJson(): JSONObject = JSONObject()
        .put("turn_index", turnIndex)
        .put("learner_claim", learnerClaim)
        .put("classification", classification)
        .put("topic", topic)
        .put("severity", severity)
        .put("verdict", verdict)
        .put("correct_concept", correctConcept)
        .put("why_in_this_patient", JSONArray(whyInThisPatient))
        .put("reasoning_repair", reasoningRepair)
        .put("score_impact", JSONArray(scoreImpact))
        .put("confidence", confidence)
        .also { target ->
            if (deepReviewJson.isNotBlank()) {
                runCatching { target.put("deep_review", JSONObject(deepReviewJson)) }
            }
        }
}

/**
 * Evidence gate and second-pass teaching prompt for post-encounter clinical misconceptions.
 * The first scoring call proposes findings; this object strips anything that is not grounded in
 * an exact learner quote. A second call is made only when the learner asks for a deeper explanation.
 */
object MisconceptionReview {
    private val classifications = setOf(
        "incorrect_claim", "faulty_reasoning", "unsafe_recommendation",
        "overgeneralization", "terminology_confusion",
    )
    private val severities = setOf("low", "moderate", "high", "critical")
    private val verdicts = setOf("incorrect_for_this_case", "misleading", "unsafe")
    private val scoreKeys = setOf("medical_accuracy", "clinical_reasoning")
    const val MIN_CONFIDENCE = 0.80

    fun sanitizeEvaluationArray(
        raw: JSONArray?,
        transcript: List<Pair<String, String>>? = null,
    ): JSONArray = JSONArray().apply {
        parse(raw, transcript).take(5).forEach { put(it.toJson()) }
    }

    fun parseJson(rawJson: String): List<MisconceptionFinding> = try {
        parse(JSONArray(rawJson.ifBlank { "[]" }), null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    fun parse(
        raw: JSONArray?,
        transcript: List<Pair<String, String>>? = null,
    ): List<MisconceptionFinding> {
        if (raw == null) return emptyList()
        val results = mutableListOf<MisconceptionFinding>()
        val seen = mutableSetOf<String>()
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val turnIndex = item.optInt("turn_index", -1)
            val claim = item.optString("learner_claim").trim()
            val confidence = item.optDouble("confidence", 0.0)
            if (turnIndex < 0 || claim.isBlank() || !confidence.isFinite() || confidence < MIN_CONFIDENCE) continue

            if (transcript != null) {
                val turn = transcript.getOrNull(turnIndex) ?: continue
                val role = turn.first.trim().lowercase(Locale.ROOT)
                if (role !in setOf("doctor", "user", "learner")) continue
                if (!containsExactQuote(turn.second, claim)) continue
            }

            val classification = item.optString("classification").lowercase(Locale.ROOT)
            val severity = item.optString("severity").lowercase(Locale.ROOT)
            val verdict = item.optString("verdict").lowercase(Locale.ROOT)
            if (classification !in classifications || severity !in severities || verdict !in verdicts) continue

            val correctConcept = item.optString("correct_concept").trim()
            if (correctConcept.isBlank()) continue
            val why = stringArray(item.optJSONArray("why_in_this_patient"), 4)
            val scoreImpact = stringArray(item.optJSONArray("score_impact"), 2)
                .map { it.lowercase(Locale.ROOT) }
                .filter { it in scoreKeys }
                .distinct()
            if (scoreImpact.isEmpty()) continue

            val finding = MisconceptionFinding(
                turnIndex = turnIndex,
                learnerClaim = claim.take(500),
                classification = classification,
                topic = item.optString("topic").trim().take(100),
                severity = severity,
                verdict = verdict,
                correctConcept = correctConcept.take(1_000),
                whyInThisPatient = why,
                reasoningRepair = item.optString("reasoning_repair").trim().take(1_000),
                scoreImpact = scoreImpact,
                confidence = confidence.coerceIn(MIN_CONFIDENCE, 1.0),
                deepReviewJson = item.optJSONObject("deep_review")?.toString().orEmpty(),
            )
            if (seen.add(finding.key)) results.add(finding)
        }
        return results
    }

    fun buildDeepReviewPrompts(
        findingJson: String,
        caseJson: String,
        nativeLanguage: String,
    ): Pair<String, String> {
        val caseRoot = runCatching { JSONObject(caseJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val curated = caseRoot.optJSONObject("clinical_knowledge")
        val grounding = curated ?: JSONObject().apply {
            val selectedCaseDetails = JSONObject()
            listOf(
                "chief_complaint", "hpi_details", "pmh", "medications", "social_hx",
                "reference_soap", "learning_objectives", "teaching",
            ).forEach { key ->
                if (caseRoot.has(key)) selectedCaseDetails.put(key, caseRoot.opt(key))
            }
            put("fallback_case_details", selectedCaseDetails)
            put(
                "fallback_warning",
                "No curated clinical_knowledge is available. Be conservative and explicitly state uncertainty.",
            )
        }
        val system = """
            You are a careful clinical educator repairing one misconception already identified in a
            simulated-patient transcript. You are teaching, not rescoring the encounter and not giving
            personal medical advice. Ground every statement in the supplied case knowledge. If curated
            clinical_knowledge is absent or the fallback case details do not support a detail, omit it or
            state the uncertainty. Do not invent citations, quotations, doses, contraindications, or patient
            facts. Explain in the learner's requested language where practical, while preserving standard
            English medical terms that help clinical-English learning.

            Return ONLY valid JSON with this exact schema:
            {
              "explanation": "clear explanation of the misconception",
              "patient_specific_reasoning": ["how a supplied clue changes the reasoning"],
              "general_rule": "portable rule for the next patient",
              "boundaries_and_exceptions": ["when the rule may not apply or needs senior/local guidance"],
              "memory_hook": "short memorable contrast",
              "check_question": "one retrieval question",
              "check_answer": "brief answer",
              "uncertainty_note": "what could not be established from supplied ground truth, or empty string"
            }
        """.trimIndent()
        val user = """
            LEARNER LANGUAGE: $nativeLanguage

            CONFIRMED, TRANSCRIPT-GROUNDED FINDING:
            $findingJson

            CASE KNOWLEDGE ${if (curated != null) "(CURATED)" else "(FALLBACK; NOT CURATED)"}:
            ${grounding.toString(2)}

            Produce the focused concept-repair JSON now. Do not repeat numeric scores.
        """.trimIndent()
        return system to user
    }

    fun sanitizeDeepReview(raw: String): JSONObject {
        val clean = raw.replace("```json", "").replace("```", "").trim()
        val root = JSONObject(clean)
        val explanation = root.optString("explanation").trim()
        val generalRule = root.optString("general_rule").trim()
        val question = root.optString("check_question").trim()
        val answer = root.optString("check_answer").trim()
        require(explanation.isNotBlank() && generalRule.isNotBlank() && question.isNotBlank() && answer.isNotBlank()) {
            "Clinical review response was incomplete"
        }
        return JSONObject()
            .put("explanation", explanation.take(2_000))
            .put("patient_specific_reasoning", JSONArray(stringArray(root.optJSONArray("patient_specific_reasoning"), 5)))
            .put("general_rule", generalRule.take(1_000))
            .put("boundaries_and_exceptions", JSONArray(stringArray(root.optJSONArray("boundaries_and_exceptions"), 4)))
            .put("memory_hook", root.optString("memory_hook").trim().take(500))
            .put("check_question", question.take(1_000))
            .put("check_answer", answer.take(1_000))
            .put("uncertainty_note", root.optString("uncertainty_note").trim().take(1_000))
    }

    private fun containsExactQuote(turn: String, quote: String): Boolean {
        fun normalize(value: String): String = value
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
        return normalize(turn).contains(normalize(quote))
    }

    private fun stringArray(array: JSONArray?, limit: Int): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until minOf(array.length(), limit)) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.take(1_000)?.let(::add)
        }
    }
}
