package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.voice.SceneTransitionOutcome
import org.json.JSONArray
import org.json.JSONObject

object PromptBuilder {
    /** Modes that supply their own persona and never behave like a walk-in patient encounter. */
    private val NON_ENCOUNTER_MODES = setOf(
        "survival", "everyday", "interview", "exam", "lounge", "follow_up",
        "presentation", "team_communication", "nursing", KmleCpx.SESSION_MODE
    )

    /**
     * [doorknobActive] is decided per-session by the caller (MainViewModel rolls the case's
     * `doorknob_probability`) so this builder stays deterministic. When true and the case declares
     * a `doorknob_disclosure`, the standard patient is told to raise that one concealed worry as
     * the doctor wraps up — the classic "hand on the doorknob" moment. Default false keeps every
     * existing caller (reconnect resend, tests, non-encounter modes) unchanged.
     */
    fun buildSystemPrompt(mode: String, caseJson: String, doorknobActive: Boolean = false): String {
        return try {
            val caseData = JSONObject(caseJson)
            val map = caseData.toMap()
            val prompt = maybeAppendDoorknob(when (mode) {
                // The Survival "Advanced Beta" tab marks its composed case with `advanced_beta`
                // (see docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md). Absent that flag — i.e. every
                // ordinary Survival session, including every session saved before the beta
                // existed — this is byte-for-byte the prompt it always built.
                "survival" -> if (booleanValue(map["advanced_beta"])) {
                    buildSurvivalAdvancedBetaPrompt(
                        map,
                        accent = (map["listening_accent"] as? String) ?: "us",
                        style = (map["speech_style"] as? String) ?: "clear",
                    )
                } else {
                    buildSurvivalPrompt(
                        map,
                        accent = (map["listening_accent"] as? String) ?: "us",
                        style = (map["speech_style"] as? String) ?: "clear",
                    )
                }
                "everyday" -> "You are an everyday person in a real-life scenario: ${map["title"] ?: ""}. Provide realistic responses."
                "interview" -> buildInterviewPrompt(firstInterviewScenario(map))
                "exam" -> ExamMode.buildExamPrompt(map)
                "follow_up" -> buildFollowUpPatientPrompt(map)
                // Korean CPX: a Korean-speaking standardized patient built from the same case.
                KmleCpx.SESSION_MODE -> KmleCpx.buildPatientPrompt(caseJson)
                "lounge" -> {
                    // Mirrors the persona_override check build_patient_prompt already does —
                    // the Lounge tab now builds the real build_lounge_prompt() result (scenario's
                    // prompt_template with {context_text} substituted) and stores it here before
                    // falling back to this generic placeholder for any lounge case without one.
                    val override = (map["persona_override"] as? String)?.trim()
                    if (!override.isNullOrEmpty()) override
                    // A Free Talk case that reaches here without its built prompt (a snapshot
                    // restarted from elsewhere) must still get the brevity rules, not the
                    // generic lounge partner who talks as much as the learner.
                    else if (FreeTalk.isFreeTalk(map)) FreeTalk.buildPrompt(map, str(map[FreeTalk.TOPIC_FIELD]))
                    else "You are a casual conversation partner in a free English lounge. Discuss the topic: ${map["title"] ?: ""}."
                }
                else -> buildPatientPrompt(map)
            }, mode, map, doorknobActive)
            // The live voice transport can drop and silently reconnect mid-session (network
            // blip, or the provider's own session time limit) — see VoiceManager/GeminiLiveClient's
            // reconnect path. When that happens the exact same system prompt is resent to a
            // socket that may have lost conversation context, so this note has to travel with
            // every mode, not just Survival, even though it only matters after a reconnect.
            prompt + SESSION_CONTINUITY_NOTE + NO_SAFETY_DISCLAIMER_NOTE
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            "You are a medical patient. Please respond to the doctor appropriately."
        }
    }

    fun buildPatientPrompt(
        scenario: Map<String, Any>,
        nativeLanguage: String = "your native language",
    ): String {
        // Skill drills (teachback, custom persona cases, etc.) supply their own free-form
        // persona instead of the rigid patient template, which assumes a clinical
        // history-taking encounter — mirrors app/analysis/prompt_builder.py's build_patient_prompt.
        val override = (scenario["persona_override"] as? String)?.trim()
        var prompt = if (!override.isNullOrEmpty()) {
            override.replace("{native_language}", nativeLanguage)
        } else {
            PATIENT_SYSTEM_PROMPT
                .replace("{patient_name}", valueOr(scenario, "patient_name", "the patient"))
                .replace("{age}", valueOr(scenario, "age", "unknown"))
                .replace("{gender}", valueOr(scenario, "gender", "unknown"))
                .replace("{care_setting}", careSetting(scenario))
                .replace("{chief_complaint}", valueOr(scenario, "chief_complaint", ""))
                .replace("{hpi_details}", valueOr(scenario, "hpi_details", ""))
                .replace("{ideas}", valueOr(scenario, "ideas", ""))
                .replace("{concerns}", valueOr(scenario, "concerns", ""))
                .replace("{expectations}", valueOr(scenario, "expectations", ""))
                .replace("{pmh}", valueOr(scenario, "pmh", "none"))
                .replace("{medications}", valueOr(scenario, "medications", "none"))
                .replace("{social_hx}", valueOr(scenario, "social_hx", "not provided"))
        }

        // The case's authored standardized-patient script (sp_script) — the same one the Korean CPX
        // patient reads — pins every answer to the case instead of leaving the model to improvise
        // the pertinent negatives. Only the first-visit template has a hidden-information block.
        if (override.isNullOrEmpty()) {
            spScriptOf(scenario)?.let { prompt += "\n" + SpScript.englishPatientBlock(it) + "\n" }
        }

        str(scenario["active_complexity_modifier"]).takeIf { it.isNotEmpty() }?.let {
            prompt += "\n\n$it\n"
        }

        val isStandardPatient = override.isNullOrEmpty() && !booleanValue(scenario["coaching_mode"])
        if (isStandardPatient) {
            prompt += PATIENT_REGISTER_MODIFIERS[str(scenario["patient_register"])] ?: ""
            prompt += STANDARD_PATIENT_AFFECTS[str(scenario["standard_patient_affect"])] ?: ""
            prompt += STANDARD_PATIENT_PERSONALITIES[str(scenario["standard_patient_personality"])] ?: ""

            // Same listening_accent/speech_style fields and instruction text Survival mode
            // uses (buildSurvivalPrompt below) — reused here so a patient can be voiced with
            // a non-US English accent. "us"/"clear" map to "" so untouched encounter cases
            // are unaffected.
            prompt = appendAccentAndStyle(
                prompt,
                accent = str(scenario["listening_accent"]).ifEmpty { "us" },
                style = str(scenario["speech_style"]).ifEmpty { "clear" },
            )

            // Standard patients get the natural-closure behavior so the encounter can end the way
            // a real visit does (the patient signals readiness to finish) without a hard auto-close
            // that would kill the doctor's ability to grab them back for one more question.
            prompt += CLOSURE_BEHAVIOR_ADDENDUM
        }

        if (booleanValue(scenario["coaching_mode"])) {
            prompt += COACHING_ADDENDUM
        }

        // Nursing personas are authored per case; these are the rules every one of them shares.
        if (str(scenario["session_mode"]).equals(NursingTrack.SESSION_MODE, ignoreCase = true)) {
            prompt += NursingTrack.liveRolePlayRules(str(scenario["counterpart"]), str(scenario["nursing_task"]))
        }

        if (scenario.containsKey("clinical_chart") || scenario.containsKey("chart_task")) {
            val chartPayload = mapOf(
                "task" to (scenario["chart_task"] ?: emptyMap<String, Any>()),
                "clinical_chart" to (scenario["clinical_chart"] ?: emptyMap<String, Any>()),
            )
            prompt += CHART_DRILL_ADDENDUM.replace(
                "{chart_payload}",
                JSONObject(chartPayload).toString(2),
            )
        }
        return prompt
    }

    /**
     * Persona for an established-patient review. The case deliberately separates information the
     * learner may read before entering the room (`clinician_brief`) from information that belongs
     * to the simulated patient (`patient_private`). Both are supplied to the persona so it can
     * answer consistently, but the Practice briefing UI renders only the former.
     */
    fun buildFollowUpPatientPrompt(scenario: Map<String, Any>): String {
        val clinicianBrief = mapValue(scenario["clinician_brief"])
        val patientPrivate = mapValue(scenario["patient_private"])
        return FOLLOW_UP_PATIENT_SYSTEM_PROMPT
            .replace("{patient_name}", valueOr(scenario, "patient_name", "the patient"))
            .replace("{age}", valueOr(scenario, "age", "unknown"))
            .replace("{gender}", valueOr(scenario, "gender", "unknown"))
            .replace("{follow_up_reason}", valueOr(scenario, "follow_up_reason", "a scheduled review"))
            .replace("{clinician_brief}", promptBlock(clinicianBrief))
            .replace("{patient_private}", promptBlock(patientPrivate))
    }

    /** Ported from app/analysis/prompt_builder.py's build_interview_prompt(). */
    fun buildInterviewPrompt(scenario: Map<String, Any>): String {
        val override = str(scenario["persona_override"]).trim()
        if (override.isNotEmpty()) {
            val opening = str(scenario["opening_question"]).trim()
            return if (opening.isEmpty()) override else "$override\n\nYour opening question: $opening"
        }
        return INTERVIEW_SYSTEM_PROMPT
            .replace("{pd_name}", valueOr(scenario, "pd_name", "Dr. Smith"))
            .replace("{program}", valueOr(scenario, "program", "this program"))
            .replace("{category}", valueOr(scenario, "category", "behavioral"))
            .replace(
                "{opening_question}",
                valueOr(scenario, "opening_question", "Tell me about yourself."),
            )
    }

    /** Ported from app/analysis/prompt_builder.py's build_custom_prompt(). */
    fun buildCustomPrompt(personaDescription: String): String {
        val persona = personaDescription.ifBlank { "You are a helpful conversational partner." }
        return "$persona\n\nSession ends when the user says \"I'd like to end the session.\"\n"
    }

    /**
     * Ported from app/analysis/prompt_builder.py's build_lounge_prompt(). [scenario]'s
     * `prompt_template` carries a literal `{context_text}` placeholder (see
     * the lounge case JSON files) substituted with the user-provided topic/YouTube transcript.
     */
    fun buildLoungePrompt(scenario: Map<String, Any?>, contextText: String): String {
        val template = (scenario["prompt_template"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?: "You are a friendly conversational partner.\n\n{context_text}"
        return template.replace("{context_text}", contextText)
    }

    /**
     * System prompt for a Survival English session — ported from
     * app/analysis/prompt_builder.py's build_survival_prompt(). [situation] is the
     * composed case dict produced by [SurvivalComposer]; rapid-fire mode passes a
     * case whose `scenario_type` is "rapid_fire" carrying a `question_samples` list.
     */
    fun buildSurvivalPrompt(
        situation: Map<String, Any?>,
        accent: String = "us",
        style: String = "clear",
    ): String {
        var prompt: String
        if ((situation["scenario_type"] as? String) == "rapid_fire") {
            val samples = stringListOf(situation["question_samples"])
            prompt = SURVIVAL_RAPID_FIRE_PROMPT.replace(
                "{question_samples}",
                samples.joinToString("\n") { "  - \"$it\"" },
            )
        } else {
            val spice = str(situation["spice"])
            var aiPrivate = str(situation["ai_private"])
            if (spice.isNotEmpty() && !aiPrivate.contains(spice)) {
                aiPrivate = listOf(aiPrivate, spice).filter { it.isNotEmpty() }.joinToString("\n")
            }
            var publicUserContext = str(situation["public_user_context"])
            if (publicUserContext.isNotEmpty()) {
                publicUserContext = "USER-KNOWN CONTEXT: $publicUserContext"
            }
            val followups = stringListOf(situation["followups"])
            // Hangout frames (survival_situations.json's "Hangouts & Long Talks" category) reuse
            // every composed field from the same SurvivalComposer pipeline, but need a template
            // that permits long, unhurried turns and topic callbacks instead of the default
            // 1-3-sentence transactional pacing.
            val template = if ((situation["scenario_type"] as? String) == "hangout") {
                SURVIVAL_HANGOUT_PROMPT
            } else {
                SURVIVAL_SYSTEM_PROMPT
            }
            prompt = applySurvivalPlaceholders(
                template, situation, publicUserContext, aiPrivate, followups,
            )
        }

        return appendAccentAndStyle(prompt, accent, style)
    }

    /**
     * Fill the shared Survival placeholders in [template]. Extracted verbatim from
     * [buildSurvivalPrompt] so the "Advanced Beta" templates below can reuse exactly the same
     * substitution instead of keeping their own copy of it — the templates differ, the data
     * binding does not.
     */
    private fun applySurvivalPlaceholders(
        template: String,
        situation: Map<String, Any?>,
        publicUserContext: String,
        aiPrivate: String,
        followups: List<String>,
    ): String = template
        .replace(
            "{counterpart_role}",
            firstNonEmpty(
                str(situation["counterpart_role"]),
                str(situation["persona"]),
                "a friendly stranger",
            ),
        )
        .replace(
            "{public_brief}",
            firstNonEmpty(
                str(situation["public_brief"]),
                str(situation["setting"]),
                "You bump into the user.",
            ),
        )
        .replace("{public_user_context}", publicUserContext)
        .replace(
            "{persona_profile}",
            str(situation["persona_profile"]).ifEmpty { "Use the scenario's natural role and tone." },
        )
        .replace("{persona_speaking_style}", str(situation["persona_speaking_style"]))
        .replace("{persona_behavior}", str(situation["persona_behavior"]))
        .replace("{role_behavior}", str(situation["role_behavior"]))
        .replace("{interaction_goal_instruction}", str(situation["interaction_goal_instruction"]))
        .replace("{twist_instruction}", str(situation["twist_instruction"]))
        .replace("{hidden_scene}", str(situation["hidden_scene"]))
        .replace("{ai_private}", aiPrivate)
        .replace("{user_variant_private}", str(situation["user_variant_private"]))
        .replace("{opener}", str(situation["opener"]).ifEmpty { "Hey, how's it going?" })
        .replace(
            "{followups}",
            followups.joinToString("; ").ifEmpty { "whatever feels natural" },
        )
        .replace("{spice}", "")

    /**
     * System prompt for a **Survival English — Advanced Beta** session (see
     * docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md). Reached only when the composed case carries
     * `advanced_beta: true`, which only the beta tab sets.
     *
     * Uses its own frozen copies of the two Survival templates plus the scene-transition rules,
     * so the shipped Survival mode's prompts can never be changed by work on this experiment.
     * `scene_transition_menu` is the category's allowed-transition list, precomputed by the beta
     * entry point from `data/survival_transitions.json` and carried in the case JSON (this builder
     * stays Context-free). With no menu, the transition rules are omitted entirely and the session
     * behaves like an ordinary Survival scene.
     */
    fun buildSurvivalAdvancedBetaPrompt(
        situation: Map<String, Any?>,
        accent: String = "us",
        style: String = "clear",
    ): String {
        if ((situation["scenario_type"] as? String) == "rapid_fire") {
            // A reaction sprint has no scene to transition between; fall back unchanged.
            return buildSurvivalPrompt(situation, accent, style)
        }
        val spice = str(situation["spice"])
        var aiPrivate = str(situation["ai_private"])
        if (spice.isNotEmpty() && !aiPrivate.contains(spice)) {
            aiPrivate = listOf(aiPrivate, spice).filter { it.isNotEmpty() }.joinToString("\n")
        }
        var publicUserContext = str(situation["public_user_context"])
        if (publicUserContext.isNotEmpty()) {
            publicUserContext = "USER-KNOWN CONTEXT: $publicUserContext"
        }
        val followups = stringListOf(situation["followups"])
        val template = if ((situation["scenario_type"] as? String) == "hangout") {
            SURVIVAL_HANGOUT_ADVANCED_BETA_PROMPT
        } else {
            SURVIVAL_ADVANCED_BETA_PROMPT
        }
        val menu = str(situation["scene_transition_menu"]).trim()
        val transitionRules = if (menu.isEmpty()) "" else {
            SCENE_TRANSITION_RULES.replace("{scene_transition_menu}", menu)
        }
        val prompt = applySurvivalPlaceholders(
            template, situation, publicUserContext, aiPrivate, followups,
        ).replace("{scene_transition_rules}", transitionRules)
        return appendAccentAndStyle(prompt, accent, style)
    }

    /**
     * System prompt for the person who takes over after an accepted `new_character` transition.
     * The live session has one voice, so a new character means a fresh connection with a new voice
     * and this prompt (see VoiceManager's scene-switch orchestration). The "you were not there"
     * line is load-bearing: the previous turns are replayed as history so the new character knows
     * what the scene is about, and without it the model reads them as its own memories.
     */
    fun buildSceneCharacterPrompt(
        newRole: String,
        sceneDescription: String,
        previousRole: String,
        accent: String = "us",
        style: String = "clear",
    ): String {
        val role = newRole.trim().ifEmpty { "a new person who has just joined the scene" }
        val scene = sceneDescription.trim().ifEmpty { "You have just come into the conversation." }
        val previous = previousRole.trim().ifEmpty { "someone else" }
        val prompt = SCENE_NEW_CHARACTER_PROMPT
            .replace("{scene_return_rules}", SCENE_RETURN_TRANSITION_RULES)
            .replace("{new_character_role}", role)
            .replace("{scene_description}", scene)
            .replace("{previous_role}", previous)
        return appendAccentAndStyle(prompt, accent, style)
    }

    /**
     * Everything the *new* character needs to know about handing the learner back — appended by
     * [buildSceneCharacterPrompt] because the beta tool declaration travels with the connection,
     * not the prompt, so a character reached by reconnecting can still call it and would otherwise
     * have no idea what for. The only transition it is allowed to propose is the return trip.
     */
    private val SCENE_RETURN_TRANSITION_RULES = """

GOING BACK (you have a tool for this):
You can call the function `propose_scene_transition` with type="return_to_previous" to suggest that the user goes back to {previous_role}, who they were talking to before you. The user sees it as a card and decides; nothing happens until they tap it.
- Call it ONLY once your own part of the conversation is genuinely wrapping up — the user has what they came to you for, or it is clear you cannot help further.
- The exception is the user asking to go back themselves ("I should get back to them", "can I go now?"): call it straight away with requested_by_user=true, whatever else was happening.
- Do not propose any other kind of scene change. That is not your part of the scene.
- Never mention the suggestion out loud and never narrate it. Make the call silently and keep talking normally — do not pause or wait for an answer.
- If the tool answers "${SceneTransitionOutcome.DECLINED_BY_USER.wire}" the user wants to stay with you, so stay and be useful. Any other answer means they simply never responded to it — carry on as normal, and it is fine to offer the way back again later.
- Set title to something natural like "Head back to {previous_role}?" and describe in one sentence what the user is taking back with them.
"""

    /**
     * Appended to the original counterpart's system prompt when the learner comes back to them
     * after a `new_character` detour, so the resumed session knows time passed and who the learner
     * was just with instead of restarting the scene.
     */
    fun sceneReturnNote(sceneDescription: String, otherRole: String): String {
        val scene = sceneDescription.trim()
        val other = otherRole.trim().ifEmpty { "someone else" }
        return "\n\nSCENE UPDATE: The user stepped away to deal with $other and has just come back " +
            "to you. Everything the two of you already talked about still stands — do not restart " +
            "the scene or re-introduce yourself. Pick up naturally, and it is fine to ask how it " +
            "went." + (if (scene.isEmpty()) "" else " What happened while they were away: $scene")
    }

    /** The ACCENT/DELIVERY suffix for [accent]/[style], for callers building their own prompt. */
    fun accentAndStyleSuffix(accent: String, style: String): String =
        appendAccentAndStyle("", accent, style)

    /** Appends the ACCENT/DELIVERY instruction text (if any) for [accent]/[style] to [prompt]. */
    private fun appendAccentAndStyle(prompt: String, accent: String, style: String): String {
        var result = prompt
        val accentPart = LISTENING_ACCENTS[accent]?.second ?: LISTENING_ACCENTS.getValue("us").second
        if (accentPart.isNotEmpty()) result += "\n\n$accentPart"
        val stylePart = SPEECH_CLARITY_STYLES[style]?.second ?: SPEECH_CLARITY_STYLES.getValue("clear").second
        if (stylePart.isNotEmpty()) result += "\n\n$stylePart"
        return result
    }

    private fun JSONObject.toMap(): Map<String, Any> =
        keys().asSequence().associateWith { key -> jsonValue(opt(key)) }

    private fun jsonValue(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.toMap()
        is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.opt(index)) }
        else -> value
    }

    private fun firstInterviewScenario(caseData: Map<String, Any>): Map<String, Any> {
        val first = (caseData["scenarios"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: return caseData
        return first.entries.mapNotNull { (key, value) ->
            (key as? String)?.let { it to (value ?: "") }
        }.toMap()
    }

    private fun str(v: Any?): String = (v as? String) ?: ""

    /** The case's `sp_script`, whether the scenario map holds it as a JSONObject or as a nested Map. */
    private fun spScriptOf(scenario: Map<String, Any>): SpScript.Script? {
        val raw = scenario[SpScript.CASE_KEY] ?: return null
        val block = when (raw) {
            is JSONObject -> raw
            is Map<*, *> -> runCatching { JSONObject(raw) }.getOrNull()
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return null
        return SpScript.parse(JSONObject().put(SpScript.CASE_KEY, block))
    }

    private fun valueOr(source: Map<String, Any>, key: String, fallback: String): String {
        val value = source[key] ?: return fallback
        return value.toString().takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun careSetting(source: Map<String, Any>): String {
        val authored = valueOr(source, "care_setting", "").trim().lowercase()
        val system = valueOr(source, "system", "").trim().lowercase()
        return when {
            authored in setOf("emergency_department", "emergency room", "ed") ||
                "emergency" in system -> "the emergency department"
            authored in setOf("outpatient_clinic", "outpatient clinic") -> "an outpatient clinic"
            authored in setOf("inpatient", "ward", "hospital_ward") -> "a hospital ward"
            authored in setOf("urgent_care", "urgent care") -> "an urgent care clinic"
            authored.isNotEmpty() -> authored.replace('_', ' ')
            else -> "an outpatient clinic"
        }
    }

    private fun booleanValue(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

    /** Read a list of strings from either a Kotlin List or an org.json JSONArray. */
    private fun stringListOf(v: Any?): List<String> = when (v) {
        is List<*> -> v.mapNotNull { it as? String }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it, null) }
        else -> emptyList()
    }

    private fun mapValue(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        is JSONObject -> value.toMap()
        else -> emptyMap()
    }

    private fun promptBlock(values: Map<String, Any?>): String = if (values.isEmpty()) {
        "Not provided. Do not invent details."
    } else {
        values.entries.joinToString("\n") { (key, value) ->
            val label = key.replace('_', ' ').uppercase()
            val rendered = when (value) {
                is List<*> -> value.joinToString("; ") { it.toString() }
                is JSONArray -> (0 until value.length()).joinToString("; ") { value.opt(it).toString() }
                is Map<*, *> -> JSONObject(value).toString()
                is JSONObject -> value.toString()
                else -> value?.toString().orEmpty()
            }
            "$label: $rendered"
        }
    }

    private val PATIENT_SYSTEM_PROMPT = """You are {patient_name}, a {age}-year-old {gender} presenting to {care_setting}.
Chief complaint: {chief_complaint}

HIDDEN INFORMATION — only reveal when directly and appropriately asked:
HPI: {hpi_details}
ICE:
  Ideas: {ideas}
  Concerns: {concerns}
  Expectations: {expectations}
PMH: {pmh}
Medications: {medications}
Social history: {social_hx}

Behavioral guidelines:
- Speak as a real patient. Use lay terms, not medical jargon.
- Show appropriate emotion (anxiety, confusion, relief, fear).
- Do NOT volunteer hidden information unprompted.
- If a question is unclear, ask for clarification as a patient would.
- Respond only to what was actually asked — never narrate what the student "should" ask.
- Session ends when the student says "I'd like to summarize what we've discussed."
$PATIENT_BREVITY_RULES"""

    /**
     * How much a standard patient says per turn. A real patient answers the question in front of
     * them and stops; the model's default is to recite the case (onset, character, radiation and
     * three associated symptoms in reply to "What brings you in?"), which does the history for
     * the learner. Shared by the first-visit and follow-up personas.
     */
    private const val PATIENT_BREVITY_RULES = """
HOW MUCH TO SAY — answer like a real patient in a real clinic, not like a case summary:
- Answer ONLY the question just asked, then stop and wait. Most answers are one short sentence, and many are just a few words ("About three days." "No." "Here, on the right side.").
- A yes/no question gets yes or no plus a few words at most. A when / where / how long / how much question gets just that fact.
- An open question ("What brings you in?", "Tell me more") gets only the main thing bothering you, in one or two plain sentences. Leave onset, character, radiation, associated symptoms, and your history for the doctor to ask.
- Never list several symptoms or history items in one answer. "Anything else?" gets only what you actually have, one or two things — "No, that's about it." is a fine answer.
- Keep your ideas, concerns, and expectations until the doctor asks about them or clearly invites them. Do not add explanations, background stories, or worries to an answer about something else.
- If the doctor asks two questions at once, answer them the way a real person would — often only the last or the easier one — and let the doctor ask again.
- Do not ask a question back or thank the doctor after every answer. A short answer followed by silence is normal.
- Emotion shows in your tone and word choice, not in longer answers.
- Exception: if a STANDARDIZED PATIENT STYLE below says you are talkative, follow that style instead.
"""

    private val FOLLOW_UP_PATIENT_SYSTEM_PROMPT = """You are {patient_name}, a {age}-year-old {gender} returning for a real outpatient follow-up visit.
Reason for follow-up: {follow_up_reason}

SHARED CLINICAL CONTEXT — the clinician has read this before the conversation:
{clinician_brief}

PATIENT-ONLY INTERVAL INFORMATION — reveal naturally when the clinician asks an appropriate question:
{patient_private}

Role and information boundaries:
- Stay in character as the patient. Never become a tutor, examiner, narrator, or clinician.
- This is an established follow-up, not a first visit. Do not retell your full medical history or act as if the prior plan is unknown.
- Treat SHARED CLINICAL CONTEXT as mutually known. You may briefly confirm it, but do not quiz the clinician on facts already in the chart.
- Do not volunteer the whole PATIENT-ONLY section at once. Answer only the specific question asked, usually in one short spoken sentence.
- If asked an open question such as "How have things been?", give only the main interval change, then wait for follow-up questions.
- Be honest about missed treatment, barriers, side effects, and concerns when asked. React positively to neutral, nonjudgmental wording and become mildly guarded if blamed.
- Use ordinary patient language. Do not introduce medical facts, measurements, symptoms, diagnoses, or treatment recommendations absent from this case.
- Results in the shared brief are known to the clinician. You may ask what they mean, but never interpret them yourself.
- If the clinician uses unexplained jargon or gives an unclear plan, ask for clarification.
- Bring up your agenda or concern when invited to ask questions, when the plan affects it, or when the clinician starts wrapping up. Do this once, naturally.
- Accept reasonable uncertainty. Do not demand a diagnosis or prescription that is not supported by the case.
- Near the end, respond realistically to teach-back and repeat the plan in your own words; include one plausible misunderstanding only if the explanation was genuinely unclear.
- When the clinician has explained the next step, timing, and warning signs, signal readiness to finish. Do not end the session yourself.
"""

    private val INTERVIEW_SYSTEM_PROMPT = """You are {pd_name}, Program Director of {program} Internal Medicine Residency.
You are conducting a {category} interview with an International Medical Graduate (IMG) applicant.

Guidelines:
- Ask the opening question first, then follow up with probing questions from the pool provided.
- Be professional but press for specifics when answers are vague.
- For behavioral questions, expect and probe for STAR format (Situation, Task, Action, Result).
- Topics to assess: clinical reasoning, communication, professionalism,
  motivation for IM, motivation for the US, cultural adaptability, long-term goals.
- For IMG-specific scenarios: probe visa situation, support network, adaptation strategies.
- Opening question: {opening_question}
"""

    private val PATIENT_REGISTER_MODIFIERS = mapOf(
        "clinical" to "",
        "everyday" to (
            "\n\nLANGUAGE STYLE: Speak in everyday, casual language. Use common lay terms and some " +
                "colloquial expressions for your symptoms (e.g. 'throwing up', 'a runny tummy', " +
                "'worn out', 'a splitting headache'). Don't explain what you mean unless the doctor asks.\n"
            ),
        "slang" to (
            "\n\nLANGUAGE STYLE: Speak very informally, using lots of colloquialisms, slang, idioms, and " +
                "euphemisms for symptoms and bodily functions (e.g. 'feeling under the weather', " +
                "'number two', 'spotting', 'down there', 'the runs', 'I passed out', 'my ticker'). " +
                "Use these naturally and do NOT explain what you mean unless the doctor explicitly " +
                "asks you to clarify — make them work a little to understand you.\n"
            ),
    )

    private val STANDARD_PATIENT_AFFECTS = mapOf(
        "neutral" to "",
        "anxious" to (
            "\n\nSTANDARDIZED PATIENT AFFECT: You are visibly anxious. Ask for reassurance, " +
                "worry about serious disease, and need the doctor to acknowledge your fear before you settle."
            ),
        "frustrated" to (
            "\n\nSTANDARDIZED PATIENT AFFECT: You are frustrated because you feel nobody has " +
                "explained things clearly. You are not abusive, but you are short and skeptical until " +
                "the doctor listens and summarizes."
            ),
        "embarrassed" to (
            "\n\nSTANDARDIZED PATIENT AFFECT: You feel embarrassed about the symptom. Give short " +
                "answers at first and open up only if the doctor uses respectful, normalizing language."
            ),
    )

    private val STANDARD_PATIENT_PERSONALITIES = mapOf(
        "typical" to "",
        "talkative" to (
            "\n\nSTANDARDIZED PATIENT STYLE: You are talkative and occasionally wander off topic. " +
                "Reward gentle redirection; if the doctor never redirects, keep adding irrelevant detail."
            ),
        "quiet" to (
            "\n\nSTANDARDIZED PATIENT STYLE: You are quiet and give brief answers. The doctor needs " +
                "open questions, silence, and follow-up prompts to get the full story."
            ),
        "skeptical" to (
            "\n\nSTANDARDIZED PATIENT STYLE: You are skeptical of medical advice. Ask practical " +
                "questions and become more cooperative only when the doctor explains the reason clearly."
            ),
    )

    private fun maybeAppendDoorknob(
        prompt: String,
        mode: String,
        map: Map<String, Any>,
        active: Boolean,
    ): String {
        if (!active || mode in NON_ENCOUNTER_MODES) return prompt
        val doorknob = (map["doorknob_disclosure"] as? String)?.trim().orEmpty()
        if (doorknob.isEmpty()) return prompt
        return prompt + DOORKNOB_ADDENDUM.replace("{doorknob}", doorknob)
    }

    /**
     * Baked into every standard patient encounter. This is how the app gives an encounter a
     * natural ending WITHOUT a hard auto-close: the patient signals they're ready to finish once
     * the interview winds down, but is explicitly forbidden from leaving on their own and told to
     * stay engaged the moment the doctor asks anything else — preserving the "oh, one more thing"
     * grab-back that a real auto-terminate would destroy.
     */
    private val CLOSURE_BEHAVIOR_ADDENDUM = """

ENDING THE VISIT NATURALLY:
- You are NOT in a hurry, and you must NEVER end the conversation or walk out on your own. The doctor decides when the visit is over.
- Once the doctor has clearly covered the main interview and starts to wrap up, summarize, or explain a plan, you may gently show you're ready to finish — e.g. "Is that everything, doctor?", "Okay, that makes sense", or a small sign you're ready to go.
- But if the doctor then asks anything else, raises a new question, or remembers something, drop the wrap-up completely and stay engaged — answer naturally, exactly as before (still only what was asked). A real patient is always willing to keep talking when the doctor isn't finished.
- Never announce that the session is ending and never refer to this as practice.
"""

    /**
     * Optional per-case "doorknob disclosure": the concern a patient blurts out just as the doctor
     * is leaving. Only appended when the case declares `doorknob_disclosure` and the per-session
     * probability roll passed (see [maybeAppendDoorknob] / [buildSystemPrompt]).
     */
    private val DOORKNOB_ADDENDUM = """

ONE LAST WORRY (do this only once, and only at the very end):
When the doctor begins to wrap up, says goodbye, or signals the visit is over — and only then — reluctantly bring up one more thing that has quietly been bothering you, the way patients often do with a hand already on the door: "Oh… actually, doctor, before I go —". The worry is: {doorknob}
Raise it only at that closing moment, never earlier, and only once. If the doctor takes it seriously and explores it, respond naturally; if they brush past it, let it drop.
"""

    private val COACHING_ADDENDUM = """

COACHING MODE — the student is a pre-clinical beginner practising medical English:
- Be warm, patient, and encouraging. Speak slowly and use short, simple, clear sentences.
- If the student goes quiet or seems stuck, gently prompt them in character, e.g.
  "Take your time, doctor." or offer a small nudge like "Did you want to ask how long I've had this?"
- If the student uses an unclear or slightly wrong word, respond to the meaning you think they intend
  rather than refusing to understand. Keep the conversation flowing.
- Keep your own turns short so the student does most of the talking.
- You may still hold back the hidden details until asked, but reveal them readily even when the
  question is imperfect. Never lecture, correct grammar, or step out of character.
"""

    private val CHART_DRILL_ADDENDUM = """

CHART-TO-SPEECH DRILL:
The learner can see the same visible chart data below. Your job is to make them
convert chart information into spoken clinical English. Do not simply read the
chart aloud for them. Ask for the task, listen for missing or inaccurate details,
and ask one short follow-up if their handoff/explanation is incomplete.

Visible chart data and task:
{chart_payload}

Prioritize spoken English skills:
- accurate reading of numbers, units, times, and abnormal results;
- concise team language for handoffs and chart summaries;
- patient-friendly language when the audience is a patient;
- clear recommendation, read-back, or teach-back when the task calls for it.
"""

    private val SURVIVAL_SYSTEM_PROMPT = """You are {counterpart_role}. This is a completely ordinary, real-life conversation — NOT a medical scenario, NOT an interview, and NOT an English lesson. The user is just another person in the scene with you.

PUBLIC SCENE THE USER COULD REALISTICALLY KNOW:
{public_brief}
{public_user_context}

YOUR PERSONALITY THIS TIME:
{persona_profile}
{persona_speaking_style}
{persona_behavior}

ROLE-SPECIFIC BEHAVIOR:
{role_behavior}

CONVERSATION DIRECTION THIS TIME:
{interaction_goal_instruction}

REAL-LIFE TWIST THIS TIME:
{twist_instruction}

AI-PRIVATE SCENE FACTS / DISCLOSURE BOUNDARY:
{hidden_scene}
{ai_private}
{user_variant_private}

Do not dump private facts into the first turn. Reveal only what your character would naturally say, what the user asks about, or what becomes obvious through the conversation.

HOW TO BEHAVE:
- YOU speak first. Open with: "{opener}" — or a natural variation of it.
- Talk like a real person: react genuinely ("oh nice", "wait, really?", "no way"), laugh, tease gently, share your own little stories and opinions, and ask specific follow-up questions about what they actually said — never generic survey questions like "what are your hobbies?".
- Let the conversation drift naturally the way real small talk does. Some directions it could go: {followups}
- If the user gives a one-word answer, dig in with a concrete follow-up question; make them talk, but never lecture them.
- Treat "sorry?", "what was that?", read-backs, and requests to slow down as normal real-life repair. Repeat, chunk, or rephrase naturally without praising the user or turning into a teacher.
- Never correct their grammar, never mention English practice, never break character, and never narrate the scene.
- Keep your own turns SHORT — one to three sentences — so the user does most of the talking.
{spice}
The conversation ends when the user says goodbye or says they have to go."""

    /**
     * Hangout variant of [SURVIVAL_SYSTEM_PROMPT] for scenario_type == "hangout" (relaxed,
     * unhurried real-life frames like a long train ride or a late-night couch talk — see
     * survival_situations.json's "Hangouts & Long Talks" category). Shares every placeholder
     * with the transactional prompt above, but drops the "1-3 sentences" pacing cap, asks for
     * topic callbacks, and is explicit that the scene must not reset if the connection hiccups.
     */
    private val SURVIVAL_HANGOUT_PROMPT = """You are {counterpart_role}. This is a relaxed, unhurried real-life hangout — NOT a medical scenario, NOT an interview, and NOT an English lesson. There is no task to finish and nowhere either of you needs to rush off to; the whole point is that the conversation can wander and go long, the way it does between two people who have time for each other.

PUBLIC SCENE THE USER COULD REALISTICALLY KNOW:
{public_brief}
{public_user_context}

YOUR PERSONALITY THIS TIME:
{persona_profile}
{persona_speaking_style}
{persona_behavior}

ROLE-SPECIFIC BEHAVIOR:
{role_behavior}

CONVERSATION DIRECTION THIS TIME:
{interaction_goal_instruction}

REAL-LIFE TWIST THIS TIME:
{twist_instruction}

AI-PRIVATE SCENE FACTS / DISCLOSURE BOUNDARY:
{hidden_scene}
{ai_private}
{user_variant_private}

Do not dump private facts into the first turn. Reveal only what your character would naturally say, what the user asks about, or what becomes obvious through the conversation.

HOW TO BEHAVE:
- YOU speak first. Open with: "{opener}" — or a natural variation of it.
- Talk like a real person who has time to talk: react genuinely, laugh, tease gently, and volunteer your own opinions, complaints, and little stories — do not just interview the user with one question after another.
- Have real content of your own: bring up something from your own day, an opinion, a complaint, a piece of gossip, or a plan, and let the user react to YOU sometimes instead of always answering your questions.
- CALLBACK: every so often, refer back to something specific the user said earlier in this same conversation ("wait, going back to what you said about...", "oh, that reminds me — earlier you mentioned..."). Real long conversations loop back on themselves; do this naturally, not mechanically, and only about things that were actually said this session.
- Let topics run their course before moving on. A single thread can last several turns; do not rush from small talk to small talk. Some directions this could go: {followups}
- It is completely fine for one of your turns to run several sentences when you are in the middle of telling a story or sharing an opinion — just do not monologue for a full paragraph without a break.
- If the user gives a one-word answer, dig in with a concrete follow-up question; make them talk, but never lecture them.
- Treat "sorry?", "what was that?", read-backs, and requests to slow down as normal real-life repair. Repeat, chunk, or rephrase naturally without praising the user or turning into a teacher.
- Never correct their grammar, never mention English practice, never break character, and never narrate the scene.
{spice}
The conversation ends when the user says goodbye or says they have to go."""

    // ── Survival English — Advanced Beta ────────────────────────────────────────────────────
    // Everything below this line belongs to the scene-transition experiment
    // (docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md) and is reached only from the beta tab. The two
    // templates are FROZEN COPIES of SURVIVAL_SYSTEM_PROMPT / SURVIVAL_HANGOUT_PROMPT taken at the
    // time the beta was written, with one extra placeholder ({scene_transition_rules}). They are
    // copies rather than an addendum on purpose: the shipped Survival mode must be impossible to
    // change from here, and deleting the beta means deleting this block. If the shipped templates
    // are ever improved, decide deliberately whether to port the change down here.

    private val SCENE_TRANSITION_RULES = """

CHANGING THE SCENE (you have a tool for this):
You can call the function `propose_scene_transition` to suggest that the scene changes. The user sees your suggestion as a small card they can accept or ignore; nothing happens until they tap it.
- Call it ONLY at a moment where the change would happen naturally in real life — the current thread has run its course, the reason to move somewhere has actually come up in the conversation, or one of you would obviously go and check something.
- NEVER mention the suggestion out loud, never ask "shall we go over there?" as speech, and never narrate that a scene change is available. Make the call silently and keep talking as if nothing happened — do NOT pause, trail off, or wait for an answer.
- The tool answers you with what became of your suggestion. "${SceneTransitionOutcome.DECLINED_BY_USER.wire}" means the user turned that idea down: let it go, and be very reluctant to suggest anything else. "${SceneTransitionOutcome.EXPIRED_UNANSWERED.wire}" and "${SceneTransitionOutcome.RATE_LIMITED.wire}" mean they never really saw it or it came too soon — nobody refused anything, so simply carry on, and a genuinely better moment much later is still fair game. "${SceneTransitionOutcome.BUDGET_EXHAUSTED.wire}" means this conversation has had all the scene changes YOU may suggest: stop offering your own and stay where you are — but a change the user asks for themselves still gets through, so keep calling the function for those.
- Do not suggest anything in the first minute or so of the conversation — there has to be a scene before there is anything to change.
- At most FIVE suggestions of your own in the whole conversation that the user actually answers, and never two close together.
- Pick from the list below and reuse its wording. Do not invent a different place, time skip, or person.
{scene_transition_menu}
- WHEN THE USER IS THE ONE WHO ASKS, this changes completely. If they bring it up themselves — "can we go inside?", "should we come back tomorrow?", "I'll go ask at the desk", "is there someone who could help with this?" — call the function IMMEDIATELY with requested_by_user=true, describing what THEY asked for, even if it is not on the list above. Their own requests are not rationed: the first-minute rule, the gap between suggestions, and the five-suggestion limit all apply only to changes you thought of. Answer them in speech as your character naturally would at the same time — just never mention the card.
- Only set requested_by_user=true when the user really did ask for it in their own words. Never set it for an idea of your own, and never to get around a limit; a suggestion of yours labelled as theirs is worse than no suggestion at all.
- If the user accepts, you will receive a [SCENE CHANGE] stage direction. Treat it as real and continue in the new scene without commenting on the jump.
- If a transition sends the user off somewhere ALONE, you did not go with them. They go quiet while they are away — do not talk to the empty air, just wait. When they come back, you genuinely do not know what they were told: ask about it, react, and let them correct you if you get a detail wrong.
"""

    private val SURVIVAL_ADVANCED_BETA_PROMPT = """You are {counterpart_role}. This is a completely ordinary, real-life conversation — NOT a medical scenario, NOT an interview, and NOT an English lesson. The user is just another person in the scene with you.

PUBLIC SCENE THE USER COULD REALISTICALLY KNOW:
{public_brief}
{public_user_context}

YOUR PERSONALITY THIS TIME:
{persona_profile}
{persona_speaking_style}
{persona_behavior}

ROLE-SPECIFIC BEHAVIOR:
{role_behavior}

CONVERSATION DIRECTION THIS TIME:
{interaction_goal_instruction}

REAL-LIFE TWIST THIS TIME:
{twist_instruction}

AI-PRIVATE SCENE FACTS / DISCLOSURE BOUNDARY:
{hidden_scene}
{ai_private}
{user_variant_private}

Do not dump private facts into the first turn. Reveal only what your character would naturally say, what the user asks about, or what becomes obvious through the conversation.

HOW TO BEHAVE:
- YOU speak first. Open with: "{opener}" — or a natural variation of it.
- Talk like a real person: react genuinely ("oh nice", "wait, really?", "no way"), laugh, tease gently, share your own little stories and opinions, and ask specific follow-up questions about what they actually said — never generic survey questions like "what are your hobbies?".
- Let the conversation drift naturally the way real small talk does. Some directions it could go: {followups}
- If the user gives a one-word answer, dig in with a concrete follow-up question; make them talk, but never lecture them.
- Treat "sorry?", "what was that?", read-backs, and requests to slow down as normal real-life repair. Repeat, chunk, or rephrase naturally without praising the user or turning into a teacher.
- Never correct their grammar, never mention English practice, never break character, and never narrate the scene.
- Keep your own turns SHORT — one to three sentences — so the user does most of the talking.
{spice}{scene_transition_rules}
The conversation ends when the user says goodbye or says they have to go."""

    private val SURVIVAL_HANGOUT_ADVANCED_BETA_PROMPT = """You are {counterpart_role}. This is a relaxed, unhurried real-life hangout — NOT a medical scenario, NOT an interview, and NOT an English lesson. There is no task to finish and nowhere either of you needs to rush off to; the whole point is that the conversation can wander and go long, the way it does between two people who have time for each other.

PUBLIC SCENE THE USER COULD REALISTICALLY KNOW:
{public_brief}
{public_user_context}

YOUR PERSONALITY THIS TIME:
{persona_profile}
{persona_speaking_style}
{persona_behavior}

ROLE-SPECIFIC BEHAVIOR:
{role_behavior}

CONVERSATION DIRECTION THIS TIME:
{interaction_goal_instruction}

REAL-LIFE TWIST THIS TIME:
{twist_instruction}

AI-PRIVATE SCENE FACTS / DISCLOSURE BOUNDARY:
{hidden_scene}
{ai_private}
{user_variant_private}

Do not dump private facts into the first turn. Reveal only what your character would naturally say, what the user asks about, or what becomes obvious through the conversation.

HOW TO BEHAVE:
- YOU speak first. Open with: "{opener}" — or a natural variation of it.
- Talk like a real person who has time to talk: react genuinely, laugh, tease gently, and volunteer your own opinions, complaints, and little stories — do not just interview the user with one question after another.
- Have real content of your own: bring up something from your own day, an opinion, a complaint, a piece of gossip, or a plan, and let the user react to YOU sometimes instead of always answering your questions.
- CALLBACK: every so often, refer back to something specific the user said earlier in this same conversation ("wait, going back to what you said about...", "oh, that reminds me — earlier you mentioned..."). Real long conversations loop back on themselves; do this naturally, not mechanically, and only about things that were actually said this session.
- Let topics run their course before moving on. A single thread can last several turns; do not rush from small talk to small talk. Some directions this could go: {followups}
- It is completely fine for one of your turns to run several sentences when you are in the middle of telling a story or sharing an opinion — just do not monologue for a full paragraph without a break.
- If the user gives a one-word answer, dig in with a concrete follow-up question; make them talk, but never lecture them.
- Treat "sorry?", "what was that?", read-backs, and requests to slow down as normal real-life repair. Repeat, chunk, or rephrase naturally without praising the user or turning into a teacher.
- Never correct their grammar, never mention English practice, never break character, and never narrate the scene.
{spice}{scene_transition_rules}
The conversation ends when the user says goodbye or says they have to go."""

    /**
     * Persona for the person who takes over after an accepted `new_character` transition. Kept
     * deliberately short: the scene's own context arrives as replayed conversation history
     * (VoiceClient.seedHistory), and this prompt's job is to say who this person is and, crucially,
     * that they did not witness any of it.
     */
    private val SCENE_NEW_CHARACTER_PROMPT = """You are {new_character_role}. This is an ordinary, real-life conversation — NOT a medical scenario, NOT an interview, and NOT an English lesson.

THE SCENE YOU ARE STEPPING INTO:
{scene_description}

IMPORTANT — WHAT YOU DO AND DO NOT KNOW:
- The conversation history you were given is a conversation the user just had with {previous_role}. YOU WERE NOT THERE and did not hear any of it. Never quote it, never imply you overheard it, and never speak as if you were the person they were just talking to.
- Read it only as background so you understand the situation the user is in. Everything you actually learn, you learn from the user telling you now.
- If the user assumes you already know something, say so plainly the way a real person would ("sorry, I've just come over — what's going on?").

HOW TO BEHAVE:
- YOU speak first, with one natural opening line for someone in your role walking into this situation.
- Talk like a real person in this job or role: concise, practical, and reactive to what the user actually says.
- Keep your turns SHORT — one to three sentences — so the user does most of the talking.
- Treat "sorry?", "what was that?", and read-backs as normal real-life repair. Repeat or rephrase naturally without turning into a teacher.
- Never correct their grammar, never mention English practice, never break character, and never narrate the scene.
{scene_return_rules}
The conversation ends when the user says goodbye or says they have to go."""

    /**
     * Appended to every mode's system prompt (see [buildSystemPrompt]) so a mid-session
     * reconnect on a provider whose context wasn't fully preserved doesn't read to the user as
     * the AI forgetting who it is or restarting the scene.
     */
    private const val SESSION_CONTINUITY_NOTE = "\n\nIMPORTANT: If your connection to the user is briefly interrupted and reconnects, or if you are reminded partway through that the conversation is continuing, treat everything that already happened as real and already-established. Never restart the scene, never re-introduce yourself, and never reinterpret who anyone is or what already happened — pick the conversation up naturally from where it left off."

    /**
     * The live models occasionally tack a stock "this is not medical advice — consult a
     * healthcare professional" paragraph onto an in-character line. It is spoken aloud as well as
     * transcribed, so it breaks immersion in the middle of an encounter and pollutes the graded
     * transcript. This note cuts how often it happens; SafetyDisclaimerFilter cleans up the text
     * of the ones that still slip through (the disclaimer is added by the provider's own safety
     * layer, so a prompt alone can never fully suppress it).
     */
    private const val NO_SAFETY_DISCLAIMER_NOTE = "\n\nIMPORTANT: You are playing a character in a training roleplay for a qualified clinician — you are never the one giving medical advice, so no safety disclaimer is ever warranted. Never say or append anything like \"this is not medical advice\", \"this is not a diagnosis\", \"consult a healthcare professional\", or \"seek professional medical attention\". Never step outside the character to add a note, caveat, or reminder of any kind. Speak only the words your character would speak."

    private val SURVIVAL_RAPID_FIRE_PROMPT = """You are a friendly acquaintance doing a brisk "speed small talk" reaction sprint with the user. Use questions that could naturally come up today, in the current place, or around an immediate practical choice. This is NOT a medical scenario and NOT an English lesson.

HOW TO BEHAVE:
- YOU speak first. Fire ONE short, specific, everyday question at the user — the kind of random thing real people suddenly ask, not interview questions.
- When they answer, react briefly and naturally (one sentence — surprise, agreement, a tiny joke), optionally ask ONE quick follow-up, then jump to a COMPLETELY different random question.
- Keep the pace brisk: your turns are 1–2 sentences max. Sudden topic changes are the whole point.
- Draw questions like these (invent more in the same lived, specific spirit):
{question_samples}
- Prefer right-now/today questions, shared surroundings, small decisions, schedules, food, transport, or something that just happened.
- Never use fantasy or interview-card hypotheticals such as teleporting, superpowers, lottery wins, dream dinner guests, or generic "tell me about your hobbies" prompts.
- If the user asks you to repeat or slow down, do it naturally once, then continue the conversation; never turn into an English teacher.
- Never correct their grammar, never mention English practice, never break character.
The session ends when the user says goodbye or says they have to go."""

    /** name → (display label, accent instruction). Ported from prompt_builder.py's LISTENING_ACCENTS. */
    private val LISTENING_ACCENTS: Map<String, Pair<String, String>> = mapOf(
        "us" to ("General American" to ""),
        "ca" to ("Canadian" to (
            "ACCENT: You are Canadian. Speak with a genuine, natural Canadian English accent — " +
                "including its subtle vowel and intonation patterns — with Canadian wording where it " +
                "fits. Keep it realistic and conversational, never a caricature."
            )),
        "uk" to ("British" to (
            "ACCENT: You are British. Speak with a genuine, natural British English accent — " +
                "its rhythm, vowels, and intonation — and use British vocabulary where natural " +
                "(e.g. 'queue', 'quid', 'cheers', 'knackered', 'fancy a…'). Stay realistic, not a caricature."
            )),
        "aus" to ("Australian" to (
            "ACCENT: You are Australian. Speak with a genuine, natural Australian English accent — " +
                "rising intonation, relaxed vowels — and use Australian vocabulary where natural " +
                "(e.g. 'arvo', 'heaps', 'reckon', 'no worries', 'keen'). Stay realistic, not a caricature."
            )),
        "nz" to ("New Zealand" to (
            "ACCENT: You are from New Zealand. Speak with a genuine, natural New Zealand English " +
                "accent, including its characteristic short vowels and relaxed rhythm, with local wording " +
                "where natural. Stay intelligible and never turn it into a caricature."
            )),
        "in" to ("Indian" to (
            "ACCENT: You are from India. Speak with a genuine, natural Indian English accent — " +
                "its characteristic rhythm, syllable timing, and intonation — and use Indian English " +
                "phrasing where natural (e.g. 'do the needful', 'itself', 'only' as emphasis). " +
                "Stay realistic and intelligible, not a caricature."
            )),
        "ph" to ("Filipino" to (
            "ACCENT: You are Filipino. Speak with a genuine, natural Filipino English accent — " +
                "its vowel qualities and melodic intonation — the way an experienced Filipino nurse or " +
                "colleague working abroad would speak. Stay realistic, not a caricature."
            )),
        "sg" to ("Singaporean" to (
            "ACCENT: You are Singaporean. Speak fluent English with a genuine, natural Singaporean " +
                "accent and rhythm, using light local discourse particles only when they fit the character. " +
                "Keep it realistic and intelligible, never a caricature."
            )),
        "ng" to ("Nigerian" to (
            "ACCENT: You are Nigerian. Speak fluent English with a genuine, natural Nigerian English " +
                "accent, including its rhythm and intonation, with local wording only where natural. " +
                "Stay realistic and intelligible, never a caricature."
            )),
        "za" to ("South African" to (
            "ACCENT: You are South African. Speak with a genuine, natural South African English " +
                "accent, including its vowel qualities and rhythm. Keep the delivery grounded and " +
                "intelligible, never a caricature."
            )),
        "es" to ("Spanish-speaking" to (
            "ACCENT: You are a native Spanish speaker speaking fluent English with a noticeable " +
                "Spanish accent — vowel shifts, rolled or tapped r's, Spanish rhythm — like an " +
                "international colleague. Stay realistic and intelligible, not a caricature."
            )),
        "fr" to ("French-speaking" to (
            "ACCENT: You are a fluent English speaker whose first language is French. Use a genuine, " +
                "natural French accent in English — its rhythm, vowels, and intonation — while remaining " +
                "easy enough to converse with. Stay realistic, never a caricature."
            )),
        "de" to ("German-speaking" to (
            "ACCENT: You are a fluent English speaker whose first language is German. Use a genuine, " +
                "natural German accent in English — its consonants, vowels, rhythm, and intonation. " +
                "Stay realistic and intelligible, never a caricature."
            )),
        "ie" to ("Irish" to (
            "ACCENT: You are Irish. Speak with a genuine, natural Irish English accent — its " +
                "distinctive vowels, rhythm, and expressions such as 'grand' where natural. Stay " +
                "realistic and intelligible, never a caricature."
            )),
        "sco" to ("Scottish" to (
            "ACCENT: You are Scottish. Speak with a genuine, natural Scottish English accent — its " +
                "distinctive vowels, consonants, rhythm, and expressions such as 'wee' where natural. " +
                "Stay realistic and intelligible, never a caricature."
            )),
    )

    /** name → (display label, delivery instruction). Ported from prompt_builder.py's SPEECH_CLARITY_STYLES. */
    private val SPEECH_CLARITY_STYLES: Map<String, Pair<String, String>> = mapOf(
        "clear" to ("Clear (textbook)" to ""),
        "natural" to ("Natural everyday" to (
            "DELIVERY: Speak the way real people actually talk, not like a narrator: use natural " +
                "connected speech, contractions, and common reductions ('gonna', 'wanna', 'kinda', " +
                "'lemme'), with occasional fillers ('like', 'you know', 'I mean'). Stay friendly and " +
                "understandable — this is normal casual speech, not a listening test."
            )),
        "soft" to ("Soft-spoken & trailing" to (
            "DELIVERY: Speak like a naturally soft-spoken person in an ordinary shared space. Let some " +
                "sentence endings trail off, use connected speech and light hesitation, and avoid presenter-" +
                "style projection. Remain audible; the challenge is realistic low-energy delivery, not a " +
                "whisper or deliberate obstruction. Repeat more clearly only when the user asks."
            )),
        "dense" to ("Information-dense burst" to (
            "DELIVERY: Put realistic names, times, numbers, choices, or short directions into compact " +
                "speech without pausing unnaturally between each detail. Use connected speech and a normal " +
                "busy-person rhythm. If the user asks for confirmation, repeat or chunk the crucial details " +
                "naturally and let them read the information back."
            )),
        "street" to ("Fast & mumbly (hard mode)" to (
            "DELIVERY (HARD MODE): Speak like a real person in a hurry in a noisy place. Use heavy " +
                "connected speech and reductions ('gonna', 'dunno', 'lemme', 'whaddya', 'y'know'), " +
                "swallow unstressed syllables, trail off mid-thought sometimes, use false starts and " +
                "self-corrections, slang, and quick asides. Do NOT slow down or repeat yourself unless " +
                "the user explicitly asks ('sorry?', 'what was that?', 'could you say that again?') — " +
                "when they do ask, repeat a bit more clearly like a real person would, then go back to " +
                "your natural fast style. Never mention that you are speaking this way on purpose."
            )),
    )

    /** Accent keys in menu order, shared by every accent-aware feature (Survival, Encounter, Listening Lab). */
    val ACCENT_KEYS: List<String> = LISTENING_ACCENTS.keys.toList()

    /** Display label for an accent key (e.g. "uk" -> "British"); falls back to General American. */
    fun accentLabel(accent: String): String =
        LISTENING_ACCENTS[accent]?.first ?: LISTENING_ACCENTS.getValue("us").first

    /**
     * Style instruction for one-shot TTS narration of a fixed line (Listening Lab), reusing the
     * same accent descriptions [appendAccentAndStyle] uses for live conversational prompting —
     * phrased for a narrator reading a line aloud rather than a character in a conversation.
     */
    fun narrationInstruction(accent: String): String {
        val accentPart = LISTENING_ACCENTS[accent]?.second.orEmpty()
        val base = "Narrate the following line naturally and clearly, the way a real person would say it out loud."
        return if (accentPart.isNotEmpty()) "$base $accentPart" else base
    }
}
