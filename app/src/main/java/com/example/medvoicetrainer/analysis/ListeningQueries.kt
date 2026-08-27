package com.example.medvoicetrainer.analysis

/**
 * Ported from app/db/listening_queries.py — spaced-repetition scheduling for Listening Lab
 * drill attempts. Pure computation only; DB reads (previous interval) and writes stay in
 * Repository, matching this project's "engines take already-fetched data" convention.
 *
 * Not ported: listening_queries.py's explicit unaided_accuracy/assisted_accuracy override
 * branch (a caller-supplied split bypassing assistance detection) — no Kotlin UI caller
 * supplies those fields yet, so only the implicit replay/reveal/repair-based detection
 * branch is implemented.
 */
object ListeningQueries {
    val INTERVAL_LADDER = intArrayOf(1, 3, 7, 14, 30)

    fun nextIntervalDays(previousIntervalDays: Int?, unaidedAccuracy: Double?, firstPass: Boolean): Int {
        val previous = previousIntervalDays ?: 1
        if (unaidedAccuracy == null) return 1
        if (unaidedAccuracy < 0.75) return 1
        if (unaidedAccuracy < 1.0 || !firstPass) return minOf(3, previous)
        for (interval in INTERVAL_LADDER) {
            if (interval > previous) return interval
        }
        return INTERVAL_LADDER.last()
    }

    data class ComputedAttempt(
        val detailsCorrect: Int,
        val detailsTotal: Int,
        val assistance: Boolean,
        val unaidedAccuracy: Double?,
        val unaidedCorrect: Int?,
        val unaidedTotal: Int?,
        val assistedAccuracy: Double?,
        val assistedCorrect: Int?,
        val assistedTotal: Int?,
        val answerPhase: String,
        val firstPass: Boolean,
        val intervalDays: Int
    )

    fun computeAttempt(
        detailsCorrect: Int,
        detailsTotal: Int,
        replayCount: Int = 0,
        cleanReplayCount: Int = 0,
        revealCount: Int = 0,
        repairCount: Int = 0,
        firstPassCorrectClaim: Boolean = true,
        previousIntervalDays: Int? = null
    ): ComputedAttempt {
        val total = maxOf(0, detailsTotal)
        val correct = maxOf(0, minOf(total, detailsCorrect))
        val accuracy = if (total > 0) correct.toDouble() / total else 0.0
        val assistance = replayCount > 0 || cleanReplayCount > 0 || revealCount > 0 || repairCount > 0

        val unaidedAccuracy: Double?
        val unaidedCorrect: Int?
        val unaidedTotal: Int?
        val assistedAccuracy: Double?
        val assistedCorrect: Int?
        val assistedTotal: Int?
        if (assistance) {
            unaidedAccuracy = null
            unaidedCorrect = null
            unaidedTotal = null
            assistedAccuracy = accuracy
            assistedCorrect = correct
            assistedTotal = total
        } else {
            unaidedAccuracy = accuracy
            unaidedCorrect = correct
            unaidedTotal = total
            assistedAccuracy = null
            assistedCorrect = null
            assistedTotal = null
        }
        val answerPhase = if (unaidedAccuracy != null) "unaided" else "assisted"
        val firstPass = firstPassCorrectClaim && unaidedAccuracy == 1.0 && !assistance
        val intervalDays = nextIntervalDays(previousIntervalDays, unaidedAccuracy, firstPass)
        return ComputedAttempt(
            detailsCorrect = correct,
            detailsTotal = total,
            assistance = assistance,
            unaidedAccuracy = unaidedAccuracy,
            unaidedCorrect = unaidedCorrect,
            unaidedTotal = unaidedTotal,
            assistedAccuracy = assistedAccuracy,
            assistedCorrect = assistedCorrect,
            assistedTotal = assistedTotal,
            answerPhase = answerPhase,
            firstPass = firstPass,
            intervalDays = intervalDays
        )
    }
}
