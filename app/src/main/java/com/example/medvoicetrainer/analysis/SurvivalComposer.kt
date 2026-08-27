package com.example.medvoicetrainer.analysis

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Composable Survival English scenario assembly.
 *
 * Faithful Kotlin port of `app/analysis/survival_composer.py`. The data files
 * (`survival_situations.json` + the persona/role/goal/twist/user-state banks)
 * define scenario frames and overlays separately; this object combines them into
 * one concrete per-session case dict while keeping the user-facing disclosure
 * separate from AI-private instructions.
 *
 * The pure composition logic (everything except [generateRandomScenario] and the
 * JSON<->Map boundary helpers) operates on plain [Map]/[List] values so it can be
 * unit-tested without `org.json` (this project has no Robolectric — see
 * PORTING_STATUS.md's honesty notes). Only [generateRandomScenario] and the
 * `loadBank`/`mapToJson` helpers touch `org.json`, and they only run on-device.
 *
 * Deliberate scope trim (documented, not silently dropped): Python's
 * `estimate_survival_variant_counts` (a combinatorial headline count) is not
 * ported — the Android Survival screen has no variant-count headline to feed, so
 * porting it would add an unwired function, exactly the dead-code pattern
 * MIGRATION_MASTER.md warns about. The listening-dial fields (accent/style/pace)
 * default to the neutral "us"/"clear" values since the Android Survival mode has
 * no dials yet (same known UI gap tracked on `survival_tab.py`'s row).
 */
object SurvivalComposer {

    /** Lightweight row retained by the Survival picker instead of a full scenario object. */
    data class FrameSummary(
        val id: String,
        val title: String,
        val category: String,
        val contentKey: String,
        val publicBrief: String,
    )

    /**
     * Picker-facing data. The 6.5 MB situation bank is represented only by [frames]; the complete
     * nested map for a situation is loaded on demand by [loadSituation]. Overlay banks are tiny
     * (together under 70 KB) and remain eagerly available for composition.
     */
    class Catalog internal constructor(
        val frames: List<FrameSummary>,
        val rapidFire: List<String>,
        val personas: List<Map<String, Any?>>,
        val roles: List<Map<String, Any?>>,
        val goals: List<Map<String, Any?>>,
        val twists: List<Map<String, Any?>>,
        val userStates: List<Map<String, Any?>>,
    )

    // ── Deterministic-RNG seam (mirrors Python's rng=random parameter) ──────────

    interface Rng {
        /** ~ random.random(): a double in [0, 1). */
        fun nextDouble(): Double
        /** ~ random.choice(seq). */
        fun <T> choice(items: List<T>): T
    }

    class DefaultRng(private val random: Random = Random.Default) : Rng {
        override fun nextDouble(): Double = random.nextDouble()
        override fun <T> choice(items: List<T>): T = items[random.nextInt(items.size)]
    }

    private val DEFAULT_RNG: Rng = DefaultRng()

    // ── Defaults (mirror the module-level DEFAULT_* dicts) ──────────────────────

    val DEFAULT_USER_VARIANT: Map<String, Any?> = mapOf(
        "id" to "open",
        "public_user_context" to "",
        "ai_private" to (
            "Do not assume whether the user is local, affected, knowledgeable, or " +
                "confused. Let the user's first response establish their position and " +
                "adapt naturally."
            ),
    )

    val DEFAULT_PERSONA: Map<String, Any?> = mapOf(
        "id" to "scenario_default",
        "label" to "Scenario default",
        "persona_profile" to "Use the counterpart role exactly as written in the scenario.",
        "speaking_style" to "Natural, concise everyday speech.",
        "behavior" to "React to the user's actual answer and keep the interaction grounded.",
        "compatible_categories" to listOf("*"),
    )

    val DEFAULT_ROLE: Map<String, Any?> = mapOf(
        "id" to "scenario_default_role",
        "label" to "Scenario-defined counterpart",
        "counterpart_role" to "{scenario_role}",
        "role_behavior" to "Use the scenario's concrete counterpart role exactly.",
        "compatible_categories" to listOf("*"),
    )

    val DEFAULT_GOAL: Map<String, Any?> = mapOf(
        "id" to "scenario_followups",
        "label" to "Scenario follow-ups",
        "instruction" to "Let the conversation follow the scenario's natural follow-up directions.",
        "compatible_categories" to listOf("*"),
    )

    val DEFAULT_TWIST: Map<String, Any?> = mapOf(
        "id" to "no_extra_twist",
        "label" to "No extra twist",
        "instruction" to "",
        "compatible_categories" to listOf("*"),
    )

    private val DEFAULT_COMPOSITION_WEIGHTS: Map<String, Double> = mapOf(
        "persona" to 0.25,
        "twist" to 0.15,
        "goal" to 0.35,
        "open_user_state" to 0.60,
    )

    private val GENERAL_PERSONA_IDS: Set<String> = setOf(
        "warm_question_asker", "blunt_kind_solver", "distracted_multitasker",
        "sarcastic_deadpan", "shy_apologetic", "dramatic_low_stakes", "overly_formal",
        "quiet_observer", "jokey_deflector", "embarrassed_recoverer", "detail_clarifier",
        "easily_impressed", "soft_spoken_mumbler",
    )

    private val GOAL_REQUIRED_ANY_TAGS: Map<String, Set<String>> = mapOf(
        "keep_smalltalk_alive" to setOf("social", "peer", "group", "waiting", "campus", "food"),
        "confirm_information" to setOf("numbers", "time", "schedule", "announcement", "directions", "price", "policy", "names"),
        "repair_misunderstanding" to setOf("misunderstanding", "names", "numbers", "unexpected"),
        "joint_problem_solving" to setOf("shared_space", "housing", "travel", "workplace", "service", "unexpected"),
        "polite_refusal_practice" to setOf("boundary", "service", "invitation", "social"),
        "boundary_or_space_negotiation" to setOf("boundary", "shared_space", "housing"),
        "ask_for_or_give_directions" to setOf("directions", "travel", "campus", "hospital"),
        "choose_between_options" to setOf("options", "preference", "ordering", "food", "service"),
        "clarify_price_or_terms" to setOf("price", "money", "policy", "service"),
        "readback_numbers_or_codes" to setOf("numbers", "readback", "phone_call", "time", "price"),
        "correct_wrong_assumption" to setOf("misunderstanding", "names", "unexpected"),
        "make_or_decline_invitation" to setOf("invitation", "social", "group", "peer"),
        "explain_simple_system" to setOf("policy", "service", "ordering", "travel", "campus", "hospital"),
        "recover_from_awkwardness" to setOf("social", "unexpected", "names", "group"),
        "ask_preference" to setOf("preference", "options", "ordering", "food"),
        "apologize_and_fix" to setOf("unexpected", "misunderstanding", "shared_space", "service"),
        "schedule_or_coordinate" to setOf("schedule", "time", "workplace", "campus"),
        "soft_exit_practice" to setOf("social", "boundary", "service", "group"),
    )

    private val TWIST_REQUIRED_ANY_TAGS: Map<String, Set<String>> = mapOf(
        "misheard_detail" to setOf("numbers", "names", "time", "announcement", "directions", "misunderstanding"),
        "wrong_name_or_identity" to setOf("names", "unexpected", "service"),
        "numbers_too_fast" to setOf("numbers", "time", "price", "phone_call", "readback"),
        "app_or_machine_failure" to setOf("service", "travel", "ordering", "housing"),
        "time_pressure" to setOf("time", "schedule", "travel", "ordering", "service", "workplace"),
        "policy_confusion" to setOf("policy", "service", "travel", "housing"),
        "overconfident_wrong" to setOf("misunderstanding", "numbers", "names"),
        "partial_information" to setOf("numbers", "names", "time", "directions", "announcement", "policy"),
        "unexpected_upsell" to setOf("service", "price", "ordering"),
        "tiny_spill_or_mess" to setOf("food", "shared_space", "housing", "workplace"),
        "shared_resource_shortage" to setOf("shared_space", "workplace", "food", "housing"),
        "too_many_options" to setOf("options", "ordering", "preference", "service"),
        "missing_receipt_or_proof" to setOf("proof", "service", "price"),
        "awkward_social_pressure" to setOf("social", "group", "invitation", "boundary"),
        "public_attention" to setOf("social", "group", "unexpected"),
        "spelling_or_pronunciation" to setOf("names", "numbers", "phone_call"),
        "weather_or_delay" to setOf("travel", "schedule", "time"),
        "unclear_ownership" to setOf("shared_space", "housing", "ordering", "unexpected"),
        "harmless_secret_or_surprise" to setOf("social", "invitation", "group", "unexpected"),
        "needs_polite_exit" to setOf("social", "boundary", "service", "group"),
        "culture_or_custom_question" to setOf("social", "food", "housing", "campus"),
    )

    private val RECEPTIVE_AXES: Set<String> = setOf(
        "numbers", "directions", "announcement", "phone_call", "schedule", "time",
        "price", "money", "ordering", "travel", "unexpected", "names",
        "misunderstanding", "policy", "options",
    )

    private val PRODUCTIVE_AXES: Set<String> = setOf(
        "boundary", "negotiation", "social", "preference", "peer",
        "invitation", "group", "service", "shared_space",
    )

    // ── Small typed accessors over the loosely-typed Map<String, Any?> shape ────

    private fun Map<String, Any?>.str(key: String): String = (this[key] as? String) ?: ""

    private fun Any?.asStringList(): List<String> =
        (this as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun Map<String, Any?>.strList(key: String): List<String> = this[key].asStringList()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.mapNotNull { asMap(it) } ?: emptyList()

    private fun asMap(v: Any?): Map<String, Any?>? =
        (v as? Map<*, *>)?.let { m -> m.entries.associate { it.key.toString() to it.value } }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

    // ── Public-brief / surprise-reveal ──────────────────────────────────────────

    fun publicBriefFor(situation: Map<String, Any?>): String = firstNonEmpty(
        situation.str("public_brief"),
        situation.str("setting"),
        "A real-life situation starts around you.",
    )

    fun surpriseRevealFor(situation: Map<String, Any?>): String = firstNonEmpty(
        situation.str("surprise_reveal"),
        situation.str("public_brief"),
        situation.str("setting"),
        "A real-life situation just started.",
    )

    // ── Tag / category compatibility ────────────────────────────────────────────

    private fun settingTags(situation: Map<String, Any?>): Set<String> {
        val tags = LinkedHashSet<String>()
        tags.addAll(situation.strList("setting_tags"))
        tags.addAll(situation.strList("goal_tags"))
        tags.addAll(situation.strList("twist_tags"))
        return tags
    }

    private fun matchesCategory(item: Map<String, Any?>, category: String?): Boolean {
        val compatible = item.strList("compatible_categories").ifEmpty { listOf("*") }
        return "*" in compatible || (category != null && category in compatible)
    }

    private fun matchesTags(item: Map<String, Any?>, situation: Map<String, Any?>): Boolean {
        val tags = settingTags(situation)
        val requiresAny = item.strList("requires_any_tags").toSet()
        if (requiresAny.isNotEmpty() && (tags intersect requiresAny).isEmpty()) return false
        val excludesAny = item.strList("excludes_any_tags").toSet()
        if (excludesAny.isNotEmpty() && (tags intersect excludesAny).isNotEmpty()) return false
        val compatibleTags = item.strList("compatible_tags").toSet()
        if (compatibleTags.isNotEmpty() && (tags intersect compatibleTags).isEmpty()) return false
        return true
    }

    private fun compatible(item: Map<String, Any?>, situation: Map<String, Any?>): Boolean =
        matchesCategory(item, situation["category"] as? String) && matchesTags(item, situation)

    /** Apply semantic requirements for legacy bank rows with no tag metadata. */
    private fun idTagCompatible(
        item: Map<String, Any?>,
        situation: Map<String, Any?>,
        requirements: Map<String, Set<String>>,
    ): Boolean {
        val required = requirements[item.str("id")] ?: emptySet()
        return required.isEmpty() || (required intersect settingTags(situation)).isNotEmpty()
    }

    private fun compositionWeight(situation: Map<String, Any?>, name: String): Double {
        val configured = asMap(situation["composition_weights"]) ?: emptyMap()
        val default = DEFAULT_COMPOSITION_WEIGHTS.getValue(name)
        val raw = configured[name] ?: return default
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: return default
            else -> return default
        }
        return minOf(1.0, maxOf(0.0, value))
    }

    private fun roleVariantMap(situation: Map<String, Any?>): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        when (val variants = situation["role_variants"]) {
            is Map<*, *> -> for ((key, value) in variants) {
                asMap(value)?.let { out[key.toString()] = it }
            }
            is List<*> -> for (value in variants) {
                val m = asMap(value) ?: continue
                val roleId = m["role_id"]?.toString() ?: continue
                if (roleId.isNotEmpty()) out[roleId] = m
            }
        }
        return out
    }

    private fun byId(items: List<Map<String, Any?>>?): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (item in items ?: emptyList()) {
            val id = item["id"] as? String
            if (!id.isNullOrEmpty()) out[id] = item
        }
        return out
    }

    private fun bankCandidates(
        situation: Map<String, Any?>,
        items: List<Map<String, Any?>>?,
        ids: List<String>?,
    ): List<Map<String, Any?>> {
        if (items.isNullOrEmpty()) return emptyList()
        val index = byId(items)
        val pool = if (!ids.isNullOrEmpty()) ids.mapNotNull { index[it] } else index.values.toList()
        return pool.filter { compatible(it, situation) }
    }

    // ── Compatible-bank selectors ───────────────────────────────────────────────

    fun compatiblePersonas(
        situation: Map<String, Any?>,
        personas: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
        if (personas.isNullOrEmpty()) return listOf(DEFAULT_PERSONA)
        val matches = personas.filter { p ->
            compatible(p, situation) && (
                p.str("id") in GENERAL_PERSONA_IDS ||
                    p.strList("compatible_tags").isNotEmpty() ||
                    p.strList("requires_any_tags").isNotEmpty() ||
                    p.strList("excludes_any_tags").isNotEmpty()
                )
        }
        return matches.ifEmpty { listOf(DEFAULT_PERSONA) }
    }

    fun compatibleRoles(
        situation: Map<String, Any?>,
        roles: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
        val index = byId(roles)
        val defaultId = DEFAULT_ROLE.str("id")
        val default = index[defaultId] ?: DEFAULT_ROLE
        val matches = mutableListOf(default)
        for ((roleId, realization) in roleVariantMap(situation)) {
            val item = index[roleId]
            if (item != null && roleId != defaultId && realization.str("opener").isNotEmpty() &&
                compatible(item, situation)
            ) {
                matches.add(item)
            }
        }
        return matches
    }

    fun compatibleGoals(
        situation: Map<String, Any?>,
        goals: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
        val ids = situation.strList("interaction_goal_ids")
        val matches = bankCandidates(situation, goals, ids)
            .filter { idTagCompatible(it, situation, GOAL_REQUIRED_ANY_TAGS) }
        return matches.ifEmpty { listOf(DEFAULT_GOAL) }
    }

    fun compatibleTwists(
        situation: Map<String, Any?>,
        twists: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
        val defaultTwistId = DEFAULT_TWIST.str("id")
        val ids = situation.strList("twist_candidate_ids").ifEmpty { listOf("no_extra_twist") }
        val matches = bankCandidates(situation, twists, ids).filter {
            it.str("id") == defaultTwistId || idTagCompatible(it, situation, TWIST_REQUIRED_ANY_TAGS)
        }
        return matches.ifEmpty { listOf(DEFAULT_TWIST) }
    }

    fun compatibleUserStates(
        situation: Map<String, Any?>,
        userStates: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
        val local = situation.mapList("user_variants")
        if (local.isNotEmpty()) return local
        val ids = situation.strList("user_state_ids")
        val matches = bankCandidates(situation, userStates, ids)
        return matches.ifEmpty { listOf(DEFAULT_USER_VARIANT) }
    }

    // ── Weighted single-axis choice ─────────────────────────────────────────────

    private fun choice(items: List<Map<String, Any?>>, rng: Rng): Map<String, Any?> {
        if (items.isEmpty()) return emptyMap()
        return rng.choice(items)
    }

    private fun chooseUserState(
        situation: Map<String, Any?>,
        candidates: List<Map<String, Any?>>,
        rng: Rng,
    ): Map<String, Any?> {
        if (candidates.isEmpty()) return DEFAULT_USER_VARIANT
        val openStates = candidates.filter { it.str("id") == "open" }
        if (openStates.isNotEmpty() && rng.nextDouble() < compositionWeight(situation, "open_user_state")) {
            return openStates[0]
        }
        val alternatives = candidates.filter { it.str("id") != "open" }
        return choice(alternatives.ifEmpty { candidates }, rng)
    }

    private fun chooseGoal(
        situation: Map<String, Any?>,
        candidates: List<Map<String, Any?>>,
        rng: Rng,
    ): Map<String, Any?> {
        val defaultGoalId = DEFAULT_GOAL.str("id")
        val nonDefault = candidates.filter { it.str("id") != defaultGoalId }
        if (nonDefault.isNotEmpty() && rng.nextDouble() < compositionWeight(situation, "goal")) {
            return choice(nonDefault, rng)
        }
        return DEFAULT_GOAL
    }

    /** Choose at most one persona/twist challenge axis (they are mutually exclusive). */
    private fun chooseSingleOverlay(
        situation: Map<String, Any?>,
        personaCandidates: List<Map<String, Any?>>,
        twistCandidates: List<Map<String, Any?>>,
        rng: Rng,
    ): Triple<Map<String, Any?>, Map<String, Any?>, String> {
        val personas = personaCandidates.filter { it.str("id") != DEFAULT_PERSONA.str("id") }
        val twists = twistCandidates.filter { it.str("id") != DEFAULT_TWIST.str("id") }
        var personaWeight = if (personas.isNotEmpty()) compositionWeight(situation, "persona") else 0.0
        var twistWeight = if (twists.isNotEmpty()) compositionWeight(situation, "twist") else 0.0
        val total = personaWeight + twistWeight
        if (total > 0.95) {
            val scale = 0.95 / total
            personaWeight *= scale
            twistWeight *= scale
        }
        val roll = rng.nextDouble()
        if (roll < personaWeight) return Triple(choice(personas, rng), DEFAULT_TWIST, "persona")
        if (roll < personaWeight + twistWeight) return Triple(DEFAULT_PERSONA, choice(twists, rng), "twist")
        return Triple(DEFAULT_PERSONA, DEFAULT_TWIST, "ordinary")
    }

    private fun renderCounterpartRole(role: Map<String, Any?>, scenarioRole: String): String {
        val template = role.str("counterpart_role").ifEmpty { "{scenario_role}" }
        return template.replace("{scenario_role}", scenarioRole)
    }

    // ── Novelty tracking ────────────────────────────────────────────────────────

    /** Stable key for counting unique Survival combinations already practised. */
    fun survivalVariantKey(case: Map<String, Any?>, includeListening: Boolean = false): String {
        val parts = mutableListOf(
            case.str("id"),
            firstNonEmpty(case.str("ai_role_id"), case.str("role_id")),
            firstNonEmpty(case.str("ai_persona_id"), case.str("persona_id")),
            firstNonEmpty(case.str("user_state_id"), case.str("user_variant_id")),
            case.str("interaction_goal_id"),
            case.str("twist_id"),
        )
        if (includeListening) {
            parts.add(case.str("listening_accent"))
            parts.add(case.str("speech_style"))
        }
        return parts.joinToString("|")
    }

    /**
     * `survival_situations.json` pads its situation count with batches of ~20 near-identical
     * frames — same opener/title, only the id's numeric suffix differs — to give the composer
     * enough distinct ids for its practice-count bookkeeping. A plain uniform draw over rows
     * would land in one of those batches roughly 20x more often than a frame that only appears
     * once, which reads to a learner as "random situation keeps giving me the same handful of
     * things". Grouping by this key before the final random pick weights by unique content
     * instead of by row count, without needing to touch the shared data file.
     */
    private fun situationContentKey(situation: Map<String, Any?>): String =
        situation.str("opener").ifBlank { situation.str("title") }

    /**
     * Choose a least-practised scenario frame, breaking ties randomly. Keeps
     * selection unpredictable while giving unseen situations priority.
     * [excludeIds] is best-effort: if it would empty the pool, the original pool
     * is used.
     */
    fun chooseNovelSituation(
        situations: List<Map<String, Any?>>,
        practiceCounts: Map<String, Int>? = null,
        excludeIds: Set<String>? = null,
        preferredTags: Set<String>? = null,
        rng: Rng = DEFAULT_RNG,
    ): Map<String, Any?> {
        // Hangout frames are opt-in only (picked explicitly via the "Hangouts & Long Talks"
        // category in the Survival tab) — a dashboard quick-start random pick should not
        // surprise the user with a 10-20 minute session instead of the usual short one.
        var pool = situations.filter { it.str("scenario_type") !in setOf("rapid_fire", "hangout") }
        if (pool.isEmpty()) return emptyMap()

        val excluded = excludeIds ?: emptySet()
        val withoutRecent = pool.filter { it.str("id") !in excluded }
        if (withoutRecent.isNotEmpty()) pool = withoutRecent

        val counts = practiceCounts ?: emptyMap()
        val lowestCount = pool.minOf { counts[it.str("id")] ?: 0 }
        var leastPractised = pool.filter { (counts[it.str("id")] ?: 0) == lowestCount }
        val wanted = preferredTags ?: emptySet()
        if (wanted.isNotEmpty()) {
            val focused = leastPractised.filter { (settingTags(it) intersect wanted).isNotEmpty() }
            if (focused.isNotEmpty()) leastPractised = focused
        }
        val groups = leastPractised.groupBy { situationContentKey(it) }.values.toList()
        return rng.choice(rng.choice(groups))
    }

    // ── Compose ─────────────────────────────────────────────────────────────────

    /**
     * Build a concrete Survival English scenario for one session. The returned
     * map is safe to persist as `raw_case_json` and preserves legacy keys while
     * adding the exact persona/user-variant choices used this time.
     */
    fun composeSurvivalSituation(
        situation: Map<String, Any?>,
        personas: List<Map<String, Any?>>? = null,
        roles: List<Map<String, Any?>>? = null,
        goals: List<Map<String, Any?>>? = null,
        twists: List<Map<String, Any?>>? = null,
        userStates: List<Map<String, Any?>>? = null,
        rng: Rng = DEFAULT_RNG,
    ): Map<String, Any?> {
        if (situation.str("scenario_type") == "rapid_fire") return LinkedHashMap(situation)

        val composed = withDefaultKeyExpressions(situation)
        val roleChoice = choice(compatibleRoles(situation, roles), rng)
        val role = if (roleChoice.isEmpty()) DEFAULT_ROLE else roleChoice
        val goal = chooseGoal(situation, compatibleGoals(situation, goals), rng)
        val variant = chooseUserState(situation, compatibleUserStates(situation, userStates), rng)
        val (persona, twist, challengeAxis) = chooseSingleOverlay(
            situation,
            compatiblePersonas(situation, personas),
            compatibleTwists(situation, twists),
            rng,
        )

        // Backward-compatible scenario role: older data used `persona` for the
        // whole counterpart; new data uses it as the role, then overlays a personality.
        val scenarioRole = firstNonEmpty(
            situation.str("counterpart_role"),
            situation.str("persona"),
            "a person in this real-life scene",
        )
        val defaultRoleId = DEFAULT_ROLE.str("id")
        val roleVariant = roleVariantMap(situation)[role.str("id")] ?: emptyMap()
        val counterpartRole = roleVariant.str("counterpart_role")
            .ifEmpty { renderCounterpartRole(role, scenarioRole) }
        if (role.str("id") != defaultRoleId) {
            composed["opener"] = roleVariant["opener"]
            if (roleVariant.strList("followups").isNotEmpty()) {
                composed["followups"] = roleVariant.strList("followups")
            }
            if (roleVariant.str("public_brief").isNotEmpty()) {
                composed["public_brief"] = roleVariant["public_brief"]
            }
        }

        composed["counterpart_role"] = counterpartRole
        composed["scenario_counterpart_role"] = scenarioRole
        composed["public_brief"] = roleVariant.str("public_brief").ifEmpty { publicBriefFor(situation) }
        composed["surprise_reveal"] = roleVariant.str("surprise_reveal").ifEmpty { surpriseRevealFor(situation) }
        if (!composed.containsKey("hidden_scene")) composed["hidden_scene"] = ""
        if (!composed.containsKey("ai_private")) composed["ai_private"] = situation.str("spice")
        composed["composition_schema_version"] = 4
        composed["composition_challenge_axis"] = challengeAxis
        // Stable ordering is part of the persisted realization/hash contract.
        composed["setting_tags"] = settingTags(situation).sorted()

        composed["role_id"] = (role["id"] as? String) ?: defaultRoleId
        composed["role_label"] = (role["label"] as? String) ?: DEFAULT_ROLE.str("label")
        composed["role_behavior"] = role.str("role_behavior")

        composed["persona_id"] = (persona["id"] as? String) ?: DEFAULT_PERSONA.str("id")
        composed["persona_label"] = (persona["label"] as? String) ?: DEFAULT_PERSONA.str("label")
        composed["persona_profile"] = persona.str("persona_profile")
        composed["persona_speaking_style"] = persona.str("speaking_style")
        composed["persona_behavior"] = persona.str("behavior")

        val userStateId = (variant["id"] as? String) ?: DEFAULT_USER_VARIANT.str("id")
        composed["user_state_id"] = userStateId
        composed["user_state_label"] = (variant["label"] as? String) ?: userStateId
        // Keep legacy name for older analysis/session code.
        composed["user_variant_id"] = userStateId
        composed["public_user_context"] = variant.str("public_user_context")
        composed["user_variant_private"] = variant.str("ai_private")

        composed["interaction_goal_id"] = (goal["id"] as? String) ?: DEFAULT_GOAL.str("id")
        composed["interaction_goal_label"] = (goal["label"] as? String) ?: DEFAULT_GOAL.str("label")
        composed["interaction_goal_instruction"] = goal.str("instruction")
        val goalFollowups = goal.strList("followups")
        if (goalFollowups.isNotEmpty()) {
            composed["followups"] = composed["followups"].asStringList() + goalFollowups
        }

        composed["twist_id"] = (twist["id"] as? String) ?: DEFAULT_TWIST.str("id")
        composed["twist_label"] = (twist["label"] as? String) ?: DEFAULT_TWIST.str("label")
        composed["twist_instruction"] = twist.str("instruction")

        val tags = composed["setting_tags"].asStringList().toSet()
        composed["receptive_skill_tags"] = (tags intersect RECEPTIVE_AXES).sorted()
        val goalId = (composed["interaction_goal_id"] as? String)?.ifEmpty { "conversation" } ?: "conversation"
        val productive = LinkedHashSet<String>()
        productive.add(goalId)
        productive.addAll(tags.filter { it in PRODUCTIVE_AXES })
        composed["productive_skill_tags"] = productive.sorted()

        val errors = validateSurvivalRealization(situation, composed)
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Invalid Survival realization for ${situation["id"] ?: "?"}: " + errors.joinToString("; "),
            )
        }
        return composed
    }

    /**
     * Keep the source catalogue compact while guaranteeing the same three
     * situation-aware expressions in both the pre-start preview and the composed
     * session. Explicitly authored lines remain the source of truth.
     */
    private fun withDefaultKeyExpressions(situation: Map<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(situation).also { result ->
            if ((result["key_expressions"] as? List<*>)?.isNotEmpty() != true) {
                result["key_expressions"] = defaultKeyExpressions(situation)
            }
        }

    private fun defaultKeyExpressions(situation: Map<String, Any?>): List<Map<String, Any?>> {
        val title = situation.str("title").ifEmpty { "this situation" }
        val category = situation.str("category")
        val register = if (category in setOf("Shops & Services", "Hospital Hallway")) "polite" else "casual"
        val lines = when (category) {
            "Travel & Transit" -> listOf(
                "Could you help me make sure I’ve got the details right?" to "Confirm the travel detail before acting",
                "Where should I go from here?" to "Ask for the next step",
                "Okay, thanks — I didn’t want to guess." to "Acknowledge the help and avoid guessing",
            )
            "Food & Drink" -> listOf(
                "Could you tell me what you’d recommend?" to "Ask for a recommendation",
                "Sorry, could we check the order one more time?" to "Confirm an order politely",
                "That sounds good — I’ll go with that, please." to "Make a clear choice",
            )
            "Shops & Services" -> listOf(
                "Hi, I need a little help with this." to "Open a service request politely",
                "Could you tell me what my options are?" to "Ask about available options",
                "That works for me. Thanks for sorting it out." to "Accept the solution warmly",
            )
            "Housing & Daily Life" -> listOf(
                "Hey, do you have a minute to talk about this?" to "Open a practical conversation politely",
                "What do you think we should do?" to "Work out a shared solution",
                "Thanks — I appreciate you working this out with me." to "Close a shared problem-solving conversation",
            )
            "Campus & Lab" -> listOf(
                "Hey, are you here for this too?" to "Open with the shared campus situation",
                "I wasn’t quite sure about that — what do you think?" to "Ask a classmate or labmate for their view",
                "Anyway, good luck with the rest of your day." to "Close the exchange naturally",
            )
            "Parties & Social" -> listOf(
                "How do you know everyone here?" to "Open a social conversation",
                "That sounds fun — how did you get into it?" to "Keep the other person talking",
                "Nice talking with you. I’m going to grab a drink, but I’ll see you around." to "Leave a social chat warmly",
            )
            "Hospital Hallway" -> listOf(
                "Do you know where I should go for that?" to "Ask for practical direction",
                "Could you point me in the right direction?" to "Ask for help without overexplaining",
                "Thanks — that really helps." to "Acknowledge quick help",
            )
            "Hangouts & Long Talks" -> listOf(
                "That sounds like a good plan. I’m in." to "Join a relaxed plan",
                "Tell me more — how did that happen?" to "Keep a longer conversation going",
                "I should probably get going soon, but this was nice." to "Leave a hangout warmly",
            )
            "Dating & Romance" -> listOf(
                "I’d be up for that — what did you have in mind?" to "Show interest while asking for a clear plan",
                "That sounds fun. I’d like to get to know you better." to "Respond warmly and naturally",
                "No pressure at all — whatever feels comfortable for you." to "Keep the interaction respectful and low-pressure",
            )
            else -> listOf(
                "Sorry, I wasn’t expecting that — what happened?" to "React calmly to a surprise",
                "Are you okay? Is there anything I can do?" to "Check on the other person and offer help",
                "Glad we got that sorted out." to "Close an unexpected interaction positively",
            )
        }
        return lines.map { (english, function) ->
            mapOf(
                "en" to english,
                "function" to function,
                "gloss" to mapOf(
                    "en" to function,
                    "ko" to "$title 상황에서 자연스럽게 쓰는 표현",
                ),
                "register" to register,
                "why" to "Written for the ${category.ifEmpty { "everyday" }} scene: $title.",
                "keywords" to english.replace("—", " ").split(" ")
                    .map { it.lowercase().trim(',', '.', '?') }
                    .filter { it.length >= 4 },
            )
        }
    }

    /** Return structural/semantic invariant violations for one realization. */
    fun validateSurvivalRealization(
        situation: Map<String, Any?>,
        composed: Map<String, Any?>,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (composed.str("opener").isBlank()) errors.add("missing opener")
        if (composed.str("counterpart_role").isBlank()) errors.add("missing counterpart role")
        if (composed.str("public_brief").isBlank()) errors.add("missing public brief")

        val defaultRoleId = DEFAULT_ROLE.str("id")
        val roleId = composed.str("role_id").ifEmpty { defaultRoleId }
        if (roleId != defaultRoleId) {
            val roleVariant = roleVariantMap(situation)[roleId]
            if (roleVariant == null || roleVariant.str("opener").isEmpty()) {
                errors.add("alternate role has no role-specific opener")
            } else if (composed["opener"] != roleVariant["opener"]) {
                errors.add("alternate role inherited the wrong opener")
            }
        }

        val personaId = composed.str("persona_id").ifEmpty { DEFAULT_PERSONA.str("id") }
        val twistId = composed.str("twist_id").ifEmpty { DEFAULT_TWIST.str("id") }
        if (personaId != DEFAULT_PERSONA.str("id") && twistId != DEFAULT_TWIST.str("id")) {
            errors.add("persona and twist challenge axes were stacked")
        }

        val tags = settingTags(situation)
        val goalRequired = GOAL_REQUIRED_ANY_TAGS[composed.str("interaction_goal_id")] ?: emptySet()
        if (goalRequired.isNotEmpty() && (tags intersect goalRequired).isEmpty()) {
            errors.add("interaction goal is incompatible with frame tags")
        }
        val twistRequired = TWIST_REQUIRED_ANY_TAGS[twistId] ?: emptySet()
        if (twistRequired.isNotEmpty() && (tags intersect twistRequired).isEmpty()) {
            errors.add("twist is incompatible with frame tags")
        }

        val localStates = situation.mapList("user_variants")
            .mapNotNull { it["id"] as? String }
            .filter { it.isNotEmpty() }
            .toSet()
        if (localStates.isNotEmpty() && composed.str("user_state_id") !in localStates) {
            errors.add("user state is not defined for this frame")
        }
        return errors
    }

    /**
     * Compose an unseen variant when possible, with a bounded fallback. The
     * combinatorial bank is intentionally huge, so a few cheap samples avoid
     * accidental repeats; the bounded loop keeps this safe even for a tiny bank.
     */
    fun composeNovelSurvivalSituation(
        situation: Map<String, Any?>,
        personas: List<Map<String, Any?>>? = null,
        roles: List<Map<String, Any?>>? = null,
        goals: List<Map<String, Any?>>? = null,
        twists: List<Map<String, Any?>>? = null,
        userStates: List<Map<String, Any?>>? = null,
        seenVariantKeys: Set<String>? = null,
        attempts: Int = 32,
        rng: Rng = DEFAULT_RNG,
    ): Map<String, Any?> {
        if (situation.str("scenario_type") == "rapid_fire") return LinkedHashMap(situation)

        val seen = seenVariantKeys ?: emptySet()
        var fallback: Map<String, Any?> = emptyMap()
        repeat(maxOf(1, attempts)) {
            val candidate = composeSurvivalSituation(
                situation, personas, roles, goals, twists, userStates, rng,
            )
            fallback = candidate
            if (survivalVariantKey(candidate) !in seen) return candidate
        }
        return fallback
    }

    // ── On-device entry point (asset load → compose → case JSON) ────────────────

    /**
     * Load the Survival banks from assets, pick a fresh scenario frame, compose a
     * novel realization, and return the concrete per-session case as a JSON string.
     *
     * Mirrors `app/ui/survival_tab.py`'s `_build_system_prompt` case-dict assembly
     * (minus the accent/style/pace listening dials, which the Android Survival mode
     * has no UI for yet — they default to the neutral "us"/"clear" values). The
     * result carries every field the deterministic mock client and the analysis
     * layer read (`opener`, `followups`, `question_samples`, `scenario_type`,
     * `counterpart_role`, persona/goal/twist fields, …).
     */
    fun generateRandomScenario(context: Context, excludedCategories: Set<String> = emptySet()): String {
        val banks = banks(context)
        val eligibleSituations = banks.situations.filterNot {
            it.str("category") in excludedCategories
        }.ifEmpty { banks.situations }
        if (eligibleSituations.isEmpty()) return "{}"

        val frame = chooseNovelSituation(eligibleSituations)
        val situation = composeNovelSurvivalSituation(
            frame,
            personas = banks.personas,
            roles = banks.roles,
            goals = banks.goals,
            twists = banks.twists,
            userStates = banks.userStates,
        )

        val accent = "us"
        val style = "clear"
        val case = buildCaseDict(situation, accent, style)
        return (mapToJson(case) as JSONObject).toString()
    }

    /** Assemble the persisted per-session case from a composed situation. */
    private fun buildCaseDict(
        situation: Map<String, Any?>,
        accent: String,
        style: String,
    ): Map<String, Any?> {
        val id = situation.str("id").ifEmpty { "survival_rapid_fire" }
        val realizationHash = sha256Prefix(stableJson(situation), 16)
        val case = LinkedHashMap<String, Any?>()
        case["id"] = id
        case["patient_name"] = situation.str("title").ifEmpty { "Everyday Reaction Sprint" }
        case["survival_mode"] = true
        case["scenario_type"] = situation.str("scenario_type").ifEmpty { "situation" }
        case["category"] = situation["category"]
        case["setting"] = publicBriefFor(situation)
        case["public_brief"] = publicBriefFor(situation)
        case["surprise_reveal"] = surpriseRevealFor(situation)
        // Persist every field that materially shaped the live prompt so the
        // deterministic keyless client gets the same opener/samples the backend saw.
        case["opener"] = situation["opener"]
        case["followups"] = situation.strList("followups")
        case["spice"] = situation["spice"]
        case["question_samples"] = situation.strList("question_samples")
        case["public_user_context"] = situation["public_user_context"]
        // Scene-specific model expressions (EverydayPhrasebook.SCENE_FIELD). Carried through
        // composition because the phrasebook is built from the persisted case, not from the bank:
        // dropping it here would leave every composed Survival scene with only generic lines.
        case["key_expressions"] = situation["key_expressions"]
        case["hidden_scene"] = situation["hidden_scene"]
        case["ai_private"] = situation["ai_private"]
        case["counterpart_role"] = situation["counterpart_role"]
        case["scenario_counterpart_role"] = situation["scenario_counterpart_role"]
        case["ai_role_id"] = situation["role_id"]
        case["ai_role_label"] = situation["role_label"]
        case["role_behavior"] = situation["role_behavior"]
        case["user_variant_id"] = situation["user_variant_id"]
        case["user_state_id"] = situation["user_state_id"]
        case["user_state_label"] = situation["user_state_label"]
        case["user_variant_private"] = situation["user_variant_private"]
        case["interaction_goal_id"] = situation["interaction_goal_id"]
        case["interaction_goal_label"] = situation["interaction_goal_label"]
        case["interaction_goal_instruction"] = situation["interaction_goal_instruction"]
        case["twist_id"] = situation["twist_id"]
        case["twist_label"] = situation["twist_label"]
        case["twist_instruction"] = situation["twist_instruction"]
        case["ai_persona_id"] = situation["persona_id"]
        case["ai_persona_label"] = situation["persona_label"]
        case["persona_profile"] = situation["persona_profile"]
        case["persona_speaking_style"] = situation["persona_speaking_style"]
        case["persona_behavior"] = situation["persona_behavior"]
        case["composition_schema_version"] = situation["composition_schema_version"]
        case["composition_challenge_axis"] = situation["composition_challenge_axis"]
        case["realization_hash"] = realizationHash
        case["setting_tags"] = situation.strList("setting_tags")
        case["receptive_skill_tags"] = situation.strList("receptive_skill_tags")
        case["productive_skill_tags"] = situation.strList("productive_skill_tags")
        case["listening_accent"] = accent
        case["speech_style"] = style
        // The AI always opens in this tab — like real life, where the situation happens TO you.
        case["kickoff_text"] = (
            "(You are already in the scene together. Open the conversation now with your " +
                "opening line, exactly as your instructions describe.)"
            )
        return case
    }

    // ── JSON <-> Map boundary (org.json — on-device only) ───────────────────────

    /**
     * The whole Survival axis-bank set (`survival_situations.json` + the five overlay banks),
     * deep-parsed into the loosely-typed maps every function here operates on.
     *
     * This is the single owner of that parse for the entire process. `survival_situations.json`
     * alone is ~6.5 MB / 2,660 situations, and deep-mapping it dominates both CPU and heap: it
     * used to be parsed twice over, once here per [generateRandomScenario] call (uncached, on
     * whichever thread `MainViewModel.startSessionInternal` ran on — i.e. the main thread) and
     * again by the Survival picker's own cache in PracticeScreen, so a learner who opened the
     * Survival tab and started a scenario paid for the parse twice and held two full copies.
     */
    class Banks internal constructor(
        val situations: List<Map<String, Any?>>,
        val rapidFire: List<String>,
        val personas: List<Map<String, Any?>>,
        val roles: List<Map<String, Any?>>,
        val goals: List<Map<String, Any?>>,
        val twists: List<Map<String, Any?>>,
        val userStates: List<Map<String, Any?>>,
    )

    // SoftReference so a memory-constrained device can reclaim the banks and pay for one re-parse
    // rather than being pushed toward an OOM by cached content the user may be done with.
    private var cachedBanks: java.lang.ref.SoftReference<Banks>? = null

    // The catalog is small enough to retain strongly. Keeping it avoids rescanning the 6.5 MB
    // asset after Android clears a SoftReference while the app is in the background.
    @Volatile
    private var cachedCatalog: Catalog? = null

    /**
     * Load the Survival picker catalog without materializing all 2,660 nested situation maps.
     * JsonReader keeps only one scalar token in memory and [loadFrameSummaries] retains five short
     * strings per row, avoiding the old String + JSONObject tree + deep Map tree peak.
     */
    @Synchronized
    fun catalog(context: Context): Catalog {
        cachedCatalog?.let { return it }
        val (frames, rapidFire) = loadFrameSummaries(context)
        val parsed = Catalog(
            frames = frames,
            rapidFire = rapidFire,
            personas = loadBank(context, "survival_personas.json", "personas"),
            roles = loadBank(context, "survival_roles.json", "roles"),
            goals = loadBank(context, "survival_interaction_goals.json", "goals"),
            twists = loadBank(context, "survival_twists.json", "twists"),
            userStates = loadBank(context, "survival_user_states.json", "user_states"),
        )
        if (frames.isNotEmpty()) cachedCatalog = parsed
        return parsed
    }

    /** Load exactly one complete situation map when the learner starts it. */
    fun loadSituation(context: Context, situationId: String): Map<String, Any?>? {
        if (situationId.isBlank()) return null
        return try {
            context.assets.open("survival_situations.json").bufferedReader().use { input ->
                JsonReader(input).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "situations" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    readSituationIfMatching(reader, situationId)?.let {
                                        return withDefaultKeyExpressions(it)
                                    }
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFrameSummaries(context: Context): Pair<List<FrameSummary>, List<String>> {
        return try {
            val frames = ArrayList<FrameSummary>(2_700)
            val rapidFire = ArrayList<String>(48)
            context.assets.open("survival_situations.json").bufferedReader().use { input ->
                JsonReader(input).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "situations" -> {
                                reader.beginArray()
                                while (reader.hasNext()) frames.add(readFrameSummary(reader))
                                reader.endArray()
                            }
                            "rapid_fire_questions" -> {
                                reader.beginArray()
                                while (reader.hasNext()) rapidFire.add(reader.nextString())
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            frames.filter { it.id.isNotBlank() } to rapidFire
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList<FrameSummary>() to emptyList()
        }
    }

    private fun readFrameSummary(reader: JsonReader): FrameSummary {
        var id = ""
        var title = ""
        var category = ""
        var opener = ""
        var publicBrief = ""
        var setting = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "title" -> title = reader.nextString()
                "category" -> category = reader.nextString()
                "opener" -> opener = reader.nextString()
                "public_brief" -> publicBrief = reader.nextString()
                "setting" -> setting = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return FrameSummary(
            id = id,
            title = title,
            category = category,
            contentKey = opener.ifBlank { title },
            publicBrief = publicBrief.ifBlank { setting }.ifBlank { "A real-life situation starts around you." },
        )
    }

    /**
     * The source bank writes `id` first. Until it is seen we preserve any leading fields for
     * schema robustness; after a non-match is known, the remainder is skipped without allocating.
     */
    private fun readSituationIfMatching(reader: JsonReader, targetId: String): Map<String, Any?>? {
        val result = LinkedHashMap<String, Any?>()
        var idSeen = false
        var matches = false
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "id") {
                val id = reader.nextString()
                idSeen = true
                matches = id == targetId
                if (matches) result[name] = id else result.clear()
            } else if (!idSeen || matches) {
                result[name] = readJsonValue(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return result.takeIf { matches }
    }

    private fun readJsonValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> LinkedHashMap<String, Any?>().also { out ->
            reader.beginObject()
            while (reader.hasNext()) out[reader.nextName()] = readJsonValue(reader)
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> ArrayList<Any?>().also { out ->
            reader.beginArray()
            while (reader.hasNext()) out.add(readJsonValue(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { raw ->
            raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull().let { null }
        else -> reader.skipValue().let { null }
    }

    /**
     * The parsed banks, reading and parsing them only on the first call (or the first call after
     * the cache was reclaimed). Blocking and multi-second on a cold cache — call it off the main
     * thread where the caller has that choice (PracticeScreen loads on `Dispatchers.IO`).
     */
    @Synchronized
    fun banks(context: Context): Banks {
        cachedBanks?.get()?.let { return it }
        val (situations, rapidFire) = loadSituations(context)
        val parsed = Banks(
            situations = situations,
            rapidFire = rapidFire,
            personas = loadBank(context, "survival_personas.json", "personas"),
            roles = loadBank(context, "survival_roles.json", "roles"),
            goals = loadBank(context, "survival_interaction_goals.json", "goals"),
            twists = loadBank(context, "survival_twists.json", "twists"),
            userStates = loadBank(context, "survival_user_states.json", "user_states"),
        )
        // An empty situations list means the asset read failed. Don't cache that permanently —
        // a later attempt (e.g. after a transient failure) should still be able to succeed.
        if (situations.isNotEmpty()) cachedBanks = java.lang.ref.SoftReference(parsed)
        return parsed
    }

    private fun loadSituations(context: Context): Pair<List<Map<String, Any?>>, List<String>> {
        return try {
            val text = context.assets.open("survival_situations.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val situations = root.optJSONArray("situations")
                ?.let { jsonArrayToList(it).mapNotNull { v -> asMap(v) } } ?: emptyList()
            val rapidFire = root.optJSONArray("rapid_fire_questions")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            situations to rapidFire
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList<Map<String, Any?>>() to emptyList<String>()
        }
    }

    private fun loadBank(context: Context, filename: String, key: String): List<Map<String, Any?>> {
        return try {
            val text = context.assets.open(filename).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            root.optJSONArray(key)?.let { jsonArrayToList(it).mapNotNull { v -> asMap(v) } } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    private fun jsonToValue(v: Any?): Any? = when (v) {
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        JSONObject.NULL -> null
        else -> v
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) out[key] = jsonToValue(obj.get(key))
        return out
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) out.add(jsonToValue(arr.get(i)))
        return out
    }

    private fun mapToJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().apply {
            for ((k, v) in value) put(k.toString(), mapToJson(v))
        }
        is List<*> -> JSONArray().apply { for (v in value) put(mapToJson(v)) }
        null -> JSONObject.NULL
        else -> value
    }

    // ── Realization hash (stable id, need not byte-match Python) ─────────────────

    private fun stableJson(value: Any?): String = when (value) {
        is Map<*, *> -> value.entries
            .map { it.key.toString() to it.value }
            .sortedBy { it.first }
            .joinToString(",", "{", "}") { "\"${it.first}\":${stableJson(it.second)}" }
        is List<*> -> value.joinToString(",", "[", "]") { stableJson(it) }
        is String -> "\"$value\""
        null -> "null"
        else -> value.toString()
    }

    private fun sha256Prefix(input: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }
}
