package com.example.medvoicetrainer.analysis

import java.util.Locale

/**
 * Stable rule identity for longitudinal tracking. Surface-string similarity remains a fallback
 * for legacy data, but new corrections are grouped by the rule that must transfer to new speech.
 */
object CorrectionPatternId {
    private val safeId = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,3}$")
    private val articles = setOf("a", "an", "the")
    private val prepositions = setOf(
        "in", "on", "at", "to", "of", "for", "with", "from", "by", "about", "since",
        "during", "after", "before", "into", "over", "under", "between"
    )

    fun sanitize(raw: String?, category: String): String? {
        val value = raw.orEmpty().trim().lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace(Regex("\\s+"), "_")
        if (!safeId.matches(value)) return null
        val canonical = L1Stats.normalizeCategory(category) ?: return null
        return value.takeIf { it.substringBefore('.') == canonical }
    }

    fun derive(
        raw: String?,
        category: String,
        original: String,
        corrected: String
    ): String {
        sanitize(raw, category)?.let { return it }
        val canonical = L1Stats.normalizeCategory(category) ?: "other"
        val before = tokens(original)
        val after = tokens(corrected)
        val added = after.toMutableList().also { list -> before.forEach { list.remove(it) } }
        val removed = before.toMutableList().also { list -> after.forEach { list.remove(it) } }

        return when (canonical) {
            "articles" -> when {
                added.any { it == "a" || it == "an" } && removed.none { it in articles } ->
                    "articles.missing_indefinite"
                added.contains("the") && removed.none { it in articles } ->
                    "articles.missing_definite"
                removed.any { it in articles } && added.none { it in articles } ->
                    "articles.unnecessary"
                else -> "articles.choice"
            }
            "plurals" -> when {
                removed.any { singular -> added.any { it == "${singular}s" || it == "${singular}es" } } ->
                    "plurals.missing_plural"
                else -> "plurals.countability"
            }
            "verb_tense" -> when {
                added.any { it in setOf("do", "does", "did", "have", "has", "had") } ->
                    "verb_tense.missing_auxiliary"
                else -> "verb_tense.tense_aspect"
            }
            "prepositions" -> {
                val replacement = added.firstOrNull { it in prepositions }
                val removedPrep = removed.firstOrNull { it in prepositions }
                if (replacement != null && removedPrep != null) {
                    "prepositions.${removedPrep}_to_$replacement"
                } else {
                    "prepositions.selection"
                }
            }
            "word_order" -> "word_order.question_or_clause"
            "word_choice" -> "word_choice.collocation"
            "konglish" -> "konglish.contextual_lexeme"
            "register" -> "register.pragmatic_softening"
            else -> {
                val surface = after.take(4).joinToString("_")
                    .replace(Regex("[^a-z0-9_]+"), "")
                    .ifBlank { "unclassified" }
                "other.surface_$surface"
            }
        }
    }

    private fun tokens(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
}
