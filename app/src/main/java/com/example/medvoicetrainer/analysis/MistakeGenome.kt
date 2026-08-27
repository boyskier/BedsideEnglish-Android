package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorItemEntity
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable

@Serializable
data class CategoryPattern(
    val category: String,
    val label: String,
    val weight: Int,
    val percentage: Double,
    val count: Int
)

data class MistakeGenome(
    val totalItems: Int,
    val activeItems: Int,
    val masteredItems: Int,
    val topCategories: List<CategoryPattern>,
    val stubbornItems: List<ErrorItemEntity>,
    val headline: String,
    val advice: String
)

/**
 * One category's recurrence in the recent window versus the one before it.
 *
 * "Recurrence" is deliberately counted from [ErrorItemEntity.lastSeen], so an item lands in a
 * window when that mistake actually came back during it. The cumulative `seenCount`/`lapses`
 * weights [MistakeGenomeEngine.buildMistakeGenome] uses carry no timestamp at all and therefore
 * cannot be attributed to a window — trying to would silently invent history.
 */
@Serializable
data class CategoryRecurrence(
    val category: String,
    val label: String,
    val recentCount: Int,
    val priorCount: Int,
    val recentSharePercent: Double,
    val priorSharePercent: Double,
    /** recent share minus prior share, in percentage *points*. Negative = shrinking share. */
    val shareDeltaPoints: Double,
    val countDelta: Int
) {
    val improved: Boolean get() = shareDeltaPoints < 0.0
}

/**
 * The genome's missing time dimension: the same category mix, but "this window vs the one
 * before". A snapshot percentage ("Articles 32.4%") barely moves day to day, so it stops being
 * worth opening; a delta is what makes the card worth a daily look.
 *
 * [hasPriorWindow] is false when the prior window holds nothing to compare against (a first-week
 * learner, or a return after a long gap). Callers must then show counts only — every delta would
 * otherwise render as a meaningless "+100%".
 */
data class MistakeGenomeTrend(
    val windowDays: Int,
    val recentTotal: Int,
    val priorTotal: Int,
    val totalDelta: Int,
    val hasPriorWindow: Boolean,
    val categories: List<CategoryRecurrence>
) {
    val hasData: Boolean get() = recentTotal > 0 || priorTotal > 0
}

object MistakeGenomeEngine {

    // Faithful port: Python `mistake_genome.py` does `from app.analysis.l1_stats
    // import CATEGORY_LABELS`, so the genome must use the *same* label map as the
    // L1 chart. Kotlin previously hardcoded a divergent map here ("Plurals & Noun
    // Forms" vs "Plural -s", "L1 Interference" vs "Konglish / literal translation",
    // …), which drifted the `label`/`headline` output — surfaced by golden vectors.
    val CATEGORY_LABELS = L1Stats.CATEGORY_LABELS

    fun buildMistakeGenome(errorItems: List<ErrorItemEntity>, maxCategories: Int = 5): MistakeGenome {
        val confirmedItems = errorItems.filter { it.state != PronunciationEvidencePolicy.OBSERVED_STATE }
        val active = confirmedItems.filter { it.state != "mastered" }
        val mastered = confirmedItems.filter { it.state == "mastered" }

        val counts = mutableMapOf<String, Int>()
        val weighted = mutableMapOf<String, Int>()
        val stubborn = mutableListOf<ErrorItemEntity>()

        for (item in confirmedItems) {
            val key = normalizeCategory(item.category)
            val seen = item.seenCount
            val lapses = item.lapses

            counts[key] = counts.getOrDefault(key, 0) + 1
            weighted[key] = weighted.getOrDefault(key, 0) + Math.max(1, seen) + (lapses * 2)

            if (item.state != "mastered" && (lapses >= 2 || seen >= 3)) {
                stubborn.add(item)
            }
        }

        val totalWeight = weighted.values.sum()
        val sortedWeighted = weighted.entries.sortedByDescending { it.value }.take(maxCategories)

        val top = sortedWeighted.map { entry ->
            val key = entry.key
            val value = entry.value
            val pct = if (totalWeight > 0) Math.round((value.toDouble() / totalWeight) * 1000) / 10.0 else 0.0
            CategoryPattern(
                category = key,
                label = CATEGORY_LABELS[key] ?: key,
                weight = value,
                percentage = pct,
                count = counts[key] ?: 0
            )
        }

        val headline: String
        val advice: String

        if (confirmedItems.isEmpty()) {
            headline = "No mistake pattern yet"
            advice = "Complete two or three sessions so the app can detect recurring patterns."
        } else if (top.isNotEmpty()) {
            headline = "Dominant pattern: ${top[0].label}"
            advice = adviceFor(top[0].category)
        } else {
            headline = "Mistakes are mostly under control"
            advice = "Keep rotating short speaking missions so mastered errors stay automatic."
        }

        val sortedStubborn = stubborn.sortedWith(compareByDescending<ErrorItemEntity> { it.lapses }.thenByDescending { it.seenCount }).take(5)

        return MistakeGenome(
            totalItems = confirmedItems.size,
            activeItems = active.size,
            masteredItems = mastered.size,
            topCategories = top,
            stubbornItems = sortedStubborn,
            headline = headline,
            advice = advice
        )
    }

    /**
     * The genome's category bucketing, shared by the snapshot and the trend so both group items
     * identically. Extracted verbatim from [buildMistakeGenome]'s former inline block — the
     * golden vectors pin that behaviour, so this must stay a pure move.
     */
    internal fun normalizeCategory(rawCategory: String): String {
        var key = rawCategory.trim().ifEmpty { "other" }
        if (key.startsWith("pronunciation", ignoreCase = true)) {
            key = "pronunciation"
        }
        if (!CATEGORY_LABELS.containsKey(key)) {
            key = "other"
        }
        return key
    }

    // `lastSeen` is written by two different call sites in two different shapes: MainViewModel's
    // SRS routing uses a bare local "yyyy-MM-dd'T'HH:mm:ss", while SrsEngine writes UTC with a
    // trailing Z. Both are live in existing installs, so the trend has to read both. The Z pattern
    // must be tried first: SimpleDateFormat.parse happily matches a prefix and would otherwise
    // read a UTC stamp as local time, shifting rows across the window boundary.
    private val TIMESTAMP_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'" to true,
        "yyyy-MM-dd'T'HH:mm:ss" to false,
        "yyyy-MM-dd HH:mm:ss" to false,
        "yyyy-MM-dd" to false
    )

    /** Epoch millis for a stored SRS timestamp, or null when it is blank or unparseable. */
    internal fun parseTimestampMillis(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for ((pattern, isUtc) in TIMESTAMP_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                if (isUtc) timeZone = TimeZone.getTimeZone("UTC")
            }
            try {
                return format.parse(text)?.time ?: continue
            } catch (_: ParseException) {
                // Try the next shape.
            }
        }
        return null
    }

    /**
     * Recurrence of each mistake category over the last [windowDays] days versus the [windowDays]
     * before that, ordered by recent volume then by how much the share grew.
     *
     * Items still in the pronunciation `observed` state are excluded, exactly as the snapshot
     * excludes them — an unconfirmed one-off recording is not a recurrence. Items whose
     * `lastSeen` will not parse are dropped from both windows rather than defaulted into one.
     */
    fun buildRecurrenceTrend(
        errorItems: List<ErrorItemEntity>,
        nowMillis: Long = System.currentTimeMillis(),
        windowDays: Int = 7,
        maxCategories: Int = 5
    ): MistakeGenomeTrend {
        val safeWindowDays = windowDays.coerceAtLeast(1)
        val windowMillis = safeWindowDays * 24L * 60L * 60L * 1000L
        val recentStart = nowMillis - windowMillis
        val priorStart = recentStart - windowMillis

        val recentCounts = mutableMapOf<String, Int>()
        val priorCounts = mutableMapOf<String, Int>()

        errorItems
            .filter { it.state != PronunciationEvidencePolicy.OBSERVED_STATE }
            .forEach { item ->
                val seenAt = parseTimestampMillis(item.lastSeen) ?: return@forEach
                val key = normalizeCategory(item.category)
                when {
                    seenAt >= recentStart -> recentCounts[key] = recentCounts.getOrDefault(key, 0) + 1
                    seenAt >= priorStart -> priorCounts[key] = priorCounts.getOrDefault(key, 0) + 1
                }
            }

        val recentTotal = recentCounts.values.sum()
        val priorTotal = priorCounts.values.sum()

        val categories = (recentCounts.keys + priorCounts.keys).map { key ->
            val recent = recentCounts.getOrDefault(key, 0)
            val prior = priorCounts.getOrDefault(key, 0)
            val recentShare = sharePercent(recent, recentTotal)
            val priorShare = sharePercent(prior, priorTotal)
            CategoryRecurrence(
                category = key,
                label = CATEGORY_LABELS[key] ?: key,
                recentCount = recent,
                priorCount = prior,
                recentSharePercent = recentShare,
                priorSharePercent = priorShare,
                // Rounded shares are subtracted (rather than subtracting then rounding) so the
                // delta always reconciles with the two percentages shown beside it.
                shareDeltaPoints = Math.round((recentShare - priorShare) * 10.0) / 10.0,
                countDelta = recent - prior
            )
        }
            .sortedWith(
                compareByDescending<CategoryRecurrence> { it.recentCount }
                    .thenByDescending { it.shareDeltaPoints }
                    .thenBy { it.label }
            )
            .take(maxCategories)

        return MistakeGenomeTrend(
            windowDays = safeWindowDays,
            recentTotal = recentTotal,
            priorTotal = priorTotal,
            totalDelta = recentTotal - priorTotal,
            hasPriorWindow = priorTotal > 0,
            categories = categories
        )
    }

    /** Genome-style one-decimal percentage, matching buildMistakeGenome's rounding. */
    private fun sharePercent(part: Int, total: Int): Double =
        if (total > 0) Math.round((part.toDouble() / total) * 1000) / 10.0 else 0.0

    private fun adviceFor(category: String): String {
        val advices = mapOf(
            "articles" to "Drill noun frames out loud: a symptom, the pain, my medication.",
            "plurals" to "Read medication lists and symptom lists aloud, exaggerating final -s.",
            "verb_tense" to "Practice timelines: started, has been going on, is getting worse.",
            "prepositions" to "Drill common clinical chunks: on metformin, allergic to, pain in.",
            "word_order" to "Use simple clinical sentence frames before adding detail.",
            "word_choice" to "Build a personal phrase bank from corrected sentences.",
            "konglish" to "Replace direct translations with standard US clinical phrases.",
            "register" to "Practice saying the same idea for a patient, nurse, and attending.",
            "pronunciation" to "Prioritize stress, numbers, and medication names over accent.",
            "other" to "Review your saved corrections and group them into phrase families."
        )
        return advices[category] ?: advices["other"]!!
    }
}
