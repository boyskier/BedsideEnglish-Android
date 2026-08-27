package com.example.medvoicetrainer.analysis

import java.util.Locale
import kotlin.math.max

/**
 * Deliberately categorical: an AI listener cannot justify a clinically precise 0-10
 * pronunciation score. The app reports the listening consequence it actually observed.
 */
enum class IntelligibilityOutcome(val wireName: String) {
    COMFORTABLE("comfortable"),
    EFFORTFUL("effortful"),
    CRITICAL_MISMATCH("critical_mismatch"),
    COULD_NOT_ASSESS("could_not_assess");

    companion object {
        fun fromWireName(value: String?): IntelligibilityOutcome =
            entries.firstOrNull { it.wireName == value } ?: COULD_NOT_ASSESS
    }
}

data class CriticalMeaningDifference(
    val type: String,
    val expected: String,
    val heard: String?
)

data class IntelligibilityAssessment(
    val outcome: IntelligibilityOutcome,
    val wordMatchRate: Double,
    val criticalDifferences: List<CriticalMeaningDifference>
) {
    val comfortablyUnderstood: Boolean get() = outcome == IntelligibilityOutcome.COMFORTABLE
}

/**
 * Reference comparison happens only after the first AI call has produced a transcript without
 * seeing the target. Deterministic slot checks prevent a high overall word-match rate from hiding
 * safety-critical changes such as "fifteen" → "fifty" or "left" → "right".
 */
object IntelligibilityComparator {
    private data class CriticalItem(val type: String, val canonical: String, val display: String)

    private val numberValues = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90
    )
    private val numberConnectors = setOf("hundred", "thousand", "and", "point")
    private val units = setOf(
        "mg", "milligram", "milligrams", "mcg", "microgram", "micrograms",
        "g", "gram", "grams", "ml", "milliliter", "milliliters", "millilitre",
        "millilitres", "unit", "units", "tablet", "tablets", "capsule", "capsules",
        "puff", "puffs", "drop", "drops", "dose", "doses", "percent"
    )
    private val negations = setOf(
        "no", "not", "never", "without", "cannot", "can't", "cant", "don't", "dont",
        "doesn't", "doesnt", "didn't", "didnt", "isn't", "isnt", "aren't", "arent",
        "wasn't", "wasnt", "weren't", "werent", "won't", "wont", "wouldn't",
        "wouldnt", "shouldn't", "shouldnt", "couldn't", "couldnt"
    )
    private val lateralities = setOf("left", "right", "bilateral", "both")
    private val timesAndFrequencies = setOf(
        "morning", "afternoon", "evening", "night", "midnight", "noon",
        "today", "tomorrow", "yesterday", "daily", "weekly", "monthly",
        "hourly", "once", "twice", "before", "after"
    )
    private val medicationSuffixes = setOf(
        "cillin", "cycline", "mycin", "pril", "sartan", "olol", "statin", "prazole",
        "azole", "caine", "dipine", "gliptin", "gliflozin", "mab", "zumab", "ximab",
        "vir", "zepam", "oxetine", "codone", "terol", "tropium", "formin"
    )
    private val commonCapitalizedWords = setOf(
        "i", "the", "a", "an", "please", "take", "my", "your", "we", "you",
        "it", "this", "that", "yes", "no", "doctor", "nurse"
    )

    // Compiled once. These are applied per token inside comparisonTokens/extractCriticalItems,
    // so building a fresh Regex per token was the dominant cost of the intelligibility pass.
    private val TOKEN_SPLIT_RE = Regex("[^a-z0-9'.]+")
    private val WHITESPACE_RE = Regex("\\s+")
    private val DECIMAL_NUMBER_RE = Regex("\\d+(?:\\.\\d+)?")
    private val INTEGER_RE = Regex("\\d+")
    private val ORIGINAL_TOKEN_RE = Regex("[A-Za-z0-9'.]+")
    private val UNUSABLE_TRANSCRIPT_TOKENS = setOf("unclear", "inaudible", "unknown")

    private fun normalizedTokens(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .replace(TOKEN_SPLIT_RE, " ")
        .trim()
        .split(WHITESPACE_RE)
        .filter { it.isNotBlank() }

    internal fun tokenMatchRate(expected: String, heard: String): Double {
        val expectedTokens = comparisonTokens(expected)
        val heardPool = comparisonTokens(heard).toMutableList()
        if (expectedTokens.isEmpty()) return 0.0
        var matches = 0
        for (token in expectedTokens) {
            val exact = heardPool.indexOf(token)
            val fuzzy = if (exact < 0 && token.length >= 6) {
                heardPool.indexOfFirst { candidate -> nearlySameWord(token, candidate) }
            } else {
                -1
            }
            val index = if (exact >= 0) exact else fuzzy
            if (index >= 0) {
                matches++
                heardPool.removeAt(index)
            }
        }
        return matches.toDouble() / expectedTokens.size
    }

    private fun comparisonTokens(value: String): List<String> {
        val raw = normalizedTokens(value)
        val result = mutableListOf<String>()
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            if (token.matches(DECIMAL_NUMBER_RE) || token in numberValues) {
                val numberParts = mutableListOf<String>()
                while (
                    index < raw.size &&
                    (raw[index].matches(DECIMAL_NUMBER_RE) ||
                        raw[index] in numberValues ||
                        raw[index] in numberConnectors)
                ) {
                    numberParts += raw[index]
                    index++
                }
                result += "#${canonicalNumber(numberParts)}"
            } else {
                result += token
                index++
            }
        }
        return result
    }

    /**
     * When [strict] is true the COMFORTABLE bar is raised to approximate a harder human listener
     * (noisy ward, hard-of-hearing patient, phone order) rather than a lenient, accent-robust ASR:
     * comprehensibility must be a full 5, word match ≥ 0.92, and no uncertain words at all. The
     * default (false) preserves the everyday "comfortably understood, not native-sounding" bar used
     * everywhere else, so existing behaviour and golden vectors are unchanged.
     */
    fun assess(
        expected: String,
        heard: String,
        uncertainWords: List<String>,
        comprehensibility: Int,
        confidence: Double,
        criticalItemsClear: Boolean = true,
        strict: Boolean = false,
        openEnded: Boolean = false
    ): IntelligibilityAssessment {
        val wordMatch = tokenMatchRate(expected, heard)
        val criticalDifferences = compareCriticalMeaning(expected, heard, openEnded)
        val unusableTranscript = heard.isBlank() ||
            normalizedTokens(heard).all { it in UNUSABLE_TRANSCRIPT_TOKENS }
        val comfortable = if (strict) {
            comprehensibility >= 5 &&
                wordMatch >= 0.92 &&
                criticalItemsClear &&
                uncertainWords.isEmpty()
        } else {
            comprehensibility >= 4 &&
                wordMatch >= 0.80 &&
                criticalItemsClear &&
                uncertainWords.size <= 1
        }
        val outcome = when {
            unusableTranscript || confidence < 0.55 || comprehensibility <= 1 ->
                IntelligibilityOutcome.COULD_NOT_ASSESS
            criticalDifferences.isNotEmpty() ->
                IntelligibilityOutcome.CRITICAL_MISMATCH
            comfortable ->
                IntelligibilityOutcome.COMFORTABLE
            else -> IntelligibilityOutcome.EFFORTFUL
        }
        return IntelligibilityAssessment(
            outcome = outcome,
            wordMatchRate = wordMatch,
            criticalDifferences = if (outcome == IntelligibilityOutcome.COULD_NOT_ASSESS) {
                emptyList()
            } else {
                criticalDifferences.take(6)
            }
        )
    }

    /**
     * [openEnded] marks a target that only fixes part of the utterance — a cloze phrase whose
     * blanks the learner fills with their own name, patient, dose, or time (see [PhraseBlanks]).
     * The expected frame is still checked, but content the learner legitimately invented can no
     * longer be reported as an added meaning-critical item, since there is nothing to add it to.
     */
    fun compareCriticalMeaning(
        expected: String,
        heard: String,
        openEnded: Boolean = false
    ): List<CriticalMeaningDifference> {
        val expectedItems = extractCriticalItems(expected)
        val heardItems = extractCriticalItems(heard)
        val usedHeard = mutableSetOf<Int>()
        val differences = mutableListOf<CriticalMeaningDifference>()

        expectedItems.forEach { expectedItem ->
            val exact = heardItems.indices.firstOrNull { index ->
                index !in usedHeard &&
                    heardItems[index].type == expectedItem.type &&
                    criticalItemsMatch(expectedItem, heardItems[index])
            }
            if (exact != null) {
                usedHeard += exact
            } else {
                val replacement = heardItems.indices.firstOrNull { index ->
                    index !in usedHeard && heardItems[index].type == expectedItem.type
                }
                if (replacement != null) usedHeard += replacement
                differences += CriticalMeaningDifference(
                    type = expectedItem.type,
                    expected = expectedItem.display,
                    heard = replacement?.let { heardItems[it].display }
                )
            }
        }

        // An added negation, number, side, or time can reverse meaning just as much as an omission.
        heardItems.forEachIndexed { index, heardItem ->
            if (!openEnded && index !in usedHeard) {
                differences += CriticalMeaningDifference(
                    type = heardItem.type,
                    expected = "(none)",
                    heard = heardItem.display
                )
            }
        }
        return differences.distinct()
    }

    private fun extractCriticalItems(text: String): List<CriticalItem> {
        val originalTokens = ORIGINAL_TOKEN_RE.findAll(text).map { it.value }.toList()
        val tokens = originalTokens.map { it.lowercase(Locale.ROOT) }
        val items = mutableListOf<CriticalItem>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.matches(DECIMAL_NUMBER_RE) || token in numberValues) {
                val start = index
                val numberParts = mutableListOf<String>()
                while (
                    index < tokens.size &&
                    (tokens[index].matches(DECIMAL_NUMBER_RE) ||
                        tokens[index] in numberValues ||
                        tokens[index] in numberConnectors)
                ) {
                    numberParts += tokens[index]
                    index++
                }
                val unit = tokens.getOrNull(index)?.takeIf { it in units }
                if (unit != null) index++
                val display = (originalTokens.subList(start, index)).joinToString(" ")
                val canonicalNumber = canonicalNumber(numberParts)
                items += CriticalItem(
                    type = "dose_or_number",
                    canonical = "$canonicalNumber|${canonicalUnit(unit)}",
                    display = display
                )
                continue
            }
            when {
                token in negations ->
                    items += CriticalItem("negation", "negative", originalTokens[index])
                token in lateralities ->
                    items += CriticalItem("laterality", token, originalTokens[index])
                token in timesAndFrequencies ->
                    items += CriticalItem("time", token, originalTokens[index])
                token in units ->
                    items += CriticalItem("dose_unit", canonicalUnit(token), originalTokens[index])
                isLikelyMedication(token) ->
                    items += CriticalItem("medication_or_name", token, originalTokens[index])
                index > 0 &&
                    originalTokens[index].firstOrNull()?.isUpperCase() == true &&
                    token.length >= 3 &&
                    token !in commonCapitalizedWords ->
                    items += CriticalItem("medication_or_name", token, originalTokens[index])
            }
            index++
        }
        return items.distinct()
    }

    private fun canonicalNumber(parts: List<String>): String {
        if (parts.size == 1 && parts[0].matches(DECIMAL_NUMBER_RE)) return parts[0]
        if ("point" in parts) return parts.joinToString(" ")
        var total = 0
        var current = 0
        parts.filterNot { it == "and" }.forEach { part ->
            when {
                part.matches(INTEGER_RE) -> current += part.toIntOrNull() ?: 0
                part in numberValues -> current += numberValues.getValue(part)
                part == "hundred" -> current = max(1, current) * 100
                part == "thousand" -> {
                    total += max(1, current) * 1_000
                    current = 0
                }
            }
        }
        return (total + current).toString()
    }

    private fun canonicalUnit(unit: String?): String = when (unit) {
        null -> ""
        "milligram", "milligrams" -> "mg"
        "microgram", "micrograms" -> "mcg"
        "gram", "grams" -> "g"
        "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
        "unit", "units" -> "unit"
        "tablet", "tablets" -> "tablet"
        "capsule", "capsules" -> "capsule"
        "puff", "puffs" -> "puff"
        "drop", "drops" -> "drop"
        "dose", "doses" -> "dose"
        else -> unit
    }

    private fun isLikelyMedication(token: String): Boolean =
        token.length >= 6 && medicationSuffixes.any(token::endsWith)

    private fun criticalItemsMatch(left: CriticalItem, right: CriticalItem): Boolean =
        left.canonical == right.canonical ||
            (
                left.type == "medication_or_name" &&
                    nearlySameWord(left.canonical, right.canonical)
                )

    private fun nearlySameWord(left: String, right: String): Boolean {
        if (left == right) return true
        if (left.length < 5 || right.length < 5) return false
        val maxLength = max(left.length, right.length)
        return levenshtein(left, right) <= max(1, maxLength / 5)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}
