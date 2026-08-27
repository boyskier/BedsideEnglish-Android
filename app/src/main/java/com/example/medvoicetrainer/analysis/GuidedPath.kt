package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/** Ported from app/analysis/guided_path.py — beginner guided path for clinical survival English. */

data class GuidedPathRoute(
    val tab: String,
    val system: String? = null,
    val kind: String? = null,
    val scenarioId: String? = null,
    val caseId: String? = null
)

data class GuidedStep(
    val key: String,
    val title: String,
    val target: String,
    val route: GuidedPathRoute,
    val completed: Boolean,
    val status: String
)

data class GuidedPathResult(
    val completed: Int,
    val total: Int,
    val percentage: Double,
    val steps: List<GuidedStep>,
    val nextStep: GuidedStep?
)

object GuidedPath {

    private data class StepDef(
        val key: String,
        val title: String,
        val target: String,
        val route: GuidedPathRoute,
        val caseIds: Set<String>
    )

    private val GUIDED_STEPS = listOf(
        StepDef(
            key = "demo",
            title = "Try a no-API demo",
            target = "See the full loop: scripted patient, feedback, mistake tracking.",
            route = GuidedPathRoute(tab = "exam", kind = "diagnostic", scenarioId = "diagnostic_english_baseline"),
            caseIds = setOf("diagnostic_english_baseline")
        ),
        StepDef(
            key = "opening",
            title = "Open and build rapport",
            target = "Introduce yourself, acknowledge worry, ask permission.",
            route = GuidedPathRoute(tab = "encounter", system = "foundations", caseId = "found_001"),
            caseIds = setOf("found_001", "found_002")
        ),
        StepDef(
            key = "pain_history",
            title = "Take a focused symptom history",
            target = "Ask onset, severity, radiation, associated symptoms, ICE.",
            route = GuidedPathRoute(tab = "encounter", system = "cardio", caseId = "cardio_001"),
            caseIds = setOf("cardio_001", "cardio_002")
        ),
        StepDef(
            key = "plain_language",
            title = "Explain without jargon",
            target = "Turn medical terms into patient-friendly English.",
            route = GuidedPathRoute(tab = "encounter", system = "drills", caseId = "drill_plain_language"),
            caseIds = setOf("drill_plain_language")
        ),
        StepDef(
            key = "mediation",
            title = "Translate between patient and team",
            target = "Patient words to chart/team English, then back to patient English.",
            route = GuidedPathRoute(tab = "encounter", system = "drills", caseId = "drill_mediation_patient_team"),
            caseIds = setOf("drill_mediation_patient_team", "drill_register_switching")
        ),
        StepDef(
            key = "survival",
            title = "Clarify unsafe or unclear instructions",
            target = "Ask for repetition, read back numbers, and speak up safely.",
            route = GuidedPathRoute(tab = "encounter", system = "drills", caseId = "drill_clarify_orders"),
            caseIds = setOf("drill_clarify_orders", "drill_speak_up", "drill_rapid_readback")
        ),
        StepDef(
            key = "sbar",
            title = "Give a 45-second SBAR",
            target = "Sound organized when calling a senior or signing out.",
            route = GuidedPathRoute(tab = "encounter", system = "drills", caseId = "handover_night_shift"),
            caseIds = setOf("handover_night_shift")
        )
    )

    private fun caseIdOf(session: Map<String, Any?>): String {
        (session["case_id"] as? String)?.let { if (it.isNotEmpty()) return it }
        val raw = session["raw_case_json"]
        return try {
            val obj = when (raw) {
                is String -> JSONObject(raw)
                is JSONObject -> raw
                else -> JSONObject()
            }
            obj.optString("id", "")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (raw is String) {
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: ""
            } else ""
        }
    }

    fun buildGuidedPath(sessions: List<Map<String, Any?>>, maxItems: Int = 7): GuidedPathResult {
        val practiced = sessions.map { caseIdOf(it) }.filter { it.isNotEmpty() }.toSet()

        var firstUnfinished: String? = null
        var completedCount = 0
        val steps = mutableListOf<GuidedStep>()

        for (step in GUIDED_STEPS) {
            val done = step.caseIds.intersect(practiced).isNotEmpty()
            if (done) {
                completedCount++
            } else if (firstUnfinished == null) {
                firstUnfinished = step.key
            }
            val status = if (done) "Done" else if (step.key == firstUnfinished) "Next" else "Locked"
            steps.add(
                GuidedStep(
                    key = step.key,
                    title = step.title,
                    target = step.target,
                    route = step.route,
                    completed = done,
                    status = status
                )
            )
        }

        val total = GUIDED_STEPS.size
        val percentage = if (total > 0) Math.round(completedCount.toDouble() / total * 1000) / 10.0 else 0.0
        val nextStep = steps.firstOrNull { !it.completed } ?: steps.lastOrNull()
        val limitedSteps = steps.take(maxItems).toMutableList()
        if (nextStep != null && limitedSteps.none { it.key == nextStep.key }) {
            limitedSteps.add(nextStep)
        }

        return GuidedPathResult(
            completed = completedCount,
            total = total,
            percentage = percentage,
            steps = limitedSteps,
            nextStep = nextStep
        )
    }
}
