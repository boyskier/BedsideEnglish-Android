package com.example.medvoicetrainer.update

import kotlin.math.roundToInt

/**
 * Pure decision logic behind the Google Play in-app update check.
 *
 * Everything Play-specific (the AppUpdateManager task API, the activity-result flow, the install
 * listener) lives in [InAppUpdateManager]; this file holds only the parts that decide *whether* and
 * *how loudly* to interrupt a learner, so they stay unit-testable on a plain JVM without the Play
 * Core AAR or an Android device.
 *
 * The shape of the inputs mirrors what `AppUpdateInfo` reports — see the Play Core docs for
 * `updatePriority()` (0–5, set per release via the Play Developer API) and
 * `clientVersionStalenessDays()` (null until Play knows how long the installed version has been out
 * of date). Staleness is retained for diagnostics and future reminder policies, but it must never
 * turn an ordinary feature release into a blocking update by itself.
 */
enum class UpdateAction {
    /** Say nothing this launch. */
    NONE,

    /** Download in the background; the learner keeps practising and restarts when they want. */
    FLEXIBLE,

    /** Blocking Play-owned screen — reserved for releases that are unsafe to keep running. */
    IMMEDIATE,
}

/** The subset of `AppUpdateInfo` the policy actually reads. */
data class UpdateAvailabilityInfo(
    val updateAvailable: Boolean,
    val availableVersionCode: Int = 0,
    /** Play Developer API in-app update priority, 0 (default) to 5 (critical). */
    val updatePriority: Int = 0,
    /** Days the installed version has been stale; null when Play has not reported it yet. */
    val clientStalenessDays: Int? = null,
    val flexibleAllowed: Boolean = true,
    val immediateAllowed: Boolean = true,
)

/** What we remember between launches so a declined update does not nag on every cold start. */
data class UpdatePromptMemory(
    val dismissedVersionCode: Int = 0,
    val lastPromptEpochDay: Long = 0L,
)

object UpdatePolicy {
    /** Priority at or above which a release is worth a blocking update screen. */
    const val IMMEDIATE_PRIORITY_THRESHOLD = 4

    /** How long a declined flexible update stays quiet, in days. */
    const val FLEXIBLE_REPROMPT_DAYS = 3

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Whole days since the epoch, used as the cooldown clock (no calendar/timezone dependency). */
    fun epochDay(nowMillis: Long): Long = Math.floorDiv(nowMillis, MILLIS_PER_DAY)

    /**
     * A release is critical only when the developer explicitly flagged it (priority >= 4).
     * Age alone is not evidence that an update is unsafe to skip: routine feature releases remain
     * flexible even for users returning after a long absence.
     */
    fun isCritical(info: UpdateAvailabilityInfo): Boolean =
        info.updatePriority >= IMMEDIATE_PRIORITY_THRESHOLD

    /**
     * @param userInitiated true when the learner tapped "Check for updates" themselves — that
     *   bypasses the snooze cooldown, because asking and then being told nothing is a bug.
     */
    fun decide(
        info: UpdateAvailabilityInfo,
        memory: UpdatePromptMemory,
        todayEpochDay: Long,
        userInitiated: Boolean = false,
    ): UpdateAction {
        if (!info.updateAvailable) return UpdateAction.NONE

        if (isCritical(info)) {
            // Critical releases ignore the snooze entirely; a learner cannot opt out of a build
            // that is unsafe to keep using. Fall back to flexible only if Play refuses the
            // blocking flow (it does, for example, when the device cannot host it).
            return when {
                info.immediateAllowed -> UpdateAction.IMMEDIATE
                info.flexibleAllowed -> UpdateAction.FLEXIBLE
                else -> UpdateAction.NONE
            }
        }

        // A routine update never takes over the screen. If Play won't allow the background flow
        // there is nothing polite left to offer, so stay silent and let the Play listing handle it.
        if (!info.flexibleAllowed) return UpdateAction.NONE

        if (!userInitiated && isSnoozed(info.availableVersionCode, memory, todayEpochDay)) {
            return UpdateAction.NONE
        }
        return UpdateAction.FLEXIBLE
    }

    /**
     * A dismissal only silences the exact version that was declined, so shipping a newer build
     * re-asks immediately. A device clock that moved backwards yields a negative elapsed value and
     * is treated as "cooldown over" rather than silencing updates indefinitely.
     */
    fun isSnoozed(
        availableVersionCode: Int,
        memory: UpdatePromptMemory,
        todayEpochDay: Long,
    ): Boolean {
        if (memory.dismissedVersionCode != availableVersionCode) return false
        val elapsedDays = todayEpochDay - memory.lastPromptEpochDay
        return elapsedDays in 0 until FLEXIBLE_REPROMPT_DAYS
    }
}

/** Download-progress arithmetic for the flexible-update banner. */
object UpdateProgress {
    /** 0f..1f, defensive against the 0-byte totals Play reports before a download really starts. */
    fun fraction(bytesDownloaded: Long, totalBytesToDownload: Long): Float {
        if (totalBytesToDownload <= 0L || bytesDownloaded <= 0L) return 0f
        return (bytesDownloaded.toFloat() / totalBytesToDownload.toFloat()).coerceIn(0f, 1f)
    }

    fun percent(bytesDownloaded: Long, totalBytesToDownload: Long): Int =
        (fraction(bytesDownloaded, totalBytesToDownload) * 100f).roundToInt()
}

/** Play Store deep links, used when the in-app flow is unavailable (sideloaded build, Play error). */
object PlayStoreLink {
    /** Opens the Play app directly; only resolvable when Play is installed. */
    fun marketUri(packageName: String): String = "market://details?id=$packageName"

    /** Browser-resolvable fallback for devices without the Play app. */
    fun webUri(packageName: String): String =
        "https://play.google.com/store/apps/details?id=$packageName"
}
