package com.example.medvoicetrainer.analysis

/**
 * Ported from app/ui/tomorrow_toast.py — decision/content logic for the session-end "see you
 * tomorrow" toast, the D1 retention hook. Non-modal, self-dismissing, once per calendar day; a
 * milestone celebration on screen takes priority (the caller skips this toast in that case,
 * without marking it shown, so a later session today can still nudge).
 *
 * The tkinter Toplevel toast window itself is not ported; a Compose Snackbar/Toast calling into
 * this object is still pending (see PORTING_STATUS.md).
 */

data class ToastContent(val headline: String, val subline: String?, val footer: String)

object TomorrowToast {

    const val AUTO_CLOSE_MS = 12_000L

    /** Whether the toast should fire: not today already, and no milestone celebration active. */
    fun shouldShow(milestoneShown: Boolean, todayIso: String, lastShownDateIso: String): Boolean {
        if (milestoneShown) return false
        return lastShownDateIso != todayIso
    }

    /**
     * Build the toast's text content.
     * @param isDemoBackend true when voice_backend == "demo" — demo mode speaks demo truth: a
     *   scripted patient waiting, or (once the tour is finished) the unlock message, never a
     *   live-only mission preview the demo can't deliver.
     */
    fun buildToastContent(
        streak: Int,
        missionTitle: String?,
        isDemoBackend: Boolean,
        demoTourComplete: Boolean = false,
        nextDemoCaseLabel: String? = null
    ): ToastContent {
        val headline = if (streak <= 1) {
            "🔥 Day 1 done — come back tomorrow to make it a streak!"
        } else {
            "🔥 $streak-day streak secured — tomorrow makes it ${streak + 1}!"
        }

        val subline = if (isDemoBackend) {
            if (demoTourComplete) {
                "Ready for live AI voice? Add your free key any time — about a minute."
            } else if (!nextDemoCaseLabel.isNullOrBlank()) {
                "$nextDemoCaseLabel is waiting — 2 minutes"
            } else {
                null
            }
        } else {
            missionTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { "Tomorrow's 5-minute mission: $it" }
        }

        return ToastContent(
            headline = headline,
            subline = subline,
            footer = "It'll be one click on the Dashboard. See you tomorrow!"
        )
    }
}
