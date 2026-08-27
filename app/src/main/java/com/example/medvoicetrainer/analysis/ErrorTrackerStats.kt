package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorItemEntity
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Pure-Kotlin port of `app/db/queries.py`'s error-item stats/leech helpers and
 * `app/ui/error_tracker_window.py`'s `_progress_dots`/`_due_str` view helpers.
 *
 * Kept free of Android-framework and org.json imports so it can run under plain JVM unit
 * tests (this project has no Robolectric).
 */
object ErrorTrackerStats {

    /** Mirrors queries.py `_MASTERY_STREAK` (defaults to `SRS_MASTERY_STREAK`, else 3). */
    const val MASTERY_STREAK = 3

    /** Mirrors queries.py `_LEECH_LAPSES` (defaults to `SRS_LEECH_LAPSES`, else 4). */
    const val LEECH_LAPSES = 4

    /** Mirrors `_STATE_ICON` from error_tracker_window.py. */
    val STATE_ICON: Map<String, String> = mapOf(
        "new" to "🆕", // 🆕
        "learning" to "📈", // 📈
        "mastered" to "✅" // ✅
    )

    /** Mirrors `_STATE_ORDER` from error_tracker_window.py (learning, new, mastered, other). */
    val STATE_ORDER: Map<String, Int> = mapOf(
        "learning" to 0,
        "new" to 1,
        "mastered" to 2
    )

    data class Stats(
        val newCount: Int = 0,
        val learning: Int = 0,
        val mastered: Int = 0,
        val active: Int = 0,
        val total: Int = 0,
        val leech: Int = 0
    )

    /**
     * Mirrors `get_error_item_stats()`: counts by state, `active`/`total`/`leech`
     * rollups for accepted clinical/everyday items plus confirmed pronunciation findings.
     */
    fun stats(items: List<ErrorItemEntity>): Stats {
        val tracked = items.filter {
            (it.domain in setOf("clinical", "everyday") ||
                it.category.startsWith("pronunciation", ignoreCase = true)) &&
                it.state != PronunciationEvidencePolicy.OBSERVED_STATE
        }
        val newCount = tracked.count { it.state == "new" }
        val learning = tracked.count { it.state == "learning" }
        val mastered = tracked.count { it.state == "mastered" }
        val active = newCount + learning
        val total = active + mastered
        val leechCount = tracked.count { isLeech(it) }
        return Stats(
            newCount = newCount,
            learning = learning,
            mastered = mastered,
            active = active,
            total = total,
            leech = leechCount
        )
    }

    /** Mirrors `is_leech()`: a still-active item the student keeps failing. */
    fun isLeech(item: ErrorItemEntity): Boolean {
        return item.state != "mastered" &&
            item.state != PronunciationEvidencePolicy.OBSERVED_STATE &&
            item.lapses >= LEECH_LAPSES
    }

    /**
     * Sorts items the way `ErrorTrackerWindow.refresh()` does: by state order
     * (learning, new, mastered, then anything else), then by seen count descending.
     */
    fun sortedForDisplay(items: List<ErrorItemEntity>): List<ErrorItemEntity> {
        return items.sortedWith(
            compareBy<ErrorItemEntity> { STATE_ORDER[it.state] ?: 9 }
                .thenByDescending { it.seenCount }
        )
    }

    /** Mirrors `_progress_dots()`: filled dots for correct-streak progress toward mastery. */
    fun progressDots(item: ErrorItemEntity): String {
        val target = maxOf(1, MASTERY_STREAK)
        if (item.state == "mastered") {
            return "●".repeat(target) // ●
        }
        val streak = item.correctStreak.coerceIn(0, target)
        return "●".repeat(streak) + "○".repeat(target - streak) // ● / ○
    }

    /**
     * Mirrors `_due_str()`: "—" once mastered, "due now" for today-or-past,
     * "tomorrow" for +1 day, else "in {n}d". Falls back to the raw date's
     * first 10 chars on parse failure.
     */
    fun dueStr(item: ErrorItemEntity, now: OffsetDateTime = OffsetDateTime.now()): String {
        if (item.state == "mastered") {
            return "—" // —
        }
        val raw = item.dueAt
        return try {
            val dt = parseIsoDateTime(raw)
            // Compare in the caller's offset. The default caller is already local time, while
            // tests/importers can supply UTC without results changing with the machine timezone.
            val localDate = dt.withOffsetSameInstant(now.offset).toLocalDate()
            val nowDate = now.toLocalDate()
            val days = java.time.temporal.ChronoUnit.DAYS.between(nowDate, localDate)
            when {
                days <= 0 -> "due now"
                days == 1L -> "tomorrow"
                else -> "in ${days}d"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            raw.take(10)
        }
    }

    /** Mirrors Python's `datetime.fromisoformat` + naive-defaults-to-UTC-then-astimezone(). */
    private fun parseIsoDateTime(raw: String): OffsetDateTime {
        return try {
            OffsetDateTime.parse(raw)
        } catch (e: DateTimeParseException) {
            // No offset in the string (naive) -> treat as UTC, like the Python code does.
            val localDateTime = java.time.LocalDateTime.parse(raw)
            localDateTime.atOffset(ZoneOffset.UTC)
        }
    }

    /** Mirrors the window's `mistake = f'{was}  ->  {aim}' if was else aim` formatting. */
    fun mistakeLine(item: ErrorItemEntity): String {
        val was = item.original.trim()
        val aim = item.corrected.trim()
        return if (was.isNotEmpty()) "$was  →  $aim" else aim
    }

    /** Mirrors the window's icon composition: "⚠ " prefix for leeches, plus the state icon. */
    fun stateIcon(item: ErrorItemEntity): String {
        val icon = STATE_ICON[item.state] ?: ""
        return if (isLeech(item)) "⚠ $icon" else icon
    }
}
