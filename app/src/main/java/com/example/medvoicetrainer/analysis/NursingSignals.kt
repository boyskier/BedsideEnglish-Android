package com.example.medvoicetrainer.analysis

/**
 * Deterministic, transcript-only checks for a nursing session — no LLM involved.
 *
 * These are the behaviours a nurse is assessed on that can be detected reliably from the words
 * alone: introducing yourself, teach-back, leading with the reason for a call, a specific request,
 * read-back, safety language, over-apologising, unexplained jargon to a patient, first-person
 * answers in an interview. They sit beside the LLM's rubric scores in the feedback, never replace
 * them: a phrase match is evidence the behaviour happened, not proof it was done well. That is also
 * why each check carries a concrete tip, so a miss turns into the next thing to say.
 *
 * Which checks apply depends on the task family and on who the counterpart is — jargon only
 * matters when the listener is a patient or relative; read-back only when the listener is a
 * clinician. A check that does not apply is simply not reported.
 */
object NursingSignals {

    /** One check as shown in the feedback. [detail] explains a pass or a miss in plain words. */
    data class Check(
        val id: String,
        val label: String,
        val passed: Boolean,
        val detail: String,
        val tip: String,
    )

    /** One unexplained jargon term said to a patient or relative, with a plain-English swap. */
    data class JargonHit(val term: String, val plain: String, val count: Int)

    data class Result(
        val checks: List<Check>,
        val jargon: List<JargonHit>,
        /** Apology / hedge words and how often the learner used each. */
        val softeners: Map<String, Int>,
        val learnerWords: Int,
        val counterpartWords: Int,
    ) {
        /** Share of the spoken words that were the learner's, 0..1; null when nobody spoke. */
        val talkShare: Double?
            get() = (learnerWords + counterpartWords).takeIf { it > 0 }
                ?.let { learnerWords.toDouble() / it }
        val passedCount: Int get() = checks.count { it.passed }
    }

    /** Counterparts a nurse talks to in lay language. */
    val LAY_COUNTERPARTS = setOf("patient", "relative")

    /** Counterparts a nurse talks to as a colleague. */
    val CLINICIAN_COUNTERPARTS = setOf("physician", "nurse", "pharmacist")

    /** Transcript roles that are the learner's own speech (app and desktop conventions). */
    private val LEARNER_ROLES = setOf("user", "learner", "doctor", "nurse", "candidate", "you")

    fun isLearnerRole(role: String): Boolean = role.trim().lowercase() in LEARNER_ROLES

    /**
     * Terms a patient or relative is unlikely to understand, with the plain-English phrase a nurse
     * would use instead. Matched as whole words, case-insensitively. Deliberately conservative:
     * words most patients do know ("IV", "blood pressure", "infection") are not listed, and short
     * abbreviations that speech-to-text often produces by accident ("PE", "PO") are left out.
     */
    val JARGON: List<Pair<Regex, String>> = listOf(
        "NPO" to "nothing to eat or drink",
        "PRN" to "only when you need it",
        "void|voiding|voided" to "pass urine (pee)",
        "ambulate|ambulating|ambulation" to "walk / get up and walk",
        "mobili[sz]e|mobili[sz]ing|mobili[sz]ation" to "get up and move around",
        "hypertension|hypertensive" to "high blood pressure",
        "hypotension|hypotensive" to "low blood pressure",
        "hypoglyc(a)?emia|hypoglyc(a)?emic" to "low blood sugar",
        "hyperglyc(a)?emia|hyperglyc(a)?emic" to "high blood sugar",
        "subcutaneous(ly)?|sub-q|subcut" to "under the skin",
        "o?edema|o?edematous" to "swelling",
        "dyspn(o)?ea" to "shortness of breath",
        "analgesia|analgesics?" to "pain relief / painkillers",
        "anticoagulants?|anticoagulation" to "blood thinner",
        "antiemetics?" to "anti-sickness medicine",
        "b\\.?i\\.?d\\.?" to "twice a day",
        "t\\.?i\\.?d\\.?" to "three times a day",
        "q\\.?i\\.?d\\.?" to "four times a day",
        "vitals|vital signs" to "your checks (blood pressure, pulse, temperature)",
        "obs" to "your checks (blood pressure, pulse, temperature)",
        "sats|oxygen saturation" to "the oxygen level in your blood",
        "prognosis" to "what we expect to happen",
        "benign" to "not cancer",
        "malignant|malignancy" to "cancer",
        "contraindicated|contraindication" to "not safe to use with",
        "adverse (effects?|reactions?|events?)" to "side effects",
        "titrate|titrating|titration" to "adjust the dose step by step",
        "sputum" to "phlegm",
        "defecate|defecation" to "open your bowels (poo)",
        "micturition" to "passing urine",
        "renal" to "kidney",
        "hepatic" to "liver",
        "cardiac" to "heart",
        "myocardial infarction" to "heart attack",
        "cerebrovascular accident|CVA" to "stroke",
        "pyrexia|pyrexial|febrile" to "fever / a high temperature",
        "tachycardi(a|c)" to "a fast heart rate",
        "bradycardi(a|c)" to "a slow heart rate",
        "comorbidit(y|ies)" to "other health conditions",
        "DVT|deep vein thrombosis" to "a blood clot in the leg",
        "UTI" to "a bladder (urine) infection",
        "NSAIDs?" to "anti-inflammatory painkillers such as ibuprofen",
        "postprandial" to "after meals",
        "nil by mouth" to "nothing to eat or drink",
        "idiopathic" to "we don't know the cause",
        "nosocomial" to "caught in hospital",
        "emesis" to "vomiting (being sick)",
        "pruritus" to "itching",
        "lesion" to "sore / patch / spot",
        "aspirat(e|ion)" to "breathe food or liquid into your lungs",
        "hemorrhage|haemorrhage" to "bleeding",
        "sutures" to "stitches",
        "incontinen(ce|t)" to "leaking urine (or bowel)",
        "dysphagia" to "difficulty swallowing",
    ).map { (pattern, plain) -> Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE) to plain }

    /** Words that follow a term when the speaker is already explaining it. */
    private val EXPLAINED_AFTER = Regex(
        "^\\W{0,3}(,\\s*)?(which means|that means|meaning|in other words|that is|i\\.e\\.|basically|or in plain|— |- )",
        RegexOption.IGNORE_CASE,
    )

    private val EXPLAINED_BEFORE = Regex(
        "(we call (it|this|that)|called|the medical (word|term) (for this )?is|doctors call (it|this))\\W{0,3}$",
        RegexOption.IGNORE_CASE,
    )

    /** Apology and hedge words a nurse overuses when speaking up in a second language. */
    private val SOFTENERS = listOf(
        "sorry", "maybe", "just", "i think", "a little", "a bit", "kind of", "sort of",
        "perhaps", "if it's okay", "if possible", "i guess",
    )

    fun analyze(
        transcript: List<Pair<String, String>>,
        taskFamily: String,
        counterpart: String,
        urgency: String = "",
    ): Result {
        val learnerTurns = transcript.filter { isLearnerRole(it.first) }.map { it.second.trim() }.filter(String::isNotEmpty)
        val counterpartTurns = transcript.filterNot { isLearnerRole(it.first) }.map { it.second.trim() }.filter(String::isNotEmpty)
        val learnerText = learnerTurns.joinToString(" \n ")
        val lower = learnerText.lowercase()
        val opening = learnerTurns.take(2).joinToString(" ").lowercase()
        val learnerWords = wordCount(learnerText)
        val counterpartWords = counterpartTurns.sumOf(::wordCount)

        val family = taskFamily.trim()
        val who = counterpart.trim().lowercase().ifEmpty { inferCounterpart(family) }
        val lay = who in LAY_COUNTERPARTS
        val clinician = who in CLINICIAN_COUNTERPARTS
        val interview = who == "interviewer" || family == NursingTrack.FAMILY_INTERVIEW
        val oet = family == NursingTrack.FAMILY_OET

        val jargon = if (lay && !interview) findUnexplainedJargon(learnerTurns) else emptyList()
        val softeners = SOFTENERS.associateWith { countPhrase(lower, it) }.filterValues { it > 0 }

        val checks = mutableListOf<Check>()
        if (learnerTurns.isEmpty()) {
            return Result(emptyList(), emptyList(), emptyMap(), 0, counterpartWords)
        }

        if (!interview) {
            checks += Check(
                id = "introduced_self",
                label = "Introduced yourself and your role",
                passed = INTRODUCTION.containsMatchIn(opening),
                detail = if (INTRODUCTION.containsMatchIn(opening)) "You said who you are at the start."
                else "Your first lines did not say who you are.",
                tip = if (clinician) "\"Hi, this is Mina, the nurse on 5 West, calling about Mr. Lee in room 12.\""
                else "\"Hello, my name is Mina, I'm one of the nurses looking after you today.\"",
            )
        }

        if (lay && !interview) {
            val teachBack = TEACH_BACK.containsMatchIn(lower)
            val closedCheck = CLOSED_UNDERSTANDING.containsMatchIn(lower)
            checks += Check(
                id = "checked_understanding",
                label = "Checked understanding with teach-back",
                passed = teachBack,
                detail = when {
                    teachBack -> "You asked them to explain or show it back."
                    closedCheck -> "You only asked a yes/no check (\"Do you understand?\") — people say yes even when they don't."
                    else -> "You never checked what they understood."
                },
                tip = "\"Just so I know I explained it clearly, can you tell me in your own words what you'll do at home?\"",
            )
            val empathy = EMPATHY.containsMatchIn(lower)
            checks += Check(
                id = "acknowledged_feelings",
                label = "Acknowledged their feelings",
                passed = empathy,
                detail = if (empathy) "You named or validated their worry." else "No explicit acknowledgement of how they feel.",
                tip = "\"It sounds like you're really worried about this — that's completely understandable.\"",
            )
            val invited = INVITE_QUESTIONS.containsMatchIn(lower)
            checks += Check(
                id = "invited_questions",
                label = "Invited their questions",
                passed = invited,
                detail = if (invited) "You gave them room to ask." else "You did not invite questions.",
                tip = "\"What questions do you have for me?\" (open) works better than \"Any questions?\"",
            )
            checks += Check(
                id = "plain_language",
                label = "Used plain language (no unexplained jargon)",
                passed = jargon.isEmpty(),
                detail = if (jargon.isEmpty()) "No unexplained medical jargon found."
                else "Unexplained: " + jargon.joinToString(", ") { it.term },
                tip = jargon.firstOrNull()?.let { "Say \"${it.plain}\" instead of \"${it.term}\"." }
                    ?: "Keep explaining terms the moment you use them: \"…your blood pressure is low, which means…\"",
            )
        }

        if (oet) {
            val open = OPEN_PERSPECTIVE.containsMatchIn(lower)
            checks += Check(
                id = "explored_perspective",
                label = "Explored their perspective with an open question",
                passed = open,
                detail = if (open) "You asked what they know, think, or worry about." else "No open question about their own view or worry.",
                tip = "\"What have you been told so far?\" / \"What worries you most about this?\"",
            )
            val signposted = SIGNPOSTING.containsMatchIn(lower)
            checks += Check(
                id = "signposted",
                label = "Signposted the structure",
                passed = signposted,
                detail = if (signposted) "You told them where the conversation was going." else "Topic changes came without a signpost.",
                tip = "\"First I'd like to find out…, then I'll explain…\" / \"Before we finish, let me summarise.\"",
            )
            val share = (learnerWords + counterpartWords).takeIf { it > 0 }?.let { learnerWords.toDouble() / it }
            if (share != null) {
                val balanced = share in 0.40..0.75
                checks += Check(
                    id = "balanced_talk",
                    label = "Balanced the conversation",
                    passed = balanced,
                    detail = "You spoke ${(share * 100).toInt()}% of the words. " + when {
                        share > 0.75 -> "That is a lecture — OET rewards letting the patient talk."
                        share < 0.40 -> "The patient did most of the talking — lead the conversation more."
                        else -> "A good balance."
                    },
                    tip = "Aim for roughly half to two thirds of the talking; pause after each chunk of information.",
                )
            }
        }

        if (clinician && family != NursingTrack.FAMILY_INTERVIEW) {
            val purpose = PURPOSE.containsMatchIn(opening)
            checks += Check(
                id = "purpose_up_front",
                label = "Stated why you're calling up front",
                passed = purpose,
                detail = if (purpose) "The reason for the call came in your first lines." else "The reason for the call was not in your first two turns.",
                tip = "\"I'm calling because I'm worried about his blood pressure — it's dropped over the last two hours.\"",
            )
            val request = REQUEST.containsMatchIn(lower)
            checks += Check(
                id = "specific_request",
                label = "Made a specific request",
                passed = request,
                detail = if (request) "You asked for something concrete." else "You never said exactly what you need them to do.",
                tip = "\"I need you to come and see him in the next 15 minutes.\"",
            )
            val readBack = READ_BACK.containsMatchIn(lower)
            checks += Check(
                id = "closed_loop",
                label = "Closed the loop (read-back / confirm)",
                passed = readBack,
                detail = if (readBack) "You confirmed what was agreed." else "You did not read back or confirm the plan.",
                tip = "\"Let me read that back: … Is that correct?\"",
            )
            if (family == NursingTrack.FAMILY_HANDOVER && urgency.trim().lowercase() == "urgent") {
                val urgent = URGENCY.containsMatchIn(lower)
                checks += Check(
                    id = "urgency_explicit",
                    label = "Made the urgency explicit",
                    passed = urgent,
                    detail = if (urgent) "You said how soon you need them." else "You left the urgency for them to guess.",
                    tip = "\"I need you now\" / \"within the next 15 minutes\" — not \"when you get a chance\".",
                )
            }
        }

        if (family == NursingTrack.FAMILY_SPEAK_UP) {
            val cus = SAFETY_LANGUAGE.containsMatchIn(lower)
            checks += Check(
                id = "safety_language",
                label = "Used clear safety language (CUS)",
                passed = cus,
                detail = if (cus) "You named the concern as a safety issue." else "You never named it as a concern or safety issue.",
                tip = "\"I'm concerned… I'm uncomfortable… this is a safety issue.\"",
            )
            val total = softeners.values.sum()
            val perHundred = if (learnerWords > 0) total * 100.0 / learnerWords else 0.0
            val sorry = softeners["sorry"] ?: 0
            val calm = perHundred <= 4.0 && sorry < 3
            checks += Check(
                id = "softeners",
                label = "Kept apologies and hedges in check",
                passed = calm,
                detail = if (softeners.isEmpty()) "No apologising or hedging."
                else softeners.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "\"${it.key}\" ×${it.value}" },
                tip = "Replace \"Sorry, maybe it's just me, but…\" with \"I'm concerned about this order.\"",
            )
        }

        if (interview) {
            val iCount = countWord(lower, "i") + countWord(lower, "i'm") + countWord(lower, "i've") + countWord(lower, "my")
            val weCount = countWord(lower, "we") + countWord(lower, "we're") + countWord(lower, "we've") + countWord(lower, "our")
            val firstPerson = iCount >= weCount
            checks += Check(
                id = "first_person",
                label = "Talked about your own actions",
                passed = firstPerson,
                detail = "\"I\"-words ×$iCount vs \"we\"-words ×$weCount.",
                tip = "Interviewers score what YOU did: \"I noticed…, I escalated…, I taught…\"",
            )
            val result = STAR_RESULT.containsMatchIn(lower)
            checks += Check(
                id = "star_result",
                label = "Finished your stories with a result",
                passed = result,
                detail = if (result) "You closed with an outcome or lesson." else "Your examples stopped before the outcome.",
                tip = "\"As a result, … and what I learned was …\"",
            )
            val asked = ASKED_QUESTION.containsMatchIn(learnerTurns.takeLast(3).joinToString(" ").lowercase())
            checks += Check(
                id = "asked_question",
                label = "Asked the interviewer a question",
                passed = asked,
                detail = if (asked) "You asked about the role or team." else "You did not ask anything back.",
                tip = "\"What does orientation look like for internationally educated nurses on this unit?\"",
            )
            val substantive = learnerTurns.filter { wordCount(it) >= 8 }
            val average = if (substantive.isEmpty()) 0 else substantive.sumOf(::wordCount) / substantive.size
            val goodLength = average in 40..220
            checks += Check(
                id = "answer_length",
                label = "Gave full-length answers",
                passed = goodLength,
                detail = "Your answers averaged $average words. " + when {
                    average < 40 -> "Too short to show a real example."
                    average > 220 -> "Long enough to lose the interviewer — tighten to 1–2 minutes."
                    else -> "About right (roughly 1–2 minutes each)."
                },
                tip = "A behavioural answer: ~1 sentence Situation/Task, most of it Action, 1–2 sentences Result.",
            )
        }

        return Result(checks, jargon, softeners, learnerWords, counterpartWords)
    }

    /** The counterpart a case omitted, inferred from its family (older cases predate the field). */
    fun inferCounterpart(taskFamily: String): String = when (taskFamily.trim()) {
        NursingTrack.FAMILY_HANDOVER, NursingTrack.FAMILY_SPEAK_UP -> "physician"
        NursingTrack.FAMILY_INTERVIEW -> "interviewer"
        else -> "patient"
    }

    fun findUnexplainedJargon(learnerTurns: List<String>): List<JargonHit> {
        val counts = linkedMapOf<String, Pair<String, Int>>()
        learnerTurns.forEach { turn ->
            JARGON.forEach { (regex, plainPhrase) ->
                regex.findAll(turn).forEach { match ->
                    val before = turn.substring(0, match.range.first).takeLast(40)
                    val after = turn.substring(match.range.last + 1).take(30)
                    if (EXPLAINED_AFTER.containsMatchIn(after) || EXPLAINED_BEFORE.containsMatchIn(before)) return@forEach
                    val term = match.value.lowercase()
                    val (plain, n) = counts[term] ?: (plainPhrase to 0)
                    counts[term] = plain to n + 1
                }
            }
        }
        return counts.map { (term, v) -> JargonHit(term, v.first, v.second) }
    }

    fun wordCount(text: String): Int = WORD.findAll(text).count()

    private fun countPhrase(lower: String, phrase: String): Int =
        Regex("\\b" + Regex.escape(phrase) + "\\b").findAll(lower).count()

    private fun countWord(lower: String, word: String): Int =
        Regex("(?<![\\w'])" + Regex.escape(word) + "(?![\\w'])").findAll(lower).count()

    private val WORD = Regex("[A-Za-z0-9']+")

    private val INTRODUCTION = Regex(
        "\\b(my name is|my name's|calling from|" +
            "(i'?m|i am|this is) [a-z]+,? (the|a|your|one of the) ([a-z]+ )?(nurses?|rn)\\b|" +
            "(i'?m|i am) (the|a|your|one of the) ([a-z]+ )?(nurses?|rn)\\b|" +
            "(i'?m|i am|this is) (?!a |an |the |it |that |just |really |very |not |so )[a-z]+ (from|on|calling)\\b)",
    )
    private val TEACH_BACK = Regex(
        "(in your own words|tell me (back )?(what|how) you|show me how|can you show me|walk me through|" +
            "explain (it|that|this) back|repeat (it|that|this) back|tell me back|what will you do (if|when)|" +
            "what would you do (if|when)|just to (make sure|check) (i|that i) (explained|was clear)|" +
            "can you tell me what you('ll| will) (do|watch for|look out for))",
    )
    private val CLOSED_UNDERSTANDING = Regex("(do you understand|does that make sense|is that (clear|ok|okay)|got it\\?)")
    private val EMPATHY = Regex(
        "(i (can )?(understand|see|hear) (that |how |why |you|this|it)|that must (be|feel)|" +
            "(it'?s|that'?s) (completely |totally |very |perfectly )?(normal|understandable|natural)|" +
            "i'?m (so |really |very )?sorry (to hear|that you|you'?re|for)|sounds (really |very |quite )?" +
            "(hard|difficult|stressful|frightening|scary|worrying|tough|overwhelming|frustrating)|" +
            "you (seem|sound) (worried|upset|anxious|frustrated|scared)|i can imagine|" +
            "(it'?s|that'?s) a lot to (take in|deal with|handle))",
    )
    private val INVITE_QUESTIONS = Regex(
        "(any questions|what questions|anything (else )?you('d| would) like to (ask|know)|anything else i can|" +
            "is there anything (else )?(you|that)|questions (for me|about)|feel free to ask|do you have any (other )?(questions|concerns))",
    )
    private val OPEN_PERSPECTIVE = Regex(
        "(what do you (know|understand|think)|what have you (been told|heard|read)|what (worries|concerns|scares|bothers) you|" +
            "how (are|do) you feel(ing)? about|tell me (more )?about|what are you (most )?(worried|concerned)|" +
            "what matters (most )?to you|what would you like|how has (this|it) been|what'?s your (biggest )?(worry|concern))",
    )
    private val SIGNPOSTING = Regex(
        "(first(ly)?,? (i'?d|i would|let me|let'?s|we)|now (i'?d|i would|let me|let'?s)|next,? (i'?d|i would|let me|let'?s|we)|" +
            "before we (finish|go|end)|to (sum|summari[sz]e)|let me (just )?summari[sz]e|so,? to recap|" +
            "i'?d like to (talk|explain|go over|ask) (about|you)|moving on|let'?s (talk|move) (about|on))",
    )
    private val PURPOSE = Regex(
        "(calling (about|because|regarding|to)|i'?m (calling|paging|ringing)|the reason (i'?m|for my) (calling|call)|" +
            "i'?m (worried|concerned)|my concern is|i have a concern|i need (you|to)|i'?d like to (report|escalate|discuss)|" +
            "(he|she|they)'?s? (deteriorating|getting worse)|i'?m not comfortable|urgent)",
    )
    private val REQUEST = Regex(
        "(can you (please )?(come|see|review|assess|order|check|give|change|hold|stop|clarify|confirm|increase|decrease|call|look)|" +
            "could you (please )?(come|see|review|assess|order|check|give|change|hold|stop|clarify|confirm|increase|decrease|call|look)|" +
            "i need you to|i'?d like you to|would you (be able to |please )?(come|see|review|assess|order|check|give|change|hold|stop|clarify|confirm)|" +
            "i'?m (asking|requesting)|i('d| would) like (an?|to request)|please (come|review|see|reassess|hold)|" +
            "i need (an?|the) (order|review|reassignment|change)|can we (hold|stop|change|reassign|get))",
    )
    private val READ_BACK = Regex(
        "(read (it |that |this )?back|let me repeat|let me confirm|to confirm|just to confirm|confirming|i'?ll repeat|" +
            "so that'?s|so you want|so,? to be clear|you'?d like me to|you want me to|is that correct|is that right)",
    )
    private val URGENCY = Regex(
        "(right now|now\\b|right away|immediately|urgent(ly)?|as soon as possible|asap|stat\\b|within (the next )?\\d+|" +
            "within (the next )?(ten|fifteen|five|twenty|thirty) minutes|straight away|can'?t wait)",
    )
    private val SAFETY_LANGUAGE = Regex(
        "(i'?m (really |very )?concerned|i am (really |very )?concerned|i'?m (really |very )?uncomfortable|" +
            "i am (really |very )?uncomfortable|safety (issue|concern|risk)|not safe|unsafe|i (don'?t|do not) feel (comfortable|safe)|" +
            "i need (you )?to stop|we need to stop|i can'?t give (this|that|it))",
    )
    private val STAR_RESULT = Regex(
        "(as a result|the (result|outcome) was|in the end|resulted in|which led to|that led to|i learned|" +
            "i learnt|what i learned|the patient (was|recovered|went)|afterwards|since then)",
    )
    private val ASKED_QUESTION = Regex(
        "(can i ask|could i ask|i('d| would) like to ask|may i ask|what (does|do|is|are|would|kind)|how (does|do|is|are|many|long)|" +
            "do you (have|offer)|is there|are there|\\?)",
    )
}
