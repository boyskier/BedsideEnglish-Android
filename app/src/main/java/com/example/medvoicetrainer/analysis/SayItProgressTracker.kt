package com.example.medvoicetrainer.analysis

import org.json.JSONObject
import java.time.LocalDate

/**
 * Lightweight, phrase-level memory for Say It. This intentionally does not create SRS cards:
 * Say It teaches fixed high-frequency chunks, while error_items represent learner-specific
 * corrections. Only the compact result of an explicit AI check is kept locally.
 */
data class SayItPhraseProgress(
    val attempts: Int = 0,
    val comfortablePasses: Int = 0,
    val consecutivePasses: Int = 0,
    val lastOutcome: String = "",
    val focusWords: List<String> = emptyList(),
    val nextReviewDate: String = "",
    val mastered: Boolean = false,
)

object SayItProgressTracker {
    const val SETTING_KEY = "say_it_phrase_progress_v1"

    /** Clear takes in a row that finish a phrase. Public so the UI can show progress toward it. */
    const val PASS_TO_MASTER = 2

    fun phraseId(category: String, phrase: String): String =
        "${category.trim().lowercase()}|${phrase.trim().lowercase()}"

    fun read(json: String): Map<String, SayItPhraseProgress> = try {
        val root = JSONObject(json.ifBlank { "{}" })
        root.keys().asSequence().mapNotNull { id ->
            val value = root.optJSONObject(id) ?: return@mapNotNull null
            id to SayItPhraseProgress(
                attempts = value.optInt("attempts", 0).coerceAtLeast(0),
                comfortablePasses = value.optInt("comfortablePasses", 0).coerceAtLeast(0),
                consecutivePasses = value.optInt("consecutivePasses", 0).coerceAtLeast(0),
                lastOutcome = value.optString("lastOutcome"),
                focusWords = value.optJSONArray("focusWords")?.let { words ->
                    (0 until words.length()).mapNotNull { index ->
                        words.optString(index).trim().takeIf(String::isNotBlank)
                    }
                } ?: emptyList(),
                nextReviewDate = value.optString("nextReviewDate"),
                mastered = value.optBoolean("mastered", false),
            )
        }.toMap()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyMap()
    }

    fun write(progress: Map<String, SayItPhraseProgress>): String {
        val root = JSONObject()
        progress.forEach { (id, item) ->
            root.put(id, JSONObject()
                .put("attempts", item.attempts)
                .put("comfortablePasses", item.comfortablePasses)
                .put("consecutivePasses", item.consecutivePasses)
                .put("lastOutcome", item.lastOutcome)
                .put("focusWords", item.focusWords)
                .put("nextReviewDate", item.nextReviewDate)
                .put("mastered", item.mastered)
            )
        }
        return root.toString()
    }

    /**
     * COULD_NOT_ASSESS is deliberately excluded: microphone/noise failures must not become a
     * learner failure. One comfortable attempt returns in 3 days; two in a row completes it and
     * schedules an optional 14-day confidence check. Failed clarity returns on the next visit.
     */
    fun record(
        previous: SayItPhraseProgress?,
        judgment: SpeakingJudgment,
        today: LocalDate = LocalDate.now(),
    ): SayItPhraseProgress? {
        if (judgment.outcome == IntelligibilityOutcome.COULD_NOT_ASSESS) return previous
        val old = previous ?: SayItPhraseProgress()
        val passed = judgment.outcome == IntelligibilityOutcome.COMFORTABLE
        val streak = if (passed) old.consecutivePasses + 1 else 0
        val mastered = streak >= PASS_TO_MASTER
        return SayItPhraseProgress(
            attempts = old.attempts + 1,
            comfortablePasses = old.comfortablePasses + if (passed) 1 else 0,
            consecutivePasses = streak,
            lastOutcome = judgment.outcome.name,
            focusWords = if (passed) emptyList() else judgment.focusWords.take(3),
            nextReviewDate = today.plusDays(if (mastered) 14 else if (passed) 3 else 0).toString(),
            mastered = mastered,
        )
    }

    /** Sort due/weak phrases first without hiding the full phrase catalogue. */
    fun priority(
        progress: SayItPhraseProgress?,
        today: LocalDate = LocalDate.now(),
    ): Int = when {
        progress == null || progress.attempts == 0 -> 2
        !progress.mastered && progress.nextReviewDate <= today.toString() -> 0
        !progress.mastered -> 1
        else -> 3
    }
}
