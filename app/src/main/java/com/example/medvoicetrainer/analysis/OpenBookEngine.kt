package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/**
 * "Open Book" — the deliberate answer-sheet scaffold for a learner who can hold an English
 * conversation but cannot yet name the disease behind the symptoms.
 *
 * A preclinical student who hears "substernal chest pain radiating to the left arm" has no idea
 * what to ask next, so an open microphone in an encounter is not a speaking exercise for them — it
 * is a wall. Open Book removes the *clinical* load so the *language* load is the only one left:
 * it names the diagnosis and lists what that diagnosis requires you to ask.
 *
 * The reveal is staged rather than dumped, because a whole answer sheet on screen turns the
 * encounter into reading practice:
 *  - [OpenBookLevel.SHORTLIST]  — a few candidate diagnoses, the real one among them
 *  - [OpenBookLevel.DIAGNOSIS]  — the answer, plus a one-line explanation
 *  - [OpenBookLevel.CHECKLIST]  — what this diagnosis obliges you to ask about
 *  - [OpenBookLevel.SCRIPT]     — the English sentence for each of those, ready to say aloud
 *
 * Each step is a separate deliberate tap, and the highest one reached is reported to the
 * post-session analysis via `practice_aids` (which already instructs the evaluator not to penalize
 * scaffolded sessions — see [AnalysisPromptBuilder]).
 *
 * The four levels above cover the *first* half of a consultation — working out what is wrong and
 * asking about it. [OpenBookClosing] covers the second half, on its own independent ladder; see
 * that class for why the two are not one counter.
 *
 * Pure logic: no Android, no network, no LLM. Everything here is derived from the case JSON, so a
 * card costs nothing and works offline.
 */
enum class OpenBookLevel(val step: Int) {
    HIDDEN(0),
    SHORTLIST(1),
    DIAGNOSIS(2),
    CHECKLIST(3),
    SCRIPT(4);

    companion object {
        fun of(step: Int): OpenBookLevel = entries.lastOrNull { it.step <= step } ?: HIDDEN
    }
}

/**
 * How far the *closing* half of the answer sheet is open.
 *
 * Deliberately a second, independent ladder rather than levels 5 and 6 of [OpenBookLevel]. A
 * learner who worked the whole history out unaided and then froze on "so what do I do now, doctor?"
 * would otherwise have to tap through the diagnosis, the checklist and the model questions — help
 * they neither wanted nor received — to reach the one thing they were stuck on, and the session
 * would then report itself to the evaluator as maximally scaffolded. Two counters describe two
 * unrelated kinds of not-knowing, and each is reported separately.
 *
 *  - [FRAME]  — the four moves a closing has to make, and what each one has to achieve
 *  - [SCRIPT] — the English sentence for each move, ready to say aloud
 */
enum class ClosingLevel(val step: Int) {
    HIDDEN(0),
    FRAME(1),
    SCRIPT(2);

    companion object {
        fun of(step: Int): ClosingLevel = entries.lastOrNull { it.step <= step } ?: HIDDEN
    }
}

/**
 * The four moves every consultation closing makes, in the order they are made.
 *
 * The sequence is fixed on purpose. Across all 1000+ cases in the library the *content* of a closing
 * changes completely while the *shape* does not, which makes the shape the part worth teaching: a
 * learner who owns these four moves can close any encounter, including one whose medicine they only
 * half understand. It is also what the encounter is graded on — `data/eval/history_taking.json`
 * scores "differential verbalized to patient", "management plan communicated" and "used Teach-back
 * method", none of which the diagnosis ladder above ever helped with.
 */
enum class ClosingMove(val key: String) {
    /** Name the problem, then say what it means in words the patient actually uses. */
    EXPLAIN("explain"),
    /** One concrete next step, and what it will tell you. */
    PLAN("plan"),
    /** What would make this urgent, and exactly what to do about it. */
    SAFETY_NET("safety_net"),
    /** Hand the explaining back to the patient to check it landed. */
    TEACH_BACK("teach_back");

    companion object {
        fun of(key: String): ClosingMove? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * One line of the closing script.
 *
 * [say] is what the learner says. [point] is a short English coaching note on what this line has
 * to achieve, so the [ClosingLevel.FRAME] step is usable on its own — the learner tries their own
 * wording first and only then reveals the model sentence.
 *
 * [chartNote] is the case's own clinical text for this move, and it is shown *as a contrast, never
 * as a script*. Case plans are written in chart register ("Aggressive IV hydration with Normal
 * Saline, check CK and urine pH"); repeating that at a patient is the single most common register
 * mistake this app's learners make, and the gap between the two columns is the lesson. The UI must
 * always label it as something not to say aloud.
 */
data class ClosingLine(
    val move: ClosingMove,
    val say: String,
    val point: String? = null,
    val chartNote: String? = null,
) {
    /** True when [say] still has a blank for the learner to fill rather than a complete sentence. */
    val hasBlank: Boolean get() = BLANK in say

    companion object {
        /** The blank a frame line leaves for the learner. */
        const val BLANK = "___"
    }
}

/** The closing half of the answer sheet: an ordered script, one or more lines per move. */
data class OpenBookClosing(
    val lines: List<ClosingLine>,
    /** True when a `teaching.closing` block supplied the content rather than it being derived. */
    val authored: Boolean,
) {
    /** Lines for one move, in author order. */
    fun linesFor(move: ClosingMove): List<ClosingLine> = lines.filter { it.move == move }

    /** The moves this closing actually has content for, in [ClosingMove] order. */
    val moves: List<ClosingMove> get() = ClosingMove.entries.filter { move -> lines.any { it.move == move } }
}

/**
 * How much help a finished session actually had, for the feedback report's badge.
 *
 * Three numbers rather than one because they mean three different things, and a learner reading
 * their own history months later deserves to see which: the diagnosis was handed over, the closing
 * script was handed over, or the whole case was briefed before the door opened.
 */
data class OpenBookAidsUsed(
    val level: Int = 0,
    val closingLevel: Int = 0,
    val briefed: Boolean = false,
) {
    val any: Boolean get() = level > 0 || closingLevel > 0 || briefed
}

/** What holds the microphone muted while the answer sheet is open. */
// Declaration order is the order the settings screen offers them, so the default leads.
enum class OpenBookMicPolicy(val key: String) {
    /**
     * Muted only while what is on screen has to be *read and thought about* — the candidate list
     * and the diagnosis. The moment the sheet turns into something to say out loud (the checklist,
     * the model questions, the closing script) the microphone comes back, because the learner is
     * meant to be reading those aloud to the patient, not memorising them and toggling the mic.
     */
    READING("reading"),

    /** Muted for as long as the sheet is open. Exam-style: read, close, then speak. */
    ALWAYS("always"),

    /** Never touches the microphone. */
    NEVER("never");

    companion object {
        val DEFAULT = READING

        fun of(key: String?): OpenBookMicPolicy =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * One thing this diagnosis obliges the learner to ask about.
 *
 * Deliberately the same `{objective, say}` core shape as a case's `objective_cues`, so authored cue
 * lists feed Open Book without conversion and [GuidedCueEngine] keeps working unchanged.
 */
data class OpenBookAsk(
    /** What to find out, in clinical terms ("Exertional trigger"). Also the coverage-map key. */
    val objective: String,
    /** The English sentence to say aloud. Null when the case only authored the objective. */
    val say: String?,
    /**
     * History domains that satisfy this row, from `history_domains.json`. Generic rows ("Onset and
     * duration") map cleanly onto one; disease-specific rows ("Cold intolerance") map onto none and
     * lean on [keywords] instead.
     */
    val domains: List<String> = emptyList(),
    /**
     * Words that count as having asked this, when no domain covers it. Without them a row like
     * "Exertional trigger" would only tick if the learner happened to say the word "exertional",
     * which is exactly the word a beginner won't use.
     */
    val keywords: List<String> = emptyList(),
)

/**
 * Everything Open Book can show for one case. Built once per session from the case JSON.
 */
data class OpenBookCard(
    val caseId: String,
    /** The answer, in English. A card is never built without one. */
    val diagnosis: String,
    /** One plain-English sentence on what the disease actually is. */
    val oneLiner: String?,
    /** Candidates for level 1, the real diagnosis among them in a stable non-obvious order. */
    val shortlist: List<String>,
    val mustAsk: List<OpenBookAsk>,
    val redFlags: List<String>,
    /** The closing script, on its own ladder. Null only when the case can supply no diagnosis. */
    val closing: OpenBookClosing?,
    /** True when a `teaching` block supplied the content, rather than it being derived. */
    val authored: Boolean,
) {
    /** True when the shortlist offers a real choice; a one-item shortlist would just be the answer. */
    val hasShortlist: Boolean get() = shortlist.size >= 2

    /** True when the closing ladder has anything to open. */
    val hasClosing: Boolean get() = closing != null && closing.lines.isNotEmpty()

    /** The deepest level this particular case can actually serve. */
    val maxLevel: OpenBookLevel = when {
        mustAsk.any { !it.say.isNullOrBlank() } -> OpenBookLevel.SCRIPT
        mustAsk.isNotEmpty() -> OpenBookLevel.CHECKLIST
        else -> OpenBookLevel.DIAGNOSIS
    }

    /** Where the first tap should land: level 1 when there is a real shortlist, otherwise the answer. */
    val firstLevel: OpenBookLevel =
        if (hasShortlist) OpenBookLevel.SHORTLIST else OpenBookLevel.DIAGNOSIS

    /** The next level up from [current], or null when this case has nothing further to give. */
    fun nextLevel(current: OpenBookLevel): OpenBookLevel? {
        if (current == OpenBookLevel.HIDDEN) return firstLevel
        return OpenBookLevel.entries
            .firstOrNull { it.step > current.step && it.step <= maxLevel.step }
    }

    /** The next step up the closing ladder, or null when it is already fully open. */
    fun nextClosingLevel(current: ClosingLevel): ClosingLevel? {
        if (!hasClosing) return null
        return ClosingLevel.entries.firstOrNull { it.step > current.step }
    }
}

object OpenBookEngine {

    /**
     * Lead-ins the generated case library wraps its diagnoses in. Stripped so the card can show
     * "Primary Hypothyroidism" rather than "Clinical presentation highly suggestive of Primary
     * Hypothyroidism." — the sentence form is written for an examiner, not for a stuck student.
     */
    private val ASSESSMENT_LEAD_INS = listOf(
        Regex("^clinical\\s+presentation\\s+(?:is\\s+)?highly\\s+suggestive\\s+of\\s+", RegexOption.IGNORE_CASE),
        Regex("^clinical\\s+presentation\\s+(?:is\\s+)?(?:suggestive|consistent)\\s+(?:of|with)\\s+", RegexOption.IGNORE_CASE),
        Regex("^condition\\s+highly\\s+suspicious\\s+for\\s+", RegexOption.IGNORE_CASE),
        Regex("^(?:presentation|findings|picture)\\s+(?:is\\s+|are\\s+)?(?:most\\s+)?consistent\\s+with\\s+", RegexOption.IGNORE_CASE),
        Regex("^(?:highly\\s+)?suspicious\\s+for\\s+", RegexOption.IGNORE_CASE),
        Regex("^(?:most\\s+)?likely\\s+diagnosis\\s*:\\s*", RegexOption.IGNORE_CASE),
        Regex("^(?:impression|assessment)\\s*:\\s*", RegexOption.IGNORE_CASE),
    )

    /**
     * The 44 library cases whose assessment is a placeholder ("Condition within cardiology scope.")
     * name no disease at all, so they get no card rather than a card that says nothing.
     */
    private val PLACEHOLDER_ASSESSMENT =
        Regex("^condition\\s+within\\s+.*\\s+scope\\.?$", RegexOption.IGNORE_CASE)

    /** Hand-written cases append their differential as "… DDx: STEMI, NSTEMI, aortic dissection". */
    private val DDX_MARKER = Regex("\\bDD?x\\b\\s*:\\s*", RegexOption.IGNORE_CASE)

    /**
     * A `reference_soap.plan` lead-in naming the case back at itself ("Plan for Carpal Tunnel
     * Syndrome:", "Evidence-based diagnostic and therapeutic protocol for Osteoarthritis…:").
     * Dropped so the chart note starts at the first real instruction.
     */
    private val PLAN_LEAD_IN =
        Regex("^[^:.]{0,120}?\\b(?:plan|protocol|management|approach|regimen)\\b[^:]{0,120}?:\\s*", RegexOption.IGNORE_CASE)

    /**
     * The same restatement written as its own sentence rather than a colon lead-in ("Evidence-based
     * diagnostic and therapeutic protocol for Facial Nerve Paralysis Reanimation."). Leading
     * sentences matching this are dropped until a real instruction is reached.
     */
    private val PLAN_HEADER_SENTENCE = Regex(
        "^(?:evidence-based\\s+)?[^.:]{0,120}?\\b(?:plan|protocol|management|approach|regimen)\\b" +
            "[^.:]{0,40}?\\bfor\\b[^.:]{0,160}$",
        RegexOption.IGNORE_CASE,
    )

    /** A numbered-step marker left dangling when the first sentence is cut before "1. Do the thing". */
    private val TRAILING_ENUMERATOR = Regex("[\\s:.-]*\\b\\d{1,2}$")

    /** Splits on a period that genuinely ends a sentence — not "2.0 g/dL", "q6h." or "S. aureus". */
    private val SENTENCE_END = Regex("\\.(?=\\s+[A-Z(0-9]|\\s*$)")

    /**
     * Generated-library boilerplate that carries no case-specific instruction. Stripped before the
     * chart note is cut to its first sentence, so a plan whose only real content sits behind the
     * boilerplate is not reduced to "Consult specialist if symptoms worsen."
     */
    private val PLAN_BOILERPLATE = listOf(
        Regex("\\bConsult specialist if symptoms worsen\\.?", RegexOption.IGNORE_CASE),
        Regex("\\bRefer to specialty team and provide comprehensive patient counseling\\.?", RegexOption.IGNORE_CASE),
        Regex("\\bCounsel patient\\.?", RegexOption.IGNORE_CASE),
    )

    /**
     * Part of the library was generated by a model that stuttered "right now" into almost every
     * clause. Left in, a chart note reads as broken English to a learner who is being asked to
     * study the register — so the repeats collapse to a single leading occurrence at most.
     */
    private val PLAN_FILLER = Regex("\\s+right now\\b", RegexOption.IGNORE_CASE)

    /** Longest chart note worth showing; past this it stops being a glance and becomes reading. */
    private const val CHART_NOTE_MAX = 180

    /**
     * Trailing qualifiers a case appends to a red flag to explain it to the *reader*
     * ("Muscle weakness or palpitations — hyperkalemia risk"). The reason belongs in the learner's
     * head, not in the sentence they say to the patient.
     */
    private val RED_FLAG_RATIONALE = Regex("\\s*[—–-]{1,2}\\s*[^—–]*$")

    /**
     * Hedges an examiner writes and a doctor does not say ("ACS until proven otherwise"). Removed
     * only from the spoken sentence; the diagnosis shown at level 2 keeps the case's own wording.
     */
    private val EXAMINER_HEDGE = Regex("\\s*,?\\s*until proven otherwise\\.?$", RegexOption.IGNORE_CASE)

    /**
     * Open Book answers "what is wrong with this patient and what does that make me ask?", which
     * only means anything in a clinical history-taking encounter. Interviews, teach-back, survival
     * and listening drills have no hidden diagnosis to reveal.
     */
    fun isSupportedMode(mode: String): Boolean = mode == "encounter"

    /**
     * The card for [caseJson], or null when this case has no diagnosis to reveal (a placeholder
     * assessment, a non-clinical case, or malformed JSON). A null card hides the entry point
     * entirely — better than a button that opens onto nothing.
     */
    fun cardFor(caseJson: String): OpenBookCard? {
        val root = try {
            JSONObject(caseJson.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val teaching = root.optJSONObject("teaching")
        val assessment = root.optJSONObject("reference_soap")?.optString("assessment", "").orEmpty()

        val diagnosis = teaching?.optString("diagnosis", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractDiagnosis(assessment)
            ?: return null

        val differentials = (teaching?.let { stringList(it, "differentials") } ?: emptyList())
            .ifEmpty { extractDifferentials(assessment) }

        val mustAsk = teaching?.let { asksFrom(it.optJSONArray("must_ask")) }.orEmpty()
            .ifEmpty { asksFrom(root.optJSONArray("objective_cues")) }

        val oneLiner = teaching?.optString("one_liner", "")?.trim()?.ifEmpty { null }
        val redFlags = teaching?.let { stringList(it, "red_flags") }.orEmpty()

        return OpenBookCard(
            caseId = root.optString("id", ""),
            diagnosis = diagnosis,
            oneLiner = oneLiner,
            shortlist = shortlistFor(root.optString("id", ""), diagnosis, differentials),
            mustAsk = mustAsk,
            redFlags = redFlags,
            closing = closingFrom(
                authoredClosing = teaching?.optJSONObject("closing"),
                diagnosis = diagnosis,
                oneLiner = oneLiner,
                redFlags = redFlags,
                plan = root.optJSONObject("reference_soap")?.optString("plan", "").orEmpty(),
            ),
            authored = teaching != null,
        )
    }

    // ---- the closing half ----

    /**
     * Build the closing script: the authored `teaching.closing` block when the case ships one,
     * otherwise a derived script.
     *
     * The derivation is deliberately conservative about *content*. A case's `reference_soap.plan`
     * is written for a chart — "Implement Permissive Hypotension (maintain systolic BP 80-90)",
     * "Activate Massive Transfusion Protocol" — and mechanically rewriting that into a sentence
     * aimed at a patient would produce both bad English and, far worse, a preclinical learner
     * reciting management they do not understand to someone playing a frightened patient.
     *
     * So the derived script supplies the part that is always safe and always the same — the four
     * moves and the English that carries them — and leaves the case-specific clinical content as a
     * blank the learner fills. The chart text rides along on [ClosingLine.chartNote] as the thing
     * *not* to say, which is the register lesson this app already teaches in its plain-language
     * drill, anchored here to a case the learner has just spent ten minutes inside.
     */
    fun closingFrom(
        authoredClosing: JSONObject?,
        diagnosis: String,
        oneLiner: String?,
        redFlags: List<String>,
        plan: String,
    ): OpenBookClosing {
        authoredClosing?.let { authored ->
            val lines = ClosingMove.entries.flatMap { move -> closingLinesFrom(authored, move) }
            if (lines.isNotEmpty()) return OpenBookClosing(lines = lines, authored = true)
        }

        val spokenDiagnosis = EXAMINER_HEDGE.replace(diagnosis.trim(), "").trim().ifEmpty { diagnosis }
        val lines = mutableListOf(
            ClosingLine(
                move = ClosingMove.EXPLAIN,
                say = "Based on what you've told me, I think this is $spokenDiagnosis.",
            ),
            ClosingLine(
                move = ClosingMove.EXPLAIN,
                say = "In plain words, that means ${ClosingLine.BLANK}.",
                point = "Explain what the diagnosis means in everyday, patient-friendly English.",
                chartNote = oneLiner,
            ),
            ClosingLine(
                move = ClosingMove.PLAN,
                say = "The next step is ${ClosingLine.BLANK}, and that will tell us more.",
                chartNote = chartPlanNote(plan),
            ),
            ClosingLine(
                move = ClosingMove.PLAN,
                say = "Does that sound alright to you?",
            ),
        )

        val flag = redFlags.firstNotNullOfOrNull { spokenRedFlag(it) }
        lines += ClosingLine(
            move = ClosingMove.SAFETY_NET,
            say = if (flag != null) {
                "If you get $flag, don't wait — come back, or go straight to the emergency department."
            } else {
                "If it gets worse, or anything worries you, come back, or go straight to the " +
                    "emergency department."
            },
        )
        lines += ClosingLine(
            move = ClosingMove.TEACH_BACK,
            say = "Just so I know I've explained it clearly — how would you tell your family what " +
                "we found today?",
        )
        return OpenBookClosing(lines = lines, authored = false)
    }

    /**
     * Cut a `reference_soap.plan` down to a chart note worth glancing at: lead-in dropped,
     * generated boilerplate and "right now" filler removed, first sentence only, length capped.
     * Null when nothing usable survives.
     */
    fun chartPlanNote(plan: String): String? {
        var text = PLAN_LEAD_IN.replace(plan.trim(), "")
        for (boilerplate in PLAN_BOILERPLATE) text = boilerplate.replace(text, "")
        text = PLAN_FILLER.replace(text, "").trim()
        if (text.isEmpty()) return null

        // Walk past any leading sentence that only restates the case back at itself, so a plan
        // whose first sentence is "Evidence-based … protocol for Bell's Palsy." yields the
        // instruction after it rather than a heading the learner already knows.
        while (true) {
            val end = SENTENCE_END.find(text)
            val first = (if (end == null) text else text.substring(0, end.range.first)).trim()
            if (end == null || !PLAN_HEADER_SENTENCE.matches(first)) {
                text = first
                break
            }
            text = text.substring(end.range.last + 1).trim()
            if (text.isEmpty()) return null
        }

        text = TRAILING_ENUMERATOR.replace(text, "").trim().trimEnd('.', ',', ';', ':').trim()
        if (text.length < 8) return null
        return if (text.length <= CHART_NOTE_MAX) {
            text
        } else {
            text.take(CHART_NOTE_MAX).substringBeforeLast(' ').trimEnd(',', ';') + "…"
        }
    }

    /**
     * Turn an authored red flag into something sayable mid-sentence: the reader-facing rationale
     * dropped ("… — hyperkalemia risk"), the first letter lowercased so it reads as a clause.
     * Null when nothing usable is left.
     */
    fun spokenRedFlag(redFlag: String): String? {
        var text = RED_FLAG_RATIONALE.replace(redFlag.trim(), "").trim().trimEnd('.', ',', ';')
        if (text.length < 3) text = redFlag.trim().trimEnd('.', ',', ';')
        if (text.length < 3) return null
        // Only decapitalise an ordinary word — "ECG changes" and "BP over 180" must keep their caps.
        if (text.length > 1 && text[0].isUpperCase() && text[1].isLowerCase()) {
            text = text[0].lowercaseChar() + text.substring(1)
        }
        return text
    }

    /**
     * Whether the sheet should be holding the microphone muted right now.
     *
     * Pure so the policy is unit-testable and so the UI can simply re-apply it whenever a level
     * changes, rather than deciding once at open time and leaving the learner muted through the
     * half of the sheet they are supposed to be reading out loud.
     */
    fun shouldHoldMic(
        policy: OpenBookMicPolicy,
        level: OpenBookLevel,
        closingLevel: ClosingLevel,
    ): Boolean = when (policy) {
        OpenBookMicPolicy.NEVER -> false
        OpenBookMicPolicy.ALWAYS -> true
        OpenBookMicPolicy.READING ->
            level.step < OpenBookLevel.CHECKLIST.step && closingLevel == ClosingLevel.HIDDEN
    }

    /**
     * Whether session setup must pause for the neutral practice-focus choice before starting a
     * live voice transport. The choice is offered before any diagnosis is rendered, so it is no
     * longer controlled by the old "brief every encounter" preference or by an intro-dialog flag.
     */
    fun shouldGateBriefing(
        cardAvailable: Boolean,
        alreadyBriefed: Boolean,
        hasSeedTranscript: Boolean,
        usesLiveMicrophone: Boolean,
    ): Boolean = cardAvailable && !alreadyBriefed && !hasSeedTranscript && usesLiveMicrophone

    /** A pre-existing manual mute means Open Book must not claim ownership of the mic. */
    fun shouldLeaveMicUnmanaged(micMuted: Boolean, heldByOpenBook: Boolean): Boolean =
        micMuted && !heldByOpenBook

    /** Per-specialty memory describes the latest reveal, not the deepest reveal ever observed. */
    fun rememberedLevelAfterReveal(
        @Suppress("UNUSED_PARAMETER") previous: Int,
        revealed: Int,
    ): Int = OpenBookLevel.of(revealed).step

    /** Read one move's authored lines, accepting either a single object or an array of them. */
    private fun closingLinesFrom(root: JSONObject, move: ClosingMove): List<ClosingLine> {
        val entries = when (val raw = root.opt(move.key)) {
            is org.json.JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> emptyList()
        }
        return entries.mapNotNull { entry ->
            val say = entry.optString("say", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ClosingLine(
                move = move,
                say = say,
                point = entry.optString("point", "").trim().ifEmpty { null },
                chartNote = entry.optString("chart_note", "").trim().ifEmpty { null },
            )
        }
    }

    /**
     * Pull the disease name out of a `reference_soap.assessment`.
     *
     * The library writes assessments three ways — a templated lead-in, a bare diagnosis, and a
     * hand-written "X until proven otherwise. DDx: …" — so this strips the lead-in, drops anything
     * from the differential marker onward, and keeps the first sentence. Returns null for the
     * placeholder assessments that name nothing.
     */
    fun extractDiagnosis(assessment: String): String? {
        var text = assessment.trim()
        if (text.isEmpty() || PLACEHOLDER_ASSESSMENT.matches(text)) return null
        for (lead in ASSESSMENT_LEAD_INS) {
            val stripped = lead.replace(text, "")
            if (stripped != text) {
                text = stripped.trim()
                break
            }
        }
        // "ACS until proven otherwise. DDx: STEMI, …" → drop the differential, keep the impression.
        DDX_MARKER.find(text)?.let { text = text.substring(0, it.range.first) }
        // Keep only the first sentence, but never split an abbreviation like "S. aureus" or a
        // decimal — require the period to be followed by whitespace and a capital or end of string.
        Regex("\\.(?=\\s+[A-Z(]|\\s*$)").find(text)?.let { text = text.substring(0, it.range.first) }
        text = text.trim().trimEnd('.', ',', ';', ':').trim()
        if (text.length < 3) return null
        return if (PLACEHOLDER_ASSESSMENT.matches(text)) null else text
    }

    /**
     * The differential a hand-written assessment already lists after "DDx:". Split on commas and
     * semicolons, capped at four so level 1 stays a decision rather than a reading task.
     */
    fun extractDifferentials(assessment: String): List<String> {
        val match = DDX_MARKER.find(assessment) ?: return emptyList()
        return assessment.substring(match.range.last + 1)
            .substringBefore("\n")
            .split(',', ';')
            .map { it.trim().trimEnd('.').trim() }
            .filter { it.length in 3..60 }
            .distinct()
            .take(4)
    }

    /**
     * The level-1 candidate list: the real diagnosis plus its differentials, ordered by a stable
     * hash of the case id.
     *
     * Ordering matters more than it looks. Appending the answer to the differentials would put it
     * last every time and sorting alphabetically would leak it just as reliably; a per-case stable
     * order means the learner has to actually reason about which one fits, and still sees the same
     * list on every recomposition and after a process death.
     */
    fun shortlistFor(caseId: String, diagnosis: String, differentials: List<String>): List<String> {
        val candidates = (listOf(diagnosis) + differentials)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(4)
        if (candidates.size < 2) return candidates
        return candidates.sortedBy { stableOrderKey(caseId, it) }
    }

    /** Objectives for the level-3 checklist, in card order — the keys its live ticks are stored under. */
    fun mustAskObjectives(card: OpenBookCard): List<String> = card.mustAsk.map { it.objective }

    /**
     * Compile the checklist into [CoverageEngine] rules so the sheet's rows tick themselves off the
     * live transcript.
     *
     * Authored `domains`/`keywords` go through [CoverageEngine.ruleFor]'s authored path, which is
     * what makes a row like "Exertional trigger" tick when the learner says "does it come on when
     * you walk uphill". Rows that author neither fall back to content-word overlap with their own
     * objective text — weaker, but never wrong in a way that matters here, since these ticks are a
     * reading aid and are never graded.
     */
    fun rulesFor(card: OpenBookCard): List<ObjectiveRule> = card.mustAsk.map { ask ->
        if (ask.domains.isEmpty() && ask.keywords.isEmpty()) {
            CoverageEngine.ruleFor(ask.objective)
        } else {
            CoverageEngine.ruleFor(
                ask.objective,
                JSONObject()
                    .put("domains", org.json.JSONArray(ask.domains))
                    .put("keywords", org.json.JSONArray(ask.keywords))
                    // Any one of the listed domains is enough: a row names the places an answer
                    // could come from, not a set the learner must exhaust.
                    .apply { if (ask.domains.isNotEmpty()) put("min_domains", 1) }
                    // Likewise one recognisable word is the signal — a beginner will say "walking",
                    // not the whole authored vocabulary.
                    .apply { if (ask.keywords.isNotEmpty()) put("min_keywords", 1) },
            )
        }
    }

    /**
     * Deterministic, platform-independent ordering key. Kotlin's `hashCode` would do, but pinning
     * our own keeps the golden/unit expectations stable if that ever changes.
     */
    private fun stableOrderKey(seed: String, item: String): Int {
        var hash = 17
        for (char in "$seed|${item.lowercase()}") {
            hash = hash * 31 + char.code
        }
        return hash
    }

    private fun asksFrom(array: org.json.JSONArray?): List<OpenBookAsk> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val objective = entry.optString("objective", "").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            OpenBookAsk(
                objective = objective,
                say = entry.optString("say", "").trim().ifEmpty { null },
                domains = stringList(entry, "domains"),
                keywords = stringList(entry, "keywords"),
            )
        }
    }

    private fun stringList(root: JSONObject, key: String): List<String> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it, null)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}
