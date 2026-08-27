package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.ui.EvaluationResult
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Python-compatible half-to-even rounding (`round(x, n)`).
 *
 * Kotlin's `Math.round` is half-*up*; Python's built-in `round()` is
 * half-to-*even* (banker's rounding). They diverge on exact `.xx5` ties
 * (e.g. `round(6.25, 1)` → `6.2` in Python, `6.3` with half-up), which the
 * scoring/self-delta golden vectors deliberately exercise. Constructing the
 * BigDecimal from the primitive `Double` (its exact binary value, not a decimal
 * re-parse) mirrors what CPython's `round()` sees.
 */
internal fun pyRound(value: Double, digits: Int): Double =
    BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

object ScoringCalibration {
    enum class Reliability {
        HIGH, MEDIUM, LOW
    }

    fun calibrate(
        rawEval: EvaluationResult
    ): Pair<EvaluationResult, Reliability> {
        val wordCount = rawEval.wordCount
        val checkedCount = rawEval.checklistChecked.count { it.second }
        val totalChecklist = rawEval.checklistChecked.size
        val checklistRatio = if (totalChecklist > 0) checkedCount.toDouble() / totalChecklist else 1.0

        var reliability = Reliability.HIGH

        if (wordCount < 50) {
            reliability = if (wordCount >= 30 && checklistRatio >= 0.8 && totalChecklist >= 5) {
                Reliability.MEDIUM
            } else {
                Reliability.LOW
            }
        } else if (wordCount < 150) {
            reliability = Reliability.MEDIUM
        }

        if (checklistRatio < 0.2 && reliability == Reliability.HIGH) {
            reliability = Reliability.MEDIUM
        }

        if (rawEval.summaryFeedback == "No checklist case.") {
            reliability = Reliability.MEDIUM
        }

        return Pair(rawEval, reliability)
    }

    private const val NOTICE_CLINICAL =
        "Scores are AI-estimated practice feedback, not an official OET, OSCE, " +
        "residency, or institutional result. Use checklist evidence and trend " +
        "over repeated attempts to judge readiness."
    private const val NOTICE_EVERYDAY =
        "Scores are AI-estimated everyday speaking feedback, not a proficiency test. " +
        "Use the cited turns, unaided listening evidence, and repeated retries to judge progress."

    /**
     * Faithful port of app/analysis/scoring_calibration.py's `build_score_reliability`.
     *
     * Returns a reliability *badge* for AI-estimated practice scores: how much
     * evidence the app had when producing the estimate (word-count + required-
     * checklist evidence rate + presence of numeric scores). This does NOT make a
     * score official. The key names, thresholds (80/180 words, 0.65/0.75/0.5
     * evidence gates), flag order, everyday-vs-clinical band labels, and rounding
     * all match the Python original — golden-vector backed via
     * `scoring_calibration.golden.json` (see the matching `ScoringCalibrationGoldenTest`).
     *
     * The previous `calibrate(EvaluationResult)` is retained for compatibility tests; live
     * feedback uses this richer method as evidence-confidence metadata. Neither method changes
     * the evaluator's numeric scores.
     */
    fun buildScoreReliability(
        analysis: Map<String, Any?>?,
        transcript: List<Map<String, Any?>>?,
        evalData: Map<String, Any?>? = null,
        caseData: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val analysisMap = analysis ?: emptyMap()
        val transcriptList = transcript ?: emptyList()
        val caseDataMap = caseData ?: emptyMap()
        val everyday = ScoreDomains.isEverydayCase(caseDataMap)

        @Suppress("UNCHECKED_CAST")
        val scores = (analysisMap["overall_scores"] as? Map<String, Any?>) ?: emptyMap()
        val numericScores = scores.values.mapNotNull { asFloat(it) }
        val avgScore: Double? =
            if (numericScores.isNotEmpty()) pyRound(numericScores.sum() / numericScores.size, 1) else null

        @Suppress("UNCHECKED_CAST")
        val checklist = (analysisMap["checklist_results"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
        val required = checklist.filter { truthy(it["required"]) }
        val evidenceItems = required.filter {
            it["passed"] == false || str(it["evidence"]).trim().isNotEmpty()
        }
        val evidenceRate: Double = when {
            required.isNotEmpty() -> evidenceItems.size.toDouble() / required.size
            everyday -> if (numericScores.isNotEmpty()) 1.0 else 0.0
            else -> if (checklist.isNotEmpty()) 1.0 else 0.0
        }
        val words = userWordCount(transcriptList)

        val flags = mutableListOf<String>()
        if (words < 80) flags.add("short_transcript")
        if (required.isNotEmpty() && evidenceRate < 0.65) flags.add("limited_checklist_evidence")
        if (numericScores.isEmpty()) flags.add("missing_scores")
        if (truthy(caseDataMap["exam_mode"])) flags.add("practice_estimate_not_official")

        val confidence = when {
            "missing_scores" in flags -> "low"
            words >= 180 && evidenceRate >= 0.75 -> "high"
            words >= 80 && evidenceRate >= 0.5 -> "medium"
            else -> "low"
        }

        var practiceBand: String? = null
        if (avgScore != null) {
            val percent = avgScore * 10.0
            practiceBand = when {
                everyday && percent >= 80 -> "strong real-life response"
                everyday && percent >= 70 -> "functional with a clear next step"
                everyday && percent >= 60 -> "developing"
                everyday -> "needs guided retry"
                percent >= 80 -> "strong"
                percent >= 70 -> "likely pass range"
                percent >= 60 -> "borderline"
                else -> "high risk"
            }
        }

        // Insertion order mirrors the Python dict so the canonical serialization matches.
        return linkedMapOf(
            "confidence" to confidence,
            "avg_score" to avgScore,
            "practice_percent" to if (avgScore != null) pyRound(avgScore * 10.0, 1) else null,
            "practice_band" to practiceBand,
            "evidence_rate" to pyRound(evidenceRate, 2),
            "user_word_count" to words,
            "flags" to flags,
            "notice" to if (everyday) NOTICE_EVERYDAY else NOTICE_CLINICAL,
        )
    }

    private fun asFloat(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun userWordCount(transcript: List<Map<String, Any?>>): Int =
        transcript.sumOf { t ->
            if (t["role"] == "user") wordCount(str(t["text"])) else 0
        }

    /** Python `str.split()` semantics: split on any whitespace run, drop empties. */
    private fun wordCount(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }

    private fun str(v: Any?): String = (v as? String) ?: ""

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "false" && v != "0"
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }
}
