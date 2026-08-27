package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorIdentity
import com.example.medvoicetrainer.db.ErrorItemEntity
import com.example.medvoicetrainer.db.Repository
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

object SrsEngine {

    // config.py's SRS tunables (SRS_INTERVALS / SRS_MASTERY_STREAK), shared with
    // MainViewModel's explicit review schedules.
    val SRS_INTERVALS = listOf(1, 3, 7, 14, 30)
    const val MASTERY_STREAK = 3

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun nowIso() = isoFormat.format(Date())

    private fun addDays(isoDate: String, days: Int): String {
        val cappedDays = days.coerceAtMost(365)
        val date = try {
            isoFormat.parse(isoDate) ?: Date()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Date()
        }
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DAY_OF_YEAR, cappedDays)
        return isoFormat.format(cal.time)
    }

    // sessionId is nullable so a correction can be captured with no owning session — e.g. the
    // standalone home-screen English coach, which drills the learner outside any practice run.
    // error_items has no foreign key to sessions and ErrorItemEntity.lastSessionId is nullable,
    // so a null here simply records "no originating session" as provenance.
    suspend fun ingestCorrections(corrections: List<DebriefCorrection>, sessionId: Int?, repository: Repository): Set<String> {
        val keys = mutableSetOf<String>()
        if (corrections.isEmpty()) return keys

        val existingItems = repository.getAllErrorItemsList()

        for (c in corrections) {
            val corrected = c.corrected.replace(Regex("[\uFEFF\u200B]"), "").trim()
            val original = c.original.replace(Regex("[\uFEFF\u200B]"), "").trim()
            if (corrected.isEmpty()) continue

            try {
                // Find fuzzy match
                val bestKey = ErrorIdentity.bestMatch(corrected, original, c.category, existingItems)

                if (bestKey != null) {
                    // Update existing
                    val existing = repository.getErrorItemByKey(bestKey)
                    if (existing != null) {
                        val updated = existing.copy(
                            seenCount = existing.seenCount + 1,
                            absentStreak = 0,
                            lastSeen = nowIso(),
                            lastSessionId = sessionId
                        )
                        repository.updateErrorItem(updated)
                        keys.add(bestKey)
                    }
                } else {
                    // Create new
                    val newKey = ErrorIdentity.normalize(corrected)
                    val newItem = ErrorItemEntity(
                        key = newKey,
                        category = c.category.ifEmpty { "other" },
                        original = original,
                        corrected = corrected,
                        explanation = c.explanation.trim(),
                        state = "new",
                        seenCount = 1,
                        correctStreak = 0,
                        intervalDays = 1,
                        dueAt = addDays(nowIso(), 1),
                        firstSeen = nowIso(),
                        lastSeen = nowIso(),
                        lastSessionId = sessionId,
                        absentStreak = 0,
                        lapses = 0,
                        domain = "clinical"
                    )
                    repository.insertErrorItem(newItem)
                    keys.add(newKey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // Ignore
            }
        }
        return keys
    }

    /**
     * Update SRS streaks after a "Practice My Mistakes" review session — a faithful port of
     * srs_engine.py's `update_after_review`. Each [ReviewTarget] carries the DB `key` it came
     * from (embedded by ReviewBuilder.buildReviewCase into the case's `target_corrections`). A
     * checklist result is matched first by the quoted improved form inside the item text
     * (`used the improved form: "..."`), then by position; if neither yields a verdict the item is
     * left untouched — a parsing miss must never be read as a failure and reset the student's
     * streak. The verdict field is read as `met` (Python's key) OR `passed` (this app's analysis
     * schema; see EvalPromptBuilder) so it works against either shape.
     */
    suspend fun updateAfterReview(checklistResults: List<Map<String, Any?>>, targets: List<ReviewTarget>, repository: Repository) {
        if (targets.isEmpty()) return

        try {
            for ((i, t) in targets.withIndex()) {
                val key = t.key
                if (key.isNullOrBlank()) continue
                var met: Boolean? = null

                if (i < checklistResults.size) {
                    val resultItem = checklistResults[i]
                    val text = resultItem["item"] as? String ?: ""

                    val quoteCount = text.count { it == '"' }
                    if (quoteCount >= 2) {
                        val inner = text.split('"')[1]
                        if (ErrorIdentity.normalize(inner) == ErrorIdentity.normalize(t.corrected)) {
                            met = reviewVerdict(resultItem)
                        }
                    } else if (text.isNotBlank()) {
                        met = reviewVerdict(resultItem)
                    }
                }

                if (met != null) {
                    val existing = repository.getErrorItemByKey(key)
                    if (existing != null && existing.domain == "clinical") {
                        // Same schedule as submitSrsAnswer: the SM-2-lite ladder,
                        // mastery at streak 3, lapse back to a 1-day interval. Any graded review
                        // also counts as "seen", so the absence streak resets either way.
                        val updated = if (met) {
                            val newStreak = existing.correctStreak + 1
                            val newInterval = SRS_INTERVALS[min(newStreak, SRS_INTERVALS.size - 1)]
                            existing.copy(
                                correctStreak = newStreak,
                                intervalDays = newInterval,
                                state = if (newStreak >= MASTERY_STREAK) "mastered" else "learning",
                                dueAt = addDays(nowIso(), newInterval),
                                lastSeen = nowIso(),
                                absentStreak = 0
                            )
                        } else {
                            existing.copy(
                                correctStreak = 0,
                                intervalDays = 1,
                                lapses = existing.lapses + 1,
                                state = "learning",
                                dueAt = addDays(nowIso(), 1),
                                lastSeen = nowIso(),
                                absentStreak = 0
                            )
                        }
                        repository.updateErrorItem(updated)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Ignore
        }
    }

    /** A checklist result's pass/fail verdict, tolerant of `met` (Python) or `passed`/`elicited`
     *  (this app's parsed analysis schema). Absent/non-boolean → false, matching Python's
     *  `bool(result_item.get("met", False))`. */
    internal fun reviewVerdict(item: Map<String, Any?>): Boolean {
        val raw = item["met"] ?: item["passed"] ?: item["elicited"]
        if (raw is Boolean) return raw
        if (raw is String) return raw.toBoolean()
        return false
    }
}
