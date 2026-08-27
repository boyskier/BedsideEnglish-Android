package com.example.medvoicetrainer.ui

/**
 * Normalizes the section labels that providers may include in an otherwise plain-text SOAP note.
 *
 * The evaluator occasionally returns a complete SOAP note in a single string, using either the
 * long labels ("SUBJECTIVE:") or their S/O/A/P abbreviations.  This is deliberately a display
 * concern: the response itself is left untouched and an unlabelled note is shown as-is.
 */
internal fun formatSoapForDisplay(note: String): String {
    val normalized = note.replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) return ""

    val headers = SOAP_SECTION_HEADER.findAll(normalized).toList()
    if (headers.isEmpty()) return normalized

    val sections = linkedMapOf<String, String>()
    headers.forEachIndexed { index, match ->
        val section = SOAP_SECTION_ALIASES[match.groupValues[1].uppercase()] ?: return@forEachIndexed
        val end = headers.getOrNull(index + 1)?.range?.first ?: normalized.length
        val content = normalized.substring(match.range.last + 1, end).trim()
        if (content.isNotEmpty()) sections[section] = content
    }

    // A stray label alone should not hide the rest of a model response.
    if (sections.isEmpty()) return normalized

    return SOAP_SECTION_ORDER.mapNotNull { section ->
        sections[section]?.let { "$section:\n$it" }
    }.joinToString("\n\n")
}

private val SOAP_SECTION_ORDER = listOf("S", "O", "A", "P")

private val SOAP_SECTION_ALIASES = mapOf(
    "S" to "S", "SUBJECTIVE" to "S",
    "O" to "O", "OBJECTIVE" to "O",
    "A" to "A", "ASSESSMENT" to "A",
    "P" to "P", "PLAN" to "P",
)

// Labels are accepted only at the start of a line so prose such as "plan: ..." is not split.
private val SOAP_SECTION_HEADER = Regex(
    "(?im)^(?:\\s*[-*]\\s*)?(S|O|A|P|SUBJECTIVE|OBJECTIVE|ASSESSMENT|PLAN)\\s*:\\s*"
)
