package com.example.medvoicetrainer.analysis

import java.text.Normalizer
import java.util.Locale

/** Small catalog-only search document. Full case JSON remains lazy-loaded after selection. */
data class EncounterSearchDocument(
    val title: String,
    val description: String,
    val diagnosis: String,
    val keywords: String,
    val specialty: String,
)

/** Pre-normalizes the catalog once; only the short query is normalized while the user types. */
class EncounterCaseSearchIndex(documents: Map<String, EncounterSearchDocument>) {
    private val documents = documents.mapValues { (_, document) -> document.prepare() }

    fun scores(query: String): Map<String, Int> {
        val normalizedQuery = normalizeEncounterSearchText(query)
        if (normalizedQuery.isBlank()) return emptyMap()
        return buildMap {
            documents.forEach { (id, document) ->
                scorePreparedDocument(document, normalizedQuery)?.let { put(id, it) }
            }
        }
    }
}

private data class PreparedEncounterSearchDocument(
    val title: String,
    val description: String,
    val diagnosis: String,
    val keywords: String,
    val specialty: String,
)

private fun EncounterSearchDocument.prepare() = PreparedEncounterSearchDocument(
    title = normalizeEncounterSearchText(title),
    description = normalizeEncounterSearchText(description),
    diagnosis = normalizeEncounterSearchText(diagnosis),
    keywords = normalizeEncounterSearchText(keywords),
    specialty = normalizeEncounterSearchText(specialty),
)

/**
 * Returns a relevance score, or null when every query token is not present. This is deliberately
 * literal substring search (not Regex): medical fragments such as "trichomonas" match
 * "trichomoniasis", punctuation cannot crash it, and multi-word queries require every word.
 */
fun encounterSearchScore(document: EncounterSearchDocument, query: String): Int? {
    val normalizedQuery = normalizeEncounterSearchText(query)
    if (normalizedQuery.isBlank()) return 0
    return scorePreparedDocument(document.prepare(), normalizedQuery)
}

private fun scorePreparedDocument(document: PreparedEncounterSearchDocument, normalizedQuery: String): Int? {
    val title = document.title
    val description = document.description
    val diagnosis = document.diagnosis
    val keywords = document.keywords
    val specialty = document.specialty
    val allText = "$title $description $diagnosis $keywords $specialty"
    val tokens = normalizedQuery.split(' ').filter(String::isNotBlank).distinct()
    if (tokens.any { !medicalTokenMatches(allText, it) }) return null

    var score = 0
    score += when {
        diagnosis == normalizedQuery -> 1_000
        diagnosis.startsWith(normalizedQuery) -> 800
        normalizedQuery in diagnosis -> 650
        title == normalizedQuery -> 600
        title.startsWith(normalizedQuery) -> 500
        normalizedQuery in title -> 400
        normalizedQuery in description -> 180
        normalizedQuery in keywords -> 120
        else -> 0
    }
    tokens.forEach { token ->
        if (medicalTokenMatches(diagnosis, token)) score += 160
        if (medicalTokenMatches(title, token)) score += 100
        if (medicalTokenMatches(description, token)) score += 40
        if (medicalTokenMatches(keywords, token)) score += 25
        if (medicalTokenMatches(specialty, token)) score += 20
    }
    return score
}

/** Covers common disease stem changes (trichomonas -> trichomoniasis) without a heavy fuzzy index. */
private fun medicalTokenMatches(text: String, token: String): Boolean =
    token in text || (token.length >= 9 && token.take(8) in text)

internal fun normalizeEncounterSearchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
