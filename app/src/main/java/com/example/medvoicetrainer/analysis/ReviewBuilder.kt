package com.example.medvoicetrainer.analysis

import android.content.Context
import com.example.medvoicetrainer.db.ErrorItemEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ported from app/analysis/review_builder.py — builds a personalised "Practice My Mistakes"
 * session from the user's own history. DB access (due/active error items, recent sessions) is
 * the caller's responsibility, matching the Repository-driven convention used elsewhere; this
 * object only ranks/aggregates already-fetched data and builds the dynamic case + eval.
 */

const val REVIEW_MIN_TARGETS = 3
const val REVIEW_MAX_TARGETS = 6
const val REVIEW_SESSION_LOOKBACK = 50

data class ReviewTarget(
    val key: String? = null,
    val original: String,
    val corrected: String,
    val explanation: String,
    val count: Int
)

object ReviewBuilder {

    private fun norm(s: String?): String = (s ?: "").lowercase().trim().split(Regex("\\s+")).joinToString(" ")

    /**
     * Collapse the `corrections` field across sessions (most-recent-first) into a ranked list of
     * target items, keyed by normalized `corrected` text; insertion order (== recency) is the
     * stable tie-breaker.
     */
    fun aggregateCorrections(sessions: List<Map<String, Any?>>, maxItems: Int = REVIEW_MAX_TARGETS): List<ReviewTarget> {
        val groups = LinkedHashMap<String, ReviewTarget>()
        for (sess in sessions) {
            val raw = sess["corrections"] as? String ?: continue
            if (raw.isBlank()) continue
            val items = try { JSONArray(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
            for (i in 0 until items.length()) {
                val c = items.optJSONObject(i) ?: continue
                val corrected = c.optString("corrected", "").trim()
                if (corrected.isEmpty()) continue
                val key = norm(corrected)
                val existing = groups[key]
                if (existing == null) {
                    groups[key] = ReviewTarget(
                        original = c.optString("original", "").trim(),
                        corrected = corrected,
                        explanation = c.optString("explanation", "").trim(),
                        count = 1
                    )
                } else {
                    groups[key] = existing.copy(count = existing.count + 1)
                }
            }
        }
        return groups.values.sortedByDescending { it.count }.take(maxItems)
    }

    private fun dbRowToTarget(row: ErrorItemEntity): ReviewTarget {
        return ReviewTarget(
            key = row.key,
            original = row.original,
            corrected = row.corrected,
            explanation = row.explanation,
            count = if (row.seenCount > 0) row.seenCount else 1
        )
    }

    /**
     * Select review targets, preferring SRS-due items over raw aggregation.
     * Priority: 1) items overdue for review, 2) any active (non-mastered) items if nothing is due,
     * 3) legacy fallback: aggregate from recent session `corrections` blobs (cold start).
     */
    fun prepareTargets(
        dueItems: List<ErrorItemEntity>,
        activeItems: List<ErrorItemEntity>,
        recentSessions: List<Map<String, Any?>>,
        maxItems: Int = REVIEW_MAX_TARGETS
    ): List<ReviewTarget> {
        if (dueItems.isNotEmpty()) {
            return dueItems.take(maxItems).map { dbRowToTarget(it) }
        }
        if (activeItems.size >= REVIEW_MIN_TARGETS) {
            return activeItems.take(maxItems).map { dbRowToTarget(it) }
        }
        return aggregateCorrections(recentSessions.take(REVIEW_SESSION_LOOKBACK), maxItems)
    }

    private fun buildPersona(targets: List<ReviewTarget>): String {
        val lines = targets.mapIndexed { i, t ->
            var line = "${i + 1}. was: \"${t.original}\"  ->  aim: \"${t.corrected}\""
            if (t.explanation.isNotEmpty()) {
                line += "  (why: ${t.explanation})"
            }
            line
        }
        val targetBlock = lines.joinToString("\n")
        return "You are a friendly clinical-English coach running a PERSONALISED REVIEW for a " +
            "beginner medical student.\n" +
            "The student previously made the language errors listed below. Create short, natural " +
            "practice moments that prompt the student to PRODUCE the improved version OUT LOUD — " +
            "without telling them the answer first.\n\n" +
            "TARGET ITEMS (the student should now produce the improved form):\n" +
            "$targetBlock\n\n" +
            "Rules:\n" +
            "- Each turn, role-play ONE realistic situation (usually as a patient) that naturally " +
            "calls for one target item, then pause and wait for the student.\n" +
            "- Do NOT say the improved phrase yourself. Let the student attempt it.\n" +
            "- If they produce it well, briefly praise and move to the next item.\n" +
            "- If they miss it or fall back into the old form, give a SMALL hint (not the full " +
            "answer) and let them try again.\n" +
            "- Cover every target item at least once, then revisit any they struggled with.\n" +
            "- Keep your turns short. The session ends when the student says " +
            "\"I'd like to end the session.\""
    }

    /** A dynamic case object compatible with EncounterTab / SessionBase equivalents. */
    fun buildReviewCase(targets: List<ReviewTarget>): JSONObject {
        val case = JSONObject()
        case.put("id", "review_corrections")
        case.put("system", "review")
        case.put("difficulty", "beginner")
        case.put("eval_template", "correction_review")
        case.put("learner_level", "preclinical")
        case.put("coaching_mode", true)
        case.put("patient_name", "My Mistakes Review")
        case.put("chief_complaint", "personalised review of your past corrections")
        case.put("persona_override", buildPersona(targets))
        case.put("target_corrections", JSONArray(targets.map {
            JSONObject().put("key", it.key).put("original", it.original).put("corrected", it.corrected).put("explanation", it.explanation)
        }))
        case.put("suggested_questions", JSONArray())

        val aim = JSONObject()
        aim.put("name", "Aim for these improved phrases")
        aim.put("phrases", JSONArray(targets.map { it.corrected }))
        case.put("phrase_categories", JSONArray().put(aim))
        return case
    }

    /** Load the base eval and inject one checklist item per target. */
    fun buildReviewEval(context: Context, targets: List<ReviewTarget>): JSONObject {
        val text = context.assets.open("eval/correction_review.json").bufferedReader().use { it.readText() }
        val ev = JSONObject(text)
        val checklist = JSONArray()
        for (t in targets) {
            checklist.put(
                JSONObject()
                    .put("item", "used the improved form: \"${t.corrected}\"")
                    .put("required", true)
            )
        }
        ev.put("checklist", checklist)
        return ev
    }
}
