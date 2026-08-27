package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.SessionEntity
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Ported from app/db/queries.py's get_streak() / get_lifetime_stats() — all-time practice
 * totals used by milestones, the dashboard streak display, and the weekly/session share cards.
 * Counts analyzed, non-deleted sessions only, so trashed experiments don't inflate the numbers a
 * user might share. `SessionEntity.createdAt` is stored as a local (not UTC) naive timestamp
 * (`SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")` on `Date()` in MainViewModel.startSession), so no
 * timezone conversion is needed here — the first 10 characters are already the local date.
 */

data class LifetimeStats(val sessions: Int, val minutes: Int, val streak: Int, val mastered: Int)

object LifetimeStatsEngine {

    private fun analyzedNonDeleted(sessions: List<SessionEntity>): List<SessionEntity> {
        return sessions.filter { !it.rawClaudeResponse.isNullOrEmpty() && it.deletedAt == null }
    }

    private fun localDateOf(createdAt: String): LocalDate? {
        return try {
            LocalDate.parse(createdAt.take(10))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Count consecutive calendar days (local time) with at least one completed session. */
    fun computeStreak(sessions: List<SessionEntity>): Int {
        val dates = analyzedNonDeleted(sessions).mapNotNull { localDateOf(it.createdAt) }.toSet()
        if (dates.isEmpty()) return 0

        val today = LocalDate.now()
        var check = if (today in dates) today else today.minusDays(1)
        var streak = 0
        while (check in dates) {
            streak++
            check = check.minusDays(1)
        }
        return streak
    }

    /** True if at least one analyzed session was completed today (local time). */
    fun hasSessionToday(sessions: List<SessionEntity>): Boolean {
        val today = LocalDate.now()
        return analyzedNonDeleted(sessions).any { localDateOf(it.createdAt) == today }
    }

    /** Sessions that completed full analysis (for the home stats). */
    fun totalSessionsAnalyzed(sessions: List<SessionEntity>): Int {
        return sessions.count { !it.rawClaudeResponse.isNullOrEmpty() }
    }

    fun computeLifetimeStats(sessions: List<SessionEntity>, masteredCount: Int): LifetimeStats {
        val analyzed = analyzedNonDeleted(sessions)
        val totalSeconds = analyzed.sumOf { it.durationSeconds }
        return LifetimeStats(
            sessions = analyzed.size,
            minutes = Math.round(totalSeconds / 60.0).toInt(),
            streak = computeStreak(sessions),
            mastered = masteredCount
        )
    }
}
