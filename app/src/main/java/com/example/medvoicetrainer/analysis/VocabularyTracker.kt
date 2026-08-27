package com.example.medvoicetrainer.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ported from app/analysis/vocabulary_tracker.py */

data class VocabTerm(
    val jargon: String,
    val laymanTerm: String,
    val category: String
)

data class VocabularyCoverage(
    val usedCount: Int,
    val totalCount: Int,
    val percentage: Int,
    val unlocked: List<VocabTerm>,
    val locked: List<VocabTerm>
)

object VocabularyTracker {

    /**
     * One wordlist entry with its needles already lower-cased.
     *
     * The wordlist is immutable APK content, but the coverage scan is re-run whenever the session
     * list changes and (via the Dashboard's `remember`) on the caller's thread. Re-reading and
     * re-parsing `oet_wordlist.json` plus re-deriving the display strings and lower-cased synonym
     * lists on every one of those runs was pure repeated work, so it is all precomputed once here.
     */
    private class PreparedTerm(
        val term: VocabTerm,
        val needles: List<String>,
    )

    // SoftReference so a memory-constrained device can reclaim the prepared list; it is cheap to
    // rebuild relative to the scan it serves.
    private var cachedTerms: java.lang.ref.SoftReference<List<PreparedTerm>>? = null

    @Synchronized
    private fun preparedTerms(context: Context): List<PreparedTerm> {
        cachedTerms?.get()?.let { return it }
        val prepared = prepareTerms(loadWordlist(context))
        // An empty list means the asset read/parse failed; don't cache that permanently.
        if (prepared.isNotEmpty()) cachedTerms = java.lang.ref.SoftReference(prepared)
        return prepared
    }

    private fun loadWordlist(context: Context): JSONObject {
        return try {
            context.assets.open("oet_wordlist.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun titleCase(value: String): String =
        value.replace("_", " ").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun prepareTerms(wordlist: JSONObject): List<PreparedTerm> {
        if (wordlist.length() == 0) return emptyList()
        val prepared = mutableListOf<PreparedTerm>()
        for (category in wordlist.keys()) {
            val items = wordlist.optJSONArray(category) ?: continue
            if (category == "communication_phrases") {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val intent = item.optString("intent", "")
                    val phrasesArr = item.optJSONArray("phrases") ?: JSONArray()
                    val phrases = (0 until phrasesArr.length()).map { phrasesArr.getString(it) }
                    prepared += PreparedTerm(
                        term = VocabTerm(
                            jargon = titleCase(intent),
                            laymanTerm = phrases.joinToString(", "),
                            category = "Communication Phrases",
                        ),
                        needles = phrases.map { it.lowercase() },
                    )
                }
            } else {
                val catName = titleCase(category)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val layman = item.optString("layman_term", "")
                    prepared += PreparedTerm(
                        term = VocabTerm(
                            jargon = item.optString("jargon", ""),
                            laymanTerm = layman,
                            category = catName,
                        ),
                        // Matches the previous `layman.split(",")` synonym handling, minus the
                        // empty entries it explicitly skipped.
                        needles = layman.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                    )
                }
            }
        }
        return prepared
    }

    fun calculateVocabularyCoverage(context: Context, transcripts: List<String>): VocabularyCoverage {
        val terms = preparedTerms(context)
        if (terms.isEmpty()) {
            return VocabularyCoverage(0, 0, 0, emptyList(), emptyList())
        }

        val allUserText = StringBuilder(" ")
        for (tJson in transcripts) {
            try {
                val turns = JSONArray(tJson)
                for (i in 0 until turns.length()) {
                    val turn = turns.getJSONObject(i)
                    if (turn.optString("role") == "user") {
                        allUserText.append(turn.optString("text", "").lowercase()).append(" ")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                continue
            }
        }
        val fullText = allUserText.toString()

        val unlocked = mutableListOf<VocabTerm>()
        val locked = mutableListOf<VocabTerm>()
        for (prepared in terms) {
            if (prepared.needles.any { fullText.contains(it) }) {
                unlocked.add(prepared.term)
            } else {
                locked.add(prepared.term)
            }
        }

        val total = unlocked.size + locked.size
        val percentage = if (total > 0) (unlocked.size.toDouble() / total * 100).toInt() else 0

        return VocabularyCoverage(
            usedCount = unlocked.size,
            totalCount = total,
            percentage = percentage,
            unlocked = unlocked,
            locked = locked
        )
    }
}
