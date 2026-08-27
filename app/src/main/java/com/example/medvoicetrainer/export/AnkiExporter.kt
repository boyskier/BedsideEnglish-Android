package com.example.medvoicetrainer.export

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ported from app/export/anki_exporter.py — export session corrections as Anki flashcards.
 *
 * The Python original wrote a binary `.apkg` deck via the `genanki` library. There is no Kotlin
 * equivalent of `genanki` (it hand-builds an Anki-schema SQLite collection), and reimplementing
 * that schema blind — or integrating AnkiDroid's ContentProvider API, whose exact column/URI
 * contract can't be verified without network access to current docs — risks a silently broken
 * export. Anki (both desktop and AnkiDroid) has a second, simpler, long-stable import path:
 * tab-separated plain text with `#`-prefixed header directives (File > Import). This ports the
 * card-extraction logic exactly and targets that format instead of `.apkg`.
 */

data class AnkiCard(val front: String, val back: String, val tags: List<String>)

/**
 * One row of the SRS mistake list, flattened to plain strings so [AnkiExporter] stays free of any
 * dependency on Room entities (and remains unit-testable without Android).
 */
data class SrsExportItem(
    val original: String,
    val corrected: String,
    val explanation: String,
    val category: String
)

object AnkiExporter {

    /**
     * Build cards straight from the SRS mistake list. Unlike [extractCards], these rows are not
     * session-shaped: `error_items` already holds one de-duplicated original/corrected pair per
     * row with its own category, so there is nothing to unwrap and no per-session date to tag.
     */
    fun extractSrsCards(items: List<SrsExportItem>): List<AnkiCard> =
        items.mapNotNull { item ->
            val front = item.original.trim()
            val corrected = item.corrected.trim()
            if (front.isEmpty() || corrected.isEmpty()) return@mapNotNull null
            val explanation = item.explanation.trim()
            AnkiCard(
                front = front,
                back = corrected + if (explanation.isEmpty()) "" else " — $explanation",
                tags = listOf(
                    item.category.ifBlank { "other" }.replace(" ", "_"),
                    "mvt-srs"
                )
            )
        }

    /**
     * Extract Anki cards from one or more sessions. Mirrors the Python original's tag scheme:
     * each card gets its own `tags`, plus `mvt-<mode>` and `date-<YYYY-MM-DD>`.
     */
    fun extractCards(sessions: List<Map<String, Any?>>): List<AnkiCard> {
        val cards = mutableListOf<AnkiCard>()
        for (session in sessions) {
            var analysis: JSONObject = JSONObject()
            val rawResponse = session["raw_claude_response"] as? String
            if (!rawResponse.isNullOrEmpty()) {
                analysis = try {
                    JSONObject(rawResponse)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    JSONObject()
                }
            }
            val auditedCorrections = try {
                val raw = session["corrections"] as? String
                if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                JSONArray()
            }
            val hasDecisionAudit = (0 until auditedCorrections.length()).any { index ->
                auditedCorrections.optJSONObject(index)?.has("decision") == true
            }
            // New sessions treat Gemini corrections as pending suggestions. Ignore model-created
            // cards and export only items the learner explicitly accepted on the feedback screen.
            if (hasDecisionAudit) {
                val acceptedCards = JSONArray()
                for (i in 0 until auditedCorrections.length()) {
                    val correction = auditedCorrections.optJSONObject(i) ?: continue
                    if (!correction.optString("decision").equals("accepted", ignoreCase = true)) continue
                    val original = correction.optString("original", "").trim()
                    val corrected = correction.optString("corrected", "").trim()
                    if (original.isEmpty() || corrected.isEmpty()) continue
                    val explanation = correction.optString("explanation", "").trim()
                    acceptedCards.put(
                        JSONObject()
                            .put("front", original)
                            .put("back", corrected + if (explanation.isEmpty()) "" else " — $explanation")
                            .put("tags", JSONArray().put(correction.optString("category", "other")))
                    )
                }
                analysis.put("anki_cards", acceptedCards)
            }
            // A session can carry the generated cards in their dedicated DB column even when
            // raw_claude_response also contains scores/metrics. The old guard only checked that
            // the whole analysis object was empty, so a perfectly valid non-empty analysis with
            // cards stored separately exported nothing.
            if (!hasDecisionAudit && (analysis.optJSONArray("anki_cards")?.length() == null ||
                analysis.optJSONArray("anki_cards")?.length() == 0
            )) {
                val ankiCardsRaw = session["anki_cards"] as? String
                if (!ankiCardsRaw.isNullOrEmpty()) {
                    try {
                        analysis.put("anki_cards", JSONArray(ankiCardsRaw))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // fall through to the correction-derived compatibility cards below
                    }
                }
            }

            var cardsArr = analysis.optJSONArray("anki_cards") ?: JSONArray()
            // Older Android sessions were never asked to return anki_cards even though their
            // corrections were saved. Keep export useful for that existing history by deriving
            // one recall card per correction instead of writing an empty file.
            if (cardsArr.length() == 0 && !hasDecisionAudit) {
                val corrections = analysis.optJSONArray("corrections") ?: run {
                    val rawCorrections = session["corrections"] as? String
                    try {
                        if (rawCorrections.isNullOrBlank()) JSONArray() else JSONArray(rawCorrections)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        JSONArray()
                    }
                }
                val derived = JSONArray()
                for (i in 0 until corrections.length()) {
                    val correction = corrections.optJSONObject(i) ?: continue
                    val original = correction.optString("original", "").trim()
                    val corrected = correction.optString("corrected", "").trim()
                    if (original.isEmpty() || corrected.isEmpty()) continue
                    val explanation = correction.optString("explanation", "").trim()
                    val category = correction.optString("category", "other")
                    derived.put(
                        JSONObject()
                            .put("front", original)
                            .put("back", corrected + if (explanation.isEmpty()) "" else " — $explanation")
                            .put("tags", JSONArray().put(category))
                    )
                }
                cardsArr = derived
            }
            val createdAt = (session["created_at"]?.toString() ?: "").take(10)
            val mode = (session["mode"] as? String ?: "session")
            val cleanMode = mode.replace(" ", "_")

            for (i in 0 until cardsArr.length()) {
                val card = cardsArr.optJSONObject(i) ?: continue
                val tagsArr = card.optJSONArray("tags") ?: JSONArray()
                val cleanTags = (0 until tagsArr.length()).map { tagsArr.optString(it, "").replace(" ", "_") }
                val tags = cleanTags + listOf("mvt-$cleanMode", "date-$createdAt")
                cards.add(
                    AnkiCard(
                        front = card.optString("front", ""),
                        back = card.optString("back", ""),
                        tags = tags
                    )
                )
            }
        }
        return cards
    }

    /**
     * Render cards as Anki's tab-separated plain-text import format. Anki's plain-text importer
     * is line-based, so literal tabs/newlines inside a field are flattened to spaces.
     */
    fun buildAnkiImportText(cards: List<AnkiCard>): String {
        fun clean(s: String) = s.replace("\t", " ").replace("\n", " ").replace("\r", " ").trim()
        fun cleanFront(s: String): String {
            val c = clean(s)
            return if (c.startsWith("#")) "&#35;" + c.substring(1) else c
        }

        val lines = mutableListOf(
            "#separator:tab",
            "#html:false",
            "#columns:Front\tBack\tTags",
            "#tags column:3"
        )
        for (card in cards) {
            lines.add("${cleanFront(card.front)}\t${clean(card.back)}\t${card.tags.joinToString(" ")}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Render cards as CSV. Anki imports comma-separated files through the same importer as the
     * tab-separated one (the `#separator:comma` directive selects it), so the file stays a
     * one-tap Anki import *and* opens directly in Sheets/Excel/Numbers — which the `.txt` form
     * did not: most share targets rendered it as an unreadable wall of text.
     *
     * Every field is quoted unconditionally. Beyond being valid RFC 4180, it guarantees no data
     * row can ever begin with `#`, which Anki would otherwise skip as a comment line.
     */
    fun buildCsv(cards: List<AnkiCard>): String {
        // Anki's importer is line-based, so a literal newline inside a field would split the note
        // even when the field is quoted. Flatten separators exactly as the tab format does.
        fun clean(s: String) = s.replace("\t", " ").replace("\n", " ").replace("\r", " ").trim()
        fun quote(s: String) = "\"" + clean(s).replace("\"", "\"\"") + "\""

        val lines = mutableListOf(
            "#separator:comma",
            "#html:false",
            "#columns:Front,Back,Tags",
            "#tags column:3"
        )
        for (card in cards) {
            lines.add(
                listOf(quote(card.front), quote(card.back), quote(card.tags.joinToString(" ")))
                    .joinToString(",")
            )
        }
        return lines.joinToString("\n")
    }

    /**
     * Export sessions to Anki import text. Returns null when there were no cards (mirrors the
     * Python original returning None rather than writing an empty file) — callers should tell
     * the user instead of claiming a deck was saved.
     */
    fun exportSessionsToImportText(sessions: List<Map<String, Any?>>): String? {
        val cards = extractCards(sessions)
        if (cards.isEmpty()) return null
        return buildAnkiImportText(cards)
    }

    /** [exportSessionsToImportText] in CSV form — see [buildCsv] for why that is the shared format. */
    fun exportSessionsToCsv(sessions: List<Map<String, Any?>>): String? {
        val cards = extractCards(sessions)
        if (cards.isEmpty()) return null
        return buildCsv(cards)
    }
}
