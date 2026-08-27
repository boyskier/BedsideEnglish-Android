package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/**
 * Deterministic navigation for an established-patient review.
 *
 * Follow-up visits do not use the first-visit history phase tracker: their useful order is authored
 * per case and follows the previous plan, interval response, safety review and shared plan.  This
 * engine turns those authored steps plus the live checklist evidence into the same [RescueHint]
 * shape the encounter UI already knows how to render.
 */
object FollowUpFlow {
    data class State(
        val primary: RescueHint?,
        val options: List<RescueHint>,
        val wrapUpSuggested: Boolean,
    )

    private data class AuthoredHint(
        val objective: String,
        val category: String,
        val question: String,
    )

    fun derive(
        caseJson: String,
        coverage: Map<String, CoverageSource>,
        learnerTurnCount: Int,
    ): State {
        val hints = parseHints(caseJson)
        if (hints.isEmpty()) return State(null, emptyList(), false)

        val open = hints.filter { coverage[it.objective] == null || coverage[it.objective] == CoverageSource.NONE }
        val uncertain = hints.filter {
            coverage[it.objective] in setOf(CoverageSource.LEARNER_WEAK, CoverageSource.PATIENT_VOLUNTEERED)
        }
        val candidates = (open.map { it to "continue" } + uncertain.map { it to "catch_up" })
            .distinctBy { it.first.objective }
            .take(3)
            .map { (hint, reason) ->
                RescueHint(
                    category = hint.category,
                    question = hint.question,
                    reason = reason,
                    domainKey = "follow_up::${hint.objective}",
                )
            }

        // A bare/negated keyword match is deliberately shown as "possible" in the checklist. It
        // must not also tell the learner that the visit is complete. Only strong transcript
        // evidence or the learner's explicit manual confirmation can unlock the closing nudge.
        val confirmedSources = setOf(CoverageSource.LEARNER_STRONG, CoverageSource.MANUAL)
        val allConfirmed = hints.all { coverage[it.objective] in confirmedSources }
        val wrapReady = learnerTurnCount >= 4 && allConfirmed
        val wrapHint = RescueHint(
            category = "Wrap up",
            question = "Before we finish, could you tell me in your own words what the plan is and when you would seek help?",
            reason = "continue",
            domainKey = "follow_up::wrap_up",
        )
        val options = if (wrapReady) listOf(wrapHint) else candidates
        return State(options.firstOrNull(), options, wrapReady)
    }

    private fun parseHints(caseJson: String): List<AuthoredHint> = runCatching {
        val array = JSONObject(caseJson).optJSONArray("follow_up_hints") ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val objective = item.optString("objective").trim()
                val category = item.optString("category").trim()
                val question = item.optString("question").trim()
                if (objective.isNotEmpty() && category.isNotEmpty() && question.isNotEmpty()) {
                    add(AuthoredHint(objective, category, question))
                }
            }
        }
    }.getOrDefault(emptyList())
}
