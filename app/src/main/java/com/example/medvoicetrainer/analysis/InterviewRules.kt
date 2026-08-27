package com.example.medvoicetrainer.analysis

import java.text.Normalizer

/**
 * How confidently one learner turn matched a history domain. See
 * [InterviewRules.studentDomainStrength].
 */
enum class MatchStrength { NONE, WEAK, STRONG }

/**
 * Ported from app/analysis/interview_rules.py — shared deterministic text rules for
 * clinical-history navigation. The live transcript is produced by speech recognition, so
 * exact sentence matching is intentionally avoided: these helpers normalise common ASR output,
 * match flexible clinical intents, and keep student-question matching separate from
 * patient-answer matching. No network or model calls are made here.
 */
object InterviewRules {

    private val NEGATION_RE = Regex(
        "\\b(no|not|never|don'?t|doesn'?t|didn'?t|without|deny|denies|denied)\\b[^.!?]{0,30}$"
    )

    private val QUESTION_START_RE = Regex(
        "(?:^|[.!?]\\s*)(?:what|when|where|which|who|why|how|any|no|do|does|did|have|has|had|" +
            "are|is|was|were|can|could|would|will|may|tell|describe|show|rate)\\b"
    )

    // normalizeTranscript runs on every learner and patient turn, and CoverageTracker also ran
    // it over each domain's chief_terms/follow_cues, so these are compiled once here rather
    // than rebuilt on every call.
    private val PUNCTUATION_RE = Regex("[,;:()\\[\\]\"]+")
    private val MEDITATION_RE =
        Regex("\\b(take|taking|on|any|regular|regularly)\\s+meditations?\\b")
    private val ENERGIES_RE =
        Regex("\\b(any|known|drug|medicine|medication)\\s+energies\\b")
    private val WHITESPACE_RE = Regex("\\s+")
    private val SO_YOU_RE = Regex("(?:^|\\bso\\s+)you\\b")
    private val YOUR_START_RE = Regex("^your\\b")
    private val LOWER_ALPHA_RUN_RE = Regex("[a-z]+")

    /** Return stable, case-folded text while preserving sentence boundaries. */
    fun normalizeTranscript(text: String?): String {
        var value = Normalizer.normalize(text ?: "", Normalizer.Form.NFKC)
        value = value.replace("’", "'").replace("‘", "'").replace("`", "'")
        value = value.replace("–", "-").replace("—", "-")
        value = value.lowercase()
        value = value.replace(PUNCTUATION_RE, " ")
        // Conservative, context-bound ASR repairs. They only fire in phrases that already
        // look like a clinical question, avoiding global word replacement.
        value = value.replace(MEDITATION_RE) {
            "${it.groupValues[1]} medication"
        }
        value = value.replace(ENERGIES_RE) {
            "${it.groupValues[1]} allergies"
        }
        value = value.replace(WHITESPACE_RE, " ").trim()
        return value
    }

    /**
     * Boundary-light substring matching with optional negation filtering.
     *
     * Partial medical stems such as "allerg" remain supported. Negation is useful for PATIENT
     * follow-up cues ("no pain" should not cue character), but student questions use
     * [studentKeywordHit] since "You don't smoke?" still covers smoking history.
     */
    fun kwHit(text: String, keyword: String, respectNegation: Boolean = true): Boolean {
        if (text.isEmpty() || keyword.isEmpty()) return false
        // The pattern here was Regex(Regex.escape(keyword)), rebuilt on every call -- a compiled
        // regex whose only job was a literal search. indexOf visits exactly the same
        // non-overlapping matches in the same order (advancing past the match is what findAll
        // does for a literal pattern), so the scan is unchanged minus the per-call compilation.
        // CoverageTracker calls this once per follow-cue per domain per patient turn.
        var from = 0
        while (true) {
            val at = text.indexOf(keyword, from)
            if (at < 0) return false
            if (!respectNegation) return true
            val preceding = text.substring(maxOf(0, at - 40), at)
            if (!NEGATION_RE.containsMatchIn(preceding)) return true
            from = at + keyword.length
        }
    }

    private fun looksLikeStudentQuestion(text: String): Boolean {
        return "?" in text ||
            QUESTION_START_RE.containsMatchIn(text) ||
            SO_YOU_RE.containsMatchIn(text) ||
            YOUR_START_RE.containsMatchIn(text)
    }

    /** Match a learner's clinical intent without rejecting negative questions. */
    fun studentKeywordHit(text: String, keyword: String): Boolean {
        if (text.isEmpty() || keyword.isEmpty()) return false
        return if (looksLikeStudentQuestion(text)) {
            kwHit(text, keyword, respectNegation = false)
        } else {
            kwHit(text, keyword, respectNegation = true)
        }
    }

    /** Constrained fuzzy token match for long, distinctive ASR anchors only. */
    private fun nearTerm(text: String, terms: List<String>, threshold: Double = 0.82): Boolean {
        val tokens = LOWER_ALPHA_RUN_RE.findAll(text).map { it.value }.toList()
        for (term in terms) {
            if (term in text) return true
            if (term.length < 6) continue
            for (token in tokens) {
                if (token.length < 5 || token[0] != term[0] || Math.abs(token.length - term.length) > 2) continue
                if (similarityRatio(token, term) >= threshold) return true
            }
        }
        return false
    }

    /** Equivalent of difflib.SequenceMatcher(None, a, b).ratio(): 2*M / (len(a)+len(b)). */
    private fun similarityRatio(a: String, b: String): Double {
        val matches = matchingBlocksLength(a, b)
        val total = a.length + b.length
        return if (total == 0) 1.0 else 2.0 * matches / total
    }

    private fun matchingBlocksLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        // Longest common subsequence of matching runs, Ratcliff/Obershelp style,
        // via the classic recursive longest-matching-block split.
        val (start, len) = longestMatch(a, 0, a.length, b, 0, b.length)
        if (len == 0) return 0
        var total = len
        total += matchingBlocksLength(a.substring(0, start.first), b.substring(0, start.second))
        total += matchingBlocksLength(
            a.substring(start.first + len), b.substring(start.second + len)
        )
        return total
    }

    private fun longestMatch(a: String, aLo: Int, aHi: Int, b: String, bLo: Int, bHi: Int): Pair<Pair<Int, Int>, Int> {
        var bestI = aLo
        var bestJ = bLo
        var bestSize = 0
        val j2len = HashMap<Int, Int>()
        for (i in aLo until aHi) {
            val newJ2Len = HashMap<Int, Int>()
            for (j in bLo until bHi) {
                if (a[i] == b[j]) {
                    val k = (j2len[j - 1] ?: 0) + 1
                    newJ2Len[j] = k
                    if (k > bestSize) {
                        bestI = i - k + 1
                        bestJ = j - k + 1
                        bestSize = k
                    }
                }
            }
            j2len.clear()
            j2len.putAll(newJ2Len)
        }
        return Pair(bestI, bestJ) to bestSize
    }

    // Strong intent patterns deliberately require a phrase or a pair of clinical anchors.
    // Generic words such as "today", "help", "when", and "drink" never advance a phase alone.
    val DOMAIN_PATTERNS: Map<String, Regex> = mapOf(
        "opening" to Regex(
            "\\bwhat(?:'s)?(?:\\s+\\w+){0,3}\\s+(?:brings|brought)\\s+you\\b" +
                "|\\bwhat(?:'s|\\s+has|\\s+is)?\\s+brought\\s+you\\s+(?:in|here)\\b" +
                "|\\bhow\\s+(?:can|may|could)\\s+i\\s+help\\s+you\\b" +
                "|\\bwhat\\s+can\\s+i\\s+do\\s+for\\s+you\\b" +
                "|\\bwhat(?:\\s+\\w+){0,3}\\s+seems\\s+to\\s+be\\s+(?:the\\s+)?(?:main\\s+)?(?:problem|matter)\\b" +
                "|\\btell\\s+me.{0,25}\\b(?:brought\\s+you|going\\s+on|problem|symptoms?)\\b" +
                "|\\breason.{0,18}\\b(?:visit|came|come|here)\\b",
            RegexOption.IGNORE_CASE
        ),
        "onset_duration" to Regex(
            "\\bhow\\s+long\\b" +
                "|\\bfor\\s+how\\s+many\\s+(?:hours?|days?|weeks?|months?|years?)\\b" +
                "|\\bwhen\\b.{0,30}\\b(?:did|has|have|does|was|were|start|begin|began|occur|happen|first|notice)\\b" +
                "|\\bsince\\s+when\\b" +
                "|\\bfirst.{0,18}\\b(?:notice|feel|occur|happen|start|begin|began)\\b" +
                "|\\b(?:come|came)\\s+on\\b" +
                "|\\b(?:sudden|suddenly|gradual|gradually).{0,18}\\b(?:start|begin|onset|come|came)\\b" +
                "|\\bduration\\b",
            RegexOption.IGNORE_CASE
        ),
        "location" to Regex(
            "\\bwhere(?:\\s+exactly)?\\b.{0,25}\\b(?:pain|hurt|ache|feel|located|discomfort|symptom)\\b" +
                "|\\b(?:show|point).{0,18}\\bwhere\\b" +
                "|\\bwhich\\s+(?:part|side|area)\\b",
            RegexOption.IGNORE_CASE
        ),
        "character" to Regex(
            "\\bdescribe\\b.{0,25}\\b(?:pain|ache|discomfort|feeling|sensation|it)\\b" +
                "|\\bwhat.{0,18}\\b(?:feel|feels|feeling)\\s+like\\b" +
                "|\\bwhat\\s+(?:kind|type|sort|quality).{0,18}\\b(?:pain|ache|discomfort|sensation|it)\\b" +
                "|\\bis\\s+it.{0,12}\\b(?:sharp|dull|burning|stabbing|throbbing|crushing|squeezing|tight)\\b" +
                "|\\b(?:is|does).{0,18}\\b(?:pain|it).{0,12}\\b(?:sharp|dull|burning|stabbing|throbbing|crushing|squeezing|tight|throb)\\w*\\b" +
                "|\\b(?:pain|it).{0,12}\\b(?:sharp|dull|burning|stabbing|throbbing|crushing|squeezing|tight|throb)\\w*\\b" +
                "|\\b(?:sharp|dull|burning|stabbing|throbbing|crushing|squeezing|pressure|tightness)\\b" +
                ".{0,15}\\b(?:pain|sensation|feeling|chest|it)\\b",
            RegexOption.IGNORE_CASE
        ),
        "severity" to Regex(
            "\\b(?:scale|rate|score|level).{0,20}\\b(?:pain|it|this|discomfort|symptom|severity)\\b" +
                "|\\b(?:zero|one|1)\\s*(?:to|through|-)\\s*(?:ten|10)\\b" +
                "|\\b(?:out\\s+of|over)\\s+(?:ten|10)\\b" +
                "|\\bten\\s+being\\s+(?:the\\s+)?worst\\b" +
                "|\\bhow\\s+(?:bad|severe|intense|painful)\\b",
            RegexOption.IGNORE_CASE
        ),
        "radiation" to Regex(
            "\\b(?:radiat\\w*|spread|travel|move|go)\\b.{0,25}\\b(?:anywhere|else|arm|back|jaw|neck|shoulder)\\b" +
                "|\\b(?:anywhere|arm|back|jaw|neck|shoulder)\\b.{0,18}\\b(?:spread|travel|move|go)\\b",
            RegexOption.IGNORE_CASE
        ),
        "aggravating" to Regex(
            "\\b(?:make|makes|making).{0,22}\\b(?:worse|bad)\\b" +
                "|\\b(?:worsen|aggravat\\w*|trigger|bring\\s+it\\s+on)\\b" +
                "|\\bworse\\s+(?:when|with|after|during|on)\\b" +
                "|\\bwhat.{0,18}\\bmakes?\\s+it\\s+worse\\b",
            RegexOption.IGNORE_CASE
        ),
        "relieving" to Regex(
            "\\b(?:make|makes|making).{0,22}\\b(?:better|easier)\\b" +
                "|\\b(?:reliev\\w*|ease\\w*|improv\\w*).{0,18}\\b(?:it|pain|symptoms?)?\\b" +
                "|\\bbetter\\s+(?:when|with|after|during|on)\\b" +
                "|\\b(?:anything|what).{0,18}\\bhelp(?:s|ed)?\\b" +
                "|\\bdoes.{0,22}\\bhelp(?:s|ed)?\\b" +
                "|\\b(?:rest|medicine|medication|tablet|pill).{0,12}\\b(?:help|ease|better|reliev)\\w*\\b",
            RegexOption.IGNORE_CASE
        ),
        "aggravating_relieving" to Regex(
            "\\b(?:make|makes|making).{0,22}\\b(?:better|worse|easier)\\b" +
                "|\\b(?:worsen|aggravat\\w*|trigger|reliev\\w*|ease\\w*|improv\\w*)\\b" +
                "|\\b(?:anything|what).{0,18}\\bhelp(?:s|ed)?\\b",
            RegexOption.IGNORE_CASE
        ),
        "progression" to Regex(
            "\\b(?:getting|becoming).{0,10}\\b(?:worse|better|more|less)\\b" +
                "|\\b(?:constant|intermittent|continuous)\\b" +
                "|\\b(?:come|comes|coming)\\s+and\\s+go\\b" +
                "|\\b(?:on\\s+and\\s+off|all\\s+the\\s+time|how\\s+often)\\b" +
                "|\\bchanged?.{0,18}\\b(?:since|over\\s+time)\\b",
            RegexOption.IGNORE_CASE
        ),
        "associated_symptoms" to Regex(
            "\\bany\\s+(?:other|else|additional)\\s+(?:symptoms?|problems?|issues?|complaints?|discomforts?)\\b" +
                "|\\banything\\s+else\\b" +
                "|\\balong\\s+with\\b" +
                "|\\bat\\s+the\\s+same\\s+time\\b" +
                "|\\balso\\s+(?:notice\\w*|feel|felt|experience\\w*|have|had)\\b" +
                "|\\b(?:any|have\\s+you\\s+had|do\\s+you\\s+have).{0,12}" +
                "\\b(?:nausea|vomit\\w*|sweat\\w*|dizz\\w*|fever|breathless\\w*|shortness\\s+of\\s+breath|palpitations?)\\b",
            RegexOption.IGNORE_CASE
        ),
        "pmh" to Regex(
            "\\b(?:past|previous|medical|health).{0,12}\\b(?:history|conditions?|problems?|issues?)\\b" +
                "|\\b(?:have|has|had|were|was|ever|you).{0,20}\\bdiagnos\\w*\\b" +
                "|\\botherwise\\s+(?:fit|healthy|well)\\b" +
                "|\\b(?:any|ever\\s+had|previous).{0,15}\\b(?:operations?|surger\\w*|hospital\\s+stays?|hospitali[sz]\\w*)\\b",
            RegexOption.IGNORE_CASE
        ),
        "medications" to Regex(
            "\\b(?:take|taking|use|using|on|prescribed).{0,18}\\b(?:medications?|medicines?|tablets?|pills?|prescriptions?|inhalers?|supplements?)\\b" +
                "|\\b(?:medications?|medicines?|tablets?|pills?|prescriptions?|inhalers?|supplements?).{0,18}\\b(?:take|taking|use|using|regular|currently|daily)\\b" +
                "|\\btake\\s+anything\\s+(?:regular|regularly|daily)\\b" +
                "|\\bon\\s+anything\\s+(?:regular|regularly|at\\s+the\\s+moment|currently)\\b",
            RegexOption.IGNORE_CASE
        ),
        "allergies" to Regex(
            "\\ballerg\\w*\\b" +
                "|\\b(?:reactions?|react|sensitive|sensitivity|intoleran\\w*).{0,18}\\b(?:medicines?|medications?|drugs?|antibiotics?|penicillin)\\b" +
                "|\\b(?:medicines?|medications?|drugs?|antibiotics?|penicillin).{0,18}\\b(?:reactions?|react|sensitive|sensitivity|intoleran\\w*)\\b",
            RegexOption.IGNORE_CASE
        ),
        "smoking" to Regex(
            "\\b(?:smoke|smoking|smoker|cigarettes?|tobacco|vape|vaping)\\b", RegexOption.IGNORE_CASE
        ),
        "alcohol" to Regex(
            "\\b(?:alcohol|beer|wine|spirits?|units?\\s+(?:a|per)\\s+week)\\b" +
                "|\\b(?:drink|drinking).{0,12}\\b(?:alcohol|beer|wine|spirits?)\\b",
            RegexOption.IGNORE_CASE
        ),
        "substances" to Regex(
            "\\b(?:recreational|illicit|street).{0,10}\\bdrugs?\\b" +
                "|\\b(?:cannabis|marijuana|cocaine|heroin)\\b",
            RegexOption.IGNORE_CASE
        ),
        "living_work" to Regex(
            "\\bwho\\s+do\\s+you\\s+live\\s+with\\b" +
                "|\\bdo\\s+you\\s+live\\s+(?:alone|with)\\b" +
                "|\\b(?:what|which).{0,10}\\b(?:work|job|occupation|line\\s+of\\s+work)\\b" +
                "|\\bwhat\\s+do\\s+you\\s+do\\s+for\\s+(?:work|a\\s+living)\\b",
            RegexOption.IGNORE_CASE
        ),
        "family_hx" to Regex(
            "\\bfamily\\s+history\\b" +
                "|\\brun(?:s)?\\s+in\\s+(?:your|the)\\s+family\\b" +
                "|\\b(?:mother|father|parent|brother|sister|sibling|relative|family).{0,28}" +
                "\\b(?:disease|condition|illness|cancer|diabetes|heart|stroke|died|diagnos\\w*)\\b" +
                "|\\banyone\\s+in\\s+your\\s+family\\b",
            RegexOption.IGNORE_CASE
        ),
        "social_hx" to Regex(
            "\\b(?:smoke|smoking|alcohol|cigarettes?|tobacco|vape)\\b" +
                "|\\bwho\\s+do\\s+you\\s+live\\s+with\\b" +
                "|\\bwhat\\s+do\\s+you\\s+do\\s+for\\s+(?:work|a\\s+living)\\b",
            RegexOption.IGNORE_CASE
        ),
        "ideas" to Regex(
            "\\bwhat\\s+do\\s+you\\s+think\\b.{0,28}\\b(?:caus\\w*|going\\s+on|might\\s+be)\\b" +
                "|\\bany\\s+idea.{0,20}\\b(?:caus\\w*|might\\s+be|going\\s+on)\\b",
            RegexOption.IGNORE_CASE
        ),
        "concerns" to Regex(
            "\\b(?:anything|what).{0,18}\\b(?:worried|worry|concerned|concerns?|afraid|scared)\\b" +
                "|\\bwhat\\s+worries\\s+you\\b",
            RegexOption.IGNORE_CASE
        ),
        "expectations" to Regex(
            "\\bwhat\\s+(?:were|are)\\s+you\\s+hoping\\b" +
                "|\\bwhat\\s+would\\s+you\\s+like\\b" +
                "|\\bhow\\s+can\\s+(?:we|i)\\s+help\\s+today\\b",
            RegexOption.IGNORE_CASE
        ),
        "summary_closing" to Regex(
            "\\b(?:summari[sz]e|to\\s+sum\\s+up|recap|let\\s+me\\s+make\\s+sure|so\\s+far\\s+you(?:'ve|\\s+have)\\s+told\\s+me)\\b" +
                "|\\bbefore\\s+we\\s+finish\\b.{0,25}\\banything\\s+else\\b",
            RegexOption.IGNORE_CASE
        )
    )

    val FUZZY_TERMS: Map<String, List<String>> = mapOf(
        "radiation" to listOf("radiate", "radiating", "radiation"),
        "severity" to listOf("severity", "intensity"),
        "pmh" to listOf("diagnosed", "condition", "operation", "hospitalised", "hospitalized"),
        "medications" to listOf("medication", "medicine", "prescription", "supplement"),
        "allergies" to listOf("allergy", "allergies", "allergic", "sensitivity"),
        "smoking" to listOf("smoking", "cigarette", "tobacco"),
        "family_hx" to listOf("family", "relative")
    )

    /** Return whether a learner turn confidently covers one history domain. */
    fun studentDomainHit(key: String, text: String, keywords: List<String> = emptyList()): Boolean =
        studentDomainStrength(key, text, keywords) != MatchStrength.NONE

    /**
     * Android-only refinement of [studentDomainHit]: the same decision, but reporting *why* it
     * matched so the UI can separate "you clearly asked this" from "this might have been asked".
     *
     * [MatchStrength.STRONG] means a full intent phrase from [DOMAIN_PATTERNS] fired — the learner
     * demonstrably asked the question. [MatchStrength.WEAK] means only a bare keyword or a fuzzy
     * ASR-tolerant term matched, which is exactly where the live checklist used to over-claim: the
     * word "pressure" appearing somewhere is not proof that character was elicited. Callers render
     * a weak hit as "possibly asked — tap to confirm" instead of a solid tick.
     *
     * [studentDomainHit] delegates here, so the Python-ported boolean contract (and its golden
     * vectors) is unchanged: every branch that used to return true still returns a non-NONE tier.
     */
    fun studentDomainStrength(
        key: String,
        text: String,
        keywords: List<String> = emptyList(),
    ): MatchStrength {
        val value = normalizeTranscript(text)
        if (value.isEmpty()) return MatchStrength.NONE

        // Collision guards for common everyday uses of clinical anchor words.
        if (key == "alcohol" && Regex("\\b(?:coffee|tea|water|juice)\\b").containsMatchIn(value) &&
            !Regex("\\b(?:alcohol|beer|wine|spirits?)\\b").containsMatchIn(value)
        ) return MatchStrength.NONE
        if (key == "medications" && Regex("\\b(?:reactions?|allerg\\w*|sensitive|sensitivity)\\b").containsMatchIn(value) &&
            !Regex("\\b(?:take|taking|use|using|on|regular|current)\\b").containsMatchIn(value)
        ) return MatchStrength.NONE
        if (key == "character" && Regex("\\bpressure\\s+at\\s+work\\b").containsMatchIn(value) &&
            !Regex("\\b(?:pain|chest|symptom|discomfort)\\b").containsMatchIn(value)
        ) return MatchStrength.NONE
        if (key == "pmh" && Regex("\\b(?:explain|discuss)\\s+(?:the\\s+)?diagnosis\\b").containsMatchIn(value) &&
            !Regex("\\b(?:you|your|ever|history)\\b").containsMatchIn(value)
        ) return MatchStrength.NONE

        val pattern = DOMAIN_PATTERNS[key]
        if (pattern != null && pattern.containsMatchIn(value)) {
            // A negative learner confirmation still counts ("You don't smoke?"). A declarative
            // role-play statement about self/the patient does not; patient facts are handled
            // separately by patientDomainEvidence().
            if (looksLikeStudentQuestion(value) || !NEGATION_RE.containsMatchIn(value)) return MatchStrength.STRONG
        }
        if (keywords.any { it.isNotEmpty() && studentKeywordHit(value, normalizeTranscript(it)) }) return MatchStrength.WEAK
        val fuzzy = looksLikeStudentQuestion(value) &&
            FUZZY_TERMS.containsKey(key) &&
            nearTerm(value, FUZZY_TERMS.getValue(key))
        return if (fuzzy) MatchStrength.WEAK else MatchStrength.NONE
    }

    // A patient can provide strong evidence even when the learner's ASR transcript is corrupt.
    // These patterns are intentionally narrower than follow-up cues.
    val PATIENT_EVIDENCE_PATTERNS: Map<String, Regex> = mapOf(
        "onset_duration" to Regex(
            "\\b(?:started|began|came\\s+on|since|for\\s+(?:about\\s+)?(?:a|one|two|three|few|several|\\d+)\\s+" +
                "(?:hours?|days?|weeks?|months?|years?))\\b",
            RegexOption.IGNORE_CASE
        ),
        "location" to Regex(
            "\\b(?:in|on|around)\\s+my\\s+(?:chest|abdomen|stomach|head|back|arm|leg|side|neck|throat)\\b" +
                "|\\bright\\s+here\\b",
            RegexOption.IGNORE_CASE
        ),
        "character" to Regex(
            "\\b(?:sharp|dull|burning|stabbing|throbbing|crushing|squeezing|tightness|pressure)\\b",
            RegexOption.IGNORE_CASE
        ),
        "severity" to Regex(
            "\\b(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s*(?:out\\s+of|over)\\s*(?:10|ten)\\b",
            RegexOption.IGNORE_CASE
        ),
        "radiation" to DOMAIN_PATTERNS.getValue("radiation"),
        "aggravating" to Regex("\\b(?:worse|triggered|brought\\s+on)\\s+(?:when|with|after|by)\\b", RegexOption.IGNORE_CASE),
        "relieving" to Regex("\\b(?:better|eases?|helps?|relieved)\\s+(?:when|with|after|by)\\b|\\brest\\s+helps\\b", RegexOption.IGNORE_CASE),
        "progression" to DOMAIN_PATTERNS.getValue("progression"),
        "pmh" to Regex(
            "\\b(?:diagnosed\\s+with|i\\s+have|i've\\s+got|had\\s+(?:an?\\s+)?operation|" +
                "never\\s+had\\s+(?:an?\\s+)?operation|hospitali[sz]ed|diabetes|hypertension|asthma|copd)\\b",
            RegexOption.IGNORE_CASE
        ),
        "medications" to Regex(
            "\\b(?:i\\s+(?:take|use)|i'm\\s+on|i\\s+am\\s+on|prescribed|metformin|aspirin|insulin|statin|inhaler)\\b",
            RegexOption.IGNORE_CASE
        ),
        "allergies" to Regex(
            "\\b(?:no\\s+known\\s+(?:drug\\s+)?allergies|not\\s+allergic|allergic\\s+to|allergy\\s+to|" +
                "reaction\\s+to\\s+(?:penicillin|medicine|medication|antibiotic))\\b",
            RegexOption.IGNORE_CASE
        ),
        "smoking" to Regex(
            "\\b(?:i\\s+(?:do\\s+not|don't|never|used\\s+to)?\\s*smoke|smoker|cigarettes?\\s+(?:a|per)\\s+day|vape)\\b",
            RegexOption.IGNORE_CASE
        ),
        "alcohol" to Regex(
            "\\b(?:i\\s+(?:do\\s+not|don't|never)?\\s*drink\\s+(?:alcohol|beer|wine)|" +
                "beer|wine|units?\\s+(?:a|per)\\s+week)\\b",
            RegexOption.IGNORE_CASE
        ),
        "substances" to Regex("\\b(?:cannabis|marijuana|cocaine|heroin|recreational\\s+drugs?)\\b", RegexOption.IGNORE_CASE),
        "living_work" to Regex("\\bi\\s+(?:live|work)\\b|\\bmy\\s+(?:job|work|husband|wife|partner)\\b", RegexOption.IGNORE_CASE),
        "family_hx" to Regex(
            "\\b(?:my\\s+(?:mother|father|parent|brother|sister)|family).{0,30}" +
                "\\b(?:disease|condition|cancer|diabetes|heart|stroke|died|diagnos\\w*)\\b",
            RegexOption.IGNORE_CASE
        ),
        "ideas" to Regex("\\bi\\s+(?:think|thought)\\b|\\bi\\s+wonder(?:ed|ing)?\\b", RegexOption.IGNORE_CASE),
        "concerns" to Regex("\\b(?:worried|scared|afraid|concerned|anxious)\\b", RegexOption.IGNORE_CASE),
        "expectations" to Regex("\\bi\\s+(?:was\\s+)?hoping\\b|\\bi\\s+(?:want|need)\\b", RegexOption.IGNORE_CASE)
    )

    fun patientDomainEvidence(key: String, text: String): Boolean {
        val value = normalizeTranscript(text)
        val pattern = PATIENT_EVIDENCE_PATTERNS[key] ?: return false
        return value.isNotEmpty() && pattern.containsMatchIn(value)
    }
}
