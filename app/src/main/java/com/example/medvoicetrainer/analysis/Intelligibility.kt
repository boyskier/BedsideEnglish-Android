package com.example.medvoicetrainer.analysis

import kotlin.math.max
import kotlin.math.min

/** Transcript-only communication-clarity heuristics. This does not inspect audio or pronunciation. */
object Intelligibility {

    private val SIGNPOST_PATTERNS = listOf(
        "\\bfirst\\b", "\\bsecond\\b", "\\bnext\\b", "\\bto summarize\\b",
        "\\bin summary\\b", "\\blet me summarize\\b", "\\bwhat I heard\\b",
        "\\bdoes that make sense\\b", "\\bthe plan is\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val REPAIR_PATTERNS = listOf(
        "\\bcould you repeat\\b", "\\bcan you repeat\\b", "\\bcould you say that again\\b",
        "\\blet me rephrase\\b", "\\bin other words\\b", "\\bto be clear\\b",
        "\\bjust to confirm\\b", "\\bif I understand correctly\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val CHECK_UNDERSTANDING_PATTERNS = listOf(
        "\\bdoes that make sense\\b", "\\bwhat questions do you have\\b",
        "\\bcould you tell me in your own words\\b", "\\bcan you explain back\\b",
        "\\bjust to check your understanding\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val HIGH_RISK_JARGON = setOf(
        "syncope", "myocardial", "infarction", "dyspnea", "oedema", "edema",
        "hypertension", "hypotension", "tachycardia", "bradycardia", "biopsy",
        "prognosis", "benign", "malignant", "contraindication", "comorbidity",
        "exacerbation", "analgesic", "antipyretic", "anticoagulant", "diuretic"
    )

    private fun userTurns(transcript: List<Map<String, Any?>>): List<String> {
        return transcript.filter { it["role"] == "user" }
            .map { it["text"]?.toString()?.trim() ?: "" }
            .filter { it.isNotEmpty() }
    }

    private fun countPatterns(text: String, patterns: List<Regex>): Int {
        return patterns.sumOf { it.findAll(text).count() }
    }

    private val wordRegex = Regex("\\b[\\w'-]+\\b")

    private fun wordCount(text: String): Int {
        return wordRegex.findAll(text).count()
    }

    fun computeIntelligibilityMetrics(transcript: List<Map<String, Any?>>, mode: String? = null): Map<String, Any> {
        val turns = userTurns(transcript)
        if (turns.isEmpty()) return emptyMap()

        val combined = turns.joinToString(" ")
        val words = wordRegex.findAll(combined.lowercase()).map { it.value }.toList()
        val wordCount = words.size
        if (wordCount == 0) return emptyMap()

        val turnLengths = turns.map { wordCount(it) }
        val longTurns = turnLengths.count { it > 45 }
        val veryShortTurns = turnLengths.count { it <= 3 }
        
        val signposts = countPatterns(combined, SIGNPOST_PATTERNS)
        val repairs = countPatterns(combined, REPAIR_PATTERNS)
        val checks = countPatterns(combined, CHECK_UNDERSTANDING_PATTERNS)

        val jargonHits = words.filter { HIGH_RISK_JARGON.contains(it) }
        val jargonDensity = Math.round((jargonHits.size.toDouble() / wordCount) * 1000) / 10.0

        val normalizedMode = mode?.lowercase() ?: ""
        val everyday = setOf("survival", "everyday", "lounge").contains(normalizedMode)
        val explicitlyClinical = setOf("clinical", "encounter", "exam", "interview", "teachback").contains(normalizedMode)
        val clinical = explicitlyClinical || (!everyday && mode == null && jargonHits.isNotEmpty())

        var score = 8.0
        if (longTurns > 0) score -= min(2.0, longTurns * 0.6)
        if (clinical && veryShortTurns >= max(2, turns.size / 3)) score -= 0.8
        if (clinical && jargonDensity > 4.0) score -= 1.2
        else if (clinical && jargonDensity > 2.0) score -= 0.6
        if (clinical && signposts == 0 && wordCount >= 80) score -= 0.8
        if (clinical && checks == 0 && wordCount >= 80) score -= 0.5
        
        if (repairs > 0) score += min(0.7, repairs * 0.2)
        if (signposts >= 2) score += 0.4
        
        score = Math.round(max(0.0, min(10.0, score)) * 10) / 10.0

        val grade = when {
            score >= 8.0 -> "Strong communication structure"
            score >= 6.5 -> "Generally clear structure"
            else -> "Clarity support needed"
        }

        val coaching = mutableListOf<String>()
        if (longTurns > 0) coaching.add("Break long turns into shorter chunks with one idea per sentence.")
        if (clinical && jargonDensity > 2.0) coaching.add("Translate jargon into patient-friendly words before continuing.")
        if (clinical && signposts == 0 && wordCount >= 80) coaching.add("Use signposts such as 'first', 'next', and 'to summarize'.")
        if (clinical && checks == 0 && wordCount >= 80) coaching.add("Check understanding after explanations instead of assuming clarity.")
        if (repairs == 0) coaching.add("Use repair phrases when speech is fast or unclear: 'Could you repeat that?'")

        if (coaching.isEmpty()) {
            coaching.add(if (!clinical) "Keep this pattern: fitting response length, clear wording, and natural clarification." else "Keep this pattern: clear chunks, useful signposting, and safe clarification.")
        }

        val uniqueJargon = jargonHits.toSet().sorted().take(8)

        return mapOf(
            "score" to score,
            "grade" to grade,
            "word_count" to wordCount,
            "avg_words_per_turn" to Math.round((turnLengths.sum().toDouble() / turnLengths.size) * 10) / 10.0,
            "long_turn_count" to longTurns,
            "signpost_count" to signposts,
            "repair_phrase_count" to repairs,
            "check_understanding_count" to checks,
            "jargon_density" to jargonDensity,
            "jargon_examples" to uniqueJargon,
            "coaching" to coaching.take(4),
            "principle" to "Transcript-only estimate of wording and structure; pronunciation and acoustic intelligibility are not measured here."
        )
    }
}
