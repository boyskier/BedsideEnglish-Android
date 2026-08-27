package com.example.medvoicetrainer.analysis

import org.json.JSONArray

/**
 * Ported from app/analysis/milestones.py — practice milestones (lifetime thresholds
 * for sessions, spoken minutes, streak days, mistakes mastered), checked after each
 * analyzed session. Pure/deterministic: caller supplies stats + a settings get/set pair
 * (backed by Repository.getSetting/setSetting) instead of this object touching the DB.
 */

data class Milestone(
    val id: String,
    val metric: String,
    val threshold: Int,
    val label: String
)

object Milestones {

    val MILESTONES = listOf(
        Milestone("sessions_1", "sessions", 1, "First session complete — the hardest step is behind you"),
        Milestone("sessions_10", "sessions", 10, "10 practice sessions"),
        Milestone("sessions_25", "sessions", 25, "25 practice sessions"),
        Milestone("sessions_50", "sessions", 50, "50 practice sessions"),
        Milestone("sessions_100", "sessions", 100, "100 practice sessions"),
        Milestone("sessions_250", "sessions", 250, "250 practice sessions"),
        Milestone("minutes_60", "minutes", 60, "1 hour of English spoken out loud"),
        Milestone("minutes_300", "minutes", 300, "5 hours of English spoken out loud"),
        Milestone("minutes_1000", "minutes", 1000, "1,000 minutes of English spoken out loud"),
        Milestone("streak_3", "streak", 3, "3-day practice streak"),
        Milestone("streak_7", "streak", 7, "7-day practice streak"),
        Milestone("streak_14", "streak", 14, "14-day practice streak"),
        Milestone("streak_30", "streak", 30, "30-day practice streak"),
        Milestone("mastered_1", "mastered", 1, "First mistake mastered — it won't fool you again"),
        Milestone("mastered_10", "mastered", 10, "10 mistakes mastered"),
        Milestone("mastered_25", "mastered", 25, "25 mistakes mastered"),
        Milestone("mastered_50", "mastered", 50, "50 mistakes mastered")
    )

    const val SETTING_KEY = "milestones_celebrated"
    const val MAX_CELEBRATED_AT_ONCE = 3

    /** Every milestone whose threshold `stats` meets, in ladder order. */
    fun crossed(stats: Map<String, Int>): List<Milestone> {
        return MILESTONES.filter { (stats[it.metric] ?: 0) >= it.threshold }
    }

    private fun parseIdSet(json: String): Set<String> {
        return try {
            val arr = JSONArray(json.ifBlank { "[]" })
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Return the milestones to celebrate right now, marking ALL crossed ones via [setSetting].
     * Celebrated = newly crossed since the last check, capped to [MAX_CELEBRATED_AT_ONCE],
     * highest threshold first, so a returning user with a big backlog gets one dialog, not a pile.
     */
    fun checkAndMarkNew(
        stats: Map<String, Int>,
        getSetting: () -> String,
        setSetting: (String) -> Unit
    ): List<Milestone> {
        val already = parseIdSet(getSetting())
        val allCrossed = crossed(stats)
        val new = allCrossed.filter { it.id !in already }
        if (new.isEmpty()) return emptyList()

        val union = (allCrossed.map { it.id }.toSet() + already).sorted()
        setSetting(JSONArray(union).toString())

        return new.sortedByDescending { it.threshold }.take(MAX_CELEBRATED_AT_ONCE)
    }

    /** How many milestones this install has unlocked (dashboard flair). */
    fun celebratedCount(getSetting: () -> String): Int {
        return parseIdSet(getSetting()).size
    }
}
