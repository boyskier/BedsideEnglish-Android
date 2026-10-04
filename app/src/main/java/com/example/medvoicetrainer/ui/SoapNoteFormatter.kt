package com.example.medvoicetrainer.ui

import java.util.Locale
import org.json.JSONObject

/**
 * The exact marker the evaluator must emit for a history area the learner never asked about.
 *
 * It is deliberately one shared constant: [EvalPromptBuilder][com.example.medvoicetrainer.analysis.EvalPromptBuilder]
 * instructs the model to write this literal string, this formatter fills it in for a missing
 * subsection, and the renderers ([NOT_ELICITED_MARKER]) colour it. If the three ever drift, an
 * unasked question would silently read like an answered one.
 */
internal const val SOAP_NOT_ELICITED = "Not elicited"

/**
 * A history-taking area inside the SOAP note's Subjective section.
 *
 * The six subsections are the teaching skeleton the app grades a history against; [keys] lists the
 * JSON names accepted from the model (its own preferred key first) so a provider that answers
 * "chief_complaint" instead of "cc" is not silently dropped.
 */
internal data class SoapSubjectiveSubsection(val label: String, val keys: List<String>)

internal val SOAP_SUBJECTIVE_SUBSECTIONS = listOf(
    SoapSubjectiveSubsection("CC", listOf("cc", "chief_complaint")),
    SoapSubjectiveSubsection(
        "PI/HPI",
        listOf("hpi", "pi", "pi_hpi", "present_illness", "history_of_present_illness")
    ),
    SoapSubjectiveSubsection("PMHx", listOf("pmhx", "pmh", "past_medical_history")),
    SoapSubjectiveSubsection("FHx", listOf("fhx", "fh", "family_history")),
    SoapSubjectiveSubsection("SHx", listOf("shx", "sh", "social_history")),
    SoapSubjectiveSubsection("ROS", listOf("ros", "review_of_systems")),
)

/** Every "Not elicited" marker in the note, so a renderer can style it without re-parsing. */
private val NOT_ELICITED_MARKER = Regex("(?i)\\bnot\\s+elicited\\b")

internal fun notElicitedRanges(text: String): List<IntRange> =
    NOT_ELICITED_MARKER.findAll(text).map { it.range }.toList()

/**
 * Renders the Subjective section of an AI SOAP note.
 *
 * A model that returns the structured six-subsection object is flattened to one labelled line per
 * subsection, in teaching order, with [SOAP_NOT_ELICITED] filled in for anything the model left
 * blank or omitted — an absent key means the learner never covered that area, which is exactly the
 * thing the note must state out loud rather than leave silent. Any other shape (a plain string
 * from an older response, a stored legacy note) is returned untouched, so existing sessions and
 * providers that ignore the schema keep rendering as before.
 */
internal fun formatSoapSubjective(value: Any?): String {
    if (value == null || value == JSONObject.NULL) return ""
    if (value !is JSONObject) return value.toString().trim()

    val byKey = linkedMapOf<String, String>()
    value.keys().forEach { rawKey ->
        val text = value.opt(rawKey)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()
        byKey[normalizeSubjectiveKey(rawKey)] = text
    }

    val consumed = mutableSetOf<String>()
    val lines = SOAP_SUBJECTIVE_SUBSECTIONS.map { section ->
        consumed += section.keys
        val text = section.keys.firstNotNullOfOrNull { key -> byKey[key]?.takeIf { it.isNotEmpty() } }
        "${section.label}: ${text ?: SOAP_NOT_ELICITED}"
    }
    // An unexpected extra key is content the learner may still need; show it rather than lose it.
    val extras = byKey.entries
        .filter { it.key !in consumed && it.value.isNotEmpty() }
        .map { "${it.key.uppercase(Locale.ROOT)}: ${it.value}" }

    return (lines + extras).joinToString("\n")
}

/**
 * Flattens the evaluator's `soap_note` object into the single labelled string the session row,
 * the tutor prompt and the presentation snapshot all store.
 *
 * S/O/A/P order is imposed; unknown sections are appended rather than dropped. Only "subjective"
 * is structured — it renders as a block of subsection lines under its own label, which keeps
 * [formatSoapForDisplay] parsing it as one S section.
 */
internal fun flattenSoapNote(value: Any?): String {
    if (value == null || value == JSONObject.NULL) return ""
    if (value !is JSONObject) return value.toString().trim()
    val preferred = listOf("subjective", "objective", "assessment", "plan")
    val keys = preferred.filter { value.has(it) } +
        value.keys().asSequence().filter { it !in preferred }.toList()
    return keys.distinct().mapNotNull { key ->
        val raw = value.opt(key)?.takeUnless { it == JSONObject.NULL }
        val text = if (key == "subjective") formatSoapSubjective(raw) else raw?.toString()?.trim()
        text?.takeIf { it.isNotEmpty() }?.let { body ->
            val label = key.uppercase(Locale.ROOT)
            // The subsection block starts on its own line so the first subsection is not swallowed
            // by the section label. Every other section keeps the original one-line-per-section
            // form, so notes stored before this change and notes stored after it look the same.
            if (key == "subjective" && body.contains('\n')) "$label:\n$body" else "$label: $body"
        }
    }.joinToString("\n")
}

private fun normalizeSubjectiveKey(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

/**
 * Normalizes the section labels that providers may include in an otherwise plain-text SOAP note.
 *
 * The evaluator occasionally returns a complete SOAP note in a single string, using either the
 * long labels ("SUBJECTIVE:") or their S/O/A/P abbreviations.  This is deliberately a display
 * concern: the response itself is left untouched and an unlabelled note is shown as-is. The
 * Subjective subsection labels (CC:, PI/HPI:, …) are not S/O/A/P labels, so they stay inside the
 * section body untouched.
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
