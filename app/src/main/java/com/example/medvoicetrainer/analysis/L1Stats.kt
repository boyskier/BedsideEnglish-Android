package com.example.medvoicetrainer.analysis

import org.json.JSONArray

/**
 * Aggregate correction categories across sessions for the L1-interference chart.
 *
 * Faithful port of `app/analysis/l1_stats.py`. The analysis prompt asks the
 * evaluator to tag every correction with an error `category` (articles, plurals,
 * konglish, …); this rolls those tags up into a frequency table so the Dashboard
 * can show where a Korean L1 speaker's errors actually cluster. Sessions analysed
 * before the tag existed simply have untagged corrections, which are skipped (not
 * lumped into "other") so the chart reflects only real classifications.
 *
 * Note — this is a distinct chart from `MistakeGenomeEngine` (the "Your common
 * mistakes" card): that one rolls up SRS `ErrorItemEntity` rows, whereas this
 * counts per-session `corrections`-column tags, exactly as `dashboard_tab.py`
 * surfaces both separately. Earlier `L1InterferenceAnalyzer.kt` was an unrelated
 * invention with a similar name, not a port of this file (see MIGRATION_MASTER.md).
 *
 * [normalizeCategory] and [countCategories] are `org.json`-free so they can be
 * unit-tested; only [aggregateCategories]'s corrections-string parsing touches
 * `org.json`, and it runs on-device only (no Robolectric here — see
 * PORTING_STATUS.md honesty notes).
 */
object L1Stats {

    data class CategoryProfile(
        val count: Int,
        val opportunities: Int,
        val learnerWords: Int
    ) {
        val errorRatePercent: Double? =
            opportunities.takeIf { it > 0 }?.let { count * 100.0 / it }
        val per100Words: Double =
            if (learnerWords > 0) count * 100.0 / learnerWords else 0.0
    }

    /** Canonical tag → human label, in the order the analysis prompt enumerates them. */
    val CATEGORY_LABELS: Map<String, String> = linkedMapOf(
        "articles" to "Articles (a/the)",
        "plurals" to "Plural -s",
        "verb_tense" to "Verb tense",
        "prepositions" to "Prepositions",
        "word_order" to "Word order",
        "word_choice" to "Word choice",
        "konglish" to "Konglish / literal translation",
        "register" to "Register (jargon vs plain)",
        "pronunciation" to "Pronunciation-prone wording",
        "other" to "Other",
    )

    /** Collapse whitespace, lowercase, spaces→underscores; unknown non-empty tags → "other". */
    fun normalizeCategory(category: String?): String? {
        val key = (category ?: "")
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("_")
        if (key.isEmpty()) return null
        return if (key in CATEGORY_LABELS) key else "other"
    }

    /**
     * Only learner-confirmed errors belong in a longitudinal profile. Old rows without an
     * explicit decision are intentionally excluded: treating an unreviewed model suggestion as
     * evidence is worse than showing less historical data.
     */
    fun isConfirmedError(item: Map<String, Any?>): Boolean {
        val decision = item["decision"]?.toString()?.trim()?.lowercase()
        val feedbackType = item["feedback_type"]?.toString()?.trim()?.lowercase() ?: "error"
        val evidenceSource = item["evidence_source"]?.toString()?.trim()?.lowercase()
        val rawCategory = item["category"]?.toString()?.trim().orEmpty()
        return decision == "accepted" &&
            feedbackType == "error" &&
            evidenceSource != "audio" &&
            !rawCategory.startsWith("pronunciation", ignoreCase = true)
    }

    /** Count learner-confirmed error items per category, most frequent first. */
    fun countCategories(items: List<Map<String, Any?>>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (c in items) {
            if (!isConfirmedError(c)) continue
            val key = normalizeCategory(c["category"] as? String) ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        // sortedByDescending is stable, so equal counts preserve insertion order (mirrors
        // Python's Counter.most_common()).
        return counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    /**
     * Count tagged corrections across [sessions], most frequent first. Each session's
     * `corrections` entry may be a JSON string (as stored in `SessionEntity`) or an
     * already-parsed list; untagged / unparseable corrections are skipped.
     */
    fun aggregateCategories(sessions: List<Map<String, Any?>>): Map<String, Int> {
        val all = mutableListOf<Map<String, Any?>>()
        for (sess in sessions) {
            all.addAll(parseCorrections(sess["corrections"]) ?: continue)
        }
        return countCategories(all)
    }

    /**
     * Adds two honest denominators to raw counts. The preferred denominator is the evaluator's
     * category-specific number of required-use opportunities; per-100 learner words is retained
     * as a deterministic fallback and exposure measure.
     */
    fun aggregateProfiles(sessions: List<Map<String, Any?>>): Map<String, CategoryProfile> {
        val counts = aggregateCategories(sessions)
        val opportunities = LinkedHashMap<String, Int>()
        var learnerWords = 0
        for (session in sessions) {
            learnerWords += (session["user_word_count"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            val rawEval = session["raw_eval_json"]?.toString().orEmpty()
            if (rawEval.isBlank()) continue
            try {
                val root = org.json.JSONObject(rawEval)
                val obj = root.optJSONObject("error_opportunities") ?: continue
                for (key in obj.keys()) {
                    val canonical = normalizeCategory(key) ?: continue
                    val value = obj.optInt(key, 0).coerceAtLeast(0)
                    opportunities[canonical] = (opportunities[canonical] ?: 0) + value
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                // Legacy or malformed evaluation JSON simply has no opportunity denominator.
            }
        }
        return counts.mapValues { (category, count) ->
            val rawOpportunities = opportunities[category] ?: 0
            val trustworthyOpportunities = rawOpportunities.takeIf {
                it >= count && (learnerWords == 0 || it <= learnerWords * 2)
            } ?: 0
            CategoryProfile(
                count = count,
                opportunities = trustworthyOpportunities,
                learnerWords = learnerWords
            )
        }
    }

    /**
     * Per-category confirmed-error rate in a recent vs an earlier window, as errors per 100 learner
     * words (always available and comparable across windows, unlike the opportunity denominator
     * which can be sparse). A falling rate is a far stronger motivation signal than a raw count that
     * only ever grows. Only categories present in BOTH windows with at least [minRecentCount] recent
     * errors are returned, most-frequent first.
     */
    data class CategoryTrend(
        val category: String,
        val recentPer100: Double,
        val previousPer100: Double,
        val recentCount: Int
    ) {
        val delta: Double get() = recentPer100 - previousPer100
        val improved: Boolean get() = recentPer100 < previousPer100
    }

    fun categoryErrorRateTrend(
        recentSessions: List<Map<String, Any?>>,
        previousSessions: List<Map<String, Any?>>,
        minRecentCount: Int = 2,
        maxCategories: Int = 4
    ): List<CategoryTrend> {
        val recent = aggregateProfiles(recentSessions)
        val previous = aggregateProfiles(previousSessions)
        return recent.entries
            .filter { (category, profile) ->
                category != "other" &&
                    profile.count >= minRecentCount &&
                    previous.containsKey(category)
            }
            .sortedByDescending { it.value.count }
            .take(maxCategories)
            .map { (category, profile) ->
                CategoryTrend(
                    category = category,
                    recentPer100 = Math.round(profile.per100Words * 10) / 10.0,
                    previousPer100 = Math.round((previous[category]?.per100Words ?: 0.0) * 10) / 10.0,
                    recentCount = profile.count
                )
            }
    }

    private fun parseCorrections(raw: Any?): List<Map<String, Any?>>? {
        val arr: JSONArray = when (raw) {
            is JSONArray -> raw
            is String -> if (raw.isBlank()) return null else try {
                JSONArray(raw)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                return null
            }
            is List<*> -> return raw.mapNotNull { item ->
                (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            }
            else -> return null
        }
        val out = ArrayList<Map<String, Any?>>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val map = LinkedHashMap<String, Any?>()
            for (key in obj.keys()) map[key] = obj.opt(key)
            out.add(map)
        }
        return out
    }
}
