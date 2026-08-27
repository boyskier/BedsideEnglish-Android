package com.example.medvoicetrainer.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play in-app updates.
 *
 * Beta testers install from a Play track, so Play itself is the only reliable place to learn that a
 * newer build exists — the app previously had no update path at all beyond the tester noticing the
 * Play listing. This wires up both official flows:
 *
 *  - **Flexible** (the default): Play downloads the new APK in the background while the learner
 *    keeps practising, and the app shows a restart prompt when it lands. A conversation in progress
 *    is never interrupted.
 *  - **Immediate**: Play's own blocking screen, used only for releases explicitly flagged priority
 *    >= 4 (see [UpdatePolicy]).
 *
 * All the "should we interrupt?" reasoning lives in [UpdatePolicy] so it can be unit-tested; this
 * class is the Play Core plumbing around it.
 *
 * Attach one per Activity, in `onCreate` — [updateLauncher] registers an activity result, which
 * must happen before the Activity is STARTED.
 */
class InAppUpdateManager(
    private val activity: ComponentActivity,
    private val store: UpdatePromptStore = UpdatePromptStore(activity),
    private val clock: () -> Long = System::currentTimeMillis,
) : InAppUpdateController, DefaultLifecycleObserver {

    private val appUpdateManager = AppUpdateManagerFactory.create(activity)

    private val _state = MutableStateFlow<InAppUpdateState>(InAppUpdateState.Idle)
    override val state: StateFlow<InAppUpdateState> = _state.asStateFlow()

    /** The version Play last offered us, so a cancel can be recorded against the right build. */
    private var offeredVersionCode: Int = 0

    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> Unit
                Activity.RESULT_CANCELED -> {
                    // Honour a deliberate "not now" for routine updates. Critical releases ignore
                    // this memory in UpdatePolicy and will be offered again on the next cold start.
                    Log.i(TAG, "Update flow declined by the user")
                    snoozeUpdate()
                }
                else -> {
                    // A Play/network failure is not a user choice, so do not suppress retries for
                    // three days. The next launch or manual check may succeed.
                    Log.w(TAG, "Update flow failed (resultCode=${result.resultCode})")
                    _state.value = InAppUpdateState.Idle
                }
            }
        }

    private val installListener = InstallStateUpdatedListener { installState ->
        when (installState.installStatus()) {
            InstallStatus.PENDING -> _state.value = InAppUpdateState.Downloading(0f)
            InstallStatus.DOWNLOADING -> _state.value = InAppUpdateState.Downloading(
                UpdateProgress.fraction(
                    installState.bytesDownloaded(),
                    installState.totalBytesToDownload(),
                )
            )
            InstallStatus.DOWNLOADED -> _state.value = InAppUpdateState.ReadyToInstall
            InstallStatus.INSTALLING -> _state.value = InAppUpdateState.Installing
            InstallStatus.INSTALLED -> {
                store.clear()
                _state.value = InAppUpdateState.Idle
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                Log.w(TAG, "Update install ended with status ${installState.installStatus()}")
                _state.value = InAppUpdateState.Idle
            }
            else -> Unit
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        appUpdateManager.registerListener(installListener)
    }

    override fun onStop(owner: LifecycleOwner) {
        appUpdateManager.unregisterListener(installListener)
    }

    /**
     * Two things can be left dangling when the app comes back to the foreground: a flexible download
     * that finished while we were away (the restart prompt has to reappear), and an immediate update
     * the learner backgrounded out of half-way (Play requires the app to re-launch it).
     */
    override fun onResume(owner: LifecycleOwner) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                offeredVersionCode = info.availableVersionCode()
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED ->
                        _state.value = InAppUpdateState.ReadyToInstall

                    // A download that survived a process death: show the progress banner right away
                    // rather than waiting for Play's next listener callback.
                    info.installStatus() == InstallStatus.DOWNLOADING ||
                        info.installStatus() == InstallStatus.PENDING ->
                        if (_state.value is InAppUpdateState.Idle) {
                            _state.value = InAppUpdateState.Downloading(0f)
                        }

                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        startFlow(info, AppUpdateType.IMMEDIATE)
                }
            }
            .addOnFailureListener { error ->
                Log.i(TAG, "Resume update check unavailable: ${error.message}")
            }
    }

    override fun checkForUpdates(userInitiated: Boolean, onResult: (ManualCheckResult) -> Unit) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                offeredVersionCode = info.availableVersionCode()

                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    _state.value = InAppUpdateState.ReadyToInstall
                    onResult(ManualCheckResult.ALREADY_DOWNLOADED)
                    return@addOnSuccessListener
                }

                val availability = UpdateAvailabilityInfo(
                    updateAvailable =
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                    availableVersionCode = info.availableVersionCode(),
                    updatePriority = info.updatePriority(),
                    clientStalenessDays = info.clientVersionStalenessDays(),
                    flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                )

                when (
                    UpdatePolicy.decide(
                        info = availability,
                        memory = store.readMemory(),
                        todayEpochDay = todayEpochDay(),
                        userInitiated = userInitiated,
                    )
                ) {
                    UpdateAction.FLEXIBLE -> {
                        onResult(
                            if (startFlow(info, AppUpdateType.FLEXIBLE)) {
                                ManualCheckResult.STARTED
                            } else {
                                ManualCheckResult.UNAVAILABLE
                            }
                        )
                    }
                    UpdateAction.IMMEDIATE -> {
                        onResult(
                            if (startFlow(info, AppUpdateType.IMMEDIATE)) {
                                ManualCheckResult.STARTED
                            } else {
                                ManualCheckResult.UNAVAILABLE
                            }
                        )
                    }
                    UpdateAction.NONE -> onResult(
                        // An update Play won't let us install in-app is not "up to date" — the
                        // caller sends those to the store listing instead of claiming otherwise.
                        if (availability.updateAvailable) {
                            ManualCheckResult.UNAVAILABLE
                        } else {
                            ManualCheckResult.UP_TO_DATE
                        }
                    )
                }
            }
            .addOnFailureListener { error ->
                // Thrown on sideloaded/debug installs (no Play install record), when Play is signed
                // out, and when the device is offline. Never surfaced as an error dialog — the
                // caller quietly offers the store listing instead.
                Log.i(TAG, "Play update check unavailable: ${error.message}")
                onResult(ManualCheckResult.UNAVAILABLE)
            }
    }

    override fun completeUpdate() {
        _state.value = InAppUpdateState.Installing
        appUpdateManager.completeUpdate()
            .addOnFailureListener { error ->
                // Keep the downloaded update actionable when Play could not restart/install it.
                Log.w(TAG, "Could not complete downloaded update", error)
                _state.value = InAppUpdateState.ReadyToInstall
            }
    }

    override fun snoozeUpdate() {
        if (offeredVersionCode > 0) {
            store.recordDismissal(offeredVersionCode, todayEpochDay())
        }
        _state.value = InAppUpdateState.Idle
    }

    /** [type] is an [AppUpdateType] constant (FLEXIBLE / IMMEDIATE). */
    private fun startFlow(info: AppUpdateInfo, type: Int): Boolean =
        try {
            val started = appUpdateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(type).build(),
            )
            if (!started) {
                Log.w(TAG, "Play declined to start update flow (type=$type)")
                _state.value = InAppUpdateState.Idle
            }
            started
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // A failed hand-off to Play must never take a practice session down with it.
            Log.w(TAG, "Could not start update flow", e)
            _state.value = InAppUpdateState.Idle
            false
        }

    private fun todayEpochDay(): Long = UpdatePolicy.epochDay(clock())

    companion object {
        private const val TAG = "InAppUpdate"

        /**
         * Fallback for when Play cannot run the in-app flow: open the store listing so the tester
         * can still update by hand. Tries the Play app first, then the browser.
         */
        fun openPlayStoreListing(context: android.content.Context) {
            val packageName = context.packageName
            val market = Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreLink.marketUri(packageName)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(market)
            } catch (e: ActivityNotFoundException) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreLink.webUri(packageName)))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (e2: Exception) {
                    Log.w(TAG, "No handler for the Play Store listing", e2)
                }
            }
        }
    }
}
