package com.example.medvoicetrainer.analysis

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.LocalDateTime

object Diagnostic {

    private fun getScore(row: Map<String, Any?>, col: String): Double? {
        return try {
            val d = row[col]?.toString()?.toDouble()
            if (d != null && d > 10.0) d / 10.0 else d
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    fun buildDiagnosticProfile(sessions: List<Map<String, Any?>>): Map<String, Any> {
        val clinical = ScoreDomains.clinicalSessions(sessions)
        
        val scored = clinical.filter { row ->
            val grammar = getScore(row, "grammar_score")
            val medical = getScore(row, "medical_accuracy_score")
            val reasoning = getScore(row, "clinical_reasoning_score")
            val professionalism = getScore(row, "professionalism_score")
            val fluency = getScore(row, "fluency_score")
            grammar != null || medical != null || reasoning != null || professionalism != null || fluency != null
        }

        val diagnostics = scored.filter { it["case_id"]?.toString() == "diagnostic_english_baseline" }
        val source = if (diagnostics.isNotEmpty()) diagnostics.take(1) else scored.take(5)

        if (source.isEmpty()) {
            return mapOf(
                "has_profile" to false,
                "level" to "Not diagnosed",
                "summary" to "Run the 10-minute diagnostic to generate a personal roadmap.",
                "priorities" to listOf("Run diagnostic", "Complete one patient encounter", "Review first corrections")
            )
        }

        val cols = mapOf(
            "grammar" to "grammar_score",
            "medical vocabulary" to "medical_accuracy_score",
            "structure" to "clinical_reasoning_score",
            "rapport" to "professionalism_score",
            "fluency" to "fluency_score"
        )

        val averages = mutableMapOf<String, Double>()
        for ((label, col) in cols) {
            val vals = source.mapNotNull { getScore(it, col) }
            if (vals.isNotEmpty()) {
                val avg = vals.sum() / vals.size
                averages[label] = Math.round(avg * 10) / 10.0
            }
        }

        val overall = if (averages.isNotEmpty()) Math.round((averages.values.sum() / averages.size) * 10) / 10.0 else 0.0

        val level = when {
            overall >= 8.5 -> "C1 clinical communicator"
            overall >= 7.2 -> "B2+ clinical communicator"
            overall >= 6.0 -> "B1-B2 developing communicator"
            else -> "A2-B1 supported communicator"
        }

        val weakest = averages.entries.sortedBy { it.value }.take(3).map { it.key }
        val priorities = weakest.map { name -> "Raise $name from ${averages[name] ?: 0.0}/10" }

        val lastStr = source.firstOrNull()?.get("created_at")?.toString() ?: ""
        val lastDate = try {
            if (lastStr.isNotBlank()) {
                LocalDateTime.parse(lastStr, DateTimeFormatter.ISO_DATE_TIME).toLocalDate().toString()
            } else ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            lastStr.take(10)
        }

        return mapOf(
            "has_profile" to true,
            "level" to level,
            "overall" to overall,
            "averages" to averages,
            "last_diagnostic" to lastDate,
            "summary" to "Estimated level: $level. Overall practice score $overall/10.",
            "priorities" to priorities
        )
    }
}
