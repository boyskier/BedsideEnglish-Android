package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the Nursing track knows about itself, deliberately free of Android and Compose so it
 * can be unit-tested directly.
 *
 * The track's design promise is that extending it is content-only: a new scenario is a JSON file in
 * `data/cases/nursing/` and nothing else. This object is where that promise is actually kept — the
 * Practice screen only renders what [cards] returns, the analysis prompt only scopes what
 * [scoringNote] and [assessmentContract] say, and the feedback card is built by [NursingScorecard]
 * from the case and transcript. Adding a case touches none of them.
 */
object NursingTrack {

    /** `session_mode` in the case JSON, and the `mode` a finished session is stored under. */
    const val SESSION_MODE = "nursing"

    /** `data/cases/<group>/`, which the generated case catalog records as each row's `group`. */
    const val CATALOG_GROUP = "nursing"

    const val FAMILY_OET = "OET role-play"
    const val FAMILY_HANDOVER = "Handover & escalation"
    const val FAMILY_EDUCATION = "Patient education"
    const val FAMILY_BEDSIDE = "Bedside care"
    const val FAMILY_SPEAK_UP = "Speak up for safety"
    const val FAMILY_INTERVIEW = "Job interview"

    /**
     * The task families a nursing case can file under, in curriculum order — which is the order the
     * picker's filter chips appear in. OET leads because it is the gate most internationally
     * educated nurses must pass first; handover follows because it is the workplace skill a nurse
     * is most often judged on; the job interview closes the list because it is the last step
     * before the job itself.
     *
     * A new *family* is the one change that is not content-only: add it here and to
     * `nursingTaskFamilies` in `app/build.gradle.kts`, so the build refuses a case filed under a
     * family the picker cannot render.
     */
    val TASK_FAMILIES = listOf(
        FAMILY_OET,
        FAMILY_HANDOVER,
        FAMILY_EDUCATION,
        FAMILY_BEDSIDE,
        FAMILY_SPEAK_UP,
        FAMILY_INTERVIEW,
    )

    /**
     * Destinations a case prepares the learner for (`nursing_pathways` in the case JSON), in the
     * order the picker's goal chips appear. A case can serve several: a patient-teaching scenario
     * written in neutral terms is as useful in Texas as in Sydney.
     */
    const val PATHWAY_OET = "oet"
    const val PATHWAY_US = "us"
    const val PATHWAY_UK_AU = "uk_au"
    val PATHWAYS = listOf(PATHWAY_OET, PATHWAY_US, PATHWAY_UK_AU)

    /** English chip label for a pathway id (translated at the UI edge like every other label). */
    fun pathwayLabel(pathway: String): String = when (pathway) {
        PATHWAY_OET -> "OET Nursing"
        PATHWAY_US -> "US hospital (NCLEX-RN)"
        PATHWAY_UK_AU -> "UK · Australia · NZ"
        else -> pathway
    }

    /** Who the learner is talking to (`counterpart` in the case JSON). */
    val COUNTERPARTS = setOf("patient", "relative", "physician", "nurse", "pharmacist", "interviewer")

    /** How a session is scored: OET's nine criteria, or the case rubric's own metrics. */
    const val FRAMEWORK_OET = "oet"
    const val FRAMEWORK_RUBRIC = "rubric"

    /** One nursing scenario as the picker renders it, resolved from a catalog row alone. */
    data class Card(
        val id: String,
        val title: String,
        val description: String,
        val taskFamily: String,
        val difficulty: String,
        val assetPath: String,
        val pathways: List<String> = emptyList(),
        val counterpart: String = "",
        val stationMinutes: Int? = null,
    )

    /**
     * Sort rank for a task family. An unrecognized family sorts last rather than vanishing: a case
     * that somehow reached a release with a typo'd family should still be reachable, just demoted.
     */
    fun taskFamilyRank(taskFamily: String): Int =
        TASK_FAMILIES.indexOf(taskFamily).takeIf { it >= 0 } ?: Int.MAX_VALUE

    /**
     * The families to show as filter chips, given what the catalog actually holds. Known families
     * keep curriculum order; anything unrecognized is appended alphabetically so it is still
     * filterable. A family with no cases never renders an empty chip.
     */
    fun orderedTaskFamilies(present: Collection<String>): List<String> {
        val found = present.filter { it.isNotBlank() }.toSet()
        return TASK_FAMILIES.filter { it in found } +
            found.filterNot { it in TASK_FAMILIES }.sorted()
    }

    /** The pathway chips to show, in [PATHWAYS] order, for the pathways the cards actually carry. */
    fun orderedPathways(cards: Collection<Card>): List<String> {
        val found = cards.flatMap { it.pathways }.toSet()
        return PATHWAYS.filter { it in found }
    }

    /** Cards for one goal, or all of them when [pathway] is null. */
    fun filterByPathway(cards: List<Card>, pathway: String?): List<Card> =
        if (pathway == null) cards else cards.filter { pathway in it.pathways }

    /**
     * Map one catalog row to a card, or null when the row is not a usable nursing scenario.
     *
     * Every field comes from the compact catalog the build generates, so the picker never opens a
     * full case JSON to draw a list. `team_title`/`team_description` are extracted by the build
     * from the case's `team_brief`, which is also what the in-session brief renders — so the card
     * and the brief can never describe different scenarios.
     */
    fun cardFrom(row: JSONObject): Card? {
        if (row.optString("group") != CATALOG_GROUP) return null
        val assetPath = row.optString("asset_path")
        if (assetPath.isBlank()) return null
        val fallbackId = assetPath.substringAfterLast('/').removeSuffix(".json")
        val id = row.optString("id").ifBlank { fallbackId }
        if (id.isBlank()) return null

        val title = row.optString("team_title")
            .ifBlank { row.optString("patient_name") }
            .ifBlank { fallbackId }
        val description = row.optString("team_description")
            .ifBlank { row.optString("chief_complaint") }
            .ifBlank { "Nursing scenario." }

        return Card(
            id = id,
            title = title,
            description = description,
            taskFamily = row.optString("nursing_task"),
            difficulty = row.optString("difficulty").lowercase(),
            assetPath = assetPath,
            pathways = parsePathways(row.opt("nursing_pathways")),
            counterpart = row.optString("counterpart").trim().lowercase(),
            stationMinutes = row.optString("station_minutes").trim().toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        )
    }

    /** Every nursing card in the catalog, in curriculum order then alphabetically by title. */
    fun cards(catalog: List<JSONObject>): List<Card> =
        catalog.mapNotNull(::cardFrom)
            .sortedWith(compareBy({ taskFamilyRank(it.taskFamily) }, { it.title }))

    /**
     * Pathways as the catalog carries them (a comma-joined string, since the build's catalog is
     * flat) or as a case carries them (a JSON array). Unknown ids are dropped, order normalised.
     */
    fun parsePathways(raw: Any?): List<String> {
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is Iterable<*> -> raw.map { it?.toString().orEmpty() }
            is String -> raw.split(',')
            else -> emptyList()
        }.map { it.trim().lowercase() }.toSet()
        return PATHWAYS.filter { it in values }
    }

    /** True when this case JSON declares itself a nursing scenario. */
    fun isNursingCase(caseData: Map<String, Any?>): Boolean =
        caseData["session_mode"]?.toString()?.trim()?.lowercase() == SESSION_MODE

    /** The counterpart a case declares, or the one its family implies (older cases predate the field). */
    fun counterpartOf(case: JSONObject): String =
        case.optString("counterpart").trim().lowercase().takeIf { it in COUNTERPARTS }
            ?: NursingSignals.inferCounterpart(case.optString("nursing_task"))

    fun counterpartOf(caseData: Map<String, Any?>): String =
        caseData["counterpart"]?.toString()?.trim()?.lowercase()?.takeIf { it in COUNTERPARTS }
            ?: NursingSignals.inferCounterpart(caseData["nursing_task"]?.toString().orEmpty())

    /** OET role-plays are scored on the OET criteria; every other family on its own rubric. */
    fun frameworkOf(case: JSONObject): String = frameworkFor(case.optString("nursing_task"), case.optString("eval_template"))

    fun frameworkOf(caseData: Map<String, Any?>): String =
        frameworkFor(caseData["nursing_task"]?.toString().orEmpty(), caseData["eval_template"]?.toString().orEmpty())

    private fun frameworkFor(family: String, evalTemplate: String): String =
        if (family.trim() == FAMILY_OET || evalTemplate.trim() == "nursing_oet_roleplay") FRAMEWORK_OET else FRAMEWORK_RUBRIC

    /** The brief's task list — the authority on what the learner was asked to do. */
    fun mustInclude(case: JSONObject): List<String> = stringList(case.optJSONObject("team_brief")?.opt("must_include"))

    fun mustInclude(caseData: Map<String, Any?>): List<String> {
        val brief = caseData["team_brief"]
        val raw = when (brief) {
            is Map<*, *> -> brief["must_include"]
            is JSONObject -> brief.opt("must_include")
            else -> null
        }
        return stringList(raw)
    }

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        is Iterable<*> -> raw.map { it?.toString().orEmpty() }
        else -> emptyList()
    }.map(String::trim).filter(String::isNotEmpty)

    // ── Live role-play ──────────────────────────────────────────────────────────────────────

    /**
     * Rules appended to every nursing persona for the live voice session. Each case authors who the
     * counterpart is and how they push back; these are the behaviours every one of them needs so the
     * session trains the learner rather than rescuing them — above all, never finishing the task
     * for the nurse and never turning into an English teacher mid-scenario.
     */
    fun liveRolePlayRules(counterpart: String, taskFamily: String): String {
        val who = counterpart.trim().lowercase()
        val extra = when {
            taskFamily.trim() == FAMILY_OET ->
                "\n- This is an OET-style role-play of about five minutes. Let the nurse lead. Raise your " +
                    "worry or resistance early and hold it until the nurse genuinely addresses it. When the " +
                    "tasks feel covered, wind down naturally (thank them, confirm what you will do)."
            taskFamily.trim() == FAMILY_INTERVIEW || who == "interviewer" ->
                "\n- Ask one question at a time and wait for the full answer. Probe a vague answer once " +
                    "before moving on. Never tell the candidate how they did."
            who in NursingSignals.CLINICIAN_COUNTERPARTS ->
                "\n- You are a busy colleague. If the nurse's message is unclear, disorganised, or has no " +
                    "request, respond the way a real colleague would (ask what they need, or what their " +
                    "concern is) rather than filling the gap for them."
            else ->
                "\n- You are not medically trained. If the nurse uses a medical term or abbreviation without " +
                    "explaining it, ask what it means."
        }
        return """

NURSING ROLE-PLAY RULES: You are voicing the other person in a spoken nursing practice scenario. The
learner is the nurse, speaking English as a second language.
- Stay in character for the whole conversation. Never mention being an AI, a simulation, or a test.
- Keep each turn short and spoken (usually one to three sentences). React to what the nurse actually
  said, not to what the case expects them to say.
- Never coach, hint, score, or correct the nurse's English, and never complete the nurse's task for
  them (do not volunteer the key information they are supposed to ask for, explain, or request).
- If you genuinely cannot understand something the nurse said, ask them to repeat or rephrase it,
  as a real person would.$extra
""".trimEnd()
    }

    // ── Scoring ──────────────────────────────────────────────────────────────────────────────

    /**
     * Scoping note appended to the post-session analysis prompt for a nursing session.
     *
     * This exists because the analysis schema is a fixed two-way branch (see EvalPromptBuilder):
     * everyday sessions get the everyday keys, and *everything else* — nursing included — gets the
     * clinical schema, which asks for a SOAP note, a history-completeness fraction, an
     * ideas/concerns/expectations flag, and a `clinical_reasoning_score`. Those are the right
     * questions for a physician taking a history and the wrong ones for a nurse handing over a
     * patient or teaching an inhaler.
     *
     * Widening the schema per profession would change the shape of every stored session, so
     * instead this note does what the follow-up and exam notes already do: it keeps the schema and
     * redefines what the keys mean for this session. The rubric anchors carried alongside it
     * (SCORING RUBRIC ANCHORS, from `data/eval/nursing_*.json`) stay the authority on quality.
     */
    val SCORING_NOTE: String = """

NURSING SCOPE SCORING NOTE: This session is a nursing scenario, not a physician history-taking
station. The learner is a nurse, and the other voice may be a physician, another nurse, a patient,
or a relative. Score only what a nurse is responsible for: the structure, clarity, and safety of
what they said, and whether they achieved the task set out in the case brief.

Do not require, and do not reward, a diagnosis, a differential, a prescribing decision, or a
physician's management plan. A nurse who correctly states a concern and escalates it — rather than
naming the underlying diagnosis — has done the right thing, and a nurse who declines to answer
something outside their scope and says who will answer it has also done the right thing. Score
those as strengths, never as gaps.

Map the required score keys onto this session as follows, and judge each one against the supplied
rubric anchors rather than the generic clinical meaning of its name:
- `clinical_reasoning_score` — nursing judgement: the completeness and structure of the assessment,
  handover, or teaching, whether the urgency and the specific request were made explicit, and
  whether the learner stayed within nursing scope.
- `medical_accuracy_score` — accuracy and precision of the clinical information the learner
  conveyed: observations, trends, outstanding tasks, warning signs, and terminology.
- `professionalism_score` — collegiality and advocacy, including holding a safety concern under
  pushback from a more senior clinician, and empathy toward a patient or relative.
- `grammar_score` and `fluency_score` — spoken English as usual.

For the remaining clinical keys: set `history_completeness` to how completely the learner covered
what the case brief's `must_include` list required, not to how much past medical history they
elicited. Set `ice_elicited` true only when eliciting the patient's or relative's own ideas,
concerns, or expectations was actually part of this nursing task. Fill `soap_note` only with what
the transcript supports for this task, and leave any section empty rather than inventing a clinical
workup the nurse was never asked to perform. Report a `misconception_review` entry only for a
clinical claim the learner actually made, never for a physician-level judgement they correctly
left alone.

The case brief was visible to the learner throughout, by design: these scenarios assess structured
spoken communication, not recall of the chart. Do not penalize the learner for reading from it.
    """.trimIndent()

    /** Extra mapping for OET role-plays: the five stored scores in OET terms. */
    val OET_NOTE: String = """
OET ROLE-PLAY NOTE: This is an OET-style Nursing Speaking role-play (about five minutes, nurse with
a patient, relative or carer). Judge it the way an OET assessor would: on the four linguistic
criteria (Intelligibility, Fluency, Appropriateness of Language, Resources of Grammar and
Expression, each 0-6) and the five clinical communication criteria (Relationship-building,
Understanding and Incorporating the Patient's Perspective, Providing Structure,
Information-gathering, Information-giving, each 0-3). Keep the five stored 0-100 scores consistent
with those bands: `grammar_score` = Resources of Grammar and Expression; `fluency_score` = Fluency
together with Intelligibility; `professionalism_score` = Relationship-building together with the
Patient's Perspective; `clinical_reasoning_score` = Providing Structure together with
Information-gathering; `medical_accuracy_score` = Information-giving together with
Appropriateness of Language. Clinical knowledge is not assessed in OET Speaking — never lower a
score for a clinical detail the card did not ask for. Covering every task on the card matters.
    """.trimIndent()

    /** Extra mapping for job interviews: the clinical keys mean the interview dimensions. */
    val INTERVIEW_NOTE: String = """
NURSE JOB INTERVIEW NOTE: This is an employment interview, not a clinical encounter. The other voice
is an interviewer. Map the stored scores as follows: `clinical_reasoning_score` = answer structure
(STAR for behavioural questions) together with clinical judgement and safety in any scenario
question; `medical_accuracy_score` = accuracy of clinical content and use of the destination
country's terminology; `professionalism_score` = motivation, fit, and professional presence. Leave
`soap_note` sections empty and set `ice_elicited` false. Judge what the candidate said about their
own experience as given — do not invent facts about their background.
    """.trimIndent()

    /** The complete nursing scoring note for one case: the scope note plus its framework's mapping. */
    fun scoringNote(caseData: Map<String, Any?>): String {
        val family = caseData["nursing_task"]?.toString()?.trim().orEmpty()
        return buildString {
            append(SCORING_NOTE)
            when {
                frameworkOf(caseData) == FRAMEWORK_OET -> append("\n\n").append(OET_NOTE)
                family == FAMILY_INTERVIEW -> append("\n\n").append(INTERVIEW_NOTE)
            }
        }
    }

    /**
     * The extra JSON key a nursing session's analysis must return, `nursing_assessment`, which
     * [NursingScorecard] turns into the nursing feedback card.
     *
     * Appended *after* the rubric context is length-capped (see AnalysisPromptBuilder), so a long
     * case never truncates the contract away. Extra keys are safe: the main parser ignores what it
     * does not know, and the raw response is stored verbatim for History to rebuild the card.
     */
    fun assessmentContract(caseData: Map<String, Any?>, evalData: Map<String, Any?>): String {
        val tasks = mustInclude(caseData)
        val taskLines = if (tasks.isEmpty()) "(none listed)" else tasks.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val criteriaSpec = if (frameworkOf(caseData) == FRAMEWORK_OET) {
            val linguistic = NursingScorecard.OET_LINGUISTIC.joinToString(", ") { "\"${it.first}\"" }
            val clinical = NursingScorecard.OET_CLINICAL.joinToString(", ") { "\"${it.first}\"" }
            """
    "criteria": {
      // OET linguistic criteria, integer band 0-6 each: $linguistic
      // OET clinical communication criteria, integer band 0-3 each: $clinical
      "<criterion key>": {"band": <integer>, "evidence": "<short transcript quote or reason>"}
    },""".trimEnd()
        } else {
            val metrics = (evalData["metrics"] as? Map<*, *>)?.entries
                ?.map { (k, v) -> k.toString() to ((v as? Map<*, *>)?.get("label")?.toString() ?: k.toString()) }
                .orEmpty()
            val keys = metrics.joinToString(", ") { "\"${it.first}\" (${it.second})" }.ifEmpty { "the rubric metric keys" }
            """
    "criteria": {
      // One entry for EVERY rubric metric key: $keys
      "<metric key>": {"score": <integer 0-100 judged against that metric's anchors>, "evidence": "<short transcript quote or reason>"}
    },""".trimEnd()
        }
        return """

NURSING ASSESSMENT OUTPUT (required for this session): add a top-level key "nursing_assessment" to
your JSON output, in addition to every other required key:

"nursing_assessment": {$criteriaSpec
    "task_items": [
      // One entry per task below, in this exact order, restating the task text in "item".
      {"item": "<task text>", "status": "done | partial | missed", "evidence": "<short transcript quote, or what was missing>"}
    ],
    "safety_flags": [
      // Only for something the learner actually SAID or clearly failed to do that could harm a
      // patient or breach professional standards (wrong information, unsafe advice, missed
      // escalation of a deteriorating patient, privacy breach). Empty array when there is none.
      {"issue": "<what was unsafe>", "severity": "critical | major | minor", "quote": "<learner's words>"}
    ],
    "line_upgrades": [
      // 1-3 of the learner's weakest spoken lines for this task, rewritten the way a confident
      // native-speaking nurse in the destination country would say them.
      {"original": "<learner's exact words>", "better": "<improved line>", "why": "<one short reason>"}
    ]
}

Tasks from the learner's brief (score each one):
$taskLines

Judge "status" strictly from the transcript: "done" needs clear evidence, "partial" means attempted
but incomplete or unclear, "missed" means no attempt. Never invent quotes.
""".trimEnd()
    }
}
