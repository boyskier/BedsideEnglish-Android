package com.example.medvoicetrainer.update

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** What the UI needs to know about an in-flight Google Play update. */
sealed interface InAppUpdateState {
    /** Nothing to show — no update, or one the learner snoozed. */
    data object Idle : InAppUpdateState

    /** Play is fetching the new APK in the background; the app stays fully usable. */
    data class Downloading(val fraction: Float) : InAppUpdateState

    /** Downloaded and waiting for a restart, which only the learner can trigger. */
    data object ReadyToInstall : InAppUpdateState

    /** Play is applying the update; the process is about to be restarted by the system. */
    data object Installing : InAppUpdateState
}

/** Outcome of an explicit "Check for updates" tap, reported back for a toast. */
enum class ManualCheckResult {
    /** A Play update flow was launched (background download or blocking screen). */
    STARTED,

    /** A previous flexible download already finished; the restart prompt is showing. */
    ALREADY_DOWNLOADED,

    /** Play says this install is current. */
    UP_TO_DATE,

    /**
     * Play could not answer — no Play install (sideloaded/debug APK), Play Store signed out, or the
     * device is offline. Callers fall back to opening the store listing.
     */
    UNAVAILABLE,
}

/**
 * The app-facing surface of in-app updates, kept as an interface so Compose code (and previews)
 * never touch the Play Core classes directly. [InAppUpdateManager] is the real implementation.
 */
interface InAppUpdateController {
    val state: StateFlow<InAppUpdateState>

    /**
     * Ask Play whether a newer build exists and start the appropriate flow.
     *
     * @param userInitiated true when a learner tapped "Check for updates"; that bypasses the snooze
     *   cooldown and is the only path that reports back through [onResult].
     */
    fun checkForUpdates(userInitiated: Boolean = false, onResult: (ManualCheckResult) -> Unit = {})

    /** Restart into the downloaded build (flexible flow only). */
    fun completeUpdate()

    /** Hide the prompt and stay quiet about this version for [UpdatePolicy.FLEXIBLE_REPROMPT_DAYS]. */
    fun snoozeUpdate()
}

/**
 * Provided once in MainActivity so any screen (the update banner, Preferences' "Check for updates")
 * can reach the controller without threading it through every composable signature. Null in
 * previews and in any host that has no Play update support wired up.
 */
val LocalInAppUpdate = staticCompositionLocalOf<InAppUpdateController?> { null }
