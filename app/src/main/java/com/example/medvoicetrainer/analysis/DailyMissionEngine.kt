package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.DebriefCommitmentEntity
import java.time.LocalDate

/**
 * Ported from app/analysis/daily_mission.py — one high-leverage 5-minute task for today.
 *
 * Previously this object was an invented reimplementation with no relation to the Python file:
 * different mission catalog entirely, no due-count/open-commitment/weekday-rotation logic, and a
 * completely different "no sessions yet" behavior (a baseline GI diagnostic case instead of
 * Python's pressure-free warm-up chat). Same "wrongly marked Y" pattern flagged for
 * l1_stats.py/learning_roadmap.py in MIGRATION_MASTER.md — corrected this round.
 */

data class MissionRoute(
    val tab: String,
    val system: String? = null,
    val caseId: String? = null,
    val action: String? = null,
    val category: String? = null
)

/**
 * Why the engine landed on this mission, split into a one-line summary and the rule behind it.
 *
 * `reason` above says what the task is good for; this says why *today's* pick is this one and not
 * another. Kept as two fields so the UI can show the short line by default and reveal the rule on
 * demand — a recommendation the learner cannot interrogate reads as arbitrary, and the rotation
 * branch in particular looks random from the outside when it is a fixed daily cycle.
 */
data class MissionBasis(
    val label: String,
    val detail: String
)

data class DailyMission(
    val priority: String,
    val title: String,
    val reason: String,
    val cta: String,
    val durationMinutes: Int,
    val route: MissionRoute?,
    val weakestMetric: String? = null,
    val weakestScore: Double? = null,
    val commitmentId: Int? = null,
    val commitmentText: String? = null,
    val basis: MissionBasis? = null
)

object DailyMissionEngine {

    private data class MissionTemplate(
        val title: String,
        val reason: String,
        val cta: String,
        val route: MissionRoute
    )

    private val MISSION_BY_WEAK_METRIC = mapOf(
        "grammar" to MissionTemplate(
            title = "Clean clinical sentence frames",
            reason = "Your recent grammar score is the lowest signal.",
            cta = "Start Foundations",
            route = MissionRoute(tab = "encounter", system = "foundations")
        ),
        "medical_accuracy" to MissionTemplate(
            title = "Explain jargon in patient English",
            reason = "Your medical vocabulary/accuracy score needs attention.",
            cta = "Plain-language drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_plain_language")
        ),
        "clinical_reasoning" to MissionTemplate(
            title = "Organize one idea three ways",
            reason = "Your structure score is the current bottleneck.",
            cta = "Register drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_register_switching")
        ),
        "professionalism" to MissionTemplate(
            title = "Empathy and repair phrases",
            reason = "Your rapport/professionalism score has room to grow.",
            cta = "Empathy drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_empathy")
        ),
        "fluency" to MissionTemplate(
            title = "Listen, read back, then clarify",
            reason = "Your fluency score is the lowest recent signal.",
            cta = "Read-back drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_rapid_readback")
        )
    )

    private val ROTATION = listOf(
        MissionTemplate(
            title = "Clinical mediation mini drill",
            reason = "Practice converting patient words into team language and back.",
            cta = "Mediation drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_mediation_patient_team")
        ),
        MissionTemplate(
            title = "Clarify one unsafe instruction",
            reason = "Safe English means repeating, clarifying, and reading back.",
            cta = "Clarifying drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_clarify_orders")
        ),
        MissionTemplate(
            title = "SBAR in five minutes",
            reason = "Keep team communication in rotation.",
            cta = "Practice SBAR",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "handover_night_shift")
        ),
        MissionTemplate(
            title = "Plain-language mini drill",
            reason = "Patient-friendly explanations are a high-yield habit.",
            cta = "Plain-language drill",
            route = MissionRoute(tab = "encounter", system = "drills", caseId = "drill_plain_language")
        ),
        MissionTemplate(
            title = "Residency answer warm-up",
            reason = "Specific stories make interview English stronger.",
            cta = "Open IMG interview",
            route = MissionRoute(tab = "interview", category = "img_specific")
        )
    )

    private val METRIC_LABELS = mapOf(
        "grammar" to "Grammar",
        "medical_accuracy" to "Medical accuracy",
        "clinical_reasoning" to "Structure",
        "professionalism" to "Rapport",
        "fluency" to "Fluency"
    )

    private val METRIC_COLUMNS = mapOf(
        "grammar" to "grammar_score",
        "medical_accuracy" to "medical_accuracy_score",
        "clinical_reasoning" to "clinical_reasoning_score",
        "professionalism" to "professionalism_score",
        "fluency" to "fluency_score"
    )

    private fun metricAverages(sessions: List<Map<String, Any?>>): Map<String, Double> {
        val buckets = METRIC_COLUMNS.keys.associateWith { mutableListOf<Double>() }
        for (row in sessions) {
            for ((metric, col) in METRIC_COLUMNS) {
                (row[col] as? Number)?.toDouble()?.let { buckets.getValue(metric).add(it) }
            }
        }
        return buckets.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.average() }
    }

    /**
     * @param sessions already-fetched sessions as analysis maps (e.g. `List<SessionEntity>.toAnalysisMaps()`);
     *   filtered down to clinical-domain sessions internally, matching Python's `clinical_sessions(sessions)`.
     * @param dueCount SRS items due as of the relevant deadline (Repository.getDueCount()/getDueCountBy()).
     * @param openCommitments most-recent-first open debrief commitments (Repository.getOpenCommitments()).
     * @param today the date the mission is being generated for — pass tomorrow's date for a
     *   tomorrow-mission preview, matching queries.py's get_tomorrow_mission_preview().
     */
    fun generateMission(
        sessions: List<Map<String, Any?>>,
        dueCount: Int = 0,
        openCommitments: List<DebriefCommitmentEntity> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): DailyMission {
        require(dueCount >= 0) { "dueCount cannot be negative" }
        val clinical = ScoreDomains.clinicalSessions(sessions)

        if (dueCount > 0) {
            return DailyMission(
                priority = "srs_review",
                title = "Rescue yesterday's mistakes",
                reason = "$dueCount saved correction(s) are due today.",
                cta = "Review $dueCount correction(s)",
                durationMinutes = 5,
                route = MissionRoute(tab = "encounter", action = "review"),
                basis = MissionBasis(
                    label = "Top priority · $dueCount due now",
                    detail = "Spaced repetition scheduled these for today. Corrections you have " +
                        "already earned come before new practice, so they lead until the queue is clear."
                )
            )
        }

        // A promise made to the AI tutor in a debrief outranks generic practice: it is
        // self-authored, so following through converts reflection into habit.
        if (clinical.isNotEmpty()) {
            val commitment = openCommitments.firstOrNull { it.text.isNotBlank() }
            if (commitment != null) {
                val text = commitment.text.replace(Regex("<[^>]*>"), "").trim()
                return DailyMission(
                    priority = "debrief_commitment",
                    title = "Keep your debrief promise",
                    reason = "You told your tutor: “$text” — run one encounter and make it happen.",
                    cta = "Practice your promise",
                    durationMinutes = 5,
                    route = MissionRoute(tab = "encounter"),
                    commitmentId = commitment.id,
                    commitmentText = text,
                    basis = MissionBasis(
                        label = "Your own commitment",
                        detail = "You set this goal yourself in a debrief. A goal you chose outranks " +
                            "any generic pick, so it stays here until you run one encounter."
                    )
                )
            }
        }

        if (clinical.isEmpty()) {
            // A brand-new user's very first action is a short, friendly warm-up conversation —
            // no scores, no level label, no pressure.
            return DailyMission(
                priority = "warmup",
                title = "2-minute warm-up chat",
                reason = "You already know English — let's just get it out of your mouth. No scores, no pressure.",
                cta = "Start your warm-up",
                durationMinutes = 2,
                route = MissionRoute(tab = "encounter", system = "foundations", caseId = "warmup_chat"),
                basis = MissionBasis(
                    label = "Your first session",
                    detail = "There is no practice history to adapt to yet, so today is a short " +
                        "unscored chat rather than a graded case."
                )
            )
        }

        val window = clinical.take(20)
        val averages = metricAverages(window)
        if (averages.isNotEmpty()) {
            val weakest = averages.entries.minByOrNull { it.value }!!
            if (weakest.value < 7.5) {
                val template = MISSION_BY_WEAK_METRIC.getValue(weakest.key)
                val metricLabel = METRIC_LABELS[weakest.key] ?: weakest.key
                val rounded = Math.round(weakest.value * 10) / 10.0
                // The sessions that actually carry this score — not the whole window, which can
                // include sessions the analyzer never scored on this metric. The basis line is an
                // evidence claim, so it has to count the evidence it was averaged from.
                val scored = METRIC_COLUMNS[weakest.key]?.let { col ->
                    window.count { it[col] is Number }
                } ?: window.size
                return DailyMission(
                    priority = "weak_metric",
                    title = template.title,
                    reason = template.reason,
                    cta = template.cta,
                    durationMinutes = 5,
                    route = template.route,
                    weakestMetric = weakest.key,
                    weakestScore = rounded,
                    basis = MissionBasis(
                        label = "Weakest score · $metricLabel $rounded/10",
                        detail = "Averaged over your last $scored scored session(s), this is the " +
                            "lowest of your five scores and still under 7.5, so today aims at it."
                    )
                )
            }
        }

        val index = Math.floorMod(today.toEpochDay(), ROTATION.size.toLong()).toInt()
        val template = ROTATION[index]
        return DailyMission(
            priority = "rotation",
            title = template.title,
            reason = template.reason,
            cta = template.cta,
            durationMinutes = 5,
            route = template.route,
            basis = MissionBasis(
                label = "Keeping in rotation · day ${index + 1} of ${ROTATION.size}",
                detail = "Nothing is due for review and all five recent scores are 7.5 or above, so " +
                    "the pick cycles through the drill set by date — tomorrow's is a different one."
            )
        )
    }
}
