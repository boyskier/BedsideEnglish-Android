package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/**
 * Ported from app/analysis/learning_roadmap.py's build_us_clinical_english_roadmap.
 *
 * Previously this object was `getRoadmapLevel(sessionsCount)` — an invented "Level 1-5" stub
 * with no relation to the real Python file, the same "wrongly marked Y" pattern flagged for
 * l1_stats.py in MIGRATION_MASTER.md. Replaced with a faithful port of the real skill-area
 * roadmap: prioritized cards driven by weakest recent metric, due SRS count, and practice history.
 *
 * build_post_session_practice_plan (the feedback-window "what's next" plan) is ported below
 * (see [LearningRoadmap.buildPostSessionPracticePlan]).
 */

/** One "what to practice next" card in the feedback window (app/analysis/learning_roadmap.py). */
data class PracticePlanCard(
    val title: String,
    val target: String,
    val reason: String,
    val cta: String
)

data class RoadmapRoute(
    val tab: String,
    val system: String? = null,
    val caseId: String? = null,
    val action: String? = null,
    val category: String? = null
)

data class RoadmapCard(
    val key: String,
    val title: String,
    val target: String,
    val cta: String,
    val route: RoadmapRoute,
    val reason: String,
    val status: String,
    val priority: Int
)

object LearningRoadmap {

    private val METRIC_LABELS = mapOf(
        "grammar" to "Grammar",
        "medical_accuracy" to "Medical vocabulary",
        "clinical_reasoning" to "Structure",
        "professionalism" to "Rapport",
        "communication_fluency" to "Fluency",
        "fluency" to "Fluency"
    )

    private data class SkillArea(
        val key: String,
        val title: String,
        val target: String,
        val cta: String,
        val route: RoadmapRoute,
        val caseIds: Set<String>,
        val weakMetrics: Set<String>,
        val basePriority: Int
    )

    private val SKILL_AREAS = listOf(
        SkillArea(
            key = "attending_presentation",
            title = "Present to a Senior",
            target = "Turn a completed patient interview into a focused oral case presentation.",
            cta = "Present Latest Patient",
            route = RoadmapRoute(tab = "encounter", action = "present_latest"),
            caseIds = emptySet(),
            weakMetrics = setOf("clinical_reasoning", "professionalism", "communication_fluency"),
            basePriority = 78
        ),
        SkillArea(
            key = "foundations",
            title = "Clinical English Foundations",
            target = "Warm openings, symptom questions, empathy, and clean sentence frames.",
            cta = "Start Foundations",
            route = RoadmapRoute(tab = "encounter", system = "foundations"),
            caseIds = emptySet(),
            weakMetrics = setOf("grammar", "communication_fluency", "fluency"),
            basePriority = 82
        ),
        SkillArea(
            key = "plain_language",
            title = "Plain-Language Explanations",
            target = "Explain jargon like hypertension, biopsy, fasting, and side effect in patient English.",
            cta = "Start Plain-Language Drill",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "drill_plain_language"),
            caseIds = setOf("drill_plain_language"),
            weakMetrics = setOf("medical_accuracy", "professionalism"),
            basePriority = 76
        ),
        SkillArea(
            key = "register_switching",
            title = "Register Switching",
            target = "Say the same idea for a patient, a nurse/team member, and an attending.",
            cta = "Start Register Drill",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "drill_register_switching"),
            caseIds = setOf("drill_register_switching"),
            weakMetrics = setOf("medical_accuracy", "clinical_reasoning", "professionalism"),
            basePriority = 74
        ),
        SkillArea(
            key = "clinical_mediation",
            title = "Clinical Mediation",
            target = "Convert patient words to team/chart English, then back to patient language.",
            cta = "Start Mediation Drill",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "drill_mediation_patient_team"),
            caseIds = setOf("drill_mediation_patient_team"),
            weakMetrics = setOf("medical_accuracy", "clinical_reasoning", "professionalism"),
            basePriority = 72
        ),
        SkillArea(
            key = "clarify_orders",
            title = "Clinical Survival English",
            target = "Clarify unclear instructions, read back numbers, and speak up safely.",
            cta = "Start Clarifying Drill",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "drill_clarify_orders"),
            caseIds = setOf("drill_clarify_orders"),
            weakMetrics = setOf("communication_fluency", "fluency", "professionalism"),
            basePriority = 71
        ),
        SkillArea(
            key = "listening_relay",
            title = "Listening and Read-Back",
            target = "Catch doses, timing, abbreviations, and instructions, then repeat them accurately.",
            cta = "Start Read-Back Drill",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "drill_rapid_readback"),
            caseIds = setOf("drill_rapid_readback", "drill_listening_instructions", "drill_listening_handover", "drill_listening"),
            weakMetrics = setOf("communication_fluency", "fluency"),
            basePriority = 70
        ),
        SkillArea(
            key = "sbar_handover",
            title = "SBAR and Team Communication",
            target = "Sound organized when calling a senior, signing out, or asking for help.",
            cta = "Practice SBAR",
            route = RoadmapRoute(tab = "encounter", system = "drills", caseId = "handover_night_shift"),
            caseIds = setOf("handover_night_shift"),
            weakMetrics = setOf("clinical_reasoning", "professionalism"),
            basePriority = 64
        ),
        SkillArea(
            key = "img_interview",
            title = "IMG Residency Interview English",
            target = "Tell specific stories, explain transitions, and answer IMG-specific questions clearly.",
            cta = "Open IMG Interview",
            route = RoadmapRoute(tab = "interview", category = "img_specific"),
            caseIds = emptySet(),
            weakMetrics = setOf("professionalism", "grammar"),
            basePriority = 58
        )
    )

    private fun metricAverages(sessions: List<Map<String, Any?>>): Map<String, Double> {
        val cols = mapOf(
            "grammar" to "grammar_score",
            "medical_accuracy" to "medical_accuracy_score",
            "clinical_reasoning" to "clinical_reasoning_score",
            "professionalism" to "professionalism_score",
            "communication_fluency" to "fluency_score"
        )
        val buckets = cols.keys.associateWith { mutableListOf<Double>() }
        for (row in sessions) {
            for ((metric, col) in cols) {
                (row[col] as? Number)?.toDouble()?.let { buckets.getValue(metric).add(it) }
            }
        }
        return buckets.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.average() }
    }

    private fun caseIdOf(session: Map<String, Any?>): String? {
        (session["case_id"] as? String)?.let { if (it.isNotEmpty()) return it }
        val raw = session["raw_case_json"]
        return try {
            val obj = when (raw) {
                is String -> JSONObject(raw)
                is JSONObject -> raw
                else -> JSONObject()
            }
            obj.optString("id", "").ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    private fun practicedCaseIds(sessions: List<Map<String, Any?>>): Set<String> {
        return sessions.mapNotNull { caseIdOf(it) }.toSet()
    }

    /** Return prioritized dashboard cards for US-facing medical English study. */
    fun buildUsClinicalEnglishRoadmap(
        sessions: List<Map<String, Any?>>,
        dueCount: Int = 0,
        maxItems: Int = 4
    ): List<RoadmapCard> {
        val clinical = ScoreDomains.clinicalSessions(sessions)
        val total = clinical.size
        val averages = metricAverages(clinical)
        val practiced = practicedCaseIds(clinical)
        val weakestEntry = averages.entries.minByOrNull { it.value }
        val weakestMetric = weakestEntry?.key
        val weakestScore = weakestEntry?.value

        val cards = mutableListOf<RoadmapCard>()
        if (dueCount >= 3) {
            cards.add(
                RoadmapCard(
                    key = "my_mistakes",
                    title = "Practice My Mistakes",
                    target = "Turn recurring corrections into automatic spoken phrases.",
                    cta = "Review $dueCount error(s)",
                    route = RoadmapRoute(tab = "encounter", action = "review"),
                    reason = "$dueCount correction(s) are due for spaced review.",
                    status = "Due now",
                    priority = 100 + minOf(dueCount, 10)
                )
            )
        }

        for (area in SKILL_AREAS) {
            val practicedArea = area.caseIds.isNotEmpty() && area.caseIds.intersect(practiced).isNotEmpty()
            var priority = area.basePriority
            var reason = "Build this core skill for US clinical settings."
            var status = "Build next"

            val hasPresentableEncounter = clinical.any { PresentationBuilder.isPresentable(it) }
            val hasPresentation = clinical.any {
                (it["mode"]?.toString() ?: "").equals("presentation", ignoreCase = true)
            }
            if (area.key == "attending_presentation" && hasPresentableEncounter && !hasPresentation) {
                priority += 30
                reason = "Use your latest interview while the patient details are still fresh."
                status = "Ready now"
            } else if (area.key == "attending_presentation" && !hasPresentableEncounter) {
                priority -= 35
                reason = "Complete a patient encounter first; the same patient will carry forward."
                status = "After an encounter"
            } else if (total == 0 && area.key == "foundations") {
                priority += 28
                reason = "Start here: it lowers the barrier before harder cases."
                status = "Start here"
            } else if (weakestMetric != null && area.weakMetrics.contains(weakestMetric) && weakestScore != null) {
                priority += 18
                val label = METRIC_LABELS[weakestMetric] ?: weakestMetric
                reason = "Your recent $label average is ${"%.1f".format(weakestScore / 10.0)}/10."
                status = "Needs attention"
            } else if (practicedArea) {
                priority -= 18
                reason = "You have practiced this recently; keep it in rotation."
                status = "Maintain"
            } else if (total >= 3 && (area.key == "sbar_handover" || area.key == "img_interview")) {
                priority += 8
                reason = "You have enough practice history to add higher-stakes English."
            }

            cards.add(
                RoadmapCard(
                    key = area.key,
                    title = area.title,
                    target = area.target,
                    cta = area.cta,
                    route = area.route,
                    reason = reason,
                    status = status,
                    priority = priority
                )
            )
        }

        return cards.sortedByDescending { it.priority }.take(maxItems)
    }

    /**
     * Deterministic next-step advice for the feedback window — port of
     * app/analysis/learning_roadmap.py's build_post_session_practice_plan.
     *
     * The caller (FeedbackScreen) reads the [com.example.medvoicetrainer.ui.EvaluationResult]
     * and passes the domain-resolved scores so this stays decoupled from app/UI types, matching
     * this project's "analysis modules take already-fetched data as parameters" convention.
     *
     * **Scale note**: Python's `overall_scores` are 0–10 (thresholds `< 7.0`); this app's
     * analysis schema (EvalPromptBuilder.kt) uses 0–100, so the same thresholds become `< 70.0`.
     * `wpm < 90` and `filler_density > 8` are absolute/percentage and unchanged.
     *
     * For clinical sessions pass [medicalAccuracy]/[clinicalReasoning]/[professionalism] and leave
     * the everyday trio null; for everyday sessions pass [naturalness]/[interaction]/
     * [comprehensionRepair] (which live in the reused clinical-named columns — see EvaluationResult)
     * and leave the clinical trio null. This mirrors Python's `everyday`-gated `None` assignments.
     */
    fun buildPostSessionPracticePlan(
        everyday: Boolean,
        correctionCount: Int,
        communicationFluency: Double?,
        wpm: Double?,
        fillerDensity: Double?,
        medicalAccuracy: Double? = null,
        clinicalReasoning: Double? = null,
        professionalism: Double? = null,
        naturalness: Double? = null,
        interaction: Double? = null,
        comprehensionRepair: Double? = null,
        caseName: String = ""
    ): List<PracticePlanCard> {
        val plan = mutableListOf<PracticePlanCard>()

        if (correctionCount > 0) {
            plan.add(
                PracticePlanCard(
                    title = "Repair today's exact mistakes",
                    target = "Read each corrected sentence aloud three times, then run Practice My Mistakes.",
                    reason = "$correctionCount correction(s) were saved for SRS review.",
                    cta = "Practice corrections"
                )
            )
        }

        if ((communicationFluency != null && communicationFluency < 70.0) || (wpm != null && wpm < 90)) {
            plan.add(
                PracticePlanCard(
                    title = "Shadow one better version",
                    target = "Use the Shadowing tab: listen, pause, imitate, then say it once without looking.",
                    reason = "This converts edited English into mouth memory.",
                    cta = "Use Shadowing"
                )
            )
        }
        if (fillerDensity != null && fillerDensity > 8) {
            plan.add(
                PracticePlanCard(
                    title = if (everyday) "Replace fillers with calm pauses" else "Replace fillers with clinical pauses",
                    target = "Practice a two-second silent pause before answering difficult questions.",
                    reason = "Filler density was ${"%.1f".format(fillerDensity)}%.",
                    cta = "Repeat with pauses"
                )
            )
        }

        val medVocab = if (everyday) null else medicalAccuracy
        val structure = if (everyday) null else clinicalReasoning
        val prof = if (everyday) null else professionalism
        if (everyday) {
            if (naturalness != null && naturalness < 70.0) {
                plan.add(
                    PracticePlanCard(
                        title = "Make one response sound more natural",
                        target = "Say the corrected everyday response three times, then use it in a new situation.",
                        reason = "Natural everyday wording is the clearest next step from this session.",
                        cta = "Practice natural phrasing"
                    )
                )
            }
            if (interaction != null && interaction < 70.0) {
                plan.add(
                    PracticePlanCard(
                        title = "Complete the conversational job",
                        target = "Practice one fitting response: answer, ask back, choose, decline, or end politely.",
                        reason = "Real-life success depends on a response that fits the moment, not on speaking longer.",
                        cta = "Retry the situation"
                    )
                )
            }
            if (comprehensionRepair != null && comprehensionRepair < 70.0) {
                plan.add(
                    PracticePlanCard(
                        title = "Use a repair phrase out loud",
                        target = "Practice: 'Sorry, was that B12?' and 'Could you say the last part again?'.",
                        reason = "Clarification and read-back are successful real-life communication.",
                        cta = "Practice clarification"
                    )
                )
            }
        }
        if (medVocab != null && medVocab < 70.0) {
            plan.add(
                PracticePlanCard(
                    title = "Plain-language drill",
                    target = "Explain five medical words without using another medical word.",
                    reason = "US patient communication rewards clear, everyday wording.",
                    cta = "Plain-language drill"
                )
            )
        }
        if (structure != null && structure < 70.0) {
            plan.add(
                PracticePlanCard(
                    title = "Register switching drill",
                    target = "Say one idea three ways: patient-friendly, team update, attending one-liner.",
                    reason = "US clinical English depends on changing register for the listener.",
                    cta = "Register switching"
                )
            )
        }
        if (prof != null && prof < 70.0) {
            plan.add(
                PracticePlanCard(
                    title = "Empathy and repair phrases",
                    target = "Practice: acknowledge emotion, ask permission, summarize, check understanding.",
                    reason = "Rapport language is often the fastest score gain for IMGs.",
                    cta = "Empathy drill"
                )
            )
        }

        val name = caseName.ifBlank { "the same scenario" }
        val fallbackCards = listOf(
            PracticePlanCard(
                title = "Review session transcript",
                target = "Scan your transcript for any hesitation or filler words.",
                reason = "Awareness of speech patterns prevents future errors.",
                cta = "Review transcript"
            ),
            PracticePlanCard(
                title = "Shadow your strongest turn",
                target = "Listen to your best response in this session and shadow it out loud twice.",
                reason = "Reinforce fluent delivery and professional tone.",
                cta = "Shadow turn"
            ),
            PracticePlanCard(
                title = "Repeat once with one constraint",
                target = "Run a short second attempt and focus on only one target phrase family.",
                reason = "Repeating $name immediately makes feedback stick.",
                cta = "Repeat session"
            )
        )
        val needed = 3 - plan.size
        if (needed > 0) {
            plan.addAll(fallbackCards.takeLast(needed))
        }
        return plan
    }
}
