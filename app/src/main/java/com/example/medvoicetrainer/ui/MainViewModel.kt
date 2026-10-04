package com.example.medvoicetrainer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.medvoicetrainer.analysis.AnalysisEngine
import com.example.medvoicetrainer.analysis.CostTracker
import com.example.medvoicetrainer.analysis.EvalPromptBuilder
import com.example.medvoicetrainer.analysis.FluencyMetrics
import com.example.medvoicetrainer.analysis.Intelligibility
import com.example.medvoicetrainer.analysis.InterviewPhaseTracker
import com.example.medvoicetrainer.analysis.WrapUpDetector
import com.example.medvoicetrainer.analysis.WrapUpSignals
import com.example.medvoicetrainer.analysis.PromptBuilder
import com.example.medvoicetrainer.analysis.ScoreDomains
import com.example.medvoicetrainer.analysis.ScoringCalibration
import com.example.medvoicetrainer.analysis.toAnalysisMaps
import com.example.medvoicetrainer.analysis.toAnalysisMap
import com.example.medvoicetrainer.analysis.toPythonRoleTranscriptJson
import com.example.medvoicetrainer.analysis.toPythonRoleTranscriptMaps
import com.example.medvoicetrainer.api.LlmUsage
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.db.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

import com.example.medvoicetrainer.voice.VoiceConnectionState
import com.example.medvoicetrainer.voice.MicrophoneState
import com.example.medvoicetrainer.voice.VoiceManager
import com.example.medvoicetrainer.voice.VoiceApiUsage
import com.example.medvoicetrainer.voice.VoiceSessionService
import com.example.medvoicetrainer.voice.LearnerAudioClip
import com.example.medvoicetrainer.voice.LearnerAudioStore
import com.example.medvoicetrainer.voice.PronunciationAudioStore
import com.example.medvoicetrainer.voice.ListeningAudioCache
import com.example.medvoicetrainer.voice.VoiceCatalog

/** Generous upper bound for a pasted/shared conversation import — see [MainViewModel.receiveSharedText]. */
private const val MAX_SHARED_IMPORT_CHARS = 100_000

/**
 * Wall-clock ceiling for the supplementary audio pronunciation pass. It now runs alongside the
 * scoring call rather than after it, so in practice this only bites when the audio route is
 * slower than scoring; whatever it has measured by then is kept.
 */
private const val PRONUNCIATION_ANALYSIS_BUDGET_MS = 45_000L

data class DashboardAnalysisState(
    val sessionMaps: List<Map<String, Any?>>,
    val errorProfiles: Map<String, com.example.medvoicetrainer.analysis.L1Stats.CategoryProfile>,
    val guidedPath: com.example.medvoicetrainer.analysis.GuidedPathResult,
    val diagnosticProfile: Map<String, Any>,
    val errorStats: com.example.medvoicetrainer.analysis.ErrorTrackerStats.Stats,
    val progressReport: com.example.medvoicetrainer.analysis.ProgressReport,
    val mission: com.example.medvoicetrainer.analysis.DailyMission,
    val roadmapCards: List<com.example.medvoicetrainer.analysis.RoadmapCard>,
)

private fun buildDashboardAnalysis(
    sessions: List<SessionEntity>,
    errorItems: List<ErrorItemEntity>,
    commitments: List<DebriefCommitmentEntity>,
): DashboardAnalysisState {
    val sessionMaps = sessions.toAnalysisMaps()
    val errorStats = com.example.medvoicetrainer.analysis.ErrorTrackerStats.stats(errorItems)
    val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    val dueCount = errorItems.count {
        (it.domain in setOf("clinical", "everyday") ||
            it.category.startsWith("pronunciation", ignoreCase = true)) &&
            it.state != "mastered" && it.state != "observed" && it.dueAt <= nowIso
    }
    val openCommitments = commitments.filter { it.status == "open" }.sortedByDescending { it.createdAt }
    return DashboardAnalysisState(
        sessionMaps = sessionMaps,
        errorProfiles = com.example.medvoicetrainer.analysis.L1Stats.aggregateProfiles(sessionMaps),
        guidedPath = com.example.medvoicetrainer.analysis.GuidedPath.buildGuidedPath(sessionMaps),
        diagnosticProfile = com.example.medvoicetrainer.analysis.Diagnostic.buildDiagnosticProfile(sessionMaps),
        errorStats = errorStats,
        progressReport = com.example.medvoicetrainer.analysis.ProgressReportEngine.buildProgressReport(
            sessionMaps,
            mapOf("mastered" to errorStats.mastered, "active" to errorStats.active),
        ),
        mission = com.example.medvoicetrainer.analysis.DailyMissionEngine.generateMission(
            sessionMaps, dueCount, openCommitments,
        ),
        roadmapCards = com.example.medvoicetrainer.analysis.LearningRoadmap
            .buildUsClinicalEnglishRoadmap(sessionMaps, dueCount),
    )
}

internal fun normalizeVoiceBackend(backend: String): String {
    return when (backend.trim().lowercase(Locale.ROOT)) {
        "mock" -> "mock"
        "demo" -> "demo"
        "openai" -> "openai"
        else -> "gemini"
    }
}

enum class CorrectionDecision {
    PENDING, ACCEPTED, NOT_ERROR, STT_ERROR, STYLE_ONLY
}

/** Parses the decision string persisted onto `SessionEntity.corrections` back into an enum. */
internal fun correctionDecisionOf(persisted: String?): CorrectionDecision =
    when (persisted?.trim()?.lowercase(Locale.ROOT)) {
        "accepted" -> CorrectionDecision.ACCEPTED
        "not_error" -> CorrectionDecision.NOT_ERROR
        "stt_error" -> CorrectionDecision.STT_ERROR
        "style_only" -> CorrectionDecision.STYLE_ONLY
        else -> CorrectionDecision.PENDING
    }

/**
 * Correction triage bound to an explicit session id rather than "the session that just ended".
 *
 * The live feedback screen's [MainViewModel.correctionDecisions] only ever tracks the most
 * recently finished session, so History could previously do nothing but replay whatever decisions
 * were already made. Findings left PENDING — the normal outcome when a learner closes a long
 * report — were stranded outside the mistake tracker permanently. This lets History resume that
 * triage against the right row.
 */
data class HistoryTriageState(
    val sessionId: Int,
    val evaluation: EvaluationResult,
    val decisions: Map<String, CorrectionDecision>,
    val predictions: Map<String, String>
) {
    val pendingCount: Int
        get() = evaluation.corrections.count {
            (decisions[it.decisionKey()] ?: CorrectionDecision.PENDING) == CorrectionDecision.PENDING
        }
}

internal fun automaticCorrectionDecision(correction: SrsCorrection): CorrectionDecision {
    // Model confidence is not a calibrated probability. A grounded model finding remains a
    // candidate until the learner classifies it; style/error discrimination is itself practice.
    return CorrectionDecision.PENDING
}

/** Confidence bar for automatic SRS saving. */
internal const val HIGH_CONFIDENCE_CORRECTION = 0.85

/**
 * A high-confidence error that automatic saving may accept without a per-item tap. Style
 * suggestions remain a judgment call. Pronunciation findings are saved as observations; the
 * existing two-session evidence policy still prevents a one-off finding from becoming SRS homework.
 */
internal fun isBulkAcceptableCorrection(correction: SrsCorrection): Boolean =
    correction.feedbackType == "error" &&
        correction.confidence >= HIGH_CONFIDENCE_CORRECTION

/**
 * Decision the app pre-fills for a correction when automatic saving is [autoSaveEnabled]. It is
 * on by default, but learners may turn it off. Style suggestions always remain pending.
 */
internal fun autoSaveCorrectionDecision(
    correction: SrsCorrection,
    autoSaveEnabled: Boolean
): CorrectionDecision =
    if (autoSaveEnabled && isBulkAcceptableCorrection(correction)) {
        CorrectionDecision.ACCEPTED
    } else {
        CorrectionDecision.PENDING
    }

internal fun hasVoiceBackendAccess(
    backend: String,
    geminiApiKey: String,
    openAiApiKey: String
): Boolean = when (normalizeVoiceBackend(backend)) {
    "mock", "demo" -> true
    "openai" -> openAiApiKey.isNotBlank()
    else -> geminiApiKey.isNotBlank()
}

/**
 * A saved credential always takes precedence over the internal keyless preview backend.
 * Demo/mock remain valid internal transports, but they are never sticky once a real provider is
 * available.
 */
internal fun realBackendForSavedCredentials(
    currentBackend: String,
    geminiApiKey: String,
    openAiApiKey: String
): String {
    val normalized = normalizeVoiceBackend(currentBackend)
    if (normalized !in setOf("demo", "mock")) return normalized
    return when {
        geminiApiKey.isNotBlank() -> "gemini"
        openAiApiKey.isNotBlank() -> "openai"
        else -> normalized
    }
}

internal fun canAcceptTypedTurn(
    connectionState: VoiceConnectionState,
    supportsPreReadyQueue: Boolean,
): Boolean = connectionState == VoiceConnectionState.READY ||
    (connectionState == VoiceConnectionState.CONNECTING && supportsPreReadyQueue)

enum class BackupOperation { EXPORT, RESTORE }

/**
 * Everything the Preferences screen needs to render an encrypted backup run, held in the ViewModel
 * so navigating away and back shows the same job rather than losing it.
 */
data class BackupUiState(
    val running: Boolean = false,
    val operation: BackupOperation? = null,
    val progress: com.example.medvoicetrainer.export.UserBackupProgress? = null,
    val summary: com.example.medvoicetrainer.export.UserBackupSummary? = null,
    val failure: com.example.medvoicetrainer.export.UserBackupFailure? = null,
) {
    /** A running restore must keep the whole app behind its modal until the required restart. */
    val blocksAppInteraction: Boolean
        get() = operation == BackupOperation.RESTORE && (running || summary != null)

    /** Keep backup progress/results reachable across Activity recreation. */
    val requiresPreferencesSurface: Boolean
        get() = running || summary != null || failure != null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = Repository(application)
    private val userBackupManager = com.example.medvoicetrainer.export.UserBackupManager(application, repository)
    // Say It keeps phrase fluency separate from the correction/SRS queue. The whole map is small
    // (one record per bundled phrase) and is persisted as one local settings value.
    private val _sayItProgress = MutableStateFlow(
        com.example.medvoicetrainer.analysis.SayItProgressTracker.read(
            repository.getSetting(com.example.medvoicetrainer.analysis.SayItProgressTracker.SETTING_KEY, "{}")
        )
    )
    val sayItProgress = _sayItProgress.asStateFlow()

    // The learner's own Say It recordings, so a phrase drilled today can be compared with how it
    // sounded two takes ago. Index here, audio under learner_audio/ — both bounded by SayItTakeLog.
    private val _sayItTakes = MutableStateFlow(
        com.example.medvoicetrainer.analysis.SayItTakeLog.read(
            repository.getSetting(com.example.medvoicetrainer.analysis.SayItTakeLog.SETTING_KEY, "{}")
        )
    )
    val sayItTakes = _sayItTakes.asStateFlow()
    /** Serializes the read-modify-persist of the take index against back-to-back recordings. */
    private val sayItTakeMutex = Mutex()

    private val learnerAudioStore = LearnerAudioStore(application)
    private val pronunciationAudioStore = PronunciationAudioStore(application)
    private var voiceManager: VoiceManager? = null
    /** Invalidates late transport callbacks whenever a session is replaced or cancelled. */
    private val voiceSessionGeneration = AtomicLong(0L)
    private val voiceUsageLock = Any()
    private var activeVoiceUsageGeneration: Long = 0L
    private var activeVoiceUsage: VoiceApiUsage? = null
    @Volatile
    private var activeSupportsPreReadyTextQueue = false
    private var interviewPhaseTracker: InterviewPhaseTracker? = null
    /** Compiled once per session from the case's learning objectives — see [CoverageEngine]. */
    private var checklistRules: List<com.example.medvoicetrainer.analysis.ObjectiveRule> = emptyList()
    /**
     * Rows the learner ticked or cleared by hand, by objective. Held outside [ActiveSessionState]
     * so the deterministic re-evaluation after every turn can rebuild the whole checklist from the
     * transcript without having to preserve the learner's edits through the rebuild.
     */
    private val checklistManualOverrides = mutableMapOf<String, Boolean>()
    /**
     * Open Book's "what this diagnosis obliges you to ask" list, compiled into the same
     * [CoverageEngine] rules the live checklist uses so its rows tick themselves off as the learner
     * asks. Empty for every case that has no Open Book card.
     */
    private var openBookRules: List<com.example.medvoicetrainer.analysis.ObjectiveRule> = emptyList()
    /**
     * The everyday phrasebook's offered expressions, compiled the same way, so a model line the
     * learner actually says ticks itself off. Empty for every clinical case — see
     * [com.example.medvoicetrainer.analysis.EverydayPhrasebook].
     */
    private var phrasebookRules: List<com.example.medvoicetrainer.analysis.ObjectiveRule> = emptyList()
    // --- Soft session-closure tracking (encounter mode only; see WrapUpDetector). Mutated only
    // from the serialized transcript callback, so plain vars are sufficient. ---
    private var wrapUpDetector: WrapUpDetector? = null
    private var sessionStartMillis: Long = 0L
    private var doctorTurnCount: Int = 0
    private var turnsSinceNewCoverage: Int = 0
    /** Draft row created before transport startup, matching the PC session snapshot contract. */
    private var activeSessionId: Int? = null
    private data class TranscriptPersistRequest(
        val sessionId: Int,
        val transcript: List<Pair<String, String>>,
        val learnerTurns: Int,
        val capture: SessionAudioCapture?,
    )
    private val transcriptPersistLock = Any()
    /** Latest pending snapshot per session; intermediate snapshots can be safely superseded. */
    private val pendingTranscriptPersists = linkedMapOf<Int, TranscriptPersistRequest>()
    private var transcriptPersistJob: Job? = null

    private class SessionAudioCapture(
        val generation: Long,
        val sessionId: Int,
        initialTranscript: List<Pair<String, String>>,
        val saveLearnerClips: Boolean,
        val analyzePronunciation: Boolean,
    ) {
        @Volatile var latestTranscript: List<Pair<String, String>> = initialTranscript
        val clips = java.util.concurrent.ConcurrentHashMap<Int, LearnerAudioClip>()
        val pronunciationFiles = java.util.concurrent.ConcurrentHashMap<Int, java.io.File>()
        val pronunciationQualityWarnings = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val jobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
        val persistMutex = Mutex()
    }

    @Volatile
    private var activeSessionAudioCapture: SessionAudioCapture? = null

    // The evaluation coroutine of the session on the Analyzing screen, so "Skip analysis (keep
    // transcript)" can stop it before it overwrites the learner's choice with `completed`.
    private var finishingJob: Job? = null

    // --- Playback speed (app/voice/capabilities.py plan_playback_speed) ---
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()
    private val _playbackSpeedExperimental = MutableStateFlow(false)
    val playbackSpeedExperimental = _playbackSpeedExperimental.asStateFlow()

    fun setPlaybackSpeed(speed: Float) {
        val plan = voiceManager?.planSpeed(speed)
        _playbackSpeed.value = (plan?.requested ?: speed.toDouble()).toFloat()
        _playbackSpeedExperimental.value = plan?.experimental ?: (speed > 1.5f)
        voiceManager?.setSpeed(speed)
    }

    // §11 "Default patient speed" (Preferences > Voice & playback) — persisted across app
    // restarts and applied automatically at the start of every session, instead of every
    // session silently starting at 1.0x regardless of the learner's usual preference.
    private val _defaultPlaybackSpeed = MutableStateFlow(
        repository.getSetting("default_playback_speed", "1.0").toFloatOrNull() ?: 1.0f
    )
    val defaultPlaybackSpeed = _defaultPlaybackSpeed.asStateFlow()

    fun updateDefaultPlaybackSpeed(speed: Float) {
        repository.setSetting("default_playback_speed", speed.toString())
        _defaultPlaybackSpeed.value = speed
    }

    // --- Live session turn-state inputs (docs/design/android-ui-spec.html §5) ---
    // Real mic mute, backed by VoiceManager.setMuted — stops audio reaching the provider
    // without dropping the connection. No-op (and stays false) for typed demo/mock sessions,
    // which never have a live microphone to mute.
    private val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    fun toggleMicMuted() {
        val next = !_micMuted.value
        voiceManager?.setMuted(next)
        _micMuted.value = next
    }

    /**
     * Set the mute state outright, for the Open Book "time out" hold. Reading a diagnosis takes
     * long enough that leaving the microphone live would spend the learner's turn on the sound of
     * them reading, so the sheet mutes for its duration and restores the previous state on close.
     */
    fun setMicMuted(muted: Boolean) {
        if (_micMuted.value == muted) return
        voiceManager?.setMuted(muted)
        _micMuted.value = muted
    }

    /** Toggle the cost-saving live-session pause without ending or analyzing the session. */
    fun toggleSessionPaused() {
        val manager = voiceManager ?: return
        val snapshot = _activeSession.value
        if (!snapshot.isActive || snapshot.isSessionControlBusy) return
        val resume = snapshot.voiceConnectionState == VoiceConnectionState.USER_PAUSED
        val generation = voiceSessionGeneration.get()
        _activeSession.update { current ->
            if (!current.isActive) current else current.copy(
                isSessionControlBusy = true,
                error = null,
                status = if (resume) "Resuming voice session..." else "Pausing voice session...",
            )
        }
        viewModelScope.launch {
            val changed = if (resume) manager.resume() else manager.pause()
            if (voiceSessionGeneration.get() != generation || voiceManager !== manager) return@launch
            if (changed) {
                if (resume && manager.usesLiveMicrophone) {
                    VoiceSessionService.start(getApplication(), _activeSession.value.caseName)
                } else if (!resume) {
                    VoiceSessionService.stop(getApplication())
                }
            } else {
                _activeSession.update { current ->
                    current.copy(
                        isSessionControlBusy = false,
                        error = "Could not ${if (resume) "resume" else "pause"} this voice session.",
                    )
                }
            }
        }
    }

    /** Force the provider to close the current microphone turn and answer immediately. */
    fun finishCurrentLearnerTurn() {
        val manager = voiceManager ?: return
        val snapshot = _activeSession.value
        if (!snapshot.isActive || snapshot.voiceConnectionState != VoiceConnectionState.READY ||
            snapshot.isManualTurnSending
        ) return
        val generation = voiceSessionGeneration.get()
        _activeSession.update { current ->
            current.copy(isManualTurnSending = true, error = null)
        }
        viewModelScope.launch {
            val sent = runCatching { manager.finishUserTurn() }.getOrDefault(false)
            if (voiceSessionGeneration.get() != generation || voiceManager !== manager) return@launch
            _activeSession.update { current ->
                current.copy(
                    isManualTurnSending = false,
                    status = if (sent) "Turn sent; waiting for the response" else current.status,
                    error = if (sent) null else "The voice turn could not be sent. Try speaking again.",
                )
            }
        }
    }

    // Live input loudness (0f..1f) of the audio actually reaching the provider, driving the
    // mic-bar waveform so the learner can see the app is hearing them. Deliberately kept off
    // ActiveSessionState: it updates ~10x/second and must not recompose the whole session view —
    // only the small waveform composable collects it. 0f whenever nothing is being forwarded
    // (muted, echo guard active, gated between turns, or session torn down).
    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    // "Aa" caption density (§5, §14): FULL is the default and the only one that may never hide
    // the current utterance; LAST_LINE_ONLY shows just the newest turn for users who find
    // reading while speaking distracting.
    // --- Database Streams ---
    // Korean CPX sessions are graded on a different scale in a different language, so every English
    // surface reads [sessions] without them and the CPX screens read [kmleSessions].
    private val englishSessionRows = repository.sessions.map { rows ->
        rows.filterNot { com.example.medvoicetrainer.analysis.KmleCpx.isKmleSession(it.mode, it.analysisDomain) }
    }
    val sessions = englishSessionRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val kmleSessions = repository.sessions.map { rows ->
        rows.filter { com.example.medvoicetrainer.analysis.KmleCpx.isKmleSession(it.mode, it.analysisDomain) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentSessionCostSamples = repository.recentSessionCostSamples.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )
    val apiUsageEvents = repository.apiUsageEvents.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )
    val errorItems = repository.errorItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val listeningAttempts = repository.listeningAttempts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val commitments = repository.commitments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dashboardAnalysis = combine(
        englishSessionRows,
        repository.errorItems,
        repository.commitments,
    ) { sessionRows, errors, commitmentRows -> Triple(sessionRows, errors, commitmentRows) }
        .mapLatest { (sessionRows, errors, commitmentRows) ->
            withContext(Dispatchers.Default) {
                buildDashboardAnalysis(sessionRows, errors, commitmentRows)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            buildDashboardAnalysis(emptyList(), emptyList(), emptyList()),
        )

    // --- UI Settings State ---
    private val _practiceExperience = MutableStateFlow(
        PracticeExperience.fromStorage(
            repository.getSetting(PracticeExperience.SETTING_KEY, "")
        )
    )
    val practiceExperience = _practiceExperience.asStateFlow()

    fun updatePracticeExperience(experience: PracticeExperience) {
        repository.setSetting(PracticeExperience.SETTING_KEY, experience.storageValue)
        _practiceExperience.value = experience
    }

    private val _geminiApiKey = MutableStateFlow(repository.getGeminiApiKey())
    val geminiApiKey = _geminiApiKey.asStateFlow()

    private val _openAiApiKey = MutableStateFlow(repository.getSetting("openai_api_key", ""))
    val openAiApiKey = _openAiApiKey.asStateFlow()

    private val _claudeApiKey = MutableStateFlow(repository.getSetting("claude_api_key", ""))
    val claudeApiKey = _claudeApiKey.asStateFlow()

    private val _geminiModel = MutableStateFlow(repository.getGeminiModel())
    val geminiModel = _geminiModel.asStateFlow()

    private val _openAiModel = MutableStateFlow(repository.getSetting("openai_model", "gpt-5.1"))
    val openAiModel = _openAiModel.asStateFlow()

    private val _claudeModel = MutableStateFlow(repository.getSetting("claude_model", "claude-sonnet-5"))
    val claudeModel = _claudeModel.asStateFlow()

    private val _geminiVoiceModel = MutableStateFlow(
        repository.getSetting("gemini_voice_model", "gemini-3.1-flash-live-preview")
    )
    val geminiVoiceModel = _geminiVoiceModel.asStateFlow()

    private val _openAiVoiceModel = MutableStateFlow(
        repository.getSetting("openai_voice_model", "gpt-realtime-2.1")
    )
    val openAiVoiceModel = _openAiVoiceModel.asStateFlow()

    private val _providerStatusMap = MutableStateFlow<Map<String, com.example.medvoicetrainer.api.ProviderVerificationResult>>(emptyMap())
    val providerStatusMap = _providerStatusMap.asStateFlow()

    // Only the newest verification attempt for a provider may update the UI. This matters on
    // onboarding where a learner can correct a pasted key while the previous network request is
    // still in flight; a late success for the old value must never unlock the new one.
    private val providerVerificationJobs = mutableMapOf<String, Job>()
    private val verifiedProviderKeys = mutableMapOf<String, String>()

    private val _analysisModelsMap = MutableStateFlow<Map<String, List<String>>>(
        mapOf(
            "gemini" to listOf(
                "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-pro-preview",
                "gemini-2.5-pro", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite"
            ),
            "openai" to listOf("gpt-5.1", "gpt-5.6-sol", "gpt-5.6-luna"),
            "claude" to listOf("claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5")
        )
    )
    val analysisModelsMap = _analysisModelsMap.asStateFlow()

    private val _voiceModelsMap = MutableStateFlow<Map<String, List<String>>>(
        mapOf(
            "gemini" to listOf("gemini-3.8-live", "gemini-3.8-live-extended-thinking", "gemini-3.1-flash-live-preview"),
            "openai" to listOf("gpt-realtime-2.1", "gpt-4o-realtime-preview")
        )
    )
    val voiceModelsMap = _voiceModelsMap.asStateFlow()

    fun verifyProviderKey(provider: String, draftKey: String? = null) {
        val cleanProvider = provider.trim().lowercase(Locale.ROOT)
        val key = draftKey?.trim() ?: getApiKeyForBackend(cleanProvider)
        providerVerificationJobs.remove(cleanProvider)?.cancel()
        verifiedProviderKeys.remove(cleanProvider)

        if (key.isBlank()) {
            _providerStatusMap.update { it + (cleanProvider to com.example.medvoicetrainer.api.ProviderVerificationResult(com.example.medvoicetrainer.api.ProviderStatus.NOT_SET)) }
            return
        }

        val job = viewModelScope.launch {
            _providerStatusMap.update { it + (cleanProvider to com.example.medvoicetrainer.api.ProviderVerificationResult(com.example.medvoicetrainer.api.ProviderStatus.VERIFYING)) }
            val result = com.example.medvoicetrainer.api.ModelsFetcher.verifyAndFetchModels(cleanProvider, key)
            if (result.status == com.example.medvoicetrainer.api.ProviderStatus.VERIFIED) {
                verifiedProviderKeys[cleanProvider] = key
            } else {
                verifiedProviderKeys.remove(cleanProvider)
            }
            _providerStatusMap.update { it + (cleanProvider to result) }
            if (result.status == com.example.medvoicetrainer.api.ProviderStatus.VERIFIED && result.availableModels.isNotEmpty()) {
                val analysisFiltered = com.example.medvoicetrainer.api.ModelsFetcher.filterAnalysisModels(cleanProvider, result.availableModels)
                val voiceFiltered = com.example.medvoicetrainer.api.ModelsFetcher.filterLiveVoiceModels(cleanProvider, result.availableModels)
                if (analysisFiltered.isNotEmpty()) {
                    _analysisModelsMap.update { it + (cleanProvider to analysisFiltered) }
                }
                if (voiceFiltered.isNotEmpty()) {
                    _voiceModelsMap.update { it + (cleanProvider to voiceFiltered) }
                }
            }
        }
        providerVerificationJobs[cleanProvider] = job
    }

    fun clearProviderVerification(provider: String) {
        val cleanProvider = provider.trim().lowercase(Locale.ROOT)
        providerVerificationJobs.remove(cleanProvider)?.cancel()
        verifiedProviderKeys.remove(cleanProvider)
        _providerStatusMap.update { it - cleanProvider }
    }

    private val defaultDeviceLanguage = normalizeLanguageCode(Locale.getDefault().language)

    private val _nativeLanguage = MutableStateFlow(repository.getSetting("native_language", defaultDeviceLanguage))
    val nativeLanguage = _nativeLanguage.asStateFlow()

    // Main-app UI is deliberately English-only.  The language picker in onboarding is a
    // temporary onboarding aid, not an application preference.
    private val _uiLanguage = MutableStateFlow("en")
    val uiLanguage = _uiLanguage.asStateFlow()

    /**
     * A dedicated completion flag keeps first-run education visible while a draft key is being
     * checked. Older installs predate the flag, so a configured key or an intentional demo/mock
     * backend is treated as already onboarded instead of replaying the wizard after an update.
     */
    private fun loadInitialOnboardingCompleted(): Boolean {
        if (repository.hasSetting("onboarding_completed")) {
            return repository.getSetting("onboarding_completed", "false") == "true"
        }

        val inferredForOlderInstall =
            repository.getGeminiApiKey().isNotBlank() ||
                repository.getSetting("openai_api_key", "").isNotBlank() ||
                normalizeVoiceBackend(repository.getSetting("voice_backend", "gemini")) in setOf("demo", "mock")

        // Persist the one-time migration result. Otherwise an upgraded learner who later removes
        // a credential would unexpectedly be sent through the full welcome tour on next launch.
        repository.setSetting("onboarding_completed", inferredForOlderInstall.toString())
        return inferredForOlderInstall
    }

    private val _onboardingCompleted = MutableStateFlow(loadInitialOnboardingCompleted())
    val onboardingCompleted = _onboardingCompleted.asStateFlow()

    // Non-sensitive checkpoint only. API-key drafts are deliberately never persisted here.
    private val _onboardingStep = MutableStateFlow(
        repository.getSetting("onboarding_step", "0").toIntOrNull()?.coerceIn(0, 2) ?: 0
    )
    val onboardingStep = _onboardingStep.asStateFlow()

    // A non-secret durable route flag lets a learner open AI Studio, rotate the device, or have
    // Android recreate the process and still return to the exact connection journey. Key drafts
    // themselves remain memory-only.
    private val _apiSetupInProgress = MutableStateFlow(
        repository.getSetting("api_setup_in_progress", "false") == "true"
    )
    val apiSetupInProgress = _apiSetupInProgress.asStateFlow()

    private val initialVoiceBackend = realBackendForSavedCredentials(
        currentBackend = repository.getSetting("voice_backend", "gemini"),
        geminiApiKey = repository.getGeminiApiKey(),
        openAiApiKey = repository.getSetting("openai_api_key", "")
    ).also { selected ->
        if (selected != normalizeVoiceBackend(repository.getSetting("voice_backend", "gemini"))) {
            repository.setSetting("voice_backend", selected)
        }
    }
    private val _voiceBackend = MutableStateFlow(initialVoiceBackend)
    val voiceBackend = _voiceBackend.asStateFlow()

    private val _isVoiceModeMock = MutableStateFlow(_voiceBackend.value == "mock")
    val isVoiceModeMock = _isVoiceModeMock.asStateFlow()

    // Ported from app/ui/preferences_window.py's pronunciation-analysis toggle: on by default,
    // sends buffered mic audio to Gemini at session end (~$0.01/session), headphones recommended.
    private val _pronunciationAnalysisEnabled = MutableStateFlow(
        repository.getSetting("pronunciation_analysis_enabled", "true") == "true"
    )
    val pronunciationAnalysisEnabled = _pronunciationAnalysisEnabled.asStateFlow()

    private val _azureSpeechKey = MutableStateFlow(
        repository.getSetting("azure_speech_key", "")
    )
    val azureSpeechKey = _azureSpeechKey.asStateFlow()

    private val _azureSpeechRegion = MutableStateFlow(
        repository.getSetting("azure_speech_region", "")
    )
    val azureSpeechRegion = _azureSpeechRegion.asStateFlow()

    private val _learnerAudioSavingEnabled = MutableStateFlow(repository.getSetting("learner_audio_saving_enabled", "true") == "true")
    val learnerAudioSavingEnabled = _learnerAudioSavingEnabled.asStateFlow()

    // Strict-listener mode for record-and-check speaking practice: band-limits the attempt to the
    // ~300–3400 Hz telephone band and raises the "comfortably understood" bar, so a pass approximates
    // a harder human listener (phone order, noisy ward) instead of a lenient accent-robust ASR.
    // Default off — the everyday intelligibility bar stays the norm; this is an opt-in "exam" gear.
    private val _strictListenerEnabled = MutableStateFlow(repository.getSetting("strict_listener_enabled", "false") == "true")
    val strictListenerEnabled = _strictListenerEnabled.asStateFlow()

    // Opt-in auto-save of high-confidence grammar/vocabulary corrections. Default OFF: the app's
    // policy is that a model finding stays a candidate until the learner confirms it (that judgment
    // is itself noticing practice). A learner who has reviewed enough to trust the engine can turn
    // this on so the mistake tracker never quietly starves on a tired day. Audio (pronunciation)
    // findings are never auto-saved — they still require the two-session confirmation policy.
    private val _correctionAutoSaveEnabled = MutableStateFlow(repository.getSetting("correction_auto_save_enabled", "true") == "true")
    val correctionAutoSaveEnabled = _correctionAutoSaveEnabled.asStateFlow()

    // When on, the analysis is asked to write correction/coaching EXPLANATIONS in the learner's
    // native language while keeping the English original/target verbatim. Reduces the meta-language
    // load of reading grammar rationale in a second language. Default off (reading rationale in
    // English is itself exposure); opt-in.
    private val _nativeExplanationsEnabled = MutableStateFlow(repository.getSetting("native_explanations_enabled", "false") == "true")
    val nativeExplanationsEnabled = _nativeExplanationsEnabled.asStateFlow()

    // Beginner guided scaffold (GuidedCueEngine): in a Foundations / coaching_mode encounter, show a
    // live "try saying ___" cue for the next uncovered objective. Default on — the app's target
    // learner can speak English but freezes on *what* to say in a US clinic — and only ever affects
    // coaching_mode cases, so it never intrudes on the graded encounter loop. Fades per skill.
    private val _guidedModeEnabled = MutableStateFlow(repository.getSetting("guided_mode_enabled", "true") == "true")
    val guidedModeEnabled = _guidedModeEnabled.asStateFlow()

    // Open Book (OpenBookEngine): the deliberate answer sheet for a learner whose clinical
    // knowledge, not their English, is what stalls an encounter. Default on — a student who cannot
    // name the disease has no way to practise speaking about it, and the scaffold costs nothing
    // until they actually open it. Never auto-reveals; every level is a separate tap.
    private val _openBookEnabled = MutableStateFlow(repository.getSetting("open_book_enabled", "true") == "true")
    val openBookEnabled = _openBookEnabled.asStateFlow()

    // Opening the answer sheet mid-encounter takes long enough to read that the patient would keep
    // talking into a microphone nobody is listening to. Muting for the duration turns that into a
    // deliberate time-out instead of a lost turn.
    //
    // That is only true of the levels the learner has to *read*, though. Once the sheet is showing
    // the checklist, the model questions or the closing script, it is something to say out loud
    // while looking at it, and holding the mic there forces a toggle-per-sentence that made the
    // scaffold more work than going without it. Hence a policy rather than a switch, defaulting to
    // "only while reading" — see [OpenBookMicPolicy] and [OpenBookEngine.shouldHoldMic].
    private val _openBookMicPolicy = MutableStateFlow(loadOpenBookMicPolicy())
    val openBookMicPolicy = _openBookMicPolicy.asStateFlow()

    /**
     * Read the mic policy, migrating the boolean setting it replaced. Only an explicit opt-out of
     * the old auto-mute carries over: it was the one unambiguous statement of intent ("never touch
     * my microphone"), whereas the old default meant "mute while I read", which is what the new
     * default already does.
     */
    private fun loadOpenBookMicPolicy(): com.example.medvoicetrainer.analysis.OpenBookMicPolicy {
        val stored = repository.getSetting("open_book_mic_policy", "")
        if (stored.isNotBlank()) return com.example.medvoicetrainer.analysis.OpenBookMicPolicy.of(stored)
        return if (repository.getSetting("open_book_auto_mute", "true") == "false") {
            com.example.medvoicetrainer.analysis.OpenBookMicPolicy.NEVER
        } else {
            com.example.medvoicetrainer.analysis.OpenBookMicPolicy.DEFAULT
        }
    }

    /** The Open Book card for the running session, or null when this case has no diagnosis to reveal. */
    private val _openBookCard = MutableStateFlow<com.example.medvoicetrainer.analysis.OpenBookCard?>(null)
    val openBookCard = _openBookCard.asStateFlow()

    /** How much of the answer sheet the learner has opened in this session. Only ever increases. */
    private val _openBookLevel = MutableStateFlow(com.example.medvoicetrainer.analysis.OpenBookLevel.HIDDEN)
    val openBookLevel = _openBookLevel.asStateFlow()

    /**
     * How much of the *closing* script the learner has opened. Tracked separately from
     * [openBookLevel] so that freezing on "so what happens now, doctor?" after an otherwise unaided
     * history neither costs four taps to reach nor reports the session as fully scaffolded — see
     * [com.example.medvoicetrainer.analysis.ClosingLevel].
     */
    private val _openBookClosingLevel = MutableStateFlow(com.example.medvoicetrainer.analysis.ClosingLevel.HIDDEN)
    val openBookClosingLevel = _openBookClosingLevel.asStateFlow()

    /** True once this session started from a pre-visit briefing rather than cold. */
    private val _openBookBriefed = MutableStateFlow(false)
    val openBookBriefed = _openBookBriefed.asStateFlow()

    /**
     * A fresh live encounter does not start its voice transport until the learner explicitly
     * chooses English-only notes or an unrevealed medical case. Keeping this in the ViewModel makes the gate
     * survive Activity recreation; a composable-local flag previously let the mic start behind the
     * dialog and could record the learner reading the briefing itself.
     */
    private val _openBookBriefingPending = MutableStateFlow(false)
    val openBookBriefingPending = _openBookBriefingPending.asStateFlow()
    private var openBookBriefingDecision: CompletableDeferred<Boolean>? = null

    /**
     * The deepest level the learner needed the last time they practised this specialty, or null on
     * their first case in it. Drives the "last time you opened level 3 — try stopping at 2" nudge,
     * which is the only thing that turns the scaffold into something they eventually stop needing.
     */
    private val _openBookPreviousLevel = MutableStateFlow<Int?>(null)
    val openBookPreviousLevel = _openBookPreviousLevel.asStateFlow()

    // --- Everyday Phrasebook (EverydayPhrasebook): Open Book's counterpart for survival/lounge/
    // listening, where nothing clinical is hidden and what runs out is the sentence itself.
    // Default on for the same reason: it costs nothing until the learner taps it, and a learner
    // who has no English for "could you say that again" has no way to practise saying it either.
    private val _phrasebookEnabled =
        MutableStateFlow(repository.getSetting("everyday_phrasebook_enabled", "true") == "true")
    val phrasebookEnabled = _phrasebookEnabled.asStateFlow()

    /** The card for the running everyday session, or null in clinical modes / when switched off. */
    private val _phrasebookCard =
        MutableStateFlow<com.example.medvoicetrainer.analysis.PhrasebookCard?>(null)
    val phrasebookCard = _phrasebookCard.asStateFlow()

    /** How far the ladder is open in this session. Only ever increases, like Open Book's. */
    private val _phrasebookLevel =
        MutableStateFlow(com.example.medvoicetrainer.analysis.PhrasebookLevel.HIDDEN)
    val phrasebookLevel = _phrasebookLevel.asStateFlow()

    /**
     * The learner's own kept expressions, drilled in "Say It: Everyday" under
     * [com.example.medvoicetrainer.analysis.MyPhrasebook.CATEGORY]. Persisted as one settings value.
     */
    private val _myPhrasebook = MutableStateFlow(
        com.example.medvoicetrainer.analysis.MyPhrasebook.read(
            repository.getSetting(com.example.medvoicetrainer.analysis.MyPhrasebook.SETTING_KEY, "[]")
        )
    )
    val myPhrasebook = _myPhrasebook.asStateFlow()

    /**
     * The shared function bank, parsed once per language.
     *
     * Cached because three separate surfaces ask for it — the start screen, the in-session sheet
     * and the drill — and because the drill's category list is rebuilt on every recomposition.
     */
    private var phrasebookBankCache: Pair<String, List<com.example.medvoicetrainer.analysis.EverydayPhraseCategory>>? = null

    @Synchronized
    fun everydayPhraseBank(): List<com.example.medvoicetrainer.analysis.EverydayPhraseCategory> {
        val language = _nativeLanguage.value
        phrasebookBankCache?.takeIf { it.first == language }?.let { return it.second }
        val parsed = com.example.medvoicetrainer.analysis.EverydayPhrasebook.parseBank(
            json = runCatching {
                repository.loadAssetFile(com.example.medvoicetrainer.analysis.EverydayPhrasebook.ASSET)
            }.getOrDefault(""),
            language = normalizeLanguageCode(language),
        )
        phrasebookBankCache = language to parsed
        return parsed
    }

    /**
     * The expressions offered before a scene starts, for the pre-start card.
     *
     * Deliberately the same deterministic selection the in-session sheet uses, so the three lines
     * the learner read on the start screen are the three the sheet opens on — a card that promised
     * different help than it delivered would be worse than no card.
     */
    fun everydayPhrasePreview(
        caseJson: String = "",
    ): List<com.example.medvoicetrainer.analysis.EverydayPhrase> =
        if (!_phrasebookEnabled.value) {
            emptyList()
        } else {
            com.example.medvoicetrainer.analysis.EverydayPhrasebook.preview(
                bank = everydayPhraseBank(),
                caseJson = caseJson,
                language = normalizeLanguageCode(_nativeLanguage.value),
            )
        }

    // The "Import a Conversation" dashboard card is a one-time nudge, not a permanent fixture — once
    // the learner has seen it (or dismissed it with the ✕) it just adds clutter to Home. Dismissing
    // hides it from the dashboard for good; the feature stays reachable from Preferences → Import.
    // Persisted so the card doesn't reappear on every app launch.
    private val _importCardDismissed = MutableStateFlow(repository.getSetting("import_card_dismissed", "false") == "true")
    val importCardDismissed = _importCardDismissed.asStateFlow()

    private val _encounterCaseTags = MutableStateFlow(
        EncounterCaseTagStore.decode(repository.getSetting(EncounterCaseTagStore.SETTING_KEY, "{}"))
    )
    val encounterCaseTags: StateFlow<Map<String, Set<EncounterCaseTag>>> =
        _encounterCaseTags.asStateFlow()

    /** Toggle one of the three fixed case labels and persist it without a Room schema change. */
    fun toggleEncounterCaseTag(caseId: String, tag: EncounterCaseTag) {
        val updated = EncounterCaseTagStore.toggle(_encounterCaseTags.value, caseId, tag)
        _encounterCaseTags.value = updated
        repository.setSetting(EncounterCaseTagStore.SETTING_KEY, EncounterCaseTagStore.encode(updated))
    }

    // Recovery is deliberately non-destructive: closing its dashboard card only clears that
    // session's Home notification. The unfinished transcript remains available in History.
    private val _dismissedRecoverySessionIds = MutableStateFlow(
        repository.getSetting("dismissed_recovery_session_ids", "")
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    )
    val dismissedRecoverySessionIds = _dismissedRecoverySessionIds.asStateFlow()

    // Shown once, on the learner's first Patient Encounter: potential users mistake Encounter mode
    // for a diagnosis trainer. This one-time interstitial reframes it as English speaking practice
    // ("we grade how you communicate, not your medicine"). Persisted so it never repeats.
    private val _englishFirstIntroSeen = MutableStateFlow(
        repository.getSetting("english_first_intro_seen", "false") == "true"
    )
    val englishFirstIntroSeen = _englishFirstIntroSeen.asStateFlow()

    fun markEnglishFirstIntroSeen() {
        if (_englishFirstIntroSeen.value) return
        repository.setSetting("english_first_intro_seen", "true")
        _englishFirstIntroSeen.value = true
    }

    // --- Active Practice Session State ---
    private val _activeSession = MutableStateFlow(ActiveSessionState())
    val activeSession = _activeSession.asStateFlow()

    // --- Survival "Advanced Beta" scene transitions (docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md) ---
    // Non-null only while a beta session has an unanswered proposal on screen. Every other session
    // leaves these at their initial values forever: nothing outside the beta path writes them.
    private val _sceneTransition =
        MutableStateFlow<com.example.medvoicetrainer.voice.SceneTransitionProposal?>(null)
    val sceneTransition = _sceneTransition.asStateFlow()

    /** Facts the learner picked up on a solo errand and now has to relay back, or null. */
    private val _relayFactCard = MutableStateFlow<RelayFactCard?>(null)
    val relayFactCard = _relayFactCard.asStateFlow()

    /**
     * Role of the stand-in a character switch handed the learner to, or null whenever they are with
     * the counterpart the session started with. Drives the manual "go back" affordance, which is
     * the learner's only guaranteed way out of a switch (see VoiceManager.returnToPreviousCharacter).
     */
    private val _sceneCharacter = MutableStateFlow<String?>(null)
    val sceneCharacter = _sceneCharacter.asStateFlow()

    // Written from the voice-client callback thread as well as the main thread.
    @Volatile
    private var sceneTransitionTimeoutJob: Job? = null

    /**
     * Whether the live session's scene-transition tool actually came up asynchronous — see
     * VoiceClientListener.onSceneTransitionModeResolved. True until a session says otherwise, which
     * is the normal case; false means the model goes silent for as long as a chip is on screen, and
     * the chip's own timeout shortens to match (see [sceneTransitionTimeoutMillis]).
     *
     * Only ever written through [setSceneTransitionsNonBlocking], which keeps the exposed timeout
     * in step with it.
     */
    @Volatile
    private var sceneTransitionsNonBlocking = true

    /**
     * How long the chip currently on screen actually has before it declines itself.
     *
     * Exposed rather than kept private because the card draws a countdown bar from it, and the bar
     * used to be hard-coded to [SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS]: on a session that
     * fell back to blocking tools the card vanished at eight seconds with the bar still three
     * quarters full, which reads as the app dropping the card rather than as time running out.
     */
    private val _sceneTransitionTimeoutMillis = MutableStateFlow(
        com.example.medvoicetrainer.voice.SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS,
    )
    val sceneTransitionTimeoutMillis = _sceneTransitionTimeoutMillis.asStateFlow()

    // Stage 3: an accepted solo errand whose acceptance is deliberately still unsent while the
    // learner reads the fact card (see acceptSceneTransition / releaseRelayErrand).
    @Volatile
    private var pendingRelayErrand: com.example.medvoicetrainer.voice.SceneTransitionProposal? = null

    @Volatile
    private var relayReleaseJob: Job? = null

    // --- Active Evaluation / Scorecard State ---
    private val _lastEvaluation = MutableStateFlow<EvaluationResult?>(null)
    val lastEvaluation = _lastEvaluation.asStateFlow()

    private val _deepClinicalReviewState = MutableStateFlow(DeepClinicalReviewUiState())
    val deepClinicalReviewState = _deepClinicalReviewState.asStateFlow()

    private val _correctionDecisions = MutableStateFlow<Map<String, CorrectionDecision>>(emptyMap())
    val correctionDecisions = _correctionDecisions.asStateFlow()
    private val _correctionPredictions = MutableStateFlow<Map<String, String>>(emptyMap())
    val correctionPredictions = _correctionPredictions.asStateFlow()

    // Kept only while the just-completed feedback screen is open. The durable copy is written
    // turn-by-turn to SessionEntity.rawTranscript and remains available from History.
    private val _lastCompletedTranscript = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val lastCompletedTranscript = _lastCompletedTranscript.asStateFlow()
    private val _lastCompletedAudioClips = MutableStateFlow<Map<Int, LearnerAudioClip>>(emptyMap())
    val lastCompletedAudioClips = _lastCompletedAudioClips.asStateFlow()
    private val _lastCompletedPresentationLaunch =
        MutableStateFlow<com.example.medvoicetrainer.analysis.PresentationBuilder.Launch?>(null)
    val lastCompletedPresentationLaunch = _lastCompletedPresentationLaunch.asStateFlow()

    // How much Open Book help the just-finished session had. The feedback report says so plainly:
    // the analysis is told not to deduct for the scaffold, but a scaffolded run must not silently
    // read back as an unaided one.
    private val _lastCompletedOpenBookAids =
        MutableStateFlow(com.example.medvoicetrainer.analysis.OpenBookAidsUsed())
    val lastCompletedOpenBookAids = _lastCompletedOpenBookAids.asStateFlow()

    // The same, for the everyday phrasebook. Kept as its own value rather than folded into the
    // Open Book aids: the two never occur together (one is clinical, one everyday) and a feedback
    // screen that reported "answer sheet level 2" for a Survival chat would be nonsense.
    private val _lastCompletedPhrasebookAids =
        MutableStateFlow(com.example.medvoicetrainer.analysis.PhrasebookAidsUsed())
    val lastCompletedPhrasebookAids = _lastCompletedPhrasebookAids.asStateFlow()

    /** `practice_aids.sections_revealed` from a saved case snapshot; 0 when Open Book was unused. */
    private fun openBookLevelIn(caseJson: String): Int = practiceAidInt(caseJson, "sections_revealed")

    /** `practice_aids.closing_revealed` from a saved case snapshot; 0 when the closing stayed shut. */
    private fun openBookClosingLevelIn(caseJson: String): Int = practiceAidInt(caseJson, "closing_revealed")

    private fun practiceAidInt(caseJson: String, key: String): Int = try {
        JSONObject(caseJson.ifBlank { "{}" })
            .optJSONObject("practice_aids")
            ?.optInt(key, 0)
            ?: 0
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        0
    }

    // The just-completed session's DB row id, so DebriefScreen's chat/insights can be persisted
    // back onto it (mirrors queries.py's save_debrief_chat/save_debrief_insights call sites).
    private var lastSessionId: Int? = null

    private val _sessionReflection = MutableStateFlow(SessionReflectionUiState())
    val sessionReflection = _sessionReflection.asStateFlow()

    // --- Post-session retention moments (milestone -> tomorrow toast) ---
    private val _postSessionMoment = MutableStateFlow<PostSessionMoment?>(null)
    val postSessionMoment = _postSessionMoment.asStateFlow()

    fun dismissPostSessionMoment() {
        _postSessionMoment.value = null
    }

    // --- Friendly UI-error handling (app/ui/error_dialog.py) ---
    private val _uiError = MutableStateFlow<com.example.medvoicetrainer.analysis.ErrorDialog.Content?>(null)
    val uiError = _uiError.asStateFlow()

    fun dismissUiError() {
        _uiError.value = null
    }

    /** Log + telemetry-count + (once per unique error) surface a friendly recoverable dialog. */
    fun handleUiException(source: String, throwable: Throwable) {
        android.util.Log.e("MedVoiceTrainer", "UI error while $source", throwable)
        try {
            com.example.medvoicetrainer.analysis.Telemetry.trackError("ui:$source", throwable)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // telemetry must never affect the app
        }
        com.example.medvoicetrainer.analysis.ErrorDialog.handle(source, throwable)?.let { content ->
            _uiError.value = content
        }
    }

    // --- Keyless demo tour status (app/ui/demo_next_dialog.py's show_demo_next_dialog data) ---
    private val _demoTourStatus = MutableStateFlow<DemoTourStatus?>(null)
    val demoTourStatus = _demoTourStatus.asStateFlow()

    fun dismissDemoTourStatus() {
        _demoTourStatus.value = null
    }

    /** Leave the post-session flow without treating an already-finished session as active. */
    fun dismissFeedback() {
        _lastEvaluation.value = null
        _correctionDecisions.value = emptyMap()
        _correctionPredictions.value = emptyMap()
        _lastCompletedTranscript.value = emptyList()
        _lastCompletedAudioClips.value = emptyMap()
        _lastCompletedPresentationLaunch.value = null
        _deepClinicalReviewState.value = DeepClinicalReviewUiState()
        _sessionReflection.value = SessionReflectionUiState()
        _postSessionMoment.value = null
        _demoTourStatus.value = null
    }

    data class FeedbackReadyEvent(val sessionId: Int, val caseName: String)

    private val detachedAnalysisGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    // The draft session is already in Room while its detached evaluation is running. Keep its id
    // in UI state so History can distinguish "still working" from a genuinely failed analysis.
    private val _backgroundAnalysisSessionIds = MutableStateFlow<Set<Int>>(emptySet())
    val backgroundAnalysisSessionIds = _backgroundAnalysisSessionIds.asStateFlow()
    private val _feedbackReadyEvent = MutableStateFlow<FeedbackReadyEvent?>(null)
    val feedbackReadyEvent = _feedbackReadyEvent.asStateFlow()
    private val _historyNavigationRequests = MutableStateFlow(0L)
    val historyNavigationRequests = _historyNavigationRequests.asStateFlow()

    /** Leave the wait screen without cancelling the saved session or its evaluation coroutine. */
    fun continueAnalysisInBackground() {
        val state = _activeSession.value
        if (!state.isActive || !state.isFinishing || state.error != null) return
        val sessionId = activeSessionId ?: return
        val generation = voiceSessionGeneration.get()
        detachedAnalysisGenerations.add(generation)
        _backgroundAnalysisSessionIds.value = _backgroundAnalysisSessionIds.value + sessionId
        com.example.medvoicetrainer.FeedbackCompletionWorker.enqueue(
            getApplication(),
            sessionId,
            state.caseName,
        )
        _activeSession.value = ActiveSessionState()
    }

    fun consumeFeedbackReadyEvent() {
        _feedbackReadyEvent.value = null
    }

    fun requestHistoryNavigation() {
        _historyNavigationRequests.value += 1L
    }

    // --- Conversation import ("paste a ChatGPT/Gemini live conversation") ---
    // Answers "why not just use ChatGPT's subscription voice mode" by letting a conversation held
    // entirely outside this app still flow through the exact same analysis/calibration/SRS
    // pipeline finishSession() uses for a live session — see docs/DIFFERENTIATION_ANSWERS.md Q1.
    private val _importUiState = MutableStateFlow(ImportUiState())
    val importUiState = _importUiState.asStateFlow()

    fun clearImportError() {
        _importUiState.value = _importUiState.value.copy(error = null)
    }

    // Text shared in from another app (e.g. Android's share sheet on a copied ChatGPT/Gemini live
    // conversation) via ACTION_SEND — see MainActivity's handleShareIntent. Consumed once by the
    // Import screen so re-composition (rotation, etc.) doesn't re-trigger the same share.
    private val _pendingImportText = MutableStateFlow<String?>(null)
    val pendingImportText = _pendingImportText.asStateFlow()

    fun receiveSharedText(text: String) {
        if (text.isBlank()) return
        // A share sheet can hand us an entire chat export rather than a single conversation;
        // cap it well above any real session transcript so a huge/accidental share can't stall
        // TranscriptImportParser or the Import screen's text field.
        _pendingImportText.value = text.take(MAX_SHARED_IMPORT_CHARS)
    }

    fun consumePendingImportText() {
        _pendingImportText.value = null
    }

    /**
     * Runs a pasted transcript through the same analysis pipeline as a live session, then shows
     * the ordinary FeedbackScreen (driven by [lastEvaluation] becoming non-null, same as after
     * finishSession()). Deliberately independent of [_activeSession]/[voiceManager] — there is no
     * live session here, just a static transcript — so it cannot interfere with (or be interfered
     * with by) a real in-progress session.
     *
     * Persisted with voiceBackend = "imported" so voice-minute cost displays and the live-only
     * fluency/latency signals never mistake this for a real-time session; it otherwise counts as
     * an ordinary session of [domain]'s mode for History, the Dashboard, and the SRS mistake loop.
     */
    fun importTranscript(
        domain: ImportDomain,
        rawPastedText: String,
        speakerOrder: com.example.medvoicetrainer.analysis.ImportSpeakerOrder =
            com.example.medvoicetrainer.analysis.ImportSpeakerOrder.LEARNER_FIRST
    ) {
        if (_importUiState.value.isAnalyzing) return
        val parsedTranscript = com.example.medvoicetrainer.analysis.TranscriptImportParser.parse(
            rawPastedText,
            speakerOrder
        )
        if (!com.example.medvoicetrainer.analysis.TranscriptImportParser.hasLearnerTurn(parsedTranscript)) {
            // The parser now accepts raw unlabelled text too, so reaching here means the paste had
            // no two-party shape at all (a single block, or empty) — telling the learner to add
            // "You:" prefixes would be advice for a problem they no longer have.
            _importUiState.value = ImportUiState(
                error = "Couldn't find a two-way conversation in that text. Paste at least a couple " +
                    "of alternating messages — speaker labels like \"You:\" / \"AI:\" are optional."
            )
            return
        }

        val spec = when (domain) {
            ImportDomain.CLINICAL -> ImportedCaseSpec(
                mode = "encounter",
                caseId = "imported_clinical",
                caseName = "Imported Conversation (Patient Encounter)",
                caseJson = JSONObject().put(
                    "learning_objectives",
                    JSONArray()
                        .put("Establish rapport and elicit CC")
                        .put("Screen for red flags")
                        .put("Address ICE (Ideas, Concerns, Expectations)")
                ).toString()
            )
            ImportDomain.SURVIVAL -> ImportedCaseSpec(
                mode = "survival",
                caseId = "imported_survival",
                caseName = "Imported Conversation (Survival English)",
                caseJson = "{}"
            )
            ImportDomain.INTERVIEW -> ImportedCaseSpec(
                mode = "interview",
                caseId = "imported_interview",
                caseName = "Imported Conversation (Residency Interview)",
                // Declared explicitly — resolveEvalTemplateName's fallback for a non-everyday case
                // with no eval_template is "diagnostic_clinical_english", which is the wrong rubric
                // for an interview transcript (see data/eval/residency_interview.json).
                caseJson = JSONObject().put("eval_template", "residency_interview").toString()
            )
        }

        _importUiState.value = ImportUiState(isAnalyzing = true)
        viewModelScope.launch {
            try {
                val createdAtIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                // A synthetic stand-in for the live ActiveSessionState — generateUnavailableEvaluation
                // and parseEvaluationJson only read its transcript/caseJson/caseName fields, so this
                // is a safe, non-invasive way to reuse them without touching the real active session.
                val syntheticState = ActiveSessionState(
                    isActive = true,
                    mode = spec.mode,
                    caseId = spec.caseId,
                    caseName = spec.caseName,
                    caseJson = spec.caseJson,
                    createdAt = createdAtIso,
                    transcript = parsedTranscript
                )

                val transcriptJson = serializeTranscript(parsedTranscript, emptyMap())
                val pythonRoleTranscriptJson = parsedTranscript.toPythonRoleTranscriptJson()
                // Real speech timing (pace, response latency) genuinely doesn't exist for a pasted
                // transcript, so WPM is left unset rather than guessed from an invented duration —
                // FluencyMetrics.addWpm only fills it in when durationSeconds > 0.
                val fluencyMetrics = FluencyMetrics.computeFluencyMetrics(pythonRoleTranscriptJson)

                var analysisBackendVal = _analysisBackend.value
                var analysisApiKey = getApiKeyForBackend(analysisBackendVal)
                if (analysisApiKey.isBlank() && analysisBackendVal != "gemini") {
                    val geminiFallbackKey = getApiKeyForBackend("gemini")
                    if (geminiFallbackKey.isNotBlank()) {
                        analysisBackendVal = "gemini"
                        analysisApiKey = geminiFallbackKey
                    }
                }
                val analysisModel = getModelForBackend(analysisBackendVal)

                val caseDataMap = jsonObjectToMap(spec.caseJson)
                val analysisDomain = ScoreDomains.inferAnalysisDomain(caseDataMap, null, spec.mode, null)
                val everydayDomainForRubric = analysisDomain == "everyday"
                val evalTemplateName = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder
                    .resolveEvalTemplateName(caseDataMap, everydayDomainForRubric)
                val evalData = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder
                    .loadEvalTemplate(getApplication(), evalTemplateName)
                val rubricContext = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder.buildRubricContext(
                    caseData = caseDataMap,
                    evalData = evalData,
                    everyday = everydayDomainForRubric,
                    selfScores = null,
                    studentSoap = null,
                    commitments = emptyList()
                ).let { base ->
                    listOf(
                        base,
                        // Blank unless this is a Free Talk session: the partner was muted on
                        // purpose, and the talk-share counts give the evaluator hard evidence.
                        com.example.medvoicetrainer.analysis.FreeTalk.analysisNote(caseDataMap, pythonRoleTranscriptJson),
                        com.example.medvoicetrainer.analysis.CorrectionFeedbackMemory.promptNote(
                            repository.getSetting("correction_feedback_memory", "{}")
                        ),
                        nativeExplanationNote()
                    ).filter { it.isNotBlank() }.joinToString("\n\n")
                }

                val intelligibilityMetrics = Intelligibility.computeIntelligibilityMetrics(
                    parsedTranscript.toPythonRoleTranscriptMaps(),
                    mode = if (analysisDomain == "everyday") "everyday" else "clinical"
                )

                var analysisUsage: LlmUsage? = null
                var rawEvalJsonForStorage: String? = null
                val evalResult = if (analysisApiKey.isBlank()) {
                    val unavailable = generateUnavailableEvaluation(syntheticState, analysisDomain, typedDemo = false)
                    rawEvalJsonForStorage = JSONObject()
                        .put("_demo", false)
                        .put("_locked", true)
                        .put("summary_feedback", unavailable.summaryFeedback)
                        .put("corrections", JSONArray())
                        .put("anki_cards", JSONArray())
                        .put("shadowing_items", JSONArray())
                        .toString()
                    unavailable
                } else {
                    val (rawEval, usage) = AnalysisEngine.evaluateSessionWithUsage(
                        backend = analysisBackendVal,
                        apiKey = analysisApiKey,
                        model = analysisModel,
                        transcript = transcriptJson,
                        caseJson = spec.caseJson,
                        nativeLanguage = _nativeLanguage.value,
                        analysisDomain = analysisDomain,
                        rubricContext = rubricContext
                    )
                    analysisUsage = usage
                    rawEvalJsonForStorage = rawEval
                    parseEvaluationJson(rawEval, syntheticState, analysisDomain)
                }

                val calibratedEval = applyEvidenceConfidence(
                    evaluation = evalResult,
                    transcript = parsedTranscript.toPythonRoleTranscriptMaps(),
                    caseData = caseDataMap,
                )
                val claudeCost = analysisUsage?.let {
                    CostTracker.computeAnalysisCost(
                        analysisBackendVal,
                        it.inputTokens,
                        it.outputTokens,
                        it.cachedTokens,
                        it.modelUsed.ifBlank { analysisModel },
                    )
                } ?: 0.0

                val rawClaudeResponseJson = try {
                    val root = if (!rawEvalJsonForStorage.isNullOrBlank()) {
                        JSONObject(rawEvalJsonForStorage!!.replace("```json", "").replace("```", "").trim())
                    } else {
                        JSONObject()
                    }
                    if (fluencyMetrics != null) {
                        val fm = JSONObject()
                        fm.put("user_word_count", fluencyMetrics.userWordCount)
                        fm.put("user_turn_count", fluencyMetrics.userTurnCount)
                        fluencyMetrics.wordsPerMinute?.let { fm.put("wpm", it) }
                        fm.put("filler_rate", fluencyMetrics.fillerRate)
                        fm.put("talk_time_ratio", fluencyMetrics.talkTimeRatio)
                        fluencyMetrics.avgResponseGapSeconds?.let { fm.put("avg_response_gap_seconds", it) }
                        fm.put("very_short_turn_count", fluencyMetrics.veryShortTurnCount)
                        fm.put("confidence_band", fluencyMetrics.confidenceBand)
                        root.put("fluency_metrics", fm)
                    }
                    if (intelligibilityMetrics.isNotEmpty()) {
                        root.put("intelligibility", JSONObject(intelligibilityMetrics))
                    }
                    root.put("_analysis_prompt_version", EvalPromptBuilder.PROMPT_VERSION)
                    root.put(
                        "_analysis_model_used",
                        analysisUsage?.modelUsed ?: analysisModel
                    )
                    calibratedEval.reliabilityBadge?.let {
                        root.put("score_reliability", JSONObject(it))
                    }
                    root.put("imported", true)
                    root.toString()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    rawEvalJsonForStorage
                }

                val completedSession = SessionEntity(
                    createdAt = createdAtIso,
                    mode = spec.mode,
                    analysisDomain = analysisDomain,
                    caseName = spec.caseName,
                    caseId = spec.caseId,
                    evalTemplate = evalTemplateName,
                    voiceBackend = "imported",
                    voiceModel = null,
                    analysisModel = analysisUsage?.modelUsed ?: analysisModel,
                    rawCaseJson = spec.caseJson,
                    rawTranscript = transcriptJson,
                    learnerTurnCount = parsedTranscript.count { it.first == "doctor" && it.second.isNotBlank() },
                    durationSeconds = 0,
                    rawClaudeResponse = rawClaudeResponseJson,
                    rawEvalJson = rawEvalJsonForStorage,
                    grammarScore = calibratedEval.grammarScore,
                    medicalAccuracyScore = calibratedEval.medicalAccuracy,
                    clinicalReasoningScore = calibratedEval.clinicalReasoning,
                    professionalismScore = calibratedEval.professionalism,
                    fluencyScore = calibratedEval.fluencyScore,
                    summaryFeedback = calibratedEval.summaryFeedback,
                    studentSoapNote = "",
                    soapNote = calibratedEval.soapNote,
                    checklistResults = calibratedEval.checklistResultsJson,
                    historyCompleteness = calibratedEval.historyCompleteness,
                    iceElicited = if (calibratedEval.iceElicited) 1 else 0,
                    empathyMarkersFound = calibratedEval.empathyMarkersJson,
                    referenceSoap = calibratedEval.referenceSoap,
                    corrections = calibratedEval.rawCorrectionsJson,
                    ankiCards = calibratedEval.ankiCardsJson,
                    userWordCount = fluencyMetrics?.userWordCount ?: calibratedEval.wordCount,
                    wordsPerMinute = fluencyMetrics?.wordsPerMinute ?: 0.0,
                    fillerRate = fluencyMetrics?.fillerRate ?: 0.0,
                    talkTimeRatio = fluencyMetrics?.talkTimeRatio ?: 0.0,
                    claudeInputTokens = analysisUsage?.inputTokens ?: 0,
                    claudeOutputTokens = analysisUsage?.outputTokens ?: 0,
                    claudeCachedTokens = analysisUsage?.cachedTokens ?: 0,
                    claudeCostUsd = claudeCost,
                    totalCostUsd = claudeCost,
                    costEstimated = false,
                    // An imported transcript is inserted already analyzed; it was never a live
                    // conversation, so it must not inherit the "still running" default.
                    endReason = SessionEndReason.COMPLETED,
                )
                val insertedId = repository.insertSession(completedSession).toInt()
                lastSessionId = insertedId

                _lastEvaluation.value = calibratedEval.copy(
                    fluencyMetrics = fluencyMetrics,
                    intelligibility = calibratedEval.intelligibility
                        ?: intelligibilityMetrics.ifEmpty { null },
                )
                _lastEvaluation.value?.let { initializeCorrectionDecisions(it, insertedId) }
                _lastCompletedTranscript.value = parsedTranscript
                _lastCompletedOpenBookAids.value = com.example.medvoicetrainer.analysis.OpenBookAidsUsed()
                _lastCompletedPhrasebookAids.value = com.example.medvoicetrainer.analysis.PhrasebookAidsUsed()
                _lastCompletedAudioClips.value = emptyMap()
                _importUiState.value = ImportUiState()

                if (!calibratedEval.evaluationLocked) {
                    try {
                        evaluatePostSessionMoments()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // best-effort retention moments — never block the import result from showing
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _importUiState.value = ImportUiState(
                    error = "Couldn't analyze this conversation: ${com.example.medvoicetrainer.api.ApiError.userMessage(e)}"
                )
            }
        }
    }

    /** Ported from demo_next_dialog.py's dlg.protocol(...) / _record_choice: records the user's
     * choice on the post-demo decision screen so shouldAutoShowDecision's frequency guard works. */
    fun recordDemoDecision(choice: String) {
        com.example.medvoicetrainer.analysis.DemoTour.markDecisionShown(choice) { key, value ->
            repository.setSetting(key, value)
        }
    }

    init {
        com.example.medvoicetrainer.analysis.ApiCostRecorder.initialize(application)
        // Automatically sync initial API Key & config
        _geminiApiKey.value = repository.getGeminiApiKey()

        // Wire up telemetry.py's settings accessors. Silent no-op until
        // TelemetryConfig.endpoint/apiKey are set (empty by default) — see Telemetry.kt.
        com.example.medvoicetrainer.analysis.Telemetry.init(
            getSetting = { key, default -> repository.getSetting(key, default) },
            setSetting = { key, value -> repository.setSetting(key, value) }
        )

        // Let the error dialog scrub this install's credentials out of any stack trace it shows,
        // since its detail pane is copyable and its body asks the learner to post it publicly.
        // Read lazily so keys edited in Preferences mid-run are covered too.
        com.example.medvoicetrainer.analysis.ErrorDialog.installSecretProvider {
            listOf(
                _geminiApiKey.value,
                _openAiApiKey.value,
                _claudeApiKey.value,
                _azureSpeechKey.value
            ).filter { it.isNotBlank() }
        }
    }

    private val _analysisBackend = MutableStateFlow(repository.getSetting("analysis_backend", "gemini"))
    val analysisBackend = _analysisBackend.asStateFlow()

    fun updateAnalysisBackend(backend: String) {
        repository.setSetting("analysis_backend", backend)
        _analysisBackend.value = backend
    }

    fun updateVoiceBackend(backend: String) {
        val normalized = normalizeVoiceBackend(backend)
        repository.setSetting("voice_backend", normalized)
        _voiceBackend.value = normalized
        _isVoiceModeMock.value = normalized == "mock"
    }

    private fun reflectOnboardingCompleted() {
        _onboardingStep.value = 0
        _onboardingCompleted.value = true
        _apiSetupInProgress.value = false
    }

    fun updateOnboardingStep(step: Int) {
        val safeStep = step.coerceIn(0, 2)
        repository.setSetting("onboarding_step", safeStep.toString())
        _onboardingStep.value = safeStep
    }

    fun openApiSetup() {
        repository.setSetting("api_setup_in_progress", "true")
        _apiSetupInProgress.value = true
    }

    fun closeApiSetup() {
        repository.setSetting("api_setup_in_progress", "false")
        _apiSetupInProgress.value = false
    }

    /** Finish first run without credentials while preserving the fully local scripted tour. */
    fun completeOnboardingWithDemo(nativeLanguage: String) {
        clearProviderVerification("gemini")
        repository.completeDemoOnboarding(nativeLanguage)
        _nativeLanguage.value = nativeLanguage
        _voiceBackend.value = "demo"
        _isVoiceModeMock.value = false
        reflectOnboardingCompleted()
    }

    /**
     * Persist a key only after the onboarding UI has verified that exact draft. One Gemini key
     * powers both the live patient and feedback, so novices do not have to understand providers
     * or model routing during first run.
     */
    fun completeOnboardingWithGeminiKey(key: String, nativeLanguage: String) {
        val cleanKey = key.trim()
        require(cleanKey.isNotBlank()) { "A Gemini API key is required" }
        require(verifiedProviderKeys["gemini"] == cleanKey) {
            "The current Gemini API key has not been verified"
        }
        repository.completeGeminiOnboarding(cleanKey, nativeLanguage)
        _geminiApiKey.value = cleanKey
        _nativeLanguage.value = nativeLanguage
        _analysisBackend.value = "gemini"
        _voiceBackend.value = "gemini"
        _isVoiceModeMock.value = false
        reflectOnboardingCompleted()
    }

    fun getApiKeyForBackend(backend: String): String {
        // Trim on read so a pasted key with trailing whitespace (very common on mobile) can't
        // silently break auth — the header/URL the key lands in treats trailing whitespace as
        // part of the credential. Gemini keys are already trimmed inside getGeminiApiKey().
        return when (backend.trim().lowercase(Locale.ROOT)) {
            "claude" -> repository.getSetting("claude_api_key", "").trim()
            "openai" -> repository.getSetting("openai_api_key", "").trim()
            "gemini" -> repository.getGeminiApiKey()
            // Demo/mock must remain offline even when a paid-provider key also exists.
            else -> ""
        }
    }

    fun setApiKeyForBackend(backend: String, key: String) {
        val cleanKey = key.trim()
        val cleanBackend = backend.trim().lowercase(Locale.ROOT)
        when (cleanBackend) {
            "claude" -> {
                repository.setSetting("claude_api_key", cleanKey)
                _claudeApiKey.value = cleanKey
            }
            "openai" -> {
                repository.setSetting("openai_api_key", cleanKey)
                _openAiApiKey.value = cleanKey
                if (cleanKey.isNotBlank() && _voiceBackend.value in setOf("demo", "mock")) {
                    updateVoiceBackend("openai")
                }
            }
            "gemini" -> updateGeminiApiKey(cleanKey)
        }
        if (cleanKey.isNotBlank() && cleanBackend != "gemini") {
            verifyProviderKey(cleanBackend)
        } else if (cleanKey.isBlank()) {
            providerVerificationJobs.remove(cleanBackend)?.cancel()
            verifiedProviderKeys.remove(cleanBackend)
            _providerStatusMap.update { it + (cleanBackend to com.example.medvoicetrainer.api.ProviderVerificationResult(com.example.medvoicetrainer.api.ProviderStatus.NOT_SET)) }
        }
    }

    fun getModelForBackend(backend: String): String {
        return when (backend.trim().lowercase(Locale.ROOT)) {
            "claude" -> repository.getSetting("claude_model", "claude-sonnet-5")
            "openai" -> repository.getSetting("openai_model", "gpt-5.1")
            else -> repository.getGeminiModel()
        }
    }

    fun setModelForBackend(backend: String, model: String) {
        val cleanModel = model.trim()
        when (backend.trim().lowercase(Locale.ROOT)) {
            "claude" -> {
                repository.setSetting("claude_model", cleanModel)
                _claudeModel.value = cleanModel
            }
            "openai" -> {
                repository.setSetting("openai_model", cleanModel)
                _openAiModel.value = cleanModel
            }
            else -> updateGeminiModel(cleanModel)
        }
    }

    // --- Settings Operations ---
    fun updateGeminiApiKey(key: String) {
        val cleanKey = key.trim()
        repository.saveGeminiApiKey(cleanKey)
        _geminiApiKey.value = cleanKey
        if (cleanKey.isNotBlank()) {
            if (_voiceBackend.value in setOf("demo", "mock")) {
                updateVoiceBackend("gemini")
            }
            verifyProviderKey("gemini")
        } else {
            providerVerificationJobs.remove("gemini")?.cancel()
            verifiedProviderKeys.remove("gemini")
            _providerStatusMap.update { it + ("gemini" to com.example.medvoicetrainer.api.ProviderVerificationResult(com.example.medvoicetrainer.api.ProviderStatus.NOT_SET)) }
        }
    }

    fun updateOpenAiApiKey(key: String) {
        setApiKeyForBackend("openai", key)
    }

    fun updateClaudeApiKey(key: String) {
        setApiKeyForBackend("claude", key)
    }

    fun updateOpenAiModel(model: String) {
        setModelForBackend("openai", model)
    }

    fun updateClaudeModel(model: String) {
        setModelForBackend("claude", model)
    }

    fun updateGeminiModel(model: String) {
        val normalized = com.example.medvoicetrainer.api.normalizeGeminiAnalysisModel(model)
        repository.saveGeminiModel(normalized)
        _geminiModel.value = normalized
    }

    fun updateNativeLanguage(langCode: String) {
        repository.setSetting("native_language", langCode)
        _nativeLanguage.value = langCode
    }

    fun updateUiLanguage(langCode: String) {
        // Retain this compatibility entry point for older callers, but never allow it to change
        // the main application's display language.
        repository.setSetting("ui_language", "en")
        _uiLanguage.value = "en"
    }

    fun updatePronunciationAnalysisEnabled(enabled: Boolean) {
        repository.setSetting("pronunciation_analysis_enabled", if (enabled) "true" else "false")
        _pronunciationAnalysisEnabled.value = enabled
    }

    fun updateStrictListenerEnabled(enabled: Boolean) {
        repository.setSetting("strict_listener_enabled", if (enabled) "true" else "false")
        _strictListenerEnabled.value = enabled
    }

    fun updateCorrectionAutoSaveEnabled(enabled: Boolean) {
        repository.setSetting("correction_auto_save_enabled", if (enabled) "true" else "false")
        _correctionAutoSaveEnabled.value = enabled
    }

    fun updateNativeExplanationsEnabled(enabled: Boolean) {
        repository.setSetting("native_explanations_enabled", if (enabled) "true" else "false")
        _nativeExplanationsEnabled.value = enabled
    }

    /**
     * Prompt block that asks the evaluator to write the human-readable rationale in the learner's
     * L1 while keeping the taught English verbatim. Empty when the toggle is off or the learner's
     * native language is already English. Appended to the analysis rubric context.
     */
    internal fun nativeExplanationNote(): String {
        if (!_nativeExplanationsEnabled.value) return ""
        val lang = _nativeLanguage.value.trim()
        if (lang.isEmpty() || lang.equals("en", ignoreCase = true) ||
            lang.equals("english", ignoreCase = true)
        ) return ""
        return "OUTPUT LANGUAGE: Write every `explanation` field and `summary_feedback` in the " +
            "learner's native language ($lang) so the grammar rationale is easy to absorb. Keep " +
            "`original`, `corrected`, `category`, `pattern_id`, and any English example phrase in " +
            "English exactly — only the explanatory prose is translated."
    }

    /**
     * Native-language code to write pronunciation coaching prose in, or "" when the toggle is off or
     * the learner is an English native. Consumed by the pronunciation engine / speaking judge to
     * localise only the coaching tip, never the target word.
     */
    internal fun nativeExplanationLang(): String {
        if (!_nativeExplanationsEnabled.value) return ""
        val lang = _nativeLanguage.value.trim()
        if (lang.isEmpty() || lang.equals("en", ignoreCase = true) ||
            lang.equals("english", ignoreCase = true)
        ) return ""
        return lang
    }

    fun updateAzureSpeechCredentials(key: String, region: String) {
        val cleanKey = key.trim()
        val cleanRegion = region.trim().lowercase(Locale.ROOT)
        repository.setSetting("azure_speech_key", cleanKey)
        repository.setSetting("azure_speech_region", cleanRegion)
        _azureSpeechKey.value = cleanKey
        _azureSpeechRegion.value = cleanRegion
    }

    private fun hasAzureSpeechCredentials(): Boolean =
        _azureSpeechKey.value.isNotBlank() && _azureSpeechRegion.value.isNotBlank()

    fun updateLearnerAudioSavingEnabled(enabled: Boolean) {
        repository.setSetting("learner_audio_saving_enabled", if (enabled) "true" else "false")
        _learnerAudioSavingEnabled.value = enabled
    }

    fun updateGuidedModeEnabled(enabled: Boolean) {
        repository.setSetting("guided_mode_enabled", if (enabled) "true" else "false")
        _guidedModeEnabled.value = enabled
    }

    fun updateOpenBookEnabled(enabled: Boolean) {
        repository.setSetting("open_book_enabled", if (enabled) "true" else "false")
        _openBookEnabled.value = enabled
    }

    fun updateOpenBookMicPolicy(policy: com.example.medvoicetrainer.analysis.OpenBookMicPolicy) {
        repository.setSetting("open_book_mic_policy", policy.key)
        _openBookMicPolicy.value = policy
    }

    // --- Open Book (OpenBookEngine) ---
    // Per-specialty memory of how much answer sheet the learner needed, so the next cardiology case
    // can ask them to try one level shallower. Keyed by specialty rather than by case: the point is
    // "you know more cardiology now", not "you memorised this case".
    private fun openBookLevelKey(system: String) =
        "open_book_level::" + system.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /**
     * Open one more level of the answer sheet.
     *
     * Levels only ever go up: the learner can close the sheet, but having seen the diagnosis they
     * cannot un-see it, and a counter that could be walked back would misreport how much help the
     * session actually had. The reveal is written into the case snapshot as `practice_aids`, which
     * [AnalysisPromptBuilder] already turns into an instruction not to penalize the scaffolding.
     */
    fun revealOpenBook(level: com.example.medvoicetrainer.analysis.OpenBookLevel) {
        openBookLevelReached(level, rememberForSpecialty = true)
    }

    /**
     * @param rememberForSpecialty whether this counts as "how much help they needed in cardiology".
     * True when the learner opened the sheet themselves; false for a pre-visit briefing, which they
     * chose *before* meeting the case. Recording a briefing here would push the specialty's
     * remembered level to 3 and so permanently silence the "you only needed level 2 last time — try
     * going in cold" nudge, which is the one thing that gets a learner off the scaffold.
     */
    private fun openBookLevelReached(
        level: com.example.medvoicetrainer.analysis.OpenBookLevel,
        rememberForSpecialty: Boolean,
    ) {
        val state = _activeSession.value
        if (!state.isActive) return
        if (level.step <= _openBookLevel.value.step) return
        _openBookLevel.value = level

        recordLearningEvent("open_book_reveal", mapOf("level" to level.step, "name" to level.name))
        writePracticeAids { aids ->
            aids.put("open_book", true)
            aids.put("sections_revealed", level.step)
        }
        if (!rememberForSpecialty) return

        val system = runCatching { JSONObject(state.caseJson).optString("system", "") }.getOrDefault("")
        if (system.isNotBlank()) {
            val key = openBookLevelKey(system)
            // Store what this session used, not a lifetime high-water mark. If the learner improves
            // from level 4 to level 2, the next case should encourage them from level 2.
            val previous = repository.getSetting(key, "0").toIntOrNull() ?: 0
            val remembered = com.example.medvoicetrainer.analysis.OpenBookEngine
                .rememberedLevelAfterReveal(previous, level.step)
            repository.setSetting(key, remembered.toString())
        }
    }

    /**
     * Open one more step of the closing script. Independent of [revealOpenBook] in both directions:
     * reaching the closing script says nothing about whether the learner needed the diagnosis, and
     * the diagnosis ladder never opens this one.
     */
    fun revealOpenBookClosing(level: com.example.medvoicetrainer.analysis.ClosingLevel) {
        if (!_activeSession.value.isActive) return
        if (level.step <= _openBookClosingLevel.value.step) return
        _openBookClosingLevel.value = level

        recordLearningEvent("open_book_closing_reveal", mapOf("level" to level.step, "name" to level.name))
        writePracticeAids { aids ->
            aids.put("open_book", true)
            aids.put("closing_revealed", level.step)
        }
    }

    /**
     * Start this encounter in English-practice mode after the learner reviewed the case notes.
     * This is deliberately independent of [openBookLevel]: choosing a focus before the visit is
     * not the same event as opening progressively deeper rescue help after the visit has begun.
     */
    fun applyOpenBookBriefing() {
        if (!_activeSession.value.isActive) return
        if (_openBookCard.value == null) return
        _openBookBriefed.value = true
        recordLearningEvent("open_book_briefing", mapOf("mode" to "pre_visit"))
        writePracticeAids { aids -> aids.put("briefed", true) }
    }

    /**
     * Optional stage-two concept repair. The scoring call has already grounded the finding in an
     * exact learner quote; this call only teaches that one concept and never changes scores.
     */
    fun requestDeepClinicalReview(findingJson: String) {
        val finding = runCatching {
            com.example.medvoicetrainer.analysis.MisconceptionReview.parse(
                JSONArray().put(JSONObject(findingJson)),
            ).firstOrNull()
        }.getOrNull() ?: return
        if (finding.deepReviewJson.isNotBlank()) return
        val currentEvaluation = _lastEvaluation.value ?: return
        if (currentEvaluation.evaluationLocked || currentEvaluation.analysisDomain == "everyday") return
        if (_deepClinicalReviewState.value.loadingKey != null) return

        _deepClinicalReviewState.value = DeepClinicalReviewUiState(loadingKey = finding.key)
        viewModelScope.launch {
            try {
                val sessionId = lastSessionId ?: error("The completed session is no longer available.")
                val session = withContext(Dispatchers.IO) {
                    repository.getSessionById(sessionId)
                } ?: error("The completed session could not be loaded.")

                var backend = _analysisBackend.value
                var apiKey = getApiKeyForBackend(backend)
                if (apiKey.isBlank() && backend != "gemini") {
                    getApiKeyForBackend("gemini").takeIf { it.isNotBlank() }?.let {
                        backend = "gemini"
                        apiKey = it
                    }
                }
                if (apiKey.isBlank()) error("Add an analysis API key to generate a detailed concept review.")
                val model = getModelForBackend(backend)
                val (systemPrompt, userPrompt) =
                    com.example.medvoicetrainer.analysis.MisconceptionReview.buildDeepReviewPrompts(
                        findingJson = finding.toJson().toString(),
                        caseJson = session.rawCaseJson,
                        nativeLanguage = _nativeLanguage.value,
                    )
                val raw = withContext(Dispatchers.IO) {
                    com.example.medvoicetrainer.analysis.AnalysisEngine.generateContent(
                        backend = backend,
                        apiKey = apiKey,
                        model = model,
                        prompt = userPrompt,
                        systemInstruction = systemPrompt,
                    )
                }
                val deepReview = com.example.medvoicetrainer.analysis.MisconceptionReview
                    .sanitizeDeepReview(raw)

                val latestEvaluation = _lastEvaluation.value ?: return@launch
                val findings = com.example.medvoicetrainer.analysis.MisconceptionReview
                    .parseJson(latestEvaluation.misconceptionReviewJson)
                val updatedArray = JSONArray().apply {
                    findings.forEach { existing ->
                        val item = existing.toJson()
                        if (existing.key == finding.key) item.put("deep_review", deepReview)
                        put(item)
                    }
                }
                val updatedJson = updatedArray.toString()
                val storedRoot = runCatching {
                    JSONObject(session.rawEvalJson?.replace("```json", "")?.replace("```", "")?.trim().orEmpty())
                }.getOrElse { JSONObject() }
                storedRoot.put("misconception_review", updatedArray)
                withContext(Dispatchers.IO) {
                    repository.updateSessionRawEvaluation(sessionId, storedRoot.toString())
                }
                _lastEvaluation.update { evaluation ->
                    evaluation?.copy(misconceptionReviewJson = updatedJson)
                }
                _deepClinicalReviewState.value = DeepClinicalReviewUiState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _deepClinicalReviewState.value = DeepClinicalReviewUiState(
                    errorKey = finding.key,
                    errorMessage = e.message ?: "Detailed clinical review could not be generated.",
                )
            }
        }
    }

    fun clearDeepClinicalReviewError() {
        _deepClinicalReviewState.update { state ->
            if (state.loadingKey == null) DeepClinicalReviewUiState() else state
        }
    }

    // --- Everyday Phrasebook (EverydayPhrasebook) ---

    fun updatePhrasebookEnabled(enabled: Boolean) {
        repository.setSetting("everyday_phrasebook_enabled", if (enabled) "true" else "false")
        _phrasebookEnabled.value = enabled
        if (!enabled) {
            _phrasebookCard.value = null
            phrasebookRules = emptyList()
        }
    }

    /**
     * Open one more step of the phrasebook: the function, then the frame, then the sentence.
     *
     * Levels only go up, for the same reason Open Book's do — a sentence that has been read cannot
     * be un-read, and a counter that could walk back would under-report how much of the learner's
     * wording came off the sheet. Written into the case snapshot so a process death mid-scene
     * cannot lose it.
     */
    fun revealPhrasebook(level: com.example.medvoicetrainer.analysis.PhrasebookLevel) {
        if (!_activeSession.value.isActive) return
        if (_phrasebookCard.value == null) return
        if (level.step <= _phrasebookLevel.value.step) return
        _phrasebookLevel.value = level
        recordLearningEvent("phrasebook_reveal", mapOf("level" to level.step, "name" to level.name))
        writePracticeAids { aids -> aids.put("phrasebook_revealed", level.step) }
    }

    /**
     * Keep one expression in the learner's own phrasebook, where "Say It: Everyday" will drill it.
     *
     * This is the return path that closes the loop: without it the feedback screen names a better
     * way to say something once and the learner never meets that sentence again.
     */
    fun addToMyPhrasebook(
        english: String,
        original: String = "",
        note: String = "",
        source: String = "",
    ) {
        val store = com.example.medvoicetrainer.analysis.MyPhrasebook
        val updated = store.add(
            _myPhrasebook.value,
            com.example.medvoicetrainer.analysis.MyPhrase(
                en = english.trim(),
                original = original.trim(),
                note = note.trim(),
                source = source.trim(),
                addedAt = System.currentTimeMillis(),
            ),
        )
        if (updated === _myPhrasebook.value) return
        _myPhrasebook.value = updated
        repository.setSetting(store.SETTING_KEY, store.write(updated))
        recordLearningEvent("phrasebook_kept", mapOf("source" to source))
    }

    /** Forget one kept expression. Its Say It progress is left alone — re-adding restores it. */
    fun removeFromMyPhrasebook(english: String) {
        val store = com.example.medvoicetrainer.analysis.MyPhrasebook
        val updated = store.remove(_myPhrasebook.value, english)
        if (updated.size == _myPhrasebook.value.size) return
        _myPhrasebook.value = updated
        repository.setSetting(store.SETTING_KEY, store.write(updated))
    }

    /** Resolve the pre-visit gate. The waiting session coroutine applies the aid before voice starts. */
    fun resolveOpenBookBriefing(briefed: Boolean) {
        val decision = openBookBriefingDecision ?: return
        if (decision.complete(briefed)) {
            _openBookBriefingPending.value = false
        }
    }

    /**
     * Record an aid in the case snapshot the analysis prompt is built from — Open Book's levels,
     * the everyday phrasebook's, and the pre-start card. Mirrors
     * [recordLearningEvent]'s write-through so a process death mid-encounter cannot lose the fact
     * that the session was scaffolded — which would let the evaluator grade an open-book run as if
     * it had been unaided.
     */
    private fun writePracticeAids(mutate: (JSONObject) -> Unit) {
        val current = _activeSession.value
        try {
            val root = JSONObject(current.caseJson.ifBlank { "{}" })
            val aids = root.optJSONObject("practice_aids") ?: JSONObject()
            mutate(aids)
            root.put("practice_aids", aids)
            val updatedJson = root.toString()
            _activeSession.value = current.copy(caseJson = updatedJson)
            activeSessionId?.let { id ->
                viewModelScope.launch { repository.updateSessionCaseSnapshot(id, updatedJson) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // The scaffold must never take the encounter down with it.
        }
    }

    fun setImportCardDismissed(dismissed: Boolean) {
        repository.setSetting("import_card_dismissed", if (dismissed) "true" else "false")
        _importCardDismissed.value = dismissed
    }

    /**
     * Insight groups on Home the learner has expanded at least once.
     *
     * Each collapsed group carries a one-line hint naming the question it answers. That hint is
     * onboarding: it exists so a learner facing four shut groups knows which one to open. Once
     * they have opened a group they know what is inside it, and the line becomes a permanent extra
     * row of text on every future visit — so it retires per group, the first time it is opened.
     */
    private val _openedInsightGroups = MutableStateFlow(
        repository.getSetting("opened_insight_groups", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    )
    val openedInsightGroups = _openedInsightGroups.asStateFlow()

    fun markInsightGroupOpened(groupKey: String) {
        if (groupKey in _openedInsightGroups.value) return
        val opened = _openedInsightGroups.value + groupKey
        repository.setSetting("opened_insight_groups", opened.sorted().joinToString(","))
        _openedInsightGroups.value = opened
    }

    /** Hides one unfinished-session recovery prompt without deleting its saved session. */
    fun dismissRecoverySessionCard(sessionId: Int) {
        val dismissed = _dismissedRecoverySessionIds.value + sessionId
        repository.setSetting("dismissed_recovery_session_ids", dismissed.sorted().joinToString(","))
        _dismissedRecoverySessionIds.value = dismissed
    }

    // --- Guided-cue fade (GuidedCueEngine gradual release) ---
    // Persisted per-skill count of how many times the learner has *produced* an objective while
    // guided. The count drives which fade stage the cue shows next time (full sentence → cloze →
    // L1 hint → nothing), so the scaffold recedes as the skill sticks instead of becoming a crutch.
    private fun guidedFadeKey(objective: String) =
        "guided_fade::" + com.example.medvoicetrainer.analysis.GuidedCueEngine.skillKeyFor(objective)

    fun guidedSuccessCount(objective: String): Int =
        repository.getSetting(guidedFadeKey(objective), "0").toIntOrNull() ?: 0

    private fun incrementGuidedSuccess(objective: String) {
        val next = (guidedSuccessCount(objective) + 1).coerceAtMost(99)
        repository.setSetting(guidedFadeKey(objective), next.toString())
    }

    fun setVoiceBackend(isMock: Boolean) {
        updateVoiceBackend(if (isMock) "mock" else "gemini")
    }

    fun getSavedCustomProfiles(): List<CustomPracticeProfile> = try {
        Json.decodeFromString(repository.getSetting("custom_practice_profiles", "[]"))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        emptyList()
    }

    fun saveCustomProfile(profile: CustomPracticeProfile) {
        val existing = getSavedCustomProfiles().filterNot {
            it.name.equals(profile.name, ignoreCase = true)
        }
        repository.setSetting(
            "custom_practice_profiles",
            Json.encodeToString((existing + profile).takeLast(30))
        )
    }

    /** Live voice models are not interchangeable with post-session text-analysis models. */
    fun getVoiceModelForBackend(backend: String): String {
        return when (backend.trim().lowercase(Locale.ROOT)) {
            "openai" -> repository.getSetting("openai_voice_model", "gpt-realtime-2.1")
            "gemini" -> repository.getSetting(
                "gemini_voice_model",
                "gemini-3.1-flash-live-preview"
            )
            else -> "offline-demo"
        }
    }

    fun updateVoiceModel(backend: String, model: String) {
        val clean = model.trim()
        if (clean.isEmpty()) return
        when (backend.trim().lowercase(Locale.ROOT)) {
            "openai" -> {
                repository.setSetting("openai_voice_model", clean)
                _openAiVoiceModel.value = clean
            }
            "gemini" -> {
                repository.setSetting("gemini_voice_model", clean)
                _geminiVoiceModel.value = clean
            }
        }
    }

    // --- Asset Helpers ---
    fun listAssets(path: String): List<String> = repository.listAssetFiles(path)
    fun loadAsset(path: String): String = repository.loadAssetFile(path)

    // Mirrors app/ui/coach.py's seen()/mark_seen() one-time-primer gating.
    fun isCoachPrimerSeen(key: String): Boolean = repository.getSetting(key, "false") == "true"
    fun markCoachPrimerSeen(key: String) { repository.setSetting(key, "true") }

    // §A1 (early-UX): the opt-in next-day practice reminder's on/off state. Persisted so the
    // Preferences toggle can reflect and cancel it; the actual AlarmManager schedule/cancel calls
    // live in the UI layer (PracticeReminder), which is where a Context + localized copy exist.
    fun isPracticeReminderEnabled(): Boolean = repository.getSetting("practice_reminder_enabled", "false") == "true"
    fun setPracticeReminderEnabled(enabled: Boolean) {
        repository.setSetting("practice_reminder_enabled", if (enabled) "true" else "false")
    }

    /**
     * Ported from app/ui/encounter_tab.py's _start_review (the "🎯 My Mistakes" button):
     * builds a personalized review session from the user's own SRS history via ReviewBuilder
     * (was dead code — logic existed, nothing called it). Priority: due items, then any active
     * item, then a cold-start aggregate over recent session corrections. Returns null when
     * there isn't yet enough data (< ReviewBuilder's REVIEW_MIN_TARGETS), matching Python's
     * "not enough data yet" dialog instead of starting a near-empty review.
     */
    suspend fun buildReviewSessionCase(): Triple<String, String, String>? {
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val dueItems = try {
            repository.getDueErrorItems(nowIso)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val activeItems = try {
            repository.getAllActiveErrorItems()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val recentSessions = repository.getAllSessionsList().toAnalysisMaps()
        val targets = com.example.medvoicetrainer.analysis.ReviewBuilder.prepareTargets(dueItems, activeItems, recentSessions)
        if (targets.size < com.example.medvoicetrainer.analysis.REVIEW_MIN_TARGETS) return null
        val case = com.example.medvoicetrainer.analysis.ReviewBuilder.buildReviewCase(targets)
        val id = case.optString("id", "review_corrections")
        val title = case.optString("patient_name", "My Mistakes Review")
        return Triple(id, title, case.toString())
    }

    /** Ported from app/analysis/demo_tour.py's get_completed_demo_cases(), for DemoIntroDialog's "✓ Played" marks. */
    fun getCompletedDemoCaseIds(): List<String> =
        com.example.medvoicetrainer.analysis.DemoTour.getCompletedDemoCases {
            repository.getSetting("demo_cases_completed", "")
        }

    /** Ported from app/analysis/demo_tour.py's select_real_case_for_demo: resolves a scripted
     * demo case id to its backing real case JSON (DemoTour.DEMO_CASE_PATIENT_FILE) and starts an
     * ordinary "encounter" session with it, so the rest of the app treats it identically to any
     * other patient encounter (mark_demo_case_completed runs in finishSession()). */
    fun startDemoSession(demoCaseId: String) {
        val (system, realCaseId) = com.example.medvoicetrainer.analysis.DemoTour.DEMO_CASE_PATIENT_FILE[demoCaseId] ?: return
        val jsonStr = try {
            repository.loadAssetFile("cases/$system/$realCaseId.json")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            "{}"
        }
        val title = try {
            val json = JSONObject(jsonStr)
            "${json.optString("patient_name", realCaseId)} - ${json.optString("chief_complaint", "Encounter")}"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            realCaseId
        }
        startSession("encounter", realCaseId, title, jsonStr)
    }

    /**
     * Starts a real Survival-English scenario through the existing local demo backend. Unlike the
     * patient tour this needs no special hardcoded case: SurvivalComposer supplies the complete
     * opener/follow-up contract and MockVoiceClient derives its scripted partner turns from it.
     *
     * Stamped as "Survival English — Advanced Beta" (see PracticeModeSelection.kt's
     * applyAdvancedBetaOptions / docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md) so the very first thing
     * an everyday-English onboarding user sees demonstrates scene transitions (move / time-skip /
     * new-character / solo errand) rather than a flat back-and-forth — the offline MockVoiceClient
     * walks the scripted proposal loop with no API key or live microphone needed, exactly as it
     * does for the beta tab.
     */
    fun startEverydayDemoSession() {
        val jsonStr = try {
            com.example.medvoicetrainer.analysis.SurvivalComposer.generateRandomScenario(
                getApplication(),
                excludedCategories = setOf("Hospital Hallway"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            handleUiException("preparing the everyday English demo", e)
            return
        }
        val json = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }
        // Same guard as applyAdvancedBetaOptions: a rapid-fire sprint has no scene to move between.
        if (json.optString("scenario_type") != "rapid_fire") {
            json.put("advanced_beta", true)
            json.put(
                "scene_transition_menu",
                com.example.medvoicetrainer.analysis.SceneTransitionCatalog
                    .load(getApplication())
                    .promptBlockFor(json.optString("category")),
            )
        }
        val caseId = json.optString("id", "survival_demo").ifBlank { "survival_demo" }
        val title = json.optString("title", "Everyday English demo").ifBlank { "Everyday English demo" }
        startSession("survival", caseId, title, json.toString())
    }

    // --- Session Setup & Control ---
    // The single highest-traffic "tap something, expect a session to start" entry point in the
    // app — exactly the "tap a case, nothing visibly happens" scenario app/ui/error_dialog.py's
    // docstring describes. Wrapped so a failure anywhere in setup shows the friendly recoverable
    // dialog (ErrorDialog.kt) instead of crashing the whole app or silently no-opping.
    fun startSession(
        mode: String,
        caseId: String,
        caseName: String,
        initialCaseJson: String,
        seedTranscript: List<Pair<String, String>> = emptyList(),
        seedAudioClips: Map<Int, LearnerAudioClip> = emptyMap(),
    ) {
        try {
            startSessionInternal(mode, caseId, caseName, initialCaseJson, seedTranscript, seedAudioClips)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            handleUiException("starting the session", e)
        }
    }

    private fun startSessionInternal(
        mode: String,
        caseId: String,
        caseName: String,
        initialCaseJson: String,
        seedTranscript: List<Pair<String, String>> = emptyList(),
        seedAudioClips: Map<Int, LearnerAudioClip> = emptyMap(),
    ) {
        if (_backupState.value.blocksAppInteraction) {
            throw IllegalStateException("Wait for the backup restore to finish and restart the app")
        }
        // Release a superseded session start that may still be waiting at its pre-visit gate. Its
        // generation check below prevents it from starting after the replacement takes ownership.
        openBookBriefingDecision?.complete(false)
        openBookBriefingDecision = null
        _openBookBriefingPending.value = false

        val currentISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        
        var caseJson = initialCaseJson
        if (mode == "survival" && (caseJson.isEmpty() || caseJson == "{}")) {
            caseJson = com.example.medvoicetrainer.analysis.SurvivalComposer.generateRandomScenario(getApplication())
        }

        // Parse learning objectives (checklist) from case JSON
        val objectives = mutableListOf<String>()
        try {
            val json = JSONObject(caseJson)
            if (json.has("learning_objectives")) {
                val array = json.getJSONArray("learning_objectives")
                for (i in 0 until array.length()) {
                    objectives.add(array.getString(i))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // fallback checklist
            objectives.add("Establish rapport and elicit CC")
            objectives.add("Screen for red flags")
            objectives.add("Address ICE (Ideas, Concerns, Expectations)")
        }
        // Compile each objective into a deterministic match rule once per session, rather than
        // asking a model the same question after every turn. See CoverageEngine.
        checklistRules = com.example.medvoicetrainer.analysis.CoverageEngine.rulesFor(caseJson, objectives)
        checklistManualOverrides.clear()

        // Build the shared case-note/Open Book card up front (pure JSON parsing) and compile its
        // "must ask" list into the same coverage rules the live checklist uses. The diagnosis is
        // shown only after the learner explicitly chooses English speaking practice or opens help.
        // Build the case-note card independently of the mid-visit Open Book preference. The card
        // also powers the pre-visit English-vs-medical focus choice; turning off rescue help during
        // a visit must not remove that separate choice.
        val openBook = if (com.example.medvoicetrainer.analysis.OpenBookEngine.isSupportedMode(mode)) {
            com.example.medvoicetrainer.analysis.OpenBookEngine.cardFor(caseJson)
        } else {
            null
        }
        _openBookCard.value = openBook
        // A resumed session (§13 process death) reloads its own case snapshot, which already
        // records how far the sheet was opened. Restoring it keeps the aid honestly reported and
        // stops a crash from handing back the levels the learner had already spent.
        _openBookLevel.value = com.example.medvoicetrainer.analysis.OpenBookLevel.of(
            if (openBook != null) openBookLevelIn(caseJson) else 0
        )
        _openBookClosingLevel.value = com.example.medvoicetrainer.analysis.ClosingLevel.of(
            if (openBook != null) openBookClosingLevelIn(caseJson) else 0
        )
        _openBookBriefed.value = openBook != null && runCatching {
            JSONObject(caseJson.ifBlank { "{}" })
                .optJSONObject("practice_aids")
                ?.optBoolean("briefed", false) == true
        }.getOrDefault(false)
        openBookRules = openBook
            ?.let { com.example.medvoicetrainer.analysis.OpenBookEngine.rulesFor(it) }
            .orEmpty()
        _openBookPreviousLevel.value = openBook
            ?.let { runCatching { JSONObject(caseJson).optString("system", "") }.getOrDefault("") }
            ?.takeIf { it.isNotBlank() }
            ?.let { repository.getSetting(openBookLevelKey(it), "0").toIntOrNull() }
            ?.takeIf { it > 0 }

        // The everyday half of the same idea: model expressions for a conversation that hides no
        // diagnosis, built from the scene's own `key_expressions` plus the shared function bank.
        // Mutually exclusive with Open Book by mode, so a session never carries two answer sheets.
        val phrasebook = if (
            _phrasebookEnabled.value &&
            com.example.medvoicetrainer.analysis.EverydayPhrasebook.isSupportedMode(mode)
        ) {
            com.example.medvoicetrainer.analysis.EverydayPhrasebook.cardFor(
                caseJson = caseJson,
                bank = everydayPhraseBank(),
                language = normalizeLanguageCode(_nativeLanguage.value),
                title = caseName,
            )
        } else {
            null
        }
        _phrasebookCard.value = phrasebook
        _phrasebookLevel.value = com.example.medvoicetrainer.analysis.PhrasebookLevel.of(
            if (phrasebook != null) practiceAidInt(caseJson, "phrasebook_revealed") else 0
        )
        phrasebookRules = phrasebook
            ?.let { com.example.medvoicetrainer.analysis.EverydayPhrasebook.rulesFor(it) }
            .orEmpty()
        // The concrete, language-resolved offer is session data, not mutable catalogue data.
        // Persisting it inside the case snapshot lets recovery, immediate feedback and History all
        // reconstruct the exact same lines even if the asset bank changes in a later app release.
        caseJson = com.example.medvoicetrainer.analysis.EverydayPhrasebook
            .withSessionSnapshot(caseJson, phrasebook)

        // AI-first kickoff (app/ui/session_base.py ~L1151-1162): interview mode always has the
        // interviewer speak first; any case may also declare its own kickoff_text (exam vivas,
        // attending-on-rounds, teachback, and the composed survival case all do). When a kickoff is
        // used the model generates a real opening turn, so we must NOT also inject the fake canned
        // greeting below (that would double up / pre-empt the model's real first line).
        // A seeded transcript means the conversation already has an opener behind it, so no
        // AI-first kickoff should fire — that would re-greet on top of an existing exchange
        // instead of continuing it. (No caller seeds today: the Home "Resume" action that used
        // this was removed. The seeding path is kept because it is the correct behavior for any
        // future continue-a-conversation entry point, and it is exercised nowhere else.)
        val kickoffText = if (seedTranscript.isNotEmpty()) {
            ""
        } else if (mode == "interview") {
            "(The candidate has just entered the room and sat down. " +
                "Greet them briefly and ask your opening question.)"
        } else {
            try {
                JSONObject(caseJson).optString("kickoff_text", "")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ""
            }
        }

        // Deterministic (no-API-call) interview phase tracker — encounter mode only, mirrors
        // app/analysis/phase_tracker.py's InterviewPhaseTracker, gated the same way as
        // coverage.supports_history_coverage (standard patient encounters, not free-form drills).
        interviewPhaseTracker = if (mode == "encounter") {
            try {
                val json = JSONObject(caseJson)
                val caseMap: Map<String, Any?> = mapOf("chief_complaint" to json.optString("chief_complaint", ""))
                // Explicit: InterviewPhaseTracker now has a domains-first constructor
                // too (ledger LOG-41), so getApplication()'s type parameter no longer
                // has a single expected type to infer from.
                InterviewPhaseTracker(getApplication<android.app.Application>(), caseMap)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        // A seeded session already has turns behind it. Replaying them through the (deterministic,
        // local) tracker rebuilds coverage exactly as the live pass would have, so a continued
        // encounter doesn't forget everything the learner asked and start suggesting the opening
        // question again. No-op while nothing seeds — see the kickoff note above.
        interviewPhaseTracker?.let { tracker ->
            seedTranscript.forEachIndexed { index, (role, text) ->
                if (role == "doctor") tracker.updateFromTurn(text, index) else tracker.updateFromPatientTurn(text, index)
            }
        }
        // Soft-closure signal is encounter-only (it reads the phase tracker). Reset every counter
        // at session start so a previous encounter can't leak a stale "ready to wrap up" state.
        wrapUpDetector = if (mode == "encounter") WrapUpDetector() else null
        sessionStartMillis = System.currentTimeMillis()
        doctorTurnCount = 0
        turnsSinceNewCoverage = 0

        // From this point onward the new session owns all voice callbacks. stop() is deliberately
        // non-blocking, so the generation check is what prevents the previous socket/AudioRecord
        // from writing a late status or transcript into this new case.
        val sessionGeneration = voiceSessionGeneration.incrementAndGet()
        synchronized(voiceUsageLock) {
            activeVoiceUsageGeneration = sessionGeneration
            activeVoiceUsage = null
        }
        val saveLearnerClipsForSession = _learnerAudioSavingEnabled.value
        val analyzePronunciationForSession = _pronunciationAnalysisEnabled.value
        val voiceBackendVal = _voiceBackend.value
        val voiceApiKey = getApiKeyForBackend(voiceBackendVal)
        val voiceModelId = getVoiceModelForBackend(voiceBackendVal)
        activeSupportsPreReadyTextQueue = normalizeVoiceBackend(voiceBackendVal) == "gemini" &&
            voiceApiKey.isNotBlank() && !_isVoiceModeMock.value

        // The dialog is part of session setup, not an overlay on an already-listening session.
        // Demo/mock never opens a real microphone, so delaying those paths provides no protection.
        val briefingDecision = if (com.example.medvoicetrainer.analysis.OpenBookEngine.shouldGateBriefing(
                cardAvailable = openBook != null,
                alreadyBriefed = _openBookBriefed.value,
                hasSeedTranscript = seedTranscript.isNotEmpty(),
                usesLiveMicrophone = normalizeVoiceBackend(voiceBackendVal) !in setOf("demo", "mock"),
            )
        ) {
            CompletableDeferred<Boolean>().also {
                openBookBriefingDecision = it
                _openBookBriefingPending.value = true
            }
        } else {
            null
        }

        // Survival "Advanced Beta": the beta prompt tells the model to call
        // `propose_scene_transition`, which only a backend that actually declares that tool can
        // honour. On one that cannot (OpenAI Realtime), leaving the rules in would have the model
        // asking "shall we go over there?" out loud — the feature's explicit anti-goal. Dropping
        // the menu is the whole fix: PromptBuilder omits the rules block when there is no menu, at
        // which point the beta template renders byte-for-byte as the shipped Survival one. The
        // `advanced_beta` flag itself stays, so the saved session still records where it came from.
        val advancedBetaSession = mode == "survival" && runCatching {
            JSONObject(caseJson).optBoolean("advanced_beta", false)
        }.getOrDefault(false)
        val sceneTransitionsSupported = com.example.medvoicetrainer.voice.supportsSceneTransitions(
            provider = voiceBackendVal,
            isMock = _isVoiceModeMock.value,
            apiKey = voiceApiKey,
        )
        if (advancedBetaSession && !sceneTransitionsSupported) {
            caseJson = runCatching {
                JSONObject(caseJson).apply { remove("scene_transition_menu") }.toString()
            }.getOrDefault(caseJson)
        }
        val sceneTransitionsActive = advancedBetaSession && sceneTransitionsSupported

        val openingHint = interviewPhaseTracker?.getRescueHintParts()
        val initialChecklist = evaluateChecklist(seedTranscript)
        val initialPhrasebookUsage = evaluatePhrasebookUsage(seedTranscript)
        caseJson = com.example.medvoicetrainer.analysis.EverydayPhrasebook.withSessionUsage(
            caseJson,
            initialPhrasebookUsage.filter { it.isMet }.map { it.objective },
        )
        val initialFollowUpFlow = if (mode == "follow_up") {
            com.example.medvoicetrainer.analysis.FollowUpFlow.derive(
                caseJson = caseJson,
                coverage = initialChecklist.associate { it.objective to it.source },
                learnerTurnCount = seedTranscript.count { it.first == "doctor" && it.second.isNotBlank() },
            )
        } else null
        val initialAvailableResults = com.example.medvoicetrainer.analysis.InvestigationResults
            .parseAvailableResults(caseJson)
        _activeSession.value = ActiveSessionState(
            isActive = true,
            mode = mode,
            caseId = caseId,
            caseName = caseName,
            caseJson = caseJson,
            createdAt = currentISO,
            transcript = seedTranscript, // Empty for a fresh case, or a seeded conversation's turns
            checklist = initialChecklist,
            phrasebookUsage = initialPhrasebookUsage,
            phaseHint = initialFollowUpFlow?.primary?.question ?: openingHint?.question.orEmpty(),
            phaseHintCategory = initialFollowUpFlow?.primary?.category ?: openingHint?.category.orEmpty(),
            phaseHintOptions = initialFollowUpFlow?.options
                ?: interviewPhaseTracker?.getRescueHintOptions().orEmpty(),
            wrapUpSuggested = initialFollowUpFlow?.wrapUpSuggested == true,
            availableResults = initialAvailableResults,
        )
        _micLevel.value = 0f
        _lastEvaluation.value = null
        _correctionDecisions.value = emptyMap()
        _correctionPredictions.value = emptyMap()
        _lastCompletedTranscript.value = emptyList()
        _lastCompletedAudioClips.value = emptyMap()
        _lastCompletedPresentationLaunch.value = null
        _micMuted.value = false
        // A composed case may carry its own pace (the Survival "Playback pace" slider); every other
        // session starts at the learner's saved default patient speed.
        val startPlaybackSpeed = runCatching {
            JSONObject(caseJson.ifBlank { "{}" }).optDouble("playback_speed", Double.NaN)
        }.getOrDefault(Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: _defaultPlaybackSpeed.value
        _playbackSpeed.value = startPlaybackSpeed
        _playbackSpeedExperimental.value = startPlaybackSpeed > 1.5f

        // Start VoiceManager
        voiceManager?.stop()
        // A previous draft is still open in memory and the learner has started a new session on
        // top of it — they moved on, so it is abandoned rather than interrupted, and Home should
        // not offer to recover it. (This only fires when the old id survived in memory. After a
        // real process death activeSessionId is null, so the crashed row keeps its in_progress
        // marker and stays recoverable, which is exactly the case that matters.)
        activeSessionId?.let { staleId ->
            viewModelScope.launch {
                repository.finalizeEmptySession(staleId, 0)
                repository.updateSessionEndReason(staleId, SessionEndReason.DISCARDED)
            }
        }
        activeSessionId = null
        
        // Roll the case's doorknob-disclosure probability once per session so the "one last worry"
        // fires on some encounters and not others (a real doorknob moment isn't guaranteed). The
        // roll is baked into the immutable system prompt, so a mid-session reconnect resends the
        // same decision instead of re-rolling. Absent field / non-encounter modes → never active.
        val doorknobActive = run {
            val obj = runCatching { JSONObject(caseJson) }.getOrNull()
            val text = obj?.optString("doorknob_disclosure", "").orEmpty().trim()
            if (mode != "encounter" || text.isBlank()) false
            else Math.random() < (obj?.optDouble("doorknob_probability", 0.7) ?: 0.7)
        }
        val systemPrompt = PromptBuilder.buildSystemPrompt(mode, caseJson, doorknobActive)

        // Survival "Advanced Beta": the composed case carries the flag, so nothing else in the app
        // (History filters, eval template resolution, the "survival" mode string) has to change.
        // Resolved above, where the voice backend is known.
        clearSceneTransitionState()

        val sessionPhaseTracker = interviewPhaseTracker

        val manager = VoiceManager(
            apiKey = voiceApiKey,
            isMock = _isVoiceModeMock.value,
            modelId = voiceModelId,
            systemPrompt = systemPrompt,
            provider = voiceBackendVal,
            mode = mode,
            caseId = caseId,
            caseJson = caseJson,
            kickoffText = kickoffText,
            onTranscriptCallback = transcriptCallback@{ role, text, isFinal, learnerPcm ->
                // Realtime providers emit deltas as well as a final turn. Only final turns belong
                // in the durable transcript; otherwise OpenAI/Gemini fragments are duplicated.
                if (!isFinal || text.isBlank() ||
                    voiceSessionGeneration.get() != sessionGeneration
                ) return@transcriptCallback
                val mappedRole = when (role) {
                    "user", "doctor" -> "doctor"
                    "model", "patient" -> "patient"
                    else -> role
                }

                // The provider's safety layer sometimes bolts a "this is not medical advice,
                // consult a healthcare professional" paragraph onto the roleplay character's
                // line. A patient never says that, and it would be graded as if the learner's
                // conversation partner had said it, so it is stripped deterministically here —
                // on the AI's turns only. See SafetyDisclaimerFilter for why this is rule-based
                // rather than left to the persona prompt. A turn that was nothing but boilerplate
                // is dropped rather than recorded as an empty bubble.
                val normalizedText = if (mappedRole == "patient") {
                    com.example.medvoicetrainer.analysis.SafetyDisclaimerFilter.strip(text).trim()
                } else {
                    text.trim()
                }
                if (normalizedText.isBlank()) return@transcriptCallback
                val tracker = sessionPhaseTracker
                var transcriptToPersist: List<Pair<String, String>>? = null
                _activeSession.update { state ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !state.isActive) {
                        state
                    } else if (state.transcript.lastOrNull()?.let {
                            it.first == mappedRole && it.second.trim() == normalizedText
                        } == true
                    ) {
                        state
                    } else {
                        val newTranscript = state.transcript + (mappedRole to normalizedText)
                        transcriptToPersist = newTranscript
                        state.copy(
                            transcript = newTranscript,
                            // Transcript delivery is deliberately independent of the live audio
                            // state. onAiTurnStarted/onTurnComplete own this flag; tying it to a
                            // late patient transcript blocks the learner's next spoken turn.
                            isAILoading = state.isAILoading,
                        )
                    }
                }
                transcriptToPersist?.let { newTranscript ->
                    val transcriptIndex = newTranscript.lastIndex
                    if (mappedRole == "doctor") {
                        val newDomains = tracker?.updateFromTurn(normalizedText, transcriptIndex) ?: emptySet()
                        maybeSignalWrapUp(tracker, newDomains, sessionGeneration)
                    } else if (mappedRole == "patient") {
                        tracker?.updateFromPatientTurn(normalizedText, transcriptIndex)
                    }
                    refreshPhaseHints(sessionGeneration)
                    persistTranscriptSnapshot(newTranscript, sessionGeneration)
                    if (mappedRole == "doctor" && learnerPcm?.isNotEmpty() == true) {
                        scheduleLearnerAudioCapture(
                            sessionGeneration = sessionGeneration,
                            transcriptIndex = transcriptIndex,
                            transcript = newTranscript,
                            pcm = learnerPcm,
                        )
                    }
                    // Patient turns matter here too, not just the learner's: an answer can resolve a
                    // row on its own (shown as "the patient told you this"), which is the
                    // deterministic safety net for a learner question the recogniser garbled.
                    updateChecklistCoverage(sessionGeneration)
                    if (mappedRole == "doctor") {
                        detectInvestigationOrders(normalizedText, sessionGeneration)
                        detectExamManeuvers(normalizedText, sessionGeneration)
                    }
                }
            },
            onStatusCallback = statusCallback@{ status ->
                if (voiceSessionGeneration.get() != sessionGeneration) return@statusCallback
                val reportedError = status.removePrefix("Error: ").takeIf {
                    status.startsWith("Error:", ignoreCase = true)
                }
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) current
                    else current.copy(
                        status = status,
                        error = reportedError ?: current.error,
                        isAILoading = if (reportedError != null) false else current.isAILoading,
                    )
                }
            },
            onConnectionStateCallback = connectionCallback@{ connectionState ->
                if (voiceSessionGeneration.get() != sessionGeneration) return@connectionCallback
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) current
                    else {
                        val now = System.currentTimeMillis()
                        val enteringPause = connectionState == VoiceConnectionState.USER_PAUSED &&
                            current.voiceConnectionState != VoiceConnectionState.USER_PAUSED
                        val leavingPause = current.voiceConnectionState == VoiceConnectionState.USER_PAUSED &&
                            connectionState != VoiceConnectionState.USER_PAUSED
                        val pausedAt = when {
                            enteringPause -> now
                            leavingPause -> null
                            else -> current.pausedAtMillis
                        }
                        val pausedTotal = if (leavingPause && current.pausedAtMillis != null) {
                            current.totalPausedMillis + (now - current.pausedAtMillis).coerceAtLeast(0L)
                        } else current.totalPausedMillis
                        current.copy(
                            voiceConnectionState = connectionState,
                            pausedAtMillis = pausedAt,
                            totalPausedMillis = pausedTotal,
                            // Keep the short resume handshake locked, but let a learner pause an
                            // automatic reconnect rather than forcing them to wait through retries.
                            isSessionControlBusy = connectionState == VoiceConnectionState.CONNECTING &&
                                current.isSessionControlBusy,
                            // A real provider-ready callback is authoritative recovery evidence.
                            error = if (connectionState == VoiceConnectionState.READY) null else current.error,
                            isAILoading = if (connectionState in setOf(
                                    VoiceConnectionState.USER_PAUSED,
                                    VoiceConnectionState.RECONNECTING,
                                    VoiceConnectionState.FAILED,
                                    VoiceConnectionState.CLOSED,
                                )
                            ) false else current.isAILoading,
                        )
                    }
                }
            },
            onMicrophoneStateCallback = microphoneCallback@{ microphoneState ->
                if (voiceSessionGeneration.get() != sessionGeneration) return@microphoneCallback
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) current
                    else current.copy(microphoneState = microphoneState)
                }
            },
            onMicLevelCallback = { level ->
                // Lightweight, high-frequency signal — write straight to its own StateFlow (no
                // ActiveSessionState churn). Generation-guarded so a torn-down manager's reader
                // thread can't keep driving the meter for the next session.
                if (voiceSessionGeneration.get() == sessionGeneration) _micLevel.value = level
            },
            onAiTurnStartedCallback = aiTurnStartedCallback@{
                if (voiceSessionGeneration.get() != sessionGeneration) return@aiTurnStartedCallback
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) current
                    else current.copy(isAILoading = true)
                }
            },
            onTurnCompleteCallback = turnCompleteCallback@{
                if (voiceSessionGeneration.get() != sessionGeneration) return@turnCompleteCallback
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) current
                    else current.copy(isAILoading = false)
                }
            },
            onUsageCallback = usageCallback@{ usage ->
                if (voiceSessionGeneration.get() != sessionGeneration) return@usageCallback
                synchronized(voiceUsageLock) {
                    if (activeVoiceUsageGeneration != sessionGeneration) return@synchronized
                    activeVoiceUsage = activeVoiceUsage?.plus(usage) ?: usage
                }
            },
            captureLearnerTurns = saveLearnerClipsForSession || analyzePronunciationForSession,
            sceneTransitionsEnabled = sceneTransitionsActive,
            // Both of these arrive on the transport's reader thread, and both go on to touch the
            // chip's timeout Job — which the learner's own accept/dismiss taps also cancel and
            // reassign from the main thread. A StateFlow would survive that; a plain Job field
            // would not, so the whole handler is hopped onto the main dispatcher first and the
            // scene-transition state stays single-threaded. The generation check moves with it: it
            // has to be read where the state it guards is read, not one thread earlier.
            onSceneTransitionProposedCallback = { proposal ->
                viewModelScope.launch {
                    if (voiceSessionGeneration.get() != sessionGeneration) return@launch
                    showSceneTransition(proposal, sessionGeneration)
                }
            },
            onSceneTransitionCancelledCallback = {
                // Gemini can withdraw an async call when its turn is interrupted, often at the
                // same boundary where the final transcript appears. The card is already a
                // learner-facing, side-effect-free suggestion, so keep its timer and buttons.
                // VoiceManager has discarded the dead provider call and will apply a later tap as
                // an explicit local learner action instead of trying to answer that call.
            },
            onSceneTransitionAppliedCallback = {},
            // Proposals the manager answered by itself — rate-limited, or describing a scene the
            // session is not in. They never reach the chip, so this is the only place they can be
            // counted, and they are exactly the numbers the gate's constants should be tuned on.
            onSceneTransitionRejectedCallback = { proposal, outcome ->
                viewModelScope.launch {
                    if (voiceSessionGeneration.get() != sessionGeneration) return@launch
                    recordSceneTransitionOutcome(proposal, outcome)
                }
            },
            onSceneTransitionModeCallback = { nonBlocking ->
                setSceneTransitionsNonBlocking(nonBlocking)
            },
            // Non-null (possibly a blank role the model never named) while the learner is with a
            // stand-in; null once they are back with the original counterpart.
            onSceneCharacterChangedCallback = characterCallback@{ role ->
                if (voiceSessionGeneration.get() != sessionGeneration) return@characterCallback
                _sceneCharacter.value = role
            },
            brevityGuard = com.example.medvoicetrainer.analysis.FreeTalk.brevityGuardFor(jsonObjectToMap(caseJson)),
        )
        voiceManager = manager
        viewModelScope.launch {
            try {
                val caseData = jsonObjectToMap(caseJson)
                val domain = ScoreDomains.inferAnalysisDomain(caseData, null, mode, null)
                val evalTemplate = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder
                    .resolveEvalTemplateName(caseData, domain == "everyday")
                val seedTranscriptJson = serializeTranscript(seedTranscript, seedAudioClips)
                val draft = SessionEntity(
                    createdAt = currentISO,
                    mode = mode,
                    analysisDomain = domain,
                    caseName = caseName,
                    caseId = caseId,
                    evalTemplate = evalTemplate,
                    voiceBackend = voiceBackendVal,
                    voiceModel = voiceModelId,
                    analysisModel = getModelForBackend(_analysisBackend.value),
                    rawTranscript = seedTranscriptJson,
                    rawCaseJson = caseJson,
                    // Load-bearing default, spelled out because it is what a crash leaves behind:
                    // a row still reading in_progress is the only evidence that the process died
                    // with this conversation open. Every orderly end overwrites it.
                    endReason = SessionEndReason.IN_PROGRESS,
                )
                val draftId = repository.insertSession(draft).toInt()
                if (voiceSessionGeneration.get() != sessionGeneration || voiceManager !== manager) {
                    repository.finalizeEmptySession(draftId, 0)
                    // Setup lost a race with a replacement session; this draft never became a
                    // conversation, so it is not something to offer recovering later.
                    repository.updateSessionEndReason(draftId, SessionEndReason.DISCARDED)
                    manager.stop()
                    return@launch
                }
                activeSessionId = draftId
                activeSessionAudioCapture = SessionAudioCapture(
                    generation = sessionGeneration,
                    sessionId = draftId,
                    initialTranscript = seedTranscript,
                    saveLearnerClips = saveLearnerClipsForSession,
                    analyzePronunciation = analyzePronunciationForSession,
                ).also { it.clips.putAll(seedAudioClips) }
                // A very fast typed turn can arrive while the draft row is still being inserted.
                // Persist the current snapshot now as well so that accepted queued input is not
                // merely visible in memory if setup later fails.
                persistTranscriptSnapshot(_activeSession.value.transcript, sessionGeneration)

                // Do not connect or initialize AudioRecord until the learner has finished reading
                // the pre-visit card. The old UI-only gate ran after manager.start(), so speech from
                // the card could become the first doctor turn and dismiss the card mid-read.
                val startBriefed = briefingDecision?.await() == true
                if (voiceSessionGeneration.get() != sessionGeneration || voiceManager !== manager) {
                    manager.stop()
                    return@launch
                }
                if (startBriefed) applyOpenBookBriefing()
                if (openBookBriefingDecision === briefingDecision) {
                    openBookBriefingDecision = null
                    _openBookBriefingPending.value = false
                }
                manager.start()
                if (voiceSessionGeneration.get() != sessionGeneration || voiceManager !== manager) {
                    manager.stop()
                    return@launch
                }
                // Runs a foreground service for as long as the mic is genuinely live, so the
                // conversation survives the screen locking or the learner switching apps instead
                // of Android silently cutting background microphone access (see
                // VoiceSessionService's doc comment). Typed demo/mock never reaches here with a
                // real mic, so no service/notification is shown for those sessions.
                if (manager.usesLiveMicrophone) {
                    VoiceSessionService.start(getApplication(), caseName)
                }
                if (startPlaybackSpeed != 1.0f) manager.setSpeed(startPlaybackSpeed)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (openBookBriefingDecision === briefingDecision) {
                    openBookBriefingDecision = null
                    _openBookBriefingPending.value = false
                }
                if (voiceSessionGeneration.compareAndSet(sessionGeneration, sessionGeneration + 1L)) {
                    activeSupportsPreReadyTextQueue = false
                    manager.stop()
                    if (voiceManager === manager) voiceManager = null
                    VoiceSessionService.stop(getApplication())
                    _activeSession.update { current ->
                        current.copy(
                            status = "Connection failed",
                            error = "Could not start this session: ${com.example.medvoicetrainer.api.ApiError.userMessage(e)}",
                            voiceConnectionState = VoiceConnectionState.FAILED,
                        )
                    }
                    handleUiException("starting the voice session", e)
                }
            }
        }
    }

    // ── Survival "Advanced Beta": scene transitions ────────────────────────────────────────────

    /**
     * Show a proposal chip and arm its own auto-decline. A Live model that made a function call
     * stops speaking until it gets a response, so an ignored chip must not be allowed to sit there
     * forever — [SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS] later it declines itself and the
     * conversation carries on as if nothing had been suggested.
     */
    private fun showSceneTransition(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
        sessionGeneration: Long,
    ) {
        // Only one chip at a time: an older unanswered proposal is closed out, not silently
        // dropped. It expired rather than being refused — the learner never got to answer it.
        _sceneTransition.value?.let { previous ->
            if (previous.id != proposal.id) {
                declineSceneTransitionInternal(
                    previous,
                    com.example.medvoicetrainer.voice.SceneTransitionOutcome.EXPIRED_UNANSWERED,
                )
            }
        }
        sceneTransitionTimeoutJob?.cancel()
        _sceneTransition.value = proposal
        sceneTransitionTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(_sceneTransitionTimeoutMillis.value)
            if (voiceSessionGeneration.get() != sessionGeneration) return@launch
            if (_sceneTransition.value?.id != proposal.id) return@launch
            _sceneTransition.value = null
            declineSceneTransitionInternal(
                proposal,
                com.example.medvoicetrainer.voice.SceneTransitionOutcome.EXPIRED_UNANSWERED,
            )
        }
    }

    /**
     * Record which mode the session's tool declaration actually came up in, and with it how long a
     * chip may sit unanswered. On a session that had to fall back to blocking mode that window is
     * not idle time: the model stopped speaking the moment it proposed and cannot start again until
     * the response goes back, so the full half-minute would be the character apparently having
     * walked off.
     */
    private fun setSceneTransitionsNonBlocking(nonBlocking: Boolean) {
        sceneTransitionsNonBlocking = nonBlocking
        _sceneTransitionTimeoutMillis.value = if (nonBlocking) {
            com.example.medvoicetrainer.voice.SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS
        } else {
            com.example.medvoicetrainer.voice.SceneTransitionGate.PROPOSAL_TIMEOUT_BLOCKING_MILLIS
        }
    }

    /**
     * The learner tapped "Not now". Reported as an explicit refusal — the one outcome that should
     * make the model reluctant to suggest anything else (see SceneTransitionOutcome).
     */
    fun dismissSceneTransition() {
        val proposal = _sceneTransition.value ?: return
        sceneTransitionTimeoutJob?.cancel()
        sceneTransitionTimeoutJob = null
        _sceneTransition.value = null
        declineSceneTransitionInternal(
            proposal,
            com.example.medvoicetrainer.voice.SceneTransitionOutcome.DECLINED_BY_USER,
        )
    }

    private fun declineSceneTransitionInternal(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
        outcome: com.example.medvoicetrainer.voice.SceneTransitionOutcome,
    ) {
        recordSceneTransitionOutcome(proposal, outcome)
        val manager = voiceManager ?: return
        viewModelScope.launch { manager.declineSceneTransition(proposal, outcome) }
    }

    /**
     * Record how one proposal ended, whatever the ending was.
     *
     * The gate's caps and cooldowns (SceneTransitionGate's companion) were picked by judgement, not
     * measurement, and the only thing the session record could show about them was the handful of
     * transitions that were accepted. Counting every outcome — including the ones the app itself
     * turned down before the learner saw them — is what makes "how often does the model actually
     * ask, and how often is the app the one saying no" answerable from real sessions.
     */
    private fun recordSceneTransitionOutcome(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
        outcome: com.example.medvoicetrainer.voice.SceneTransitionOutcome,
    ) {
        recordLearningEvent(
            "scene_transition_outcome",
            mapOf(
                "type" to proposal.type.wire,
                "outcome" to outcome.wire,
                "blocking_tools" to !sceneTransitionsNonBlocking,
            ),
        )
    }

    /**
     * The learner accepted the transition. Order matters: the stage-direction line is written into
     * the transcript (and persisted) *before* the live session is told to change scene, so the
     * session record can never end up showing a jump that has no marker — the transcript is the one
     * thing this app must never lose.
     */
    fun acceptSceneTransition() {
        val proposal = _sceneTransition.value ?: return
        sceneTransitionTimeoutJob?.cancel()
        sceneTransitionTimeoutJob = null
        _sceneTransition.value = null
        val manager = voiceManager ?: return
        val sessionGeneration = voiceSessionGeneration.get()

        val isSoloErrand =
            proposal.type == com.example.medvoicetrainer.voice.SceneTransitionType.SOLO_ERRAND
        val relayFacts = if (isSoloErrand) buildRelayFacts(proposal) else emptyList()
        // An errand with no facts is not a learning task, it is a broken one: the stage direction
        // tells the partner the learner is back with news, while the learner was handed nothing to
        // report and gets asked about details they never saw. Only reachable if the bundled
        // catalogue could not be read at all, so decline it rather than running the scene — the
        // model gets its answer and the conversation carries on exactly where it is.
        if (isSoloErrand && relayFacts.isEmpty()) {
            // Not a refusal: the learner said yes and the app could not deliver. Telling the model
            // it was turned down would make it back off from an idea nobody rejected.
            declineSceneTransitionInternal(
                proposal,
                com.example.medvoicetrainer.voice.SceneTransitionOutcome.EXPIRED_UNANSWERED,
            )
            _activeSession.update { current ->
                if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) {
                    current
                } else {
                    current.copy(status = "That errand didn't work out — carry on where you are.")
                }
            }
            return
        }
        if (relayFacts.isNotEmpty()) {
            _relayFactCard.value = RelayFactCard(
                place = proposal.place.ifBlank { proposal.title },
                facts = relayFacts,
                awaitingReturn = true,
            )
            recordRelayFacts(proposal, relayFacts)
        }
        appendSceneTransitionTurn(proposal, relayFacts, sessionGeneration)
        recordLearningEvent(
            "scene_transition",
            mapOf("type" to proposal.type.wire, "title" to proposal.title),
        )
        recordSceneTransitionOutcome(
            proposal,
            com.example.medvoicetrainer.voice.SceneTransitionOutcome.ACCEPTED,
        )
        if (relayFacts.isEmpty()) {
            viewModelScope.launch { manager.acceptSceneTransition(proposal) }
            return
        }
        // Stage 3: the learner is off on the errand *now*, reading the two or three details they
        // were handed. Only the stage direction waits for them — releasing it here would have the
        // partner ask "so what did they say?" while they are still on the first line. The function
        // call itself is answered immediately, and the microphone is what holds the scene still
        // while they read; see VoiceManager.beginSoloErrand for why that split is the right one now
        // that the tool is declared asynchronously. The fallback timer still guarantees an
        // abandoned card eventually resumes the scene rather than leaving the learner stranded.
        viewModelScope.launch { manager.beginSoloErrand(proposal) }
        pendingRelayErrand = proposal
        relayReleaseJob?.cancel()
        relayReleaseJob = viewModelScope.launch {
            kotlinx.coroutines.delay(
                com.example.medvoicetrainer.voice.SceneTransitionGate.RELAY_READING_TIMEOUT_MILLIS,
            )
            releaseRelayErrand(sessionGeneration)
        }
    }

    /** The learner tapped "I'm back" on the fact card — let the scene resume and the report begin. */
    fun startRelayReport() {
        releaseRelayErrand(voiceSessionGeneration.get())
    }

    /**
     * Send the deferred solo-errand acceptance. The card itself stays on screen afterwards, now as
     * a reference list, because the learner has to read those facts *out* — dismissing it is a
     * separate, explicit action.
     */
    private fun releaseRelayErrand(sessionGeneration: Long) {
        if (voiceSessionGeneration.get() != sessionGeneration) return
        relayReleaseJob?.cancel()
        relayReleaseJob = null
        val proposal = pendingRelayErrand ?: return
        pendingRelayErrand = null
        _relayFactCard.update { card -> card?.copy(awaitingReturn = false) }
        val manager = voiceManager ?: return
        viewModelScope.launch { manager.acceptSceneTransition(proposal) }
    }

    /** Dismiss the relay fact card once the learner has reported (or given up on) it. */
    fun dismissRelayFactCard() {
        // Dismissing before tapping "I'm back" still owes the model its function response.
        releaseRelayErrand(voiceSessionGeneration.get())
        _relayFactCard.value = null
    }

    /**
     * Hand the learner back to the counterpart they started with, on their own initiative.
     *
     * The model-proposed return trip is not a reliable way home: the stand-in may never suggest it,
     * and one the app's own cooldown declines is never repeated. Without this a learner who accepted
     * a character switch could be stuck with the stand-in until they end the session.
     */
    fun returnToPreviousCharacter() {
        val manager = voiceManager ?: return
        val currentRole = _sceneCharacter.value ?: return
        if (!manager.canReturnToPreviousCharacter()) return
        // Hides the affordance immediately so a double tap cannot start two switches; the manager
        // fires the same transition back through onSceneCharacterChangedCallback either way.
        _sceneCharacter.value = null
        val sessionGeneration = voiceSessionGeneration.get()
        val proposal = com.example.medvoicetrainer.voice.SceneTransitionProposal(
            id = "",
            type = com.example.medvoicetrainer.voice.SceneTransitionType.RETURN_TO_PREVIOUS,
            title = "",
            description = "",
        )
        appendSceneTransitionTurn(proposal, emptyList(), sessionGeneration)
        recordLearningEvent(
            "scene_transition",
            mapOf("type" to proposal.type.wire, "title" to "learner_initiated"),
        )
        viewModelScope.launch {
            // A hand-back that never started (another switch was already in flight) would otherwise
            // leave the bar hidden — and this is the only guaranteed way home, so hiding it strands
            // the learner with the stand-in for the rest of the session.
            if (!manager.returnToPreviousCharacter() &&
                voiceSessionGeneration.get() == sessionGeneration
            ) {
                _sceneCharacter.value = currentRole
            }
        }
    }

    private fun buildRelayFacts(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
    ): List<String> = try {
        val category = JSONObject(_activeSession.value.caseJson.ifBlank { "{}" })
            .optString("category", "")
        com.example.medvoicetrainer.analysis.SceneTransitionCatalog
            .load(getApplication())
            .errandFacts(category, proposal.place)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        emptyList()
    }

    /**
     * Persist the errand's facts onto the session's case snapshot so the post-session analysis can
     * compare what the learner was told with what they actually reported back (see
     * AnalysisPromptBuilder's relay block). Deliberately never sent to the live model — the
     * counterpart is supposed to be hearing this for the first time from the learner.
     */
    private fun recordRelayFacts(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
        facts: List<String>,
    ) {
        val current = _activeSession.value
        if (!current.isActive) return
        try {
            val root = JSONObject(current.caseJson.ifBlank { "{}" })
            val entries = root.optJSONArray("relay_facts") ?: JSONArray()
            entries.put(
                JSONObject()
                    .put("place", proposal.place)
                    .put("facts", JSONArray().apply { facts.forEach { put(it) } }),
            )
            root.put("relay_facts", entries)
            val updated = root.toString()
            _activeSession.value = current.copy(caseJson = updated)
            activeSessionId?.let { id ->
                viewModelScope.launch { repository.updateSessionCaseSnapshot(id, updated) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // A missing fact record must never break the running conversation.
        }
    }

    /** Append the stage-direction marker as its own transcript turn and persist immediately. */
    private fun appendSceneTransitionTurn(
        proposal: com.example.medvoicetrainer.voice.SceneTransitionProposal,
        relayFacts: List<String>,
        sessionGeneration: Long,
    ) {
        val line = com.example.medvoicetrainer.voice.SceneTransitionProtocol
            .transcriptLineFor(proposal, relayFacts)
        var updated: List<Pair<String, String>>? = null
        _activeSession.update { state ->
            if (voiceSessionGeneration.get() != sessionGeneration || !state.isActive) {
                state
            } else {
                val next = state.transcript +
                    (com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE to line)
                updated = next
                state.copy(transcript = next)
            }
        }
        updated?.let { persistTranscriptSnapshot(it, sessionGeneration) }
    }

    private fun persistTranscriptSnapshot(
        transcript: List<Pair<String, String>>,
        sessionGeneration: Long = voiceSessionGeneration.get(),
    ) {
        val id = activeSessionId ?: return
        // Read the row id first, then verify ownership. If a replacement races this call, either
        // the old id is updated safely or the generation mismatch drops the stale snapshot; a
        // late callback can never write the previous case into the new session row.
        if (voiceSessionGeneration.get() != sessionGeneration) return
        val capture = activeSessionAudioCapture?.takeIf { it.generation == sessionGeneration }
        capture?.latestTranscript = transcript
        val learnerTurns = transcript.count { it.first == "doctor" && it.second.isNotBlank() }
        synchronized(transcriptPersistLock) {
            // A burst of final callbacks no longer launches overlapping full-list JSON writes.
            // Keep only the newest not-yet-written snapshot for this session; the worker remains
            // ordered, so an older database write can never finish after and overwrite a newer one.
            pendingTranscriptPersists[id] = TranscriptPersistRequest(id, transcript, learnerTurns, capture)
            if (transcriptPersistJob?.isActive == true) return
            transcriptPersistJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    val request = synchronized(transcriptPersistLock) {
                        val first = pendingTranscriptPersists.entries.firstOrNull()
                        if (first == null) {
                            transcriptPersistJob = null
                            null
                        } else {
                            pendingTranscriptPersists.remove(first.key)
                            first.value
                        }
                    } ?: return@launch
                    if (request.capture != null) {
                        persistAudioCaptureSnapshot(request.capture)
                    } else {
                        repository.updateSessionTranscript(
                            request.sessionId,
                            serializeTranscript(request.transcript, emptyMap()),
                            request.learnerTurns,
                        )
                    }
                }
            }
        }
    }

    private fun serializeTranscript(
        transcript: List<Pair<String, String>>,
        clips: Map<Int, LearnerAudioClip>,
    ): String = JSONArray().apply {
        transcript.forEachIndexed { index, (role, text) ->
            put(JSONObject().put("role", role).put("text", text).apply {
                clips[index]?.let { clip ->
                    put("learner_audio_path", clip.relativePath)
                    put("learner_audio_duration_ms", clip.durationMs)
                }
            })
        }
    }.toString()

    private fun parseLearnerAudioClips(rawTranscript: String): Map<Int, LearnerAudioClip> = try {
        val turns = JSONArray(rawTranscript.ifBlank { "[]" })
        buildMap {
            for (index in 0 until turns.length()) {
                val turn = turns.optJSONObject(index) ?: continue
                val path = turn.optString("learner_audio_path")
                if (path.isNotBlank()) {
                    put(index, LearnerAudioClip(path, turn.optLong("learner_audio_duration_ms", 0)))
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyMap()
    }

    private fun scheduleLearnerAudioCapture(
        sessionGeneration: Long,
        transcriptIndex: Int,
        transcript: List<Pair<String, String>>,
        pcm: ByteArray,
    ) {
        val capture = activeSessionAudioCapture?.takeIf { it.generation == sessionGeneration } ?: return
        capture.latestTranscript = transcript
        val job = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (capture.analyzePronunciation) {
                val spool = pronunciationAudioStore.spoolTurn(capture.sessionId, transcriptIndex, pcm)
                spool.file?.let {
                    capture.pronunciationFiles[transcriptIndex] = it
                } ?: spool.quality.reason?.let { reason ->
                    capture.pronunciationQualityWarnings[transcriptIndex] = reason
                }
            }
            if (capture.saveLearnerClips) {
                learnerAudioStore.saveTurn(capture.sessionId, transcriptIndex, pcm)?.let { clip ->
                    capture.clips[transcriptIndex] = clip
                    persistAudioCaptureSnapshot(capture)
                }
            }
        }
        capture.jobs.add(job)
    }

    private suspend fun awaitLearnerAudioSaves(capture: SessionAudioCapture?) {
        capture?.jobs?.toList()?.forEach { it.join() }
    }

    /**
     * Blind-listener + diagnostic pronunciation pass over the captured learner turns, sharing one
     * time budget between the Azure route and its Gemini fallback so a supplementary signal can
     * never hold the feedback screen for several extra minutes.
     *
     * The budget is handed to the engines rather than imposed here with `withTimeoutOrNull`. That
     * wrapper was the wrong shape twice over: a long session is analysed in several batches, and
     * cancelling mid-run discarded every batch that had already succeeded — the learner waited the
     * full budget and got nothing back. (It could not even cut a call short reliably: the provider
     * clients block in `HttpURLConnection`, which does not observe coroutine cancellation.) The
     * engines instead check the budget between round-trips, stop cleanly, and return the evidence
     * they actually collected.
     *
     * Always returns a result — empty when nothing usable came back — so the caller's merge path is
     * the same whether the pass succeeded, partially succeeded, or failed outright.
     */
    private suspend fun runPronunciationAnalysis(
        sources: List<com.example.medvoicetrainer.analysis.PronunciationAudioSource>,
        analysisDomain: String,
    ): com.example.medvoicetrainer.analysis.PronunciationAnalysisResult {
        val empty = com.example.medvoicetrainer.analysis.PronunciationAnalysisResult(
            emptyList(),
            emptyList()
        )
        if (sources.isEmpty()) return empty
        val startedAt = System.nanoTime()
        fun remainingBudgetMs(): Long =
            PRONUNCIATION_ANALYSIS_BUDGET_MS - (System.nanoTime() - startedAt) / 1_000_000

        val geminiKey = getApiKeyForBackend("gemini")
        return try {
            val azureResult = if (hasAzureSpeechCredentials()) {
                runCatching {
                    com.example.medvoicetrainer.analysis.PronunciationEngine
                        .analyzePronunciationSegmentsWithAzureDetailed(
                            azureKey = _azureSpeechKey.value,
                            azureRegion = _azureSpeechRegion.value,
                            sources = sources,
                            l1Lang = _nativeLanguage.value,
                            domain = analysisDomain,
                            geminiApiKey = geminiKey,
                            geminiModel = getModelForBackend("gemini"),
                            explanationLanguage = nativeExplanationLang(),
                            budgetMs = remainingBudgetMs()
                        )
                }.getOrNull()?.takeIf { it.blindObservations.isNotEmpty() }
            } else {
                null
            }
            azureResult ?: if (geminiKey.isNotBlank()) {
                com.example.medvoicetrainer.analysis.PronunciationEngine
                    .analyzePronunciationSegmentsDetailed(
                        apiKey = geminiKey,
                        sources = sources,
                        l1Lang = _nativeLanguage.value,
                        domain = analysisDomain,
                        model = getModelForBackend("gemini"),
                        explanationLanguage = nativeExplanationLang(),
                        budgetMs = remainingBudgetMs()
                    )
            } else {
                empty
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            empty
        }
    }

    private suspend fun persistAudioCaptureSnapshot(capture: SessionAudioCapture) {
        capture.persistMutex.withLock {
            val transcript = capture.latestTranscript
            repository.updateSessionTranscript(
                capture.sessionId,
                serializeTranscript(transcript, capture.clips),
                transcript.count { it.first == "doctor" && it.second.isNotBlank() },
            )
        }
    }

    fun recordLearningEvent(eventType: String, details: Map<String, Any?> = emptyMap()) {
        val current = _activeSession.value
        if (!current.isActive) return
        try {
            val root = JSONObject(current.caseJson.ifBlank { "{}" })
            val events = root.optJSONArray("learning_events") ?: JSONArray()
            val event = JSONObject()
                .put("event_type", eventType)
                .put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            details.forEach { (key, value) -> event.put(key, value ?: JSONObject.NULL) }
            events.put(event)
            root.put("learning_events", events)
            val updatedJson = root.toString()
            _activeSession.value = current.copy(caseJson = updatedJson)
            activeSessionId?.let { id ->
                viewModelScope.launch { repository.updateSessionCaseSnapshot(id, updatedJson) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Learning-event telemetry is supplementary and must never interrupt practice.
        }
    }

    /**
     * End the live session without analyzing it.
     *
     * [endReason] records *why* on the session row (see [SessionEndReason]). Every caller of this
     * function is a learner deciding to stop, so the default is [SessionEndReason.DISCARDED]: the
     * decision is remembered, and Home never re-offers to recover a session the learner chose to
     * drop. The transcript itself is untouched and stays in History either way.
     */
    fun cancelSession(endReason: String = SessionEndReason.DISCARDED) {
        val state = _activeSession.value
        val draftId = activeSessionId
        // Skipping from the Analyzing screen: the running evaluation belongs to this session and
        // must not later write `completed` over the learner's decision. A detached (background)
        // analysis has already reset the active state, so it is never cancelled here.
        if (state.isFinishing) {
            finishingJob?.cancel()
            finishingJob = null
        }
        val audioCapture = activeSessionAudioCapture
        val endingGeneration = voiceSessionGeneration.get()
        val usage = synchronized(voiceUsageLock) {
            activeVoiceUsage?.takeIf { activeVoiceUsageGeneration == endingGeneration }
        }
        voiceSessionGeneration.incrementAndGet()
        openBookBriefingDecision?.complete(false)
        openBookBriefingDecision = null
        _openBookBriefingPending.value = false
        activeSupportsPreReadyTextQueue = false
        voiceManager?.stop()
        voiceManager = null
        VoiceSessionService.stop(getApplication())
        audioCapture?.let { capture ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                capture.jobs.toList().forEach { it.join() }
                pronunciationAudioStore.cleanupSession(capture.sessionId)
            }
        }
        activeSessionAudioCapture = null
        activeSessionId = null
        if (draftId != null) {
            val duration = sessionDurationSeconds(state)
            val patientChars = state.transcript
                .filter { it.first == "patient" || it.first == "interviewer" }
                .sumOf { it.second.length }
            val backend = _voiceBackend.value.lowercase()
            val voiceModelId = usage?.model ?: getVoiceModelForBackend(backend)
            val voiceCost = usage?.let(CostTracker::computeVoiceCost) ?: when (backend) {
                // Live re-bills the accumulated context on every turn, so the ordered turn list —
                // not just a character total — is what the estimate has to walk.
                "gemini" -> CostTracker.estimateGeminiLiveCost(
                    turns = state.transcript.map { CostTracker.VoiceTurn(it.first, it.second) },
                    durationSeconds = duration,
                    model = voiceModelId,
                    systemPromptChars = CostTracker.systemPromptCharsFor(state.caseJson),
                ).costUsd
                "openai" -> CostTracker.computeOpenaiVoiceCost(duration, patientChars)
                else -> 0.0
            }
            viewModelScope.launch {
                repository.finalizeVoiceOnlySession(
                    id = draftId,
                    durationSeconds = duration,
                    voiceModel = voiceModelId,
                    voiceCostUsd = voiceCost,
                    usage = usage,
                    // Cancellation can cut off a final provider usage event, so even completed
                    // turn metadata cannot prove the unfinished tail's bill. Unlike finishSession,
                    // this path bumps the session generation before stop() and snapshots usage
                    // beforehand, so GeminiLiveClient.close()'s pending-usage flush cannot reach
                    // it either — "estimated" stays the honest label here even when usage != null.
                    estimated = backend in setOf("gemini", "openai"),
                    // Same write that banks the voice cost also banks the learner's intent, so a
                    // process death between the two can never leave a discarded session looking
                    // like an interrupted one.
                    endReason = endReason,
                )
            }
        }
        interviewPhaseTracker = null
        wrapUpDetector = null
        openBookRules = emptyList()
        _openBookCard.value = null
        _openBookLevel.value = com.example.medvoicetrainer.analysis.OpenBookLevel.HIDDEN
        _openBookClosingLevel.value = com.example.medvoicetrainer.analysis.ClosingLevel.HIDDEN
        _openBookBriefed.value = false
        _openBookPreviousLevel.value = null
        phrasebookRules = emptyList()
        _phrasebookCard.value = null
        _phrasebookLevel.value = com.example.medvoicetrainer.analysis.PhrasebookLevel.HIDDEN
        clearSceneTransitionState()
        _activeSession.value = ActiveSessionState()
        _playbackSpeed.value = 1.0f
        _playbackSpeedExperimental.value = false
    }

    /** Drop any beta scene-transition UI state; the socket is already gone, so nothing is owed. */
    private fun clearSceneTransitionState() {
        sceneTransitionTimeoutJob?.cancel()
        sceneTransitionTimeoutJob = null
        relayReleaseJob?.cancel()
        relayReleaseJob = null
        // A solo errand still waiting on "I'm back" is dropped rather than released: the session it
        // belonged to is ending, so there is no conversation left to resume.
        pendingRelayErrand = null
        _sceneTransition.value = null
        _relayFactCard.value = null
        _sceneCharacter.value = null
        // The next session declares its own tool afresh and reports back what it got; until it
        // does, assume the mode every session normally comes up in.
        setSceneTransitionsNonBlocking(true)
    }

    private fun sessionDurationSeconds(createdAt: String): Int = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val startMs = fmt.parse(createdAt)?.time ?: 0L
        if (startMs > 0L) (((Date().time - startMs) / 1000L).toInt()).coerceAtLeast(0) else 0
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        0
    }

    private fun sessionDurationSeconds(state: ActiveSessionState): Int {
        val wallClockMillis = sessionDurationSeconds(state.createdAt) * 1_000L
        val activePauseMillis = state.pausedAtMillis?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        } ?: 0L
        return ((wallClockMillis - state.totalPausedMillis - activePauseMillis)
            .coerceAtLeast(0L) / 1_000L).toInt()
    }

    // --- Trash (queries.py list_trash_sessions / restore_session / purge_old_trash) ---
    private val _trashSessions = MutableStateFlow<List<com.example.medvoicetrainer.db.SessionEntity>>(emptyList())
    val trashSessions = _trashSessions.asStateFlow()

    /** Load soft-deleted sessions into [trashSessions] (mirrors history_tab.py's Trash view). */
    fun loadTrash() {
        viewModelScope.launch {
            _trashSessions.value = repository.listTrashSessions()
        }
    }

    /** Move a session to trash (soft delete), then refresh the trash list if it's open. */
    fun trashSession(id: Int) {
        viewModelScope.launch {
            repository.softDeleteSession(id)
            _trashSessions.value = repository.listTrashSessions()
        }
    }

    /** Restore a soft-deleted session (queries.restore_session). */
    fun restoreSession(id: Int) {
        viewModelScope.launch {
            repository.restoreSession(id)
            _trashSessions.value = repository.listTrashSessions()
        }
    }

    /** Permanently purge trash older than [days] days (queries.purge_old_trash). */
    fun purgeOldTrash(days: Int = 7) {
        viewModelScope.launch {
            repository.purgeOldTrash(days)
            _trashSessions.value = repository.listTrashSessions()
        }
    }

    /**
     * Ported from history_tab.py's DOCX export: renders the session scorecard to a `.docx` in the
     * FileProvider-shared cache subdir (`shared_cards/`) and records the path (queries.save_docx_path).
     * Returns the file to hand to a share intent, or null on failure. DocxExporter writes a minimal
     * hand-built OOXML file (see its honesty note) — opens in Word/Docs/LibreOffice.
     */
    suspend fun exportSessionDocx(session: com.example.medvoicetrainer.db.SessionEntity): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().cacheDir, "shared_cards").apply { mkdirs() }
                val safeName = session.caseName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "session" }
                val outFile = java.io.File(dir, "BedsideEnglish_${safeName}_${session.id}.docx")
                com.example.medvoicetrainer.export.DocxExporter.generateReport(session.toAnalysisMap(), outFile.absolutePath)
                repository.saveDocxPath(session.id, outFile.absolutePath)
                outFile
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                handleUiException("exporting the DOCX report", e)
                null
            }
        }

    // --- Encrypted backup / restore ---
    //
    // Both run in viewModelScope, never in the Preferences screen's composition scope. A backup of
    // a long history takes minutes, and a composition-scoped job dies the moment the learner presses
    // Back or the Activity is recreated (uiMode is not in the manifest's configChanges) — which
    // silently dropped the result of work that had already written the file.
    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState = _backupState.asStateFlow()
    private var backupJob: kotlinx.coroutines.Job? = null

    fun startUserBackupExport(uri: android.net.Uri, password: String, includeAudio: Boolean) {
        startBackupOperation(BackupOperation.EXPORT) {
            userBackupManager.exportTo(uri, password, includeAudio) { progress ->
                _backupState.update { current -> current.copy(progress = progress) }
            }
        }
    }

    /** Starts the normal export path without first handing the learner to the system file picker. */
    fun startUserBackupExportToDownloads(password: String, includeAudio: Boolean) {
        startBackupOperation(BackupOperation.EXPORT) {
            userBackupManager.exportToDownloads(password, includeAudio) { progress ->
                _backupState.update { current -> current.copy(progress = progress) }
            }
        }
    }

    fun startUserBackupRestore(uri: android.net.Uri, password: String) {
        startBackupOperation(BackupOperation.RESTORE) {
            userBackupManager.restoreFrom(uri, password) { progress ->
                _backupState.update { current -> current.copy(progress = progress) }
            }
        }
    }

    /** Dismiss the success/failure result the UI is showing; a running job is left alone. */
    fun clearBackupResult() {
        _backupState.update { current ->
            if (current.running) current else BackupUiState()
        }
    }

    private fun startBackupOperation(
        operation: BackupOperation,
        block: suspend () -> com.example.medvoicetrainer.export.UserBackupSummary,
    ) {
        if (_backupState.value.running || backupJob?.isActive == true) return
        _backupState.value = BackupUiState(running = true, operation = operation)
        backupJob = viewModelScope.launch {
            try {
                if (_activeSession.value.isActive) {
                    throw com.example.medvoicetrainer.export.UserBackupException(
                        com.example.medvoicetrainer.export.UserBackupFailure.SESSION_ACTIVE,
                        "End the current practice session first",
                    )
                }
                val summary = block()
                _backupState.value = BackupUiState(operation = operation, summary = summary)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _backupState.value = BackupUiState(
                    operation = operation,
                    failure = com.example.medvoicetrainer.export.UserBackupException.failureOf(e),
                )
            }
        }
    }

    // --- Conversational Loop (Dual-Engine) ---
    /** Returns true only when the turn was accepted for immediate send or pre-ready queueing. */
    fun addLearnerTurn(text: String): Boolean {
        val sessionState = _activeSession.value
        val normalizedText = text.trim()
        if (!sessionState.isActive || normalizedText.isEmpty()) return false
        val sessionGeneration = voiceSessionGeneration.get()
        val manager = voiceManager
        if (manager == null || !canAcceptTypedTurn(
                sessionState.voiceConnectionState,
                activeSupportsPreReadyTextQueue,
            )
        ) {
            _activeSession.update { current ->
                if (voiceSessionGeneration.get() != sessionGeneration) current
                else current.copy(
                    error = if (manager == null) {
                        "Voice session is not available. Please end this session and try again."
                    } else if (current.voiceConnectionState == VoiceConnectionState.CONNECTING) {
                        "Wait until the patient connection is ready before sending this message."
                    } else {
                        "Wait for the voice connection to recover before sending, or end this session and try again."
                    },
                )
            }
            return false
        }

        interviewPhaseTracker?.updateFromTurn(normalizedText, sessionState.transcript.size)

        // CONNECTING is intentionally accepted: GeminiLiveClient owns a bounded, reconnect-safe
        // pre-setup text queue. Other degraded states above are rejected so the editor keeps the
        // user's text instead of pretending a failed socket received it.
        var updatedTranscript: List<Pair<String, String>>? = null
        _activeSession.update { current ->
            if (voiceSessionGeneration.get() != sessionGeneration || !current.isActive) {
                current
            } else if (!canAcceptTypedTurn(
                    current.voiceConnectionState,
                    activeSupportsPreReadyTextQueue,
                )
            ) {
                current.copy(
                    error = if (current.voiceConnectionState == VoiceConnectionState.CONNECTING) {
                        "Wait until the patient connection is ready before sending this message."
                    } else {
                        "Wait for the voice connection to recover before sending this message."
                    },
                )
            } else {
                val nextTranscript = current.transcript + ("doctor" to normalizedText)
                updatedTranscript = nextTranscript
                val nextHint = interviewPhaseTracker?.getRescueHintParts()
                current.copy(
                    transcript = nextTranscript,
                    isAILoading = true,
                    phaseHint = nextHint?.question ?: current.phaseHint,
                    phaseHintCategory = nextHint?.category ?: current.phaseHintCategory,
                    phaseHintOptions = interviewPhaseTracker?.getRescueHintOptions()
                        ?: current.phaseHintOptions,
                    error = null,
                )
            }
        }
        val acceptedTranscript = updatedTranscript ?: return false
        persistTranscriptSnapshot(acceptedTranscript, sessionGeneration)

        updateChecklistCoverage(sessionGeneration)
        detectInvestigationOrders(normalizedText, sessionGeneration)
        detectExamManeuvers(normalizedText, sessionGeneration)

        viewModelScope.launch {
            if (voiceSessionGeneration.get() != sessionGeneration) return@launch
            if (voiceManager !== manager) {
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration) current
                    else current.copy(
                        isAILoading = false,
                        error = "Voice session is not available. Please end this session and try again.",
                    )
                }
                return@launch
            }
            try {
                manager.sendText(normalizedText)
                // Keep the waiting state until a patient transcript, turn-complete event, or
                // explicit provider error arrives. Queueing a WebSocket frame is not a response.
            } catch (error: Throwable) {
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() != sessionGeneration) current
                    else current.copy(
                        isAILoading = false,
                        error = "Could not send your message: ${com.example.medvoicetrainer.api.ApiError.userMessage(error)}",
                    )
                }
            }
        }
        return true
    }

    /**
     * Feed one just-completed learner turn into the deterministic [WrapUpDetector] and, the single
     * turn it decides the encounter is wrap-up-ready, raise [ActiveSessionState.wrapUpSuggested]
     * for the soft UI nudge near the End button. Never ends the session itself — the patient's
     * in-fiction closure behavior (PromptBuilder's closure addendum) and the learner stay in
     * control. Encounter-only: [wrapUpDetector] is null for every other mode.
     */
    private fun maybeSignalWrapUp(
        tracker: InterviewPhaseTracker?,
        newDomains: Set<String>,
        sessionGeneration: Long,
    ) {
        val detector = wrapUpDetector ?: return
        if (tracker == null || detector.hasFired) return
        doctorTurnCount += 1
        turnsSinceNewCoverage = if (newDomains.isNotEmpty()) 0 else turnsSinceNewCoverage + 1
        val phasesComplete = (0..4).count { tracker.phaseProgress(it).complete }
        val fired = detector.observe(
            WrapUpSignals(
                doctorTurns = doctorTurnCount,
                elapsedSeconds = (System.currentTimeMillis() - sessionStartMillis) / 1000L,
                phasesComplete = phasesComplete,
                phasesTotal = 5,
                turnsSinceNewCoverage = turnsSinceNewCoverage,
            )
        )
        if (fired) {
            _activeSession.update { current ->
                if (voiceSessionGeneration.get() != sessionGeneration) current
                else current.copy(wrapUpSuggested = true)
            }
        }
    }

    /**
     * Re-derive every checklist row from the transcript. Deterministic, local, and cheap enough to
     * run inline on the caller's thread — the whole point of [CoverageEngine] replacing the old
     * per-turn model call.
     *
     * The result is rebuilt from scratch each time rather than merged into the previous one. The
     * old code only ever merged `true` values, so one bad tick was permanent; recomputing means a
     * row can also come *back down* when the evidence for it no longer stands, and the learner's
     * own overrides are re-applied from [checklistManualOverrides] on top.
     */
    private fun evaluateChecklist(transcript: List<Pair<String, String>>): List<ChecklistItem> {
        val rules = checklistRules
        if (rules.isEmpty()) return emptyList()
        val evidence = com.example.medvoicetrainer.analysis.CoverageEngine.evaluate(
            rules = rules,
            tracker = interviewPhaseTracker?.coverage,
            transcript = transcript,
        )
        return rules.map { rule ->
            ChecklistItem(
                objective = rule.objective,
                evidence = evidence[rule.objective]
                    ?: com.example.medvoicetrainer.analysis.CoverageEvidence(),
                manualOverride = checklistManualOverrides[rule.objective],
            )
        }
    }

    /**
     * The same deterministic evaluation for Open Book's "must ask" rows.
     *
     * Kept separate from the graded checklist on purpose: these rows come from the answer sheet,
     * not from the case's own learning objectives, and the learner cannot hand-correct them — a row
     * the rules missed simply stays open as a reminder, which is the behaviour that matters for a
     * reference card. They share [interviewPhaseTracker], so a question asked once satisfies both.
     */
    private fun evaluateOpenBookChecklist(transcript: List<Pair<String, String>>): List<ChecklistItem> {
        val rules = openBookRules
        if (rules.isEmpty()) return emptyList()
        val evidence = com.example.medvoicetrainer.analysis.CoverageEngine.evaluate(
            rules = rules,
            tracker = interviewPhaseTracker?.coverage,
            transcript = transcript,
        )
        return rules.map { rule ->
            ChecklistItem(
                objective = rule.objective,
                evidence = evidence[rule.objective]
                    ?: com.example.medvoicetrainer.analysis.CoverageEvidence(),
            )
        }
    }

    /**
     * The same deterministic evaluation for the everyday phrasebook's offered expressions.
     *
     * Advisory only, and deliberately never graded: a tick tells the learner "you actually used
     * that one", which is what stops the sheet from being something they read and forgot. A line
     * the rules miss simply stays unticked and costs them nothing.
     */
    private fun evaluatePhrasebookUsage(transcript: List<Pair<String, String>>): List<ChecklistItem> {
        val rules = phrasebookRules
        if (rules.isEmpty()) return emptyList()
        val evidence = com.example.medvoicetrainer.analysis.CoverageEngine.evaluate(
            rules = rules,
            tracker = null,
            transcript = transcript,
        )
        return rules.map { rule ->
            ChecklistItem(
                objective = rule.objective,
                evidence = evidence[rule.objective]
                    ?: com.example.medvoicetrainer.analysis.CoverageEvidence(),
            )
        }
    }

    private fun updateChecklistCoverage(sessionGeneration: Long = voiceSessionGeneration.get()) {
        if (checklistRules.isEmpty() && openBookRules.isEmpty() && phrasebookRules.isEmpty()) return
        val preUpdate = _activeSession.value
        if (voiceSessionGeneration.get() != sessionGeneration || !preUpdate.isActive) return

        val updated = evaluateChecklist(preUpdate.transcript)
        val updatedOpenBook = evaluateOpenBookChecklist(preUpdate.transcript)
        val updatedPhrasebook = evaluatePhrasebookUsage(preUpdate.transcript)
        val caseJsonWithPhraseUsage = com.example.medvoicetrainer.analysis.EverydayPhrasebook
            .withSessionUsage(
                preUpdate.caseJson,
                updatedPhrasebook.filter { it.isMet }.map { it.objective },
            )

        // Guided-cue fade: an objective that just flipped unmet→met means the learner produced it.
        // In a guided coaching_mode session, bump its per-skill success count so its cue reveals
        // less next time (gradual release).
        if (_guidedModeEnabled.value &&
            com.example.medvoicetrainer.analysis.GuidedCueEngine.isCoachingCase(preUpdate.caseJson)
        ) {
            val wasMet = preUpdate.checklist.associate { it.objective to it.isMet }
            updated.forEach { item ->
                if (item.isMet && wasMet[item.objective] != true) incrementGuidedSuccess(item.objective)
            }
        }

        val followUpFlow = if (preUpdate.mode == "follow_up") {
            com.example.medvoicetrainer.analysis.FollowUpFlow.derive(
                caseJson = preUpdate.caseJson,
                coverage = updated.associate { it.objective to it.source },
                learnerTurnCount = preUpdate.transcript.count { it.first == "doctor" && it.second.isNotBlank() },
            )
        } else null
        _activeSession.update { currentState ->
            if (voiceSessionGeneration.get() != sessionGeneration || !currentState.isActive) currentState
            else currentState.copy(
                checklist = updated,
                openBookChecklist = updatedOpenBook,
                phrasebookUsage = updatedPhrasebook,
                caseJson = caseJsonWithPhraseUsage,
                phaseHint = followUpFlow?.primary?.question ?: currentState.phaseHint,
                phaseHintCategory = followUpFlow?.primary?.category ?: currentState.phaseHintCategory,
                phaseHintOptions = followUpFlow?.options ?: currentState.phaseHintOptions,
                wrapUpSuggested = followUpFlow?.wrapUpSuggested ?: currentState.wrapUpSuggested,
            )
        }
        if (caseJsonWithPhraseUsage != preUpdate.caseJson) {
            activeSessionId?.let { id ->
                viewModelScope.launch { repository.updateSessionCaseSnapshot(id, caseJsonWithPhraseUsage) }
            }
        }
    }

    /**
     * Match a completed learner turn against case-authored investigation events. This is local and
     * deterministic: the voice model never chooses or generates a result. The state is raised as
     * soon as the provider's final learner transcript arrives, even if patient audio has started.
     */
    private fun detectInvestigationOrders(
        learnerTurn: String,
        sessionGeneration: Long = voiceSessionGeneration.get(),
    ) {
        val current = _activeSession.value
        if (!current.isActive || voiceSessionGeneration.get() != sessionGeneration) return
        val events = com.example.medvoicetrainer.analysis.InvestigationResults.parseEvents(current.caseJson)
        if (events.isEmpty()) return
        val triggered = (current.pendingInvestigationEvents + current.revealedInvestigationEvents)
            .mapTo(mutableSetOf()) { it.id }
        val matches = com.example.medvoicetrainer.analysis.InvestigationResults.detectOrders(
            learnerTurn = learnerTurn,
            events = events,
            alreadyTriggeredIds = triggered,
        )
        makeInvestigationResultsAvailable(matches, sessionGeneration, source = "voice")
    }

    /**
     * Korean CPX: match a learner turn against the examination vocabulary embedded in the session
     * snapshot. Local and deterministic like [detectInvestigationOrders]: the finding shown is the
     * one authored in the case's `sp_script`, never one a model produced.
     */
    private fun detectExamManeuvers(
        learnerTurn: String,
        sessionGeneration: Long = voiceSessionGeneration.get(),
    ) {
        val current = _activeSession.value
        if (!current.isActive || voiceSessionGeneration.get() != sessionGeneration) return
        if (current.mode != com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE) return
        val session = com.example.medvoicetrainer.analysis.KmleCpx.sessionCase(current.caseJson) ?: return
        if (session.maneuvers.isEmpty()) return
        val found = com.example.medvoicetrainer.analysis.SpScript.detect(learnerTurn, session.maneuvers.values, korean = true)
        if (found.isEmpty()) return
        _activeSession.update { state ->
            if (!state.isActive || voiceSessionGeneration.get() != sessionGeneration) state
            else {
                val fresh = found.filterNot { it in state.revealedExamManeuvers }
                if (fresh.isEmpty()) state else state.copy(revealedExamManeuvers = state.revealedExamManeuvers + fresh)
            }
        }
    }

    /**
     * Manual fallback for learners whose spoken order was not transcribed or did not match an
     * authored alias. The selectable tests still come exclusively from the active case's
     * investigation_events, so the UI cannot request or reveal an invented result.
     */
    fun orderInvestigation(eventId: String) {
        val sessionGeneration = voiceSessionGeneration.get()
        val current = _activeSession.value
        if (!current.isActive) return
        val event = com.example.medvoicetrainer.analysis.InvestigationResults
            .parseEvents(current.caseJson)
            .firstOrNull { it.id == eventId }
            ?: return
        makeInvestigationResultsAvailable(listOf(event), sessionGeneration, source = "ui")
    }

    private fun makeInvestigationResultsAvailable(
        candidates: List<com.example.medvoicetrainer.analysis.InvestigationEvent>,
        sessionGeneration: Long,
        source: String,
    ) {
        if (candidates.isEmpty()) return
        var newlyAvailable = emptyList<com.example.medvoicetrainer.analysis.InvestigationEvent>()
        _activeSession.update { state ->
            if (!state.isActive || voiceSessionGeneration.get() != sessionGeneration) {
                newlyAvailable = emptyList()
                state
            } else {
                val existingIds = (state.pendingInvestigationEvents + state.revealedInvestigationEvents)
                    .mapTo(mutableSetOf()) { it.id }
                newlyAvailable = candidates.filter { it.id !in existingIds }.distinctBy { it.id }
                if (newlyAvailable.isEmpty()) state else state.copy(
                    pendingInvestigationEvents = state.pendingInvestigationEvents + newlyAvailable,
                )
            }
        }
        newlyAvailable.forEach { event ->
            recordLearningEvent(
                "investigation_result_available",
                mapOf("event_id" to event.id, "title" to event.title, "source" to source),
            )
        }
    }

    /** Reveal one pre-authored result bundle and keep it reachable in the session chart. */
    fun revealInvestigationResult(eventId: String) {
        val current = _activeSession.value
        val event = current.pendingInvestigationEvents.firstOrNull { it.id == eventId } ?: return
        _activeSession.update { state ->
            state.copy(
                pendingInvestigationEvents = state.pendingInvestigationEvents.filterNot { it.id == eventId },
                revealedInvestigationEvents =
                    (state.revealedInvestigationEvents + event).distinctBy { it.id },
            )
        }
        recordLearningEvent(
            "investigation_result_viewed",
            mapOf("event_id" to event.id, "title" to event.title),
        )
    }

    /**
     * Cycle one checklist row through the learner's own verdict: rules → ticked → cleared → rules.
     *
     * A single-domain objective also pushes the verdict down into the coverage tracker, so ticking
     * "ask about allergies" by hand stops the hint engine from continuing to suggest it. That link
     * is what keeps the checklist and "Stuck? Question idea" from contradicting each other, which
     * was one of the ways the pair looked untrustworthy even when each was individually defensible.
     */
    fun cycleChecklistOverride(objective: String) {
        val current = _activeSession.value.checklist.firstOrNull { it.objective == objective } ?: return
        val next = when (current.manualOverride) {
            null -> !current.evidence.isMet // first tap flips whatever the rules decided
            true -> false
            false -> null
        }
        if (next == null) checklistManualOverrides.remove(objective) else checklistManualOverrides[objective] = next

        checklistRules.firstOrNull { it.objective == objective }
            ?.takeIf { it.propagatesManualTick }
            ?.let { rule -> interviewPhaseTracker?.coverage?.setManualOverride(rule.domains.first(), next) }

        _activeSession.update { state ->
            if (!state.isActive) state
            else state.copy(
                checklist = state.checklist.map {
                    if (it.objective == objective) it.copy(manualOverride = next) else it
                }
            )
        }
        refreshPhaseHints(voiceSessionGeneration.get())
        recordLearningEvent("checklist_override", mapOf("state" to next.toString()))
    }

    /** Push the tracker's current suggestions into session state (single hint + picker options). */
    private fun refreshPhaseHints(sessionGeneration: Long) {
        val current = _activeSession.value
        val followUpFlow = if (current.mode == "follow_up") {
            com.example.medvoicetrainer.analysis.FollowUpFlow.derive(
                caseJson = current.caseJson,
                coverage = current.checklist.associate { it.objective to it.source },
                learnerTurnCount = current.transcript.count { it.first == "doctor" && it.second.isNotBlank() },
            )
        } else null
        val tracker = interviewPhaseTracker
        if (tracker == null && followUpFlow == null) return
        val hint = followUpFlow?.primary ?: tracker?.getRescueHintParts()
        val options = followUpFlow?.options ?: tracker?.getRescueHintOptions().orEmpty()
        _activeSession.update { current ->
            if (voiceSessionGeneration.get() != sessionGeneration) current
            else current.copy(
                phaseHint = hint?.question.orEmpty(),
                phaseHintCategory = hint?.category.orEmpty(),
                phaseHintOptions = options,
                wrapUpSuggested = followUpFlow?.wrapUpSuggested ?: current.wrapUpSuggested,
            )
        }
    }

    /** Save post-feedback self-assessment without mutating the AI score. */
    fun saveSelfAssessment(scores: Map<String, Double>, everyday: Boolean) {
        val id = lastSessionId ?: return
        viewModelScope.launch { repository.saveSelfScores(id, scores, everyday) }
    }

    /** Prepare the optional, local-only reflection card for the current feedback screen. */
    fun prepareSessionReflection() {
        val sessionId = lastSessionId ?: return
        if (_sessionReflection.value.sessionId == sessionId) return
        // Claim this feedback synchronously so recomposition cannot launch duplicate reads.
        _sessionReflection.value = SessionReflectionUiState(sessionId = sessionId)
        viewModelScope.launch {
            val reflection = com.example.medvoicetrainer.analysis.SessionReflection
            val historyJson = repository.getSetting(reflection.HISTORY_KEY, "[]")
            val existing = reflection.parseHistory(historyJson)
                .lastOrNull { it.sessionId == sessionId }
            var reason: com.example.medvoicetrainer.analysis.ReflectionPromptReason? = null
            if (existing == null) {
                reason = reflection.automaticPromptReason(
                    sessionsNewestFirst = repository.getAllSessionsList()
                        .filterNot { com.example.medvoicetrainer.analysis.KmleCpx.isKmleSession(it.mode, it.analysisDomain) },
                    currentSessionId = sessionId,
                    lastAutoDateIso = repository.getSetting(reflection.LAST_AUTO_DATE_KEY, ""),
                    lastAutoSessionId = repository.getSetting(reflection.LAST_AUTO_SESSION_KEY, "0")
                        .toIntOrNull() ?: 0
                )
                if (reason != null) {
                    repository.setSetting(reflection.LAST_AUTO_DATE_KEY, java.time.LocalDate.now().toString())
                    repository.setSetting(reflection.LAST_AUTO_SESSION_KEY, sessionId.toString())
                }
            }
            _sessionReflection.value = SessionReflectionUiState(
                sessionId = sessionId,
                autoPromptReason = reason,
                selectedFeeling = existing?.feeling
                    ?: _sessionReflection.value.takeIf { it.sessionId == sessionId }?.selectedFeeling
            )
        }
    }

    fun submitSessionFeeling(feeling: com.example.medvoicetrainer.analysis.SessionFeeling) {
        val state = _sessionReflection.value
        val sessionId = state.sessionId ?: lastSessionId ?: return
        viewModelScope.launch {
            val reflection = com.example.medvoicetrainer.analysis.SessionReflection
            val history = repository.getSetting(reflection.HISTORY_KEY, "[]")
            repository.setSetting(
                reflection.HISTORY_KEY,
                reflection.record(
                    historyJson = history,
                    sessionId = sessionId,
                    feeling = feeling,
                    dateIso = java.time.LocalDate.now().toString(),
                    promptReason = state.autoPromptReason?.wireName ?: "manual"
                )
            )
            _sessionReflection.value = state.copy(selectedFeeling = feeling)
        }
    }

    /** Manually classify only a correction that did not meet the conservative auto-save rule. */
    fun decideCorrection(correction: SrsCorrection, decision: CorrectionDecision) {
        if (decision == CorrectionDecision.PENDING) return
        val evaluation = _lastEvaluation.value ?: return
        if (evaluation.evaluationLocked || correction !in evaluation.corrections) return
        val key = correction.decisionKey()
        val previous = _correctionDecisions.value[key]
        if (previous == decision) return

        val decisions = _correctionDecisions.value + (key to decision)
        _correctionDecisions.value = decisions
        rememberCorrectionDecision(correction, decision)
        if (decision == CorrectionDecision.ACCEPTED && previous != CorrectionDecision.ACCEPTED) {
            saveCorrectionsToSRS(listOf(correction), lastSessionId, evaluation.analysisDomain)
        } else if (previous == CorrectionDecision.ACCEPTED && decision != CorrectionDecision.ACCEPTED) {
            removeCorrectionFromSRS(correction, lastSessionId)
        }

        val sessionId = lastSessionId ?: return
        persistCorrectionDecisions(evaluation, decisions, sessionId)
    }

    fun predictCorrection(correction: SrsCorrection, predictsError: Boolean) {
        val evaluation = _lastEvaluation.value ?: return
        if (evaluation.evaluationLocked || correction !in evaluation.corrections) return
        val predictions = _correctionPredictions.value +
            (correction.decisionKey() to if (predictsError) "error" else "okay")
        _correctionPredictions.value = predictions
        lastSessionId?.let {
            persistCorrectionDecisions(evaluation, _correctionDecisions.value, it)
        }
    }

    private fun rememberCorrectionDecision(
        correction: SrsCorrection,
        decision: CorrectionDecision
    ) {
        val patternId = correction.patternId.ifBlank {
            if (
                com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                    .isPronunciation(correction.category)
            ) {
                com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.evidenceKey(
                    correction.category,
                    correction.corrected
                )
            } else {
                com.example.medvoicetrainer.analysis.CorrectionPatternId.derive(
                    null,
                    correction.category,
                    correction.original,
                    correction.corrected
                )
            }
        }
        val settingKey = "correction_feedback_memory"
        repository.setSetting(
            settingKey,
            com.example.medvoicetrainer.analysis.CorrectionFeedbackMemory.record(
                repository.getSetting(settingKey, "{}"),
                patternId,
                decision.name.lowercase(Locale.ROOT)
            )
        )
    }

    fun resetCorrectionDecision(correction: SrsCorrection) {
        val evaluation = _lastEvaluation.value ?: return
        if (evaluation.evaluationLocked || correction !in evaluation.corrections) return
        val key = correction.decisionKey()
        val previous = _correctionDecisions.value[key] ?: return
        if (previous == CorrectionDecision.PENDING) return
        if (previous == CorrectionDecision.ACCEPTED) {
            removeCorrectionFromSRS(correction, lastSessionId)
        }
        val decisions = _correctionDecisions.value + (key to CorrectionDecision.PENDING)
        _correctionDecisions.value = decisions
        lastSessionId?.let { persistCorrectionDecisions(evaluation, decisions, it) }
    }

    private fun initializeCorrectionDecisions(evaluation: EvaluationResult, sessionId: Int) {
        if (evaluation.evaluationLocked) {
            _correctionDecisions.value = emptyMap()
            _correctionPredictions.value = emptyMap()
            return
        }
        val autoSave = _correctionAutoSaveEnabled.value
        val decisions = evaluation.corrections.associate { correction ->
            correction.decisionKey() to autoSaveCorrectionDecision(correction, autoSave)
        }
        _correctionDecisions.value = decisions
        _correctionPredictions.value = emptyMap()

        // Automatically saved high-confidence errors must also land in the SRS
        // tracker and feedback memory, exactly as a manual "Save" would have.
        if (autoSave) {
            val accepted = evaluation.corrections.filter {
                decisions[it.decisionKey()] == CorrectionDecision.ACCEPTED
            }
            if (accepted.isNotEmpty()) {
                accepted.forEach { rememberCorrectionDecision(it, CorrectionDecision.ACCEPTED) }
                saveCorrectionsToSRS(accepted, sessionId, evaluation.analysisDomain)
            }
        }

        persistCorrectionDecisions(evaluation, decisions, sessionId)
    }

    /**
     * One-tap bulk save of every still-pending high-confidence error in the current feedback set —
     * the escape hatch from per-item triage fatigue, without lowering the bar for style or
     * pronunciation. Reuses [decideCorrection] so SRS routing, feedback memory, and undo all behave
     * identically to a manual save.
     */
    fun acceptAllHighConfidenceCorrections() {
        val evaluation = _lastEvaluation.value ?: return
        if (evaluation.evaluationLocked) return
        val pending = evaluation.corrections.filter {
            isBulkAcceptableCorrection(it) &&
                (_correctionDecisions.value[it.decisionKey()]
                    ?: CorrectionDecision.PENDING) == CorrectionDecision.PENDING
        }
        pending.forEach { decideCorrection(it, CorrectionDecision.ACCEPTED) }
    }

    /** One category resolver shared by session tracking, exports, SRS save, and SRS undo. */
    private fun refinedL1Category(correction: SrsCorrection): String =
        if (
            com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                .isPronunciation(correction.category)
        ) {
            correction.category
        } else {
            com.example.medvoicetrainer.analysis.L1InterferenceCatalog.classify(
                _nativeLanguage.value,
                correction.original,
                correction.corrected,
                correction.explanation,
                correction.category
            ) ?: correction.category
        }

    private fun persistCorrectionDecisions(
        evaluation: EvaluationResult,
        decisions: Map<String, CorrectionDecision>,
        sessionId: Int,
        predictions: Map<String, String> = _correctionPredictions.value
    ) {
        viewModelScope.launch {
            try {
                val persisted = JSONArray(evaluation.rawCorrectionsJson.ifBlank { "[]" })
                evaluation.corrections.forEachIndexed { index, candidate ->
                    persisted.optJSONObject(index)?.apply {
                        // Dashboard L1Stats reads this persisted session JSON, not the SRS row.
                        // Persist the same evidence-based refinement used by saveCorrectionsToSRS
                        // so an evaluator's blank/"other" tag cannot break tracking downstream.
                        put("category", refinedL1Category(candidate))
                        put(
                            "decision",
                            (decisions[candidate.decisionKey()] ?: CorrectionDecision.PENDING)
                                .name.lowercase(Locale.ROOT)
                        )
                        predictions[candidate.decisionKey()]?.let {
                            put("learner_prediction", it)
                        }
                    }
                }
                repository.updateSessionCorrections(sessionId, persisted.toString())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                // Feedback and SRS remain usable even when audit metadata cannot be persisted.
            }
        }
    }

    // --- Resuming an older session's correction triage from History --------------------------

    private val _historyTriage = MutableStateFlow<HistoryTriageState?>(null)
    val historyTriage = _historyTriage.asStateFlow()

    /**
     * Arm triage for a stored session so History can finish a review that was never completed.
     * The caller passes the [evaluation] and [records] it already reconstructed for rendering —
     * `toEvaluationResult()` recomputes fluency and intelligibility from the transcript, and doing
     * that a second time here would double that work on the main thread for no new information.
     */
    fun beginHistoryTriage(
        sessionId: Int,
        evaluation: EvaluationResult,
        records: List<HistoryCorrectionRecord>
    ) {
        if (evaluation.corrections.isEmpty()) {
            _historyTriage.value = null
            return
        }
        _historyTriage.value = HistoryTriageState(
            sessionId = sessionId,
            evaluation = evaluation,
            decisions = records.associate {
                it.correction.decisionKey() to correctionDecisionOf(it.decision)
            },
            predictions = records.mapNotNull { record ->
                record.learnerPrediction?.let { record.correction.decisionKey() to it }
            }.toMap()
        )
    }

    fun endHistoryTriage() {
        _historyTriage.value = null
    }

    /**
     * Same SRS routing, feedback memory and audit persistence as the live [decideCorrection], but
     * against the History session's own row. When that row happens to be the session that just
     * ended, the live decision map is updated too so the two screens never disagree.
     */
    fun decideHistoryCorrection(correction: SrsCorrection, decision: CorrectionDecision) {
        if (decision == CorrectionDecision.PENDING) return
        val state = _historyTriage.value ?: return
        if (correction !in state.evaluation.corrections) return
        val key = correction.decisionKey()
        val previous = state.decisions[key] ?: CorrectionDecision.PENDING
        if (previous == decision) return

        val decisions = state.decisions + (key to decision)
        _historyTriage.value = state.copy(decisions = decisions)
        rememberCorrectionDecision(correction, decision)
        if (decision == CorrectionDecision.ACCEPTED) {
            saveCorrectionsToSRS(
                listOf(correction),
                state.sessionId,
                state.evaluation.analysisDomain
            )
        } else if (previous == CorrectionDecision.ACCEPTED) {
            removeCorrectionFromSRS(correction, state.sessionId)
        }
        if (state.sessionId == lastSessionId) {
            _correctionDecisions.value = _correctionDecisions.value + (key to decision)
        }
        persistCorrectionDecisions(
            state.evaluation,
            decisions,
            state.sessionId,
            state.predictions
        )
    }

    fun resetHistoryCorrectionDecision(correction: SrsCorrection) {
        val state = _historyTriage.value ?: return
        if (correction !in state.evaluation.corrections) return
        val key = correction.decisionKey()
        val previous = state.decisions[key] ?: return
        if (previous == CorrectionDecision.PENDING) return
        if (previous == CorrectionDecision.ACCEPTED) {
            removeCorrectionFromSRS(correction, state.sessionId)
        }
        val decisions = state.decisions + (key to CorrectionDecision.PENDING)
        _historyTriage.value = state.copy(decisions = decisions)
        if (state.sessionId == lastSessionId) {
            _correctionDecisions.value =
                _correctionDecisions.value + (key to CorrectionDecision.PENDING)
        }
        persistCorrectionDecisions(
            state.evaluation,
            decisions,
            state.sessionId,
            state.predictions
        )
    }

    /** Bulk escape hatch, identical policy to [acceptAllHighConfidenceCorrections]. */
    fun acceptAllHistoryHighConfidenceCorrections() {
        val state = _historyTriage.value ?: return
        state.evaluation.corrections
            .filter {
                isBulkAcceptableCorrection(it) &&
                    (state.decisions[it.decisionKey()]
                        ?: CorrectionDecision.PENDING) == CorrectionDecision.PENDING
            }
            .forEach { decideHistoryCorrection(it, CorrectionDecision.ACCEPTED) }
    }

    fun saveStudentSoapNote(text: String) {
        val id = lastSessionId ?: return
        viewModelScope.launch { repository.saveStudentSoap(id, text.trim()) }
    }

    /**
     * Export the just-completed session's accepted corrections as CSV — readable in any
     * spreadsheet app and still importable by Anki (see AnkiExporter.buildCsv).
     */
    suspend fun exportLastSessionCsv(): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val id = lastSessionId ?: return@withContext null
                val session = repository.getSessionById(id) ?: return@withContext null
                val sessionMap = session.toAnalysisMap().toMutableMap()
                val evaluation = _lastEvaluation.value
                if (evaluation != null) {
                    val decisions = _correctionDecisions.value
                    val audited = JSONArray(evaluation.rawCorrectionsJson.ifBlank { "[]" })
                    evaluation.corrections.forEachIndexed { index, correction ->
                        audited.optJSONObject(index)?.apply {
                            put("category", refinedL1Category(correction))
                            put(
                                "decision",
                                (decisions[correction.decisionKey()] ?: CorrectionDecision.PENDING)
                                    .name.lowercase(Locale.ROOT)
                            )
                            _correctionPredictions.value[correction.decisionKey()]?.let {
                                put("learner_prediction", it)
                            }
                        }
                    }
                    sessionMap["corrections"] = audited.toString()
                }
                val content = com.example.medvoicetrainer.export.AnkiExporter
                    .exportSessionsToCsv(listOf(sessionMap))
                    ?: return@withContext null
                val dir = java.io.File(getApplication<Application>().cacheDir, "shared_cards")
                    .apply { mkdirs() }
                java.io.File(dir, "BedsideEnglish_Cards_$id.csv").also {
                    it.writeText(content, Charsets.UTF_8)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                handleUiException("exporting cards as CSV", e)
                null
            }
        }

    /**
     * §13 edge case ("Analysis fails / quota (429)"): a session whose analysis never completed
     * (still has a blank `summaryFeedback` — see HistoryScreen's `isUnanalyzed` heuristic) is the
     * transcript, safely persisted, with nothing to show for it. Rather than a parallel
     * "re-analyze a detached row" code path, this reloads the saved transcript/case into a fresh
     * [ActiveSessionState] against the *same* session id (so [finishSession] updates the existing
     * row instead of inserting a new one) and immediately re-runs the real, already-tested
     * finishSession() pipeline — no live voice, no new conversation, just a retry of scoring.
     */
    fun retrySessionAnalysis(session: com.example.medvoicetrainer.db.SessionEntity) {
        if (_activeSession.value.isActive) return
        val transcript = mutableListOf<Pair<String, String>>()
        try {
            val turns = JSONArray(session.rawTranscript)
            for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                transcript.add(turn.getString("role") to turn.getString("text"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            handleUiException("reloading this session's transcript", e)
            return
        }
        // Nothing the learner said means nothing to score. Re-finishing it would only strand an
        // active session on finishSession's "say at least one response" error, with no microphone.
        if (transcript.none { it.first == "doctor" && it.second.isNotBlank() }) return
        val sessionGeneration = voiceSessionGeneration.incrementAndGet()
        synchronized(voiceUsageLock) {
            activeVoiceUsageGeneration = sessionGeneration
            activeVoiceUsage = if (session.voiceUsageExact) {
                VoiceApiUsage(
                    provider = session.voiceBackend,
                    model = session.voiceModel.orEmpty(),
                    inputTextTokens = session.voiceInputTextTokens,
                    inputAudioTokens = session.voiceInputAudioTokens,
                    outputTextTokens = session.voiceOutputTextTokens,
                    outputAudioTokens = session.voiceOutputAudioTokens,
                    cachedInputTextTokens = session.voiceCachedInputTextTokens,
                    cachedInputAudioTokens = session.voiceCachedInputAudioTokens,
                    thinkingTokens = session.voiceThinkingTokens,
                )
            } else {
                null
            }
        }
        activeSupportsPreReadyTextQueue = false
        voiceManager?.stop()
        voiceManager = null
        interviewPhaseTracker = null
        wrapUpDetector = null
        activeSessionId = session.id
        activeSessionAudioCapture = SessionAudioCapture(
            generation = sessionGeneration,
            sessionId = session.id,
            initialTranscript = transcript,
            saveLearnerClips = false,
            analyzePronunciation = false,
        ).also { it.clips.putAll(parseLearnerAudioClips(session.rawTranscript)) }
        _lastEvaluation.value = null
        _activeSession.value = ActiveSessionState(
            isActive = true,
            mode = session.mode,
            caseId = session.caseId ?: "",
            caseName = session.caseName,
            caseJson = session.rawCaseJson,
            createdAt = session.createdAt,
            preservedDurationSeconds = session.durationSeconds,
            transcript = transcript
        )
        finishSession()
    }

    // The Home recovery card used to offer a second action, "Resume", which opened a brand-new
    // live session seeded with the saved transcript. It was never a real reconnect (no provider
    // here exposes a resume-from-here signal), and as a recovery action it was the wrong trade:
    // it re-opened a paid voice socket to continue a conversation whose context the learner had
    // already left, hours earlier in the worst case. Recovery is now one action — analyze what was
    // actually said — and starting a fresh conversation is what the Practice tab is for.

    /** Provider-reported voice cost when available, otherwise the per-backend estimate. */
    private fun sessionVoiceCost(
        voiceUsage: VoiceApiUsage?,
        state: ActiveSessionState,
        durationSeconds: Int,
        voiceBackend: String,
    ): Double = voiceUsage?.let(CostTracker::computeVoiceCost) ?: run {
        val patientChars = state.transcript
            .filter { it.first == "patient" || it.first == "interviewer" }
            .sumOf { it.second.length }
        when (voiceBackend.lowercase()) {
            // Live re-bills the whole session context on every turn, so the estimate
            // has to walk the ordered turns rather than scale a character total.
            "gemini" -> CostTracker.estimateGeminiLiveCost(
                turns = state.transcript.map { CostTracker.VoiceTurn(it.first, it.second) },
                durationSeconds = durationSeconds,
                model = getVoiceModelForBackend(voiceBackend),
                systemPromptChars = CostTracker.systemPromptCharsFor(state.caseJson),
            ).costUsd
            "openai" -> CostTracker.computeOpenaiVoiceCost(durationSeconds, patientChars)
            else -> 0.0
        }
    }

    // --- Korean CPX track ---

    private val _lastKmleResult = MutableStateFlow<KmleCpxResult?>(null)
    /** The Korean CPX result currently on screen (just finished, or opened from CPX history). */
    val lastKmleResult = _lastKmleResult.asStateFlow()

    fun dismissKmleResult() {
        _lastKmleResult.value = null
    }

    // --- Korean CPX mock exam (모의고사) ---

    /**
     * A circuit of stations run back to back like exam day: each from a different official
     * clinical presentation, full scope, strict clock, no study aids, and no feedback until the
     * last station — then one summary with an estimated pass verdict.
     */
    data class KmleMockExam(
        val id: String,
        val launches: List<KmleCpxLaunch>,
        /** Stations completed so far, in order (their session row ids). */
        val sessionIds: List<Int> = emptyList(),
    ) {
        val index: Int get() = sessionIds.size
        val finished: Boolean get() = sessionIds.size >= launches.size
        val current: KmleCpxLaunch? get() = launches.getOrNull(index)
    }

    private val _kmleMock = MutableStateFlow<KmleMockExam?>(null)
    val kmleMock = _kmleMock.asStateFlow()
    private val _kmleMockPreparing = MutableStateFlow(false)
    val kmleMockPreparing = _kmleMockPreparing.asStateFlow()

    /** True while a circuit is in progress (not yet at its summary). */
    val kmleMockRunning: Boolean get() = _kmleMock.value?.finished == false

    fun startKmleMockExam(stationCount: Int, onNothingPrepared: () -> Unit = {}) {
        if (_kmleMockPreparing.value) return
        _kmleMockPreparing.value = true
        viewModelScope.launch {
            try {
                val kmle = com.example.medvoicetrainer.analysis.KmleCpx
                val examId = "mock_" + System.currentTimeMillis()
                val byItem = loadKmlePresentations().filter { it.kmleItem.isNotBlank() }.groupBy { it.kmleItem }
                val launches = mutableListOf<KmleCpxLaunch>()
                for (item in byItem.keys.shuffled()) {
                    if (launches.size >= stationCount) break
                    val station = byItem.getValue(item).random()
                    val launch = prepareKmleCpxSession(station.id) ?: continue
                    val scoped = kmle.withScope(launch.caseJson, kmle.SCOPE_FULL)
                    launches += launch.copy(
                        caseJson = kmle.withMockExam(scoped, examId, launches.size),
                        title = "모의고사 ${launches.size + 1}번 · ${launch.title}",
                        scope = kmle.SCOPE_FULL,
                    )
                }
                _kmleMock.value = if (launches.isEmpty()) null else KmleMockExam(examId, launches)
                if (launches.isEmpty()) onNothingPrepared()
            } finally {
                _kmleMockPreparing.value = false
            }
        }
    }

    fun abandonKmleMockExam() {
        _kmleMock.value = null
    }

    /** Called when a mock-exam station's session is stored; returns true if it belonged to the running circuit. */
    private fun recordKmleMockStation(caseJson: String, sessionId: Int): Boolean {
        val mock = _kmleMock.value ?: return false
        if (mock.finished || com.example.medvoicetrainer.analysis.KmleCpx.mockExamId(caseJson) != mock.id) return false
        _kmleMock.value = mock.copy(sessionIds = mock.sessionIds + sessionId)
        return true
    }

    // --- Korean CPX peer mode (친구와 역할극) ---

    private val _kmlePeerLaunch = MutableStateFlow<KmleCpxLaunch?>(null)
    /** The station two students are role-playing in person, while its screen is open. */
    val kmlePeerLaunch = _kmlePeerLaunch.asStateFlow()

    /** "transcribing" / "grading" while a recording is being processed, null otherwise. */
    private val _kmlePeerStage = MutableStateFlow<String?>(null)
    val kmlePeerStage = _kmlePeerStage.asStateFlow()
    private val _kmlePeerError = MutableStateFlow<String?>(null)
    val kmlePeerError = _kmlePeerError.asStateFlow()

    fun openKmlePeer(launch: KmleCpxLaunch) {
        _kmlePeerError.value = null
        _kmlePeerLaunch.value = launch.copy(
            caseJson = com.example.medvoicetrainer.analysis.KmlePeer.markPeer(launch.caseJson),
            title = "[친구와] ${launch.title}",
        )
    }

    fun closeKmlePeer() {
        if (_kmlePeerStage.value != null) return
        _kmlePeerLaunch.value = null
        _kmlePeerError.value = null
    }

    /**
     * Transcribe a recorded role-play with speaker labels (Gemini, audio in), then grade it with
     * the station's normal checklist grader and store it like any CPX session. The recording is
     * deleted only after the session row exists, so a failure can be retried from the same file.
     */
    fun gradeKmlePeerRecording(audio: java.io.File, durationSeconds: Int) {
        val launch = _kmlePeerLaunch.value ?: return
        if (_kmlePeerStage.value != null) return
        viewModelScope.launch {
            _kmlePeerError.value = null
            _kmlePeerStage.value = "transcribing"
            try {
                val kmle = com.example.medvoicetrainer.analysis.KmleCpx
                val peer = com.example.medvoicetrainer.analysis.KmlePeer
                val session = kmle.sessionCase(launch.caseJson)
                    ?: throw IllegalStateException("이 스테이션의 채점표를 읽지 못했어요. 스테이션을 다시 골라 주세요.")
                val geminiKey = repository.getGeminiApiKey()
                if (geminiKey.isBlank()) {
                    throw IllegalStateException("녹음을 받아 적으려면 Gemini API 키가 필요해요. 설정에서 무료 Gemini 키를 넣어 주세요.")
                }
                val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
                val rawTranscript = com.example.medvoicetrainer.api.GeminiService.generateContentWithAudioParts(
                    apiKey = geminiKey,
                    model = getModelForBackend("gemini"),
                    audioParts = listOf(com.example.medvoicetrainer.api.GeminiService.InlineAudioPart(bytes, peer.AUDIO_MIME)),
                    prompt = peer.transcriptionPrompt(session),
                    responseSchema = peer.transcriptionSchema(),
                    longForm = true,
                )
                val turns = peer.parseTranscript(rawTranscript)
                if (turns.none { it.first == "doctor" }) {
                    throw IllegalStateException("녹음에서 학생의사의 말을 찾지 못했어요. 휴대폰을 두 사람 사이에 두고 다시 녹음해 주세요.")
                }

                _kmlePeerStage.value = "grading"
                var backend = _analysisBackend.value
                var key = getApiKeyForBackend(backend)
                if (key.isBlank()) {
                    backend = "gemini"
                    key = geminiKey
                }
                val model = getModelForBackend(backend)
                val (system, user) = kmle.buildGradingPrompts(session, turns)
                val (rawEval, usage) = com.example.medvoicetrainer.analysis.AnalysisEngine.evaluatePromptsWithUsage(
                    backend = backend, apiKey = key, model = model, systemPrompt = system, userPrompt = user,
                )
                val root = com.example.medvoicetrainer.analysis.KmleCpxScorecard.parseModelJson(rawEval)
                    ?: throw IllegalStateException("채점 결과를 읽지 못했어요. 다시 채점해 주세요.")
                val card = com.example.medvoicetrainer.analysis.KmleCpxScorecard.build(session, root)
                val analysisCost = CostTracker.computeAnalysisCost(
                    backend, usage.inputTokens, usage.outputTokens, usage.cachedTokens, usage.modelUsed.ifBlank { model },
                )
                val transcriptJson = JSONArray().apply {
                    turns.forEach { (role, text) -> put(JSONObject().put("role", role).put("text", text)) }
                }.toString()
                val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                val entity = SessionEntity(
                    createdAt = createdAt,
                    mode = kmle.SESSION_MODE,
                    analysisDomain = kmle.ANALYSIS_DOMAIN,
                    caseName = launch.title,
                    caseId = launch.caseId,
                    evalTemplate = kmle.SESSION_MODE,
                    voiceBackend = "peer",
                    voiceModel = null,
                    analysisModel = usage.modelUsed.ifBlank { model },
                    rawCaseJson = launch.caseJson,
                    rawTranscript = transcriptJson,
                    learnerTurnCount = turns.count { it.first == "doctor" },
                    durationSeconds = durationSeconds,
                    rawEvalJson = root.toString(),
                    summaryFeedback = card.summary.ifBlank { card.overall?.let { "CPX ${it}점" } ?: "채점됨" },
                    claudeInputTokens = usage.inputTokens,
                    claudeOutputTokens = usage.outputTokens,
                    claudeCachedTokens = usage.cachedTokens,
                    claudeCostUsd = analysisCost,
                    totalCostUsd = analysisCost,
                    costEstimated = false,
                    endReason = SessionEndReason.COMPLETED,
                )
                val id = repository.insertSession(entity).toInt()
                withContext(Dispatchers.IO) { audio.delete() }
                _kmlePeerLaunch.value = null
                _lastKmleResult.value = KmleCpxResult(
                    sessionId = id,
                    caseName = launch.title,
                    createdAt = createdAt,
                    scorecard = card,
                    transcript = turns,
                    analyzed = true,
                    examReview = kmle.examReview(session, turns),
                )
                com.example.medvoicetrainer.analysis.Telemetry.track(
                    "kmle_cpx_peer_completed",
                    mapOf("presentation" to session.presentation.id, "turns" to turns.size),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _kmlePeerError.value = e.message ?: "채점하지 못했어요. 다시 시도해 주세요."
            } finally {
                _kmlePeerStage.value = null
            }
        }
    }

    @Volatile private var kmlePresentationCache: List<com.example.medvoicetrainer.analysis.KmleCpx.Presentation>? = null
    @Volatile private var caseAssetPathCache: Map<String, String>? = null

    /** Every CPX presentation shipped in `data/kmle_cpx/presentations/`, by category then title. */
    suspend fun loadKmlePresentations(): List<com.example.medvoicetrainer.analysis.KmleCpx.Presentation> =
        withContext(Dispatchers.IO) {
            if (!kmlePrefsLoaded) readKmlePrefs()
            kmlePresentationCache?.let { return@withContext it }
            val kmle = com.example.medvoicetrainer.analysis.KmleCpx
            val loaded = repository.listAssetFiles(kmle.PRESENTATION_DIR)
                .filter { it.endsWith(".json") }
                .mapNotNull { name -> kmle.parsePresentation(repository.loadAssetFile("${kmle.PRESENTATION_DIR}/$name")) }
                .sortedWith(compareBy({ it.category }, { it.title }))
            kmlePresentationCache = loaded
            loaded
        }

    @Volatile private var kmleCommonCache: com.example.medvoicetrainer.analysis.KmleCpx.Common? = null

    /** The shared rubric (`data/kmle_cpx/common.json`), for the study sheets. */
    suspend fun loadKmleCommon(): com.example.medvoicetrainer.analysis.KmleCpx.Common = withContext(Dispatchers.IO) {
        kmleCommonCache ?: com.example.medvoicetrainer.analysis.KmleCpx.parseCommon(
            repository.loadAssetFile(com.example.medvoicetrainer.analysis.KmleCpx.COMMON_ASSET)
        ).also { kmleCommonCache = it }
    }

    /** The nine 기본진료술기 checklists (`data/kmle_cpx/osce/`), by group then title. */
    suspend fun loadKmleOsceSkills(): List<com.example.medvoicetrainer.analysis.KmleOsce.Skill> = withContext(Dispatchers.IO) {
        val osce = com.example.medvoicetrainer.analysis.KmleOsce
        repository.listAssetFiles(osce.DIR)
            .filter { it.endsWith(".json") }
            .mapNotNull { osce.parse(repository.loadAssetFile("${osce.DIR}/$it")) }
            .sortedWith(compareBy({ it.group }, { it.title }))
    }

    /** Case id → bundled asset path, from the build-generated catalog. */
    private fun caseAssetPaths(): Map<String, String> {
        caseAssetPathCache?.let { return it }
        val map = runCatching {
            val array = JSONArray(repository.loadAssetFile("case_catalog.json"))
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id")
                val path = row.optString("asset_path")
                if (id.isBlank() || path.isBlank()) null else id to path
            }.toMap()
        }.getOrDefault(emptyMap())
        caseAssetPathCache = map
        return map
    }

    data class KmleCpxLaunch(
        val caseId: String,
        val title: String,
        val caseJson: String,
        val doorNote: String,
        val scope: String = com.example.medvoicetrainer.analysis.KmleCpx.SCOPE_FULL,
    ) {
        /** The problem sheet for this launch, as the session will store it. */
        val situationCard: com.example.medvoicetrainer.analysis.KmleCpx.SituationCard?
            get() = com.example.medvoicetrainer.analysis.KmleCpx.sessionCase(caseJson)?.situationCard
    }

    /**
     * The learner's CPX practice settings: how far each station goes, whether the sheet shows the
     * complaint (school-practice style; the national exam dropped it in 2026), whether the station
     * clock ends the session at time like the exam, and whether performed examinations show their
     * findings.
     */
    data class KmlePrefs(
        val scope: String = com.example.medvoicetrainer.analysis.KmleCpx.SCOPE_FULL,
        val showComplaint: Boolean = false,
        val strictTimer: Boolean = false,
        val showFindings: Boolean = true,
        /** Beginner aid: the station's checklist, with model lines, in the in-station sheet. */
        val showChecklistHint: Boolean = false,
    )

    private val _kmlePrefs = MutableStateFlow(KmlePrefs())
    val kmlePrefs = _kmlePrefs.asStateFlow()
    @Volatile private var kmlePrefsLoaded = false

    private fun readKmlePrefs(): KmlePrefs {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        return KmlePrefs(
            scope = kmle.normalizeScope(repository.getSetting("kmle_cpx_scope", kmle.SCOPE_FULL)),
            showComplaint = repository.getSetting("kmle_cpx_show_complaint", "false") == "true",
            strictTimer = repository.getSetting("kmle_cpx_strict_timer", "false") == "true",
            showFindings = repository.getSetting("kmle_cpx_show_findings", "true") == "true",
            showChecklistHint = repository.getSetting("kmle_cpx_show_hint", "false") == "true",
        ).also {
            _kmlePrefs.value = it
            kmlePrefsLoaded = true
        }
    }

    private fun currentKmlePrefs(): KmlePrefs = if (kmlePrefsLoaded) _kmlePrefs.value else readKmlePrefs()

    fun updateKmlePrefs(prefs: KmlePrefs) {
        _kmlePrefs.value = prefs.copy(scope = com.example.medvoicetrainer.analysis.KmleCpx.normalizeScope(prefs.scope))
        kmlePrefsLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSetting("kmle_cpx_scope", _kmlePrefs.value.scope)
            repository.setSetting("kmle_cpx_show_complaint", prefs.showComplaint.toString())
            repository.setSetting("kmle_cpx_strict_timer", prefs.strictTimer.toString())
            repository.setSetting("kmle_cpx_show_findings", prefs.showFindings.toString())
            repository.setSetting("kmle_cpx_show_hint", prefs.showChecklistHint.toString())
        }
    }

    /** The same launch with another practice scope (and that scope remembered for next time). */
    fun rescopeKmleLaunch(launch: KmleCpxLaunch, scope: String): KmleCpxLaunch {
        updateKmlePrefs(_kmlePrefs.value.copy(scope = scope))
        return launch.copy(
            caseJson = com.example.medvoicetrainer.analysis.KmleCpx.withScope(launch.caseJson, scope),
            scope = com.example.medvoicetrainer.analysis.KmleCpx.normalizeScope(scope),
        )
    }

    /** One row of the CPX "all cases" browser. Deliberately carries no diagnosis. */
    data class KmleCaseRow(
        val caseId: String,
        val system: String,
        val age: Int?,
        val gender: String,
        val difficulty: String,
        val doorComplaint: String,
        val presentationId: String,
        val presentationTitle: String,
    )

    @Volatile private var kmleCaseIndexCache: Map<String, com.example.medvoicetrainer.analysis.KmleCpx.IndexEntry>? = null

    private fun kmleCaseIndex(): Map<String, com.example.medvoicetrainer.analysis.KmleCpx.IndexEntry> {
        kmleCaseIndexCache?.let { return it }
        val index = com.example.medvoicetrainer.analysis.KmleCpx.parseCaseIndex(
            repository.loadAssetFile(com.example.medvoicetrainer.analysis.KmleCpx.CASE_INDEX_ASSET)
        )
        kmleCaseIndexCache = index
        return index
    }

    /** Every case a presentation can play: the ones its file lists plus every case the index files under it. */
    suspend fun kmlePoolSizes(): Map<String, Int> = withContext(Dispatchers.IO) {
        val index = kmleCaseIndex()
        val paths = caseAssetPaths()
        loadKmlePresentations().associate { p ->
            p.id to com.example.medvoicetrainer.analysis.KmleCpx.casePool(p, index).count { it in paths }
        }
    }

    /**
     * Pick a case for [presentationId] and compose its Korean CPX session case. Cases the learner
     * has not met yet for this presentation come first, so repeating a station rotates through
     * its patients before any repeats. Null when the presentation or all its cases are missing.
     */
    suspend fun prepareKmleCpxSession(presentationId: String): KmleCpxLaunch? = withContext(Dispatchers.IO) {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        val presentationText = repository.loadAssetFile("${kmle.PRESENTATION_DIR}/$presentationId.json")
        val presentation = kmle.parsePresentation(presentationText) ?: return@withContext null
        val index = kmleCaseIndex()
        val paths = caseAssetPaths()
        val candidates = kmle.casePool(presentation, index).filter { it in paths }
        if (candidates.isEmpty()) return@withContext null
        val playedKey = "kmle_cpx_played_$presentationId"
        val played = repository.getSetting(playedKey, "").split(',').filter { it.isNotBlank() }.toSet()
        val fresh = candidates.filterNot { it in played }
        val caseId = (fresh.ifEmpty { candidates }).random()
        val nextPlayed = if (fresh.isEmpty()) setOf(caseId) else played + caseId
        repository.setSetting(playedKey, nextPlayed.joinToString(","))
        // A case a station lists itself may be filed elsewhere in the index (its lead complaint
        // fits another station better); its index door line then describes that other complaint.
        val entry = index[caseId]?.takeIf { it.presentationId == presentation.id }
        composeKmleLaunch(presentationText, presentation, caseId, entry?.doorComplaint.orEmpty(), entry?.speaker.orEmpty())
    }

    /** Compose a CPX session for one specific case, at the station the case index files it under. */
    suspend fun prepareKmleCpxCase(caseId: String): KmleCpxLaunch? = withContext(Dispatchers.IO) {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        val entry = kmleCaseIndex()[caseId]
        val presentationId = entry?.presentationId ?: kmle.GENERAL_PRESENTATION
        val presentationText = repository.loadAssetFile("${kmle.PRESENTATION_DIR}/$presentationId.json")
        val presentation = kmle.parsePresentation(presentationText) ?: return@withContext null
        composeKmleLaunch(presentationText, presentation, caseId, entry?.doorComplaint.orEmpty(), entry?.speaker.orEmpty())
    }

    private fun composeKmleLaunch(
        presentationText: String,
        presentation: com.example.medvoicetrainer.analysis.KmleCpx.Presentation,
        caseId: String,
        doorComplaint: String,
        speaker: String,
    ): KmleCpxLaunch? {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        val path = caseAssetPaths()[caseId] ?: return null
        val base = repository.loadAssetFile(path)
        if (base.isBlank()) return null
        val caseObj = runCatching { JSONObject(base) }.getOrNull() ?: return null
        val age = kmle.ageOf(caseObj)
        val gender = kmle.genderOf(caseObj)
        val scope = currentKmlePrefs().scope
        return KmleCpxLaunch(
            caseId = caseId,
            title = kmle.sessionTitle(presentation, age, gender, doorComplaint),
            caseJson = kmle.composeCaseJson(
                base, presentationText, repository.loadAssetFile(kmle.COMMON_ASSET), doorComplaint, speaker,
                scope = scope,
                maneuversJson = repository.loadAssetFile(com.example.medvoicetrainer.analysis.SpScript.MANEUVER_ASSET),
            ),
            doorNote = kmle.doorNote(presentation, age, gender, doorComplaint, kmle.isChild(age) || speaker == kmle.SPEAKER_GUARDIAN),
            scope = scope,
        )
    }

    /**
     * Every case in the CPX index, for the "all cases" browser, in body-system then id order.
     * Age, sex and difficulty come from the build-generated catalog, so no case file is opened.
     */
    suspend fun loadKmleCaseRows(): List<KmleCaseRow> = withContext(Dispatchers.IO) {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        val titles = loadKmlePresentations().associate { it.id to it.title }
        val catalog = runCatching {
            val array = JSONArray(repository.loadAssetFile("case_catalog.json"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.associateBy { it.optString("id") }
        }.getOrDefault(emptyMap())
        kmleCaseIndex().values.mapNotNull { entry ->
            val row = catalog[entry.caseId] ?: return@mapNotNull null
            KmleCaseRow(
                caseId = entry.caseId,
                system = row.optString("group"),
                age = row.optString("age").trim().toDoubleOrNull()?.toInt(),
                gender = when {
                    row.optString("gender").trim().lowercase().startsWith("f") -> "female"
                    row.optString("gender").trim().lowercase().startsWith("m") -> "male"
                    else -> ""
                },
                difficulty = row.optString("difficulty").lowercase(),
                doorComplaint = entry.doorComplaint,
                presentationId = entry.presentationId,
                presentationTitle = titles[entry.presentationId] ?: "기타 증상",
            )
        }.sortedWith(compareBy({ it.system }, { it.caseId }))
    }

    /** Re-open a stored CPX session's card; the scorecard is rebuilt from the stored case and reply. */
    fun openKmleResult(session: com.example.medvoicetrainer.db.SessionEntity) {
        val card = com.example.medvoicetrainer.analysis.KmleCpxScorecard.build(session.rawCaseJson, session.rawEvalJson)
            ?: return
        val turns = parseTranscriptTurns(session.rawTranscript)
        _lastKmleResult.value = KmleCpxResult(
            sessionId = session.id,
            caseName = session.caseName,
            createdAt = session.createdAt,
            scorecard = card,
            transcript = turns,
            analyzed = !card.locked,
            examReview = com.example.medvoicetrainer.analysis.KmleCpx.sessionCase(session.rawCaseJson)
                ?.let { com.example.medvoicetrainer.analysis.KmleCpx.examReview(it, turns) }.orEmpty(),
        )
    }

    /**
     * The learner disputes one checklist verdict (speech recognition garbled a question they did
     * ask, or the grader missed it). The correction is stored beside the grader's reply and the
     * card is rebuilt, so every score, here and in History, reflects it.
     */
    fun overrideKmleItem(sessionId: Int, key: String, status: String) {
        viewModelScope.launch {
            try {
                val scorecards = com.example.medvoicetrainer.analysis.KmleCpxScorecard
                val session = withContext(Dispatchers.IO) { repository.getSessionById(sessionId) } ?: return@launch
                val graderOnly = session.rawEvalJson?.let { raw ->
                    scorecards.parseModelJson(raw)?.apply { remove(scorecards.OVERRIDES_KEY) }?.toString()
                }
                val graderStatus = scorecards.build(session.rawCaseJson, graderOnly)
                    ?.sections?.flatMap { it.items }?.firstOrNull { it.key == key }?.status
                val updated = scorecards.withOverride(session.rawEvalJson, key, status, graderStatus) ?: return@launch
                val card = scorecards.build(session.rawCaseJson, updated)
                withContext(Dispatchers.IO) { repository.updateSession(session.copy(rawEvalJson = updated)) }
                val current = _lastKmleResult.value
                if (current?.sessionId == sessionId && card != null) _lastKmleResult.value = current.copy(scorecard = card)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                handleUiException("saving your checklist correction", e)
            }
        }
    }

    fun deleteKmleSession(sessionId: Int) {
        viewModelScope.launch {
            repository.softDeleteSession(sessionId)
            if (_lastKmleResult.value?.sessionId == sessionId) _lastKmleResult.value = null
        }
    }

    private fun parseTranscriptTurns(rawTranscript: String): List<Pair<String, String>> = runCatching {
        val turns = JSONArray(rawTranscript)
        (0 until turns.length()).mapNotNull { i ->
            val turn = turns.optJSONObject(i) ?: return@mapNotNull null
            turn.optString("role") to turn.optString("text")
        }
    }.getOrDefault(emptyList())

    /**
     * Korean CPX completion: grade the transcript against the checklist embedded in the session's
     * case snapshot, store it, and show the Korean card. Everything English-specific in
     * [finishSession] (rubric context, pronunciation, fluency, corrections, SRS, commitments,
     * milestones) is skipped. Throws on a failed or unreadable grading call so the caller marks
     * the row ANALYSIS_FAILED and it can be re-graded from CPX history.
     */
    private suspend fun completeKmleCpxSession(
        state: ActiveSessionState,
        transcriptJson: String,
        durationSeconds: Int,
        analysisBackend: String,
        analysisApiKey: String,
        analysisModel: String,
        voiceUsage: VoiceApiUsage?,
        finishingVoiceBackend: String,
        finishingDraftId: Int?,
        finishingGeneration: Long,
        audioCapture: SessionAudioCapture?,
    ) {
        val kmle = com.example.medvoicetrainer.analysis.KmleCpx
        val scorecards = com.example.medvoicetrainer.analysis.KmleCpxScorecard
        val session = kmle.sessionCase(state.caseJson)
            ?: throw IllegalStateException("This CPX session is missing its checklist. Start the station again from the CPX screen.")

        var analysisUsage: LlmUsage? = null
        // A mock-exam station must not stall the circuit on a grading failure: it is stored
        // ungraded (re-gradable from 기록) and the learner moves on to the next room.
        val inMockCircuit = kmle.mockExamId(state.caseJson).let { it.isNotEmpty() && it == _kmleMock.value?.id }
        val (rawEval, scorecard) = if (analysisApiKey.isBlank()) {
            scorecards.lockedPlaceholder() to scorecards.build(session, null)
        } else try {
            val (system, user) = kmle.buildGradingPrompts(session, state.transcript)
            val (raw, usage) = AnalysisEngine.evaluatePromptsWithUsage(
                backend = analysisBackend,
                apiKey = analysisApiKey,
                model = analysisModel,
                systemPrompt = system,
                userPrompt = user,
            )
            analysisUsage = usage
            val root = scorecards.parseModelJson(raw)
                ?: throw Exception("The grader's reply could not be read. Your transcript is saved; retry grading from the CPX history.")
            root.toString() to scorecards.build(session, root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!inMockCircuit) throw e
            scorecards.lockedPlaceholder() to scorecards.build(session, null)
        }

        val analysisCost = analysisUsage?.let {
            CostTracker.computeAnalysisCost(
                analysisBackend,
                it.inputTokens,
                it.outputTokens,
                it.cachedTokens,
                it.modelUsed.ifBlank { analysisModel },
            )
        } ?: 0.0
        val voiceCost = sessionVoiceCost(voiceUsage, state, durationSeconds, finishingVoiceBackend)
        val completed = SessionEntity(
            id = finishingDraftId ?: 0,
            createdAt = state.createdAt,
            mode = kmle.SESSION_MODE,
            analysisDomain = kmle.ANALYSIS_DOMAIN,
            caseName = state.caseName,
            caseId = state.caseId,
            evalTemplate = kmle.SESSION_MODE,
            voiceBackend = finishingVoiceBackend,
            voiceModel = voiceUsage?.model ?: getVoiceModelForBackend(finishingVoiceBackend),
            analysisModel = analysisUsage?.modelUsed ?: analysisModel,
            rawCaseJson = state.caseJson.ifBlank { "{}" },
            rawTranscript = transcriptJson,
            learnerTurnCount = state.transcript.count { it.first == "doctor" && it.second.isNotBlank() },
            durationSeconds = durationSeconds,
            rawEvalJson = rawEval,
            // FeedbackCompletionWorker treats a blank summary as "still analysing", so never store one.
            summaryFeedback = scorecard.summary.ifBlank {
                scorecard.overall?.let { "CPX ${it}점" } ?: "채점되지 않음 (분석용 API 키 필요)"
            },
            claudeInputTokens = analysisUsage?.inputTokens ?: 0,
            claudeOutputTokens = analysisUsage?.outputTokens ?: 0,
            claudeCachedTokens = analysisUsage?.cachedTokens ?: 0,
            claudeCostUsd = analysisCost,
            voiceCostUsd = voiceCost,
            totalCostUsd = analysisCost + voiceCost,
            voiceInputTextTokens = voiceUsage?.inputTextTokens ?: 0,
            voiceInputAudioTokens = voiceUsage?.inputAudioTokens ?: 0,
            voiceOutputTextTokens = voiceUsage?.outputTextTokens ?: 0,
            voiceOutputAudioTokens = voiceUsage?.outputAudioTokens ?: 0,
            voiceCachedInputTextTokens = voiceUsage?.cachedInputTextTokens ?: 0,
            voiceCachedInputAudioTokens = voiceUsage?.cachedInputAudioTokens ?: 0,
            voiceThinkingTokens = voiceUsage?.thinkingTokens ?: 0,
            voiceUsageExact = voiceUsage != null,
            costEstimated = voiceUsage == null && finishingVoiceBackend in setOf("gemini", "openai"),
            endReason = SessionEndReason.COMPLETED,
        )
        val completedSessionId = if (finishingDraftId != null) {
            repository.updateSession(completed)
            finishingDraftId
        } else {
            repository.insertSession(completed).toInt()
        }

        val stillOwnsUi = SessionCompletionOwnership.stillOwnsUi(finishingGeneration, voiceSessionGeneration.get())
        val isDetachedAnalysis = detachedAnalysisGenerations.contains(finishingGeneration)
        if (stillOwnsUi && activeSessionId == finishingDraftId) activeSessionId = null
        audioCapture?.let { pronunciationAudioStore.cleanupSession(it.sessionId) }
        // A mock-exam station moves on to the next room; its card waits for the circuit summary.
        val mockStation = recordKmleMockStation(state.caseJson, completedSessionId)
        if (stillOwnsUi && !isDetachedAnalysis && mockStation) {
            if (activeSessionAudioCapture?.generation == finishingGeneration) activeSessionAudioCapture = null
            _activeSession.value = ActiveSessionState()
        } else if (stillOwnsUi && !isDetachedAnalysis) {
            if (activeSessionAudioCapture?.generation == finishingGeneration) activeSessionAudioCapture = null
            _lastKmleResult.value = KmleCpxResult(
                sessionId = completedSessionId,
                caseName = state.caseName,
                createdAt = state.createdAt,
                scorecard = scorecard,
                transcript = state.transcript,
                analyzed = analysisApiKey.isNotBlank(),
                examReview = kmle.examReview(session, state.transcript),
            )
            _activeSession.value = ActiveSessionState()
        }
        if (isDetachedAnalysis) {
            detachedAnalysisGenerations.remove(finishingGeneration)
            finishingDraftId?.let { id -> _backgroundAnalysisSessionIds.value = _backgroundAnalysisSessionIds.value - id }
            _feedbackReadyEvent.value = FeedbackReadyEvent(completedSessionId, state.caseName)
        }
        com.example.medvoicetrainer.analysis.Telemetry.track(
            "kmle_cpx_completed",
            mapOf("presentation" to session.presentation.id, "graded" to analysisApiKey.isNotBlank()),
        )
    }

    // --- Session Completion & Rubric Evaluation ---
    fun finishSession() {
        val initialState = _activeSession.value
        if (!initialState.isActive) return
        // One run per finish: a second tap (or a KMLE time-up racing a manual finish) while the
        // first run is still working must not start another. Only a failed run may be retried.
        if (initialState.isFinishing && initialState.error == null) return
        if (initialState.transcript.none { it.first == "doctor" && it.second.isNotBlank() }) {
            _activeSession.value = initialState.copy(
                error = "Say or type at least one response before finishing the session."
            )
            return
        }

        val manager = voiceManager
        // Everything below may outlive this screen. A learner can start another practice while
        // this evaluation is still in flight, so the completion must keep using the row/state it
        // started with and may only publish UI state while it still owns the active generation.
        val finishingGeneration = voiceSessionGeneration.get()
        val finishingDraftId = activeSessionId
        val finishingVoiceBackend = _voiceBackend.value
        val finishingAudioCapture = activeSessionAudioCapture?.takeIf {
            it.generation == finishingGeneration
        }
        val voiceUsageBeforeDrain = synchronized(voiceUsageLock) {
            activeVoiceUsage?.takeIf { activeVoiceUsageGeneration == finishingGeneration }
        }
        // The socket is about to close, so an outstanding beta proposal has nowhere to go.
        clearSceneTransitionState()
        _activeSession.update { current ->
            if (!current.isActive) current
            else current.copy(
                isAILoading = true,
                isFinishing = true,
                status = "Finishing the final turn…",
                error = null,
                analysisStage = FeedbackAnalysisStage.PREPARING,
            )
        }

        finishingJob = viewModelScope.launch {
            // Declared out here so the failure path below can cancel a pronunciation pass that is
            // still in flight: once scoring has failed there is no evaluation to merge it into, and
            // letting it run on only spends the learner's audio quota on a discarded result.
            var pronunciationDeferred: Deferred<com.example.medvoicetrainer.analysis.PronunciationAnalysisResult>? = null
            try {
                // PC waits briefly after closing the mic so the provider's final transcription
                // event is not lost. VoiceManager keeps the socket alive during this drain.
                manager?.stopAndDrain()
                if (voiceManager === manager) voiceManager = null
                if (SessionCompletionOwnership.stillOwnsUi(
                        finishingGeneration,
                        voiceSessionGeneration.get(),
                    )
                ) {
                    VoiceSessionService.stop(getApplication())
                }
                val voiceUsage = synchronized(voiceUsageLock) {
                    activeVoiceUsage?.takeIf {
                        activeVoiceUsageGeneration == finishingGeneration
                    }
                } ?: voiceUsageBeforeDrain
                // Take the drained transcript only if no replacement session has claimed the
                // ViewModel. Otherwise the immutable snapshot belongs to this analysis; reading
                // _activeSession here would analyze/persist the replacement conversation.
                val drainedState = if (SessionCompletionOwnership.stillOwnsUi(
                        finishingGeneration,
                        voiceSessionGeneration.get(),
                    )
                ) {
                    _activeSession.value
                } else {
                    initialState
                }
                // Re-evaluate once from the drained transcript. A final provider transcript event
                // can arrive after the previous live tick, and History must still persist the same
                // used/unused set that immediate feedback reports.
                val finalPhrasebookUsage = evaluatePhrasebookUsage(drainedState.transcript)
                val state = drainedState.copy(
                    phrasebookUsage = finalPhrasebookUsage,
                    caseJson = com.example.medvoicetrainer.analysis.EverydayPhrasebook.withSessionUsage(
                        drainedState.caseJson,
                        finalPhrasebookUsage.filter { it.isMet }.map { it.objective },
                    ),
                )
                val audioCapture = finishingAudioCapture
                awaitLearnerAudioSaves(audioCapture)

                // Formatting raw transcript JSON
                val transcriptJson = serializeTranscript(state.transcript, audioCapture?.clips.orEmpty())

                // Ported from session_base.py's "Feature 2: objective fluency metrics" step:
                // computed deterministically from the transcript itself, independent of whatever
                // the analysis LLM reports. Previously this app stored (110..135).random()/
                // (1..5).random() here instead — every real session's wpm/fillerRate in the DB
                // was fabricated noise, not a measurement. FluencyMetrics/Intelligibility expect
                // Python's "user"/"model" transcript role convention, not this app's
                // "doctor"/"patient" display convention, so remap via SessionMapper.kt.
                val durationSeconds = state.preservedDurationSeconds ?: sessionDurationSeconds(state)
                val pythonRoleTranscriptJson = state.transcript.toPythonRoleTranscriptJson()
                val fluencyMetrics = FluencyMetrics.computeFluencyMetrics(pythonRoleTranscriptJson)
                    ?.let { FluencyMetrics.addWpm(it, durationSeconds.toDouble()) }

                var analysisBackendVal = _analysisBackend.value
                var analysisApiKey = getApiKeyForBackend(analysisBackendVal)
                // Match the PC fallback contract: if an optional Claude/OpenAI analysis key is
                // missing but Gemini is configured, evaluate with Gemini instead of inventing a
                // score or silently failing.
                if (analysisApiKey.isBlank() && analysisBackendVal != "gemini") {
                    val geminiFallbackKey = getApiKeyForBackend("gemini")
                    if (geminiFallbackKey.isNotBlank()) {
                        analysisBackendVal = "gemini"
                        analysisApiKey = geminiFallbackKey
                    }
                }
                val analysisModel = getModelForBackend(analysisBackendVal)

                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() == finishingGeneration && current.isFinishing) {
                        current.copy(analysisStage = FeedbackAnalysisStage.SCORING)
                    } else current
                }

                // Korean CPX sessions have their own Korean grader and card; none of the English
                // steps below (rubric context, pronunciation, corrections, SRS) apply to them.
                if (state.mode == com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE) {
                    completeKmleCpxSession(
                        state = state,
                        transcriptJson = transcriptJson,
                        durationSeconds = durationSeconds,
                        analysisBackend = analysisBackendVal,
                        analysisApiKey = analysisApiKey,
                        analysisModel = analysisModel,
                        voiceUsage = voiceUsage,
                        finishingVoiceBackend = finishingVoiceBackend,
                        finishingDraftId = finishingDraftId,
                        finishingGeneration = finishingGeneration,
                        audioCapture = audioCapture,
                    )
                    return@launch
                }

                // Ported from app/analysis/prompt_builder.py's build_analysis_prompt: a
                // survival/lounge session must be scored on the everyday rubric, never the
                // clinical one. Inferred from the case JSON snapshotted at session start plus
                // the session mode, matching ScoreDomains.inferAnalysisDomain's Python counterpart
                // (app/analysis/score_domains.py's infer_analysis_domain).
                val caseDataMap = jsonObjectToMap(state.caseJson)
                val analysisDomain = ScoreDomains.inferAnalysisDomain(caseDataMap, null, state.mode, null)

                // Rubric/context injection (app/analysis/prompt_builder.py's build_analysis_prompt,
                // input side): load the case's eval template (assets/eval/<name>.json) and build the
                // scoring-anchor / checklist / empathy / fairness-note / open-commitment block that
                // AnalysisPromptBuilder appends to the analysis prompt. self_scores/student_soap are
                // null until their editors feed them; open debrief commitments are passed so the
                // evaluator checks them (clinical only, matching Python).
                val everydayDomainForRubric = analysisDomain == "everyday"
                val evalTemplateName = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder
                    .resolveEvalTemplateName(caseDataMap, everydayDomainForRubric)
                val evalData = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder
                    .loadEvalTemplate(getApplication(), evalTemplateName)

                // "Practice My Mistakes" review session: the case snapshot carries the target
                // corrections (ReviewBuilder.buildReviewCase), and correction_review.json ships an
                // intentionally EMPTY checklist that must be filled at runtime — one item per
                // target — so the evaluator returns a checklist_results array aligned 1:1 with the
                // targets. Without this injection the model has nothing to grade and
                // SrsEngine.updateAfterReview below can never advance a streak. Mirrors
                // review_builder.build_review_eval.
                val isReviewSession = (caseDataMap["id"] as? String) == "review_corrections" ||
                    state.caseId == "review_corrections"
                val reviewTargets = if (isReviewSession) parseReviewTargets(state.caseJson) else emptyList()
                val evalDataForRubric = if (isReviewSession && reviewTargets.isNotEmpty()) {
                    evalData.toMutableMap().apply {
                        put("checklist", reviewTargets.map {
                            mapOf("item" to "used the improved form: \"${it.corrected}\"", "required" to true)
                        })
                    }
                } else {
                    evalData
                }

                val openCommitmentsForRubric = commitments.value
                    .filter { it.status == "open" }
                    .map { mapOf("id" to it.id, "text" to it.text, "focus_area" to it.focusArea) }
                val rubricContext = com.example.medvoicetrainer.analysis.AnalysisPromptBuilder.buildRubricContext(
                    caseData = caseDataMap,
                    evalData = evalDataForRubric,
                    everyday = everydayDomainForRubric,
                    selfScores = null,
                    studentSoap = null,
                    commitments = openCommitmentsForRubric
                ).let { base ->
                    listOf(
                        base,
                        // Blank unless this is a Free Talk session: the partner was muted on
                        // purpose, and the talk-share counts give the evaluator hard evidence.
                        com.example.medvoicetrainer.analysis.FreeTalk.analysisNote(caseDataMap, pythonRoleTranscriptJson),
                        com.example.medvoicetrainer.analysis.CorrectionFeedbackMemory.promptNote(
                            repository.getSetting("correction_feedback_memory", "{}")
                        ),
                        nativeExplanationNote()
                    ).filter { it.isNotBlank() }.joinToString("\n\n")
                }

                val intelligibilityMetrics = Intelligibility.computeIntelligibilityMetrics(
                    state.transcript.toPythonRoleTranscriptMaps(),
                    mode = if (analysisDomain == "everyday") "everyday" else "clinical"
                )

                // Audio pronunciation analysis (session_base.py's "Feature 1") needs only the
                // captured turn audio and the transcript — nothing the scoring call returns; its
                // findings are merged into the evaluation further down, once both have landed.
                // Running it *after* scoring therefore made the learner wait for the sum of two
                // provider chains (up to 120s + 45s) for no reason. Started here it overlaps with
                // scoring, so the wait is the longer of the two instead of both back to back.
                //
                // The gate is the one the merge used to apply: `evaluationLocked` is set only by
                // generateUnavailableEvaluation (and preserved by applyEvidenceConfidence), i.e.
                // exactly when the analysis key is blank — knowable before the scoring call
                // returns, which is what lets this start early.
                pronunciationDeferred = if (
                    analysisApiKey.isNotBlank() &&
                    audioCapture?.analyzePronunciation == true &&
                    audioCapture.pronunciationFiles.isNotEmpty() &&
                    (
                        hasAzureSpeechCredentials() ||
                            getApiKeyForBackend("gemini").isNotBlank()
                        )
                ) {
                    val pythonTranscript = state.transcript.toPythonRoleTranscriptMaps()
                    val pronunciationSources = audioCapture.pronunciationFiles.entries.mapNotNull { (turnIndex, file) ->
                        val turn = pythonTranscript.getOrNull(turnIndex)
                        val text = turn?.takeIf { it["role"] == "user" }
                            ?.get("text")?.toString()?.trim().orEmpty()
                        if (text.isBlank() || !file.isFile) null else {
                            com.example.medvoicetrainer.analysis.PronunciationAudioSource(
                                turnIndex = turnIndex,
                                transcriptText = text,
                                byteSize = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                loadAudio = { file.readBytes() }
                            )
                        }
                    }
                    async { runPronunciationAnalysis(pronunciationSources, analysisDomain) }
                } else {
                    null
                }

                var analysisUsage: LlmUsage? = null
                var rawEvalJsonForStorage: String? = null
                val evalResult = if (analysisApiKey.isBlank()) {
                    val typedDemo = finishingVoiceBackend in setOf("demo", "mock")
                    val unavailable = generateUnavailableEvaluation(state, analysisDomain, typedDemo)
                    rawEvalJsonForStorage = JSONObject()
                        .put("_demo", typedDemo)
                        .put("_locked", true)
                        .put("summary_feedback", unavailable.summaryFeedback)
                        .put("corrections", JSONArray())
                        .put("anki_cards", JSONArray())
                        .put("shadowing_items", JSONArray())
                        .toString()
                    unavailable
                } else {
                    // Call Analysis Engine (usage-tracking variant so cost can be computed below)
                    val (rawEval, usage) = AnalysisEngine.evaluateSessionWithUsage(
                        backend = analysisBackendVal,
                        apiKey = analysisApiKey,
                        model = analysisModel,
                        transcript = transcriptJson,
                        caseJson = state.caseJson,
                        nativeLanguage = _nativeLanguage.value,
                        analysisDomain = analysisDomain,
                        rubricContext = rubricContext
                    )
                    analysisUsage = usage
                    rawEvalJsonForStorage = rawEval
                    parseEvaluationJson(rawEval, state, analysisDomain)
                }

                // Save Session to Database
                val baseCalibratedEval = applyEvidenceConfidence(
                    evaluation = evalResult,
                    transcript = state.transcript.toPythonRoleTranscriptMaps(),
                    caseData = caseDataMap,
                )
                val qualityAwareEval = audioCapture?.pronunciationQualityWarnings
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { warnings ->
                        val reasons = warnings.values.distinct().take(2).joinToString(" ")
                        baseCalibratedEval.copy(
                            summaryFeedback = baseCalibratedEval.summaryFeedback +
                                "\n\nPronunciation note: ${warnings.size} learner turn(s) were skipped by the " +
                                "on-device audio-quality check. $reasons"
                        )
                    } ?: baseCalibratedEval

                // Fold in the pronunciation pass started before scoring. By the time scoring
                // returns this has usually finished too, so the await is normally free.
                val pronAnalysis = pronunciationDeferred?.await()
                val calibratedEval = if (pronAnalysis != null) {
                    val pronCorrections = pronAnalysis.corrections
                    val communicationMetrics = intelligibilityMetrics.toMutableMap().apply {
                        putAll(pronAnalysis.acousticMetrics())
                    }
                    if (pronCorrections.isEmpty()) {
                        qualityAwareEval.copy(
                            intelligibility = communicationMetrics.ifEmpty { null }
                        )
                    } else {
                        val mergedCorrections = qualityAwareEval.corrections + pronCorrections.map {
                            SrsCorrection(
                                it.original,
                                it.corrected,
                                it.explanation,
                                "pronunciation:${it.pattern}",
                                it.turnIndex,
                                confidence = it.confidence,
                                feedbackType = "error",
                                evidenceSource = "audio",
                                patternId =
                                    com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                                        .evidenceKey(
                                            "pronunciation:${it.pattern}",
                                            it.corrected
                                        ),
                                audioStartMs = it.startMs,
                                audioEndMs = it.endMs
                            )
                        }
                        val correctionsArray = JSONArray(qualityAwareEval.rawCorrectionsJson.ifBlank { "[]" })
                        for (pc in pronCorrections) {
                            correctionsArray.put(
                                JSONObject()
                                    .put("original", pc.original)
                                    .put("corrected", pc.corrected)
                                    .put("explanation", pc.explanation)
                                    .put("category", "pronunciation:${pc.pattern}")
                                    .put("feedback_type", "error")
                                    .put("confidence", pc.confidence)
                                    .put("evidence_source", "audio")
                                    .put(
                                        "pattern_id",
                                        com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                                            .evidenceKey(
                                                "pronunciation:${pc.pattern}",
                                                pc.corrected
                                            )
                                    )
                                    .put("decision", "pending")
                                    .apply {
                                        pc.startMs?.let { put("start_ms", it) }
                                        pc.endMs?.let { put("end_ms", it) }
                                    }
                                    .apply { pc.turnIndex?.let { put("turn_index", it) } }
                            )
                        }
                        qualityAwareEval.copy(
                            corrections = mergedCorrections,
                            rawCorrectionsJson = correctionsArray.toString(),
                            intelligibility = communicationMetrics.ifEmpty { null }
                        )
                    }
                } else {
                    qualityAwareEval
                }

                // Cards are already returned with the evaluation. Only show this final stage
                // while serializing and saving them; the pronunciation pass merged above can still
                // be finishing its own provider calls and must not masquerade as slow SRS-card
                // generation.
                _activeSession.update { current ->
                    if (voiceSessionGeneration.get() == finishingGeneration && current.isFinishing) {
                        current.copy(analysisStage = FeedbackAnalysisStage.BUILDING_CARDS)
                    } else current
                }

                val claudeCost = analysisUsage?.let {
                    CostTracker.computeAnalysisCost(
                        analysisBackendVal,
                        it.inputTokens,
                        it.outputTokens,
                        it.cachedTokens,
                        it.modelUsed.ifBlank { analysisModel },
                    )
                } ?: 0.0
                val voiceCost = sessionVoiceCost(voiceUsage, state, durationSeconds, finishingVoiceBackend)

                // Fold the deterministic metrics into raw_claude_response so
                // ProgressReportEngine.buildProgressReport (which reads
                // session["raw_claude_response"]["fluency_metrics"/"intelligibility"]) has real
                // data to trend on — mirrors Python's result["fluency_metrics"] = fm /
                // result["intelligibility"] = compute_intelligibility_metrics(...).
                val rawClaudeResponseJson = try {
                    val root = if (!rawEvalJsonForStorage.isNullOrBlank()) {
                        JSONObject(rawEvalJsonForStorage!!.replace("```json", "").replace("```", "").trim())
                    } else {
                        JSONObject()
                    }
                    if (fluencyMetrics != null) {
                        val fm = JSONObject()
                        fm.put("user_word_count", fluencyMetrics.userWordCount)
                        fm.put("user_turn_count", fluencyMetrics.userTurnCount)
                        fluencyMetrics.wordsPerMinute?.let { fm.put("wpm", it) }
                        fm.put("filler_rate", fluencyMetrics.fillerRate)
                        fm.put("talk_time_ratio", fluencyMetrics.talkTimeRatio)
                        fluencyMetrics.avgResponseGapSeconds?.let { fm.put("avg_response_gap_seconds", it) }
                        fm.put("very_short_turn_count", fluencyMetrics.veryShortTurnCount)
                        fm.put("confidence_band", fluencyMetrics.confidenceBand)
                        root.put("fluency_metrics", fm)
                    }
                    val combinedIntelligibility = calibratedEval.intelligibility
                        ?: intelligibilityMetrics.takeIf { it.isNotEmpty() }
                    if (combinedIntelligibility != null) {
                        root.put("intelligibility", JSONObject(combinedIntelligibility))
                    }
                    root.put("_analysis_prompt_version", EvalPromptBuilder.PROMPT_VERSION)
                    root.put(
                        "_analysis_model_used",
                        analysisUsage?.modelUsed ?: analysisModel
                    )
                    calibratedEval.reliabilityBadge?.let {
                        root.put("score_reliability", JSONObject(it))
                    }
                    if (!calibratedEval.evaluationLocked) {
                        val scores = JSONObject()
                        if (analysisDomain == "everyday") {
                            scores.put("naturalness", calibratedEval.grammarScore / 10.0)
                            scores.put("interaction", calibratedEval.medicalAccuracy / 10.0)
                            scores.put("comprehension_repair", calibratedEval.clinicalReasoning / 10.0)
                        } else {
                            scores.put("grammar", calibratedEval.grammarScore / 10.0)
                            scores.put("medical_accuracy", calibratedEval.medicalAccuracy / 10.0)
                            scores.put("clinical_reasoning", calibratedEval.clinicalReasoning / 10.0)
                            scores.put("professionalism", calibratedEval.professionalism / 10.0)
                        }
                        scores.put("fluency", calibratedEval.fluencyScore / 10.0)
                        root.put("overall_scores", scores)
                    }
                    root.toString()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    rawEvalJsonForStorage
                }

                val completedSession = SessionEntity(
                    id = finishingDraftId ?: 0,
                    createdAt = state.createdAt,
                    mode = state.mode,
                    analysisDomain = analysisDomain,
                    caseName = state.caseName,
                    caseId = state.caseId,
                    evalTemplate = evalTemplateName,
                    voiceBackend = finishingVoiceBackend,
                    voiceModel = voiceUsage?.model ?: getVoiceModelForBackend(finishingVoiceBackend),
                    analysisModel = analysisUsage?.modelUsed ?: analysisModel,
                    rawCaseJson = state.caseJson.ifBlank { "{}" },
                    rawTranscript = transcriptJson,
                    learnerTurnCount = state.transcript.count {
                        it.first == "doctor" && it.second.isNotBlank()
                    },
                    durationSeconds = durationSeconds,
                    rawClaudeResponse = rawClaudeResponseJson,
                    rawEvalJson = rawEvalJsonForStorage,
                    grammarScore = calibratedEval.grammarScore,
                    medicalAccuracyScore = calibratedEval.medicalAccuracy,
                    clinicalReasoningScore = calibratedEval.clinicalReasoning,
                    professionalismScore = calibratedEval.professionalism,
                    fluencyScore = calibratedEval.fluencyScore,
                    summaryFeedback = calibratedEval.summaryFeedback,
                    studentSoapNote = "",
                    soapNote = calibratedEval.soapNote,
                    checklistResults = calibratedEval.checklistResultsJson,
                    historyCompleteness = calibratedEval.historyCompleteness,
                    iceElicited = if (calibratedEval.iceElicited) 1 else 0,
                    empathyMarkersFound = calibratedEval.empathyMarkersJson,
                    referenceSoap = calibratedEval.referenceSoap,
                    corrections = calibratedEval.rawCorrectionsJson,
                    ankiCards = calibratedEval.ankiCardsJson,
                    userWordCount = fluencyMetrics?.userWordCount ?: calibratedEval.wordCount,
                    wordsPerMinute = fluencyMetrics?.wordsPerMinute ?: 0.0,
                    fillerRate = fluencyMetrics?.fillerRate ?: 0.0,
                    talkTimeRatio = fluencyMetrics?.talkTimeRatio ?: 0.0,
                    claudeInputTokens = analysisUsage?.inputTokens ?: 0,
                    claudeOutputTokens = analysisUsage?.outputTokens ?: 0,
                    claudeCachedTokens = analysisUsage?.cachedTokens ?: 0,
                    claudeCostUsd = claudeCost,
                    voiceCostUsd = voiceCost,
                    totalCostUsd = claudeCost + voiceCost,
                    voiceInputTextTokens = voiceUsage?.inputTextTokens ?: 0,
                    voiceInputAudioTokens = voiceUsage?.inputAudioTokens ?: 0,
                    voiceOutputTextTokens = voiceUsage?.outputTextTokens ?: 0,
                    voiceOutputAudioTokens = voiceUsage?.outputAudioTokens ?: 0,
                    voiceCachedInputTextTokens = voiceUsage?.cachedInputTextTokens ?: 0,
                    voiceCachedInputAudioTokens = voiceUsage?.cachedInputAudioTokens ?: 0,
                    voiceThinkingTokens = voiceUsage?.thinkingTokens ?: 0,
                    voiceUsageExact = voiceUsage != null,
                    costEstimated = voiceUsage == null && finishingVoiceBackend in setOf("gemini", "openai"),
                    endReason = SessionEndReason.COMPLETED,
                )
                val completedSessionId = if (finishingDraftId != null) {
                    repository.updateSession(completedSession)
                    finishingDraftId
                } else {
                    repository.insertSession(completedSession).toInt()
                }
                val stillOwnsUi = {
                    SessionCompletionOwnership.stillOwnsUi(
                        finishingGeneration,
                        voiceSessionGeneration.get(),
                    )
                }
                val isDetachedAnalysis = detachedAnalysisGenerations.contains(finishingGeneration)
                val mayPresentFeedback = { stillOwnsUi() && !isDetachedAnalysis }
                if (stillOwnsUi() && activeSessionId == finishingDraftId) {
                    activeSessionId = null
                }

                // Keyless demo tour bookkeeping: mark this scripted patient played and compute
                // the status show_demo_next_dialog needs, ported from session_base.py's
                // demo_tour.mark_demo_case_completed(...) call around session finish.
                if (finishingVoiceBackend == "demo") {
                    val demoCaseId = com.example.medvoicetrainer.analysis.DemoTour.demoCaseIdForRealCaseId(state.caseId)
                    if (demoCaseId != null) {
                        com.example.medvoicetrainer.analysis.DemoTour.markDemoCaseCompleted(
                            demoCaseId,
                            getSetting = { repository.getSetting("demo_cases_completed", "") },
                            setSetting = { v -> repository.setSetting("demo_cases_completed", v) }
                        )
                    }
                    val completed = com.example.medvoicetrainer.analysis.DemoTour.getCompletedDemoCases {
                        repository.getSetting("demo_cases_completed", "")
                    }
                    val (done, total) = com.example.medvoicetrainer.analysis.DemoTour.tourProgress(completed)
                    val tourComplete = com.example.medvoicetrainer.analysis.DemoTour.isTourComplete(completed)
                    val recap = com.example.medvoicetrainer.analysis.DemoTour.tourRecapStats(repository.getAllSessionsList())
                    // Frequency guard: a same-day "decide later" suppresses the next automatic prompt.
                    val showDecision = com.example.medvoicetrainer.analysis.DemoTour.shouldAutoShowDecision { key, default ->
                        repository.getSetting(key, default)
                    }
                    if (mayPresentFeedback() && showDecision) {
                        _demoTourStatus.value = DemoTourStatus(
                            doneCount = done,
                            totalCount = total,
                            tourComplete = tourComplete,
                            recap = recap,
                            everyday = analysisDomain == "everyday"
                        )
                    }
                }

                // A dedicated review is explicit retrieval evidence, so it can grade existing
                // cards automatically. New model-generated corrections remain pending until the
                // learner accepts them on the feedback screen.
                if (analysisDomain == "clinical" && !calibratedEval.evaluationLocked) {
                    if (isReviewSession) {
                        com.example.medvoicetrainer.analysis.SrsEngine.updateAfterReview(
                            checklistResultsToMaps(calibratedEval.checklistResultsJson),
                            reviewTargets,
                            repository
                        )
                    }
                    // Anti-hallucination guard (feedback_engine.py's _record_commitment_results):
                    // only accept verdicts for commitment ids that were actually sent to the
                    // evaluator in this session's rubric context, so a fabricated/stale id can
                    // never touch another commitment's kept-streak. Repository.recordCommitmentCheck
                    // does the result normalize/validate, mirroring record_commitment_check.
                    val validCommitmentIds = openCommitmentsForRubric
                        .mapNotNull { (it["id"] as? Number)?.toInt() }
                        .toSet()
                    val parsedCommitmentResults = mutableListOf<Map<String, Any?>>()
                    val commitmentResults = JSONArray(calibratedEval.commitmentResultsJson)
                    for (i in 0 until commitmentResults.length()) {
                        val item = commitmentResults.optJSONObject(i) ?: continue
                        parsedCommitmentResults.add(
                            mapOf("id" to item.opt("id"), "result" to item.opt("result"))
                        )
                    }
                    com.example.medvoicetrainer.analysis.CommitmentResults
                        .plannedChecks(parsedCommitmentResults, validCommitmentIds)
                        .forEach { (id, result) ->
                            repository.recordCommitmentCheck(id, result, completedSessionId)
                        }
                }

                // §6's reliability badge — the richer word-count/evidence-gated version
                // (ScoringCalibration.buildScoreReliability), not just the HIGH/MEDIUM/LOW name
                // the badge used to show alone. Built from the same overall_scores/
                // checklist_results shape the Python original scores against; harmless if it
                // ever throws (best-effort, the plain `reliability` name still renders).
                val reliabilityBadge = calibratedEval.reliabilityBadge ?: try {
                    // buildScoreReliability is a faithful port of the Python original, which
                    // scores on a 0-10 rubric (it derives a percent as avg_score * 10) — this
                    // app stores 0-100. Divide back down here rather than touch the port itself.
                    val overallScores = if (analysisDomain == "everyday") {
                        mapOf(
                            "naturalness_score" to calibratedEval.grammarScore / 10.0,
                            "interaction_score" to calibratedEval.medicalAccuracy / 10.0,
                            "comprehension_repair_score" to calibratedEval.clinicalReasoning / 10.0,
                            "fluency_score" to calibratedEval.fluencyScore / 10.0
                        )
                    } else {
                        mapOf(
                            "grammar_score" to calibratedEval.grammarScore / 10.0,
                            "medical_accuracy_score" to calibratedEval.medicalAccuracy / 10.0,
                            "clinical_reasoning_score" to calibratedEval.clinicalReasoning / 10.0,
                            "professionalism_score" to calibratedEval.professionalism / 10.0,
                            "fluency_score" to calibratedEval.fluencyScore / 10.0
                        )
                    }
                    com.example.medvoicetrainer.analysis.ScoringCalibration.buildScoreReliability(
                        analysis = mapOf(
                            "overall_scores" to overallScores,
                            "checklist_results" to checklistResultsToMaps(calibratedEval.checklistResultsJson)
                        ),
                        transcript = state.transcript.toPythonRoleTranscriptMaps(),
                        evalData = null,
                        caseData = caseDataMap
                    )
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    null
                }

                if (mayPresentFeedback()) {
                    lastSessionId = completedSessionId
                    _lastEvaluation.value = calibratedEval.copy(
                        reliability = calibratedEval.reliability,
                        fluencyMetrics = fluencyMetrics,
                        intelligibility = calibratedEval.intelligibility
                            ?: intelligibilityMetrics.ifEmpty { null },
                        reliabilityBadge = reliabilityBadge
                    )
                    _lastEvaluation.value?.let { initializeCorrectionDecisions(it, completedSessionId) }
                    _lastCompletedTranscript.value = state.transcript
                    _lastCompletedOpenBookAids.value = com.example.medvoicetrainer.analysis.OpenBookAidsUsed(
                        level = openBookLevelIn(state.caseJson),
                        closingLevel = openBookClosingLevelIn(state.caseJson),
                        briefed = runCatching {
                            JSONObject(state.caseJson.ifBlank { "{}" })
                                .optJSONObject("practice_aids")
                                ?.optBoolean("briefed", false) == true
                        }.getOrDefault(false),
                    )
                    // Which offered expressions the learner actually said. Captured from the live
                    // ticks rather than recomputed, so the feedback recap and the sheet the learner
                    // was looking at a moment ago can never disagree.
                    _lastCompletedPhrasebookAids.value =
                        com.example.medvoicetrainer.analysis.EverydayPhrasebook
                            .aidsFromCaseJson(state.caseJson)
                    _lastCompletedAudioClips.value = audioCapture?.clips?.toMap().orEmpty()
                    _lastCompletedPresentationLaunch.value =
                        com.example.medvoicetrainer.analysis.PresentationBuilder.buildLaunch(
                            mapOf(
                                "id" to completedSessionId,
                                "mode" to state.mode,
                                "case_name" to state.caseName,
                                "raw_case_json" to state.caseJson,
                                "raw_transcript" to transcriptJson,
                            )
                        )
                    if (state.mode in setOf("presentation", "team_communication")) {
                        val task = runCatching {
                            JSONObject(state.caseJson).optString("communication_task", state.mode)
                        }.getOrDefault(state.mode)
                        com.example.medvoicetrainer.analysis.Telemetry.track(
                            "team_communication_completed",
                            mapOf("task" to task, "mode" to state.mode)
                        )
                    }
                    // Its own event, not a third value on the one above: the Nursing track is a
                    // beta whose whole purpose is a start/finish funnel per task family, and
                    // folding it into team_communication_completed would make that unreadable.
                    if (state.mode == com.example.medvoicetrainer.analysis.NursingTrack.SESSION_MODE) {
                        val task = runCatching {
                            JSONObject(state.caseJson).optString("nursing_task", state.mode)
                        }.getOrDefault(state.mode)
                        com.example.medvoicetrainer.analysis.Telemetry.track(
                            "nursing_completed",
                            mapOf("task" to task, "mode" to state.mode)
                        )
                    }
                }
                audioCapture?.let { pronunciationAudioStore.cleanupSession(it.sessionId) }
                if (mayPresentFeedback()) {
                    if (activeSessionAudioCapture?.generation == finishingGeneration) {
                        activeSessionAudioCapture = null
                    }
                    _activeSession.value = ActiveSessionState() // Reset the session we finished
                }

                // Post-session moments (milestone celebration -> confidence check-in -> tomorrow
                // toast, in that priority order) — best-effort, must never break session end.
                if (mayPresentFeedback() && !calibratedEval.evaluationLocked) {
                    try {
                        evaluatePostSessionMoments()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // ignore — these are supplementary retention moments, not core flow
                    }
                }
                if (isDetachedAnalysis) {
                    detachedAnalysisGenerations.remove(finishingGeneration)
                    finishingDraftId?.let { sessionId ->
                        _backgroundAnalysisSessionIds.value =
                            _backgroundAnalysisSessionIds.value - sessionId
                    }
                    _feedbackReadyEvent.value = FeedbackReadyEvent(completedSessionId, state.caseName)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                pronunciationDeferred?.cancel()
                // The learner asked for analysis and the call failed. Unlike a discard, this is an
                // accident they did not choose, so the row stays eligible for the Home recovery
                // card — where "Analyze" is a genuine retry of exactly this failed step.
                finishingDraftId?.let { sessionId ->
                    try {
                        repository.updateSessionEndReason(sessionId, SessionEndReason.ANALYSIS_FAILED)
                    } catch (inner: kotlinx.coroutines.CancellationException) {
                        throw inner
                    } catch (_: Exception) {
                        // Best-effort bookkeeping; never mask the analysis error being reported.
                    }
                }
                if (detachedAnalysisGenerations.remove(finishingGeneration)) {
                    finishingDraftId?.let { sessionId ->
                        _backgroundAnalysisSessionIds.value =
                            _backgroundAnalysisSessionIds.value - sessionId
                    }
                }
                if (SessionCompletionOwnership.stillOwnsUi(
                        finishingGeneration,
                        voiceSessionGeneration.get(),
                    )
                ) {
                    _activeSession.value = _activeSession.value.copy(
                        isAILoading = false,
                        error = "Failed to evaluate session: ${com.example.medvoicetrainer.api.ApiError.userMessage(e)}"
                    )
                }
            }
        }
    }

    /**
     * A milestone celebration takes the modal slot; otherwise the "see you tomorrow" toast may
     * appear once per day. Reflection now lives inside feedback, where it can affect the next
     * practice action without interrupting the report.
     */
    private suspend fun evaluatePostSessionMoments() {
        val freshSessions = repository.getAllSessionsList()
            .filterNot { com.example.medvoicetrainer.analysis.KmleCpx.isKmleSession(it.mode, it.analysisDomain) }
        val freshErrorItems = repository.getAllErrorItemsList()
        val masteredCount = freshErrorItems.count { it.state == "mastered" }
        val stats = com.example.medvoicetrainer.analysis.LifetimeStatsEngine.computeLifetimeStats(freshSessions, masteredCount)

        val statsMap = mapOf(
            "sessions" to stats.sessions,
            "minutes" to stats.minutes,
            "streak" to stats.streak,
            "mastered" to stats.mastered
        )
        val newMilestones = com.example.medvoicetrainer.analysis.Milestones.checkAndMarkNew(
            stats = statsMap,
            getSetting = { repository.getSetting(com.example.medvoicetrainer.analysis.Milestones.SETTING_KEY, "[]") },
            setSetting = { v -> repository.setSetting(com.example.medvoicetrainer.analysis.Milestones.SETTING_KEY, v) }
        )
        if (newMilestones.isNotEmpty()) {
            _postSessionMoment.value = PostSessionMoment.MilestoneMoment(newMilestones, stats)
            return
        }

        val voiceBackendVal = repository.getSetting("voice_backend", "gemini")
        val todayIso = java.time.LocalDate.now().toString()
        val lastToastDate = repository.getSetting("tomorrow_toast_date", "")
        if (com.example.medvoicetrainer.analysis.TomorrowToast.shouldShow(milestoneShown = false, todayIso = todayIso, lastShownDateIso = lastToastDate)) {
            repository.setSetting("tomorrow_toast_date", todayIso)
            val isDemoBackend = voiceBackendVal == "demo"
            val content = if (isDemoBackend) {
                val everydayExperience = PracticeExperience.fromStorage(
                    repository.getSetting(PracticeExperience.SETTING_KEY, "")
                ) == PracticeExperience.EVERYDAY_ENGLISH
                if (everydayExperience) {
                    com.example.medvoicetrainer.analysis.TomorrowToast.buildToastContent(
                        stats.streak,
                        null,
                        isDemoBackend = true,
                        demoTourComplete = true,
                    )
                } else {
                    val completed = com.example.medvoicetrainer.analysis.DemoTour.getCompletedDemoCases {
                        repository.getSetting("demo_cases_completed", "")
                    }
                    val demoComplete = com.example.medvoicetrainer.analysis.DemoTour.isTourComplete(completed)
                    val nextLabel = if (!demoComplete) {
                        com.example.medvoicetrainer.analysis.DemoTour.getFirstUnseenDemoCaseId(completed)
                    } else null
                    com.example.medvoicetrainer.analysis.TomorrowToast.buildToastContent(
                        stats.streak, null, isDemoBackend = true, demoTourComplete = demoComplete, nextDemoCaseLabel = nextLabel
                    )
                }
            } else {
                // Mirrors queries.py's get_tomorrow_mission_preview(): same build_daily_mission()
                // selector as the dashboard, evaluated as-of tomorrow (SRS due count includes
                // items due by end of tomorrow, rotation index advances one day) so this preview
                // matches what the dashboard will actually recommend when the user returns.
                val missionTitle = try {
                    val tomorrow = java.time.LocalDate.now().plusDays(1)
                    val eodTomorrowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(
                        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 2) }.time
                    )
                    val recentClinical = repository.listClinicalSessions(30).toAnalysisMaps()
                    val dueCountTomorrow = repository.getDueCountBy(eodTomorrowIso)
                    val openCommitmentsPreview = repository.getOpenCommitments(1)
                    com.example.medvoicetrainer.analysis.DailyMissionEngine.generateMission(
                        recentClinical, dueCountTomorrow, openCommitmentsPreview, tomorrow
                    ).title
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    null
                }
                com.example.medvoicetrainer.analysis.TomorrowToast.buildToastContent(stats.streak, missionTitle, isDemoBackend = false)
            }
            _postSessionMoment.value = PostSessionMoment.Toast(content)
        }
    }

    /** Parses JSON recursively so case-authored nested rubric/checklist data remains usable. */
    private fun jsonObjectToMap(json: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(json.ifBlank { "{}" })
            jsonObjectToMap(obj)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> = buildMap {
        for (key in obj.keys()) put(key, jsonValueToKotlin(obj.opt(key)))
    }

    private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.opt(it)) }
        else -> value
    }

    // Lives in SoapNoteFormatter.kt with the display formatter it feeds, so the two halves of the
    // note's shape stay together (and are unit-testable without a ViewModel).
    private fun formatSoap(value: Any?): String = flattenSoapNote(value)

    private fun referenceSoapFromCase(caseJson: String): String = try {
        formatSoap(JSONObject(caseJson.ifBlank { "{}" }).opt("reference_soap"))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        ""
    }

    private fun generateUnavailableEvaluation(
        state: ActiveSessionState,
        analysisDomain: String = "clinical",
        typedDemo: Boolean
    ): EvaluationResult {
        val wordCount = state.transcript.filter { it.first == "doctor" }
            .sumOf { turn -> turn.second.split(Regex("\\s+")).count { it.isNotBlank() } }
        val checklistChecked = state.checklistCoverage.map { (k, v) -> k to v }
        val everyday = EvalPromptBuilder.isEverydayDomain(analysisDomain)
        val summary = if (typedDemo) {
            com.example.medvoicetrainer.analysis.FeedbackEngine
                .mockLockedDemoResult(everyday)["summary_feedback"].toString()
        } else {
            "AI scoring was not run because the selected feedback provider has no API key. " +
                "Your offline fluency and intelligibility measurements below are still based on your real words."
        }
        return EvaluationResult(
            grammarScore = 0.0,
            medicalAccuracy = 0.0,
            clinicalReasoning = 0.0,
            professionalism = 0.0,
            fluencyScore = 0.0,
            summaryFeedback = summary,
            soapNote = "",
            corrections = emptyList(),
            rawCorrectionsJson = "[]",
            wordCount = wordCount,
            wpm = 0.0,
            fillerRate = 0.0,
            checklistChecked = checklistChecked,
            analysisDomain = analysisDomain,
            caseName = state.caseName,
            evaluationLocked = true,
            isTypedDemo = typedDemo,
            referenceSoap = referenceSoapFromCase(state.caseJson)
        )
    }

    private fun parseEvaluationJson(rawJson: String, state: ActiveSessionState, analysisDomain: String = "clinical"): EvaluationResult {
        val cleanJson = rawJson.replace("```json", "").replace("```", "").trim()
        val root = JSONObject(cleanJson)

        val correctionsList = mutableListOf<SrsCorrection>()
        val groundedCorrectionsArray = JSONArray()
        val correctionsArray = root.optJSONArray("corrections")
        if (correctionsArray != null) {
            for (i in 0 until correctionsArray.length()) {
                val item = correctionsArray.optJSONObject(i) ?: continue
                val original = item.optString("original").trim()
                val corrected = item.optString("corrected").trim()
                val category = com.example.medvoicetrainer.analysis.CorrectionEvidence
                    .sanitizeTextCategory(item.optString("category", "other")) ?: continue
                // Transcript-only analysis can never create pronunciation evidence.
                if (category.startsWith("pronunciation", ignoreCase = true)) continue
                val confidence = item.optDouble("confidence", 0.0)
                if (!confidence.isFinite() || confidence < com.example.medvoicetrainer.analysis.CorrectionEvidence.MIN_TEXT_CONFIDENCE) continue
                val requestedTurn = if (item.has("turn_index") && !item.isNull("turn_index")) {
                    item.optInt("turn_index", -1).takeIf { it >= 0 }
                } else null
                val groundedTurn = com.example.medvoicetrainer.analysis.CorrectionEvidence.groundedTurnIndex(
                    requestedTurn, original, state.transcript
                ) ?: continue
                val feedbackType = com.example.medvoicetrainer.analysis.CorrectionEvidence
                    .sanitizeFeedbackType(item.optString("feedback_type", "error"))
                if (
                    feedbackType == "error" &&
                    !com.example.medvoicetrainer.analysis.CorrectionEvidence
                        .isAtomicMinimalEdit(original, corrected)
                ) continue
                val l1Hypothesis = item.opt("l1_hypothesis")
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.toString()?.trim().orEmpty()
                val correction = try {
                    SrsCorrection(
                        original = original,
                        corrected = corrected,
                        explanation = item.optString("explanation"),
                        category = category,
                        turnIndex = groundedTurn,
                        confidence = confidence,
                        feedbackType = feedbackType,
                        l1Hypothesis = l1Hypothesis,
                        evidenceSource = "transcript",
                        patternId = com.example.medvoicetrainer.analysis.CorrectionPatternId.derive(
                            item.optString("pattern_id"),
                            category,
                            original,
                            corrected
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    continue
                }
                correctionsList.add(correction)
                groundedCorrectionsArray.put(
                    JSONObject()
                        .put("turn_index", groundedTurn)
                        .put("original", correction.original)
                        .put("corrected", correction.corrected)
                        .put("explanation", correction.explanation)
                        .put("category", correction.category)
                        .put("feedback_type", correction.feedbackType)
                        .put("confidence", correction.confidence)
                        .put("l1_hypothesis", correction.l1Hypothesis.ifBlank { JSONObject.NULL })
                        .put("pattern_id", correction.patternId)
                        .put("evidence_source", correction.evidenceSource)
                        .put("decision", "pending")
                )
            }
        }

        val isFollowUp = com.example.medvoicetrainer.analysis.FollowUpEvaluation.isFollowUp(
            state.mode,
            state.caseJson,
        )
        val checklistChecked = mutableListOf<Pair<String, Boolean>>()
        val returnedChecklist = root.optJSONArray("checklist_results")
            ?: root.optJSONArray("checklist_checked")
            ?: if (runCatching { JSONObject(state.caseJson).has("evaluation_checklist") }.getOrDefault(false)) {
                JSONArray()
            } else null
        val checklistArray = if (isFollowUp) {
            com.example.medvoicetrainer.analysis.FollowUpEvaluation.canonicalChecklist(
                state.caseJson,
                returnedChecklist,
            )
        } else {
            returnedChecklist
        }
        // Case-authored applicability is authoritative. A model may forget the new status field or
        // even omit an N/A row; normalize both cases so an impossible task can never become a fail.
        if (!isFollowUp) runCatching {
            val authored = JSONObject(state.caseJson).optJSONArray("evaluation_checklist")
                ?: return@runCatching
            val result = checklistArray ?: return@runCatching
            for (index in 0 until authored.length()) {
                val spec = authored.optJSONObject(index) ?: continue
                if (spec.optBoolean("applicable", true)) continue
                val name = spec.optString("item")
                var found: JSONObject? = null
                for (resultIndex in 0 until result.length()) {
                    val candidate = result.optJSONObject(resultIndex) ?: continue
                    if (candidate.optString("item") == name) {
                        found = candidate
                        break
                    }
                }
                val item = found ?: JSONObject().put("item", name).also(result::put)
                item.put("required", false)
                    .put("passed", false)
                    .put("status", "not_applicable")
                    .put("evidence", spec.optString("not_applicable_reason", "Not applicable to this visit."))
            }
        }
        if (checklistArray != null) {
            for (i in 0 until checklistArray.length()) {
                val item = checklistArray.optJSONObject(i) ?: continue
                val passed = when {
                    item.has("passed") -> item.optBoolean("passed")
                    else -> item.optBoolean("elicited")
                }
                checklistChecked.add(item.optString("item") to passed)
            }
        } else {
            checklistChecked.addAll(state.checklistCoverage.map { it.key to it.value })
        }

        val wordCount = state.transcript.filter { it.first == "doctor" }
            .sumOf { turn -> turn.second.split(Regex("\\s+")).count { it.isNotBlank() } }
        val everyday = EvalPromptBuilder.isEverydayDomain(analysisDomain)
        val misconceptionReviewJson = if (everyday) {
            "[]"
        } else {
            com.example.medvoicetrainer.analysis.MisconceptionReview.sanitizeEvaluationArray(
                root.optJSONArray("misconception_review"),
                state.transcript,
            ).toString()
        }

        // Everyday sessions are scored against EvalPromptBuilder.EVERYDAY_SCHEMA's
        // naturalness_score/interaction_score/comprehension_repair_score/fluency_score keys, not
        // the clinical schema. Stored into the same EvaluationResult/SessionEntity fields that
        // ScoreDomains.normalizeSurvivalScores already expects to fall back-read from (naturalness
        // <- grammar column, interaction <- medical_accuracy column, comprehension_repair <-
        // clinical_reasoning column) rather than adding parallel DB columns.
        val nestedScores = root.optJSONObject("overall_scores")
        // Both the flat (grammar_score, ...) and nested (overall_scores.grammar, ...) shapes are
        // supposed to be 0-100 per EvalPromptBuilder's schema, but models occasionally slip into a
        // 0-10 global-rating-scale habit anyway (that convention is common for clinical/OSCE-style
        // scoring) regardless of which shape they answer in. Rescale defensively on both branches so
        // a stray "grammar_score": 4 doesn't render as 4/100 instead of the intended ~40/100.
        // Clamp to the documented 0-100 range on top of the rescale above: models occasionally
        // return an out-of-schema value (a raw sub-score total instead of a percentage, or a
        // stray negative), and an unclamped score corrupts the Feedback/History UI and any
        // Dashboard average derived from it far more visibly than a slightly-off estimate would.
        fun score(flatKey: String, nestedKey: String): Double = when {
            root.has(flatKey) -> {
                val raw = root.optDouble(flatKey, 0.0)
                (if (raw in 0.0..10.0) raw * 10.0 else raw).coerceIn(0.0, 100.0)
            }
            nestedScores?.has(nestedKey) == true -> {
                val raw = nestedScores.optDouble(nestedKey, 0.0)
                (if (raw in 0.0..10.0) raw * 10.0 else raw).coerceIn(0.0, 100.0)
            }
            else -> 0.0
        }
        val grammarOrNaturalness = if (everyday) {
            score("naturalness_score", "naturalness")
        } else {
            score("grammar_score", "grammar")
        }
        val medicalAccuracyOrInteraction = if (everyday) {
            score("interaction_score", "interaction")
        } else {
            score("medical_accuracy_score", "medical_accuracy")
        }
        val clinicalReasoningOrRepair = if (everyday) {
            score("comprehension_repair_score", "comprehension_repair")
        } else {
            score("clinical_reasoning_score", "clinical_reasoning")
        }
        val fluency = score("fluency_score", "fluency")

        val empathy = root.optJSONArray("empathy_markers_found") ?: JSONArray()
        // Study artifacts are derived only from corrections that survived deterministic
        // transcript grounding; never trust a separate ungrounded model-generated card list.
        val anki = JSONArray().apply {
            correctionsList.forEach { correction ->
                put(
                    JSONObject()
                        .put("front", correction.original)
                        .put("back", correction.corrected)
                        .put("tags", JSONArray().put(correction.category))
                )
            }
        }
        val shadowing = JSONArray().apply {
            correctionsList.take(5).forEach { correction ->
                put(
                    JSONObject()
                        .put("original", correction.original)
                        .put("target", correction.corrected)
                        .put("focus", correction.explanation)
                )
            }
        }
        val commitments = root.optJSONArray("commitment_results") ?: JSONArray()
        val soap = if (everyday) "" else formatSoap(root.opt("soap_note"))
        val deterministicFollowUpCompleteness = if (isFollowUp) {
            com.example.medvoicetrainer.analysis.FollowUpEvaluation.completeness(checklistArray)
                ?: com.example.medvoicetrainer.analysis.FollowUpEvaluation.boundedLegacyCompleteness(
                    root.opt("follow_up_completeness")?.takeUnless { it == JSONObject.NULL },
                )
        } else null
        val followUpFeedbackJson = if (isFollowUp) {
            JSONObject()
                .put("is_follow_up", true)
                .put(
                    "follow_up_completeness",
                    deterministicFollowUpCompleteness ?: JSONObject.NULL,
                )
                .put(
                    "shared_plan",
                    root.optJSONObject("shared_plan") ?: JSONObject(),
                )
                .toString()
        } else "{}"
        val checklistResultsJson = checklistArray?.toString() ?: JSONArray().apply {
            checklistChecked.forEach { (item, passed) ->
                put(
                    JSONObject()
                        .put("item", item)
                        .put("required", true)
                        .put("passed", passed)
                        .put("evidence", JSONObject.NULL)
                )
            }
        }.toString()
        val iceElicited = when (val rawIce = root.opt("ice_elicited")) {
            is Boolean -> rawIce
            is Number -> rawIce.toInt() != 0
            is String -> rawIce.equals("true", ignoreCase = true) || rawIce == "1"
            else -> false
        }

        return EvaluationResult(
            grammarScore = grammarOrNaturalness,
            medicalAccuracy = medicalAccuracyOrInteraction,
            clinicalReasoning = clinicalReasoningOrRepair,
            professionalism = if (everyday) fluency else score("professionalism_score", "professionalism"),
            fluencyScore = fluency,
            summaryFeedback = root.optString("summary_feedback", "AI feedback returned no summary."),
            soapNote = soap,
            corrections = correctionsList,
            rawCorrectionsJson = groundedCorrectionsArray.toString(),
            wordCount = wordCount,
            // Unused placeholders — see the comment in generateMockEvaluation() above; real
            // values come from FluencyMetrics.computeFluencyMetrics() in finishSession().
            wpm = 0.0,
            fillerRate = 0.0,
            checklistChecked = checklistChecked,
            analysisDomain = analysisDomain,
            caseName = state.caseName,
            checklistResultsJson = checklistResultsJson,
            historyCompleteness = root.optDouble("history_completeness", 0.0).coerceIn(0.0, 1.0),
            iceElicited = iceElicited,
            empathyMarkersJson = empathy.toString(),
            ankiCardsJson = anki.toString(),
            shadowingItemsJson = shadowing.toString(),
            commitmentResultsJson = commitments.toString(),
            referenceSoap = referenceSoapFromCase(state.caseJson),
            followUpFeedbackJson = followUpFeedbackJson,
            misconceptionReviewJson = misconceptionReviewJson,
            nursingScorecardJson = if (everyday) "{}" else {
                com.example.medvoicetrainer.analysis.NursingScorecard.build(state.caseJson, root, state.transcript)
                    ?.let { com.example.medvoicetrainer.analysis.NursingScorecard.toJson(it).toString() } ?: "{}"
            },
        )
    }

    /**
     * Ported from session_base.py's Socratic-debrief close: persists the debrief chat (was pure
     * in-memory Compose state before this — the whole conversation vanished on navigate-back,
     * with nothing written to SessionEntity.debriefChat), and — if the learner engaged enough
     * (DebriefEngine.worthExtracting, was dead code) — extracts corrections/commitments via
     * DebriefEngine.extractDebriefInsights and ingests them through SrsEngine.ingestCorrections
     * (also dead code until now) + DebriefCommitmentEntity inserts, exactly like a normal
     * session's corrections feed the SRS loop.
     */
    fun finalizeDebrief(chatHistory: List<Pair<String, String>>, kickoffMessage: String) {
        val sessionId = lastSessionId ?: return
        if (chatHistory.isEmpty()) return

        viewModelScope.launch {
            val chatJson = JSONArray().apply {
                chatHistory.forEach { (role, text) ->
                    put(JSONObject().put("role", role).put("text", text))
                }
            }.toString()
            try {
                repository.saveDebriefChat(sessionId, chatJson)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // best-effort — don't block the user leaving the screen
            }

            if (!com.example.medvoicetrainer.analysis.DebriefEngine.worthExtracting(chatHistory, kickoffMessage)) {
                return@launch
            }

            try {
                val backend = _analysisBackend.value
                val apiKey = getApiKeyForBackend(backend)
                if (apiKey.isEmpty()) return@launch
                val model = getModelForBackend(backend)

                val insights = com.example.medvoicetrainer.analysis.DebriefEngine.extractDebriefInsights(
                    backend = backend,
                    apiKey = apiKey,
                    modelId = model,
                    messages = chatHistory,
                    analysisSummaryJson = null
                )
                repository.saveDebriefInsights(
                    sessionId,
                    kotlinx.serialization.json.Json.encodeToString(
                        com.example.medvoicetrainer.analysis.DebriefInsights.serializer(),
                        insights
                    )
                )

                // Ported from debrief_engine.py's ingest_debrief_insights: each commitment goes
                // through add_debrief_commitment's fuzzy dedup + open-set cap (a restated open
                // commitment refreshes its timestamp instead of spawning a sibling row), not a
                // bare insert — was calling repository.insertCommitment() directly before, which
                // skipped both the dedup and the overflow eviction Repository.addDebriefCommitment
                // already implements.
                insights.commitments.forEach { c ->
                    repository.addDebriefCommitment(sessionId, c.text, c.focus_area)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // Insight extraction is a supplementary enhancement — never blocks the user.
            }
        }
    }

    /**
     * Analyze a standalone English-coach conversation (the always-on home-screen coach, opened
     * without a practice session) and feed what it surfaces back into the learner's SRS loop —
     * exactly like [finalizeDebrief], minus the session-bound persistence.
     *
     * There is no session row to attach the chat to, so unlike the post-session debrief we do NOT
     * save the transcript or write debrief insights onto a session (doing so would corrupt an
     * unrelated older session). We DO still mine the conversation for new corrections and
     * commitments and route them through the same [SrsEngine]/daily-mission pipeline with a null
     * originating session, so a coaching-only session still improves the mistake genome and the
     * SRS review queue. Best-effort throughout: any failure here never blocks the user leaving.
     */
    fun finalizeStandaloneCoach(chatHistory: List<Pair<String, String>>) {
        if (chatHistory.isEmpty()) return
        if (!com.example.medvoicetrainer.analysis.DebriefEngine.worthExtracting(chatHistory, "")) return

        viewModelScope.launch {
            try {
                val backend = _analysisBackend.value
                val apiKey = getApiKeyForBackend(backend)
                if (apiKey.isEmpty()) return@launch
                val model = getModelForBackend(backend)

                val insights = com.example.medvoicetrainer.analysis.DebriefEngine.extractDebriefInsights(
                    backend = backend,
                    apiKey = apiKey,
                    modelId = model,
                    messages = chatHistory,
                    analysisSummaryJson = null
                )

                // No owning session — corrections/commitments are captured with a null session id
                // (see SrsEngine.ingestCorrections / Repository.addDebriefCommitment, both nullable).
                insights.commitments.forEach { c ->
                    repository.addDebriefCommitment(null, c.text, c.focus_area)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // Coaching-side SRS enrichment is supplementary — never blocks the user.
            }
        }
    }

    /**
     * Upsert one or more learner-approved corrections. Merely failing to observe an error in a
     * later session never counts as mastery; only an explicit SRS/redo review advances the ladder.
     */
    private fun saveCorrectionsToSRS(
        corrections: List<SrsCorrection>,
        sessionId: Int?,
        domain: String = "clinical"
    ) {
        viewModelScope.launch {
            val now = Date()
            val cal = Calendar.getInstance()
            val nowISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now)
            
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dueTomorrowISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time)
            val observedCal = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }
            val observedDueISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(observedCal.time)

            val existingItems = try {
                repository.errorItems.first().toMutableList()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                mutableListOf()
            }

            for (corr in corrections) {
                val correctedVal = corr.corrected.trim()
                val originalVal = corr.original.trim()
                if (originalVal.isEmpty() || correctedVal.isEmpty()) continue

                // Every supported L1: structurally re-classify blank/"other" tags from the actual
                // edit evidence so SRS and Dashboard use the same canonical categories.
                val refinedCategory = refinedL1Category(corr)
                val categoryVal = refinedCategory.takeIf { it.isNotBlank() && it != "other" } ?: if (corr.category.isNotEmpty() && corr.category != "other") {
                    corr.category
                } else if (corr.explanation.isNotEmpty()) {
                    corr.explanation // fallback
                } else {
                    "Grammar/Vocabulary"
                }

                val isPronunciation = com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                    .isPronunciation(categoryVal)
                val patternId = if (isPronunciation) {
                    com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.evidenceKey(
                        categoryVal,
                        correctedVal
                    )
                } else {
                    com.example.medvoicetrainer.analysis.CorrectionPatternId.derive(
                        corr.patternId,
                        categoryVal,
                        originalVal,
                        correctedVal
                    )
                }
                val hasPriorPronunciationEvidence =
                    com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
                        .hasPriorSessionEvidence(categoryVal, correctedVal, sessionId, existingItems)

                // A second session confirming the same sound/stress pattern promotes all earlier
                // observations for that pattern. One noisy model finding never becomes homework.
                if (isPronunciation && hasPriorPronunciationEvidence) {
                    existingItems.filter {
                        it.state == com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE &&
                            it.patternId == patternId
                    }.forEach { observed ->
                        val promoted = observed.copy(state = "new", dueAt = nowISO)
                        repository.updateErrorItem(promoted)
                        existingItems[existingItems.indexOf(observed)] = promoted
                    }
                }

                val matchedKey = ErrorIdentity.bestMatch(
                    correctedVal,
                    originalVal,
                    categoryVal,
                    existingItems,
                    patternId = patternId
                )
                
                if (matchedKey != null) {
                    val matchedItem = existingItems.find { it.key == matchedKey }
                    if (matchedItem != null) {
                        val nextState = if (
                            matchedItem.state == com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE &&
                            !hasPriorPronunciationEvidence
                        ) {
                            com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE
                        } else if (matchedItem.state == "mastered") {
                            "learning"
                        } else {
                            matchedItem.state
                        }
                        val updatedItem = matchedItem.copy(
                            original = originalVal,
                            corrected = correctedVal,
                            explanation = corr.explanation,
                            seenCount = matchedItem.seenCount + 1,
                            correctStreak = 0,
                            intervalDays = 1,
                            absentStreak = 0,
                            dueAt = if (nextState == com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE) matchedItem.dueAt else nowISO,
                            lastSeen = nowISO,
                            lastSessionId = sessionId,
                            state = nextState,
                            patternId = patternId
                        )
                        repository.updateErrorItem(updatedItem)
                        existingItems[existingItems.indexOf(matchedItem)] = updatedItem
                    }
                } else {
                    val normalizedKey = ErrorIdentity.normalize(correctedVal)
                    val initialState = if (isPronunciation && !hasPriorPronunciationEvidence) {
                        com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE
                    } else {
                        "new"
                    }
                    val newItem = ErrorItemEntity(
                        key = if (isPronunciation) {
                            "pron:$patternId"
                        } else {
                            "pattern:$patternId"
                        },
                        category = categoryVal,
                        original = originalVal,
                        corrected = correctedVal,
                        explanation = corr.explanation,
                        state = initialState,
                        seenCount = 1,
                        correctStreak = 0,
                        intervalDays = 1,
                        dueAt = if (initialState == com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE) observedDueISO else dueTomorrowISO,
                        firstSeen = nowISO,
                        lastSeen = nowISO,
                        lastSessionId = sessionId,
                        absentStreak = 0,
                        lapses = 0,
                        domain = domain,
                        patternId = patternId
                    )
                    repository.insertErrorItem(newItem)
                    existingItems.add(newItem)
                }
            }

        }
    }

    /**
     * Undo an accepted finding without deleting unrelated learning history. A newly created item
     * from this session is removed; a recurrent item is decremented and kept for earlier evidence.
     */
    private fun removeCorrectionFromSRS(correction: SrsCorrection, sessionId: Int?) {
        viewModelScope.launch {
            val items = try {
                repository.errorItems.first()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                return@launch
            }
            val category = refinedL1Category(correction)
            val patternId = if (
                com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.isPronunciation(category)
            ) {
                com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.evidenceKey(
                    category,
                    correction.corrected
                )
            } else {
                com.example.medvoicetrainer.analysis.CorrectionPatternId.derive(
                    correction.patternId,
                    category,
                    correction.original,
                    correction.corrected
                )
            }
            val key = ErrorIdentity.bestMatch(
                correction.corrected,
                correction.original,
                category,
                items,
                patternId = patternId
            ) ?: return@launch
            val item = items.firstOrNull { it.key == key } ?: return@launch
            if (item.lastSessionId != sessionId) return@launch
            if (item.seenCount <= 1) {
                repository.deleteErrorItemByKey(item.key)
            } else {
                repository.updateErrorItem(
                    item.copy(
                        seenCount = item.seenCount - 1,
                        lastSessionId = null,
                        correctStreak = 0,
                        state = if (item.state == "mastered") "learning" else item.state
                    )
                )
            }
        }
    }

    /** Parse a review case's `target_corrections` (embedded by ReviewBuilder.buildReviewCase) back
     *  into [ReviewTarget]s, preserving the DB `key` SrsEngine.updateAfterReview needs. */
    private fun parseReviewTargets(caseJson: String): List<com.example.medvoicetrainer.analysis.ReviewTarget> {
        return try {
            val arr = JSONObject(caseJson.ifBlank { "{}" }).optJSONArray("target_corrections")
                ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                com.example.medvoicetrainer.analysis.ReviewTarget(
                    key = o.optString("key").ifBlank { null },
                    original = o.optString("original"),
                    corrected = o.optString("corrected"),
                    explanation = o.optString("explanation"),
                    count = 1
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** Normalize a checklist_results JSON array into the `List<Map>` shape SrsEngine expects. */
    private fun checklistResultsToMaps(json: String): List<Map<String, Any?>> {
        return try {
            val arr = JSONArray(json.ifBlank { "[]" })
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val m = LinkedHashMap<String, Any?>()
                for (k in o.keys()) m[k] = o.opt(k)
                m
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** Attach evidence-confidence metadata; this never changes the model's numeric scores. */
    private fun applyEvidenceConfidence(
        evaluation: EvaluationResult,
        transcript: List<Map<String, Any?>>,
        caseData: Map<String, Any?>,
    ): EvaluationResult {
        if (evaluation.evaluationLocked) return evaluation
        return try {
            val overallScores = if (evaluation.analysisDomain == "everyday") {
                mapOf(
                    "naturalness_score" to evaluation.grammarScore / 10.0,
                    "interaction_score" to evaluation.medicalAccuracy / 10.0,
                    "comprehension_repair_score" to evaluation.clinicalReasoning / 10.0,
                    "fluency_score" to evaluation.fluencyScore / 10.0,
                )
            } else {
                mapOf(
                    "grammar_score" to evaluation.grammarScore / 10.0,
                    "medical_accuracy_score" to evaluation.medicalAccuracy / 10.0,
                    "clinical_reasoning_score" to evaluation.clinicalReasoning / 10.0,
                    "professionalism_score" to evaluation.professionalism / 10.0,
                    "fluency_score" to evaluation.fluencyScore / 10.0,
                )
            }
            val badge = ScoringCalibration.buildScoreReliability(
                analysis = mapOf(
                    "overall_scores" to overallScores,
                    "checklist_results" to checklistResultsToMaps(evaluation.checklistResultsJson),
                ),
                transcript = transcript,
                evalData = null,
                caseData = caseData,
            )
            val confidence = (badge["confidence"] as? String)
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it in setOf("HIGH", "MEDIUM", "LOW") }
                ?: "LOW"
            evaluation.copy(reliability = confidence, reliabilityBadge = badge)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            evaluation.copy(reliability = "LOW")
        }
    }

    // --- Speaking practice (pronunciation record-and-check) ---

    /** Azure is preferred when configured; Gemini remains the automatic fallback. */
    fun isSpeakingJudgeAvailable(): Boolean =
        hasAzureSpeechCredentials() || getApiKeyForBackend("gemini").isNotBlank()

    /** [judgeTransferAttempt] runs on Gemini only — Azure credentials cannot grade a transfer. */
    fun isTransferJudgeAvailable(): Boolean = getApiKeyForBackend("gemini").isNotBlank()

    /**
     * Judge one recorded attempt at [targetText] for intelligibility (never accent — see
     * SpeakingAttemptJudge's prompt). Null on any failure so callers degrade gracefully.
     */
    suspend fun judgeSpeakingAttempt(
        targetText: String,
        audioPcm: ByteArray,
        sampleRate: Int = com.example.medvoicetrainer.voice.AttemptRecorder.SAMPLE_RATE,
        focusHint: String = ""
    ): com.example.medvoicetrainer.analysis.SpeakingJudgment? {
        val geminiKey = getApiKeyForBackend("gemini")
        val strict = _strictListenerEnabled.value
        if (hasAzureSpeechCredentials()) {
            val azureJudgment = runCatching {
                com.example.medvoicetrainer.analysis.SpeakingAttemptJudge.judgeWithAzure(
                    azureKey = _azureSpeechKey.value,
                    azureRegion = _azureSpeechRegion.value,
                    geminiApiKey = geminiKey,
                    geminiModel = getModelForBackend("gemini"),
                    audioBytes = audioPcm,
                    sampleRate = sampleRate,
                    targetText = targetText,
                    l1Lang = _nativeLanguage.value,
                    focusHint = focusHint,
                    strict = strict
                )
            }.getOrNull()
            if (azureJudgment != null) return azureJudgment
        }
        if (geminiKey.isBlank()) return null
        return try {
            com.example.medvoicetrainer.analysis.SpeakingAttemptJudge.judge(
                apiKey = geminiKey,
                model = getModelForBackend("gemini"),
                audioBytes = audioPcm,
                sampleRate = sampleRate,
                targetText = targetText,
                l1Lang = _nativeLanguage.value,
                focusHint = focusHint,
                strict = strict
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /** Save an explicit Say It AI result so the next visit can bring weak phrases back first. */
    fun recordSayItVerdict(category: String, phrase: String, judgment: com.example.medvoicetrainer.analysis.SpeakingJudgment) {
        val tracker = com.example.medvoicetrainer.analysis.SayItProgressTracker
        val id = tracker.phraseId(category, phrase)
        // The verdict is also what the kept recording is labelled with, and an unassessable
        // recording still gets its label even though it deliberately changes no phrase progress.
        tagLatestSayItTake(id, judgment.outcome.name)
        val updated = tracker.record(_sayItProgress.value[id], judgment) ?: return
        val next = _sayItProgress.value + (id to updated)
        _sayItProgress.value = next
        repository.setSetting(tracker.SETTING_KEY, tracker.write(next))
    }

    /**
     * Keep one Say It recording so the learner can play their own attempt back on a later visit.
     * Governed by the same "save my voice clips" switch as session audio: with it off, nothing the
     * learner says is written to disk. Normalized on the way in so a take recorded at arm's length
     * plays back at a usable level, and silently dropped if the recording holds no speech.
     */
    fun saveSayItTake(category: String, phrase: String, audioPcm: ByteArray) {
        if (!_learnerAudioSavingEnabled.value) return
        val log = com.example.medvoicetrainer.analysis.SayItTakeLog
        val id = com.example.medvoicetrainer.analysis.SayItProgressTracker.phraseId(category, phrase)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val recordedAt = System.currentTimeMillis()
            val normalized = com.example.medvoicetrainer.voice.AudioEffects.normalizeForSpeech(
                audioPcm,
                com.example.medvoicetrainer.voice.AttemptRecorder.SAMPLE_RATE,
            )
            val clip = learnerAudioStore.saveDrillTake(id, normalized, recordedAt) ?: return@launch
            sayItTakeMutex.withLock {
                val update = log.add(
                    _sayItTakes.value,
                    id,
                    com.example.medvoicetrainer.analysis.SayItTake(
                        path = clip.relativePath,
                        durationMs = clip.durationMs,
                        recordedAt = recordedAt,
                    ),
                )
                update.evicted.forEach { learnerAudioStore.deleteClip(it.path) }
                publishSayItTakes(update.log)
            }
        }
    }

    /** Forget one kept recording, index entry and audio file together. */
    fun deleteSayItTake(category: String, phrase: String, path: String) {
        val log = com.example.medvoicetrainer.analysis.SayItTakeLog
        val id = com.example.medvoicetrainer.analysis.SayItProgressTracker.phraseId(category, phrase)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sayItTakeMutex.withLock {
                publishSayItTakes(log.remove(_sayItTakes.value, id, path))
            }
            learnerAudioStore.deleteClip(path)
        }
    }

    /** Resolve a kept recording for playback; null once its file is gone (e.g. after a restore). */
    fun resolveLearnerAudio(relativePath: String): java.io.File? =
        learnerAudioStore.resolve(relativePath)

    private fun tagLatestSayItTake(phraseId: String, outcome: String) {
        val log = com.example.medvoicetrainer.analysis.SayItTakeLog
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sayItTakeMutex.withLock {
                val tagged = log.tagLatest(_sayItTakes.value, phraseId, outcome)
                if (tagged != _sayItTakes.value) publishSayItTakes(tagged)
            }
        }
    }

    private fun publishSayItTakes(next: Map<String, List<com.example.medvoicetrainer.analysis.SayItTake>>) {
        _sayItTakes.value = next
        repository.setSetting(
            com.example.medvoicetrainer.analysis.SayItTakeLog.SETTING_KEY,
            com.example.medvoicetrainer.analysis.SayItTakeLog.write(next),
        )
    }

    /**
     * Judge one spoken REDO attempt (feedback screen: re-say the corrected sentence right after
     * the session). Unlike [judgeSpeakingAttempt] the judge also fails attempts that reproduce
     * the old mistake. Null on any failure so the panel degrades to self-comparison.
     */
    suspend fun judgeRedoAttempt(
        targetText: String,
        originalText: String,
        audioPcm: ByteArray,
        sampleRate: Int = com.example.medvoicetrainer.voice.AttemptRecorder.SAMPLE_RATE,
        focusHint: String = ""
    ): com.example.medvoicetrainer.analysis.SpeakingJudgment? {
        val apiKey = getApiKeyForBackend("gemini")
        if (apiKey.isBlank()) return null
        return try {
            com.example.medvoicetrainer.analysis.SpokenReviewJudge.judgeRedo(
                apiKey = apiKey,
                model = getModelForBackend("gemini"),
                audioBytes = audioPcm,
                sampleRate = sampleRate,
                targetText = targetText,
                originalText = originalText,
                l1Lang = _nativeLanguage.value,
                focusHint = focusHint
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /**
     * Judge one spoken TRANSFER attempt (SRS screen: use the corrected form in one new sentence
     * fitting a fresh scenario). Null on any failure so the panel degrades to self-grading.
     */
    suspend fun judgeTransferAttempt(
        scenario: String,
        targetText: String,
        originalText: String,
        audioPcm: ByteArray,
        sampleRate: Int = com.example.medvoicetrainer.voice.AttemptRecorder.SAMPLE_RATE
    ): com.example.medvoicetrainer.analysis.SpeakingJudgment? {
        val apiKey = getApiKeyForBackend("gemini")
        if (apiKey.isBlank()) return null
        return try {
            com.example.medvoicetrainer.analysis.SpokenReviewJudge.judgeTransfer(
                apiKey = apiKey,
                model = getModelForBackend("gemini"),
                audioBytes = audioPcm,
                sampleRate = sampleRate,
                scenario = scenario,
                targetText = targetText,
                originalText = originalText,
                l1Lang = _nativeLanguage.value
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /** One synthesized Listening Lab narration clip, ready to hand to an AudioTrack. */
    data class ListeningNarration(
        val pcm: ByteArray,
        val sampleRate: Int,
        val voiceName: String,
        val provider: String,
        val fromCache: Boolean
    )

    /**
     * Synthesize (or reuse a cached synthesis of) one Listening Lab drill's transcript in the
     * requested accent/gender, via Gemini's TTS-capable `generateContent` audio-output modality —
     * the only cloud TTS backend this app currently wires for narration (see GeminiService.synthesizeSpeech).
     * Returns null when no Gemini key is configured or synthesis fails for every model in the
     * fallback chain, so the caller can degrade to on-device TextToSpeech. The same
     * (drillId, accent, genderPreference) combo always resolves to the same voice
     * ([VoiceCatalog.voiceForKey] is deterministic) and, within [ListeningAudioCache]'s TTL, the
     * same audio bytes — so "Play again"/"Ask to repeat" never re-pays for an identical clip.
     */
    suspend fun synthesizeListeningNarration(
        drillId: String,
        text: String,
        accent: String,
        genderPreference: String
    ): ListeningNarration? {
        if (text.isBlank()) return null
        val apiKey = getApiKeyForBackend("gemini")
        if (apiKey.isBlank()) return null

        val cacheKey = ListeningAudioCache.Key(drillId, accent, genderPreference)
        ListeningAudioCache.get(cacheKey)?.let { clip ->
            return ListeningNarration(clip.pcm, clip.sampleRate, clip.voiceName, "gemini", fromCache = true)
        }

        val gender = VoiceCatalog.parseGender(genderPreference)
        val voice = VoiceCatalog.voiceForKey("gemini", seedKey = "$drillId|$accent|$genderPreference", gender = gender)
        val instruction = PromptBuilder.narrationInstruction(accent)
        val synthesized = try {
            GeminiService.synthesizeSpeech(apiKey, text, voiceName = voice.name, instruction = instruction)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        } ?: return null

        val (pcm, sampleRate) = synthesized
        ListeningAudioCache.put(cacheKey, ListeningAudioCache.Clip(pcm, sampleRate, voice.name))
        return ListeningNarration(pcm, sampleRate, voice.name, "gemini", fromCache = false)
    }

    /**
     * Grade the SRS item behind a feedback-screen redo attempt. The item was explicitly accepted
     * just before this drill, so it is found by the same fuzzy identity match;
     * a passed redo is genuine spoken retrieval and advances the ladder like any correct review.
     * No-op if no matching item exists (e.g. analysis persisted nothing for this correction).
     */
    fun applySpokenRedoResult(original: String, corrected: String, category: String, pass: Boolean) {
        viewModelScope.launch {
            try {
                val items = repository.errorItems.first()
                val key = ErrorIdentity.bestMatch(corrected.trim(), original.trim(), category, items)
                    ?: return@launch
                val item = items.find { it.key == key } ?: return@launch
                submitSrsAnswer(item, pass, countsTowardMastery = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // SRS credit is best-effort; never break the feedback screen.
            }
        }
    }

    // --- Spaced Repetition (SRS) Engine Quizzes ---
    fun submitSrsAnswer(
        item: ErrorItemEntity,
        wasCorrect: Boolean,
        countsTowardMastery: Boolean = true
    ) {
        viewModelScope.launch {
            val now = Date()
            val cal = Calendar.getInstance()
            val nowISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now)

            val srsIntervals = listOf(1, 3, 7, 14, 30)
            val masteryStreak = 3

            val updatedItem = if (
                item.state == com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE &&
                wasCorrect
            ) {
                // A successful immediate redo suggests the first finding was transient. Keep it
                // as evidence, but do not schedule homework until a later session confirms it.
                cal.add(Calendar.DAY_OF_YEAR, 7)
                item.copy(
                    correctStreak = 1,
                    intervalDays = 7,
                    dueAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time),
                    lastSeen = nowISO,
                    seenCount = item.seenCount + 1,
                    state = com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.OBSERVED_STATE
                )
            } else if (wasCorrect && !countsTowardMastery) {
                // An immediate redo is useful encoding practice, not spaced retrieval evidence.
                cal.add(Calendar.DAY_OF_YEAR, 1)
                item.copy(
                    dueAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time),
                    lastSeen = nowISO,
                    seenCount = item.seenCount + 1,
                    absentStreak = 0,
                    state = "learning"
                )
            } else if (wasCorrect) {
                val newStreak = item.correctStreak + 1
                val newInterval = srsIntervals[minOf(newStreak, srsIntervals.size - 1)]
                cal.add(Calendar.DAY_OF_YEAR, newInterval)
                val newDueISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time)
                val state = if (newStreak >= masteryStreak) "mastered" else "learning"

                item.copy(
                    correctStreak = newStreak,
                    intervalDays = newInterval,
                    dueAt = newDueISO,
                    lastSeen = nowISO,
                    seenCount = item.seenCount + 1,
                    absentStreak = 0,
                    state = state
                )
            } else {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val newDueISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time)

                item.copy(
                    correctStreak = 0,
                    intervalDays = 1,
                    dueAt = newDueISO,
                    lastSeen = nowISO,
                    seenCount = item.seenCount + 1,
                    absentStreak = 0,
                    lapses = item.lapses + 1,
                    state = "learning"
                )
            }
            repository.updateErrorItem(updatedItem)
            if (com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy.isPronunciation(item.category)) {
                val settingKey = "pronunciation_practice_history"
                repository.setSetting(
                    settingKey,
                    com.example.medvoicetrainer.analysis.PronunciationProgressTracker.append(
                        repository.getSetting(settingKey, "[]"),
                        timestamp = nowISO,
                        category = item.category,
                        target = item.corrected,
                        passed = wasCorrect
                    )
                )
            }
        }
    }

    fun pronunciationWeeklyProgress(): com.example.medvoicetrainer.analysis.PronunciationProgressSummary {
        val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
        val since = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(weekAgo.time)
        return com.example.medvoicetrainer.analysis.PronunciationProgressTracker.summarize(
            repository.getSetting("pronunciation_practice_history", "[]"),
            since
        )
    }

    // --- Listening Lab Attempts ---
    // Mirrors listening_queries.py's record_listening_attempt(): computes assistance detection,
    // unaided/assisted accuracy split, and the next spaced-repetition interval, then reports the
    // new attempt's id back (Python's record_listening_attempt() returns it synchronously so the
    // caller can log post-commit reveals/ratings against the same row).
    fun saveListeningAttempt(
        drillId: String,
        category: String?,
        difficulty: Int,
        skillTags: List<String> = emptyList(),
        score: Int,
        total: Int,
        replayCount: Int = 0,
        cleanReplayCount: Int = 0,
        repairStrategies: List<String> = emptyList(),
        accent: String? = null,
        voiceName: String? = null,
        provider: String? = null,
        audioSource: String? = null,
        onSaved: (Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val attemptId = repository.recordListeningAttempt(
                drillId = drillId,
                detailsCorrect = score,
                detailsTotal = total,
                category = category,
                difficulty = difficulty,
                skillTags = skillTags,
                replayCount = replayCount,
                cleanReplayCount = cleanReplayCount,
                repairStrategies = repairStrategies,
                accent = accent,
                voiceName = voiceName,
                provider = provider,
                audioSource = audioSource
            )
            onSaved(attemptId)
        }
    }

    // Mirrors listening_queries.py's update_listening_assistance(): logs post-commit help events
    // (reveal, further replays) without altering the already-frozen unaided/assisted evidence.
    fun logListeningAssistance(
        attemptId: Int,
        replayCount: Int,
        cleanReplayCount: Int,
        revealCount: Int,
        repairStrategies: List<String>
    ) {
        viewModelScope.launch {
            repository.updateListeningAssistance(attemptId, replayCount, cleanReplayCount, revealCount, repairStrategies)
        }
    }

    // Mirrors listening_queries.py's update_listening_ratings().
    fun saveListeningRatings(attemptId: Int, authenticity: Int?, accentMatch: Int?, naturalness: Int?) {
        viewModelScope.launch {
            repository.updateListeningRatings(attemptId, authenticity, accentMatch, naturalness)
        }
    }

    // --- Commitments ---
    fun addCommitment(text: String, focusArea: String) {
        viewModelScope.launch {
            val nowISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            repository.insertCommitment(
                DebriefCommitmentEntity(
                    createdAt = nowISO,
                    text = text,
                    focusArea = focusArea
                )
            )
        }
    }

    fun completeCommitment(id: Int) {
        viewModelScope.launch {
            repository.updateCommitmentStatus(id, "completed")
        }
    }

    override fun onCleared() {
        voiceSessionGeneration.incrementAndGet()
        openBookBriefingDecision?.cancel()
        openBookBriefingDecision = null
        activeSupportsPreReadyTextQueue = false
        voiceManager?.stop()
        voiceManager = null
        VoiceSessionService.stop(getApplication())
        super.onCleared()
    }
}

// --- Supporting State Classes ---

@kotlinx.serialization.Serializable
data class CustomPracticeProfile(
    val name: String,
    val persona: String,
    val evalTemplate: String = "diagnostic_clinical_english",
    val analysisDomain: String = "clinical",
    val customCriteria: String = ""
)

data class ActiveSessionState(
    val isActive: Boolean = false,
    val mode: String = "",
    val caseId: String = "",
    val caseName: String = "",
    val caseJson: String = "",
    val createdAt: String = "",
    // Set only by retrySessionAnalysis: that path re-finishes an already-ended session whose real
    // duration was captured (correctly, at the time) by cancelSession()'s finalizeVoiceOnlySession.
    // Without this, finishSession() falls back to sessionDurationSeconds(createdAt), which measures
    // wall-clock time from the original session start to whenever the learner happens to retry —
    // minutes or hours of waiting get recorded as if they were part of the conversation.
    val preservedDurationSeconds: Int? = null,
    val transcript: List<Pair<String, String>> = emptyList(), // Pair of role to text
    val isAILoading: Boolean = false,
    /**
     * Live checklist rows in the case's own objective order, each carrying how it was established
     * and the transcript line that justifies it. Deterministic and local — see [CoverageEngine].
     */
    val checklist: List<ChecklistItem> = emptyList(),
    /**
     * Open Book's "what this diagnosis obliges you to ask" rows, evaluated by the same rules as
     * [checklist] so the answer sheet shows live progress instead of a static list. Empty unless
     * the case has an Open Book card with an authored must-ask list. Never graded.
     */
    val openBookChecklist: List<ChecklistItem> = emptyList(),
    /**
     * Which of the everyday phrasebook's offered expressions the learner has actually produced,
     * evaluated by the same rules. Empty outside the everyday modes. Never graded — see
     * [MainViewModel.evaluatePhrasebookUsage].
     */
    val phrasebookUsage: List<ChecklistItem> = emptyList(),
    val error: String? = null,
    val status: String = "",
    val voiceConnectionState: VoiceConnectionState = VoiceConnectionState.CONNECTING,
    val microphoneState: MicrophoneState = MicrophoneState.STARTING,
    val isSessionControlBusy: Boolean = false,
    val isManualTurnSending: Boolean = false,
    /** Wall-clock pause accounting so session duration and the visible timer exclude manual pauses. */
    val pausedAtMillis: Long? = null,
    val totalPausedMillis: Long = 0L,
    // Deterministic (no-API-call) interview phase hint from InterviewPhaseTracker, "encounter" mode
    // only. Just the suggested question — this is what goes into the typing box verbatim.
    val phaseHint: String = "",
    // The phase the hint belongs to ("Meds & allergies", ...). Shown as a label next to the typing
    // box; deliberately kept out of phaseHint so it can never be sent to the patient as speech.
    val phaseHintCategory: String = "",
    /**
     * The same deterministic engine's top few suggestions, offered as a picker instead of a single
     * answer. The rules can narrow the field to a handful of open areas far more reliably than they
     * can rank the one the learner wants, so a short menu turns a mis-ranked first guess into a
     * second row on the sheet rather than a dead end.
     */
    val phaseHintOptions: List<com.example.medvoicetrainer.analysis.RescueHint> = emptyList(),
    // Raised once by WrapUpDetector when an encounter is substantially complete — drives the soft
    // "good time to wrap up" nudge near the End button. Never auto-ends; the learner stays in control.
    val wrapUpSuggested: Boolean = false,
    /** Results that existed before the conversation and were shown in the pre-encounter table. */
    val availableResults: List<com.example.medvoicetrainer.analysis.ClinicalResult> = emptyList(),
    /** Ordered, authored result bundles waiting for the learner to open. */
    val pendingInvestigationEvents: List<com.example.medvoicetrainer.analysis.InvestigationEvent> = emptyList(),
    /** Result bundles the learner opened; kept reachable from the chart button. */
    val revealedInvestigationEvents: List<com.example.medvoicetrainer.analysis.InvestigationEvent> = emptyList(),
    /**
     * Korean CPX: maneuver ids (`data/exam_maneuvers.json`) the learner has said they performed,
     * in order. Their authored findings from the case's `sp_script` are shown as a study aid.
     */
    val revealedExamManeuvers: List<String> = emptyList(),
    // True from the moment finishSession() is called until either lastEvaluation is populated
    // (session resets) or the analysis step fails — drives the §5 "Analyzing" full-screen state
    // instead of the ordinary chat view. A non-null `error` while this is true is the retry state.
    val isFinishing: Boolean = false,
    val analysisStage: FeedbackAnalysisStage = FeedbackAnalysisStage.PREPARING
) {
    /**
     * Flat met/unmet view of [checklist], which is what the guided-cue scaffold and the saved
     * session record consume. Derived rather than stored so the two can never disagree.
     */
    val checklistCoverage: Map<String, Boolean>
        get() = checklist.associate { it.objective to it.isMet }
}

/**
 * One row of the live checklist: the objective, what the rules found, and the learner's own verdict
 * if they gave one.
 *
 * The learner's tap is stored separately from the inferred evidence and always wins. Rules over
 * noisy speech recognition will sometimes miss and sometimes over-reach, and a panel that can't be
 * corrected turns every such slip into a reason to distrust the whole thing — whereas a panel that
 * can be corrected turns "did I actually ask that?" into the self-check an OSCE candidate should be
 * doing anyway.
 */
data class ChecklistItem(
    val objective: String,
    val evidence: com.example.medvoicetrainer.analysis.CoverageEvidence =
        com.example.medvoicetrainer.analysis.CoverageEvidence(),
    /** `true` ticked by hand, `false` cleared by hand, `null` following the rules. */
    val manualOverride: Boolean? = null,
) {
    val source: com.example.medvoicetrainer.analysis.CoverageSource
        get() = when (manualOverride) {
            true -> com.example.medvoicetrainer.analysis.CoverageSource.MANUAL
            false -> com.example.medvoicetrainer.analysis.CoverageSource.NONE
            null -> evidence.source
        }

    val isMet: Boolean get() = source != com.example.medvoicetrainer.analysis.CoverageSource.NONE

    /** True when the row is solid enough to present as a plain "you did this". */
    val isConfident: Boolean
        get() = source == com.example.medvoicetrainer.analysis.CoverageSource.LEARNER_STRONG ||
            source == com.example.medvoicetrainer.analysis.CoverageSource.MANUAL

    /** The transcript line behind the tick, if the tick came from something that was said. */
    val quote: String
        get() = if (manualOverride == null) evidence.quote else ""

    val quoteTurnIndex: Int
        get() = if (manualOverride == null) evidence.turnIndex else -1
}

enum class FeedbackAnalysisStage { PREPARING, SCORING, BUILDING_CARDS }

/**
 * Survival "Advanced Beta", Stage 3: what the learner "found out" while running an errand alone,
 * shown as a card they have to relay back to their conversation partner in English. Generated by
 * template composition (SceneTransitionCatalog) — no LLM call, no cost, and an exact known list the
 * post-session analysis can grade the report against.
 */
data class RelayFactCard(
    val place: String,
    val facts: List<String>,
    /**
     * True while the learner is still "away" reading these. The scene — and the acceptance the
     * model is waiting on — is deliberately held until they say they are back, so the partner
     * cannot start asking about the errand before they have finished reading it.
     */
    val awaitingReturn: Boolean = false,
)

/** Which existing rubric/mode a pasted conversation import should be scored against — see
 *  MainViewModel.importTranscript. Deliberately reuses real mode strings ("encounter", "survival",
 *  "interview") rather than inventing a separate "imported" mode, so an imported session is a full
 *  citizen of History/Dashboard/SRS filtering exactly like a live one of that mode would be. */
enum class ImportDomain {
    CLINICAL,
    SURVIVAL,
    INTERVIEW
}

data class ImportedCaseSpec(
    val mode: String,
    val caseId: String,
    val caseName: String,
    val caseJson: String
)

data class ImportUiState(
    val isAnalyzing: Boolean = false,
    val error: String? = null
)

class SrsCorrection(
    original: String,
    corrected: String,
    explanation: String,
    val category: String = "other",
    // Index into the session transcript (same key LearnerAudioStore clips use) for corrections
    // that came from a specific spoken turn — pronunciation items only today. Deliberately
    // excluded from equals/hashCode so dedup semantics stay purely text-based.
    val turnIndex: Int? = null,
    confidence: Double = 1.0,
    val feedbackType: String = "error",
    l1Hypothesis: String = "",
    val evidenceSource: String = "transcript",
    val patternId: String = "",
    val audioStartMs: Int? = null,
    val audioEndMs: Int? = null
) {
    val original: String = original.trim()
    val corrected: String = corrected.trim()
    val explanation: String = explanation.replace(Regex("<[^>]*>"), "").take(500)
    val confidence: Double = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    val l1Hypothesis: String = l1Hypothesis.replace(Regex("<[^>]*>"), "").trim().take(240)

    init {
        require(this.original.isNotBlank() && this.corrected.isNotBlank()) {
            "Original and target text cannot be empty"
        }
        require(this.original.lowercase() != this.corrected.lowercase()) {
            "Original and target text must not be identical"
        }
    }

    fun copy(
        original: String = this.original,
        corrected: String = this.corrected,
        explanation: String = this.explanation,
        category: String = this.category,
        turnIndex: Int? = this.turnIndex,
        confidence: Double = this.confidence,
        feedbackType: String = this.feedbackType,
        l1Hypothesis: String = this.l1Hypothesis,
        evidenceSource: String = this.evidenceSource,
        patternId: String = this.patternId,
        audioStartMs: Int? = this.audioStartMs,
        audioEndMs: Int? = this.audioEndMs
    ): SrsCorrection = SrsCorrection(
        original, corrected, explanation, category, turnIndex, confidence,
        feedbackType, l1Hypothesis, evidenceSource, patternId, audioStartMs, audioEndMs
    )

    operator fun component1(): String = original
    operator fun component2(): String = corrected
    operator fun component3(): String = explanation
    operator fun component4(): String = category

    fun decisionKey(): String = listOf(
        turnIndex?.toString() ?: "-1",
        category.trim().lowercase(Locale.ROOT),
        patternId.trim().lowercase(Locale.ROOT),
        ErrorIdentity.normalize(original),
        ErrorIdentity.normalize(corrected)
    ).joinToString("|")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SrsCorrection) return false
        return original == other.original &&
                corrected == other.corrected &&
                explanation == other.explanation &&
                category == other.category
    }

    override fun hashCode(): Int {
        var result = original.hashCode()
        result = 31 * result + corrected.hashCode()
        result = 31 * result + explanation.hashCode()
        result = 31 * result + category.hashCode()
        return result
    }

    override fun toString(): String {
        return "SrsCorrection(original=$original, corrected=$corrected, explanation=$explanation, category=$category)"
    }
}

/** A Korean CPX card on screen, either just graded or reopened from CPX history. */
data class KmleCpxResult(
    val sessionId: Int,
    val caseName: String,
    val createdAt: String,
    val scorecard: com.example.medvoicetrainer.analysis.KmleCpxScorecard.Scorecard,
    val transcript: List<Pair<String, String>>,
    /** False when the session was saved without grading (no analysis key); it can be graded later. */
    val analyzed: Boolean,
    /** The case's scripted findings and whether the learner performed each examination. */
    val examReview: List<com.example.medvoicetrainer.analysis.KmleCpx.ExamReviewRow> = emptyList(),
)

class EvaluationResult(
    grammarScore: Double,
    medicalAccuracy: Double,
    clinicalReasoning: Double,
    professionalism: Double,
    fluencyScore: Double,
    summaryFeedback: String,
    soapNote: String,
    val corrections: List<SrsCorrection>,
    rawCorrectionsJson: String,
    wordCount: Int,
    wpm: Double,
    fillerRate: Double,
    checklistChecked: List<Pair<String, Boolean>>,
    reliability: String = "HIGH",
    // "clinical" or "everyday" — see ScoreDomains.kt/EvalPromptBuilder.kt. When "everyday",
    // grammarScore/medicalAccuracy/clinicalReasoning/fluencyScore hold the everyday rubric's
    // naturalness/interaction/comprehension_repair/fluency scores respectively (professionalism
    // is not meaningful for everyday sessions and mirrors fluencyScore as a benign default) —
    // this reuses SessionEntity's existing clinical-named columns rather than adding new ones,
    // matching the fallback mapping ScoreDomains.normalizeSurvivalScores already expects.
    val analysisDomain: String = "clinical",
    caseName: String = "",
    /** True when AI-only feedback is unavailable (typed demo or missing analysis key). */
    val evaluationLocked: Boolean = false,
    val checklistResultsJson: String = "[]",
    historyCompleteness: Double = 0.0,
    val iceElicited: Boolean = false,
    val empathyMarkersJson: String = "[]",
    val ankiCardsJson: String = "[]",
    val shadowingItemsJson: String = "[]",
    val commitmentResultsJson: String = "[]",
    val referenceSoap: String = "",
    // Deterministic, transcript-derived metrics (see finishSession()) — not from the LLM.
    // Populated after deterministic scoring/quality processing via .copy(), so null in
    // generateUnavailableEvaluation()/parseEvaluationJson()'s own constructor calls.
    val fluencyMetrics: com.example.medvoicetrainer.analysis.FluencyMetricsResult? = null,
    val intelligibility: Map<String, Any>? = null,
    // §6 reliability badge (ScoringCalibration.buildScoreReliability) — confidence/avg_score/
    // practice_band/evidence_rate/user_word_count/flags/notice. Null for demo/locked/legacy
    // evaluations; SummaryFeedbackContent falls back to the plain `reliability` name then.
    val reliabilityBadge: Map<String, Any?>? = null,
    /** Follow-up-only completeness/shared-plan payload; `{}` for all other modes. */
    val followUpFeedbackJson: String = "{}",
    /** True only for the fully local scripted demo; a missing analysis key is not a demo. */
    val isTypedDemo: Boolean = false,
    /** Transcript-grounded clinical claims that need concept repair; never used for everyday sessions. */
    misconceptionReviewJson: String = "[]",
    /** Nursing-only feedback card (see analysis/NursingScorecard.kt); `{}` for all other modes. */
    val nursingScorecardJson: String = "{}",
) {
    val wordCount: Int = run {
        require(wordCount >= 0) { "wordCount must not be negative" }
        wordCount
    }
    val fillerRate: Double = run {
        require(fillerRate >= 0.0) { "fillerRate must not be negative" }
        fillerRate.coerceIn(0.0, 1.0)
    }
    val grammarScore: Double = grammarScore.coerceIn(0.0, 100.0)
    val medicalAccuracy: Double = medicalAccuracy.coerceIn(0.0, 100.0)
    val clinicalReasoning: Double = clinicalReasoning.coerceIn(0.0, 100.0)
    val professionalism: Double = professionalism.coerceIn(0.0, 100.0)
    val fluencyScore: Double = fluencyScore.coerceIn(0.0, 100.0)
    val summaryFeedback: String = summaryFeedback.lines()
        .filterNot { it.trim().startsWith("|") && it.indexOf('|', 1) != -1 }
        .joinToString("\n")
    // Keep the model response intact. Presentation code recognizes both S/O/A/P and the full
    // section labels when they are present; forcing an unlabeled response into S used to make a
    // complete SOAP note appear as though it only had a subjective section.
    val soapNote: String = soapNote.trim()
    val rawCorrectionsJson: String = try {
        org.json.JSONArray(rawCorrectionsJson)
        rawCorrectionsJson
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        "[]"
    }
    val misconceptionReviewJson: String = try {
        org.json.JSONArray(misconceptionReviewJson)
        misconceptionReviewJson
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        "[]"
    }
    val wpm: Double = if (wpm.isInfinite() || wpm.isNaN()) 0.0 else wpm.coerceAtLeast(0.0)
    val checklistChecked: List<Pair<String, Boolean>> = checklistChecked
        .groupBy { it.first }
        .map { (key, list) -> key to list.any { it.second } }
        .take(100)
    val reliability: String = when {
        reliability !in setOf("HIGH", "MEDIUM", "LOW") -> "MEDIUM"
        this.wordCount == 50 && reliability == "LOW" -> "MEDIUM"
        else -> reliability
    }
    val caseName: String = caseName.replace("..", "").replace("/", "").replace("\\", "")
    val historyCompleteness: Double = historyCompleteness.coerceIn(0.0, 1.0)

    fun copy(
        grammarScore: Double = this.grammarScore,
        medicalAccuracy: Double = this.medicalAccuracy,
        clinicalReasoning: Double = this.clinicalReasoning,
        professionalism: Double = this.professionalism,
        fluencyScore: Double = this.fluencyScore,
        summaryFeedback: String = this.summaryFeedback,
        soapNote: String = this.soapNote,
        corrections: List<SrsCorrection> = this.corrections,
        rawCorrectionsJson: String = this.rawCorrectionsJson,
        wordCount: Int = this.wordCount,
        wpm: Double = this.wpm,
        fillerRate: Double = this.fillerRate,
        checklistChecked: List<Pair<String, Boolean>> = this.checklistChecked,
        reliability: String = this.reliability,
        analysisDomain: String = this.analysisDomain,
        caseName: String = this.caseName,
        evaluationLocked: Boolean = this.evaluationLocked,
        checklistResultsJson: String = this.checklistResultsJson,
        historyCompleteness: Double = this.historyCompleteness,
        iceElicited: Boolean = this.iceElicited,
        empathyMarkersJson: String = this.empathyMarkersJson,
        ankiCardsJson: String = this.ankiCardsJson,
        shadowingItemsJson: String = this.shadowingItemsJson,
        commitmentResultsJson: String = this.commitmentResultsJson,
        referenceSoap: String = this.referenceSoap,
        fluencyMetrics: com.example.medvoicetrainer.analysis.FluencyMetricsResult? = this.fluencyMetrics,
        intelligibility: Map<String, Any>? = this.intelligibility,
        reliabilityBadge: Map<String, Any?>? = this.reliabilityBadge,
        followUpFeedbackJson: String = this.followUpFeedbackJson,
        isTypedDemo: Boolean = this.isTypedDemo,
        misconceptionReviewJson: String = this.misconceptionReviewJson,
        nursingScorecardJson: String = this.nursingScorecardJson,
    ): EvaluationResult = EvaluationResult(
        grammarScore, medicalAccuracy, clinicalReasoning, professionalism, fluencyScore,
        summaryFeedback, soapNote, corrections, rawCorrectionsJson, wordCount, wpm,
        fillerRate, checklistChecked, reliability, analysisDomain, caseName,
        evaluationLocked, checklistResultsJson, historyCompleteness, iceElicited,
        empathyMarkersJson, ankiCardsJson, shadowingItemsJson, commitmentResultsJson,
        referenceSoap, fluencyMetrics, intelligibility, reliabilityBadge, followUpFeedbackJson, isTypedDemo,
        misconceptionReviewJson, nursingScorecardJson,
    )

    operator fun component1(): Double = grammarScore
    operator fun component2(): Double = medicalAccuracy
    operator fun component3(): Double = clinicalReasoning
    operator fun component4(): Double = professionalism
    operator fun component5(): Double = fluencyScore
    operator fun component6(): String = summaryFeedback
    operator fun component7(): String = soapNote
    operator fun component8(): List<SrsCorrection> = corrections
    operator fun component9(): String = rawCorrectionsJson
    operator fun component10(): Int = wordCount
    operator fun component11(): Double = wpm
    operator fun component12(): Double = fillerRate
    operator fun component13(): List<Pair<String, Boolean>> = checklistChecked
    operator fun component14(): String = reliability
    operator fun component15(): String = analysisDomain
    operator fun component16(): String = caseName
    operator fun component17(): Boolean = evaluationLocked
    operator fun component18(): String = checklistResultsJson
    operator fun component19(): Double = historyCompleteness
    operator fun component20(): Boolean = iceElicited
    operator fun component21(): String = empathyMarkersJson
    operator fun component22(): String = ankiCardsJson
    operator fun component23(): String = shadowingItemsJson
    operator fun component24(): String = commitmentResultsJson
    operator fun component25(): String = referenceSoap
    operator fun component26(): com.example.medvoicetrainer.analysis.FluencyMetricsResult? = fluencyMetrics
    operator fun component27(): Map<String, Any>? = intelligibility
    operator fun component28(): Map<String, Any?>? = reliabilityBadge
    operator fun component29(): String = followUpFeedbackJson
    operator fun component30(): Boolean = isTypedDemo
    operator fun component31(): String = misconceptionReviewJson
    operator fun component32(): String = nursingScorecardJson

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvaluationResult) return false
        return grammarScore == other.grammarScore &&
                medicalAccuracy == other.medicalAccuracy &&
                clinicalReasoning == other.clinicalReasoning &&
                professionalism == other.professionalism &&
                fluencyScore == other.fluencyScore &&
                summaryFeedback == other.summaryFeedback &&
                soapNote == other.soapNote &&
                corrections == other.corrections &&
                rawCorrectionsJson == other.rawCorrectionsJson &&
                wordCount == other.wordCount &&
                wpm == other.wpm &&
                fillerRate == other.fillerRate &&
                checklistChecked == other.checklistChecked &&
                reliability == other.reliability &&
                analysisDomain == other.analysisDomain &&
                caseName == other.caseName &&
                evaluationLocked == other.evaluationLocked &&
                checklistResultsJson == other.checklistResultsJson &&
                historyCompleteness == other.historyCompleteness &&
                iceElicited == other.iceElicited &&
                empathyMarkersJson == other.empathyMarkersJson &&
                ankiCardsJson == other.ankiCardsJson &&
                shadowingItemsJson == other.shadowingItemsJson &&
                commitmentResultsJson == other.commitmentResultsJson &&
                referenceSoap == other.referenceSoap &&
                misconceptionReviewJson == other.misconceptionReviewJson &&
                fluencyMetrics == other.fluencyMetrics &&
                intelligibility == other.intelligibility &&
                reliabilityBadge == other.reliabilityBadge &&
                followUpFeedbackJson == other.followUpFeedbackJson &&
                isTypedDemo == other.isTypedDemo &&
                nursingScorecardJson == other.nursingScorecardJson
    }

    override fun hashCode(): Int {
        var result = grammarScore.hashCode()
        result = 31 * result + medicalAccuracy.hashCode()
        result = 31 * result + clinicalReasoning.hashCode()
        result = 31 * result + professionalism.hashCode()
        result = 31 * result + fluencyScore.hashCode()
        result = 31 * result + summaryFeedback.hashCode()
        result = 31 * result + soapNote.hashCode()
        result = 31 * result + corrections.hashCode()
        result = 31 * result + rawCorrectionsJson.hashCode()
        result = 31 * result + wordCount
        result = 31 * result + wpm.hashCode()
        result = 31 * result + fillerRate.hashCode()
        result = 31 * result + checklistChecked.hashCode()
        result = 31 * result + reliability.hashCode()
        result = 31 * result + analysisDomain.hashCode()
        result = 31 * result + caseName.hashCode()
        result = 31 * result + evaluationLocked.hashCode()
        result = 31 * result + checklistResultsJson.hashCode()
        result = 31 * result + historyCompleteness.hashCode()
        result = 31 * result + iceElicited.hashCode()
        result = 31 * result + empathyMarkersJson.hashCode()
        result = 31 * result + ankiCardsJson.hashCode()
        result = 31 * result + shadowingItemsJson.hashCode()
        result = 31 * result + commitmentResultsJson.hashCode()
        result = 31 * result + referenceSoap.hashCode()
        result = 31 * result + misconceptionReviewJson.hashCode()
        result = 31 * result + (fluencyMetrics?.hashCode() ?: 0)
        result = 31 * result + (intelligibility?.hashCode() ?: 0)
        result = 31 * result + (reliabilityBadge?.hashCode() ?: 0)
        result = 31 * result + followUpFeedbackJson.hashCode()
        result = 31 * result + isTypedDemo.hashCode()
        result = 31 * result + nursingScorecardJson.hashCode()
        return result
    }

    override fun toString(): String {
        return "EvaluationResult(grammarScore=$grammarScore, medicalAccuracy=$medicalAccuracy, clinicalReasoning=$clinicalReasoning, professionalism=$professionalism, fluencyScore=$fluencyScore, summaryFeedback=$summaryFeedback, soapNote=$soapNote, corrections=$corrections, rawCorrectionsJson=$rawCorrectionsJson, wordCount=$wordCount, wpm=$wpm, fillerRate=$fillerRate, checklistChecked=$checklistChecked, reliability=$reliability, analysisDomain=$analysisDomain, caseName=$caseName, evaluationLocked=$evaluationLocked, checklistResultsJson=$checklistResultsJson, historyCompleteness=$historyCompleteness, iceElicited=$iceElicited, empathyMarkersJson=$empathyMarkersJson, ankiCardsJson=$ankiCardsJson, shadowingItemsJson=$shadowingItemsJson, commitmentResultsJson=$commitmentResultsJson, referenceSoap=$referenceSoap, misconceptionReviewJson=$misconceptionReviewJson, fluencyMetrics=$fluencyMetrics, intelligibility=$intelligibility, reliabilityBadge=$reliabilityBadge, followUpFeedbackJson=$followUpFeedbackJson, isTypedDemo=$isTypedDemo, nursingScorecardJson=$nursingScorecardJson)"
    }
}

/**
 * One retention "moment" surfaced right after a session finishes, in priority order —
 * ports app/ui/milestone_dialog.py, checkin_prompt.py, and tomorrow_toast.py's coordination.
 */
/** Ported from app/ui/demo_next_dialog.py's show_demo_next_dialog inputs. */
data class DemoTourStatus(
    val doneCount: Int,
    val totalCount: Int,
    val tourComplete: Boolean,
    val recap: com.example.medvoicetrainer.analysis.TourRecapStats,
    val everyday: Boolean
)

data class SessionReflectionUiState(
    val sessionId: Int? = null,
    val autoPromptReason: com.example.medvoicetrainer.analysis.ReflectionPromptReason? = null,
    val selectedFeeling: com.example.medvoicetrainer.analysis.SessionFeeling? = null
)

data class DeepClinicalReviewUiState(
    val loadingKey: String? = null,
    val errorKey: String? = null,
    val errorMessage: String? = null,
)

sealed class PostSessionMoment {
    data class MilestoneMoment(
        val milestones: List<com.example.medvoicetrainer.analysis.Milestone>,
        val stats: com.example.medvoicetrainer.analysis.LifetimeStats
    ) : PostSessionMoment()

    data class Toast(val content: com.example.medvoicetrainer.analysis.ToastContent) : PostSessionMoment()
}
