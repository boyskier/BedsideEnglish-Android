package com.example.medvoicetrainer.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rubric-and-context assembly for the post-session analysis prompt — a port of the
 * additive blocks of app/analysis/prompt_builder.py's `build_analysis_prompt`
 * (the per-case scoring context that the fixed two-schema [EvalPromptBuilder]
 * intentionally left out; see MIGRATION_MASTER.md's `prompt_builder.py` row).
 *
 * Design: rather than replace the app's working flat 0–100 output schema/parser
 * with Python's nested `overall_scores` shape (a risky core rewrite), this builds
 * the *input-side* rubric context — scoring-rubric anchors, the checklist to
 * evaluate, empathy markers, per-session fairness NOTE blocks (complexity twist,
 * accent/pace/delivery, practice aids, chart task, listening assistance),
 * self-assessment delta, student SOAP, and debrief commitments — as a text block
 * that [EvalPromptBuilder] appends to its user prompt. This delivers the concrete
 * value of the eval_data engine (rubric-grounded, context-aware scoring) while
 * keeping the existing schema/parser intact.
 *
 * The eval template itself lives in `assets/eval/<name>.json` (already bundled),
 * keyed by each case's `eval_template` field. All `org.json` usage is on-device
 * only (asset load + JSON embedding); the app has no Robolectric (see
 * PORTING_STATUS.md), so this file is exercised at runtime, not in unit tests.
 */
object AnalysisPromptBuilder {

    /** Display names for listening accents (subset of prompt_builder.py's LISTENING_ACCENTS). */
    private val ACCENT_NAMES = mapOf(
        "ca" to "Canadian", "uk" to "British", "aus" to "Australian", "nz" to "New Zealand",
        "in" to "Indian", "ph" to "Filipino", "sg" to "Singaporean", "ng" to "Nigerian",
        "za" to "South African", "es" to "Spanish-speaking", "fr" to "French-speaking",
        "de" to "German-speaking", "ie" to "Irish", "sco" to "Scottish",
    )

    /** Load and parse an eval template from assets/eval/<name>.json, or empty if missing. */
    fun loadEvalTemplate(context: Context, templateName: String): Map<String, Any?> {
        if (templateName.isBlank()) return emptyMap()
        return try {
            val text = context.assets.open("eval/$templateName.json").bufferedReader().use { it.readText() }
            jsonObjectToMap(JSONObject(text))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Resolve which eval template a case uses — its declared `eval_template`, else a
     * domain default (matches how each Python tab passes a default eval template name).
     */
    fun resolveEvalTemplateName(caseData: Map<String, Any?>, everyday: Boolean): String {
        val declared = (caseData["eval_template"] as? String)?.trim()
        if (!declared.isNullOrEmpty()) return declared
        return if (everyday) "survival" else "diagnostic_clinical_english"
    }

    /**
     * Build the additive rubric/context block appended to the analysis user prompt.
     * Ports the NOTE blocks + rubric anchors + checklist + empathy + self-assessment +
     * custom criteria + student SOAP + debrief-commitment sections of `build_analysis_prompt`.
     */
    fun buildRubricContext(
        caseData: Map<String, Any?>,
        evalData: Map<String, Any?>,
        everyday: Boolean,
        selfScores: Map<String, Any?>? = null,
        studentSoap: Map<String, Any?>? = null,
        commitments: List<Map<String, Any?>> = emptyList(),
    ): String {
        val sections = (evalData["output_sections"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val parts = mutableListOf<String>()

        if (!everyday && (caseData["learner_level"] == "preclinical" || evalData["learner_level"] == "preclinical")) {
            parts.add(
                "\nLEARNER LEVEL — PRECLINICAL: This learner is early in training. Prioritise spoken " +
                    "English clarity, empathy, and safe communication over advanced clinical reasoning; " +
                    "do not penalise missing specialist knowledge."
            )
        }

        if (!everyday && str(caseData["encounter_type"]) == "follow_up") {
            parts.add(
                "\nPATIENT FOLLOW-UP SCORING NOTE: This is a longitudinal review, not a first-visit " +
                    "history-taking station. Reward the learner for connecting the interval story to the " +
                    "previous assessment and plan; assessing response, adherence, barriers, adverse effects, " +
                    "new symptoms and case-relevant red flags; explaining available results; and agreeing on " +
                    "a concrete next step, monitoring interval, safety net and understanding check. Do not " +
                    "penalize the learner for not repeating a complete past, family or social history unless " +
                    "the case-specific checklist explicitly requires it. Discussing adherence must be " +
                    "nonjudgmental. Use only transcript evidence and the supplied case ground truth. " +
                    "The app calculates follow-up completeness deterministically from the returned " +
                    "checklist, so do not invent a separate completeness estimate. Summarize only what " +
                    "was actually agreed in shared_plan."
            )
        }

        // A nursing session is scored against the clinical schema (it is not everyday English), but
        // the clinical schema's questions are a physician's. This re-scopes them; see
        // NursingTrack.SCORING_NOTE for why the schema itself is left alone.
        val nursing = !everyday && NursingTrack.isNursingCase(caseData)
        if (nursing) {
            parts.add("\n" + NursingTrack.scoringNote(caseData))
        }
        // The emergency note asks for a physician's acute workup; a nursing case set in an ED is
        // still scored on the nursing task.
        val emergencyEncounter = !everyday && !nursing && (
            str(caseData["care_setting"]) == "emergency_department" ||
                str(caseData["eval_template"]) == "emergency_encounter" ||
                str(caseData["system"]).contains("emergency", ignoreCase = true)
            )
        if (emergencyEncounter) {
            parts.add(
                "\nEMERGENCY ENCOUNTER SCORING NOTE: This is a focused acute-care conversation, " +
                    "not a comprehensive outpatient history. Reward calm, concise spoken English; rapid " +
                    "orientation to the immediate concern; case-relevant red flags; clear explanation of " +
                    "what is happening now; and a concrete investigation, treatment, monitoring, or " +
                    "escalation step supported by the case. Do not penalize omission of a complete family, " +
                    "social, or systems history unless the case-specific checklist requires it. Do not " +
                    "reward a memorized medical list by itself: score what the learner actually communicated " +
                    "to the patient. Results shown by the app are authored case facts, never learner speech."
            )
        }

        // ── Per-session fairness NOTE blocks (mirror build_analysis_prompt) ──
        if (!everyday) {
            val clinicalKnowledge = caseData["clinical_knowledge"]
            if (clinicalKnowledge != null && truthy(clinicalKnowledge)) {
                parts.add(
                    "\nCURATED CLINICAL KNOWLEDGE (preferred misconception ground truth):\n" +
                        prettyJson(clinicalKnowledge) +
                        "\nUse this block before the reference SOAP when deciding whether a spoken " +
                        "clinical claim is incorrect. The source_refs are provenance for content review, " +
                        "not permission to fabricate quotations or recommendations beyond this block."
                )
            } else {
                parts.add(
                    "\nCLINICAL KNOWLEDGE FALLBACK: This case has no curated clinical_knowledge block. " +
                        "You may still identify a clearly spoken misconception by cautiously triangulating " +
                        "the case facts, teaching block, learning objectives, reference SOAP and rubric. " +
                        "Prefer omission over speculation: return no misconception when those inputs do not " +
                        "support a confident correction. Never invent a citation, dose, patient fact or " +
                        "guideline-specific exception."
                )
            }
        }

        if (!everyday && !str(caseData["active_complexity_modifier"]).isEmpty()) {
            parts.add(
                "\nNOTE: During this session the simulated patient was secretly role-playing the " +
                    "following behavioral twist. Take it into account when judging the student's " +
                    "performance (e.g. reward detecting and adapting to it; do not penalize the student " +
                    "for information the patient deliberately withheld):\n" + str(caseData["active_complexity_modifier"])
            )
        }
        if (!everyday && str(caseData["patient_register"]) in setOf("everyday", "slang")) {
            parts.add(
                "\nNOTE: The simulated patient was instructed to speak in deliberately colloquial / " +
                    "slang-heavy lay language. Reward the student for understanding it and for politely " +
                    "asking the patient to clarify unfamiliar expressions — do NOT treat such " +
                    "clarification requests as a weakness."
            )
        }
        if (!everyday && truthy(caseData["exam_mode"])) {
            parts.add(
                "\nEXAM PRACTICE SCORING NOTE: This is an AI-estimated practice score, not an official " +
                    "exam result. Score strictly from transcript evidence and the supplied rubric anchors. " +
                    "Do not infer competence from what the student probably meant. For checklist items, " +
                    "cite direct transcript evidence when passed; if evidence is missing, mark the item as not passed."
            )
        }
        if (str(caseData["ai_speaking_pace"]) in setOf("fast", "challenge")) {
            parts.add(
                "\nNOTE: The live AI was instructed to speak faster than beginner speed. Reward the student " +
                    "for using safe listening strategies: asking for repetition, slowing down the speaker, " +
                    "clarifying numbers, and reading back key details. Do not penalize a polite clarification " +
                    "request as poor fluency."
            )
        }

        if (everyday) {
            val note = if (isSurvival(caseData)) "SURVIVAL ENGLISH NOTE" else "EVERYDAY ENGLISH NOTE"
            parts.add(
                "\n$note — EVERYDAY SCORING: Score only the explicit everyday metric keys in the supplied " +
                    "rubric. Reward a response that fits the situation, including a concise answer, honest " +
                    "uncertainty, clarification, read-back, polite boundary, or natural exit. Do not require " +
                    "the learner to prolong a transactional exchange. Corrections and anki_cards must use " +
                    "useful everyday wording and must not introduce medical content."
            )
            // Survival "Advanced Beta" (docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md). Purely
            // additive: data/eval/survival.json is untouched, and these blocks only appear for a
            // session whose case JSON carries the beta flag, so every existing Survival session —
            // including ones already in History — produces exactly the prompt it always did.
            if (truthy(caseData["advanced_beta"])) {
                parts.add(
                    // Every clause is conditional on a change having actually happened: the learner
                    // may have declined every suggestion, and the voice backend may not have been
                    // able to offer any, in which case this must read as an ordinary Survival
                    // session rather than one whose scene changes went unnoticed.
                    "\nSCENE TRANSITION NOTE: This session was allowed to change scene " +
                        "mid-conversation (moving somewhere, skipping ahead in time, a different " +
                        "person taking over, or the learner going somewhere alone), but it may " +
                        "well not have — the learner decides each one. Any that happened appear in " +
                        "the transcript as `narrator` turns starting with \"[SCENE CHANGE]\"; they " +
                        "are stage directions, NOT something the learner or their partner said. " +
                        "Never score them as learner speech and never quote them as evidence. " +
                        "Where a change did happen, reward the learner for handling it smoothly: " +
                        "re-orienting, re-opening the conversation with a new person, and picking " +
                        "a thread back up after a gap. If none appear, score the session exactly " +
                        "as you would any other everyday conversation and do not remark on their " +
                        "absence."
                )
                val relayEntries = (caseData["relay_facts"] as? List<*>)?.filterNotNull().orEmpty()
                if (relayEntries.isNotEmpty()) {
                    parts.add(
                        "\nRELAY TASK SCORING: The learner was sent off alone and handed the facts " +
                            "below, which their conversation partner did NOT hear. After returning, " +
                            "the learner had to report them in English. Judge the report strictly " +
                            "from the transcript on two things and describe both in your feedback " +
                            "text: (1) accuracy — was every fact relayed, and were any numbers, " +
                            "times, names, or prices dropped or distorted; (2) delivery — did the " +
                            "report sound like a natural spoken hand-over rather than a list read " +
                            "aloud. Do not add new rubric keys and do not introduce medical content; " +
                            "put concrete relay wording fixes in `corrections` like any other " +
                            "everyday-English correction.\n" +
                            // The facts are recorded the moment the errand is accepted, but the
                            // report happens later and can be prevented entirely — the learner
                            // ended the session while still reading the card, or the live scene
                            // never made it back to the partner. Scoring that as a failed relay
                            // penalizes a learner who was never given the chance to try, so the
                            // absence of a report must be reported, not graded.
                            "IMPORTANT: if the transcript contains no such report at all, the " +
                            "learner never got the opportunity to give one. Say so plainly in your " +
                            "feedback text and do NOT treat it as a failed or inaccurate relay, do " +
                            "NOT lower any score for it, and do NOT invent corrections for words " +
                            "the learner never said.\n" + prettyJson(relayEntries)
                    )
                }
            }

            val reveals = learningEvents(caseData).count { it["event_type"] == "transcript_reveal" }
            if (reveals > 0) {
                parts.add(
                    "\nLISTENING ASSISTANCE NOTE: The learner revealed the most recent hidden AI line " +
                        "$reveals time(s). Do not count an answer made after a reveal as evidence of unaided " +
                        "listening comprehension. You may still credit the spoken response itself for " +
                        "naturalness and interaction, and should describe the help factually rather than " +
                        "penalizing or shaming the learner."
                )
            }
        }

        val accent = str(caseData["listening_accent"])
        if (accent.isNotEmpty() && accent != "us") {
            val accentName = ACCENT_NAMES[accent] ?: accent
            parts.add(
                "\nNOTE: The live AI was instructed to use a $accentName English accent as listening " +
                    "training, although accent fidelity varies by voice provider. Reward the student for " +
                    "following the actual speech and for politely asking for repetition or clarification " +
                    "when needed — never treat those requests as a weakness."
            )
        }
        if (str(caseData["speech_style"]) in setOf("natural", "soft", "dense", "street")) {
            parts.add(
                "\nNOTE: The live AI was instructed to use less textbook-like delivery (connected, soft, " +
                    "information-dense, or colloquial speech) as listening training. Reward comprehension-repair " +
                    "strategies ('sorry?', 'what was that?', asking the speaker to slow down) and understanding " +
                    "of casual expressions. Do not penalize clarification requests."
            )
        }

        val speedHistory = mutableListOf(coerceDouble(caseData["playback_speed"]) ?: 1.0)
        for (event in learningEvents(caseData)) {
            if (event["event_type"] == "playback_speed_change") {
                coerceDouble(event["requested_speed"])?.let { speedHistory.add(it) }
            }
        }
        val playbackSpeed = speedHistory.maxOrNull() ?: 1.0
        if (playbackSpeed >= 1.2) {
            val speedContext = if (speedHistory.size > 1) {
                "The requested speed changed during the session (" +
                    speedHistory.joinToString(" → ") { "%.2fx".format(it) } + ")."
            } else {
                "The AI audio was played at a requested %.2fx speed.".format(playbackSpeed)
            }
            parts.add(
                "\nNOTE: $speedContext Playback used the backend's documented speed control and/or local " +
                    "pitch-preserving processing. This acoustic speed plan is deterministic and explicit, " +
                    "unlike prompt-level pace. Reward accurate understanding and natural requests to repeat, " +
                    "chunk, or confirm key details."
            )
        }

        if (!everyday && (truthy(caseData["clinical_chart"]) || truthy(caseData["chart_task"]))) {
            parts.add(
                "\nCHART-TO-SPEECH SCORING NOTE: This session trained spoken conversion of visible chart " +
                    "data into clinical English. Score the learner on whether they accurately verbalized key " +
                    "chart facts, numbers, abnormal results, audience-appropriate register, and a clear next " +
                    "step or recommendation. Do not require hidden history-taking items unless the supplied " +
                    "checklist asks for them."
            )
        }

        if (!everyday && (truthy(caseData["available_results"]) || truthy(caseData["investigation_events"]))) {
            val viewedEventIds = learningEvents(caseData)
                .filter { it["event_type"] == "investigation_result_viewed" }
                .mapNotNull { it["event_id"]?.toString()?.takeIf(String::isNotBlank) }
                .distinct()
            parts.add(
                "\nINVESTIGATION VISIBILITY NOTE: `available_results` were visible to the learner " +
                    "before and during the encounter. Dynamic `investigation_events` were NOT visible " +
                    "unless their id appears in this viewed list: " + prettyJson(viewedEventIds) + ". " +
                    "Do not penalize the learner for failing to discuss an unrevealed result, and never " +
                    "quote an app-authored result as learner speech."
            )
        }

        val helpEvents = learningEvents(caseData).count { it["event_type"] == "help_continue" }
        if (helpEvents > 0) {
            parts.add(
                "\nLIVE LANGUAGE SUPPORT: The learner used 'Help me continue' $helpEvents time(s). Do not " +
                    "shame or directly deduct points for using the scaffold. Do not treat the suggested question " +
                    "itself as unaided language retrieval; instead evaluate how naturally the learner delivered " +
                    "it, understood the answer, and continued the interaction. Suggest one concrete way to need " +
                    "less support next time."
            )
        }

        (caseData["practice_aids"] as? Map<*, *>)?.let { aids ->
            val used = mutableListOf<String>()
            if (truthy(aids["briefed"])) {
                used.add(
                    "a pre-visit briefing naming the diagnosis and the questions to ask, read before " +
                        "the encounter began (so this session was never a diagnostic exercise — do " +
                        "not credit or fault the student for knowing what was wrong)"
                )
            }
            if (truthy(aids["follow_up_english_focus"])) {
                used.add(
                    "an established-patient chart plus a ready-made follow-up question plan reviewed " +
                        "before the visit (this was an English-speaking rehearsal, not a test of " +
                        "independently selecting the clinical agenda; judge how naturally, clearly and " +
                        "responsively the learner used the plan)"
                )
            }
            // A briefing already hands over sections 1-3, so only report the sheet separately when
            // the student went on to open more than the briefing had given them.
            val briefedSections = if (truthy(aids["briefed"])) 3 else 0
            (aids["sections_revealed"] as? Number)?.toInt()?.takeIf { it > briefedSections }?.let {
                used.add("an open-book answer sheet ($it section(s) revealed)")
            }
            (aids["closing_revealed"] as? Number)?.toInt()?.takeIf { it > 0 }?.let {
                used.add(
                    "an open-book closing script (level $it) covering how to explain the diagnosis, " +
                        "state a plan, safety-net and teach-back"
                )
            }
            // The everyday counterpart of the answer sheet (EverydayPhrasebook). Reported by level
            // rather than as a flag for the same reason: reading the frame and reading the finished
            // sentence are different amounts of help, and only the second one produced the wording.
            (aids["phrasebook_revealed"] as? Number)?.toInt()?.takeIf { it > 0 }?.let {
                used.add(
                    "an everyday phrasebook (level $it of 3) offering model expressions for this " +
                        "situation — at level 3 the complete sentences were on screen, so wording " +
                        "that matches them was read rather than retrieved"
                )
            }
            if (truthy(aids["phrasebook_previewed"])) {
                used.add("a pre-start card showing a few model expressions before the conversation began")
            }
            if (truthy(aids["guided_script"])) used.add("a guided script of model questions to read aloud")
            if (truthy(aids["hints_used"])) used.add("${(aids["hints_used"] as? Number)?.toInt() ?: aids["hints_used"]} 'next question' hint(s)")
            if (used.isNotEmpty()) {
                parts.add(
                    "\nPRACTICE AIDS: This was a deliberately scaffolded session — the student used " +
                        used.joinToString(", ") + ". Do NOT penalize reliance on these aids or missing information " +
                        "the aids made obvious. Focus feedback on spoken language quality, pronunciation-relevant " +
                        "phrasing, and rapport. Be encouraging, and suggest one concrete way to depend less on the " +
                        "scaffolding next time."
                )
            }
        }

        // ── Rubric anchors / checklist / empathy markers from the eval template ──
        (evalData["metrics"])?.let {
            parts.add("\nSCORING RUBRIC ANCHORS:\n" + prettyJson(it))
        }
        val isFollowUp = !everyday && str(caseData["encounter_type"]) == "follow_up"
        val followUpObjectives = (caseData["learning_objectives"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        if ("checklist" in sections &&
            (followUpObjectives.isNotEmpty() || caseData["evaluation_checklist"] != null || evalData["checklist"] != null)
        ) {
            // Follow-up's live checklist already uses learning_objectives. Reuse the exact same
            // strings here so the learner never finishes one checklist and receives a different
            // report. Case-authored evaluation_checklist remains available to other clinical modes.
            val checklist = if (isFollowUp && followUpObjectives.isNotEmpty()) {
                followUpObjectives.map { mapOf("item" to it, "required" to true) }
            } else {
                caseData["evaluation_checklist"] ?: evalData["checklist"]
            }
            parts.add(
                "\nCHECKLIST (evaluate each item against transcript):\n" + prettyJson(checklist) +
                    "\nReturn every supplied item exactly once. If an item declares applicable=false, " +
                    "return status=not_applicable, required=false and passed=false, explain why briefly, " +
                    "and do not lower any score for it. Otherwise return status=passed or failed."
            )
        }
        if ("ice_empathy" in sections && evalData["empathy_markers"] != null) {
            parts.add("\nEMPATHY MARKERS TO DETECT:\n" + prettyJson(evalData["empathy_markers"]))
        }
        if (!selfScores.isNullOrEmpty()) {
            parts.add("\nSTUDENT SELF-ASSESSMENT (include delta in output for comparison):\n" + prettyJson(selfScores))
        }
        str(evalData["custom_criteria_prose"] ?: caseData["custom_criteria_prose"])
            .takeIf { it.isNotEmpty() }?.let {
            parts.add("\nADDITIONAL EVALUATION CRITERIA:\n$it")
        }
        if (studentSoap != null && studentSoap.isNotEmpty() && !everyday) {
            parts.add(
                "\n<STUDENT_SOAP>\nThe student wrote the following SOAP note. Compare it to your ideal SOAP " +
                    "note and include specific corrections for it in the `corrections` list.\n" +
                    prettyJson(studentSoap) + "\n</STUDENT_SOAP>\n"
            )
        }

        if (!everyday && commitments.isNotEmpty()) {
            val lines = commitments.mapNotNull { c ->
                val textVal = str(c["text"])
                if (textVal.isEmpty()) null else mapOf(
                    "id" to c["id"],
                    "commitment" to textVal,
                    "focus_area" to (str(c["focus_area"])),
                )
            }
            if (lines.isNotEmpty()) {
                parts.add(
                    "\nDEBRIEF COMMITMENTS TO CHECK:\nAfter a previous session's debrief, the student " +
                        "committed to the behaviors below. Judge each strictly from THIS transcript and add a " +
                        "\"commitment_results\" key to your JSON output:\n\n\"commitment_results\": [\n  {\"id\": " +
                        "<int, the commitment id given below>,\n    \"commitment\": \"<the commitment text>\",\n    " +
                        "\"result\": \"<kept | missed | not_applicable>\",\n    \"evidence\": \"<short transcript " +
                        "quote, or why it was not applicable>\"}\n]\n\nRules: \"kept\" needs direct transcript " +
                        "evidence; \"missed\" means a clear opportunity existed and the student did not do it; " +
                        "\"not_applicable\" means this scenario offered no real opportunity. Do not lower any scores " +
                        "because a commitment was missed — report it factually in commitment_results only.\n\n" +
                        prettyJson(lines)
                )
            }
        }

        val result = parts.joinToString("\n")
        val capped = if (result.length > 15_000) result.take(15_000) else result
        // Outside the cap on purpose: a long clinical_knowledge block must never truncate away the
        // output contract the nursing feedback card depends on.
        return if (nursing) capped + "\n" + NursingTrack.assessmentContract(caseData, evalData) else capped
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun str(v: Any?): String = (v as? String) ?: ""

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "false" && v != "0"
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun coerceDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun isSurvival(caseData: Map<String, Any?>): Boolean =
        truthy(caseData["survival_mode"]) || str(caseData["mode"]) == "survival"

    @Suppress("UNCHECKED_CAST")
    private fun learningEvents(caseData: Map<String, Any?>): List<Map<String, Any?>> =
        (caseData["learning_events"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    private fun prettyJson(value: Any?): String = try {
        when (val json = toJson(value)) {
            is JSONObject -> json.toString(2)
            is JSONArray -> json.toString(2)
            else -> json.toString()
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        value.toString()
    }

    private fun toJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().apply { for ((k, v) in value) put(k.toString(), toJson(v)) }
        is List<*> -> JSONArray().apply { for (v in value) put(toJson(v)) }
        null -> JSONObject.NULL
        else -> value
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) out[key] = jsonValue(obj.get(key))
        return out
    }

    private fun jsonValue(v: Any?): Any? = when (v) {
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> (0 until v.length()).map { jsonValue(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
