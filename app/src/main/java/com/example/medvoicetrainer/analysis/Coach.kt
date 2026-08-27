package com.example.medvoicetrainer.analysis

/**
 * Ported from app/ui/coach.py — first-run guidance: one-time primers, empty-state copy, and a
 * re-openable "How it works" tour. Everything here is additive, gated on a saved setting so each
 * primer shows once (see MainViewModel.isCoachPrimerSeen/markCoachPrimerSeen, which wrap
 * Repository.getSetting/setSetting — settings I/O stays out of this pure-logic/content object,
 * matching the project convention).
 *
 * Not ported: `_Tooltip`/`attach_tooltip` (Tkinter hover-tooltip chrome explaining jargon like
 * "Mistake Genome"/"stubborn" — hover has no equivalent on a touchscreen; N/A here the same way
 * `widgets.py`/`dialogs.py` are, not a gap).
 */
object Coach {

    const val KEY_AUDIO_PREFLIGHT = "coach_audio_preflight_done"
    const val KEY_SESSION_TIP = "coach_session_tip_seen"
    const val KEY_FEEDBACK_EXPLAINER = "coach_feedback_explainer_seen"
    const val KEY_OPENING_SCAFFOLD = "coach_opening_scaffold_seen"

    // One-time, first-run "what's in each tab" coach-mark tour, shown once the learner reaches the
    // main dashboard for the first time (right after onboarding/API-key entry). A brand-new user
    // has every feature laid out in the bottom nav but no reason to discover them; this spotlights
    // each destination in turn. Gated by this key so it never reappears — re-openable later from
    // the in-app Guide is out of scope here.
    const val KEY_HOME_TOUR = "coach_home_tour_seen"

    // --- Early-learning UX (first live session support) ---
    // A one-time in-session reassurance that speaking is optional — the key-entered learner skips
    // the scripted demo tour entirely, so their very first product experience is a cold, live,
    // unscripted voice encounter. This lowers the "open mic, must speak now" pressure.
    const val KEY_FIRST_LIVE_SPEAK_OPTIONAL = "coach_first_live_speak_optional_seen"
    // A one-time post-session cost/quota reassurance, shown after the learner's first *live*
    // (billable) session — a novice using their own API key otherwise has no idea what a session
    // costs and can churn out of bill anxiety.
    const val KEY_FIRST_COST_PRIMER = "coach_first_cost_primer_seen"
    // A one-time opt-in for a next-day practice reminder, shown after the first analyzed session —
    // the only day-2 return hook in the funnel.
    const val KEY_REMINDER_OPTIN = "coach_reminder_optin_seen"

    /** [title]/[description] are i18n keys (see [com.example.medvoicetrainer.ui.LocalTranslate]),
     * not display text — callers must resolve them with `t(...)` before rendering. */
    data class PracticeModeInfo(val emoji: String, val title: String, val description: String)

    /** (emoji, title key, one-line description key) for each practice surface — used by the
     * "How it works" tour so a newcomer can tell the modes apart without opening each tab. */
    fun practiceModes(): List<PracticeModeInfo> = listOf(
        PracticeModeInfo("🩺", "coach.mode.encounter.title", "coach.mode.encounter.desc"),
        PracticeModeInfo("📋", "coach.mode.exam.title", "coach.mode.exam.desc"),
        PracticeModeInfo("🎓", "coach.mode.teachback.title", "coach.mode.teachback.desc"),
        PracticeModeInfo("💼", "coach.mode.interview.title", "coach.mode.interview.desc"),
        PracticeModeInfo("☕", "coach.mode.lounge.title", "coach.mode.lounge.desc"),
        PracticeModeInfo("🌍", "coach.mode.survival.title", "coach.mode.survival.desc"),
        PracticeModeInfo("🛠", "coach.mode.custom.title", "coach.mode.custom.desc")
    )

    /** i18n keys for the practice-loop steps shown at the top of the "How it works" tour. */
    fun practiceLoopSteps(): List<String> = listOf(
        "coach.loop.step1",
        "coach.loop.step2",
        "coach.loop.step3",
        "coach.loop.step4"
    )

    /** i18n key for the one-line in-session coaching tip, shown once. Typed Demo gets typing
     * instructions instead of microphone instructions. */
    fun firstSessionTipKey(typedDemo: Boolean = false): String {
        return if (typedDemo) "coach.session_tip_typed" else "coach.session_tip"
    }

    /**
     * The first-30-seconds opening line handed to a brand-new user with a blank transcript and
     * an open mic (docs/design/android-ui-spec.html §5's "FIRST 30s" scaffold) — the single
     * highest-leverage moment in the funnel, since a silent open mic is the blank-page problem
     * made audible. Shown once ever, only for a real (non-typed) encounter with no kickoff_text
     * (ordinary patient-first encounters), gated by KEY_OPENING_SCAFFOLD. [translate] resolves
     * the greeting template and localized fallback name for the caller's current UI language.
     */
    fun openingScaffoldLine(caseName: String, translate: (String) -> String): String {
        // caseName is conventionally "Chief complaint — Patient label (age/sex)"; fall back to
        // the whole string when a case doesn't follow that convention.
        val afterDash = caseName.substringAfter("—", caseName).trim()
        val greetingName = afterDash.substringBefore("(").trim()
            .ifBlank { translate("coach.opening_scaffold.fallback_name") }
        return translate("coach.opening_scaffold.greeting").replace("{name}", greetingName)
    }

    /** i18n key for the post-first-feedback explainer banner. */
    const val FEEDBACK_EXPLAINER_KEY = "coach.feedback_explainer"

    const val HEADPHONES_TITLE_KEY = "preflight.headphones_title"
    const val HEADPHONES_BODY_KEY = "preflight.headphones_body"
    const val MIC_CHECK_TITLE_KEY = "preflight.mic_title"
    const val MIC_CHECK_BODY_KEY = "preflight.mic_body"
    const val FLOW_TITLE_KEY = "preflight.flow_title"
    const val FLOW_BODY_KEY = "preflight.flow_body"

    /** Matches mic_check.py's "we heard you" threshold. */
    const val MIC_OK_PEAK = 200.0
    const val MIC_METER_SECONDS = 6
}
