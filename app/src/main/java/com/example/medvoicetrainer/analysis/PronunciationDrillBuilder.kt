package com.example.medvoicetrainer.analysis

data class PronunciationDrillStage(
    val label: String,
    val text: String,
    val instruction: String = "",
    /**
     * When present, the UI can run a perception-discrimination quiz (play one word, learner picks
     * which they heard) before asking for production. Perception drives production: a learner who
     * cannot hear the contrast cannot self-monitor it. Null when the pattern has no clean minimal
     * pair to quiz on.
     */
    val minimalPair: Pair<String, String>? = null
)

/** Perception -> articulation -> controlled production -> contextual transfer. */
object PronunciationDrillBuilder {
    private val contrasts = mapOf(
        "r_l" to "right, light",
        "f_p" to "fan, pan",
        "th" to "thin, tin; this, dis",
        "final_consonant" to "cap, cab",
        "consonant_cluster" to "ask, asked",
        "inserted_vowel" to "school, not suh-cool",
        "vowel" to "ship, sheep"
    )

    private val mouthCues = mapOf(
        "r_l" to "For /l/, touch the tongue tip behind the upper teeth. For /r/, keep it back.",
        "f_p" to "For /f/, keep air flowing between the lower lip and upper teeth; /p/ is one burst.",
        "th" to "Place the tongue tip lightly between the teeth and let air pass.",
        "final_consonant" to "Finish the last consonant without adding an extra vowel.",
        "consonant_cluster" to "Keep every consonant; slow down first, then reconnect them.",
        "inserted_vowel" to "Move directly between consonants without adding an extra uh sound.",
        "word_stress" to "Make one syllable longer, clearer, and slightly louder than the others.",
        "sentence_stress" to "Stress the meaning-critical word and reduce less important words.",
        "rhythm" to "Keep stressed beats clear and compress the unstressed syllables between them.",
        "intonation" to "Use pitch movement to mark whether you are asking, checking, or stating.",
        "linking_reduction" to "Connect word boundaries smoothly without deleting the key consonant.",
        "vowel" to "Hold the target vowel steady before adding the surrounding consonants."
    )

    /**
     * A clean two-single-word minimal pair from a contrast string, or null. "right, light" →
     * ("right","light"); "thin, tin; this, dis" → ("thin","tin"); "school, not suh-cool" → null
     * (the second side is a description, not a word).
     */
    internal fun minimalPairOf(contrast: String): Pair<String, String>? {
        val firstPair = contrast.split(";").first()
        val words = firstPair.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val a = words[0]
        val b = words[1]
        val singleWord = Regex("^[A-Za-z]+$")
        if (!singleWord.matches(a) || !singleWord.matches(b)) return null
        if (a.equals(b, ignoreCase = true)) return null
        return a to b
    }

    fun build(target: String, category: String, domain: String): List<PronunciationDrillStage> {
        val clean = target.trim()
        if (clean.isBlank()) return emptyList()
        val pattern = PronunciationEvidencePolicy.pattern(category)
        val stages = mutableListOf<PronunciationDrillStage>()
        contrasts[pattern]?.let {
            stages += PronunciationDrillStage(
                "Hear the contrast",
                it,
                "Listen first. Identify which side contains the sound you need before repeating.",
                minimalPair = minimalPairOf(it)
            )
        }
        mouthCues[pattern]?.let {
            stages += PronunciationDrillStage("Mouth or stress cue", clean, it)
        }
        stages += PronunciationDrillStage(
            "Target",
            clean,
            "Say it slowly once, then at a natural speed without changing the key sound or stress."
        )
        stages += PronunciationDrillStage(
            "Carrier phrase",
            "Please confirm the term: $clean.",
            "Keep the target clear inside a short phrase."
        )
        stages += PronunciationDrillStage(
            "New context",
            if (domain.equals("clinical", ignoreCase = true)) {
                "I want to clarify the clinical term $clean."
            } else {
                "I want to make the word $clean clear."
            },
            "Say the whole sentence naturally. Preserve the target while shifting attention to meaning."
        )
        return stages.distinctBy { it.label to it.text.lowercase() }
    }
}
