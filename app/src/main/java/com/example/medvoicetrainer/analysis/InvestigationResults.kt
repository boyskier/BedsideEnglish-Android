package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One authored, learner-visible result. Values are strings so qualitative ECG/imaging and
 * ordinary numeric laboratory results use the same small, forward-compatible table model. */
data class ClinicalResult(
    val id: String,
    val test: String,
    val value: String,
    val unit: String = "",
    val referenceRange: String = "",
    val flag: String = "",
    val specimen: String = "",
    val collectedAt: String = "",
    val note: String = "",
)

/** A result bundle that becomes available after the learner orders one of [orderTerms]. */
data class InvestigationEvent(
    val id: String,
    val title: String,
    val orderTerms: List<String>,
    val results: List<ClinicalResult>,
    val availabilityMessage: String = "",
)

/**
 * Optional case-data bridge for pre-encounter results and deterministic result-release events.
 *
 * This intentionally never asks an AI to invent a value. Invalid or incomplete authored rows are
 * omitted, and an event without a stable id, trigger term, or result can never fire.
 */
object InvestigationResults {
    private const val ACTION =
        "order|obtain|check|run|send|draw|request|perform|repeat|arrange"
    private const val PLANNED_ACTION = "$ACTION|get|do|take"

    fun parseAvailableResults(caseJson: String): List<ClinicalResult> = runCatching {
        val root = JSONObject(caseJson.ifBlank { "{}" })
        parseResultArray(root.optJSONArray("available_results"), "available")
    }.getOrDefault(emptyList())

    fun parseEvents(caseJson: String): List<InvestigationEvent> = runCatching {
        val root = JSONObject(caseJson.ifBlank { "{}" })
        val array = root.optJSONArray("investigation_events") ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                val terms = stringList(item.optJSONArray("order_terms"))
                    .ifEmpty { stringList(item.optJSONArray("aliases")) }
                    .ifEmpty { listOf(title) }
                    .map(::normalizeTerm)
                    .filter { it.length >= 2 }
                    .distinct()
                val results = parseResultArray(item.optJSONArray("results"), id.ifEmpty { "event_$index" })
                if (id.isEmpty() || title.isEmpty() || terms.isEmpty() || results.isEmpty()) continue
                add(
                    InvestigationEvent(
                        id = id,
                        title = title,
                        orderTerms = terms,
                        results = results,
                        availabilityMessage = item.optString("availability_message").trim(),
                    )
                )
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    /** Return every newly ordered event in authoring order. No network or model call is involved. */
    fun detectOrders(
        learnerTurn: String,
        events: List<InvestigationEvent>,
        alreadyTriggeredIds: Set<String> = emptySet(),
    ): List<InvestigationEvent> {
        val text = normalizeText(learnerTurn)
        if (text.isEmpty()) return emptyList()
        return events.filter { event ->
            event.id !in alreadyTriggeredIds && event.orderTerms.any { term ->
                orderedTermRegex(term).any { it.containsMatchIn(text) }
            }
        }
    }

    fun valueWithUnit(result: ClinicalResult): String =
        listOf(result.value, result.unit).filter(String::isNotBlank).joinToString(" ")

    private fun parseResultArray(array: JSONArray?, parentId: String): List<ClinicalResult> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val test = item.optString("test").trim()
                val value = item.optString("value").trim()
                if (test.isEmpty() || value.isEmpty()) continue
                add(
                    ClinicalResult(
                        id = item.optString("id").trim().ifEmpty { "${parentId}_$index" },
                        test = test,
                        value = value,
                        unit = item.optString("unit").trim(),
                        referenceRange = item.optString("reference_range").trim(),
                        flag = item.optString("flag").trim().lowercase(Locale.ROOT),
                        specimen = item.optString("specimen").trim(),
                        collectedAt = item.optString("collected_at").trim(),
                        note = item.optString("note").trim(),
                    )
                )
            }
        }.distinctBy { it.id }
    }

    private fun stringList(array: JSONArray?): List<String> = if (array == null) emptyList() else {
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace(Regex("[^a-z0-9+./' -]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTerm(value: String): String = normalizeText(value)

    /**
     * High-precision spoken-order shapes. Broad words such as "do", "get", "need", and "want"
     * are accepted only inside a first-person/team plan, never merely because they occur somewhere
     * near a test name. This prevents history questions ("Did you get an ECG before?"), education
     * ("Do you know what troponin is?"), and result discussion from opening a result card.
     */
    private fun orderedTermRegex(term: String): List<Regex> {
        val target = Regex.escape(term)
        // Mandatory whitespace before the authored term, with at most eight intervening words.
        // This still catches "order an ECG and troponin" without letting intent elsewhere in a
        // long utterance accidentally license a bare test-name mention.
        val gap = "(?:\\s+[a-z0-9+./'-]+[,.]?){0,8}\\s+"
        val options = setOf(RegexOption.IGNORE_CASE)
        return listOf(
            // "I'll order...", "we should get...", "let's do...", "I want to check..."
            Regex(
                "\\b(?:i(?:'d| would) like to|i(?:'ll| will)|we(?:'ll| will)|" +
                    "let's|lets|let us|i (?:need|want) to|we (?:need|want) to|" +
                    "i think we should|we should|we must)\\s+(?:$PLANNED_ACTION)\\b$gap" +
                    "(?<![a-z0-9])$target(?![a-z0-9])",
                options,
            ),
            // Explicit clinical action: "Order a troponin", "Please obtain an ECG".
            Regex(
                "(?:^|[.!?]\\s+|\\bplease\\s+)\\s*(?:$ACTION)\\b$gap" +
                    "(?<![a-z0-9])$target(?![a-z0-9])",
                options,
            ),
            // Polite team request, with the action verb mandatory.
            Regex(
                "\\b(?:can|could|would) (?:we|you)\\s+(?:please\\s+)?(?:$PLANNED_ACTION)\\b$gap" +
                    "(?<![a-z0-9])$target(?![a-z0-9])",
                options,
            ),
            // Common clipped ED speech: "ECG please", "troponin now", "CBC STAT".
            Regex(
                "(?<![a-z0-9])$target(?![a-z0-9])(?:\\s+\\w+[,.]?){0,3}\\s+" +
                    "(?:please|now|stat|as soon as possible)\\b",
                options,
            ),
        )
    }
}
