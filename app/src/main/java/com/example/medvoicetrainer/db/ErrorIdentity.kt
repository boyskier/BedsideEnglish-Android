package com.example.medvoicetrainer.db

import java.util.Locale

object ErrorIdentity {
    const val MATCH_THRESHOLD = 0.82

    // Compiled once. normalize() is the innermost operation of the SRS routing pass --
    // bestMatch runs it across every stored error item for every correction in a session --
    // and rebuilding four Regex objects per call cost far more than the matching itself.
    private val ZERO_WIDTH_RE = Regex("[\uFEFF\u200B]")
    private val APOSTROPHE_RE = Regex("['’]")
    private val NON_WORD_RE = Regex("[^\\w\\s]")
    private val WHITESPACE_RE = Regex("\\s+")

    fun normalize(s: String?): String {
        if (s == null) return ""
        val stripped = s.replace(ZERO_WIDTH_RE, "").replace(APOSTROPHE_RE, "")
        val clean = stripped.lowercase(Locale.US).replace(NON_WORD_RE, " ")
        return clean.split(WHITESPACE_RE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Count the intersection directly, then |A u B| = |A| + |B| - |A n B|. Same result
        // intersect()/union() give, without allocating the two intermediate sets they build.
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var intersection = 0
        for (item in smaller) if (item in larger) intersection++
        val union = a.size + b.size - intersection
        return intersection.toDouble() / union
    }

    private fun lcsLength(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = IntArray(n + 1)
        for (i in 1..m) {
            var prev = 0
            for (j in 1..n) {
                val temp = dp[j]
                if (a[i - 1] == b[j - 1]) {
                    dp[j] = prev + 1
                } else {
                    dp[j] = maxOf(dp[j], dp[j - 1])
                }
                prev = temp
            }
        }
        return dp[n]
    }

    fun similarity(a: String, b: String): Double = similarityOfNormalized(normalize(a), normalize(b))

    /**
     * [similarity]'s body once both sides are already [normalize]d. Split out so a caller comparing
     * one string against many (see [bestMatch]) normalizes its own side once instead of re-running
     * that regex pipeline for every candidate.
     */
    private fun similarityOfNormalized(na: String, nb: String): Double {
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val jac = jaccard(na.split(" ").toSet(), nb.split(" ").toSet())
        val lcs = lcsLength(na, nb)
        val seq = 2.0 * lcs / (na.length + nb.length)
        return maxOf(jac, seq)
    }

    fun bestMatch(
        corrected: String,
        original: String,
        category: String,
        candidates: List<ErrorItemEntity>,
        threshold: Double = MATCH_THRESHOLD,
        patternId: String = ""
    ): String? {
        val cat = category.trim().lowercase(Locale.US)
        val stablePattern = patternId.trim().lowercase(Locale.US)
        if (stablePattern.isNotEmpty()) {
            candidates.firstOrNull {
                it.patternId.trim().lowercase(Locale.US) == stablePattern
            }?.let { return it.key }
        }
        // The query side is fixed for the whole sweep, so normalize it once rather than once per
        // candidate. This runs over every stored error item for every correction in a session.
        val normalizedCorrected = normalize(corrected)
        val normalizedOriginal = normalize(original)
        var bestKey: String? = null
        var bestScore = 0.0
        for (c in candidates) {
            val cCat = c.category.trim().lowercase(Locale.US)
            val categoriesComparable = cat.isNotEmpty() && cCat.isNotEmpty()
            // Tested before scoring, not after: a candidate in a different category is discarded
            // outright, so computing its similarity first was work whose result was thrown away.
            // Different educational rules must never collapse merely because their short
            // corrected strings happen to be identical.
            if (categoriesComparable && cat != cCat) continue

            val scoreCorrected = similarityOfNormalized(normalizedCorrected, normalize(c.corrected))
            val scoreOriginal = similarityOfNormalized(normalizedOriginal, normalize(c.original))
            var score = maxOf(scoreCorrected, scoreOriginal)
            if (categoriesComparable) score += 0.05

            if (score > bestScore) {
                bestScore = score
                bestKey = c.key
            }
        }
        return if (bestScore >= threshold) bestKey else null
    }
}
