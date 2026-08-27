package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic evaluation of a case's `learning_objectives` against the live transcript — the
 * engine behind the checklist strip under the conversation. No network, no LLM, no cost.
 *
 * This used to send the whole transcript to a model after every learner turn (10-30+ calls per
 * session) and ask it which objectives had been met. That was the most expensive call in the app
 * *and* the least accurate: because it ran so often it was routed to a low-tier fallback model, its
 * prompt asked whether each objective had been "elicited or addressed" (so information the patient
 * volunteered counted as the learner's own work), and the caller only ever merged `true` values —
 * so a single hallucinated tick from a cheap model stuck for the rest of the session. Ticks
 * appearing for questions the learner never asked was the direct result.
 *
 * The replacement is rules the learner can predict, argue with, and correct:
 *  - objectives are mapped to the same [HistoryDomain] keys the hint engine uses, via the concept
 *    lexicon below, so the checklist and "Stuck? Question idea" can no longer contradict each other;
 *  - anything the lexicon doesn't recognise falls back to content-word overlap with what the
 *    learner actually said;
 *  - every tick carries the transcript line that justifies it and how strong that evidence is, so
 *    the UI can show its reasoning instead of demanding trust.
 *
 * Live coverage is a navigation aid, deliberately not a grade. The authoritative judgement is still
 * the once-per-session post-encounter analysis, which sees the full transcript and uses the model
 * the learner actually chose.
 */

/**
 * How one objective can be satisfied. Either path alone is enough: a case whose objective maps onto
 * clinical domains is checked through the shared coverage rules, and a free-text objective
 * ("Practise everyday symptom vocabulary: runny nose, sore throat, cough") falls back to its own
 * content words appearing in the learner's speech.
 */
data class ObjectiveRule(
    val objective: String,
    /** History-domain keys that count toward this objective. */
    val domains: List<String> = emptyList(),
    /** How many of [domains] must be resolved before the objective counts as met. */
    val minDomains: Int = 0,
    /** Stemmed content words drawn from the objective text (see [CoverageEngine.contentTokens]). */
    val tokens: List<String> = emptyList(),
    /** How many distinct [tokens] the learner must have used. */
    val minTokens: Int = 0,
    /** True when a case author explicitly selected the keywords/threshold for this objective. */
    val authored: Boolean = false,
) {
    /**
     * True when a learner tick on this row can be pushed back into the domain tracker. Only
     * single-domain objectives qualify: one tap saying "I did ask about allergies" is unambiguous,
     * whereas one tap on "Elicit cardiac history using SOCRATES" must not silently mark eight
     * separate areas as asked.
     */
    val propagatesManualTick: Boolean get() = domains.size == 1
}

object CoverageEngine {

    /**
     * Objective phrasing → the clinical domains that satisfy it.
     *
     * Written against how case authors actually phrase objectives across `data/cases/` ("elicit ...
     * using SOCRATES", "address the patient's ICE", "explore cardiac risk factors"). `min` is the
     * floor for how many of the listed domains must be resolved; the effective requirement is
     * raised to half the matched set in [ruleFor], so a broad objective can't be satisfied by one
     * lucky match.
     */
    private data class Concept(val pattern: Regex, val domains: List<String>, val min: Int)

    private val SOCRATES_DOMAINS = listOf(
        "onset_duration", "location", "character", "severity",
        "radiation", "aggravating", "relieving", "progression",
    )

    private val CONCEPTS: List<Concept> = listOf(
        Concept(Regex("\\bsocrates\\b|\\bhistory\\s+of\\s+present\\s+illness\\b|\\bhpi\\b", RegexOption.IGNORE_CASE), SOCRATES_DOMAINS, 4),
        Concept(Regex("\\bpain\\s+history\\b|\\bcharacteri[sz]e\\s+the\\s+(?:pain|symptom)", RegexOption.IGNORE_CASE), SOCRATES_DOMAINS, 3),
        Concept(Regex("\\bice\\b|\\bideas,?\\s+concerns|\\bpatient(?:'s)?\\s+perspective\\b", RegexOption.IGNORE_CASE), listOf("ideas", "concerns", "expectations"), 2),
        Concept(Regex("\\brisk\\s+factors?\\b", RegexOption.IGNORE_CASE), listOf("smoking", "alcohol", "family_hx", "pmh"), 2),
        Concept(Regex("\\bsocial\\s+history\\b|\\blifestyle\\b", RegexOption.IGNORE_CASE), listOf("smoking", "alcohol", "living_work"), 2),
        Concept(Regex("\\bred\\s+flags?\\b|\\bassociated\\s+symptoms?\\b|\\bsystems?\\s+review\\b", RegexOption.IGNORE_CASE), listOf("associated_symptoms"), 1),
        Concept(Regex("\\bpast\\s+medical\\b|\\bcomorbidit|\\bmedical\\s+background\\b|\\bpmh\\b", RegexOption.IGNORE_CASE), listOf("pmh"), 1),
        Concept(Regex("\\bmedications?\\b|\\bdrug\\s+history\\b|\\bwhat\\s+they(?:'re| are)\\s+taking\\b", RegexOption.IGNORE_CASE), listOf("medications"), 1),
        Concept(Regex("\\ballerg", RegexOption.IGNORE_CASE), listOf("allergies"), 1),
        Concept(Regex("\\bfamily\\s+(?:history|hx)\\b", RegexOption.IGNORE_CASE), listOf("family_hx"), 1),
        Concept(Regex("\\bsmoking\\b|\\bsmoker\\b|\\btobacco\\b", RegexOption.IGNORE_CASE), listOf("smoking"), 1),
        Concept(Regex("\\balcohol\\b|\\bdrinking\\b", RegexOption.IGNORE_CASE), listOf("alcohol"), 1),
        Concept(Regex("\\brecreational\\s+drugs?\\b|\\bsubstance\\s+use\\b", RegexOption.IGNORE_CASE), listOf("substances"), 1),
        Concept(Regex("\\boccupation\\b|\\bliving\\s+situation\\b|\\bhome\\s+circumstances\\b|\\bwho\\s+they\\s+live\\s+with\\b", RegexOption.IGNORE_CASE), listOf("living_work"), 1),
        Concept(Regex("\\bonset\\b|\\bduration\\b|\\btimeline\\b|\\bhow\\s+long\\b", RegexOption.IGNORE_CASE), listOf("onset_duration"), 1),
        Concept(Regex("\\bseverity\\b|\\bpain\\s+score\\b", RegexOption.IGNORE_CASE), listOf("severity"), 1),
        Concept(Regex("\\bradiation\\b|\\bradiat", RegexOption.IGNORE_CASE), listOf("radiation"), 1),
        Concept(Regex("\\bconcerns?\\b|\\bworr(?:y|ies|ied)\\b|\\bfears?\\b|\\banxiet", RegexOption.IGNORE_CASE), listOf("concerns"), 1),
        Concept(Regex("\\bexpectations?\\b|\\bhoping\\s+for\\b|\\bwhat\\s+they\\s+want\\b", RegexOption.IGNORE_CASE), listOf("expectations"), 1),
        Concept(Regex("\\bideas?\\s+about\\b|\\bwhat\\s+they\\s+think\\b|\\bhealth\\s+beliefs?\\b", RegexOption.IGNORE_CASE), listOf("ideas"), 1),
        Concept(Regex("\\bgreet\\b|\\bintroduce\\b|\\brapport\\b|\\bopen(?:ing|\\s+the\\s+consultation)?\\b|\\bopen[\\s-]ended\\b|\\bchief\\s+complaint\\b|\\bpresenting\\s+complaint\\b", RegexOption.IGNORE_CASE), listOf("opening"), 1),
        Concept(Regex("\\bsummari[sz]|\\bclosing\\b|\\bclose\\s+the\\s+consultation\\b|\\bsafety[\\s-]net", RegexOption.IGNORE_CASE), listOf("summary_closing"), 1),
    )

    /**
     * Words that carry no matching value: ordinary English function words plus the pedagogical
     * verbs and nouns every objective is built from ("elicit", "demonstrate", "appropriately"). If
     * these counted, two unrelated objectives would look identical to the token matcher.
     */
    private val STOPWORDS: Set<String> = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "about", "their", "them",
        "they", "you", "your", "his", "her", "its", "our", "are", "was", "were", "have", "has",
        "had", "not", "any", "all", "can", "will", "would", "should", "when", "what", "which",
        "who", "why", "how", "then", "than", "such", "each", "other", "more", "most", "some",
        "using", "use", "used", "via", "per", "own", "one", "two", "first", "also", "well",
        "elicit", "explore", "address", "identify", "obtain", "gather", "ask", "asking", "take",
        "taking", "demonstrate", "practise", "practice", "perform", "verbalise", "verbalize",
        "discuss", "describe", "explain", "check", "confirm", "cover", "manage", "handle",
        "communicate", "respond", "recognise", "recognize", "acknowledge", "establish", "build",
        "show", "give", "provide", "ensure", "make", "let", "keep", "systematically", "clearly",
        "appropriately", "effectively", "properly", "correctly", "patient", "patients", "doctor",
        "history", "question", "questions", "information", "detail", "details", "way", "words",
        "everyday", "common", "basic", "simple", "relevant", "important", "key", "main",
    )

    /**
     * Split text into distinct, stemmed content words. Both the objective and the learner's speech
     * go through this same function, so the crude stemming only has to be *consistent* — "allergies"
     * and "allergy" need to land on the same token, not on a linguistically correct one.
     */
    fun contentTokens(text: String): List<String> {
        val normalized = InterviewRules.normalizeTranscript(text)
        return normalized
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .filter { it.length >= 3 && it !in STOPWORDS }
            .map { stem(it) }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()
            .toList()
    }

    private fun stem(word: String): String = when {
        // "allergies"/"allergy", "worries"/"worry", "injuries"/"injury" — the plural pattern that
        // actually shows up in clinical objectives.
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 5 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") ->
            word.dropLast(1)
        else -> word
    }

    /**
     * Build the rule for one objective: authored overrides first, then the concept lexicon, then
     * content-word overlap as the catch-all.
     */
    fun ruleFor(objective: String, authored: JSONObject? = null): ObjectiveRule {
        authored?.let { return authoredRule(objective, it) }

        val matched = CONCEPTS.filter { it.pattern.containsMatchIn(objective) }
        val domains = matched.flatMap { it.domains }.distinct()
        val minDomains = if (domains.isEmpty()) {
            0
        } else {
            // Raise a broad objective's bar to half its domains so one incidental match can't
            // satisfy "elicit the full pain history".
            val declared = matched.maxOf { it.min }
            minOf(domains.size, maxOf(declared, (domains.size + 1) / 2))
        }

        val tokens = contentTokens(objective)
        return ObjectiveRule(
            objective = objective,
            domains = domains,
            minDomains = minDomains,
            tokens = tokens,
            minTokens = tokenThreshold(tokens.size),
        )
    }

    /**
     * How many of an objective's content words the learner must have used. Short objectives need
     * all of them (there is nothing to spare); longer ones need about half, capped so a wordy
     * objective doesn't become unreachable.
     */
    private fun tokenThreshold(tokenCount: Int): Int = when {
        tokenCount == 0 -> 0
        tokenCount <= 2 -> tokenCount
        else -> minOf(4, maxOf(2, (tokenCount + 1) / 2))
    }

    private fun authoredRule(objective: String, spec: JSONObject): ObjectiveRule {
        val domains = spec.optJSONArray("domains").toStringList()
        val phrases = spec.optJSONArray("keywords").toStringList()
        val tokens = if (phrases.isEmpty()) contentTokens(objective) else phrases.flatMap { contentTokens(it) }.distinct()
        val minDomains = when {
            domains.isEmpty() -> 0
            spec.has("min_domains") -> spec.optInt("min_domains", 1).coerceIn(1, domains.size)
            else -> domains.size
        }
        val minTokens = when {
            tokens.isEmpty() -> 0
            spec.has("min_keywords") -> spec.optInt("min_keywords", 1).coerceIn(1, tokens.size)
            else -> tokenThreshold(tokens.size)
        }
        return ObjectiveRule(objective, domains, minDomains, tokens, minTokens, authored = true)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    /**
     * Build rules for every objective in a case.
     *
     * A case may override any objective by shipping an `objective_rules` array of
     * `{objective, domains: [...], min_domains: n, keywords: [...], min_keywords: n}`. That is
     * additive to the existing case schema, so the same JSON keeps loading everywhere it is used.
     */
    fun rulesFor(caseJson: String, objectives: List<String>): List<ObjectiveRule> {
        val authoredByObjective = mutableMapOf<String, JSONObject>()
        runCatching {
            val arr = JSONObject(caseJson).optJSONArray("objective_rules") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val key = entry.optString("objective", "").takeIf { it.isNotBlank() } ?: continue
                authoredByObjective[key] = entry
            }
        }
        return objectives.map { ruleFor(it, authoredByObjective[it]) }
    }

    /**
     * Evaluate every rule against the current session state.
     *
     * [tracker] is the encounter's domain tracker (null for modes that have no clinical history to
     * track, where only the token path applies). [transcript] is the full role/text session log.
     *
     * Returns one [CoverageEvidence] per objective, whose `source` records how it was established:
     * the learner asking outright, a bare keyword, or the patient volunteering it.
     */
    fun evaluate(
        rules: List<ObjectiveRule>,
        tracker: CoverageTracker?,
        transcript: List<Pair<String, String>>,
    ): Map<String, CoverageEvidence> {
        if (rules.isEmpty()) return emptyMap()
        // This runs after every turn, so each learner line is tokenized once here and shared across
        // all objectives rather than re-tokenized per rule.
        val learnerTurns = if (rules.any { it.tokens.isNotEmpty() }) {
            transcript.withIndex()
                .filter { it.value.first == "doctor" }
                .map { (index, turn) -> LearnerTurnTokens(index, turn.second, contentTokens(turn.second).toSet()) }
        } else {
            emptyList()
        }
        return rules.associate { rule ->
            val byDomain = evaluateDomains(rule, tracker)
            val byTokens = evaluateTokens(rule, learnerTurns)
            rule.objective to byDomain.mergedWith(byTokens)
        }
    }

    private class LearnerTurnTokens(val index: Int, val text: String, val tokens: Set<String>)

    private fun evaluateDomains(rule: ObjectiveRule, tracker: CoverageTracker?): CoverageEvidence {
        if (tracker == null || rule.domains.isEmpty() || rule.minDomains <= 0) return CoverageEvidence()
        val met = rule.domains
            .map { tracker.evidenceFor(it) }
            .filter { it.isMet }
        if (met.size < rule.minDomains) return CoverageEvidence()

        // An objective is only as solid as the weakest evidence it leans on, so take its strongest
        // contributors and report the weakest of *those* — the honest floor for the whole row.
        val contributors = met.sortedByDescending { it.source }.take(rule.minDomains)
        val floor = contributors.minByOrNull { it.source } ?: return CoverageEvidence()
        // Quote the most recent contributing line: it is the one that completed the objective, and
        // the one the learner is most likely to still remember saying.
        val newest = contributors.maxByOrNull { it.turnIndex } ?: floor
        return CoverageEvidence(source = floor.source, turnIndex = newest.turnIndex, quote = newest.quote)
    }

    private fun evaluateTokens(
        rule: ObjectiveRule,
        learnerTurns: List<LearnerTurnTokens>,
    ): CoverageEvidence {
        if (rule.tokens.isEmpty() || rule.minTokens <= 0) return CoverageEvidence()
        val wanted = rule.tokens.toSet()
        val seen = mutableSetOf<String>()
        for (turn in learnerTurns) {
            val hits = turn.tokens.filterTo(mutableSetOf()) { it in wanted }
            if (hits.isEmpty()) continue
            // An authored rule has already been reviewed by the case designer. When its full
            // threshold occurs in one coherent learner turn, that is strong navigation evidence;
            // cross-turn accumulation remains only "possible". Explicit negation/contradiction
            // keeps even a same-turn match tentative ("I would not increase it" must not look like
            // a confirmed treatment plan merely because both keywords appeared).
            if (rule.authored && hits.size >= rule.minTokens) {
                val negated = Regex(
                    "\\b(?:not|never|no|don't|doesn't|didn't|won't|wouldn't|shouldn't|can't|cannot)\\b",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(turn.text)
                return CoverageEvidence(
                    source = if (negated) CoverageSource.LEARNER_WEAK else CoverageSource.LEARNER_STRONG,
                    turnIndex = turn.index,
                    quote = CoverageSignals.shortQuote(turn.text),
                )
            }
            seen += hits
            if (seen.size >= rule.minTokens) {
                // Word overlap is suggestive, never proof of a well-formed question, so it can only
                // ever produce a "possibly covered" tick the learner is invited to confirm.
                return CoverageEvidence(
                    source = CoverageSource.LEARNER_WEAK,
                    turnIndex = turn.index,
                    quote = CoverageSignals.shortQuote(turn.text),
                )
            }
        }
        return CoverageEvidence()
    }
}
