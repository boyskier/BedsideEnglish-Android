package com.example.medvoicetrainer.analysis

import android.content.Context

/**
 * Ported from app/analysis/phase_tracker.py — deterministic interview-phase tracker backed by
 * the shared coverage rules ([CoverageTracker] / [InterviewRules]).
 *
 * Any confidently recognised later-phase intent advances [currentPhase] immediately. Earlier
 * phases retain their own evidence state, so a jump to medications does not falsely claim that
 * initiation/HPI/PMH were detected.
 */

data class PhaseSubitem(val kw: List<String>, val hint: String)
data class PhaseDef(val name: String, val keywords: List<String> = emptyList(), val hints: List<String> = emptyList(), val subitems: Map<String, PhaseSubitem> = emptyMap())
data class PhaseProgress(val status: String, val covered: Int, val total: Int, val complete: Boolean)

/**
 * [domains] is passed in rather than loaded here so the tracker can be driven from a plain JVM
 * test (ledger LOG-41). Loading `history_domains.json` needs a [Context], and needing one for a
 * class that is otherwise pure arithmetic over strings is what kept this module out of the
 * golden suite -- the one exclusion there whose stated reason was "equivalence with the desktop
 * is NOT currently proven" rather than "there is nothing to compare".
 */
class InterviewPhaseTracker(
    domains: List<HistoryDomain>,
    case: Map<String, Any?>? = null,
) {

    constructor(context: Context, case: Map<String, Any?>? = null) :
        this(HistoryDomains.loadDomains(context), case)


    companion object {
        private val LEGACY_ITEM_BY_DOMAIN: Map<String, List<String>> = mapOf(
            "opening" to listOf("phase_0"),
            "onset_duration" to listOf("1_onset"),
            "location" to listOf("1_location"),
            "severity" to listOf("1_severity"),
            "radiation" to listOf("1_radiation"),
            "character" to listOf("1_character"),
            "aggravating" to listOf("1_exacerbating"),
            "relieving" to listOf("1_relieving"),
            "aggravating_relieving" to listOf("1_exacerbating", "1_relieving"),
            "progression" to listOf("1_progression"),
            "associated_symptoms" to listOf("1_associated"),
            "pmh" to listOf("phase_2"),
            "medications" to listOf("3_meds"),
            "allergies" to listOf("3_allergy"),
            "smoking" to listOf("4_smoke"),
            "alcohol" to listOf("4_alcohol"),
            "substances" to listOf("4_substances"),
            "living_work" to listOf("4_living_work"),
            "family_hx" to listOf("4_family"),
            "social_hx" to listOf("4_smoke", "4_alcohol"),
            "ideas" to listOf("4_ideas"),
            "concerns" to listOf("4_concerns"),
            "expectations" to listOf("4_expectations"),
            "summary_closing" to listOf("4_close")
        )

        private val DOMAIN_BY_LEGACY_ITEM: Map<String, String> = mapOf(
            "phase_0" to "opening",
            "1_onset" to "onset_duration",
            "1_location" to "location",
            "1_severity" to "severity",
            "1_radiation" to "radiation",
            "1_character" to "character",
            "1_exacerbating" to "aggravating",
            "1_relieving" to "relieving",
            "1_progression" to "progression",
            "1_associated" to "associated_symptoms",
            "phase_2" to "pmh",
            "3_meds" to "medications",
            "3_allergy" to "allergies",
            "4_smoke" to "smoking",
            "4_alcohol" to "alcohol",
            "4_substances" to "substances",
            "4_living_work" to "living_work",
            "4_family" to "family_hx",
            "4_ideas" to "ideas",
            "4_concerns" to "concerns",
            "4_expectations" to "expectations",
            "4_close" to "summary_closing"
        )

        private val PHASE_LABELS = mapOf(
            0 to "Start",
            1 to "HPI",
            2 to "PMH",
            3 to "Meds & allergies",
            4 to "Social / patient perspective"
        )

        private val COMPLETION_THRESHOLD = mapOf(0 to 1, 1 to 3, 2 to 1, 3 to 2, 4 to 2)
    }

    private val chief: String = run {
        val raw = (case?.get("chief_complaint")?.toString() ?: "the symptoms")
        raw.split(" for ")[0].split(" since ")[0].trim()
    }

    private val coverageCase: Map<String, Any?> = run {
        val base = (case ?: emptyMap()).toMutableMap()
        if (base["chief_complaint"] == null) base["chief_complaint"] = chief
        base
    }

    val coverage = CoverageTracker(domains, coverageCase)
    val askedItems: MutableSet<String> = mutableSetOf()
    var currentPhase: Int = 0
        private set

    /** Retained for compatibility and for concise generic fallback hints. */
    val PHASES: Map<Int, PhaseDef> = mapOf(
        0 to PhaseDef(
            name = "Initiation",
            keywords = listOf("brings you in", "brought you here", "how can i help"),
            hints = listOf("What brings you in today?")
        ),
        1 to PhaseDef(
            name = "HPI (History of Present Illness)",
            subitems = mapOf(
                "onset" to PhaseSubitem(listOf("start", "begin", "how long", "come on"), "When did $chief start?"),
                "location" to PhaseSubitem(listOf("where", "location", "point"), "Where exactly do you feel $chief?"),
                "character" to PhaseSubitem(listOf("describe", "feel like", "sharp", "dull"), "Can you describe what $chief feels like?"),
                "severity" to PhaseSubitem(listOf("scale", "rate", "how bad", "out of ten"), "On a scale of 1 to 10, how bad is it?"),
                "radiation" to PhaseSubitem(listOf("radiate", "spread", "travel"), "Does $chief move or spread anywhere?"),
                "exacerbating" to PhaseSubitem(listOf("worse", "trigger", "aggravate"), "Does anything make it worse?"),
                "relieving" to PhaseSubitem(listOf("better", "relieve", "ease"), "Does anything make it better?"),
                "progression" to PhaseSubitem(listOf("constant", "come and go", "getting worse"), "Is it constant, or does it come and go?"),
                "associated" to PhaseSubitem(listOf("other symptoms", "anything else", "nausea"), "Have you noticed any other symptoms with it?")
            )
        ),
        2 to PhaseDef(
            name = "Past Medical History",
            keywords = listOf("medical history", "conditions", "diagnosed", "operation"),
            hints = listOf("Do you have any medical conditions I should know about?")
        ),
        3 to PhaseDef(
            name = "Medications & Allergies",
            subitems = mapOf(
                "meds" to PhaseSubitem(listOf("medication", "medicine", "taking", "regularly"), "Are you taking any medications at the moment?"),
                "allergy" to PhaseSubitem(listOf("allergy", "reaction", "sensitivity"), "Do you have any allergies, especially to medicines?")
            )
        ),
        4 to PhaseDef(
            name = "Social & Family History",
            subitems = mapOf(
                "smoke" to PhaseSubitem(listOf("smoke", "cigarette", "tobacco", "vape"), "Do you smoke or vape?"),
                "alcohol" to PhaseSubitem(listOf("alcohol", "beer", "wine"), "Do you drink alcohol?"),
                "family" to PhaseSubitem(listOf("family history", "run in your family"), "Are there any major illnesses in your family?")
            )
        )
    )

    private fun syncLegacyItems() {
        for (key in coverage.covered) {
            askedItems.addAll(LEGACY_ITEM_BY_DOMAIN[key] ?: emptyList())
        }
    }

    /** Keep direct askedItems mutation working for older callers/tests. */
    private fun syncInjectedLegacyItems() {
        for (item in askedItems) {
            val key = DOMAIN_BY_LEGACY_ITEM[item]
            if (key != null) coverage.covered.add(key)
        }
        val observed = coverage.domains
            .filter { it.key in coverage.resolved }
            .map { domainPhase(it) }
        if (observed.isNotEmpty()) {
            coverage.furthestPhase = maxOf(coverage.furthestPhase, currentPhase, observed.max())
        }
    }

    /** Consume one learner transcript and return newly recognised domains. */
    fun updateFromTurn(text: String, turnIndex: Int = -1): Set<String> {
        val new = coverage.update(text, turnIndex)
        syncLegacyItems()
        currentPhase = maxOf(currentPhase, coverage.furthestPhase)
        return new
    }

    /** Use a clear patient answer to corroborate garbled learner ASR. */
    fun updateFromPatientTurn(text: String, turnIndex: Int = -1): Set<String> {
        val new = coverage.notePatientTurn(text, turnIndex)
        currentPhase = maxOf(currentPhase, coverage.furthestPhase)
        return new
    }

    /** Label for the five-stage navigator, used to caption a hint with the area it belongs to. */
    fun phaseLabel(phase: Int): String = PHASE_LABELS[phase] ?: "Next"

    /** Return independent evidence/completion state for one UI phase. */
    fun phaseProgress(phase: Int): PhaseProgress {
        val allKeys = coverage.domains
            .filter { domainPhase(it) == phase && coverage.isApplicable(it) && it.core }
            .map { it.key }
            .toSet()
        val resolvedKeys = allKeys intersect coverage.resolved
        val threshold = minOf(allKeys.size, COMPLETION_THRESHOLD[phase] ?: 1)
        val complete = threshold > 0 && resolvedKeys.size >= threshold
        val status = when {
            phase == currentPhase -> "active"
            phase < currentPhase -> if (complete) "completed" else if (resolvedKeys.isNotEmpty()) "visited" else "skipped"
            else -> "not_started"
        }
        return PhaseProgress(status = status, covered = resolvedKeys.size, total = allKeys.size, complete = complete)
    }

    /**
     * Same hint as [getRescueHint], kept split into its two parts. The UI fills the typing box
     * with [RescueHint.question] alone and shows [RescueHint.category] as a separate label — the
     * category is context for the learner, never something they should send to the patient.
     */
    fun getRescueHintParts(): RescueHint {
        syncInjectedLegacyItems()
        val hint = coverage.nextHint() ?: return WRAP_UP_HINT
        return RescueHint(
            category = PHASE_LABELS[hint.phase] ?: "Next",
            question = hint.questions.firstOrNull() ?: "",
            reason = hint.reason,
            domainKey = hint.domain.key,
        )
    }

    /**
     * A short menu of things worth asking next rather than a single guess — what the "Stuck?"
     * button offers the learner.
     *
     * The rules are far better at narrowing the field to a few open areas than at picking the one
     * the learner wants, so committing to a single suggestion throws away most of what they know
     * and makes every mis-rank feel like a failure. Each option carries the reason it is being
     * offered, which the UI shows as a grouping label ("the patient just mentioned this" /
     * "next in order" / "you may have skipped this").
     */
    fun getRescueHintOptions(limit: Int = 3): List<RescueHint> {
        syncInjectedLegacyItems()
        val options = coverage.hintOptions(limit).mapNotNull { hint ->
            val question = hint.questions.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RescueHint(
                category = PHASE_LABELS[hint.phase] ?: "Next",
                question = question,
                reason = hint.reason,
                domainKey = hint.domain.key,
            )
        }
        return options.ifEmpty { listOf(WRAP_UP_HINT) }
    }

    /**
     * Legacy single-string form ("💡 Category: question"), kept for parity with the desktop
     * app's `get_rescue_hint()`. The Android UI uses [getRescueHintParts] instead so the
     * category never lands in the learner's typing box.
     */
    fun getRescueHint(): String {
        val parts = getRescueHintParts()
        return "💡 ${parts.category}: ${parts.question}"
    }
}

/**
 * One deterministic "what could I ask next?" suggestion: the phase it belongs to, the question, and
 * why it is being offered ("follow_up" — the patient just raised it, "continue" — next area in
 * order, "catch_up" — an earlier area that was skipped).
 */
data class RescueHint(
    val category: String,
    val question: String,
    val reason: String = "continue",
    val domainKey: String = "",
)

/** Shown when every applicable history area has been resolved and only closing is left. */
val WRAP_UP_HINT = RescueHint(
    category = "Wrap up",
    question = "Before we finish — is there anything else you'd like to mention?",
    reason = "continue",
    domainKey = "summary_closing",
)
