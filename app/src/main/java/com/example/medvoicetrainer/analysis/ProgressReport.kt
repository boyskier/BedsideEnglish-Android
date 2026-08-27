package com.example.medvoicetrainer.analysis

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Ported from app/analysis/progress_report.py — progress report helpers focused on evidence of improvement. */

data class ProgressReport(
    val hasData: Boolean,
    val headline: String,
    val summary: String,
    val bullets: List<String>,
    val sessionCount: Int = 0,
    val recentAverageScore: Double? = null,
    val scoreDelta: Double? = null,
    val fillerDelta: Double? = null,
    val wpmDelta: Double? = null,
    val intelligibilityDelta: Double? = null,
    val activeErrors: Int = 0,
    val masteredErrors: Int = 0
)

object ProgressReportEngine {

    private val SCORE_KEYS = listOf(
        "grammar_score",
        "medical_accuracy_score",
        "clinical_reasoning_score",
        "professionalism_score",
        "fluency_score"
    )

    private fun avg(values: List<Double>): Double? {
        return if (values.isNotEmpty()) round(values.sum() / values.size * 10) / 10.0 else null
    }

    private fun toDoubleOrNull(v: Any?): Double? {
        return when (v) {
            null -> null
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun scoreAvg(session: Map<String, Any?>): Double? {
        val vals = SCORE_KEYS.mapNotNull { toDoubleOrNull(session[it]) }
        return avg(vals)
    }

    private fun analysisOf(session: Map<String, Any?>): JSONObject {
        val raw = session["raw_claude_response"]
        return when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            else -> JSONObject()
        }
    }

    private fun metricFromAnalysis(session: Map<String, Any?>, section: String, key: String): Double? {
        return try {
            val sectionObj = analysisOf(session).optJSONObject(section) ?: return null
            if (!sectionObj.has(key)) return null
            toDoubleOrNull(sectionObj.get(key))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    private fun windowDelta(values: List<Double>): Double? {
        if (values.size < 2) return null
        val split = max(1, min(5, if (values.size / 3 == 0) 1 else values.size / 3))
        val early = avg(values.take(split)) ?: return null
        val recent = avg(values.takeLast(split)) ?: return null
        return round((recent - early) * 10) / 10.0
    }

    fun buildProgressReport(
        allSessions: List<Map<String, Any?>>,
        errorStats: Map<String, Int> = emptyMap()
    ): ProgressReport {
        val sessions = ScoreDomains.clinicalSessions(allSessions)
            .filter { (it["raw_claude_response"] as? String)?.isNotEmpty() == true || it["raw_claude_response"] is JSONObject }
            .sortedBy { it["created_at"]?.toString() ?: "" }

        if (sessions.isEmpty()) {
            return ProgressReport(
                hasData = false,
                headline = "No progress report yet",
                summary = "Complete at least two analyzed sessions to see improvement evidence.",
                bullets = emptyList()
            )
        }

        val scoreValues = sessions.mapNotNull { scoreAvg(it) }
        val fillerValues = sessions.mapNotNull { metricFromAnalysis(it, "fluency_metrics", "filler_density") }
        val wpmValues = sessions.mapNotNull { metricFromAnalysis(it, "fluency_metrics", "wpm") }
        val intelligibilityValues = sessions.mapNotNull { metricFromAnalysis(it, "intelligibility", "score") }

        val scoreDelta = windowDelta(scoreValues)
        val fillerDelta = windowDelta(fillerValues)
        val wpmDelta = windowDelta(wpmValues)
        val intelDelta = windowDelta(intelligibilityValues)

        val bullets = mutableListOf<String>()
        if (scoreDelta != null) {
            val direction = if (scoreDelta >= 0) "up" else "down"
            bullets.add("Average practice score is $direction ${"%.1f".format(abs(scoreDelta))} points from your early sessions.")
        }
        if (fillerDelta != null) {
            val direction = if (fillerDelta <= 0) "down" else "up"
            bullets.add("Filler-word density is $direction ${"%.1f".format(abs(fillerDelta))} percentage points.")
        }
        if (wpmDelta != null) {
            val direction = if (wpmDelta >= 0) "up" else "down"
            bullets.add("Speaking pace is $direction ${"%.1f".format(abs(wpmDelta))} WPM.")
        }
        if (intelDelta != null) {
            val direction = if (intelDelta >= 0) "up" else "down"
            bullets.add("Intelligibility coaching score is $direction ${"%.1f".format(abs(intelDelta))} points.")
        }
        val mastered = errorStats["mastered"] ?: 0
        val active = errorStats["active"] ?: 0
        if (mastered != 0 || active != 0) {
            bullets.add("Error tracker: $mastered mastered, $active still active.")
        }
        if (bullets.isEmpty()) {
            bullets.add("Keep practicing: trend metrics need more sessions before they become meaningful.")
        }

        val recentAvg = if (scoreValues.isNotEmpty()) avg(scoreValues.takeLast(5)) else null
        var headline = "Your clinical English is building evidence"
        if (scoreDelta != null && scoreDelta > 0) {
            headline = "Your clinical English is improving"
        } else if (scoreDelta != null && scoreDelta < -0.5) {
            headline = "Recent sessions show a dip to review"
        }

        return ProgressReport(
            hasData = true,
            headline = headline,
            sessionCount = sessions.size,
            recentAverageScore = recentAvg,
            scoreDelta = scoreDelta,
            fillerDelta = fillerDelta,
            wpmDelta = wpmDelta,
            intelligibilityDelta = intelDelta,
            activeErrors = active,
            masteredErrors = mastered,
            summary = "${sessions.size} analyzed session(s). Recent average: ${recentAvg?.toString() ?: "N/A"}/10.",
            bullets = bullets
        )
    }

    fun formatProgressReport(report: ProgressReport): String {
        val lines = mutableListOf(
            "Bedside English Progress Report",
            report.headline,
            report.summary
        )
        for (bullet in report.bullets) {
            lines.add("- $bullet")
        }
        lines.add("Scores are practice estimates; trends across repeated sessions are more meaningful than one score.")
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
