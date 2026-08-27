package com.example.medvoicetrainer.analysis

/**
 * Deterministic "good time to wrap up" detector for standard patient encounters.
 *
 * This is the language-app answer to the old "the patient never leaves, so the learner talks
 * forever" problem: instead of a hard auto-close (which would break the classic "oh, one more
 * thing, doctor" grab-back), the app only ever *suggests* closure. Detection is fully local and
 * deterministic — no API call — and only drives a soft UI nudge and the in-fiction closure
 * behavior baked into the patient system prompt (see PromptBuilder's closure/doorknob addenda).
 *
 * Trigger is "the interview is substantially done AND the learner is winding down or the visit
 * has simply run long enough", never raw context length (which is invisible to the learner and
 * unrelated to how complete the encounter is — a chatty patient fills context at half coverage).
 */
data class WrapUpSignals(
    /** Learner (doctor) turns taken so far this session. */
    val doctorTurns: Int,
    /** Wall-clock seconds since the session started. */
    val elapsedSeconds: Long,
    /** Interview phases the deterministic tracker considers complete (0..[phasesTotal]). */
    val phasesComplete: Int,
    /** Total interview phases the tracker knows about (5 for the standard history). */
    val phasesTotal: Int,
    /** Consecutive learner turns that surfaced no new checklist coverage (running-dry signal). */
    val turnsSinceNewCoverage: Int,
)

object WrapUpPolicy {
    /** Below this many learner turns the encounter is too young to ever suggest closing. */
    const val MIN_DOCTOR_TURNS = 6

    /** Fraction of interview phases that must be complete before closure is on the table. */
    const val CORE_PHASES_FRACTION = 0.6

    /** Learner turns with no new coverage that read as "they've run out of ground to cover". */
    const val STAGNATION_TURNS = 3

    /** An encounter this long is a reasonable full visit even if the learner keeps finding ground. */
    const val LONG_ENCOUNTER_SECONDS = 240L

    /**
     * True once the core interview is substantially covered AND the learner is either winding
     * down (no new coverage for [STAGNATION_TURNS] turns) or the visit has run [LONG_ENCOUNTER_SECONDS].
     * Pure and side-effect free so it can be unit-tested without a live session; [WrapUpDetector]
     * wraps it with a one-shot latch.
     */
    fun shouldSuggestWrapUp(s: WrapUpSignals): Boolean {
        if (s.doctorTurns < MIN_DOCTOR_TURNS) return false
        val coreCovered = s.phasesTotal > 0 &&
            s.phasesComplete.toDouble() / s.phasesTotal >= CORE_PHASES_FRACTION
        if (!coreCovered) return false
        return s.turnsSinceNewCoverage >= STAGNATION_TURNS ||
            s.elapsedSeconds >= LONG_ENCOUNTER_SECONDS
    }
}

/**
 * One-shot latch around [WrapUpPolicy]. [observe] returns true exactly once — on the first turn
 * the encounter becomes wrap-up-ready — so callers can raise the nudge a single time instead of
 * every turn afterwards. Not thread-safe; drive it from the (serialized) transcript callback.
 */
class WrapUpDetector {
    var hasFired: Boolean = false
        private set

    fun observe(signals: WrapUpSignals): Boolean {
        if (hasFired) return false
        if (WrapUpPolicy.shouldSuggestWrapUp(signals)) {
            hasFired = true
            return true
        }
        return false
    }
}
