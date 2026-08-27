package com.example.medvoicetrainer.export

import com.example.medvoicetrainer.analysis.ScoreDomains
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Ported from app/export/share_report.py — de-identified share cards for user-facing progress. */
object ShareReport {

    private fun analysisOf(session: Map<String, Any?>): JSONObject {
        val raw = session["raw_claude_response"]
        return when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            else -> JSONObject()
        }
    }

    private fun avgScore(scores: JSONObject): Double? {
        val vals = mutableListOf<Double>()
        for (key in scores.keys()) {
            val v = scores.opt(key)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null) vals.add(d)
        }
        return if (vals.isNotEmpty()) Math.round(vals.sum() / vals.size * 10) / 10.0 else null
    }

    /** Return a PHI-safe text card for clipboard or social sharing. */
    fun buildShareCard(session: Map<String, Any?>): String {
        val everyday = ScoreDomains.isEverydaySession(session)
        val analysis = analysisOf(session)
        val scores = analysis.optJSONObject("overall_scores") ?: JSONObject()
        val avg = avgScore(scores) ?: run {
            // Compatibility for older Android rows whose evaluator stored only scalar Room
            // columns instead of Python's nested overall_scores object.
            val keys = if (everyday) {
                listOf("grammar_score", "medical_accuracy_score", "clinical_reasoning_score", "fluency_score")
            } else {
                listOf("grammar_score", "medical_accuracy_score", "clinical_reasoning_score", "professionalism_score", "fluency_score")
            }
            val values = keys.mapNotNull { key -> (session[key] as? Number)?.toDouble() }
                .filter { it > 0.0 }
            if (values.isEmpty()) null else Math.round(values.average() * 10) / 10.0
        }
        val fluency = analysis.optJSONObject("fluency_metrics") ?: JSONObject()
        val intel = analysis.optJSONObject("intelligibility") ?: JSONObject()
        val reliability = analysis.optJSONObject("score_reliability") ?: JSONObject()
        val corrections = analysis.optJSONArray("corrections")
        val correctionsCount = corrections?.length() ?: 0

        val createdAtRaw = session["created_at"]?.toString() ?: ""
        val created = try {
            OffsetDateTime.parse(createdAtRaw).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            createdAtRaw.take(10)
        }

        val mode = (session["mode"]?.toString() ?: "session")
        val lines = mutableListOf(
            "Bedside English Practice Card",
            "Date: ${created.ifEmpty { "N/A" }}",
            "Mode: ${mode.replaceFirstChar { it.uppercase() }}"
        )
        if (avg != null && !analysis.optBoolean("_locked", false)) {
            lines.add("Average practice score: ${"%.1f".format(avg)}/10")
        }
        val confidence = reliability.optString("confidence", "")
        if (confidence.isNotEmpty()) lines.add("Score confidence: $confidence (AI-estimated practice feedback)")
        if (fluency.has("wpm") && fluency.opt("wpm") != null) {
            lines.add("Fluency: ${fluency.opt("wpm")} WPM, fillers ${fluency.opt("filler_density") ?: 0}%")
        }
        val grade = intel.optString("grade", "")
        if (grade.isNotEmpty()) {
            val score = if (intel.has("score")) intel.opt("score").toString() else "N/A"
            lines.add("Communication clarity (transcript): $grade ($score/10)")
        }
        lines.add("Corrections saved: $correctionsCount")
        val summaryFeedback = analysis.optString("summary_feedback", "")
        if (summaryFeedback.isNotEmpty()) {
            val summary = summaryFeedback.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val truncated = summary.take(220) + if (summary.length > 220) "..." else ""
            lines.add("Focus: $truncated")
        }
        if (everyday) {
            lines.add("No personal conversation details or transcript included.")
        } else {
            lines.add("No patient-identifying details or transcript included.")
        }
        return lines.joinToString("\n")
    }

    /**
     * Return a de-identified weekly confidence card for sharing. No names, no transcript, no
     * patient details. Carries a subtle app reference so shares are discoverable.
     */
    fun buildWeeklyConfidenceCard(
        totalSpeakingMinutes: Double,
        sessionCount: Int,
        avgWpm: Double?,
        avgFillerRate: Double?,
        mastered: Int,
        active: Int,
        streak: Int
    ): String {
        val today = LocalDate.now().toString()
        val lines = mutableListOf(
            "Bedside English — Weekly Confidence Card",
            "Week ending: $today",
            "─".repeat(36)
        )
        if (sessionCount != 0) lines.add("Sessions this week:     $sessionCount")
        if (totalSpeakingMinutes != 0.0) lines.add("Speaking time:          ${Math.round(totalSpeakingMinutes)} min")
        if (avgWpm != null && avgWpm != 0.0) lines.add("Avg words/min:          ${avgWpm.toInt()}")
        if (avgFillerRate != null) lines.add("Filler rate:            ${"%.1f".format(avgFillerRate)}%")
        if (mastered != 0) lines.add("Errors graduated:       $mastered")
        if (active != 0) lines.add("Errors still training:  $active")
        if (streak > 0) lines.add("Streak:                 $streak day(s)")
        lines.add("─".repeat(36))
        lines.add("No transcript or identifying details included.")
        lines.add("Bedside English — free, private, BYO-key.")
        return lines.joinToString("\n")
    }
}
