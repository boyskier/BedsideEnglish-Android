package com.example.medvoicetrainer.ui.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One normalized interview scenario ready to be rendered as its own practice card. */
internal data class InterviewScenarioOption(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val scenarioJson: String,
)

/**
 * Interview asset files are banks, not runnable cases. Flatten a bank and make every scenario
 * self-contained so PromptBuilder never silently falls back to the first scenario in the file.
 */
internal fun flattenInterviewBank(
    bankJson: String,
    fallbackBankId: String,
): List<InterviewScenarioOption> {
    val root = runCatching { Json.parseToJsonElement(bankJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val scenarios = root["scenarios"] as? JsonArray ?: return emptyList()
    val bankCategory = fallbackBankId.removeSuffix(".json").ifBlank { "interview" }

    return scenarios.mapIndexedNotNull { index, element ->
        val raw = element as? JsonObject ?: return@mapIndexedNotNull null
        fun text(key: String): String = raw[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val id = text("id").ifBlank { "${bankCategory}_${index + 1}" }
        val openingQuestion = text("opening_question")
            .ifBlank { "Tell me about yourself." }
        val title = text("title")
            .ifBlank { text("topic") }
            .ifBlank { openingQuestion }
            .ifBlank { id }
        val category = text("category").ifBlank { bankCategory }
        val pd = text("pd").ifBlank { text("pd_name") }.ifBlank { "Interviewer" }
        val program = text("program").ifBlank { "Interview practice" }
        val evalTemplate = text("eval_template").ifBlank { "residency_interview" }
        val followUps = raw["follow_up_pool"] as? JsonArray ?: JsonArray(emptyList())

        val normalized = buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("category", JsonPrimitive(category))
            put("opening_question", JsonPrimitive(openingQuestion))
            put("follow_up_pool", followUps)
            put("pd", JsonPrimitive(pd))
            put("pd_name", JsonPrimitive(pd))
            put("program", JsonPrimitive(program))
            put("eval_template", JsonPrimitive(evalTemplate))
        }

        InterviewScenarioOption(
            id = id,
            title = title,
            description = "$pd | $program | $category",
            category = category,
            scenarioJson = normalized.toString(),
        )
    }
}

internal data class SurvivalSessionOptions(
    val surpriseMode: Boolean,
    val rapidFire: Boolean,
    val earOnly: Boolean,
    val accent: String,
    val speechStyle: String,
    val playbackSpeed: Float,
    // "male" / "female" / "" (random across both genders). Lets the learner pick the
    // counterpart's voice gender for gender-sensitive scenarios (e.g. Dating & Romance), since a
    // fully random voice can otherwise land on an unwanted pairing there (see VoiceCatalog).
    val voiceGenderPreference: String = "",
)

/** Add the PC Survival controls to a concrete realization without discarding composer metadata. */
internal fun applySurvivalSessionOptions(
    realization: Map<String, Any?>,
    options: SurvivalSessionOptions,
): Map<String, Any?> {
    val result = LinkedHashMap(realization)
    val title = result["title"]?.toString().orEmpty().ifBlank { "Everyday Reaction Sprint" }
    val speed = options.playbackSpeed.coerceIn(0.5f, 2.5f)

    result["id"] = result["id"]?.toString().orEmpty().ifBlank { "survival_practice" }
    result["title"] = title
    result["patient_name"] = title
    result["case_name"] = title
    result["survival_mode"] = true
    result["analysis_domain"] = "everyday"
    result["eval_template"] = "survival"
    result["surprise_mode"] = options.surpriseMode
    result["reveal_context_after_first_ai_turn"] = options.surpriseMode
    result["public_brief_visible_before_start"] = !options.surpriseMode
    result["rapid_fire"] = options.rapidFire
    if (options.rapidFire) result["scenario_type"] = "rapid_fire"
    result["ear_only"] = options.earOnly
    result["listening_accent"] = options.accent
    result["speech_style"] = options.speechStyle
    result["voice_gender_preference"] = options.voiceGenderPreference
    result["playback_speed"] = speed
    result["requested_playback_speed"] = speed
    result["ai_speaking_pace"] = when {
        speed < 0.9f -> "slow"
        speed > 1.5f -> "challenge"
        speed > 1.15f -> "fast"
        else -> "natural"
    }
    result["kickoff_text"] = if (options.rapidFire) {
        "Start the reaction sprint now with the first question."
    } else {
        "(You are already in the scene together. Open the conversation now with your opening line.)"
    }
    return result
}

/**
 * Mark a composed Survival case as a "Survival English — Advanced Beta" session and attach the
 * scene-transition menu for its category (see docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md).
 *
 * Additive by design: the DB `mode` stays `"survival"` and `eval_template` stays `"survival"`, so
 * History filtering, the eval pipeline, and every existing saved session are unaffected — a beta
 * session is an ordinary Survival session carrying two extra case fields. The menu is resolved
 * here, at the entry point, because PromptBuilder is deliberately Context-free and cannot read
 * assets itself. A rapid-fire sprint has no scene to move between, so it is left alone.
 */
internal fun applyAdvancedBetaOptions(
    case: Map<String, Any?>,
    sceneTransitionMenuFor: (String) -> String,
): Map<String, Any?> {
    if (case["scenario_type"]?.toString() == "rapid_fire") return case
    val result = LinkedHashMap(case)
    result["advanced_beta"] = true
    result["scene_transition_menu"] = sceneTransitionMenuFor(result["category"]?.toString().orEmpty())
    return result
}

/** Build the scenario shape already recognized by SurvivalComposer and PromptBuilder. */
internal fun rapidFireSurvivalFrame(questions: List<String>): Map<String, Any?> {
    val samples = questions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
    return linkedMapOf(
        "id" to "survival_rapid_fire",
        "title" to "Everyday Reaction Sprint",
        "category" to "Rapid fire",
        "scenario_type" to "rapid_fire",
        "question_samples" to samples,
        "opener" to samples.firstOrNull().orEmpty(),
        "followups" to samples.drop(1),
        "counterpart_role" to "a succession of ordinary people in quick real-life moments",
        "public_brief" to "Respond quickly and naturally to short everyday prompts.",
        "surprise_reveal" to "A rapid sequence of everyday prompts has started.",
    )
}
