package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.SessionEntity

/**
 * Ported from app/db/queries.py's get_confidence_trend() — fluency averages + session counts for
 * a period, compared with the equal-length period before it. Feeds dashboard_tab.py's
 * "Confidence this week" card (_refresh_confidence_card), which had no Kotlin equivalent at all.
 *
 * Takes already-loaded sessions (Dashboard's `sessions` StateFlow already carries every field
 * this needs) and ISO cutoff strings computed by the caller in the same local-time convention as
 * the rest of the app (SessionEntity.createdAt), rather than issuing a fresh DB query — mirrors
 * how Dashboard already computes streak/due-count/etc. synchronously from collected state.
 */

data class ConfidenceWindow(
    val sessionCount: Int,
    val totalSpeakingMinutes: Double,
    val avgWordCount: Double?,
    val avgWpm: Double?,
    val avgFillerRate: Double?,
    val avgTalkTimeRatio: Double?
)

data class ConfidenceTrend(val thisWeek: ConfidenceWindow?, val priorWeek: ConfidenceWindow?)

object ConfidenceTrendEngine {

    private fun avg(values: List<Double>): Double? {
        return if (values.isNotEmpty()) Math.round(values.average() * 100) / 100.0 else null
    }

    private fun windowStats(sessions: List<SessionEntity>, fromIso: String, toIso: String): ConfidenceWindow? {
        // Matches Python's `user_word_count IS NOT NULL` filter — Kotlin's column defaults to 0
        // rather than null, so only sessions with real fluency metrics computed (userWordCount > 0)
        // count, same convention used by AppDao.getSessionsInWindowWithWordCount.
        val rows = sessions.filter { it.userWordCount > 0 && it.createdAt >= fromIso && it.createdAt < toIso }
        if (rows.isEmpty()) return null
        return ConfidenceWindow(
            sessionCount = rows.size,
            totalSpeakingMinutes = Math.round(rows.sumOf { it.durationSeconds / 60.0 } * 10) / 10.0,
            avgWordCount = avg(rows.map { it.userWordCount.toDouble() }),
            avgWpm = avg(rows.map { it.wordsPerMinute }),
            avgFillerRate = avg(rows.map { it.fillerRate }),
            avgTalkTimeRatio = avg(rows.map { it.talkTimeRatio })
        )
    }

    /**
     * @param nowIso the current instant, @param weekAgoIso `days` days before [nowIso],
     * @param priorAgoIso `2 * days` days before [nowIso] — all in the app's local ISO timestamp
     * format (`yyyy-MM-dd'T'HH:mm:ss`), matching `SessionEntity.createdAt`.
     */
    fun computeTrend(sessions: List<SessionEntity>, nowIso: String, weekAgoIso: String, priorAgoIso: String): ConfidenceTrend {
        return ConfidenceTrend(
            thisWeek = windowStats(sessions, weekAgoIso, nowIso),
            priorWeek = windowStats(sessions, priorAgoIso, weekAgoIso)
        )
    }

    /** Percent change of [vNow] vs [vPrev], flipped so a positive result always reads as "better". */
    fun pctDelta(vNow: Double?, vPrev: Double?, lowerIsBetter: Boolean = false): Int? {
        if (vNow == null || vPrev == null || vPrev == 0.0) return null
        val pct = Math.round((vNow - vPrev) / vPrev * 100).toInt()
        return if (lowerIsBetter) -pct else pct
    }
}
