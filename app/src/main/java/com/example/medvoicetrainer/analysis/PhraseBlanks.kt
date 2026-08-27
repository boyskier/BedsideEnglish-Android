package com.example.medvoicetrainer.analysis

/**
 * Cloze blanks — the `___` runs in the shared phrase/drill content under `data/` (e.g.
 * "Hello, my name is ___." or "I'm calling about ___, a ___-year-old patient with ___.").
 *
 * A blank is a slot the learner fills with their own words, so it is neither speakable nor
 * gradeable text and must never be handed to a downstream engine verbatim:
 *
 *  - Android TTS reads a literal underscore run out loud (or swallows the whole utterance),
 *    so the model audio the learner is asked to copy is wrong. [segments] splits the phrase at
 *    the blanks instead, and the caller speaks the pieces with a real silence between them.
 *  - The intelligibility comparator would treat `___` as a word the learner failed to say, and
 *    would flag the name/number/medication they actually put in the slot as an *added*
 *    meaning-critical item — a guaranteed CRITICAL_MISMATCH on every blanked phrase.
 *    [comparisonTarget] reduces the phrase to the fixed frame that is genuinely gradeable.
 *
 * Kept deliberately deterministic and free of Android dependencies so it is unit-testable and
 * usable from both the UI (TTS) and the analysis layer (judging).
 */
object PhraseBlanks {
    /** Two or more underscores. A single `_` inside a word (`_note`) is not a learner slot. */
    private val BLANK_RUN = Regex("_{2,}")

    /** Punctuation left dangling once the blank in front of it is gone (", a", "-year-old", "."). */
    private val ORPHANED_PUNCTUATION = Regex("^[\\s,.;:!?/\\\\-]+")

    private val WHITESPACE = Regex("\\s+")

    /** Silence spoken in place of a blank, long enough to read as "your turn" without dead air. */
    const val BLANK_PAUSE_MS = 550L

    fun hasBlank(text: String): Boolean = BLANK_RUN.containsMatchIn(text)

    /**
     * The speakable pieces of [text], in order, with blanks (and the punctuation they stranded)
     * removed. A phrase with no blank yields the single trimmed phrase, so callers can always
     * drive TTS from this and get unchanged behaviour on ordinary sentences.
     */
    fun segments(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        if (!hasBlank(text)) return listOf(text.trim())
        return BLANK_RUN.split(text)
            .mapIndexed { index, part ->
                // Only a part that *follows* a blank can start with punctuation the blank owned.
                if (index == 0) part else ORPHANED_PUNCTUATION.replace(part, " ")
            }
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { part -> part.any(Char::isLetterOrDigit) }
    }

    /**
     * One-utterance fallback for a speech engine that cannot insert a pause: the segments joined
     * by a comma, which every TTS voice renders as a short break.
     */
    fun speechText(text: String): String = segments(text).joinToString(", ")

    /**
     * The fixed frame to grade against — the words the learner is actually expected to produce.
     * Terminal punctuation inside a segment is preserved, because the blind transcript the frame
     * is compared with is punctuated too.
     */
    fun comparisonTarget(text: String): String = segments(text).joinToString(" ")

    /**
     * [text] with each blank spelled out for an LLM prompt, so a coaching model reads the slot as
     * free learner content instead of trying to explain a row of underscores.
     */
    fun describedForPrompt(text: String): String =
        BLANK_RUN.replace(text, "[the learner's own words]")
}
