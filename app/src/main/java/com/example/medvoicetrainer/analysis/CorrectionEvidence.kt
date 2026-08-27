package com.example.medvoicetrainer.analysis

import java.util.Locale

/** Deterministic evidence gate between generative feedback and the learner's persistent profile. */
object CorrectionEvidence {
    const val MIN_TEXT_CONFIDENCE = 0.70

    private val textCategories = setOf(
        "articles", "plurals", "verb_tense", "prepositions", "word_order",
        "word_choice", "konglish", "register", "other"
    )

    fun normalizeForEvidence(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun isLearnerRole(role: String): Boolean = role.trim().lowercase(Locale.ROOT) in
        setOf("doctor", "user", "learner")

    fun sanitizeTextCategory(raw: String?): String? {
        val rawKey = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("[\\s-]+"), "_")
            .orEmpty()
        if (rawKey.startsWith("pronunciation")) return null
        val aliased = when (rawKey) {
            "article" -> "articles"
            "plural" -> "plurals"
            "verbtense", "tense" -> "verb_tense"
            "preposition" -> "prepositions"
            "wordorder" -> "word_order"
            "wordchoice", "vocabulary" -> "word_choice"
            else -> rawKey
        }
        val normalized = L1Stats.normalizeCategory(aliased) ?: return null
        return normalized.takeIf { it in textCategories }
    }

    fun sanitizeFeedbackType(raw: String?): String =
        if (raw?.trim()?.lowercase(Locale.ROOT) == "style") "style" else "error"

    /**
     * GEC feedback must teach one reusable edit. Broad rewrites are useful style coaching but are
     * not reliable evidence of one learner error and therefore cannot enter the error tracker.
     */
    fun isAtomicMinimalEdit(original: String, corrected: String): Boolean {
        val before = normalizeForEvidence(original).split(" ").filter { it.isNotBlank() }
        val after = normalizeForEvidence(corrected).split(" ").filter { it.isNotBlank() }
        if (before.isEmpty() || after.isEmpty() || before == after) return false
        if (kotlin.math.abs(before.size - after.size) > 5) return false
        val edits = tokenEditDistance(before, after)
        val allowed = maxOf(4, kotlin.math.ceil(maxOf(before.size, after.size) * 0.5).toInt())
        return edits <= allowed
    }

    private fun tokenEditDistance(a: List<String>, b: List<String>): Int {
        val previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            var diagonal = previous[0]
            previous[0] = i + 1
            for (j in b.indices) {
                val above = previous[j + 1]
                previous[j + 1] = minOf(
                    previous[j + 1] + 1,
                    previous[j] + 1,
                    diagonal + if (a[i] == b[j]) 0 else 1
                )
                diagonal = above
            }
        }
        return previous[b.size]
    }

    /**
     * Returns the learner turn that contains [original] verbatim after punctuation/whitespace
     * normalization. A supplied turn index is authoritative and cannot silently drift to another
     * turn; a missing index may be recovered only when exactly one learner turn contains the text.
     */
    fun groundedTurnIndex(
        requestedTurnIndex: Int?,
        original: String,
        transcript: List<Pair<String, String>>
    ): Int? {
        val needle = normalizeForEvidence(original)
        if (needle.isBlank()) return null

        fun containsAt(index: Int): Boolean {
            val turn = transcript.getOrNull(index) ?: return false
            if (!isLearnerRole(turn.first)) return false
            val haystack = normalizeForEvidence(turn.second)
            return " $needle " in " $haystack "
        }

        if (requestedTurnIndex != null) {
            return requestedTurnIndex.takeIf(::containsAt)
        }
        val matches = transcript.indices.filter(::containsAt)
        return matches.singleOrNull()
    }
}
