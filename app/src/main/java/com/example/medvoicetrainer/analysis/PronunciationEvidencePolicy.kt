package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorItemEntity
import java.util.Locale

object PronunciationEvidencePolicy {
    const val OBSERVED_STATE = "observed"

    fun isPronunciation(category: String): Boolean =
        category.trim().startsWith("pronunciation", ignoreCase = true)

    fun pattern(category: String): String = category
        .substringAfter(':', "other")
        .trim()
        .lowercase()
        .ifBlank { "other" }

    /**
     * Confirmation is target-specific. Two unrelated words that both received a broad
     * "word_stress" label are not independent evidence of the same learner habit.
     */
    fun evidenceKey(category: String, target: String): String {
        val normalizedTarget = target.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "unknown_target" }
        return "pronunciation.${pattern(category)}.$normalizedTarget"
    }

    fun hasPriorSessionEvidence(
        category: String,
        target: String,
        currentSessionId: Int?,
        items: List<ErrorItemEntity>
    ): Boolean {
        if (!isPronunciation(category)) return true
        val targetEvidenceKey = evidenceKey(category, target)
        return items.any { item ->
            isPronunciation(item.category) &&
                (
                    item.patternId == targetEvidenceKey ||
                        item.key == "pron:$targetEvidenceKey"
                    ) &&
                item.lastSessionId != currentSessionId
        }
    }
}
