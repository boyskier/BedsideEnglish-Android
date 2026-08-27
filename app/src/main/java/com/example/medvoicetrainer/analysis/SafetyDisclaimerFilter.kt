package com.example.medvoicetrainer.analysis

/**
 * Strips the model's own medical-safety boilerplate out of an in-character turn.
 *
 * Gemini Live (and, less often, OpenAI Realtime) will sometimes append a stock disclaimer to a
 * roleplay patient's line — e.g.
 *
 * > "The bloating is mostly like all over my stomach. […]
 * >
 * >  The information provided is not medical advice or a diagnosis. You should consult a
 * >  healthcare professional for advice on your health."
 *
 * A patient never says that. It breaks character, it pollutes the transcript the analysis
 * backends grade (the disclaimer's vocabulary is not the learner's), and it is the single most
 * frequently reported immersion break. Prompt instructions reduce it (see PromptBuilder's
 * NO_SAFETY_DISCLAIMER_NOTE) but do not eliminate it, because the disclaimer is bolted on by the
 * provider's own safety layer after the persona has spoken — hence this deterministic second
 * line of defense on the text side.
 *
 * Deliberate scope limits:
 *  * This only ever removes text; it never rewrites or paraphrases. If nothing matches, the turn
 *    comes back byte-identical.
 *  * It only runs on the AI's turns. Learner speech is never filtered — if a learner actually
 *    says "consult a healthcare professional" in a counselling encounter, that is exactly the
 *    English we want to grade.
 *  * It matches whole sentences anchored on the stock phrasings below, not any mention of
 *    "medical advice", so a patient asking "should I get medical advice about this?" survives.
 *
 * Deliberately contains no `android.*` references so it is covered by plain JVM unit tests.
 */
object SafetyDisclaimerFilter {

    /**
     * Sentence-level signatures of the boilerplate. A hit alone never removes anything — the
     * matches have to cover most of the sentence they sit in (see [isDisclaimerSentence]), so
     * ordinary conversational uses of the same words are untouched.
     */
    private val DISCLAIMER_PATTERNS: List<Regex> = listOf(
        // "The information provided is not medical advice or a diagnosis."
        // "This is not medical advice." / "I am not a doctor and this isn't medical advice."
        """(?:^|\b)(?:the\s+)?(?:information|content|response|this|that|it)\b[^.!?]{0,80}?\b(?:is|are|was)\s+not\s+(?:intended\s+as\s+)?(?:a\s+substitute\s+for\s+)?(?:professional\s+)?medical\s+(?:advice|guidance)""",
        """\bnot\s+(?:a\s+)?(?:medical\s+)?(?:advice|diagnosis)\s+or\s+(?:a\s+)?(?:diagnosis|treatment|medical\s+advice)""",
        """\bi(?:'m| am)\s+not\s+a\s+(?:doctor|physician|medical\s+professional|healthcare\s+professional)\b[^.!?]{0,80}""",
        // "You should consult a healthcare professional for advice on your health."
        // "Please consult a doctor / seek professional medical advice."
        """\b(?:you\s+should\s+)?(?:please\s+|always\s+)?(?:consult|see|talk\s+to|speak\s+(?:to|with)|seek(?:\s+advice\s+from)?)\s+(?:a|an|your)\s+(?:qualified\s+|licensed\s+)?(?:healthcare|health\s+care|medical)\s+(?:professional|provider|practitioner|expert)\b""",
        """\b(?:you\s+should\s+)?(?:please\s+|always\s+)(?:consult|see|talk\s+to|speak\s+(?:to|with))\s+(?:a|an|your)\s+(?:doctor|physician|clinician)\b""",
        """\bseek\s+(?:professional\s+)?medical\s+(?:advice|attention|help)\b""",
        """\bfor\s+(?:advice|guidance|information)\s+(?:on|about)\s+your\s+(?:health|medical\s+condition)\b""",
        """\bthis\s+(?:is|does)\s+not\s+(?:constitute|replace|substitute\s+for)\s+(?:professional\s+)?medical\s+(?:advice|care|treatment)\b""",
        """\b(?:this|that|it)\s+(?:is|was)\s+not\s+(?:a\s+)?(?:medical\s+)?diagnosis\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * Splits on sentence terminators while keeping the terminator attached to its sentence, so a
     * removed sentence takes its own punctuation with it and leaves the rest of the turn intact.
     */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

    /**
     * A sentence is dropped only when matched boilerplate covers at least this much of it — the
     * guard that stops "I saw a healthcare professional about my back last year, and she said it
     * was fine" (a legitimate patient line containing a matched phrase) from disappearing.
     */
    private const val MIN_MATCH_RATIO = 0.5

    /**
     * True when [sentence] reads as provider safety boilerplate rather than persona speech.
     *
     * Coverage is measured over the *union* of all pattern hits, not the longest single one:
     * the boilerplate is routinely two clauses joined by "and" ("this is not medical advice and
     * you should consult a healthcare professional"), where neither half alone clears the ratio
     * but together they are the whole sentence.
     */
    private fun isDisclaimerSentence(sentence: String): Boolean {
        val core = sentence.trim().trimEnd('.', '!', '?', '"', '”', ')', ' ')
        if (core.isEmpty()) return false
        val spans = DISCLAIMER_PATTERNS.flatMap { pattern ->
            pattern.findAll(core).map { it.range }.toList()
        }
        if (spans.isEmpty()) return false
        val covered = BooleanArray(core.length)
        for (span in spans) {
            for (i in span.first.coerceAtLeast(0)..span.last.coerceAtMost(core.length - 1)) {
                covered[i] = true
            }
        }
        return covered.count { it }.toDouble() / core.length >= MIN_MATCH_RATIO
    }

    /**
     * Returns [text] with any trailing/embedded safety-disclaimer sentences removed, collapsing
     * the blank line the provider usually puts in front of them. Returns an empty string when the
     * whole turn was nothing but boilerplate — callers should drop such a turn entirely rather
     * than record an empty patient line.
     */
    fun strip(text: String): String {
        if (text.isBlank()) return ""
        // Cheap pre-filter: the boilerplate always names one of these. Skips the regex sweep for
        // the overwhelming majority of turns, which matters because this runs per transcript turn
        // on the voice callback path.
        val lowered = text.lowercase()
        if (SCREEN_TERMS.none { it in lowered }) return text

        val kept = text
            .split(SENTENCE_SPLIT)
            .filterNot { isDisclaimerSentence(it) }
            .joinToString(" ") { it.trim() }
            .trim()
        // Providers put the disclaimer in its own paragraph; once its sentences are gone the
        // leftover blank lines/extra spaces would otherwise show up as a ragged tail.
        return kept.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val SCREEN_TERMS = listOf(
        "medical advice",
        "medical guidance",
        "healthcare",
        "health care",
        "medical professional",
        "medical provider",
        "medical attention",
        "doctor",
        "physician",
        "clinician",
        "diagnosis",
    )

    /** True when [text] would be reduced to nothing — i.e. the turn was disclaimer and nothing else. */
    fun isEntirelyDisclaimer(text: String): Boolean = text.isNotBlank() && strip(text).isBlank()
}
