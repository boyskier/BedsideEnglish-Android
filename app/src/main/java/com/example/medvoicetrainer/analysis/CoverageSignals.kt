package com.example.medvoicetrainer.analysis

/**
 * Android-only signal layer sitting on top of the Python-ported [InterviewRules].
 *
 * Two things drove this file. First, the live navigation aids were asymmetrically wrong: the
 * checklist over-claimed (things ticked that were never asked) while "Stuck? Question idea" under-
 * claimed (an opening question suggested deep into an encounter, because nothing the learner said
 * had matched any rule yet). Second, only one side of the conversation is noisy — the learner's
 * half is speech recognition output, but the patient's half is text the model generated, so it is
 * clean, well-formed, and by far the more reliable place to read "has this information been
 * obtained?" from.
 *
 * So this layer promotes patient answers to first-class evidence via [EXTRA_PATIENT_EVIDENCE], and
 * grades learner evidence into [CoverageSource] tiers so the UI can be honest about how it knows.
 *
 * [InterviewRules] itself keeps its Python parity (its golden vectors pin it); everything added
 * here is additive and deliberately Android-only.
 */

/**
 * Where a coverage tick came from, weakest to strongest. Declaration order *is* the precedence
 * order — [CoverageSource.compareTo] is what merges a new observation into an existing one, so a
 * later weak signal can never downgrade an earlier strong one.
 */
enum class CoverageSource {
    /** Nothing seen yet, or the learner explicitly un-ticked it. */
    NONE,

    /** Only a bare keyword or a fuzzy ASR-tolerant term matched. Shown as "possibly asked". */
    LEARNER_WEAK,

    /** The learner never clearly asked, but the patient's answer supplied the information. */
    PATIENT_VOLUNTEERED,

    /** A full question-intent phrase fired: the learner demonstrably asked this. */
    LEARNER_STRONG,

    /** The learner ticked (or un-ticked) the row themselves. Always wins over any inference. */
    MANUAL,
}

/**
 * One coverage tick plus the transcript line that justifies it, so the UI can answer "why is this
 * ticked?" with the actual sentence instead of asking the learner to trust the matcher.
 */
data class CoverageEvidence(
    val source: CoverageSource = CoverageSource.NONE,
    /** Index into the session transcript, or -1 when the quote is not from a recorded turn. */
    val turnIndex: Int = -1,
    val quote: String = "",
) {
    val isMet: Boolean get() = source != CoverageSource.NONE

    /** True when the tick is solid enough to present as a plain "you did this". */
    val isConfident: Boolean
        get() = source == CoverageSource.LEARNER_STRONG || source == CoverageSource.MANUAL

    /** Keep whichever observation is stronger; ties keep the earlier (first) evidence. */
    fun mergedWith(other: CoverageEvidence): CoverageEvidence =
        if (other.source > source) other else this
}

object CoverageSignals {

    /**
     * How many learner turns an encounter runs before the opening question is treated as bygone.
     *
     * This is the deliberate belt-and-braces for the reported bug: the ordering rules already drop
     * "opening" once any later-phase domain is recognised, but that guard is itself made of
     * matches, so a run of unrecognised turns kept "What brings you in today?" alive as the
     * suggestion. Counting turns needs no match to succeed, so it holds even when every rule misses.
     */
    const val OPENING_STALE_AFTER_LEARNER_TURNS = 3

    /**
     * Additional patient-side evidence, unioned with [InterviewRules.PATIENT_EVIDENCE_PATTERNS].
     *
     * These are intentionally looser than the ported set, because a patient stating a fact is much
     * weaker proof of *bad* practice than a learner failing to ask: at worst an over-eager match
     * here marks a domain as "the patient told you this", which is displayed distinctly from "you
     * asked this" and never claimed as the learner's own work.
     *
     * Negation is not filtered — "I don't take any medication" resolves medications just as well as
     * a list of drugs does. The information exists either way.
     */
    val EXTRA_PATIENT_EVIDENCE: Map<String, Regex> = mapOf(
        // The whole point of the opening domain is "did the consultation get started?". A patient
        // narrating why they are here answers that on its own, whatever the ASR made of the
        // learner's greeting.
        "opening" to Regex(
            "\\bi(?:'m| am)\\s+here\\s+(?:because|for|about|to)\\b" +
                "|\\bi\\s+came\\s+(?:in|here|to\\s+see)\\b" +
                "|\\bi(?:'ve| have)\\s+been\\s+(?:having|feeling|getting)\\b" +
                "|\\bi(?:'ve| have)\\s+(?:got|had)\\s+(?:a|an|this|some)\\b" +
                "|\\bi\\s+(?:have|feel|felt|keep)\\b.{0,30}\\b(?:pain|ache|sore|cough|fever|sick|dizzy|tired|trouble|problem|rash|nausea)\\b" +
                "|\\bmy\\s+\\w+\\s+(?:hurts?|aches?|has\\s+been|have\\s+been|is\\s+killing)\\b" +
                "|\\bit(?:'s| is)?\\s+(?:my|this)\\s+\\w+\\b.{0,20}\\b(?:doctor|again)\\b" +
                "|\\bthanks?\\s+for\\s+seeing\\s+me\\b" +
                "|\\bwell,?\\s+doctor\\b",
            RegexOption.IGNORE_CASE
        ),
        "onset_duration" to Regex(
            "\\b(?:a\\s+few\\s+|a\\s+couple\\s+of\\s+|several\\s+|\\d+\\s+)?" +
                "(?:minutes?|hours?|days?|weeks?|months?|years?)\\s+ago\\b" +
                "|\\b(?:this\\s+morning|this\\s+afternoon|last\\s+night|yesterday|overnight|" +
                "the\\s+day\\s+before\\s+yesterday|last\\s+(?:week|month|year))\\b" +
                "|\\bever\\s+since\\b" +
                "|\\bit\\s+(?:came|come)\\s+on\\b",
            RegexOption.IGNORE_CASE
        ),
        "location" to Regex(
            "\\b(?:over|down|up)\\s+here\\b" +
                "|\\b(?:left|right|lower|upper)\\s+(?:side|chest|abdomen|back|arm|leg|belly)\\b" +
                "|\\ball\\s+over\\s+my\\b" +
                "|\\bjust\\s+(?:below|above|behind|under)\\s+my\\b",
            RegexOption.IGNORE_CASE
        ),
        "character" to Regex(
            "\\b(?:aching|cramping|shooting|gnawing|heavy|tingling|numb|stinging|colicky)\\b" +
                "|\\bit\\s+feels\\s+like\\b" +
                "|\\blike\\s+(?:a|an)\\s+(?:band|weight|elephant|knife|vice)\\b",
            RegexOption.IGNORE_CASE
        ),
        // severity is deliberately absent: a bare "it hurts quite a lot" is not a graded answer,
        // and the ported rule (a number out of ten) is exactly the right strictness.
        "aggravating" to Regex(
            "\\b(?:worse|hurts?\\s+more|sets?\\s+it\\s+off|brings?\\s+it\\s+on)\\b" +
                ".{0,25}\\b(?:when|if|after|during|walking|moving|eating|lying|breathing|coughing|exercise)\\b" +
                "|\\bif\\s+i\\s+\\w+.{0,20}\\b(?:it\\s+gets\\s+worse|worse)\\b" +
                "|\\bcan(?:'t|not)\\s+\\w+\\s+without\\b",
            RegexOption.IGNORE_CASE
        ),
        "relieving" to Regex(
            "\\bnothing\\s+(?:really\\s+)?(?:helps?|works?|makes?\\s+it\\s+better)\\b" +
                "|\\b(?:resting|lying\\s+down|sitting|paracetamol|ibuprofen|antacids?|painkillers?)\\b" +
                ".{0,20}\\b(?:helps?|eases?|better)\\b" +
                "|\\b(?:helps?|eases?|better)\\b.{0,20}\\b(?:when\\s+i\\s+rest|if\\s+i\\s+rest|lying\\s+down)\\b" +
                "|\\bgoes?\\s+away\\s+(?:when|if|after)\\b",
            RegexOption.IGNORE_CASE
        ),
        "progression" to Regex(
            "\\b(?:on\\s+and\\s+off|comes?\\s+and\\s+goes?|all\\s+the\\s+time|non\\s*-?\\s*stop)\\b" +
                "|\\b(?:getting|got|been\\s+getting)\\s+(?:worse|better)\\b" +
                "|\\bit\\s+(?:hasn't|has\\s+not)\\s+changed\\b" +
                "|\\b(?:every|a\\s+few\\s+times)\\s+(?:day|night|hour|week)\\b",
            RegexOption.IGNORE_CASE
        ),
        "associated_symptoms" to Regex(
            "\\b(?:also|as\\s+well|too|along\\s+with\\s+that|on\\s+top\\s+of\\s+that)\\b.{0,40}" +
                "\\b(?:nausea|nauseous|vomit\\w*|sweat\\w*|dizz\\w*|fever|chills?|breathless\\w*|" +
                "short\\s+of\\s+breath|palpitations?|tired\\w*|weak\\w*|cough\\w*|headaches?|rash)\\b" +
                "|\\bi(?:'ve| have)\\s+(?:also\\s+)?been\\s+(?:feeling\\s+)?" +
                "(?:sick|nauseous|dizzy|sweaty|feverish|breathless|short\\s+of\\s+breath)\\b" +
                "|\\bno\\s+(?:other\\s+symptoms|nausea|fever|vomiting)\\b",
            RegexOption.IGNORE_CASE
        ),
        "pmh" to Regex(
            "\\b(?:high\\s+blood\\s+pressure|high\\s+cholesterol|heart\\s+(?:attack|failure)|" +
                "stroke|kidney\\s+disease|thyroid)\\b" +
                "|\\bi(?:'m| am)\\s+(?:otherwise\\s+)?(?:healthy|fit|well)\\b" +
                "|\\bno(?:thing)?\\s+(?:medical\\s+)?(?:problems?|conditions?|history)\\b" +
                "|\\bnever\\s+been\\s+(?:ill|in\\s+hospital|to\\s+hospital)\\b" +
                "|\\bmy\\s+(?:gp|doctor)\\s+(?:said|told\\s+me|put\\s+me\\s+on)\\b",
            RegexOption.IGNORE_CASE
        ),
        "medications" to Regex(
            "\\b(?:lisinopril|amlodipine|omeprazole|atorvastatin|simvastatin|ramipril|bisoprolol|" +
                "salbutamol|levothyroxine|warfarin|paracetamol|ibuprofen|antacids?|painkillers?)\\b" +
                "|\\bi\\s+don'?t\\s+take\\s+(?:any|anything|medication)\\b" +
                "|\\bno\\s+(?:regular\\s+)?(?:medications?|medicines?|tablets?|pills?)\\b" +
                "|\\bjust\\s+(?:the\\s+)?(?:one\\s+)?(?:tablet|pill|inhaler)\\b" +
                "|\\bover\\s+the\\s+counter\\b" +
                "|\\b(?:twice|once|three\\s+times)\\s+a\\s+day\\b",
            RegexOption.IGNORE_CASE
        ),
        "allergies" to Regex(
            "\\bno\\s+allergies\\b" +
                "|\\bi(?:'m| am)\\s+not\\s+allergic\\b" +
                "|\\bnone\\s+that\\s+i\\s+know\\s+of\\b" +
                "|\\bcomes?\\s+out\\s+in\\s+(?:a\\s+)?rash\\b",
            RegexOption.IGNORE_CASE
        ),
        "smoking" to Regex(
            "\\b(?:pack|packs|\\d+\\s+cigarettes?)\\s+a\\s+day\\b" +
                "|\\b(?:quit|gave\\s+up|stopped)\\s+(?:smoking|years\\s+ago)\\b" +
                "|\\bnever\\s+smoked\\b" +
                "|\\bsocial\\s+smoker\\b",
            RegexOption.IGNORE_CASE
        ),
        "alcohol" to Regex(
            "\\bi\\s+(?:drink|have)\\b.{0,25}\\b(?:beer|wine|pints?|glasses?|drinks?|spirits?)\\b" +
                "|\\b(?:a\\s+couple\\s+of|one\\s+or\\s+two|two\\s+or\\s+three)\\s+" +
                "(?:beers?|wines?|pints?|glasses?|drinks?)\\b" +
                "|\\bonly\\s+(?:socially|at\\s+weekends|on\\s+weekends)\\b" +
                "|\\bi\\s+don'?t\\s+drink\\b" +
                "|\\bteetotal\\b",
            RegexOption.IGNORE_CASE
        ),
        "substances" to Regex(
            "\\bi\\s+don'?t\\s+(?:use|touch|do)\\s+(?:drugs|anything)\\b" +
                "|\\bnever\\s+(?:used|taken)\\s+drugs\\b",
            RegexOption.IGNORE_CASE
        ),
        "living_work" to Regex(
            "\\bi(?:'m| am)\\s+(?:retired|unemployed|a\\s+student|self\\s*-?\\s*employed)\\b" +
                "|\\bi\\s+live\\s+(?:alone|with|at)\\b" +
                "|\\bi\\s+work\\s+(?:as|in|at|for)\\b" +
                "|\\bmy\\s+(?:children|kids|son|daughter|family)\\s+(?:live|help|are)\\b" +
                "|\\bon\\s+my\\s+own\\b",
            RegexOption.IGNORE_CASE
        ),
        "family_hx" to Regex(
            "\\bruns?\\s+in\\s+(?:the|my)\\s+family\\b" +
                "|\\bmy\\s+(?:mum|mom|dad|mother|father|brother|sister|gran|grandmother|grandfather)\\b" +
                ".{0,40}\\b(?:had|has|died|passed|got|diagnosed)\\b" +
                "|\\bno(?:body|\\s+one)\\s+in\\s+(?:the|my)\\s+family\\b" +
                "|\\bnothing\\s+(?:like\\s+that\\s+)?in\\s+(?:the|my)\\s+family\\b",
            RegexOption.IGNORE_CASE
        ),
        "ideas" to Regex(
            "\\b(?:maybe|perhaps|could)\\s+it(?:'s| is|\\s+be)\\b" +
                "|\\bi\\s+(?:reckon|assumed|figured|googled)\\b" +
                "|\\bi\\s+(?:have\\s+)?no\\s+idea\\s+what\\b" +
                "|\\bmy\\s+(?:wife|husband|friend|mum|mom)\\s+(?:thinks?|said)\\b",
            RegexOption.IGNORE_CASE
        ),
        "concerns" to Regex(
            "\\bis\\s+it\\s+(?:serious|cancer|my\\s+heart|dangerous|something\\s+bad)\\b" +
                "|\\bi\\s+keep\\s+thinking\\b" +
                "|\\bit\\s+(?:frightens|scares)\\s+me\\b" +
                "|\\bam\\s+i\\s+(?:going\\s+to\\s+be\\s+)?(?:ok|okay|alright|dying)\\b",
            RegexOption.IGNORE_CASE
        ),
        "expectations" to Regex(
            "\\bi\\s+(?:just\\s+)?(?:wanted|would\\s+like|was\\s+after)\\b" +
                "|\\bcan\\s+you\\s+(?:give|do|check|test|refer|prescribe)\\b" +
                "|\\bdo\\s+i\\s+need\\s+(?:a|an|any)\\b" +
                "|\\bi\\s+was\\s+told\\s+to\\s+come\\b",
            RegexOption.IGNORE_CASE
        ),
    )

    /**
     * True when the patient's turn supplies this domain's information, by either the ported
     * evidence rules or the additional ones above.
     */
    fun patientEvidence(key: String, text: String): Boolean {
        if (InterviewRules.patientDomainEvidence(key, text)) return true
        val extra = EXTRA_PATIENT_EVIDENCE[key] ?: return false
        val value = InterviewRules.normalizeTranscript(text)
        return value.isNotEmpty() && extra.containsMatchIn(value)
    }

    /** Grade one learner turn against one domain. See [InterviewRules.studentDomainStrength]. */
    fun learnerSource(key: String, text: String, keywords: List<String> = emptyList()): CoverageSource =
        when (InterviewRules.studentDomainStrength(key, text, keywords)) {
            MatchStrength.STRONG -> CoverageSource.LEARNER_STRONG
            MatchStrength.WEAK -> CoverageSource.LEARNER_WEAK
            MatchStrength.NONE -> CoverageSource.NONE
        }

    /**
     * Trim a transcript line down to something that fits on one row of the checklist card, cutting
     * at a word boundary so the quote never ends mid-word.
     */
    fun shortQuote(text: String, limit: Int = 90): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= limit) return clean
        val cut = clean.take(limit)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
    }
}
