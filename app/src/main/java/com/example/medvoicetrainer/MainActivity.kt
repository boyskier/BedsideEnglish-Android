package com.example.medvoicetrainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.ActiveSessionState
import com.example.medvoicetrainer.ui.FeedbackAnalysisStage
import com.example.medvoicetrainer.ui.SessionCompletionOwnership
import com.example.medvoicetrainer.ui.PracticeExperience
import com.example.medvoicetrainer.ui.hasVoiceBackendAccess
import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.ui.screens.*
import com.example.medvoicetrainer.analysis.CoverageSource
import com.example.medvoicetrainer.ui.theme.MedVoiceTrainerTheme
import com.example.medvoicetrainer.ui.theme.SuccessGreen
import com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
import com.example.medvoicetrainer.ui.theme.DangerRedStrong
import com.example.medvoicetrainer.ui.theme.DangerContainer
import com.example.medvoicetrainer.ui.theme.AccentAmber
import com.example.medvoicetrainer.update.InAppUpdateManager
import com.example.medvoicetrainer.voice.VoiceConnectionState
import com.example.medvoicetrainer.voice.MicrophoneState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turn-state machine driving the live-session header badge, mic-bar copy, and (eventually)
 * TalkBack announcements from one place, per docs/design/android-ui-spec.html §5's "Turn-state
 * machine (normative)" table. Honesty note: the app's transcript/status model can't currently
 * distinguish "the model is thinking" from "the model's audio is playing" — both collapse into
 * [PATIENT_SPEAKING] here rather than inventing an unobserved THINKING signal.
 */
private enum class TurnState {
    CONNECTING, YOUR_TURN, PATIENT_SPEAKING, MUTED, MIC_UNAVAILABLE,
    USER_PAUSED, PAUSED, FAILED
}

private fun deriveTurnState(activeSession: ActiveSessionState, micMuted: Boolean): TurnState =
    when (activeSession.voiceConnectionState) {
        VoiceConnectionState.CONNECTING -> TurnState.CONNECTING
        VoiceConnectionState.USER_PAUSED -> TurnState.USER_PAUSED
        VoiceConnectionState.RECONNECTING -> TurnState.PAUSED
        VoiceConnectionState.FAILED, VoiceConnectionState.CLOSED -> TurnState.FAILED
        VoiceConnectionState.READY -> when {
            activeSession.microphoneState == MicrophoneState.STARTING -> TurnState.CONNECTING
            activeSession.microphoneState == MicrophoneState.UNAVAILABLE -> TurnState.MIC_UNAVAILABLE
            micMuted -> TurnState.MUTED
            activeSession.isAILoading -> TurnState.PATIENT_SPEAKING
            else -> TurnState.YOUR_TURN
        }
    }

internal fun modeForGenericPracticeCase(caseId: String, caseJson: String): String {
    val id = caseId.trim().lowercase()
    if (id == "custom_case" || id.startsWith("custom_")) return "custom"
    if (id.startsWith("lounge_")) return "lounge"
    fun fieldEquals(key: String, expected: String): Boolean = Regex(
        "\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"${Regex.escape(expected)}\\\"",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(caseJson)
    return when {
        fieldEquals("encounter_type", "follow_up") -> "follow_up"
        fieldEquals("session_mode", "team_communication") -> "team_communication"
        // Nursing sessions get their own stored mode so History can filter them and so the
        // physician track's per-mode statistics are never diluted by a different profession's
        // rubric. Everything downstream (analysis, SRS, export) is mode-agnostic.
        fieldEquals("session_mode", "nursing") -> "nursing"
        // Korean CPX: Korean-speaking patient and Korean grader, stored apart from English sessions.
        fieldEquals("session_mode", com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE) ->
            com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE
        fieldEquals("system", "presentation") || fieldEquals("eval_template", "case_presentation") -> "presentation"
        fieldEquals("system", "lounge") || fieldEquals("eval_template", "lounge") -> "lounge"
        fieldEquals("id", "custom_case") -> "custom"
        else -> "encounter"
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private lateinit var inAppUpdateManager: InAppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashTelemetry()
        com.example.medvoicetrainer.ui.I18n.init(this)
        enableEdgeToEdge()
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        // Must be constructed here, not lazily from Compose: it registers an activity-result
        // launcher for Play's update flow, which is only legal before the Activity is STARTED.
        // It also observes this Activity's lifecycle to resume interrupted updates (see onResume
        // inside the manager), so no further wiring is needed here.
        inAppUpdateManager = InAppUpdateManager(this)
        // Testers install from a Play track, so the launch check is the only thing that tells them
        // a newer build exists. Routine releases use Play's consent-based flexible flow, download
        // in the background after acceptance, and only ask for a restart once they have landed.
        inAppUpdateManager.checkForUpdates()
        handleShareIntent(intent)
        handleFeedbackIntent(intent)

        setContent {
            val viewModel = mainViewModel
            // On a fresh install this value comes from Android's locale and stays on-device.
            val uiLang by viewModel.uiLanguage.collectAsStateWithLifecycle()

            CompositionLocalProvider(
                com.example.medvoicetrainer.ui.LocalTranslate provides { key -> com.example.medvoicetrainer.ui.I18n.t(key, uiLang) },
                LocalLayoutDirection provides if (uiLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                com.example.medvoicetrainer.update.LocalInAppUpdate provides inAppUpdateManager
            ) {
                MedVoiceTrainerTheme {
                    MainAppScaffold(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleFeedbackIntent(intent)
    }

    private fun handleFeedbackIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(FeedbackNotification.EXTRA_OPEN_HISTORY, false) == true) {
            mainViewModel.requestHistoryNavigation()
            intent.removeExtra(FeedbackNotification.EXTRA_OPEN_HISTORY)
        }
    }

    /** Android's share sheet (ACTION_SEND, text/plain) for "import a conversation" — see
     *  AndroidManifest.xml's second intent-filter and MainViewModel.receiveSharedText. Handles
     *  both a cold start via share (onCreate) and sharing into an already-running instance
     *  (onNewIntent — requires launchMode="singleTask", set on this activity). */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        mainViewModel.receiveSharedText(sharedText)
    }

    /**
     * Ported from app/ui/error_dialog.py's install()/handle_ui_exception() for the genuinely
     * uncaught (crashing) case — Android tears the process down once this fires, so unlike
     * Python's Tkinter callback hook there is no way to log a friendly dialog and "keep the app
     * running"; MainViewModel.startSession's try/catch (see handleUiException) covers the
     * recoverable, non-fatal half of this file's job instead. This handler only covers logging +
     * anonymous telemetry counting before letting the system's default crash handling proceed.
     */
    private fun installCrashTelemetry() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                android.util.Log.e("MedVoiceTrainer", "Uncaught exception on ${thread.name}", throwable)
                com.example.medvoicetrainer.analysis.Telemetry.trackError("uncaught", throwable)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // telemetry/logging must never block the crash from being reported normally
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(viewModel: MainViewModel) {
    var currentTab by remember { mutableIntStateOf(0) }
    var requestedEverydayPracticeMode by remember { mutableStateOf<String?>(null) }
    var requestedPhraseDrill by remember {
        mutableStateOf<List<com.example.medvoicetrainer.analysis.EverydayPhrase>>(emptyList())
    }
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    var preferencesFocusProvider by remember { mutableStateOf<String?>(null) }
    var showImportScreen by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()

    // Backup work lives in the ViewModel. If an Activity recreation rebuilds this scaffold while a
    // job is running (or while a restore is waiting for its mandatory restart), put the blocking
    // surface back instead of exposing the dashboard over a database replacement in progress.
    LaunchedEffect(backupState.requiresPreferencesSurface) {
        if (backupState.requiresPreferencesSurface) showPreferences = true
    }
    // Measured on-screen bounds of each spotlightable control, fed to the first-run coach-mark tour.
    val tutorialAnchors = remember { mutableStateMapOf<com.example.medvoicetrainer.ui.screens.TutorialTarget, Rect>() }

    // Is the soft keyboard up? Material3's Scaffold pads its content by the bottom bar's height
    // *instead of* contentWindowInsets whenever a bottom bar is present, so with the tab bar
    // showing the IME inset never reaches the content and the keyboard covers whatever is being
    // typed into (search fields, note fields). Folding the tab bar away while typing — the usual
    // Android behavior — hands the content the full IME inset again. Derived so IME animation
    // frames don't recompose the whole scaffold.
    val scaffoldDensity = LocalDensity.current
    val scaffoldImeInsets = WindowInsets.ime
    val keyboardVisible by remember(scaffoldImeInsets, scaffoldDensity) {
        derivedStateOf { scaffoldImeInsets.getBottom(scaffoldDensity) > 0 }
    }

    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val lastEvaluation by viewModel.lastEvaluation.collectAsStateWithLifecycle()
    val lastKmleResult by viewModel.lastKmleResult.collectAsStateWithLifecycle()
    val kmleMockState by viewModel.kmleMock.collectAsStateWithLifecycle()
    val lastPresentationLaunch by viewModel.lastCompletedPresentationLaunch.collectAsStateWithLifecycle()
    val apiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val openAiApiKey by viewModel.openAiApiKey.collectAsStateWithLifecycle()
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val practiceExperience by viewModel.practiceExperience.collectAsStateWithLifecycle()
    val showApiSetup by viewModel.apiSetupInProgress.collectAsStateWithLifecycle()
    val demoTourStatus by viewModel.demoTourStatus.collectAsStateWithLifecycle()
    val pendingImportText by viewModel.pendingImportText.collectAsStateWithLifecycle()
    val feedbackReadyEvent by viewModel.feedbackReadyEvent.collectAsStateWithLifecycle()
    val historyNavigationRequests by viewModel.historyNavigationRequests.collectAsStateWithLifecycle()
    var experiencePromptDismissed by rememberSaveable { mutableStateOf(false) }
    val effectivePracticeExperience = practiceExperience ?: PracticeExperience.ALL_FEATURES
    val koreanCpx = effectivePracticeExperience == PracticeExperience.KOREAN_CPX
    // The Korean CPX track has two tabs of its own (stations, score history); if the learner
    // switches into it from another tab, land on its home rather than an English screen.
    LaunchedEffect(koreanCpx) {
        if (koreanCpx && currentTab !in setOf(0, 3)) currentTab = 0
    }

    LaunchedEffect(historyNavigationRequests) {
        if (historyNavigationRequests > 0L) currentTab = 3
    }

    // A share-sheet hand-off (Android ACTION_SEND from ChatGPT/Gemini/notes apps) should open the
    // Import screen immediately rather than silently waiting for the learner to find it — but never
    // over a live encounter or its just-finished feedback, which Import would otherwise cover (it
    // outranks both below). The shared text waits and Import opens once neither is showing.
    val feedbackPending = lastEvaluation != null
    LaunchedEffect(pendingImportText, activeSession.isActive, feedbackPending) {
        if (!pendingImportText.isNullOrBlank() && !activeSession.isActive && !feedbackPending) {
            showImportScreen = true
        }
    }
    // Once an import finishes analyzing, close this screen so the lastEvaluation != null branch
    // below can show the ordinary FeedbackScreen — otherwise this screen's own early return would
    // keep showing the (now-stale) Import form on top of a result that's ready to display.
    LaunchedEffect(lastEvaluation) {
        if (lastEvaluation != null) showImportScreen = false
    }

    var showDebrief by remember { mutableStateOf(false) }
    // Always-available spoken English coach, launched from the home-screen FAB independently of any
    // just-finished session (grounded in the learner's saved-mistake profile — see DebriefScreen's
    // standalone mode). Kept separate from showDebrief, which is the post-session debrief flow.
    var showStandaloneCoach by remember { mutableStateOf(false) }
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(feedbackReadyEvent) {
        val event = feedbackReadyEvent ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${event.caseName.ifBlank { "Your session" }} feedback is ready. Review it and study your corrections.",
            actionLabel = "History",
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite
        )
        if (result == SnackbarResult.ActionPerformed) currentTab = 3
        viewModel.consumeFeedbackReadyEvent()
    }

    // First-run education is its own state, separate from credential availability. Saving a
    // nonblank (but invalid) key can no longer dismiss onboarding, and an existing learner who
    // later clears a key gets the focused connection screen instead of the welcome tour again.
    if (!onboardingCompleted && practiceExperience == null) {
        PracticeExperienceScreen(onSelect = viewModel::updatePracticeExperience)
        return
    }

    if (!onboardingCompleted) {
        // The English tour is about practising medical English; a learner who picked the Korean
        // CPX track gets a Korean first run that sets up the key (or skips it) and nothing else.
        if (practiceExperience == PracticeExperience.KOREAN_CPX) {
            com.example.medvoicetrainer.ui.screens.KmleOnboardingScreen(viewModel = viewModel)
        } else {
            OnboardingScreen(viewModel = viewModel, onComplete = {})
        }
        return
    }

    // Require credentials only for a selected live provider. Backing out of this repair route
    // safely switches to the keyless demo rather than trapping the learner in a welcome loop.
    if (!hasVoiceBackendAccess(voiceBackend, apiKey, openAiApiKey)) {
        OnboardingScreen(
            viewModel = viewModel,
            startAtConnect = true,
            onComplete = {},
            onDismiss = {
                viewModel.updateVoiceBackend(
                    when {
                        apiKey.isNotBlank() -> "gemini"
                        openAiApiKey.isNotBlank() -> "openai"
                        else -> "demo"
                    }
                )
            }
        )
        return
    }

    // The exact same compact connection journey is reused after a demo result and from the
    // demo-complete dashboard; neither path dumps a novice into advanced provider settings.
    if (showApiSetup) {
        OnboardingScreen(
            viewModel = viewModel,
            startAtConnect = true,
            onComplete = viewModel::closeApiSetup,
            onDismiss = viewModel::closeApiSetup
        )
        return
    }

    if (showPreferences) {
        androidx.activity.compose.BackHandler {
            if (!backupState.blocksAppInteraction && !backupState.running) {
                showPreferences = false
                preferencesFocusProvider = null
            }
        }
        PreferencesScreen(
            viewModel,
            focusProvider = preferencesFocusProvider,
            onNavigateBack = {
                if (!backupState.blocksAppInteraction && !backupState.running) {
                    showPreferences = false
                    preferencesFocusProvider = null
                }
            },
            onSaved = {
                if (!backupState.blocksAppInteraction && !backupState.running) {
                    showPreferences = false
                    preferencesFocusProvider = null
                }
            },
            onOpenImport = {
                if (!backupState.blocksAppInteraction && !backupState.running) {
                    showPreferences = false
                    preferencesFocusProvider = null
                    showImportScreen = true
                }
            }
        )
        return
    }

    if (showImportScreen) {
        androidx.activity.compose.BackHandler { showImportScreen = false }
        ImportTranscriptScreen(
            viewModel = viewModel,
            prefillText = pendingImportText,
            onConsumedPrefill = { viewModel.consumePendingImportText() },
            onNavigateBack = { showImportScreen = false }
        )
        return
    }

    // The in-app Guide moved out of the bottom nav (freeing that slot for the Pronunciation Lab
    // tab) into a "?" action in the top bar. It opens as a full-screen overlay with its own back
    // affordance, matching the Preferences/Import pattern.
    if (showGuide) {
        androidx.activity.compose.BackHandler { showGuide = false }
        WikiScreen(viewModel = viewModel, onNavigateBack = { showGuide = false })
        return
    }

    // Standalone "English Coach" — the always-on home-screen entry point. Rendered before the
    // post-session feedback/debrief block so it can open any time, with no completed evaluation
    // required. Its chat is ephemeral (see DebriefScreen standalone mode).
    if (showStandaloneCoach) {
        DebriefScreen(
            viewModel = viewModel,
            apiKey = apiKey,
            model = viewModel.geminiModel.collectAsStateWithLifecycle().value,
            soapNote = "",
            everyday = false,
            sessionSummary = "",
            standalone = true,
            onNavigateBack = { showStandaloneCoach = false }
        )
        return
    }

    // Korean CPX peer mode: two students role-play in person while the phone records.
    val kmlePeer by viewModel.kmlePeerLaunch.collectAsStateWithLifecycle()
    val peerLaunch = kmlePeer
    if (peerLaunch != null && !activeSession.isActive && lastKmleResult == null) {
        androidx.activity.compose.BackHandler { viewModel.closeKmlePeer() }
        com.example.medvoicetrainer.ui.screens.KmlePeerScreen(
            viewModel = viewModel,
            launch = peerLaunch,
            onClose = { viewModel.closeKmlePeer() },
        )
        return
    }

    // Korean CPX result card: its own screen, never the English FeedbackScreen.
    val kmleResult = lastKmleResult
    if (kmleResult != null && !activeSession.isActive) {
        androidx.activity.compose.BackHandler { viewModel.dismissKmleResult() }
        com.example.medvoicetrainer.ui.screens.KmleCpxResultScreen(
            result = kmleResult,
            onClose = { viewModel.dismissKmleResult() },
            onOverride = { key, status -> viewModel.overrideKmleItem(kmleResult.sessionId, key, status) },
        )
        return
    }

    // A detached, slower analysis must never cover an in-progress conversation. The ViewModel
    // generation guard normally keeps such a result history-only; this UI condition is the final
    // defense against a stale result taking over the active session or its Back navigation.
    if (SessionCompletionOwnership.shouldPresentFeedback(
            hasEvaluation = lastEvaluation != null,
            hasActiveSession = activeSession.isActive,
        )
    ) {
        if (showDebrief) {
            // DebriefScreen owns its own BackHandler (it needs chatHistory in scope to persist
            // the conversation before leaving — see finalizeDebrief) instead of one registered
            // here, to avoid two competing BackHandlers for the same screen.
            DebriefScreen(
                viewModel = viewModel,
                apiKey = apiKey,
                model = viewModel.geminiModel.collectAsStateWithLifecycle().value,
                soapNote = lastEvaluation!!.soapNote,
                everyday = lastEvaluation!!.analysisDomain.equals("everyday", ignoreCase = true),
                sessionSummary = lastEvaluation!!.summaryFeedback,
                onNavigateBack = {
                    showDebrief = false
                    viewModel.dismissFeedback()
                }
            )
        } else {
            androidx.activity.compose.BackHandler { viewModel.dismissFeedback() }
            FeedbackScreen(
                viewModel = viewModel,
                evaluation = lastEvaluation!!,
                onNavigateBack = { viewModel.dismissFeedback() },
                onNavigateToDebrief = { showDebrief = true },
                onPresentToAttending = lastPresentationLaunch?.let { launch ->
                    {
                        viewModel.dismissFeedback()
                        // This action only exists after a completed voice encounter, so microphone
                        // permission/preflight has already succeeded in this app run.
                        viewModel.startSession("presentation", launch.id, launch.title, launch.caseJson)
                    }
                },
                // Closes the loop the recap opens: the expressions the learner was offered and did
                // not use are one tap from the drill that teaches them.
                onOpenPhraseDrill = { phrases ->
                    viewModel.dismissFeedback()
                    requestedPhraseDrill = phrases
                    requestedEverydayPracticeMode = "sayit_session"
                    currentTab = 1
                },
            )
        }
        // Post-session retention moments (milestone celebration / confidence check-in /
        // tomorrow toast) overlay on top of the feedback/debrief screen — see
        // MainViewModel.evaluatePostSessionMoments() and PostSessionMomentDialogs.kt.
        PostSessionMomentHost(viewModel)

        // Keyless-demo "what's next" decision screen (app/ui/demo_next_dialog.py), shown after
        // every typed-demo session's feedback instead of/on top of the normal retention moments.
        val demoStatus = demoTourStatus
        if (demoStatus != null) {
            com.example.medvoicetrainer.ui.screens.DemoNextDialog(
                tourComplete = demoStatus.tourComplete,
                doneCount = demoStatus.doneCount,
                totalCount = demoStatus.totalCount,
                everyday = demoStatus.everyday,
                onUnlockNow = {
                    viewModel.recordDemoDecision("A")
                    viewModel.dismissDemoTourStatus()
                    viewModel.dismissFeedback()
                    viewModel.openApiSetup()
                },
                onNextDemoPatient = if (demoStatus.everyday || !demoStatus.tourComplete) {
                    {
                        viewModel.recordDemoDecision("B")
                        viewModel.dismissDemoTourStatus()
                        viewModel.dismissFeedback()
                        // Jump straight into the next scripted patient (same contract as the
                        // onboarding "Start Demo" and Dashboard "Play next" entry points) instead
                        // of dropping the learner onto the main Practice tab — landing there mid
                        // keyless-tour read as the app losing its place rather than continuing it.
                        if (demoStatus.everyday) {
                            viewModel.startEverydayDemoSession()
                        } else {
                            val completed = viewModel.getCompletedDemoCaseIds()
                            val nextDemoId = com.example.medvoicetrainer.analysis.DemoTour.getFirstUnseenDemoCaseId(completed)
                            viewModel.startDemoSession(nextDemoId)
                        }
                    }
                } else null,
                onDecideLater = {
                    viewModel.recordDemoDecision("C")
                    viewModel.dismissDemoTourStatus()
                }
            )
        }
        return
    }

    // Ported from app/ui/coach.py's audio_preflight(): a one-time headphones-reminder + mic-test
    // primer shown before the user's very first real (non-demo) voice session. Gates every
    // session-start entry point uniformly via startGated() below.
    var pendingSessionStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAudioPreflight by remember { mutableStateOf(false) }
    // §13 "Mic permission denied": previously a silent no-op (pendingSessionStart just cleared,
    // no explanation, no way forward) — now a designed state with cause + one recovery action.
    var showMicPermissionDenied by remember { mutableStateOf(false) }
    // §A2 (early-UX): the session the learner was about to start, held so the mic-denied dialog can
    // offer to run it in typing mode instead of discarding it (a live session with no mic degrades
    // to the designed MIC_UNAVAILABLE / "Type instead" state — see ActiveSessionView's mic bar).
    var typedFallbackStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    // §3 (early-UX): kept off the mic-permission dialog's heels — stacking two OS permission
    // popups back-to-back at peak intent reads as spammy to a novice. Instead this fires right
    // before a real (non-demo/mock) session actually starts, separated from the mic prompt by the
    // in-app audio-preflight screen on a learner's first session. Without it, the foreground mic
    // service still runs (the OS's green mic dot still lights up) but Android silently drops its
    // required ongoing notification, leaving no in-app explanation that the mic is live in the
    // background — previously this was requested only via the opt-in next-day reminder banner
    // (PracticeReminder / FeedbackScreen), which most learners never reach or decline, so the
    // notification could go unrequested for the life of the install.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Display-only permission — the session never waits on or is blocked by the outcome. */ }

    fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            typedFallbackStart = pendingSessionStart
            pendingSessionStart = null
            showMicPermissionDenied = true
        } else if (viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_AUDIO_PREFLIGHT)) {
            requestNotificationPermissionIfNeeded()
            pendingSessionStart?.invoke()
            pendingSessionStart = null
        } else {
            showAudioPreflight = true
        }
    }

    fun startGated(startSession: () -> Unit) {
        pendingSessionStart = startSession
        val audioPreflightSeen = viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_AUDIO_PREFLIGHT)
        if (voiceBackend == "demo" || voiceBackend == "mock") {
            startSession()
            pendingSessionStart = null
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (audioPreflightSeen) {
            requestNotificationPermissionIfNeeded()
            startSession()
            pendingSessionStart = null
        } else {
            showAudioPreflight = true
        }
    }

    if (showAudioPreflight) {
        AudioPreflightDialog(
            onProceed = {
                viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_AUDIO_PREFLIGHT)
                showAudioPreflight = false
                requestNotificationPermissionIfNeeded()
                pendingSessionStart?.invoke()
                pendingSessionStart = null
            },
            onCancel = {
                showAudioPreflight = false
                pendingSessionStart = null
            }
        )
    }

    if (showMicPermissionDenied) {
        AlertDialog(
            onDismissRequest = { showMicPermissionDenied = false },
            title = { Text(t("early.mic_denied_title")) },
            text = { Text(t("early.mic_denied_body")) },
            // §A2 (early-UX): the primary action is now "start in typing mode", so a mic-denied
            // learner can still do the whole encounter by typing rather than being turned away.
            confirmButton = {
                val fallback = typedFallbackStart
                if (fallback != null) {
                    Button(onClick = {
                        showMicPermissionDenied = false
                        typedFallbackStart = null
                        fallback()
                    }) {
                        Text(t("early.mic_denied_type"))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showMicPermissionDenied = false
                        typedFallbackStart = null
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    }) {
                        Text(t("early.mic_denied_settings"))
                    }
                    TextButton(onClick = {
                        showMicPermissionDenied = false
                        typedFallbackStart = null
                    }) {
                        Text(t("early.mic_denied_later"))
                    }
                }
            }
        )
    }

    // First-run coach-mark tour: shown once when a brand-new learner first reaches the main
    // dashboard (right after onboarding / API-key entry). Reuses the same one-time primer flag
    // mechanism as the rest of the first-run coaching (see Coach.KEY_HOME_TOUR). This remember only
    // runs once the early-return onboarding/API-setup gates above are cleared, so it reads the flag
    // at the right moment — the learner's first arrival on the real app.
    var showHomeTour by remember {
        mutableStateOf(!viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_HOME_TOUR))
    }

    // Bottom-navigation destinations are peer screens rather than entries in Android's back
    // stack. Without this handler, pressing Back from Practice, Review, History, or the
    // Pronunciation Lab immediately finishes the activity, which feels like the app lost the
    // learner's place. Child screens (for example an open detail pane) compose their own handler
    // later and therefore get first refusal.
    androidx.activity.compose.BackHandler(enabled = !activeSession.isActive && currentTab != 0) {
        currentTab = 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!activeSession.isActive) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = "Logo",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                if (koreanCpx) "CPX 연습" else t("Bedside English"),
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                        }
                    },
                    actions = {
                        if (effectivePracticeExperience == PracticeExperience.ALL_FEATURES) {
                            IconButton(
                                onClick = { showGuide = true },
                                modifier = Modifier.onGloballyPositioned {
                                    tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.HELP] = it.boundsInRoot()
                                }
                            ) {
                                Icon(Icons.Default.HelpOutline, contentDescription = t("Guide"), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { showPreferences = true }) {
                            Icon(Icons.Default.Settings, contentDescription = t("Settings"), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        bottomBar = {
            if (!activeSession.isActive && !keyboardVisible) {
                // §14 "Font scaling": at extreme system font scales, 5 labeled tabs stop fitting
                // ("SRS Reviews" is the longest) — the spec explicitly allows folding to icon-only
                // rather than truncating/wrapping. Not on-device-verified at 200% (no
                // emulator/device in this sandbox); the threshold is a conservative guess.
                val extremeFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.8f
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        modifier = Modifier.onGloballyPositioned {
                            tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.HOME] = it.boundsInRoot()
                        },
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = if (koreanCpx) "CPX 스테이션" else t("Home")) },
                        label = if (extremeFontScale) null else ({ Text(if (koreanCpx) "스테이션" else t("Home"), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                        alwaysShowLabel = !extremeFontScale
                    )
                    // The Korean CPX track has no English practice, pronunciation, or SRS tools.
                    if (!koreanCpx) NavigationBarItem(
                        modifier = Modifier.onGloballyPositioned {
                            tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.PRACTICE] = it.boundsInRoot()
                        },
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = t("Practice")) },
                        label = if (extremeFontScale) null else ({ Text(t("Practice"), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                        alwaysShowLabel = !extremeFontScale
                    )
                    // Pronunciation Lab (screen index 4, reusing the slot the Guide vacated) sits
                    // third in the bar. Guide itself now lives in the top-bar "?" action.
                    if (!koreanCpx) NavigationBarItem(
                        modifier = Modifier.onGloballyPositioned {
                            tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.PRON_LAB] = it.boundsInRoot()
                        },
                        selected = currentTab == 4,
                        onClick = { currentTab = 4 },
                        icon = { Icon(Icons.Default.Mic, contentDescription = t("nav.pron_lab")) },
                        label = if (extremeFontScale) null else ({ Text(t("nav.pron_lab"), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                        alwaysShowLabel = !extremeFontScale
                    )
                    if (!koreanCpx) NavigationBarItem(
                        modifier = Modifier.onGloballyPositioned {
                            tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.SRS] = it.boundsInRoot()
                        },
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = { Icon(Icons.Default.Error, contentDescription = t("SRS Reviews")) },
                        label = if (extremeFontScale) null else ({ Text(t("SRS Reviews"), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                        alwaysShowLabel = !extremeFontScale
                    )
                    NavigationBarItem(
                        modifier = Modifier.onGloballyPositioned {
                            tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.HISTORY] = it.boundsInRoot()
                        },
                        selected = currentTab == 3,
                        onClick = { currentTab = 3 },
                        icon = { Icon(Icons.Default.History, contentDescription = if (koreanCpx) "채점 기록" else t("History")) },
                        label = if (extremeFontScale) null else ({ Text(if (koreanCpx) "기록" else t("History"), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                        alwaysShowLabel = !extremeFontScale
                    )
                }
            }
        },
        floatingActionButton = {
            // Spoken English coach, offered from the Home tab only. The other tabs are each their
            // own workflow with their own primary actions (pickers, review queues, lab controls),
            // and a floating coach button there competed with them instead of helping. Also hidden
            // during a live session (the active view owns the screen) and when there's no Gemini key
            // to generate replies (a keyless demo user would only hit errors). Scaffold
            // auto-positions it above the bottom nav bar.
            if (currentTab == 0 && !activeSession.isActive && apiKey.isNotBlank() && !koreanCpx) {
                FloatingActionButton(
                    onClick = { showStandaloneCoach = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.onGloballyPositioned {
                        tutorialAnchors[com.example.medvoicetrainer.ui.screens.TutorialTarget.COACH_FAB] = it.boundsInRoot()
                    }
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = t("Practice English out loud"),
                        tint = Color.White
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // contentWindowInsets above is safeDrawing, which *includes* the IME, so this
                // padding already lifts every screen clear of the keyboard. Marking those insets
                // consumed is what stops a nested imePadding()/safeDrawing padding from applying
                // the keyboard height a second time — the double padding that squeezed the active
                // session's composer to zero height and hid it behind the keyboard.
                .consumeWindowInsets(paddingValues)
        ) {
            // Screen contents
            when (currentTab) {
                0 -> if (koreanCpx && kmleMockState != null) {
                    com.example.medvoicetrainer.ui.screens.KmleMockExamScreen(
                        viewModel = viewModel,
                        mock = kmleMockState!!,
                        onEnter = { launch ->
                            startGated {
                                viewModel.startSession(
                                    com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE,
                                    launch.caseId,
                                    launch.title,
                                    launch.caseJson,
                                )
                            }
                        },
                    )
                } else if (koreanCpx) {
                    com.example.medvoicetrainer.ui.screens.KmleCpxHomeScreen(
                        viewModel = viewModel,
                        onStart = { launch ->
                            startGated {
                                viewModel.startSession(
                                    com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE,
                                    launch.caseId,
                                    launch.title,
                                    launch.caseJson,
                                )
                            }
                        },
                        onOpenHistory = { currentTab = 3 },
                        onOpenSettings = { showPreferences = true },
                    )
                } else if (effectivePracticeExperience == PracticeExperience.EVERYDAY_ENGLISH) {
                    EverydayDashboardScreen(
                        viewModel = viewModel,
                        onStartConversation = {
                            if (voiceBackend == "demo") {
                                viewModel.startEverydayDemoSession()
                            } else {
                                startGated {
                                    viewModel.startSession("survival", "survival_quick_start", "Survival English", "{}")
                                }
                            }
                        },
                        onOpenPracticeMode = { mode ->
                            requestedEverydayPracticeMode = mode
                            currentTab = 1
                        },
                        onOpenPronunciation = { currentTab = 4 },
                        onOpenSettings = { showPreferences = true }
                    )
                } else {
                    DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToTab = { currentTab = it },
                        onStartCase = { id, name, json ->
                            startGated {
                                viewModel.startSession(modeForGenericPracticeCase(id, json), id, name, json)
                            }
                        },
                        onStartExam = { id, name, json -> startGated { viewModel.startSession("exam", id, name, json) } },
                        onOpenSettings = {
                            if (voiceBackend == "demo") viewModel.openApiSetup() else showPreferences = true
                        },
                        onOpenImport = { showImportScreen = true }
                    )
                }
                1 -> PracticeScreen(
                    viewModel = viewModel,
                    onStartCase = { id, name, json ->
                        startGated {
                            viewModel.startSession(modeForGenericPracticeCase(id, json), id, name, json)
                        }
                    },
                    onStartFollowUp = { id, name, json -> startGated { viewModel.startSession("follow_up", id, name, json) } },
                    onStartSurvival = { id, name, json -> startGated { viewModel.startSession("survival", id, name, json) } },
                    onStartListening = { id, name, json -> startGated { viewModel.startSession("listening", id, name, json) } },
                    onStartTeachback = { id, name, json -> startGated { viewModel.startSession("teachback", id, name, json) } },
                    onStartInterview = { id, name, json -> startGated { viewModel.startSession("interview", id, name, json) } },
                    onStartExam = { id, name, json -> startGated { viewModel.startSession("exam", id, name, json) } },
                    requestedMode = requestedEverydayPracticeMode,
                    requestedPhraseDrill = requestedPhraseDrill,
                    onRequestedModeConsumed = {
                        requestedEverydayPracticeMode = null
                        requestedPhraseDrill = emptyList()
                    }
                )
                2 -> SrsScreen(
                    viewModel = viewModel,
                    onNavigateToTab = { currentTab = it },
                    onStartCase = { id, name, json ->
                        startGated {
                            viewModel.startSession(modeForGenericPracticeCase(id, json), id, name, json)
                        }
                    },
                )
                3 -> if (koreanCpx) {
                    com.example.medvoicetrainer.ui.screens.KmleCpxHistoryScreen(viewModel = viewModel)
                } else HistoryScreen(
                    viewModel = viewModel,
                    onNavigateToTab = { currentTab = it },
                    onStartCase = { id, name, json -> startGated { viewModel.startSession("presentation", id, name, json) } },
                    onOpenPhraseDrill = { phrases ->
                        requestedPhraseDrill = phrases
                        requestedEverydayPracticeMode = "sayit_session"
                        currentTab = 1
                    },
                )
                4 -> PronunciationLabScreen(viewModel = viewModel, onNavigateToTab = { currentTab = it })
            }

            // Overlay: Active Clinical Encounter Simulation View
            if (activeSession.isActive) {
                if (activeSession.mode == "listening") {
                    com.example.medvoicetrainer.ui.screens.ListeningLabScreen(viewModel = viewModel, activeSession = activeSession)
                } else {
                    ActiveSessionView(viewModel = viewModel, activeSession = activeSession)
                }
            }

            // Google Play flexible-update prompt ("downloaded — restart to install"). Lives inside
            // the scaffold content, which Scaffold has already inset for the tab bar, so it sits
            // above the tabs instead of covering them. Suppressed during a live session: the mic
            // bar and composer own the bottom of the screen there, and an update can wait for the
            // encounter to end.
            if (!activeSession.isActive) {
                com.example.medvoicetrainer.update.InAppUpdateBanner(
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Ported from app/ui/error_dialog.py — a friendly, recoverable "something went
            // wrong" dialog for failures inside primary user actions (see startSession's
            // try/catch). Overlays whichever tab/session view is currently showing.
            com.example.medvoicetrainer.ui.screens.ErrorDialogHost(
                viewModel,
                onOpenPreferences = { provider ->
                    preferencesFocusProvider = provider
                    showPreferences = true
                }
            )
        }
    }

        // First-run spotlight tour, overlaid above the scaffold (bars included). Suppressed during a
        // live session, when the bars are hidden and their measured anchors would be stale.
        if (showHomeTour && !activeSession.isActive && effectivePracticeExperience == PracticeExperience.ALL_FEATURES) {
            com.example.medvoicetrainer.ui.screens.FirstRunTutorialOverlay(
                anchors = tutorialAnchors,
                // Mirrors the FAB's own gate — the tour must not spotlight a control that isn't
                // on screen (the FAB is Home-only now).
                coachFabPresent = apiKey.isNotBlank() && currentTab == 0,
                onFinish = {
                    viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_HOME_TOUR)
                    showHomeTour = false
                }
            )
        }

        // Existing installs keep their current all-features UI until they choose. The prompt is
        // dismissible for the current run, unlike the required first screen on a brand-new install.
        if (onboardingCompleted && practiceExperience == null && !experiencePromptDismissed && !activeSession.isActive) {
            PracticeExperiencePromptDialog(
                onSelect = viewModel::updatePracticeExperience,
                onNotNow = { experiencePromptDismissed = true }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionView(viewModel: MainViewModel, activeSession: ActiveSessionState) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    var textInput by remember { mutableStateOf("") }
    var aiResponseToReport by remember { mutableStateOf<String?>(null) }
    // Phase label of the "Stuck? Question idea" suggestion currently sitting in the typing box.
    // Shown as a caption above the box only — never prefixed onto the text the learner sends.
    var suggestedHintCategory by remember { mutableStateOf("") }
    var showTypedInput by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var showEndSheet by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var showSessionMenu by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // A follow-up visit is premised on the doctor already knowing the chart, so the pre-visit brief
    // has to stay reachable once the conversation starts — otherwise the mode quietly becomes a
    // memory test, and any entry point that skips the review screen starts the learner blind.
    var showFollowUpChart by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var showInvestigationResults by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val authoredInvestigationEvents = remember(activeSession.caseJson) {
        com.example.medvoicetrainer.analysis.InvestigationResults.parseEvents(activeSession.caseJson)
    }
    // Team calls are assessed on structure and prioritisation, not on memorising an invisible
    // patient chart. Keep the same learner brief reachable after the pre-call review.
    var showTeamBrief by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val presentationSoapNotes = remember(activeSession.caseJson) {
        PresentationSoapNotes.fromCaseJson(activeSession.caseJson)
    }
    var showPresentationSoap by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle()
    // Live loudness of the audio actually reaching the provider — drives the mic-bar waveform so
    // the learner can see the app is hearing them. Its own StateFlow (not ActiveSessionState) so
    // these ~10x/second updates only redraw the small waveform, not the whole session view.
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    // Survival "Advanced Beta" only — all three stay null for every other session.
    val sceneTransition by viewModel.sceneTransition.collectAsStateWithLifecycle()
    val sceneTransitionTimeoutMillis by viewModel.sceneTransitionTimeoutMillis.collectAsStateWithLifecycle()
    val relayFactCard by viewModel.relayFactCard.collectAsStateWithLifecycle()
    val sceneCharacter by viewModel.sceneCharacter.collectAsStateWithLifecycle()

    // Beginner guided scaffold: only Foundations / coaching_mode cases opt in, and only when the
    // learner hasn't switched it off in Preferences. Computed here so both the TTS engine and the
    // live cue card below are created only for a case that actually uses them.
    val guidedModeEnabled by viewModel.guidedModeEnabled.collectAsStateWithLifecycle()
    val isCoachingCase = remember(activeSession.caseJson) {
        com.example.medvoicetrainer.analysis.GuidedCueEngine.isCoachingCase(activeSession.caseJson)
    }
    val guidedActive = guidedModeEnabled && isCoachingCase
    var guidedHidden by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val guidedTts = if (guidedActive) com.example.medvoicetrainer.ui.screens.rememberEnglishTts() else null
    val guidedCue = remember(activeSession.checklistCoverage, activeSession.caseJson, guidedActive, guidedHidden) {
        if (!guidedActive || guidedHidden) {
            null
        } else {
            com.example.medvoicetrainer.analysis.GuidedCueEngine.nextCue(
                caseJson = activeSession.caseJson,
                coverage = activeSession.checklistCoverage,
                successCountFor = { obj -> viewModel.guidedSuccessCount(obj) }
            )
        }
    }

    // --- Open Book (OpenBookEngine): one parsed card powers two independent experiences: the
    // pre-visit English-practice notes and optional mid-visit rescue help. The preference hides
    // only the latter; the card itself is null only when the mode/case has no supported diagnosis.
    val openBookCard by viewModel.openBookCard.collectAsStateWithLifecycle()
    val openBookEnabled by viewModel.openBookEnabled.collectAsStateWithLifecycle()
    val openBookLevel by viewModel.openBookLevel.collectAsStateWithLifecycle()
    val openBookClosingLevel by viewModel.openBookClosingLevel.collectAsStateWithLifecycle()
    val openBookMicPolicy by viewModel.openBookMicPolicy.collectAsStateWithLifecycle()
    val openBookBriefed by viewModel.openBookBriefed.collectAsStateWithLifecycle()
    val openBookBriefingPending by viewModel.openBookBriefingPending.collectAsStateWithLifecycle()
    val openBookPreviousLevel by viewModel.openBookPreviousLevel.collectAsStateWithLifecycle()
    var showOpenBook by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var openBookInitialPhrases by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // Whether *this sheet* is what is holding the microphone muted. Without it, closing the sheet
    // would un-mute a learner who had deliberately muted themselves before opening it.
    var openBookHeldMic by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // Set once the learner has taken the microphone back by hand — either by muting themselves
    // before opening the sheet, or by un-muting from inside it. From then on the sheet stops
    // managing the mic for the rest of the session: a scaffold that keeps overriding a deliberate
    // choice is worse than one that never touches it.
    var openBookMicUnmanaged by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // The learner dismissed the pinned strip. Kept per case so it does not follow them to the next
    // encounter, and never auto-restored — dismissing it is a statement about this screen.
    var openBookHudDismissed by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val openBookTts = if (openBookCard != null) {
        com.example.medvoicetrainer.ui.screens.rememberEnglishTts()
    } else {
        null
    }
    val closeOpenBook: () -> Unit = {
        showOpenBook = false
        openBookInitialPhrases = false
        if (openBookHeldMic) {
            viewModel.setMicMuted(false)
            openBookHeldMic = false
        }
    }
    val openOpenBook: () -> Unit = {
        openBookInitialPhrases = false
        // Preserve a manual mute no matter which Open Book entry point was used. Previously only
        // the main chip did this check; reopening from the pinned HUD under ALWAYS policy made the
        // sheet claim ownership and unmute the learner when it closed.
        if (com.example.medvoicetrainer.analysis.OpenBookEngine.shouldLeaveMicUnmanaged(
                micMuted = micMuted,
                heldByOpenBook = openBookHeldMic,
            )
        ) {
            openBookMicUnmanaged = true
        }
        showOpenBook = true
        openBookHudDismissed = false
    }
    val openOpenBookPhrases: () -> Unit = {
        openBookInitialPhrases = true
        if (com.example.medvoicetrainer.analysis.OpenBookEngine.shouldLeaveMicUnmanaged(
                micMuted = micMuted,
                heldByOpenBook = openBookHeldMic,
            )
        ) {
            openBookMicUnmanaged = true
        }
        showOpenBook = true
        openBookHudDismissed = false
    }

    // --- Everyday Phrasebook (EverydayPhrasebook): the same idea for the everyday modes, where
    // nothing clinical is hidden and what runs out is the English sentence itself. Null in clinical
    // modes, so the two answer sheets never appear behind one conversation.
    val phrasebookCard by viewModel.phrasebookCard.collectAsStateWithLifecycle()
    val phrasebookLevel by viewModel.phrasebookLevel.collectAsStateWithLifecycle()
    val myPhrasebook by viewModel.myPhrasebook.collectAsStateWithLifecycle()
    var showPhrasebook by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var phrasebookHeldMic by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var phrasebookMicUnmanaged by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val phrasebookTts = if (phrasebookCard != null) {
        com.example.medvoicetrainer.ui.screens.rememberEnglishTts()
    } else {
        null
    }
    val closePhrasebook: () -> Unit = {
        showPhrasebook = false
        if (phrasebookHeldMic) {
            viewModel.setMicMuted(false)
            phrasebookHeldMic = false
        }
    }
    val openPhrasebook: () -> Unit = {
        if (com.example.medvoicetrainer.analysis.OpenBookEngine.shouldLeaveMicUnmanaged(
                micMuted = micMuted,
                heldByOpenBook = phrasebookHeldMic,
            )
        ) {
            phrasebookMicUnmanaged = true
        }
        showPhrasebook = true
    }

    var earOnly by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    var revealedPatientTurn by rememberSaveable(activeSession.caseId) { mutableIntStateOf(-1) }
    // Live checklist: which row is opened for its evidence, and whether the learner has asked to
    // see every objective rather than the default "what's done + what's next" summary.
    var expandedChecklistRow by rememberSaveable(activeSession.caseId) { mutableStateOf<String?>(null) }
    var checklistShowAll by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // The "Stuck?" picker. Offering the ranked shortlist beats committing to one suggestion, since
    // the rules are much better at narrowing the field than at ordering it.
    var showHintPicker by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    // Typed Demo/mock never starts a live microphone (VoiceManager.shouldStartLiveMicrophone) —
    // the mic bar only makes sense, and is only shown, when a real provider is actually listening.
    val usesLiveMic = voiceBackend !in setOf("demo", "mock")
    val isSampleScenario = voiceBackend == "demo"

    // Open Book's microphone hold, re-applied whenever the sheet or its level changes rather than
    // decided once when it opens. The levels differ in kind: the candidate list and the diagnosis
    // have to be read and thought about (mute, so the patient isn't talking into a mic nobody is
    // listening to), while the checklist, the model questions and the closing script are meant to
    // be said out loud off the screen. Deciding once left the learner muted through the half of the
    // sheet they were supposed to be reading aloud, so they closed and reopened it per sentence.
    LaunchedEffect(
        showOpenBook,
        openBookLevel,
        openBookBriefed,
        openBookClosingLevel,
        openBookMicPolicy,
        usesLiveMic,
        openBookMicUnmanaged,
    ) {
        if (!usesLiveMic || openBookMicUnmanaged) return@LaunchedEffect
        // Judged on the level the sheet shows: a briefed learner already sees the checklist.
        val hold = showOpenBook && com.example.medvoicetrainer.analysis.OpenBookEngine.shouldHoldMic(
            policy = openBookMicPolicy,
            level = com.example.medvoicetrainer.analysis.OpenBookEngine.displayedLevel(openBookLevel, openBookBriefed),
            closingLevel = openBookClosingLevel,
        )
        if (hold != openBookHeldMic) {
            viewModel.setMicMuted(hold)
            openBookHeldMic = hold
        }
    }
    // The same hold for the everyday sheet, sharing the learner's one answer to "may a scaffold
    // mute me while I read it". Released at the sentence level, which is meant to be read aloud.
    LaunchedEffect(showPhrasebook, phrasebookLevel, openBookMicPolicy, usesLiveMic, phrasebookMicUnmanaged) {
        if (!usesLiveMic || phrasebookMicUnmanaged) return@LaunchedEffect
        val hold = showPhrasebook && com.example.medvoicetrainer.analysis.EverydayPhrasebook.shouldHoldMic(
            policy = openBookMicPolicy,
            level = phrasebookLevel,
        )
        if (hold != phrasebookHeldMic) {
            viewModel.setMicMuted(hold)
            phrasebookHeldMic = hold
        }
    }
    val completedSampleTurns = activeSession.transcript.count { it.first == "doctor" }
    val nextSampleLine = remember(activeSession.caseId, completedSampleTurns) {
        com.example.medvoicetrainer.analysis.DemoTour.nextSampleLearnerLine(
            activeSession.caseId,
            completedSampleTurns
        )
    }
    val sampleLineCount = remember(activeSession.caseId) {
        com.example.medvoicetrainer.analysis.DemoTour.sampleLearnerLineCount(activeSession.caseId)
    }
    val turnState = deriveTurnState(activeSession, micMuted)
    // "Send turn" is recovery, not part of the normal turn-taking model. Surface it only after
    // this device heard speech followed by several seconds of silence with no AI response.
    var heardSpeechThisTurn by remember(activeSession.caseId) { mutableStateOf(false) }
    var showSendTurnRecovery by remember(activeSession.caseId) { mutableStateOf(false) }
    val speechActive = micLevel >= 0.006f
    LaunchedEffect(turnState, speechActive, activeSession.isManualTurnSending) {
        if (turnState != TurnState.YOUR_TURN || activeSession.isManualTurnSending) {
            heardSpeechThisTurn = false
            showSendTurnRecovery = false
        } else if (speechActive) {
            heardSpeechThisTurn = true
            showSendTurnRecovery = false
        } else if (heardSpeechThisTurn) {
            delay(3_500L)
            if (turnState == TurnState.YOUR_TURN && !speechActive && heardSpeechThisTurn) {
                showSendTurnRecovery = true
            }
        }
    }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // IME animation frames are consumed by imePadding during layout. This derived boolean only
    // recomposes the heavy controls once when the keyboard opens or closes.
    val isImeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    val submitTypedTurn = {
        if (textInput.trim().isNotEmpty() && viewModel.addLearnerTurn(textInput)) {
            textInput = ""
            suggestedHintCategory = ""
        }
    }

    // First-encounter English-first framing (corrects the "this is a diagnosis trainer" misread).
    // §4 (early-UX): the blocking dialog is no longer shown on its own over a connecting live
    // session — for a live first encounter it is folded into the opening scaffold below, and the
    // standalone dialog is kept only for the case where that scaffold won't appear (see further
    // down, gated on !showOpeningScaffold).
    val englishFirstIntroSeen by viewModel.englishFirstIntroSeen.collectAsStateWithLifecycle()

    // docs/design/android-ui-spec.html §5: system back during a live session must always open
    // the "End this session?" confirm — never a silent drop. This was previously unhandled here,
    // so a stray back press fell through and could exit the app mid-conversation.
    // A sample scenario has no session to end (no analysis, no cost) — the generic sheet's
    // "scores, corrections, SRS cards" copy doesn't apply, and its "Discard" option let a learner
    // land straight back on the dashboard with no reminder to add a Gemini key. Back is a no-op
    // here instead; "Finish sample scenario" is the only way out, same as it is for the X below.
    androidx.activity.compose.BackHandler(enabled = !activeSession.isFinishing && !isSampleScenario) { showEndSheet = true }
    if (isSampleScenario) {
        androidx.activity.compose.BackHandler { }
    }

    val modeLabels = remember(activeSession.mode, isSampleScenario, t) {
        if (isSampleScenario) {
            listOf(
                t("demo.sample.doctor_label"),
                t("demo.sample.patient_label"),
                "",
                t("demo.sample.finish")
            )
        } else when (activeSession.mode) {
            "interview" -> listOf(t("CANDIDATE (YOU)"), t("INTERVIEWER"), t("Answer the interview question…"), t("FINISH INTERVIEW & SCORE"))
            "teachback" -> listOf(t("TEACHER (YOU)"), t("LEARNER"), t("Explain the idea in plain English…"), t("FINISH TEACH-BACK & SCORE"))
            "survival", "listening" -> listOf(t("YOU"), t("CONVERSATION PARTNER"), t("Respond naturally…"), t("FINISH PRACTICE & SCORE"))
            "lounge" -> listOf(t("YOU"), t("CONVERSATION PARTNER"), t("Continue the conversation…"), t("FINISH CONVERSATION & SCORE"))
            "follow_up" -> listOf(t("DOCTOR (YOU)"), t("RETURNING PATIENT"), t("Review progress since the last visit…"), t("FINISH FOLLOW-UP & SCORE"))
            "presentation" -> listOf(t("PRESENTER (YOU)"), t("ATTENDING"), t("Present the case…"), t("FINISH PRESENTATION & SCORE"))
            "team_communication" -> listOf(t("CLINICIAN (YOU)"), t("TEAM MEMBER"), t("Give your structured message…"), t("FINISH & SCORE"))
            // One counterpart label for all four nursing task families: across them the other
            // voice may be a doctor, a nurse, a patient, or a relative, so a role-specific label
            // would be wrong three times out of four.
            "nursing" -> listOf(t("NURSE (YOU)"), t("THE OTHER PERSON"), t("Speak as the nurse…"), t("FINISH & SCORE"))
            com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE -> listOf("학생의사 (나)", "환자", "환자에게 말하기…", "진료 종료 및 채점")
            "exam" -> listOf(t("CANDIDATE (YOU)"), t("EXAMINER / PATIENT"), t("Respond to the station…"), t("FINISH STATION & SCORE"))
            else -> listOf(t("DOCTOR (YOU)"), t("PATIENT"), t("Ask about symptoms, pain, concerns…"), t("FINISH ENCOUNTER & SCORE"))
        }
    }
    var elapsedSeconds by remember(activeSession.createdAt) { mutableIntStateOf(0) }
    // Nursing scenarios with a station length (OET role-plays are 5 minutes) show it next to the
    // clock, so the learner practises pacing the conversation the way the exam will time it.
    val stationSeconds = remember(activeSession.caseJson, activeSession.mode) {
        if (activeSession.mode != "nursing" && activeSession.mode != com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE) 0
        else runCatching { org.json.JSONObject(activeSession.caseJson).optInt("station_minutes", 0) * 60 }.getOrDefault(0)
    }
    LaunchedEffect(
        activeSession.createdAt,
        activeSession.pausedAtMillis,
        activeSession.totalPausedMillis,
    ) {
        while (true) {
            elapsedSeconds = try {
                val now = System.currentTimeMillis()
                val start = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    java.util.Locale.US
                ).parse(activeSession.createdAt)?.time ?: now
                val currentPause = activeSession.pausedAtMillis?.let { pausedAt ->
                    (now - pausedAt).coerceAtLeast(0L)
                } ?: 0L
                ((now - start - activeSession.totalPausedMillis - currentPause) / 1000L)
                    .toInt().coerceAtLeast(0)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                0
            }
            delay(1_000)
        }
    }

    // Korean CPX station: the exam's "종료 2분 전" and "시험 종료" announcements, the problem sheet
    // kept on the desk, and (as a study aid) the authored finding for each examination performed.
    val isKmle = activeSession.mode == com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE
    val kmleSession = remember(activeSession.caseJson, isKmle) {
        if (isKmle) com.example.medvoicetrainer.analysis.KmleCpx.sessionCase(activeSession.caseJson) else null
    }
    val storedKmlePrefs by viewModel.kmlePrefs.collectAsStateWithLifecycle()
    // A mock-exam station runs like the exam whatever the learner's practice settings: strict
    // clock, no findings, no complaint hint — and the sheet opens on entry, since reading it is
    // part of the 12 minutes.
    val isMockStation = remember(activeSession.caseJson, isKmle) {
        isKmle && com.example.medvoicetrainer.analysis.KmleCpx.mockExamId(activeSession.caseJson).isNotEmpty()
    }
    val kmlePrefs = if (isMockStation) {
        storedKmlePrefs.copy(strictTimer = true, showFindings = false, showComplaint = false, showChecklistHint = false)
    } else storedKmlePrefs
    var showKmleSheet by rememberSaveable(activeSession.caseId) { mutableStateOf(isMockStation) }
    var kmleAnnouncement by remember(activeSession.createdAt) { mutableStateOf<String?>(null) }
    var kmleWarned by remember(activeSession.createdAt) { mutableStateOf(false) }
    var kmleTimeUp by remember(activeSession.createdAt) { mutableStateOf(false) }
    LaunchedEffect(isKmle, elapsedSeconds, stationSeconds) {
        if (!isKmle || stationSeconds <= 0 || !activeSession.isActive || activeSession.isFinishing) return@LaunchedEffect
        if (!kmleWarned && elapsedSeconds >= stationSeconds - 120 && elapsedSeconds < stationSeconds) {
            kmleWarned = true
            kmleAnnouncement = "종료 2분 전입니다. 진료를 마무리하세요."
            com.example.medvoicetrainer.ui.StationChime.play(double = false)
        }
        if (!kmleTimeUp && elapsedSeconds >= stationSeconds) {
            kmleTimeUp = true
            com.example.medvoicetrainer.ui.StationChime.play(double = true)
            if (kmlePrefs.strictTimer) {
                kmleAnnouncement = "시험 종료. 채점을 시작합니다."
                viewModel.finishSession()
            } else {
                kmleAnnouncement = "시험 종료 시간입니다. 실제 시험에서는 여기서 바로 나가야 합니다."
            }
        }
    }
    LaunchedEffect(kmleAnnouncement) {
        if (kmleAnnouncement != null) {
            delay(8_000)
            kmleAnnouncement = null
        }
    }
    val latestExamFinding = remember(activeSession.revealedExamManeuvers, kmleSession) {
        val id = activeSession.revealedExamManeuvers.lastOrNull()
        val script = kmleSession?.script
        if (id == null || script == null) null
        else com.example.medvoicetrainer.analysis.SpScript.findingsFor(script, listOf(id)).firstOrNull()
    }
    var visibleExamFinding by remember(activeSession.createdAt) {
        mutableStateOf<com.example.medvoicetrainer.analysis.SpScript.Finding?>(null)
    }
    LaunchedEffect(latestExamFinding) {
        visibleExamFinding = latestExamFinding
        if (latestExamFinding != null) {
            delay(10_000)
            visibleExamFinding = null
        }
    }

    // §14 "Motion": autoscroll jumps instead of smooth-scrolls under reduced motion.
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()
    LaunchedEffect(activeSession.transcript.size, isImeVisible) {
        val visibleItemCount = activeSession.transcript.size
        if (visibleItemCount > 0) {
            val lastVisibleItem = visibleItemCount - 1
            if (reducedMotion || isImeVisible) {
                listState.scrollToItem(lastVisibleItem)
            } else {
                listState.animateScrollToItem(lastVisibleItem)
            }
        }
    }

    // Ported from app/ui/coach.py's first_session_tip(): a one-line in-session coaching tip
    // shown once, ever — Python inserts it as a fake "system" transcript line; Kotlin's
    // transcript only models doctor/patient turns, so this is a dismissible banner instead
    // (same information, no new transcript role to teach every other transcript consumer about).
    val wasSessionTipSeen = remember { viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_SESSION_TIP) }
    var showSessionTip by remember { mutableStateOf(!isSampleScenario && !wasSessionTipSeen) }
    LaunchedEffect(isSampleScenario) {
        if (!isSampleScenario && !wasSessionTipSeen) {
            viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_SESSION_TIP)
        }
    }

    // §1 (early-UX): a one-time, thin, dismissible reassurance that speaking is optional — the
    // key-entered learner skips the scripted demo entirely, so this is the escape hatch for anyone
    // frozen in front of a live open mic on their very first session.
    val wasSpeakOptionalSeen = remember { viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_FIRST_LIVE_SPEAK_OPTIONAL) }
    var showSpeakOptional by remember { mutableStateOf(usesLiveMic && !wasSpeakOptionalSeen) }
    LaunchedEffect(Unit) {
        if (usesLiveMic && !wasSpeakOptionalSeen) viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_FIRST_LIVE_SPEAK_OPTIONAL)
    }

    // §5 "FIRST 30s" opening scaffold: a real encounter, live mic, blank transcript, never shown
    // before. Marked seen the moment either side speaks, so it never reappears mid-conversation.
    val wasOpeningScaffoldSeen = remember {
        viewModel.isCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_OPENING_SCAFFOLD)
    }
    var openingScaffoldDismissed by rememberSaveable(activeSession.caseId) { mutableStateOf(false) }
    val showOpeningScaffold = usesLiveMic && activeSession.mode == "encounter" &&
        activeSession.transcript.isEmpty() && !wasOpeningScaffoldSeen && !openingScaffoldDismissed
    LaunchedEffect(activeSession.transcript.isEmpty(), isSampleScenario) {
        if (!isSampleScenario && activeSession.transcript.isNotEmpty()) {
            viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_OPENING_SCAFFOLD)
            // §4: the scaffold carries the English-first framing, so a learner who starts talking
            // has effectively "seen" it — don't then pop the standalone dialog on a later encounter.
            if (!englishFirstIntroSeen) viewModel.markEnglishFirstIntroSeen()
        }
    }

    // §4 (early-UX): standalone English-first dialog only when the scaffold won't show (e.g. a
    // typed/demo first encounter). For a live first encounter the framing is folded into the
    // scaffold card, so the learner is never blocked by a modal over a connecting session.
    if (activeSession.mode == "encounter" && !isSampleScenario &&
        !englishFirstIntroSeen && !showOpeningScaffold
    ) {
        com.example.medvoicetrainer.ui.screens.EnglishFirstIntroDialog(
            onDismiss = { viewModel.markEnglishFirstIntroSeen() }
        )
    }

    // §5 "WAIT" analyzing state: the ~20s gap between End and feedback previously showed the
    // ordinary chat view with a loading bubble. This takes over the whole screen instead, with
    // real progress steps, an explicit safety promise, and a retry/skip escape hatch.
    if (activeSession.isFinishing) {
        AnalyzingScreen(
            activeSession = activeSession,
            onRetry = { viewModel.finishSession() },
            onContinueInBackground = { viewModel.continueAnalysisInBackground() },
            // "Skip analysis, keep the transcript" is a decision, not a loss: record it so Home
            // doesn't greet the learner with a card asking whether to analyze it after all.
            onSkipKeepTranscript = {
                viewModel.cancelSession(com.example.medvoicetrainer.db.SessionEndReason.KEPT_UNANALYZED)
            }
        )
        return
    }

    if (showEndSheet) {
        EndSessionSheet(
            onDismiss = { showEndSheet = false },
            onFinishAndAnalyze = {
                showEndSheet = false
                viewModel.finishSession()
            },
            onKeepTalking = { showEndSheet = false },
            onDiscard = {
                showEndSheet = false
                viewModel.cancelSession(com.example.medvoicetrainer.db.SessionEndReason.DISCARDED)
            }
        )
    }

    if (showFollowUpChart) {
        FollowUpChartSheet(
            caseJson = activeSession.caseJson,
            onDismiss = { showFollowUpChart = false },
        )
    }

    if (showInvestigationResults) {
        InvestigationResultsSheet(
            availableResults = activeSession.availableResults,
            authoredEvents = authoredInvestigationEvents,
            pendingEvents = activeSession.pendingInvestigationEvents,
            revealedEvents = activeSession.revealedInvestigationEvents,
            onOrder = viewModel::orderInvestigation,
            onViewResult = viewModel::revealInvestigationResult,
            onDismiss = { showInvestigationResults = false },
        )
    }

    if (showTeamBrief) {
        TeamCommunicationBriefSheet(
            caseJson = activeSession.caseJson,
            onDismiss = { showTeamBrief = false },
        )
    }

    if (showKmleSheet && kmleSession != null) {
        com.example.medvoicetrainer.ui.screens.KmleStationSheet(
            session = kmleSession,
            revealedManeuvers = activeSession.revealedExamManeuvers,
            showFindings = kmlePrefs.showFindings,
            showComplaint = kmlePrefs.showComplaint,
            showChecklistHint = kmlePrefs.showChecklistHint,
            onDismiss = { showKmleSheet = false },
        )
    }

    if (showPresentationSoap) {
        presentationSoapNotes?.let { notes ->
            PresentationSoapSheet(notes = notes, onDismiss = { showPresentationSoap = false })
        }
    }

    if (showHintPicker) {
        QuestionIdeaSheet(
            options = activeSession.phaseHintOptions,
            onDismiss = { showHintPicker = false },
            onPick = { hint ->
                showHintPicker = false
                textInput = hint.question
                suggestedHintCategory = hint.category
                showTypedInput = true
                viewModel.recordLearningEvent(
                    "continuation_hint",
                    mapOf("reason" to hint.reason, "domain" to hint.domainKey),
                )
            },
        )
    }

    // The case preview holds the voice transport, so reading notes or sample questions can never
    // be captured as the learner's first spoken turn.
    if (openBookBriefingPending && !openBookBriefed && !isSampleScenario) {
        openBookCard?.let { card ->
            com.example.medvoicetrainer.ui.screens.OpenBookBriefingDialog(
                card = card,
                onStart = { reviewedPreview -> viewModel.resolveOpenBookBriefing(reviewedPreview) },
            )
        }
    }

    openBookCard?.takeIf { showOpenBook }?.let { card ->
        com.example.medvoicetrainer.ui.screens.OpenBookSheet(
            card = card,
            level = openBookLevel,
            closingLevel = openBookClosingLevel,
            coverage = activeSession.openBookChecklist.associateBy { it.objective },
            previousLevel = openBookPreviousLevel,
            micHeld = openBookHeldMic,
            micAvailable = usesLiveMic,
            briefed = openBookBriefed,
            ttsReady = openBookTts?.ready == true,
            onReveal = { viewModel.revealOpenBook(it) },
            onRevealClosing = { viewModel.revealOpenBookClosing(it) },
            onReleaseMic = {
                viewModel.setMicMuted(false)
                openBookHeldMic = false
                openBookMicUnmanaged = true
            },
            onListen = { openBookTts?.speak(it) },
            onUsePhrase = { phrase ->
                closeOpenBook()
                textInput = phrase
                showTypedInput = true
            },
            initialPhrases = openBookInitialPhrases,
            onDismiss = closeOpenBook,
        )
    }

    phrasebookCard?.takeIf { showPhrasebook }?.let { card ->
        com.example.medvoicetrainer.ui.screens.PhrasebookSheet(
            card = card,
            level = phrasebookLevel,
            usage = activeSession.phrasebookUsage.associateBy { it.objective },
            micHeld = phrasebookHeldMic,
            micAvailable = usesLiveMic,
            ttsReady = phrasebookTts?.ready == true,
            kept = { english ->
                com.example.medvoicetrainer.analysis.MyPhrasebook.contains(myPhrasebook, english)
            },
            onReveal = { viewModel.revealPhrasebook(it) },
            onReleaseMic = {
                viewModel.setMicMuted(false)
                phrasebookHeldMic = false
                phrasebookMicUnmanaged = true
            },
            onListen = { phrasebookTts?.speak(it) },
            onUsePhrase = { phrase ->
                closePhrasebook()
                textInput = phrase
                showTypedInput = true
            },
            onKeep = { phrase ->
                viewModel.addToMyPhrasebook(
                    english = phrase.en,
                    note = phrase.gloss.orEmpty(),
                    source = card.title.ifBlank { activeSession.caseName },
                )
            },
            onDismiss = closePhrasebook,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // §1 (Korean UX pass): a demo/mock session has no live audio and an entirely scripted
        // patient — without this banner, the "mic unavailable" status and typed-only input read
        // as a bug rather than an intentional keyless-tour constraint (see VoiceManager's
        // listeningStatus fix above for the actual false-error root cause this banner explains).
        // Folded away while the keyboard is up: with the IME open the column only has the top
        // third of the screen left, and that space belongs to the conversation the learner is
        // replying to, not to a standing explainer they have already read.
        if (isSampleScenario && !isImeVisible) {
            DemoModeBanner()
        }
        // --- Active Case Header ---
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Compact header while the keyboard is up — the case name and the End button stay,
            // the breathing room does not.
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = if (isImeVisible) 6.dp else 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        val overStation = stationSeconds > 0 && elapsedSeconds >= stationSeconds
                        val lastTwoMinutes = isKmle && stationSeconds > 0 && !overStation && elapsedSeconds >= stationSeconds - 120
                        Text(
                            text = "${if (isKmle) "CPX" else activeSession.mode.uppercase()} · %02d:%02d".format(
                                elapsedSeconds / 60, elapsedSeconds % 60
                            ) + (if (stationSeconds > 0) " / %02d:00".format(stationSeconds / 60) else "") +
                                (if (overStation) " · " + (if (isKmle) "시험 종료" else t("TIME — wrap up"))
                                else if (lastTwoMinutes) " · 종료 2분 전" else ""),
                            fontWeight = FontWeight.Black,
                            color = if (overStation || lastTwoMinutes) Color(0xFFFFD54F)
                            else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = activeSession.caseName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = if (isImeVisible) 1 else 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isSampleScenario) {
                            SampleScenarioBadge()
                        } else {
                            TurnStateBadge(
                                turnState,
                                partnerIsPatient = activeSession.mode !in setOf(
                                    "interview", "teachback", "survival", "listening", "lounge",
                                    "presentation", "team_communication", "nursing",
                                ),
                            )
                        }
                        if (kmleSession != null) {
                            TextButton(onClick = { showKmleSheet = true }) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (activeSession.revealedExamManeuvers.isEmpty() || !kmlePrefs.showFindings) "문제"
                                    else "문제·소견 ${activeSession.revealedExamManeuvers.size}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (activeSession.mode == "follow_up") {
                            IconButton(onClick = { showFollowUpChart = true }) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = t("Chart review"),
                                    tint = Color.White,
                                )
                            }
                        }
                        if (authoredInvestigationEvents.isNotEmpty() ||
                            activeSession.availableResults.isNotEmpty() ||
                            activeSession.pendingInvestigationEvents.isNotEmpty() ||
                            activeSession.revealedInvestigationEvents.isNotEmpty()
                        ) {
                            IconButton(onClick = { showInvestigationResults = true }) {
                                Icon(
                                    Icons.Default.Science,
                                    contentDescription = t("Tests & results"),
                                    tint = Color.White,
                                )
                            }
                        }
                        // Nursing cases carry the same `team_brief` block, and the brief is
                        // deliberately available *during* the session — these scenarios assess
                        // structured communication, not memory of the chart.
                        if (activeSession.mode == "team_communication" || activeSession.mode == "nursing") {
                            IconButton(onClick = { showTeamBrief = true }) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = t("Team handoff brief"),
                                    tint = Color.White,
                                )
                            }
                        }
                        if (activeSession.mode == "presentation" && presentationSoapNotes != null) {
                            TextButton(onClick = { showPresentationSoap = true }) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(t("SOAP"), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (!isSampleScenario) {
                            Box {
                                IconButton(onClick = { showSessionMenu = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = t("More session controls"),
                                        tint = Color.White,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSessionMenu,
                                    onDismissRequest = { showSessionMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                t(
                                                    if (activeSession.voiceConnectionState == VoiceConnectionState.USER_PAUSED) {
                                                        "Resume session"
                                                    } else {
                                                        "Pause session"
                                                    }
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (activeSession.voiceConnectionState == VoiceConnectionState.USER_PAUSED) {
                                                    Icons.Default.PlayArrow
                                                } else {
                                                    Icons.Default.Pause
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        enabled = !activeSession.isSessionControlBusy,
                                        onClick = {
                                            showSessionMenu = false
                                            viewModel.toggleSessionPaused()
                                        },
                                    )
                                }
                            }
                        }
                        // No X here: a sample scenario always exits through "Finish sample
                        // scenario" below, so the demo results screen (with its "add your free
                        // key" nudge) is guaranteed rather than skippable via an early discard.
                        if (!isSampleScenario) {
                            IconButton(onClick = { showEndSheet = true }) {
                                Icon(Icons.Default.Close, contentDescription = t("End session"), tint = Color.White)
                            }
                        }
                    }
                }
                if (!isSampleScenario && activeSession.status.isNotBlank() && !isImeVisible &&
                    turnState != TurnState.PAUSED && turnState != TurnState.FAILED
                ) {
                    Text(
                        activeSession.status,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        kmleAnnouncement?.let { message ->
            Surface(color = Color(0xFFFFE082), modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    color = Color(0xFF3E2723),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        val examFinding = visibleExamFinding
        if (examFinding != null && kmleSession != null && kmlePrefs.showFindings) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().clickable { showKmleSheet = true },
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "진찰 소견 · ${kmleSession.maneuverKo(examFinding.maneuver)}" + if (examFinding.painful) " (통증 있음)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        examFinding.finding,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        // Soft closure nudge: once WrapUpDetector marks the encounter substantially complete, the
        // app *offers* ending — it never auto-ends, so the learner can still ask "one more thing"
        // and the patient (per PromptBuilder's closure behavior) stays engaged. A quiet inline
        // banner, not a modal, so it can't interrupt the learner mid-utterance. Tapping opens the
        // same end sheet the Close button does.
        if (!isSampleScenario && activeSession.wrapUpSuggested &&
            turnState != TurnState.PAUSED && turnState != TurnState.FAILED
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEndSheet = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        t("Good moment to wrap up — finish when you're ready, or keep going."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        t("End"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // §5 DEGRADED frame: network loss mid-session gets a designed state (cause, safety
        // promise, one recovery action) instead of a bare error line.
        if (turnState == TurnState.PAUSED || turnState == TurnState.FAILED) {
            Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (turnState == TurnState.PAUSED) {
                            t("Connection lost — your transcript is safe")
                        } else {
                            t("Voice connection unavailable — your transcript is safe")
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        activeSession.error ?: activeSession.status,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { viewModel.finishSession() }) {
                        Text(t("✓ End here & analyze what I have"))
                    }
                }
            }
        } else {
            activeSession.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Case-authored ED result release. The detector raises this immediately when the final
        // learner transcript contains an explicit order; it intentionally does not wait for the
        // patient's current audio turn to finish and never asks the model to invent a value.
        activeSession.pendingInvestigationEvents.firstOrNull()?.let { event ->
            InvestigationResultPrompt(
                event = event,
                onView = {
                    viewModel.revealInvestigationResult(event.id)
                    showInvestigationResults = true
                },
            )
        }

        // --- Survival "Advanced Beta": scene-transition proposal + relay fact card ---
        // Both are null for every other session (nothing outside the beta path ever sets them),
        // so this block renders nothing at all in the shipped modes.
        sceneTransition?.let { proposal ->
            SceneTransitionChip(
                proposal = proposal,
                timeoutMillis = sceneTransitionTimeoutMillis,
                onAccept = { viewModel.acceptSceneTransition() },
                onDismiss = { viewModel.dismissSceneTransition() },
            )
        }
        relayFactCard?.let { card ->
            RelayFactCardView(
                card = card,
                onReady = { viewModel.startRelayReport() },
                onDismiss = { viewModel.dismissRelayFactCard() },
            )
        }
        sceneCharacter?.let { role ->
            ReturnToPreviousCharacterBar(
                role = role,
                onReturn = { viewModel.returnToPreviousCharacter() },
            )
        }

        // §1 (early-UX): the speak-is-optional reassurance for a first live session.
        // Both coaching banners yield to the keyboard for the same reason as the demo strip above.
        if (showSpeakOptional && !isImeVisible) {
            com.example.medvoicetrainer.ui.screens.InfoBanner(
                text = t("early.speak_optional_tip"),
                onDismiss = { showSpeakOptional = false },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        // §4 (early-UX): don't stack the generic first-session tip on top of the opening scaffold —
        // the scaffold already coaches the first moment. Show the tip only when the scaffold isn't.
        if (showSessionTip && !showOpeningScaffold && !isImeVisible) {
            com.example.medvoicetrainer.ui.screens.InfoBanner(
                text = t(com.example.medvoicetrainer.analysis.Coach.firstSessionTipKey(typedDemo = voiceBackend in setOf("demo", "mock"))),
                onDismiss = { showSessionTip = false },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // --- Dialog View (Transcript) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            // §5 caption density: FULL (default, never hides the current utterance) vs. the
            // "Aa" last-line-only mode for users who find reading while speaking distracting.
            val visibleTranscript = activeSession.transcript
            val indexOffset = 0

            if (showOpeningScaffold) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        t("coach.opening_scaffold.waiting"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OpeningScaffoldCard(
                        line = com.example.medvoicetrainer.analysis.Coach.openingScaffoldLine(activeSession.caseName, t),
                        showEnglishFirstFraming = !englishFirstIntroSeen,
                        onHide = {
                            viewModel.markCoachPrimerSeen(com.example.medvoicetrainer.analysis.Coach.KEY_OPENING_SCAFFOLD)
                            if (!englishFirstIntroSeen) viewModel.markEnglishFirstIntroSeen()
                            openingScaffoldDismissed = true
                        }
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(visibleTranscript) { visibleIndex, (role, text) ->
                    val index = visibleIndex + indexOffset
                    // Survival "Advanced Beta" stage direction: neither side said this, so it gets
                    // a centred narration line rather than a speech bubble — and it must never be
                    // hidden behind the ear-only reveal, which exists for spoken partner turns.
                    if (role == com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelMedium,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        return@itemsIndexed
                    }
                    val isDoctor = role == "doctor"
                    val visibleText = if (!isDoctor && earOnly && revealedPatientTurn != index) {
                        t("🔊 Audio-only turn — listen before revealing")
                    } else {
                        text
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isDoctor) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isDoctor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isDoctor) 16.dp else 0.dp,
                                bottomEnd = if (isDoctor) 0.dp else 16.dp
                            ),
                            shadowElevation = 1.dp,
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isDoctor) modeLabels[0] else modeLabels[1],
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDoctor) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = visibleText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isDoctor) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                if (
                                    !isDoctor &&
                                    !isSampleScenario &&
                                    BuildConfig.AI_REPORT_ENDPOINT.isNotBlank()
                                ) {
                                    TextButton(
                                        onClick = { aiResponseToReport = text },
                                        modifier = Modifier.align(Alignment.End),
                                        contentPadding = PaddingValues(
                                            horizontal = 4.dp,
                                            vertical = 0.dp,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.Flag,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            t("ai_report.action"),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (activeSession.isAILoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        modeLabels[1].lowercase().replaceFirstChar { it.uppercase() } + " " + t("is responding…"),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // --- Beginner guided cue ("try saying ___") ---
        // The spoon-feeding scaffold for a Foundations/coaching_mode encounter: one cue for the next
        // uncovered objective, revealing less as the skill sticks (full sentence → cloze → L1 hint).
        if (!isSampleScenario && guidedCue != null && !isImeVisible) {
            GuidedCueCard(
                cue = guidedCue,
                ttsReady = guidedTts?.ready == true,
                onListen = { guidedTts?.speak(guidedCue.modelPhrase) },
                onHide = { guidedHidden = true },
            )
        }

        // --- Live Checklist Coverage strip (§5) ---
        if (!isSampleScenario && activeSession.checklist.isNotEmpty() && !isImeVisible) {
            LiveChecklistCard(
                checklist = activeSession.checklist,
                showAll = checklistShowAll,
                onToggleShowAll = { checklistShowAll = !checklistShowAll },
                expandedObjective = expandedChecklistRow,
                onRowClick = { objective ->
                    expandedChecklistRow = if (expandedChecklistRow == objective) null else objective
                },
                onCorrect = { objective -> viewModel.cycleChecklistOverride(objective) },
            )
        }

        // --- Input Controls ---
        Surface(
            color = MaterialTheme.colorScheme.surface,
            // No imePadding here: the Scaffold's safeDrawing content padding (consumed in
            // MainAppScaffold) already ends this column exactly at the top of the keyboard, so the
            // composer lands directly above it and the weighted transcript above keeps the rest.
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Open Book's pinned strip. Appears only once the sheet has been opened far enough
                // to have something worth keeping in view, and stays out of the way of the keyboard.
                openBookCard?.takeIf {
                    !showOpenBook && !openBookHudDismissed && !isImeVisible &&
                        (
                            openBookBriefed ||
                                openBookLevel.step >= com.example.medvoicetrainer.analysis.OpenBookLevel.CHECKLIST.step ||
                                openBookClosingLevel != com.example.medvoicetrainer.analysis.ClosingLevel.HIDDEN
                            )
                }?.let { card ->
                    com.example.medvoicetrainer.ui.screens.OpenBookHudStrip(
                        card = card,
                        coverage = activeSession.openBookChecklist.associateBy { it.objective },
                        closingLevel = openBookClosingLevel,
                        onOpen = openOpenBook,
                        onUnpin = { openBookHudDismissed = true },
                    )
                }
                if (!isSampleScenario && !isImeVisible &&
                    activeSession.mode in setOf("survival", "listening", "lounge")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("Ear-only practice"))
                        }
                        Switch(
                            checked = earOnly,
                            onCheckedChange = {
                                earOnly = it
                                if (!it) revealedPatientTurn = -1
                            }
                        )
                    }
                    // "Not the learner" isn't enough any more: a beta stage-direction line is
                    // neither side's speech and is always visible, so revealing it would be a no-op
                    // that consumed the learner's reveal.
                    val isPartnerTurn: (Pair<String, String>) -> Boolean = { (role, _) ->
                        role != "doctor" &&
                            role != com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE
                    }
                    if (earOnly && activeSession.transcript.any(isPartnerTurn)) {
                        TextButton(onClick = {
                            revealedPatientTurn = activeSession.transcript.indexOfLast(isPartnerTurn)
                            viewModel.recordLearningEvent("transcript_reveal", mapOf("scope" to "last_line"))
                        }) {
                            Text(t("Reveal last line"))
                        }
                    }
                }
                // Patient playback speed (app/voice/capabilities.py plan_playback_speed).
                if (!isSampleScenario && !isImeVisible) {
                    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
                    val speedExperimental by viewModel.playbackSpeedExperimental.collectAsStateWithLifecycle()
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = t("Playback speed"),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${((playbackSpeed * 100).roundToInt() / 100f)}x",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = playbackSpeed,
                                onValueChange = { raw ->
                                    val clean = ((raw * 100).roundToInt() / 100f)
                                    viewModel.setPlaybackSpeed(clean)
                                },
                                valueRange = 0.5f..2.5f,
                                steps = 7,
                                modifier = Modifier.width(130.dp)
                            )
                            if (speedExperimental) {
                                Text(
                                    t("experimental"),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        // "Aa" caption density toggle (§5/§14) — full transcript (default) vs.
                        // last-line-only. Never fully hides the current utterance either way.
                        // The "stuck? question idea" hint, moved out of the text field (spec shows
                        // it as its own labeled pill, not a trailing icon) — fills the typed-input
                        // box with a deterministic (no-API-call) suggested next question. Only the
                        // question itself goes into the box; its phase ("Meds & allergies", ...) is
                        // surfaced as a caption above the box, since it is orientation for the
                        // learner and not something to say to the patient.
                        //
                        // In an encounter the rules can rank a shortlist of open areas, so this
                        // opens a picker instead of committing to one guess: a mis-ranked first
                        // suggestion then costs a glance rather than the whole feature's
                        // credibility. Other modes have no history model behind them and keep the
                        // single canned continuation line.
                        // The canned continuation lines are English; a Korean CPX station has none.
                        if (activeSession.mode != "survival" && activeSession.mode != com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE) {
                            val hintOptions = activeSession.phaseHintOptions
                            AssistChip(
                            onClick = {
                                if (hintOptions.isNotEmpty()) {
                                    showHintPicker = true
                                } else {
                                    textInput = activeSession.phaseHint.ifBlank {
                                        when (activeSession.mode) {
                                            "interview" -> t("Could you give me a moment to structure my answer?")
                                            "listening", "lounge" -> t("Could you say that again more slowly, please?")
                                            "teachback" -> t("What part would you like me to explain another way?")
                                            "follow_up" -> t("How have things changed since your last visit?")
                                            else -> t("Can you tell me more about what happened next?")
                                        }
                                    }
                                    suggestedHintCategory =
                                        if (activeSession.phaseHint.isNotBlank()) activeSession.phaseHintCategory else ""
                                    showTypedInput = true
                                    viewModel.recordLearningEvent("continuation_hint")
                                }
                            },
                            label = { Text(t("Stuck? Question idea"), fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                        // Open Book sits beside "Stuck?" on purpose: they answer the two different
                        // reasons a learner stalls. "Stuck?" is for not knowing how to phrase the
                        // next question; Open Book is for not knowing what the next question *is*.
                        if (openBookCard != null && (openBookEnabled || openBookBriefed)) {
                            com.example.medvoicetrainer.ui.screens.OpenBookChip(
                                level = openBookLevel,
                                briefed = openBookBriefed,
                                onClick = openOpenBook,
                            )
                            if (openBookBriefed && openBookCard?.mustAsk?.any { !it.say.isNullOrBlank() } == true) {
                                com.example.medvoicetrainer.ui.screens.OpenBookPhrasesChip(
                                    onClick = openOpenBookPhrases,
                                )
                            }
                        }
                        // The everyday counterpart, and never alongside Open Book — the modes that
                        // have one have no diagnosis for the other to reveal. Same position for the
                        // same reason: this is where a learner looks when the sentence runs out.
                        if (phrasebookCard != null) {
                            com.example.medvoicetrainer.ui.screens.PhrasebookChip(
                                level = phrasebookLevel,
                                usedCount = activeSession.phrasebookUsage.count { it.isMet },
                                onClick = openPhrasebook,
                            )
                        }
                    }
                }

                if (usesLiveMic && !isImeVisible) {
                    // --- Mic bar (§5): a real mute toggle on a full-duplex conversation, never
                    // a push-to-talk/record button. Status text always answers "whose turn?".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val micEnabled = activeSession.voiceConnectionState == VoiceConnectionState.READY &&
                            activeSession.microphoneState == MicrophoneState.AVAILABLE
                        val micColor = if (!micEnabled || micMuted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            DangerRedStrong
                        }
                        // §14 "Motion" example case: a pulsing ring while the mic is actively
                        // listening for the user's turn, replaced by a static ring under reduced
                        // motion instead of an animated one.
                        val micListening = turnState == TurnState.YOUR_TURN
                        val pulseTransition = rememberInfiniteTransition(label = "micPulse")
                        val pulseScale by if (micListening && !reducedMotion) {
                            pulseTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.35f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "micPulseScale"
                            )
                        } else {
                            remember { mutableStateOf(1f) }
                        }
                        val pulseAlpha by if (micListening && !reducedMotion) {
                            pulseTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "micPulseAlpha"
                            )
                        } else {
                            remember { mutableStateOf(0f) }
                        }
                        Box(contentAlignment = Alignment.Center) {
                            if (micListening) {
                                if (reducedMotion) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .border(2.dp, DangerRedStrong.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .scale(pulseScale)
                                            .background(DangerRedStrong.copy(alpha = pulseAlpha), androidx.compose.foundation.shape.CircleShape)
                                    )
                                }
                            }
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = micColor,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable(enabled = micEnabled) { viewModel.toggleMicMuted() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (micMuted) t("Unmute microphone") else t("Mute microphone"),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val (label, sub) = when (turnState) {
                                TurnState.CONNECTING -> t("Connecting to patient…") to t("You can type while the connection finishes")
                                TurnState.MUTED -> t("Muted — patient can't hear you") to t("Tap to unmute")
                                TurnState.USER_PAUSED -> t("Session paused") to t("API connection is closed while paused")
                                TurnState.PAUSED -> t("Mic paused") to t("Resumes automatically when reconnected")
                                TurnState.FAILED -> t("Voice unavailable") to t("End this session and try again")
                                TurnState.MIC_UNAVAILABLE -> t("Microphone unavailable") to t("Use Type instead")
                                TurnState.PATIENT_SPEAKING -> t("Speak to interrupt") to t("The patient can hear you the moment you talk")
                                TurnState.YOUR_TURN -> t("Listening to you…") to t("Tap the mic to mute")
                            }
                            Text(label, fontWeight = FontWeight.Bold, color = if (turnState == TurnState.MUTED) DangerRedStrong else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            // While it's the learner's turn, the live waveform replaces the static
                            // sub-line: a moving wave confirms the app is hearing them, and a flat
                            // one signals a dropped/unheard turn (the frustration this addresses).
                            if (turnState == TurnState.YOUR_TURN) {
                                VoiceWaveform(
                                    level = micLevel,
                                    active = true,
                                    reducedMotion = reducedMotion,
                                    color = DangerRedStrong,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .padding(top = 5.dp)
                                )
                            } else {
                                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(
                            color = DangerContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { showEndSheet = true }
                        ) {
                            Text(
                                "■ " + t("End"),
                                color = DangerRedStrong,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)
                            )
                        }
                    }
                    if (activeSession.voiceConnectionState == VoiceConnectionState.USER_PAUSED) {
                        Button(
                            onClick = viewModel::toggleSessionPaused,
                            enabled = !activeSession.isSessionControlBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("Resume session"))
                        }
                    }
                    AnimatedVisibility(visible = showSendTurnRecovery) {
                        OutlinedButton(
                            onClick = {
                                showSendTurnRecovery = false
                                heardSpeechThisTurn = false
                                viewModel.finishCurrentLearnerTurn()
                            },
                            enabled = activeSession.voiceConnectionState == VoiceConnectionState.READY &&
                                activeSession.microphoneState == MicrophoneState.AVAILABLE &&
                                !micMuted && !activeSession.isAILoading &&
                                !activeSession.isManualTurnSending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("No response? Send now"))
                        }
                    }
                    TextButton(onClick = { showTypedInput = !showTypedInput }) {
                        Text(if (showTypedInput) t("Hide typing") else "⌨ " + t("Type instead"))
                    }
                }

                if (isSampleScenario) {
                    SampleScenarioControls(
                        nextLine = nextSampleLine,
                        completedTurns = completedSampleTurns,
                        totalTurns = sampleLineCount,
                        // The offline demo has prepared learner lines.  Do not require a
                        // preceding patient turn here: a generated everyday scenario can be
                        // ready before its opener is rendered, and that old condition trapped
                        // the learner on the first line.
                        enabled = activeSession.voiceConnectionState == VoiceConnectionState.READY &&
                            !activeSession.isAILoading,
                        onNext = { nextSampleLine?.let(viewModel::addLearnerTurn) },
                        onFinish = viewModel::finishSession
                    )
                }

                if (!isSampleScenario && (showTypedInput || !usesLiveMic)) {
                    // Caption for a hint the learner just accepted: it names the phase the
                    // suggested question belongs to, and stays out of the editable text so the
                    // patient only ever receives the question itself.
                    if (suggestedHintCategory.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = t("Question idea") + " · " + t(suggestedHintCategory),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = {
                                textInput = it
                                // Emptying the box drops the suggestion it was labelling.
                                if (it.isBlank()) suggestedHintCategory = ""
                            },
                            placeholder = { Text(modeLabels[2]) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitTypedTurn() })
                        )

                        FloatingActionButton(
                            onClick = submitTypedTurn,
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = t("Send"), tint = Color.White)
                        }
                    }
                }

                if (!isSampleScenario && !usesLiveMic && !isImeVisible) {
                    Button(
                        onClick = { showEndSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(modeLabels[3])
                            Icon(Icons.Default.CheckCircle, contentDescription = t("Submit Case"))
                        }
                    }
                }
            }
        }
    }

    aiResponseToReport
        ?.takeIf { BuildConfig.AI_REPORT_ENDPOINT.isNotBlank() }
        ?.let { response ->
        AiResponseReportDialog(
            aiResponse = response,
            surface = "live_patient_response",
            provider = voiceBackend,
            model = viewModel.getVoiceModelForBackend(voiceBackend),
            onDismiss = { aiResponseToReport = null },
        )
    }
}

/**
 * Survival "Advanced Beta": the model's scene-change suggestion, as a card the learner accepts or
 * ignores. Nothing happens to the conversation until they tap — and if they do nothing at all, it
 * declines itself once [timeoutMillis] is up (MainViewModel arms that timer), because a Live model
 * that made a function call stays silent until it gets an answer.
 */
@Composable
private fun SceneTransitionChip(
    proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
    timeoutMillis: Long,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val icon = when (proposal.type) {
        com.example.medvoicetrainer.voice.SceneTransitionType.MOVE -> "📍"
        com.example.medvoicetrainer.voice.SceneTransitionType.TIME_SKIP -> "⏭️"
        com.example.medvoicetrainer.voice.SceneTransitionType.NEW_CHARACTER -> "🧑"
        com.example.medvoicetrainer.voice.SceneTransitionType.SOLO_ERRAND -> "🚶"
        com.example.medvoicetrainer.voice.SceneTransitionType.RETURN_TO_PREVIOUS -> "↩️"
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$icon  ${proposal.title}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (proposal.description.isNotBlank()) {
                Text(
                    text = proposal.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(t("Let's go")) }
                TextButton(onClick = onDismiss) { Text(t("Not now")) }
            }
            // The card really does expire, and until now it vanished with no warning — leaving the
            // learner to wonder whether they missed something or the app dropped it. Keyed on the
            // proposal id so a replacement card restarts the bar instead of inheriting the old
            // one's remaining time. Purely a mirror of MainViewModel's timer: this drains to empty
            // at the same moment the auto-decline fires, but never causes it — which is why it
            // takes the session's real timeout rather than the constant. A session that fell back
            // to blocking tools gets eight seconds, and a bar still three quarters full when the
            // card went was the single most misleading thing the beta did.
            val timeLeft = remember(proposal.id) { Animatable(1f) }
            LaunchedEffect(proposal.id, timeoutMillis) {
                timeLeft.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = timeoutMillis.toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
            LinearProgressIndicator(
                progress = { timeLeft.value },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f),
                trackColor = Color.Transparent,
            )
        }
    }
}

/**
 * Survival "Advanced Beta" Stage 3: what the learner "was told" while off running an errand alone.
 * Their partner never heard any of it, so the task is to relay all of it back in English — the
 * post-session analysis compares this exact list against what they actually said.
 */
@Composable
private fun RelayFactCardView(
    card: com.example.medvoicetrainer.ui.RelayFactCard,
    onReady: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = t("📋 What you found out") +
                    (if (card.place.isBlank()) "" else " · ${card.place}"),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = if (card.awaitingReturn) {
                    t("Take your time reading these — nobody is waiting on you yet.")
                } else {
                    t("They weren't with you — tell them all of this in your own words.")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
            card.facts.forEach { fact ->
                Text(
                    text = "• $fact",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            // Until this is tapped the scene is genuinely paused: the accepted transition has not
            // been sent, so the partner cannot ask about the errand mid-read.
            if (card.awaitingReturn) {
                Button(onClick = onReady, modifier = Modifier.align(Alignment.End)) {
                    Text(t("I'm back — tell them"))
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(t("Done reporting"))
                }
            }
        }
    }
}

/**
 * Survival "Advanced Beta": the way back out of a character switch, on screen for as long as the
 * learner is with a stand-in.
 *
 * Not a convenience — it is the only guaranteed way back. The stand-in can propose the return trip
 * itself, but it is under no obligation to, and a proposal the app's own cooldown turns down is
 * never repeated; without this a learner who accepted "talk to the front desk staff?" could be left
 * with them until they end the session.
 */
@Composable
private fun ReturnToPreviousCharacterBar(role: String, onReturn: () -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val who = role.trim().ifEmpty { t("someone else") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "🧑  " + t("You're talking to {who}").replace("{who}", who),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReturn) { Text(t("Go back")) }
        }
    }
}

/** Clear, persistent framing for a bounded sample—not a degraded live chat. */
@Composable
private fun DemoModeBanner() {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            t("demo.session_banner"),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun SampleScenarioBadge() {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(
        color = Color.White.copy(alpha = 0.22f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            t("demo.sample.badge"),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SampleScenarioControls(
    nextLine: String?,
    completedTurns: Int,
    totalTurns: Int,
    enabled: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val completed = completedTurns.coerceIn(0, totalTurns.coerceAtLeast(0))
    val progress = if (totalTurns > 0) completed.toFloat() / totalTurns else 0f

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                t("demo.sample.controls_title"),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                t("demo.sample.controls_body"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (totalTurns > 0) {
                Text(
                    t("demo.sample.progress")
                        .replace("{done}", completed.toString())
                        .replace("{total}", totalTurns.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(
                onClick = if (nextLine == null) onFinish else onNext,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (nextLine == null) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (nextLine == null) {
                        t("demo.sample.finish")
                    } else {
                        t("demo.sample.next_line")
                    }
                )
            }
        }
    }
}

/** Header pill for the §5 turn-state machine — one enum, one place, feeding badge + mic bar. */
@Composable
private fun TurnStateBadge(state: TurnState, partnerIsPatient: Boolean = true) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val (bg, fg, text) = when (state) {
        TurnState.CONNECTING -> Triple(AccentAmber.copy(alpha = 0.28f), Color.White, "… " + t("Connecting"))
        TurnState.YOUR_TURN -> Triple(SuccessGreen.copy(alpha = 0.22f), Color.White, "🟢 " + t("Your turn"))
        TurnState.PATIENT_SPEAKING -> Triple(
            Color.White.copy(alpha = 0.22f),
            Color.White,
            "🔵 " + t(if (partnerIsPatient) "Patient speaking" else "Partner speaking"),
        )
        TurnState.MUTED -> Triple(Color.White.copy(alpha = 0.22f), Color.White, "🔇 " + t("Muted"))
        TurnState.USER_PAUSED -> Triple(AccentAmber.copy(alpha = 0.28f), Color.White, "⏸ " + t("Paused"))
        TurnState.PAUSED -> Triple(AccentAmber.copy(alpha = 0.28f), Color.White, "⟳ " + t("Reconnecting"))
        TurnState.FAILED -> Triple(DangerRedStrong.copy(alpha = 0.35f), Color.White, "! " + t("Unavailable"))
        TurnState.MIC_UNAVAILABLE -> Triple(AccentAmber.copy(alpha = 0.28f), Color.White, "! " + t("Type instead"))
    }
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/**
 * §5 "FIRST 30s" scaffold: a literal, name-personalized opening line for a brand-new user with a
 * blank transcript and an open mic — the single highest-leverage moment in the whole funnel.
 */
@Composable
private fun OpeningScaffoldCard(line: String, showEnglishFirstFraming: Boolean, onHide: () -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val context = LocalContext.current
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    DisposableEffect(context) {
        val instance = android.speech.tts.TextToSpeech(context.applicationContext) { }
        tts = instance
        onDispose {
            instance.stop()
            instance.shutdown()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // §4 (early-UX): the English-first framing that used to be a separate blocking modal,
            // folded in here so the first live encounter shows one calm layer instead of a stack.
            if (showEnglishFirstFraming) {
                Text(
                    t("coach.english_first.title"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    t("coach.english_first.body1"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            }
            Text(
                "🧭 " + t("coach.opening_scaffold.badge"),
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text("\"$line\"", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                t("coach.opening_scaffold.hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { tts?.speak(line, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "opening-scaffold") }) {
                    Text("🔊 " + t("coach.opening_scaffold.hear_it_first"))
                }
                TextButton(onClick = onHide) {
                    Text(t("coach.opening_scaffold.hide"))
                }
            }
        }
    }
}

/**
 * The live checklist under the conversation.
 *
 * Everything here follows from one decision: the panel says how it knows, not just what it thinks.
 * Coverage is inferred from rules over speech-recognition output, so it will sometimes miss and
 * sometimes over-reach — and a panel that states a verdict it can't justify and won't let you
 * correct turns every such slip into a reason to stop believing the whole thing.
 *
 * So a row shows one of four states rather than a bare tick/no-tick: you asked it, you may have
 * asked it, the patient volunteered it, or it's still open. Opening a row shows the actual sentence
 * behind the tick, and offers the learner the last word. For an OSCE candidate that correction step
 * isn't overhead — "did I really ask that?" is the self-check the exam is about.
 */
@Composable
private fun LiveChecklistCard(
    checklist: List<com.example.medvoicetrainer.ui.ChecklistItem>,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    expandedObjective: String?,
    onRowClick: (String) -> Unit,
    onCorrect: (String) -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val confirmedCount = checklist.count { it.isConfident }
    val possibleCount = checklist.count { it.isMet && !it.isConfident }

    // Guidance, not an answer key: what's done, plus only the next open item — never the whole
    // remaining list at once, unless the learner asks for it (which they need to, to correct a row
    // the default view would hide).
    val rows = if (showAll) {
        checklist
    } else {
        val met = checklist.filter { it.isConfident }
        val possible = checklist.filter { it.isMet && !it.isConfident }
        val nextOpen = checklist.firstOrNull { !it.isMet }
        (met + possible + listOfNotNull(nextOpen)).distinctBy { it.objective }
    }

    Card(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(t("CHECKLIST COVERAGE"))
                        append(" · ")
                        append(t("checklist.confirmed_count").replace("{n}", confirmedCount.toString()))
                        if (possibleCount > 0) {
                            append(" · ")
                            append(t("checklist.possible_count").replace("{n}", possibleCount.toString()))
                        }
                        append(" · ")
                        append(checklist.size)
                        append(" ")
                        append(t("checklist.total_count"))
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (showAll) t("Show less") else t("Show all"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onToggleShowAll() }
                )
            }
            // Says plainly what this panel is and is not. The live pass is a navigation aid; the
            // grade comes from the post-session analysis, which reads the whole transcript.
            Text(
                text = t("checklist.estimate_caption"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.forEach { item ->
                ChecklistRow(
                    item = item,
                    expanded = expandedObjective == item.objective,
                    onClick = { onRowClick(item.objective) },
                    onCorrect = { onCorrect(item.objective) },
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    item: com.example.medvoicetrainer.ui.ChecklistItem,
    expanded: Boolean,
    onClick: () -> Unit,
    onCorrect: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val source = item.source

    // Four states, each with its own icon and colour, so "you asked this" never looks the same as
    // "the patient happened to mention it" or "a keyword matched somewhere".
    val icon = when (source) {
        CoverageSource.LEARNER_STRONG, CoverageSource.MANUAL -> Icons.Default.Check
        CoverageSource.LEARNER_WEAK -> Icons.Default.QuestionMark
        CoverageSource.PATIENT_VOLUNTEERED -> Icons.Default.RecordVoiceOver
        CoverageSource.NONE -> Icons.Default.RadioButtonUnchecked
    }
    val tint = when (source) {
        CoverageSource.LEARNER_STRONG, CoverageSource.MANUAL -> SuccessGreenStrong
        CoverageSource.LEARNER_WEAK -> AccentAmber
        CoverageSource.PATIENT_VOLUNTEERED -> MaterialTheme.colorScheme.tertiary
        CoverageSource.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bubble = when (source) {
        CoverageSource.LEARNER_STRONG, CoverageSource.MANUAL -> SuccessGreen.copy(alpha = 0.18f)
        CoverageSource.LEARNER_WEAK -> AccentAmber.copy(alpha = 0.18f)
        CoverageSource.PATIENT_VOLUNTEERED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
        CoverageSource.NONE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val stateLabel = when (source) {
        CoverageSource.LEARNER_STRONG -> t("checklist.state.you_asked")
        CoverageSource.MANUAL -> t("checklist.state.marked_by_you")
        CoverageSource.LEARNER_WEAK -> t("checklist.state.possibly_asked")
        CoverageSource.PATIENT_VOLUNTEERED -> t("checklist.state.patient_mentioned")
        CoverageSource.NONE -> t("checklist.state.open")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = bubble,
                modifier = Modifier.size(17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stateLabel,
                        tint = tint,
                        modifier = Modifier.size(if (source == CoverageSource.NONE) 8.dp else 11.dp)
                    )
                }
            }
            Text(item.objective, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) t("Collapse") else t("Why?"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 25.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
                // The sentence that produced the tick. This is the whole answer to "why is that
                // checked?" — without it the learner can only take the matcher's word for it.
                if (item.quote.isNotBlank()) {
                    Text(
                        text = "“${item.quote}”",
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val correctionLabel = when {
                    item.manualOverride != null -> t("checklist.correct.use_auto")
                    item.isMet -> t("checklist.correct.didnt_ask")
                    else -> t("checklist.correct.did_ask")
                }
                TextButton(
                    onClick = onCorrect,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(correctionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * The "Stuck?" picker: the ranked shortlist of things still worth asking, grouped by why each is
 * being offered.
 *
 * A single suggestion has to be right to be useful, and the deterministic rules simply aren't that
 * good at ranking — they are good at knowing which handful of areas are still open. Showing three
 * with their reasons plays to that: the learner picks, so a mis-ordered first row costs a glance
 * instead of sending them off down a question they didn't want.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionIdeaSheet(
    options: List<com.example.medvoicetrainer.analysis.RescueHint>,
    onDismiss: () -> Unit,
    onPick: (com.example.medvoicetrainer.analysis.RescueHint) -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(t("hint.picker.title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                t("hint.picker.subtitle"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            options.forEach { hint ->
                val reasonLabel = when (hint.reason) {
                    "follow_up" -> t("hint.reason.follow_up")
                    "catch_up" -> t("hint.reason.catch_up")
                    else -> t("hint.reason.continue")
                }
                val reasonIcon = when (hint.reason) {
                    "follow_up" -> Icons.Default.Link
                    "catch_up" -> Icons.Default.History
                    else -> Icons.Default.ArrowForward
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hint) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                reasonIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = reasonLabel + " · " + hint.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(hint.question, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Beginner guided cue for a Foundations / coaching_mode encounter — the "spoon-feeding" scaffold.
 * Shows one prompt for the next uncovered objective, and reveals progressively less as the learner
 * masters that skill: the full model sentence, then a cloze blank, then only the English
 * communicative goal (gradual release, so the cue is an on-ramp, not a permanent crutch). The three dots on the
 * right shrink as the scaffold fades, making the "you're getting there" progression visible.
 */
@Composable
private fun GuidedCueCard(
    cue: com.example.medvoicetrainer.analysis.GuidedCue,
    ttsReady: Boolean,
    onListen: () -> Unit,
    onHide: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val stage = cue.stage
    val promptLabel = when (stage) {
        com.example.medvoicetrainer.analysis.GuidedFadeStage.FULL -> "💬 " + t("guided.try_saying")
        com.example.medvoicetrainer.analysis.GuidedFadeStage.CLOZE -> "✏️ " + t("guided.fill_blank")
        else -> "🧭 " + t("guided.your_turn")
    }
    // Remaining scaffold, most support first: FULL=3 dots, CLOZE=2, OBJECTIVE=1.
    val supportDots = when (stage) {
        com.example.medvoicetrainer.analysis.GuidedFadeStage.FULL -> 3
        com.example.medvoicetrainer.analysis.GuidedFadeStage.CLOZE -> 2
        else -> 1
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t("guided.badge"),
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (i < supportDots) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    },
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
            }
            Text(
                promptLabel,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                if (stage == com.example.medvoicetrainer.analysis.GuidedFadeStage.OBJECTIVE) {
                    cue.displayPhrase
                } else {
                    "\"${cue.displayPhrase}\""
                },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onListen, enabled = ttsReady) {
                    Text("🔊 " + t("guided.listen"))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHide) {
                    Text(t("guided.hide"))
                }
            }
        }
    }
}

/**
 * §5 "WAIT" analyzing state: replaces the ordinary chat view for the ~20s gap between End and
 * feedback (the highest-abandonment moment) with real progressive steps, an explicit safety
 * promise, and a retry/skip escape hatch. The first two steps are genuinely instant local work
 * (transcript persistence + FluencyMetrics) by the time finishSession() sets isFinishing=true.
 */
@Composable
private fun AnalyzingScreen(
    activeSession: ActiveSessionState,
    onRetry: () -> Unit,
    onContinueInBackground: () -> Unit,
    onSkipKeepTranscript: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    // Disable back entirely while analysis is genuinely in flight (no error yet); once it fails,
    // let skip/retry — not the raw back gesture — be the way out, so nothing looks abandoned.
    androidx.activity.compose.BackHandler { }
    val hasError = activeSession.error != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (hasError) "⚠️" else "🧠", fontSize = 34.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasError) t("Couldn't finish analyzing") else t("Analyzing your conversation"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            if (hasError) activeSession.error ?: "" else t("Usually under a minute; longer conversations may take more time"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyzingStepRow(t("Transcript saved to your device"), done = true)
                AnalyzingStepRow(t("Fluency metrics computed"), done = true)
                AnalyzingStepRow(
                    t("Scoring & corrections…"),
                    done = !hasError && activeSession.analysisStage == FeedbackAnalysisStage.BUILDING_CARDS,
                    inProgress = !hasError && activeSession.analysisStage == FeedbackAnalysisStage.SCORING
                )
                // The final stage serializes and saves the result (and, for clinical sessions, the
                // SRS cards). Neutral wording: everyday and Korean CPX sessions have no SRS cards.
                AnalyzingStepRow(
                    t("Saving your results"),
                    done = false,
                    inProgress = !hasError && activeSession.analysisStage == FeedbackAnalysisStage.BUILDING_CARDS
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            t("You can't lose this session — if the app closes, it resumes from the saved transcript in History."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        if (hasError) {
            Button(onClick = onRetry) { Text(t("Retry analysis")) }
            Spacer(Modifier.height(4.dp))
        }
        if (!hasError) {
            OutlinedButton(onClick = onContinueInBackground, modifier = Modifier.fillMaxWidth()) {
                Text(t("Continue in background"))
            }
            Text(
                t("We'll notify you when feedback is ready. You can review it later in History."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        TextButton(onClick = onSkipKeepTranscript) {
            Text(t("Skip analysis (keep transcript)"), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AnalyzingStepRow(label: String, done: Boolean, inProgress: Boolean = false) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            done -> Icon(Icons.Default.CheckCircle, contentDescription = t("Done"), tint = SuccessGreenStrong, modifier = Modifier.size(17.dp))
            inProgress -> CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
            else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = t("Pending"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * §5 "CONFIRM" end-of-session sheet — the single exit affordance for a live session (End button,
 * mic-bar; system back; header close). Ending is always explicit: the primary path analyzes,
 * discarding is the quiet, guarded option, never the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndSessionSheet(
    onDismiss: () -> Unit,
    onFinishAndAnalyze: () -> Unit,
    onKeepTalking: () -> Unit,
    onDiscard: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(t("END SESSION?"), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DangerRedStrong)
            Text(t("Finish and get your feedback"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                t("Your conversation will be analyzed — scores, corrections, and SRS cards take ~20 seconds to generate."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onFinishAndAnalyze, modifier = Modifier.fillMaxWidth()) {
                Text("✓ " + t("Finish & analyze"))
            }
            OutlinedButton(onClick = onKeepTalking, modifier = Modifier.fillMaxWidth()) {
                Text(t("Keep talking"))
            }
            TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                Text(t("Discard without analyzing"), color = DangerRedStrong, fontSize = 12.sp)
            }
        }
    }
}
