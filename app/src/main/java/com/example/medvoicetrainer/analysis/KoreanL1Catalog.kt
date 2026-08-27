package com.example.medvoicetrainer.analysis

/**
 * Compatibility facade for older callers and golden exports.
 * New runtime code uses [L1InterferenceCatalog], which prevents Korean and multilingual behavior
 * from drifting into separate implementations again.
 */
object KoreanL1Catalog {
    data class L1Pattern(
        val category: String,
        val titleEn: String,
        val titleKo: String,
        val hint: String,
        val examples: List<Pair<String, String>>
    )

    private val koreanTitles = mapOf(
        "ko.articles" to "관사 누락/오용",
        "ko.plurals" to "복수형 및 가산성",
        "ko.tense" to "시제·상 및 조동사",
        "ko.prepositions" to "조사와 전치사",
        "ko.word_order" to "어순과 의문문 도치",
        "ko.collocation" to "한국어 연어의 직역",
        "ko.konglish" to "콩글리시",
        "ko.register" to "높임법과 영어식 완곡 표현"
    )

    val PATTERNS: List<L1Pattern> = L1InterferenceCatalog.PROFILES.getValue("ko").patterns.map {
        L1Pattern(
            category = it.category,
            titleEn = it.title,
            titleKo = koreanTitles[it.id].orEmpty(),
            hint = it.transferHint,
            examples = listOf(it.original to it.corrected)
        )
    }

    fun isKorean(nativeLanguage: String?): Boolean =
        L1InterferenceCatalog.normalizeLanguageCode(nativeLanguage) == "ko"

    fun promptNote(nativeLanguage: String?): String {
        if (!isKorean(nativeLanguage)) return ""
        val lines = PATTERNS.joinToString("\n") { pattern ->
            val example = pattern.examples.first()
            "- ${pattern.category}: ${pattern.hint} (e.g. \"${example.first}\" -> \"${example.second}\")"
        }
        return "KOREAN L1 INTERFERENCE WATCHLIST\n$lines"
    }

    internal fun tokens(value: String): List<String> = L1InterferenceCatalog.tokens(value)

    fun classify(original: String, corrected: String, explanation: String, category: String): String? =
        L1InterferenceCatalog.classify("ko", original, corrected, explanation, category)
}
