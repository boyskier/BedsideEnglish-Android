package com.example.medvoicetrainer.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoiceManager(
    private val apiKey: String,
    private val isMock: Boolean,
    private val modelId: String,
    private val systemPrompt: String,
    private val provider: String = "gemini",
    private val mode: String = "encounter",
    private val caseId: String = "",
    private val caseJson: String = "{}",
    // AI-first kickoff instruction (interview mode / cases declaring kickoff_text). Empty means
    // "the learner speaks first" (ordinary encounters). See start().
    private val kickoffText: String = "",
    private val onTranscriptCallback: (role: String, text: String, isFinal: Boolean, learnerPcm: ByteArray?) -> Unit,
    private val onStatusCallback: (String) -> Unit,
    private val onConnectionStateCallback: (VoiceConnectionState) -> Unit = {},
    private val onMicrophoneStateCallback: (MicrophoneState) -> Unit = {},
    // Instantaneous 0f..1f loudness of the audio actually being forwarded to the provider, emitted
    // per mic callback (~10x/sec) to drive the live "is the app hearing me?" waveform. Reports 0f
    // whenever nothing is getting through — muted, echo-guard-suppressed, or gated between turns —
    // so a flat wave is the honest signal that this turn's speech is not reaching the model.
    private val onMicLevelCallback: (Float) -> Unit = {},
    private val onAiTurnStartedCallback: () -> Unit = {},
    private val onTurnCompleteCallback: () -> Unit = {},
    private val onUsageCallback: (VoiceApiUsage) -> Unit = {},
    private val echoPrevention: Boolean = VoiceSessionSafety.DEFAULT_ECHO_PREVENTION,
    private val captureLearnerTurns: Boolean = true,
    // ── Survival "Advanced Beta" scene transitions (docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md) ──
    // Off for every other session: with this false the client is never opted in, no tool is ever
    // declared to the provider, and none of the callbacks below can fire.
    private val sceneTransitionsEnabled: Boolean = false,
    private val onSceneTransitionProposedCallback: (SceneTransitionProposal) -> Unit = {},
    private val onSceneTransitionCancelledCallback: (String) -> Unit = {},
    /** Fired once a transition has actually been applied to the live session. */
    private val onSceneTransitionAppliedCallback: (SceneTransitionProposal) -> Unit = {},
    /**
     * Every proposal this manager turned down before the learner ever saw it, with why. These are
     * the outcomes nothing above this class can otherwise observe — the gate's rate limiting and
     * the scene validation both answer the model and return, so without this the only proposals
     * anyone can count are the ones that reached the screen. Tuning
     * [SceneTransitionGate.MAX_PROPOSALS_PER_SESSION] and its cooldowns needs the opposite: how
     * often the model actually asks.
     */
    private val onSceneTransitionRejectedCallback: (SceneTransitionProposal, SceneTransitionOutcome) -> Unit = { _, _ -> },
    /**
     * Which mode the live tool declaration came up in — see
     * [VoiceClientListener.onSceneTransitionModeResolved]. false means the model goes mute while a
     * chip is on screen, which is the caller's cue to shorten how long it leaves one there.
     */
    private val onSceneTransitionModeCallback: (Boolean) -> Unit = {},
    /**
     * Who the learner is with now: the stand-in's role after a character switch, or null once they
     * are back with the counterpart the session started with. Fired at the moment the switch is
     * committed rather than when the new socket comes up, so the "go back" affordance it drives is
     * on screen even if that connect needs a reconnect to complete.
     */
    private val onSceneCharacterChangedCallback: (String?) -> Unit = {},
) : VoiceClientListener {

    val usesLiveMicrophone = shouldStartLiveMicrophone(isMock, apiKey)

    private fun newClient(): VoiceClient = if (!usesLiveMicrophone) {
        MockVoiceClient(mode = mode, caseId = caseId, caseJson = caseJson)
    } else if (provider == "openai") {
        OpenAIRealtimeClient(apiKey)
    } else {
        GeminiLiveClient(apiKey)
    }

    /**
     * The transport this session is speaking through. A `var` for exactly one reason: a beta
     * character switch can be served by a session that was already brought up in the background
     * while the learner was still looking at the chip (see [prewarmSceneSwitch]), and promoting it
     * means swapping the whole client rather than reconnecting this one. Every other session holds
     * the same instance from construction to shutdown.
     */
    @Volatile
    private var client: VoiceClient = newClient()

    // Picked once per session so every reconnect (see the reconnect loop below) resends the same
    // voice instead of the character's voice changing mid-conversation. Constrained to a gender
    // when the case calls for it (Encounter/Follow-up patient `gender`, or Survival's learner-
    // chosen `voice_gender_preference` for gender-sensitive scenarios like Dating & Romance) —
    // otherwise picked fully at random across the provider's whole voice catalog.
    private val selectedVoice: VoiceOption = VoiceCatalog.randomVoice(
        provider = provider,
        gender = VoiceCatalog.genderConstraintFor(mode, caseJson),
    )

    private val audioIO = AudioIO()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stretcher = WsolaStretcher(AudioIO.PLAYBACK_SAMPLE_RATE)
    private val sessionSafety = VoiceSessionSafety()
    private val playbackStateLock = Any()
    private val playbackIoLock = Any()
    private val micStateLock = Any()
    private val shutdownStarted = AtomicBoolean(false)
    private val manuallyPaused = AtomicBoolean(false)
    private val pendingManualResume = AtomicBoolean(false)
    private val learnerSpeechPending = AtomicBoolean(false)
    private val playbackErrorReported = AtomicBoolean(false)
    private val reconnectAfterCurrent = AtomicBoolean(false)
    private val reconnectReadyBeforeJobExit = AtomicBoolean(false)
    private val shutdownComplete = CompletableDeferred<Unit>()

    @Volatile
    private var acceptingMicChunks = false

    @Volatile
    private var acceptingPlayback = false

    private var micHealthJob: Job? = null
    private var listeningStatusJob: Job? = null
    private var playbackCompletionJob: Job? = null
    private var reconnectJob: Job? = null
    private val playbackTimingLock = Any()
    private var estimatedPlaybackEndNanos = Long.MIN_VALUE
    private var providerTurnComplete = false

    @Volatile
    private var currentProviderSpeed = 1.0

    /**
     * Captured mic chunks on their way to the provider, drained by exactly one consumer coroutine
     * (see [startOutboundAudioPump]).
     *
     * Each chunk used to get its own `coroutineScope.launch { client.sendAudioChunk(it) }` from the
     * AudioRecord reader thread, ~10-30x a second for the whole session. Besides allocating a
     * coroutine per chunk, that put the sends on the multi-threaded IO dispatcher with no ordering
     * guarantee between them — so captured audio could reach the socket out of order and be
     * transcribed from a scrambled stream. A single consumer restores strict capture order.
     *
     * Unbounded on purpose, combined with [Channel.trySend] at the producer: the audio thread must
     * never block on this, and dropping a chunk here would punch a hole in the learner's speech.
     * The queue only grows if the transport stalls, and the real bound lives downstream, where
     * GeminiLiveClient/OpenAIRealtimeClient cap their own pre-send audio backlog.
     */
    private val outboundAudio = kotlinx.coroutines.channels.Channel<ByteArray>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    )
    private var outboundAudioJob: Job? = null

    // Non-null exactly while a reconnect attempt is waiting on [onSessionReady] for the socket it
    // just opened. Lets onSessionReady tell "this completed a reconnect" apart from "this is the
    // very first connect", without the two ever racing (see onConnectionLost/onSessionReady).
    private val pendingReconnectGate = AtomicReference<CompletableDeferred<Unit>?>(null)

    // True exactly when the client already told us this connect attempt resumed the provider's
    // prior server-side session (see VoiceClientListener.onSessionResumed). Reset before every
    // connect() attempt in the reconnect loop; read in onSessionReady() to decide whether the
    // seedHistory() fallback is needed for a reconnect that landed on fresh context instead.
    @Volatile
    private var resumedThisAttempt = false

    // Bounded tail of (role, text) turns from onTranscript, used only as seedHistory() fallback
    // material for a reconnect whose new provider session did NOT resume server-side (see
    // resumedThisAttempt/onSessionResumed) — never sent anywhere unless that happens.
    private val recentTurnsLock = Any()
    private val recentTurns = ArrayDeque<Pair<String, String>>()

    @Volatile
    private var mutedByUser = false

    // Beta solo errand only: the learner has stepped away from the scene and their microphone must
    // not reach the person they left behind (see beginSoloErrand). Kept apart from mutedByUser so
    // lifting the hold cannot un-mute a learner who had muted themselves.
    @Volatile
    private var awayOnErrand = false

    @Volatile
    private var microphoneAvailable = false

    @Volatile
    private var microphoneInitializationComplete = !usesLiveMicrophone

    @Volatile
    private var providerReady = false

    // One learner turn at a time. The completed turn is handed to MainViewModel, which either
    // persists the user's replay clip or spools a temporary WAV for pronunciation analysis.
    // This replaces the former first-6MiB session buffer that silently discarded later speech.
    private val pendingLearnerTurnAudio = java.io.ByteArrayOutputStream()

    // ── Scene-transition state (beta only; inert when sceneTransitionsEnabled is false) ─────────

    /**
     * The prompt and voice the *current* character is using. Ordinarily these never change and are
     * exactly the constructor's [systemPrompt] / [selectedVoice] — the reconnect loop resends them
     * unchanged so a socket drop can't alter who the learner is talking to. A beta character switch
     * is the one thing that reassigns them, and it does so before opening the new socket, so any
     * later reconnect restores the character the learner is actually with.
     */
    @Volatile
    private var activeSystemPrompt: String = systemPrompt

    @Volatile
    private var activeVoice: VoiceOption = selectedVoice

    // Typed demo / mock exists so the whole beta flow can be walked without burning tokens. The
    // shipped caps (5 per session, a minute apart, none in the first minute) would make that take
    // minutes of waiting, so the offline client gets a looser gate with no opening stretch at all —
    // the live path always uses the real limits.
    private val sceneTransitionGate = if (usesLiveMicrophone) {
        SceneTransitionGate()
    } else {
        SceneTransitionGate(maxProposals = 4, cooldownMillis = 5_000L, leadInMillis = 0L)
    }

    /** Voice per character key, so returning to the original person restores the original voice. */
    private val characterVoices = linkedMapOf(ORIGINAL_CHARACTER_KEY to selectedVoice)

    /** Who the learner is with right now (an empty key means the original counterpart). */
    @Volatile
    private var currentCharacterKey: String = ORIGINAL_CHARACTER_KEY

    @Volatile
    private var currentCharacterRole: String = ""

    private val sceneSwitchInProgress = AtomicBoolean(false)

    /**
     * Whether the learner has finished a turn since the last proposal was let through — the
     * corroboration behind [SceneTransitionProposal.requestedByUser].
     *
     * That flag unlocks the gate's budget and lead-in, and the model is the one who sets it, so
     * taking it at face value would leave the limits standing only as long as the model chose to
     * respect them. This is the cheap fact it cannot invent: a final learner transcript this class
     * saw arrive for itself. It does not prove the learner asked for a scene change — only that
     * they said something since the last one — which is enough to make a mislabelled call cost the
     * model a real turn of the learner's speech rather than nothing at all.
     */
    private val learnerSpokeSinceProposal = AtomicBoolean(false)

    /**
     * Ids admitted as learner-requested, which therefore never spent a budget slot.
     *
     * [declineSceneTransition] sees only the proposal, and its raw `requestedByUser` is not what
     * the gate acted on — an uncorroborated claim is charged like any other. Refunding on the raw
     * flag would quietly cost the learner a slot they had paid for, which is the exact class of bug
     * the budget refund exists to fix.
     */
    private val unchargedProposalIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Same-scene changes the learner has already accepted, in order, so a reconnect that lands on a
     * fresh provider session can be told where the scene actually is (see
     * [SceneTransitionProtocol.sceneRecapFor]). Without it the model reads the replayed turns
     * against a system prompt that still describes the scene's *original* setting and carries on as
     * if the move never happened. A character switch rewrites the prompt instead, so it clears this
     * rather than adding to it.
     */
    private val appliedSceneChangesLock = Any()
    private val appliedSceneChanges = mutableListOf<SceneTransitionProposal>()

    /** Set while a character-switch connect is waiting for the provider's readiness signal. */
    private val pendingSceneSwitch = AtomicReference<PendingSceneSwitch?>(null)

    private class PendingSceneSwitch(
        val proposal: SceneTransitionProposal,
        val seedTurns: List<Pair<String, String>>,
        val stageDirection: String,
        val readyStatus: String,
    )

    /**
     * A second provider session brought up in the background while a character-switch chip is on
     * screen, so that tapping it swaps to a session that is already live instead of starting one.
     *
     * The wait it removes is the whole handshake — socket, TLS, setup round trip, model-candidate
     * sweep if the first choice is busy — which the learner currently spends looking at "Walking
     * over…" with the scene frozen. Everything else about a switch (seeding the history, the stage
     * direction) has to happen after the tap anyway, because it depends on what was said while the
     * chip was up.
     *
     * Speculative, and therefore discarded the moment the proposal it belongs to stops being live:
     * see [discardPrewarm], which every path that ends a proposal calls.
     */
    private val prewarmedSwitch = AtomicReference<PrewarmedSwitch?>(null)

    private class PrewarmedSwitch(
        val proposalId: String,
        val client: VoiceClient,
        val listener: PrewarmListener,
        val plan: CharacterSwitchPlan,
    )

    /** Who a switch turns the session into — computed identically whether or not it was pre-warmed. */
    private class CharacterSwitchPlan(
        val returning: Boolean,
        val role: String,
        val characterKey: String,
        val systemPrompt: String,
        val voice: VoiceOption,
    )

    /**
     * The listener a pre-warmed session runs under until it is promoted.
     *
     * Deliberately not [VoiceManager] itself. A background session that reached the learner's
     * callbacks would play its own audio over the conversation still in progress, write its
     * transcripts into the session record, and — worst — report its own socket trouble as
     * [onConnectionLost], sending the *live* session into a reconnect loop for a failure that has
     * nothing to do with it. Everything is swallowed here except the two things that must not be:
     * readiness, which is the whole point, and usage, because a speculative session that burns
     * tokens must burn them visibly.
     */
    private inner class PrewarmListener : VoiceClientListener {
        /** The background session finished its handshake and can be promoted. */
        @Volatile
        var isReady = false

        /**
         * Its transport died. Checked at promotion, and again straight after the swap, because a
         * drop in between would otherwise be the one failure nobody hears: it was reported to this
         * listener, which does not reconnect, and the manager's own [onConnectionLost] is only
         * wired up once the swap is done.
         */
        @Volatile
        var isBroken = false

        /** The session this is listening to, so a stray function call can still be answered. */
        @Volatile
        var standby: VoiceClient? = null

        override fun onStatus(text: String) {}
        override fun onError(message: String) {}
        override fun onTranscript(role: String, text: String, isFinal: Boolean) {}
        override fun onAudioReceived(pcmData: ByteArray) {}
        override fun onTurnComplete() {}
        override fun onSessionReady() { isReady = true }
        override fun onConnectionLost() { isBroken = true }
        override fun onUsage(usage: VoiceApiUsage) { onUsageCallback(usage) }

        /**
         * A background session has been given nothing to react to, so it should never propose
         * anything. If one somehow does, answering it is not optional: an unanswered call is one
         * the model waits on, and it would still be waiting after this session is promoted.
         */
        override fun onSceneTransitionProposed(proposal: SceneTransitionProposal) {
            val target = standby ?: return
            coroutineScope.launch {
                runCatching {
                    target.respondToSceneTransition(
                        proposal.id,
                        SceneTransitionOutcome.EXPIRED_UNANSWERED,
                    )
                }
            }
        }
    }

    init {
        client.setListener(this)
        if (sceneTransitionsEnabled) client.enableSceneTransitions(true)
    }

    /**
     * Compute how [requested] splits into provider-native vs. local-WSOLA speed per
     * app/voice/capabilities.py's plan_playback_speed, for UI labelling (e.g. "native" vs.
     * "1.5x native + experimental local residual"). As of the OpenAI-native-speed wiring this
     * plan is authoritative, not just informational: [setSpeed] applies [SpeedPlan.providerSpeed]
     * server-side (OpenAI's `audio.output.speed`) and only the [SpeedPlan.localSpeed] residual
     * through the local WSOLA stretcher.
     */
    fun planSpeed(requested: Float): SpeedPlan {
        return Capabilities.planPlaybackSpeed(provider, requested.toDouble())
    }

    /**
     * Split one learner-facing speed into its provider-native and local components and apply each
     * to its own stage. For OpenAI the plan pushes the supported 0.25×–1.5× to the server and the
     * WSOLA stretcher only supplies the residual above 1.5×; for Gemini/Mock the provider request
     * is a no-op (SpeedPlan.providerSpeed == 1.0) and the full speed is applied locally exactly as
     * before, so those backends are unchanged.
     */
    fun setSpeed(speed: Float) {
        val plan = Capabilities.planPlaybackSpeed(provider, speed.toDouble())
        currentProviderSpeed = plan.providerSpeed
        synchronized(playbackStateLock) {
            stretcher.setSpeed(plan.localSpeed.toFloat())
        }
        coroutineScope.launch {
            runCatching { client.requestOutputSpeed(plan.providerSpeed) }
        }
    }

    /**
     * One consumer for [outboundAudio], so chunks reach the provider in capture order.
     *
     * A send failure must not take the session down: the transport layer already reports and
     * recovers from a dead socket itself (see GeminiLiveClient.sendOrQueue -> handleSocketDown),
     * and an exception escaping this coroutine would reach the default handler and kill the app
     * mid-session. Capture keeps running; a genuinely broken socket surfaces via onConnectionLost.
     */
    private fun startOutboundAudioPump() {
        if (outboundAudioJob?.isActive == true) return
        outboundAudioJob = coroutineScope.launch {
            for (chunk in outboundAudio) {
                runCatching { client.sendAudioChunk(chunk) }
            }
        }
    }

    suspend fun start() {
        if (shutdownStarted.get()) return
        sessionSafety.startSession()
        acceptingPlayback = true
        microphoneInitializationComplete = !usesLiveMicrophone
        updateMicGate(providerReady = false, microphoneAvailable = false)
        onMicrophoneStateCallback(
            if (usesLiveMicrophone) MicrophoneState.STARTING else MicrophoneState.OFF
        )
        onConnectionStateCallback(VoiceConnectionState.CONNECTING)
        onStatusCallback("Connecting voice session...")
        client.connect(activeSystemPrompt, modelId, activeVoice.name)
        // Ask the AI to open the conversation when the case calls for it (interview mode, or an
        // exam/rounds/teachback/survival case declaring kickoff_text). The client buffers this and
        // flushes it once the live session is actually ready. Mock client no-ops (it emits its own
        // scripted opener). This replaces MainViewModel's old fake-canned-greeting workaround for
        // real backends — see app/ui/session_base.py's kickoff_text mechanism.
        if (kickoffText.isNotEmpty()) {
            client.sendKickoffText(kickoffText)
        }

        // Typed Demo and developer mock must remain completely local and must never request
        // RECORD_AUDIO permission or instantiate AudioRecord. The user supplies turns via text.
        if (!usesLiveMicrophone) {
            updateMicGate(microphoneAvailable = false)
            onMicrophoneStateCallback(MicrophoneState.OFF)
            onConnectionStateCallback(VoiceConnectionState.READY)
            onStatusCallback("Typed demo ready - microphone off")
            return
        }

        // Capture can be initialized now, but no audio is forwarded until the provider confirms
        // setup/session readiness. This prevents a long model fallback sweep from silently
        // dropping the oldest speech out of GeminiLiveClient's bounded pre-setup queue.
        if (shutdownStarted.get()) return
        startOutboundAudioPump()
        val recordingResult = audioIO.startRecording(
            onChunkReceived = { chunk ->
                if (!acceptingMicChunks) {
                    // Gated (muted, reconnecting, or waiting for provider readiness): nothing is
                    // reaching the model, so the meter must read flat rather than react to a mic
                    // whose audio is being dropped.
                    onMicLevelCallback(0f)
                    return@startRecording
                }
                val outbound = sessionSafety.prepareMicChunk(chunk, echoPrevention)
                // Level is measured on the outbound bytes, so an echo-suppressed chunk (silence)
                // honestly reads ~0 — the visible cue that speech during patient audio isn't heard.
                val level = forwardedMicLevel(outbound)
                onMicLevelCallback(level)
                if (outbound === chunk && level >= MANUAL_TURN_SPEECH_LEVEL) {
                    learnerSpeechPending.set(true)
                }
                // Echo-muted speaker audio must not contaminate the optional pronunciation sample.
                if (outbound === chunk) {
                    if (captureLearnerTurns) bufferLearnerTurnChunk(chunk)
                }
                // Never blocks the AudioRecord reader thread; the pump forwards in capture order.
                outboundAudio.trySend(outbound)
            },
            onError = { message ->
                microphoneInitializationComplete = true
                updateMicGate(microphoneAvailable = false)
                onMicrophoneStateCallback(MicrophoneState.UNAVAILABLE)
                onStatusCallback("Error: $message. Use Type instead while the voice connection stays open.")
            },
        )
        recordingResult.onSuccess success@{
            microphoneInitializationComplete = true
            if (shutdownStarted.get()) {
                audioIO.stopRecording()
                onMicrophoneStateCallback(MicrophoneState.OFF)
                return@success
            }
            val canForward = updateMicGate(microphoneAvailable = true)
            onMicrophoneStateCallback(MicrophoneState.AVAILABLE)
            if (canForward) {
                startMicHealthCheck()
                // With no AI-first opener there cannot already be patient audio whose status this
                // would overwrite; replace the brief "starting microphone" message immediately.
                if (kickoffText.isEmpty()) onStatusCallback("Listening")
            }
        }
        recordingResult.onFailure { error ->
            microphoneInitializationComplete = true
            updateMicGate(microphoneAvailable = false)
            onMicrophoneStateCallback(
                if (shutdownStarted.get()) MicrophoneState.OFF else MicrophoneState.UNAVAILABLE
            )
            onStatusCallback(
                "Error: Microphone could not start: ${error.message ?: error.javaClass.simpleName}. " +
                    "Use Type instead while the voice connection stays open.",
            )
        }
        // Mic capture and outbound chunks are already flowing (queued client-side if the socket
        // isn't ready yet), so nothing is lost by waiting — but the "Listening" status itself is
        // deferred to onSessionReady(). connect() above returns as soon as the socket object
        // exists, well before the provider's setup/session-update handshake finishes, so saying
        // "Listening" here would tell the user they're live when the session may still be
        // connecting or may never come up at all.
    }

    private fun startMicHealthCheck() {
        if (!acceptingMicChunks || micHealthJob?.isActive == true) return
        micHealthJob = coroutineScope.launch {
            delay(VoiceSessionSafety.MIC_WARNING_AFTER_MILLIS)
            if (acceptingMicChunks && sessionSafety.shouldEmitMicWarning() && !shutdownStarted.get()) {
                onStatusCallback(
                    "Microphone warning: input stayed near zero for 12 seconds. " +
                        "Check microphone permission, input source, and volume."
                )
            }
        }
    }

    /**
     * One typed learner turn. Stage directions never come through here — they go straight to the
     * client — so this is only ever the learner's own words, and counts as a turn for
     * [learnerSpokeSinceProposal] exactly like a spoken one. Without it a typed session (the
     * offline demo, or anyone using the keyboard) could never corroborate a learner-requested
     * transition, which is the one kind the beta most wants to let through.
     */
    suspend fun sendText(text: String) {
        if (text.isNotBlank()) learnerSpokeSinceProposal.set(true)
        client.sendText(text)
    }

    /**
     * Real mic mute for the live-session mic button (docs/design/android-ui-spec.html §5's
     * MUTED turn-state): stops forwarding captured audio to the live provider without tearing
     * down the connection or the local AudioRecord, so unmuting is instant. Recording itself
     * keeps running (mirrors a real phone call's mute button) — only the outbound chunk is
     * gated, same mechanism [start] already uses to gate chunks before the socket is ready.
     * No-op for typed demo/mock, which never starts live capture in the first place.
     */
    fun setMuted(muted: Boolean) {
        if (!usesLiveMicrophone) return
        val canForward = updateMicGate(mutedByUser = muted)
        if (canForward) startMicHealthCheck()
    }

    /** End the current learner audio turn immediately instead of waiting for provider VAD. */
    suspend fun finishUserTurn(): Boolean {
        if (!usesLiveMicrophone || shutdownStarted.get() || manuallyPaused.get() || !providerReady ||
            !learnerSpeechPending.compareAndSet(true, false)
        ) {
            return false
        }
        return try {
            val sent = client.finishUserTurn()
            if (sent) onStatusCallback("Turn sent; waiting for the response")
            else learnerSpeechPending.set(true)
            sent
        } catch (error: Throwable) {
            learnerSpeechPending.set(true)
            throw error
        }
    }

    /**
     * A cost-saving pause: release the microphone and close the provider WebSocket. Unlike mute,
     * this leaves no live realtime API session running in the background.
     */
    suspend fun pause(): Boolean {
        if (!usesLiveMicrophone || shutdownStarted.get() ||
            !manuallyPaused.compareAndSet(false, true)
        ) return false

        pendingManualResume.set(false)
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        pendingReconnectGate.getAndSet(null)?.cancel()
        updateMicGate(providerReady = false, microphoneAvailable = false)
        onMicLevelCallback(0f)
        onMicrophoneStateCallback(MicrophoneState.OFF)
        acceptingPlayback = false
        micHealthJob?.cancel()
        listeningStatusJob?.cancel()
        playbackCompletionJob?.cancel()
        playbackCompletionJob = null
        sessionSafety.markAiAudioInterrupted()
        learnerSpeechPending.set(false)
        audioIO.stopRecording()
        outboundAudioJob?.cancelAndJoin()
        outboundAudioJob = null
        while (outboundAudio.tryReceive().isSuccess) {
            // Audio captured before the pause must not leak into the fresh resumed socket.
        }
        synchronized(playbackIoLock) { audioIO.stopPlayback() }
        discardPrewarm()
        runCatching { client.close() }
        onConnectionStateCallback(VoiceConnectionState.USER_PAUSED)
        onStatusCallback("Session paused; API connection closed")
        return true
    }

    /** Open a fresh provider session and restore recent transcript context before reopening input. */
    suspend fun resume(): Boolean {
        if (!usesLiveMicrophone || shutdownStarted.get() ||
            !manuallyPaused.compareAndSet(true, false)
        ) return false

        pendingManualResume.set(true)
        acceptingPlayback = true
        microphoneInitializationComplete = false
        updateMicGate(providerReady = false, microphoneAvailable = false)
        onMicrophoneStateCallback(MicrophoneState.STARTING)
        onConnectionStateCallback(VoiceConnectionState.CONNECTING)
        onStatusCallback("Resuming voice session...")

        val resumedClient = newClient().also {
            it.setListener(this)
            if (sceneTransitionsEnabled) it.enableSceneTransitions(true)
        }
        client = resumedClient
        return try {
            resumedClient.connect(activeSystemPrompt, modelId, activeVoice.name)
            if (currentProviderSpeed != 1.0) {
                resumedClient.requestOutputSpeed(currentProviderSpeed)
            }
            restartMicrophoneAfterPause()
            true
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            pendingManualResume.set(false)
            manuallyPaused.set(true)
            updateMicGate(providerReady = false, microphoneAvailable = false)
            acceptingPlayback = false
            audioIO.stopRecording()
            runCatching { resumedClient.close() }
            onMicrophoneStateCallback(MicrophoneState.OFF)
            onConnectionStateCallback(VoiceConnectionState.USER_PAUSED)
            onStatusCallback(
                "Error: Could not resume voice session: ${error.message ?: error.javaClass.simpleName}"
            )
            false
        }
    }

    private fun restartMicrophoneAfterPause() {
        if (shutdownStarted.get() || manuallyPaused.get()) return
        startOutboundAudioPump()
        val recordingResult = audioIO.startRecording(
            onChunkReceived = { chunk ->
                if (!acceptingMicChunks) {
                    onMicLevelCallback(0f)
                    return@startRecording
                }
                val outbound = sessionSafety.prepareMicChunk(chunk, echoPrevention)
                val level = forwardedMicLevel(outbound)
                onMicLevelCallback(level)
                if (outbound === chunk && level >= MANUAL_TURN_SPEECH_LEVEL) {
                    learnerSpeechPending.set(true)
                }
                if (outbound === chunk && captureLearnerTurns) bufferLearnerTurnChunk(chunk)
                outboundAudio.trySend(outbound)
            },
            onError = { message ->
                microphoneInitializationComplete = true
                updateMicGate(microphoneAvailable = false)
                onMicrophoneStateCallback(MicrophoneState.UNAVAILABLE)
                onStatusCallback("Error: $message. Use Type instead while the voice connection stays open.")
            },
        )
        recordingResult.onSuccess success@{
            microphoneInitializationComplete = true
            if (shutdownStarted.get() || manuallyPaused.get()) {
                audioIO.stopRecording()
                onMicrophoneStateCallback(MicrophoneState.OFF)
                return@success
            }
            val canForward = updateMicGate(microphoneAvailable = true)
            onMicrophoneStateCallback(MicrophoneState.AVAILABLE)
            if (canForward) startMicHealthCheck()
        }
        recordingResult.onFailure { error ->
            microphoneInitializationComplete = true
            updateMicGate(microphoneAvailable = false)
            onMicrophoneStateCallback(MicrophoneState.UNAVAILABLE)
            onStatusCallback(
                "Error: Microphone could not restart: ${error.message ?: error.javaClass.simpleName}. " +
                    "Use Type instead while the voice connection stays open."
            )
        }
    }

    /**
     * Normalized 0f..1f loudness of the audio actually forwarded to the provider this callback,
     * for the live mic-level waveform. Because it runs on [outbound] (post echo-guard), an
     * echo-suppressed or silence chunk reads ~0 and the meter flattens on its own.
     */
    private fun forwardedMicLevel(pcm: ByteArray): Float = pcm16MicLevel(pcm)

    @Synchronized
    private fun bufferLearnerTurnChunk(chunk: ByteArray) {
        val maxBytes = LearnerAudioStore.SAMPLE_RATE * 2 * LearnerAudioStore.MAX_TURN_SECONDS
        val remaining = maxBytes - pendingLearnerTurnAudio.size()
        if (remaining > 0) pendingLearnerTurnAudio.write(chunk, 0, minOf(chunk.size, remaining))
    }

    @Synchronized
    private fun takeLearnerTurnAudio(): ByteArray {
        val pcm = pendingLearnerTurnAudio.toByteArray()
        pendingLearnerTurnAudio.reset()
        return pcm
    }

    /**
     * AudioRecord startup and provider setup complete on different threads and in either order.
     * Recompute the forwarding gate after every transition so neither a fast setup callback nor
     * a mute tap during reconnect can leave the real microphone in the wrong state.
     */
    private fun updateMicGate(
        providerReady: Boolean? = null,
        microphoneAvailable: Boolean? = null,
        mutedByUser: Boolean? = null,
        awayOnErrand: Boolean? = null,
    ): Boolean = synchronized(micStateLock) {
        providerReady?.let { this.providerReady = it }
        microphoneAvailable?.let { this.microphoneAvailable = it }
        mutedByUser?.let { this.mutedByUser = it }
        awayOnErrand?.let { this.awayOnErrand = it }
        acceptingMicChunks = this.providerReady &&
            this.microphoneAvailable &&
            !this.mutedByUser &&
            !this.awayOnErrand &&
            !shutdownStarted.get()
        if (!acceptingMicChunks) micHealthJob?.cancel()
        acceptingMicChunks
    }

    /**
     * Backward-compatible non-blocking stop. Microphone and playback close synchronously; the
     * transport then gets a bounded grace period for final transcript callbacks on the IO scope.
     * New suspend callers should prefer [stopAndDrain].
     */
    fun stop() {
        // Preserve the old immediate/non-blocking cancellation semantics. The analyzed-session
        // path explicitly calls stopAndDrain(), while cancel/replacement must not let an old
        // manager deliver callbacks into a newly-started session for another 600 ms.
        if (beginShutdown()) coroutineScope.launch { finishShutdown(0L) }
    }

    /**
     * Stop capture first, close the live transport, and keep callbacks alive briefly so final
     * provider transcript events can drain before the caller snapshots the session transcript.
     */
    suspend fun stopAndDrain(drainMillis: Long = FINAL_TRANSCRIPT_DRAIN_MILLIS) {
        if (beginShutdown()) {
            finishShutdown(drainMillis)
        } else {
            shutdownComplete.await()
        }
    }

    /** Suspend-friendly close alias for lifecycle owners. */
    suspend fun close(drainMillis: Long = FINAL_TRANSCRIPT_DRAIN_MILLIS) {
        stopAndDrain(drainMillis)
    }

    private fun beginShutdown(): Boolean {
        if (!shutdownStarted.compareAndSet(false, true)) return false

        // This flag closes the tiny callback race before AudioRecord.stop() joins its reader.
        updateMicGate(providerReady = false, microphoneAvailable = false)
        onMicLevelCallback(0f)
        onMicrophoneStateCallback(MicrophoneState.OFF)
        acceptingPlayback = false
        sessionSafety.stopSession()
        micHealthJob?.cancel()
        listeningStatusJob?.cancel()
        playbackCompletionJob?.cancel()
        reconnectJob?.cancel()
        // A speculative session nobody is going to take up must not outlive the one that spawned it.
        discardPrewarm()
        // Closing (not cancelling) lets the pump finish forwarding whatever the learner already
        // said before the socket goes down, then fall out of its for-loop on its own. Chunks that
        // arrive after this are rejected by trySend rather than queued for a dead session.
        outboundAudio.close()

        onStatusCallback(
            if (usesLiveMicrophone) "Microphone stopped"
            else "Closing typed demo..."
        )
        runCatching { audioIO.stopRecording() }
            .onFailure { onStatusCallback("Error stopping microphone: ${it.message}") }
        synchronized(playbackIoLock) {
            runCatching { audioIO.stopPlayback() }
                .onFailure { onStatusCallback("Error stopping playback: ${it.message}") }
        }
        return true
    }

    private suspend fun finishShutdown(drainMillis: Long) {
        try {
            // Keep the transport/listener alive during the bounded drain so an already-in-flight
            // final transcription can arrive after capture stops. Then close the provider socket.
            val boundedDrain = drainMillis.coerceAtLeast(0L)
            if (boundedDrain > 0L) {
                onStatusCallback("Draining final transcript...")
                delay(boundedDrain)
            }
            client.close()
        } catch (error: Throwable) {
            onStatusCallback("Error closing voice session: ${error.message}")
        } finally {
            onStatusCallback("Voice session closed")
            onConnectionStateCallback(VoiceConnectionState.CLOSED)
            shutdownComplete.complete(Unit)
        }
    }

    override fun onStatus(text: String) {
        onStatusCallback(text)
    }

    override fun onError(message: String) {
        onStatusCallback("Error: $message")
    }

    override fun onTranscript(role: String, text: String, isFinal: Boolean) {
        if (isFinal && (role == "user" || role == "doctor")) {
            learnerSpeechPending.set(false)
        }
        val learnerPcm = if (
            captureLearnerTurns && isFinal && (role == "user" || role == "doctor")
        ) takeLearnerTurnAudio() else null
        if (isFinal && text.isNotBlank()) {
            synchronized(recentTurnsLock) {
                recentTurns.addLast(role to text)
                while (recentTurns.size > MAX_RESUME_TURNS) recentTurns.removeFirst()
            }
            // Beta only: the learner has now said something the model could be responding to (see
            // learnerSpokeSinceProposal). Partial transcripts deliberately do not count — a call
            // made mid-utterance is answering something nobody finished saying.
            if (role == "user" || role == "doctor") learnerSpokeSinceProposal.set(true)
        }
        onTranscriptCallback(role, text, isFinal, learnerPcm)
    }

    override fun onAudioReceived(pcmData: ByteArray) {
        if (!acceptingPlayback) return
        val firstChunkOfTurn = sessionSafety.markAiAudioStarted()
        if (firstChunkOfTurn) {
            learnerSpeechPending.set(false)
            listeningStatusJob?.cancel()
            playbackCompletionJob?.cancel()
            playbackCompletionJob = null
            synchronized(playbackTimingLock) {
                estimatedPlaybackEndNanos = System.nanoTime()
                providerTurnComplete = false
            }
            onAiTurnStartedCallback()
            onStatusCallback(
                if (echoPrevention) "AI speaking - microphone echo guard active"
                else "AI speaking - microphone open for interruption"
            )
        }
        val stretched = synchronized(playbackStateLock) { stretcher.process(pcmData) }
        if (stretched.isNotEmpty()) {
            synchronized(playbackIoLock) {
                if (acceptingPlayback && playPatientAudio(stretched)) {
                    noteQueuedPlayback(stretched.size)
                }
            }
        }
    }

    override fun onTurnComplete() {
        if (!acceptingPlayback) return
        if (!usesLiveMicrophone) {
            // Typed Demo/mock never calls onAudioReceived, so aiAudioActive never turns true and
            // the playback-clock machinery below — built to time real provider audio — would wait
            // on a turn that was never marked active, leaving isAILoading stuck forever. There is
            // no audio to drain or time here, so complete the turn immediately, as this always did
            // before the playback-clock rewrite.
            onTurnCompleteCallback()
            return
        }
        val remaining = synchronized(playbackStateLock) { stretcher.finish() }
        if (remaining.isNotEmpty()) {
            synchronized(playbackIoLock) {
                if (acceptingPlayback && playPatientAudio(remaining)) {
                    noteQueuedPlayback(remaining.size)
                }
            }
        }
        synchronized(playbackTimingLock) { providerTurnComplete = true }
        schedulePlaybackCompletion(restart = true)
    }

    /**
     * End the audible turn from the local playback clock, independently of final transcript
     * delivery. Gemini can finish speaking before its output-transcription event arrives; using
     * that event as the mic gate silently discarded anything the learner said in between.
     */
    private fun noteQueuedPlayback(byteCount: Int) {
        val durationNanos = byteCount.toLong() * 1_000_000_000L /
            (AudioIO.PLAYBACK_SAMPLE_RATE.toLong() * PCM16_MONO_BYTES_PER_FRAME)
        synchronized(playbackTimingLock) {
            val now = System.nanoTime()
            estimatedPlaybackEndNanos = maxOf(now, estimatedPlaybackEndNanos) + durationNanos
        }
        schedulePlaybackCompletion()
    }

    private fun schedulePlaybackCompletion(restart: Boolean = false) {
        if (restart) {
            playbackCompletionJob?.cancel()
            playbackCompletionJob = null
        }
        if (playbackCompletionJob?.isActive == true) return
        playbackCompletionJob = coroutineScope.launch {
            while (acceptingPlayback && !shutdownStarted.get()) {
                val waitNanos = synchronized(playbackTimingLock) {
                    if (estimatedPlaybackEndNanos == Long.MIN_VALUE) {
                        0L
                    } else {
                        val grace = if (providerTurnComplete) 0L else AUDIO_STREAM_IDLE_GRACE_NANOS
                        estimatedPlaybackEndNanos + grace - System.nanoTime()
                    }
                }
                if (waitNanos > 0L) {
                    delay((waitNanos + 999_999L) / 1_000_000L)
                    continue
                }
                finishAudibleTurn()
                return@launch
            }
        }
    }

    private fun finishAudibleTurn() {
        val turnToken = sessionSafety.markAiTurnCompleteIfActive() ?: return
        onTurnCompleteCallback()
        listeningStatusJob?.cancel()
        if (!echoPrevention) {
            onStatusCallback(listeningStatus("Listening - barge-in enabled"))
            return
        }
        onStatusCallback("AI turn complete - echo guard active for 250 ms")
        listeningStatusJob = coroutineScope.launch {
            delay(VoiceSessionSafety.ECHO_TAIL_MILLIS)
            if (sessionSafety.canAnnounceListening(turnToken) && !shutdownStarted.get()) {
                onStatusCallback(listeningStatus("Listening"))
            }
        }
    }

    override fun onUsage(usage: VoiceApiUsage) {
        onUsageCallback(usage)
    }

    // ── Survival "Advanced Beta": scene transitions ─────────────────────────────────────────────

    /**
     * The model proposed a scene change. The beta prompt already asks for a handful at most, spaced
     * out and never in the opening minute;
     * [SceneTransitionGate] enforces the same limits here because a prompt is a request, not a
     * guarantee. Anything over the limit is declined immediately and never reaches the screen —
     * the model must always get a function response or it will simply stop speaking.
     *
     * Those limits ration the model's own ideas. A change the learner asked for
     * ([SceneTransitionProposal.requestedByUser]) is not one of them and is let through on a much
     * looser footing — corroborated first, because the model is the only witness to what the
     * learner said and the flag is what unlocks the limits.
     */
    override fun onSceneTransitionProposed(proposal: SceneTransitionProposal) {
        if (shutdownStarted.get()) {
            reject(proposal, SceneTransitionOutcome.EXPIRED_UNANSWERED)
            return
        }
        // Checked before the gate, so a proposal that describes something untrue never spends a
        // slot from a budget meant for detours the learner could actually take. The reason travels
        // with the response because both cases are ones the model can get right next time.
        SceneTransitionProtocol.rejectionReasonFor(
            proposal,
            isWithStandInCharacter = currentCharacterKey != ORIGINAL_CHARACTER_KEY,
        )?.let { reason ->
            reject(proposal, SceneTransitionOutcome.NOT_APPLICABLE, reason)
            return
        }
        // A change the learner asked for out loud skips the budget and the opening lead-in, but
        // only the model can see that they asked, and a flag that unlocks a limit cannot be taken
        // on trust alone — so it counts only when this class also saw the learner finish a turn
        // since the last proposal went up (see learnerSpokeSinceProposal).
        val learnerAsked = proposal.requestedByUser && learnerSpokeSinceProposal.get()
        // Monotonic, not wall time: an NTP correction mid-session must not hand out a free
        // proposal (clock jumps forward) or freeze the gate for hours (clock jumps back).
        val admission = sceneTransitionGate.admit(
            proposal.type,
            android.os.SystemClock.elapsedRealtime(),
            requestedByUser = learnerAsked,
        )
        if (!admission.isAdmitted) {
            // "Wait a while" and "never again this session" are answered differently on purpose;
            // see SceneTransitionOutcome.BUDGET_EXHAUSTED. A learner-requested change can only
            // ever hit the cooldown, and telling the model to sit out two minutes after the
            // learner personally asked for something is the wrong instruction entirely.
            val note = if (learnerAsked) {
                "The user did ask for this, but the scene has only just changed. Nothing is " +
                    "refused and nothing is used up — carry on, and if they still want it in a " +
                    "few seconds' time, call this again with requested_by_user=true."
            } else {
                admission.refusalOutcome.note
            }
            reject(proposal, admission.refusalOutcome, note)
            return
        }
        // Answered proposals are charged at admission; this one was not, so a later refund must
        // not credit it back. Cleared as soon as the proposal is resolved either way.
        if (learnerAsked) unchargedProposalIds.add(proposal.id)
        learnerSpokeSinceProposal.set(false)
        // The chip is going up, so this is the moment the learner might say yes — and, for the two
        // types that need a whole new session, the only chance to have one ready before they do.
        prewarmSceneSwitch(proposal)
        onSceneTransitionProposedCallback(proposal)
    }

    /** Answer a proposal the learner will never see, and let the caller count that it happened. */
    private fun reject(
        proposal: SceneTransitionProposal,
        outcome: SceneTransitionOutcome,
        note: String = "",
    ) {
        respondInBackground(proposal.id, outcome, note)
        onSceneTransitionRejectedCallback(proposal, outcome)
    }

    override fun onSceneTransitionCancelled(id: String) {
        // No response is owed for a cancelled call — the provider has withdrawn it.
        discardPrewarm(id)
        onSceneTransitionCancelledCallback(id)
    }

    override fun onSceneTransitionModeResolved(nonBlocking: Boolean) {
        onSceneTransitionModeCallback(nonBlocking)
    }

    /**
     * The learner turned the chip down, or it went away unanswered. Answer the model with which of
     * those it was, and give the session budget back when nobody actually said no — see
     * [SceneTransitionGate.refundUnanswered].
     */
    suspend fun declineSceneTransition(
        proposal: SceneTransitionProposal,
        outcome: SceneTransitionOutcome,
    ) {
        val wasUncharged = unchargedProposalIds.remove(proposal.id)
        if (outcome == SceneTransitionOutcome.EXPIRED_UNANSWERED) {
            sceneTransitionGate.refundUnanswered(proposal.type, requestedByUser = wasUncharged)
        }
        discardPrewarm(proposal.id)
        runCatching { client.respondToSceneTransition(proposal.id, outcome) }
    }

    /**
     * Stage 3, the moment the learner sets off: answer the errand's function call straight away and
     * stop forwarding the microphone until they are back.
     *
     * Answering now rather than on their return is what the asynchronous tool declaration makes
     * necessary. The response used to be held back for the whole reading window purely to keep the
     * model quiet — with a blocking call, an unanswered one is silence. That silence is no longer
     * free: it now means a function call left open across anything that happens in the meantime,
     * including a reconnect that would abandon it, while the model talks on regardless. So the
     * protocol obligation is discharged immediately and the *fiction* is held up by the microphone
     * instead: the learner has walked away, so nothing they say reaches the person they left
     * behind, and a model with no input has nothing to answer. [acceptSceneTransition] lifts the
     * hold when the errand's stage direction finally goes in.
     */
    suspend fun beginSoloErrand(proposal: SceneTransitionProposal) {
        if (shutdownStarted.get()) return
        runCatching { client.respondToSceneTransition(proposal.id, SceneTransitionOutcome.ACCEPTED) }
        setAwayOnErrand(true)
    }

    private fun setAwayOnErrand(away: Boolean) {
        if (!usesLiveMicrophone) return
        val canForward = updateMicGate(awayOnErrand = away)
        if (canForward) startMicHealthCheck()
    }

    /**
     * True when a character switch is in effect and the learner can still be handed back.
     *
     * Deliberately does not require a live microphone: the offline demo switches character too
     * (see [adoptCharacter]), and gating this on the live path is what used to make the Stage 2
     * round trip impossible to walk without an API key.
     */
    fun canReturnToPreviousCharacter(): Boolean =
        sceneTransitionsEnabled && !shutdownStarted.get() &&
            currentCharacterKey != ORIGINAL_CHARACTER_KEY

    /**
     * Hand the learner back to the person they were talking to before a character switch, on their
     * own initiative rather than the model's.
     *
     * Without this the way back exists only if the stand-in volunteers it. The model is under no
     * obligation to propose the return trip, and one that [SceneTransitionGate] declines is never
     * asked again (the model is simply told "declined"), so a learner could be stuck with the
     * stand-in for the rest of the session with no way out but ending it.
     *
     * Deliberately not routed through [VoiceClient.respondToSceneTransition]: nothing here answers
     * an outstanding function call, and the blank id on the synthesized proposal is what guarantees
     * that even a future caller who tried would send nothing to a provider that never asked.
     *
     * Returns false when the hand-back did not actually start, so the caller can put its "go back"
     * affordance back rather than leaving the learner with no way home.
     */
    suspend fun returnToPreviousCharacter(): Boolean {
        if (!canReturnToPreviousCharacter()) return false
        val proposal = SceneTransitionProposal(
            id = "",
            type = SceneTransitionType.RETURN_TO_PREVIOUS,
            title = "",
            description = "",
        )
        val stageDirection = SceneTransitionProtocol.stageDirectionFor(proposal)
        if (!usesLiveMicrophone) {
            adoptCharacter(proposal)
            runCatching { client.sendText(stageDirection) }
            onSceneTransitionAppliedCallback(proposal)
            return true
        }
        return switchCharacter(proposal, stageDirection)
    }

    /**
     * Apply an accepted transition. Same-person changes (move / time skip / solo errand) are just a
     * stage direction injected into the running session. A new person — or coming back to the old
     * one — needs a different voice, which a Live session cannot change mid-stream, so it is done
     * by reconnecting (see [switchCharacter]).
     */
    suspend fun acceptSceneTransition(proposal: SceneTransitionProposal) {
        if (shutdownStarted.get()) return
        // Resolved, so nothing can be refunded for it any more (an accepted proposal never is).
        unchargedProposalIds.remove(proposal.id)
        // The learner is back in the scene, whatever else this transition does.
        setAwayOnErrand(false)
        // A no-op for an errand [beginSoloErrand] already answered: a client only answers a call it
        // still holds outstanding, so the id is long gone by now. Left unconditional because every
        // other transition type is answered here and nowhere else.
        runCatching { client.respondToSceneTransition(proposal.id, SceneTransitionOutcome.ACCEPTED) }
        val stageDirection = SceneTransitionProtocol.stageDirectionFor(proposal)
        if (!proposal.type.requiresReconnect) {
            runCatching { client.sendText(stageDirection) }
            // A same-scene change lives only in the running session's context, which a
            // non-resuming reconnect throws away — remember it so it can be restated.
            rememberSceneChange(proposal)
            onSceneTransitionAppliedCallback(proposal)
            return
        }
        // A typed-demo/mock session has no second socket to open; the stage direction alone is
        // enough for the scripted client to react, which keeps the whole flow reproducible offline.
        // The learner-facing character state still has to move, though — without it the "go back"
        // affordance never appears offline and the Stage 2 round trip cannot be exercised at all.
        if (!usesLiveMicrophone) {
            adoptCharacter(proposal)
            runCatching { client.sendText(stageDirection) }
            onSceneTransitionAppliedCallback(proposal)
            return
        }
        switchCharacter(proposal, stageDirection)
    }

    /**
     * Move the "who is the learner with now" state onto [proposal]'s character without touching the
     * transport. The live path does this inside [switchCharacter], where it is inseparable from
     * opening the new socket; the offline demo has no socket to open but must still expose the same
     * affordances, so the two share this bookkeeping and nothing else.
     */
    private fun adoptCharacter(proposal: SceneTransitionProposal) {
        val returning = proposal.type == SceneTransitionType.RETURN_TO_PREVIOUS
        val role = proposal.newCharacterRole.trim()
        currentCharacterKey = if (returning) {
            ORIGINAL_CHARACTER_KEY
        } else {
            role.lowercase().ifEmpty { "scene_character_${characterVoices.size}" }
        }
        currentCharacterRole = if (returning) "" else role
        onSceneCharacterChangedCallback(if (returning) null else role)
    }

    private fun rememberSceneChange(proposal: SceneTransitionProposal) {
        synchronized(appliedSceneChangesLock) {
            appliedSceneChanges.add(proposal)
            while (appliedSceneChanges.size > MAX_TRACKED_SCENE_CHANGES) {
                appliedSceneChanges.removeAt(0)
            }
        }
    }

    private fun sceneRecap(): String = SceneTransitionProtocol.sceneRecapFor(
        synchronized(appliedSceneChangesLock) { appliedSceneChanges.toList() },
    )

    /**
     * Stage 2: tear the session down and bring it back up as a different person.
     *
     * The old socket is [VoiceClient.close]d rather than reconnected, which is what discards the
     * Gemini resumption handle — resuming would restore the *previous* character's server-side
     * conversation, exactly what must not happen. The scene's recent turns are replayed instead,
     * as inert history, capped at [SCENE_SWITCH_SEED_TURNS] because Gemini Live re-bills replayed
     * context on every turn.
     */
    private suspend fun switchCharacter(
        proposal: SceneTransitionProposal,
        stageDirection: String,
    ): Boolean {
        if (!sceneSwitchInProgress.compareAndSet(false, true)) {
            // The learner accepted this one and the transcript already records it, so say why
            // nothing is happening rather than dropping it without a trace.
            onStatusCallback("Already moving between people — that one didn't take.")
            // Nothing will ever claim the session opened for it, and the switch that *is* running
            // has its own.
            discardPrewarm(proposal.id)
            return false
        }
        try {
            // A reconnect loop racing this would reopen the *old* character's prompt.
            reconnectJob?.cancel()
            reconnectJob = null
            pendingReconnectGate.set(null)

            // A session brought up while the chip was on screen, if there is one and it survived.
            // Its plan is reused rather than recomputed: the socket already out there was opened
            // with that prompt and that voice, so anything else would describe a different session.
            val prewarmed = takePrewarmFor(proposal.id)
            val plan = prewarmed?.plan ?: planCharacterSwitch(proposal)
            val returning = plan.returning

            updateMicGate(providerReady = false)
            onConnectionStateCallback(VoiceConnectionState.RECONNECTING)
            onStatusCallback(
                if (returning) "Heading back…" else "Walking over…",
            )
            synchronized(playbackIoLock) { audioIO.clearPlaybackQueue() }
            synchronized(playbackStateLock) { stretcher.reset() }

            // The new person hears the recent exchanges verbatim and everything older as one short
            // digest — see SceneTransitionProtocol.compressSeedTurns for why the tail is worth its
            // price and the head is not. "The other person" because this history is not theirs:
            // they were not in the scene for any of it.
            val seedTurns = SceneTransitionProtocol.compressSeedTurns(
                turns = synchronized(recentTurnsLock) {
                    recentTurns.toList().takeLast(SCENE_SWITCH_SEED_TURNS)
                },
                verbatimTurns = SCENE_SWITCH_VERBATIM_TURNS,
                maxDigestChars = SEED_DIGEST_MAX_CHARS,
                modelSpeakerLabel = "The other person",
            )
            pendingSceneSwitch.set(
                PendingSceneSwitch(
                    proposal = proposal,
                    // The returning character's own server-side memory is gone too (fresh socket),
                    // so both directions need the history replay.
                    seedTurns = seedTurns,
                    stageDirection = stageDirection,
                    readyStatus = if (returning) {
                        listeningStatus("You're back — keep talking")
                    } else {
                        listeningStatus("New person — keep talking")
                    },
                ),
            )
            // Committed here, not when the new socket reports ready: if this connect needs the
            // reconnect loop to finish the job, the learner is already with the new character and
            // must still be offered the way back.
            commitCharacter(plan)

            resumedThisAttempt = false
            if (prewarmed != null) {
                promotePrewarmed(prewarmed)
                return true
            }
            // close() clears the resumption handle; the following connect() therefore starts a
            // genuinely fresh provider session for the new character.
            runCatching { client.close() }
            runCatching { client.connect(plan.systemPrompt, modelId, plan.voice.name) }
                .onFailure {
                    // pendingSceneSwitch is deliberately left in place. The reconnect loop resends
                    // this character's prompt and voice (activeSystemPrompt/activeVoice were just
                    // reassigned), and onSessionReady's scene-switch branch is what finally seeds
                    // the history and injects the stage direction — the path its own comment
                    // already anticipates. Clearing it here meant a switch rescued by the loop came
                    // up as the new character with no history and no idea why the learner was
                    // suddenly talking to them.
                    onStatusCallback(
                        "Couldn't bring the new person in yet — reconnecting: " +
                            (it.message ?: it.javaClass.simpleName),
                    )
                    onConnectionLost()
                }
            return true
        } finally {
            sceneSwitchInProgress.set(false)
        }
    }

    /**
     * Work out who a switch turns the session into, without changing anything.
     *
     * Split out of [switchCharacter] so a pre-warmed session can be opened as exactly the character
     * the tap will later commit to. Reads state but writes none — in particular the voice is looked
     * up rather than reserved, because a proposal the learner ignores must not quietly use up one
     * of the distinct voices [pickDistinctVoiceLocked] has left to hand out.
     */
    private fun planCharacterSwitch(proposal: SceneTransitionProposal): CharacterSwitchPlan {
        val returning = proposal.type == SceneTransitionType.RETURN_TO_PREVIOUS
        val role = proposal.newCharacterRole.trim()
        val previousRole = currentCharacterRole
        val characterKey = if (returning) ORIGINAL_CHARACTER_KEY else {
            role.lowercase().ifEmpty { "scene_character_${characterVoices.size}" }
        }
        val accent = caseField("listening_accent").ifEmpty { "us" }
        val style = caseField("speech_style").ifEmpty { "clear" }
        val prompt = if (returning) {
            systemPrompt + com.example.medvoicetrainer.analysis.PromptBuilder.sceneReturnNote(
                sceneDescription = proposal.description,
                otherRole = previousRole,
            )
        } else {
            com.example.medvoicetrainer.analysis.PromptBuilder.buildSceneCharacterPrompt(
                newRole = role,
                sceneDescription = proposal.description,
                previousRole = previousRole.ifEmpty { caseField("counterpart_role") },
                accent = accent,
                style = style,
            )
        }
        val voice = synchronized(characterVoices) {
            characterVoices[characterKey] ?: pickDistinctVoiceLocked()
        }
        return CharacterSwitchPlan(returning, role, characterKey, prompt, voice)
    }

    /**
     * Make [plan] the session's truth: this is who the learner is with, and who any later reconnect
     * must come back up as.
     */
    private fun commitCharacter(plan: CharacterSwitchPlan) {
        synchronized(characterVoices) { characterVoices[plan.characterKey] = plan.voice }
        activeSystemPrompt = plan.systemPrompt
        activeVoice = plan.voice
        currentCharacterKey = plan.characterKey
        currentCharacterRole = if (plan.returning) "" else plan.role
        onSceneCharacterChangedCallback(if (plan.returning) null else plan.role)
        // The prompt just built describes this character's own scene from scratch, so the previous
        // character's moves are not theirs to be reminded of on a later reconnect.
        synchronized(appliedSceneChangesLock) { appliedSceneChanges.clear() }
    }

    /**
     * Swap a ready pre-warmed session in as the live one, in place of the close-and-reconnect
     * [switchCharacter] would otherwise do.
     *
     * The old transport is closed *before* the swap, not after: while both are reachable, a dying
     * old socket's [onConnectionLost] would start a reconnect loop that reconnects whatever `client`
     * currently points at — which, after the swap, is the session that is perfectly fine.
     */
    private suspend fun promotePrewarmed(prewarmed: PrewarmedSwitch) {
        val previous = client
        runCatching { previous.close() }
        prewarmed.client.setListener(this)
        client = prewarmed.client
        // It died between being checked and being swapped in. Nothing has heard about that yet —
        // the listener that did is the inert one — so report it now and let the ordinary reconnect
        // loop rebuild the session, as the new character (commitCharacter already ran) and with
        // pendingSceneSwitch still set for its onSessionReady to finish the job.
        if (prewarmed.listener.isBroken) {
            onStatusCallback("Couldn't bring the new person in yet — reconnecting…")
            onConnectionLost()
            return
        }
        onConnectionStateCallback(VoiceConnectionState.READY)
        if (updateMicGate(providerReady = true)) startMicHealthCheck()
        // onSessionReady already fired, on the pre-warm listener, so the branch that normally
        // consumes this never runs for a promoted session — do its work here instead.
        pendingSceneSwitch.getAndSet(null)?.let { applySceneSwitch(it) }
    }

    /**
     * Seed the new session's history and set it going. One coroutine, in this order: the new
     * character must have the scene's history before the stage direction tells them to start
     * talking about it.
     */
    private fun applySceneSwitch(pending: PendingSceneSwitch) {
        onStatusCallback(pending.readyStatus)
        coroutineScope.launch {
            if (pending.seedTurns.isNotEmpty()) {
                runCatching { client.seedHistory(pending.seedTurns) }
            }
            runCatching { client.sendText(pending.stageDirection) }
            onSceneTransitionAppliedCallback(pending.proposal)
        }
    }

    /**
     * Start bringing up the session a character switch will need, while the learner is still
     * deciding. No-op for every proposal that does not change who is speaking, and for the offline
     * demo, which has no socket to open.
     *
     * This is the one speculative cost in the feature: a chip the learner ignores has opened a
     * provider session that is then thrown away. It is bounded — one at a time, only for the two
     * types that reconnect, only while a chip is actually up, and closed by [discardPrewarm] the
     * moment the proposal ends — and an idle Live session that is never spoken to has no turn to
     * be billed for. If that ever stops being true, [PREWARM_CHARACTER_SWITCH] turns the whole
     * thing off and the switch falls back to connecting on the tap.
     */
    private fun prewarmSceneSwitch(proposal: SceneTransitionProposal) {
        if (!PREWARM_CHARACTER_SWITCH) return
        if (!sceneTransitionsEnabled || !usesLiveMicrophone) return
        if (!proposal.type.requiresReconnect || proposal.id.isBlank()) return
        if (shutdownStarted.get() || sceneSwitchInProgress.get()) return

        discardPrewarm()
        val plan = planCharacterSwitch(proposal)
        val listener = PrewarmListener()
        val standby = newClient()
        listener.standby = standby
        standby.setListener(listener)
        standby.enableSceneTransitions(true)
        val record = PrewarmedSwitch(proposal.id, standby, listener, plan)
        if (!prewarmedSwitch.compareAndSet(null, record)) return
        coroutineScope.launch {
            runCatching { standby.connect(plan.systemPrompt, modelId, plan.voice.name) }
                .onFailure {
                    // Nothing to report: the live conversation is untouched and the tap simply
                    // takes the ordinary connect-on-accept path.
                    listener.isBroken = true
                    discardPrewarm(proposal.id)
                }
        }
    }

    /**
     * Close a pre-warmed session that is no longer worth holding. [proposalId] null discards
     * whatever is there; otherwise only the one belonging to that proposal, so a discard racing a
     * newer chip cannot take the newer session down with it.
     */
    private fun discardPrewarm(proposalId: String? = null) {
        val current = prewarmedSwitch.get() ?: return
        if (proposalId != null && current.proposalId != proposalId) return
        if (!prewarmedSwitch.compareAndSet(current, null)) return
        coroutineScope.launch { runCatching { current.client.close() } }
    }

    /**
     * The pre-warmed session for [proposalId] if it is genuinely ready to take over, claiming it so
     * nothing else can. Anything less — a different proposal, still connecting, already dead — is
     * closed and null returned, which puts the caller back on the ordinary path.
     */
    private fun takePrewarmFor(proposalId: String): PrewarmedSwitch? {
        if (proposalId.isBlank()) return null
        val current = prewarmedSwitch.get() ?: return null
        if (current.proposalId != proposalId) return null
        if (!prewarmedSwitch.compareAndSet(current, null)) return null
        if (current.listener.isReady && !current.listener.isBroken) return current
        coroutineScope.launch { runCatching { current.client.close() } }
        return null
    }

    /** A voice nobody in this session has used yet, so a new character never sounds identical. */
    private fun pickDistinctVoiceLocked(): VoiceOption {
        val used = characterVoices.values.map { it.name }.toSet()
        repeat(VOICE_PICK_ATTEMPTS) {
            val candidate = VoiceCatalog.randomVoice(provider = provider)
            if (candidate.name !in used) return candidate
        }
        return VoiceCatalog.voicesFor(provider).firstOrNull { it.name !in used }
            ?: VoiceCatalog.randomVoice(provider = provider)
    }

    private fun respondInBackground(
        id: String,
        outcome: SceneTransitionOutcome,
        note: String = "",
    ) {
        coroutineScope.launch { runCatching { client.respondToSceneTransition(id, outcome, note) } }
    }

    private fun caseField(key: String): String = try {
        org.json.JSONObject(caseJson).optString(key, "")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        ""
    }

    /**
     * The provider's own setup/session-update handshake actually finished — the one moment it is
     * true that mic audio will reach a live conversation. If a reconnect attempt is waiting on
     * this (see [onConnectionLost]), fulfill its gate and report "Reconnected"; otherwise this is
     * the very first connect for this session and the user hasn't been told they're live yet.
     */
    override fun onSessionReady() {
        if (shutdownStarted.get() || manuallyPaused.get()) return
        if (pendingManualResume.compareAndSet(true, false)) {
            val resumedClient = client
            coroutineScope.launch {
                val turnsSnapshot = synchronized(recentTurnsLock) { recentTurns.toList() }
                val recap = sceneRecap()
                val seedTurns = if (recap.isEmpty()) turnsSnapshot else {
                    turnsSnapshot + (SceneTransitionProtocol.NARRATOR_ROLE to recap)
                }
                if (seedTurns.isNotEmpty()) {
                    runCatching { resumedClient.seedHistory(seedTurns) }
                }
                if (shutdownStarted.get() || manuallyPaused.get() || client !== resumedClient) {
                    return@launch
                }
                onConnectionStateCallback(VoiceConnectionState.READY)
                if (updateMicGate(providerReady = true)) startMicHealthCheck()
                onStatusCallback(listeningStatus("Resumed; you can keep talking"))
            }
            return
        }
        onConnectionStateCallback(VoiceConnectionState.READY)
        if (updateMicGate(providerReady = true)) {
            startMicHealthCheck()
        }
        // The conversation starts here, which is what the scene-transition lead-in measures from.
        // Only the first ready signal counts (the gate ignores the rest), so neither a reconnect
        // nor a character switch resets it.
        sceneTransitionGate.markSessionStart(android.os.SystemClock.elapsedRealtime())
        // Beta scene switch: this socket belongs to a different character, opened deliberately
        // rather than to recover from a drop, so it takes priority over the reconnect gate below
        // (a switch cancels any in-flight reconnect before connecting).
        pendingSceneSwitch.getAndSet(null)?.let { pending ->
            // A reconnect loop can legitimately be waiting on this very signal: a switch whose
            // own connect() died before setup surfaces as onConnectionLost, and the loop's
            // retry — which resends the new character's prompt and voice — is then what brings
            // the session up. Release its gate here too, or it waits out the full readiness
            // timeout and tears down the session that just came up as the new character.
            pendingReconnectGate.getAndSet(null)?.let { gate ->
                reconnectReadyBeforeJobExit.set(true)
                gate.complete(Unit)
            }
            applySceneSwitch(pending)
            return
        }
        val gate = pendingReconnectGate.getAndSet(null)
        if (gate != null) {
            reconnectReadyBeforeJobExit.set(true)
            gate.complete(Unit)
            onStatusCallback(
                if (!microphoneInitializationComplete) "Reconnected; starting microphone…"
                else listeningStatus("Reconnected — you can keep talking")
            )
            // The transport reconnected, but onSessionResumed() (fired before this callback —
            // see GeminiLiveClient.markSetupComplete) never landed, meaning the new provider
            // session started with a blank context instead of resuming the old one server-side.
            // Replay the recent transcript so the model isn't guessing the scene from scratch.
            if (!resumedThisAttempt) {
                val turnsSnapshot = synchronized(recentTurnsLock) { recentTurns.toList() }
                // Beta only: the accepted scene changes are not in the replayed turns (they were
                // never spoken by anyone) and not in the system prompt (which still describes
                // where the scene began), so a blank-context session would silently put everyone
                // back in the original setting. Seeded as a narrator turn in the same frame, which
                // keeps it inert — restating the scene must not itself provoke a model turn while
                // the learner may be mid-sentence.
                val recap = sceneRecap()
                val seedTurns = if (recap.isEmpty()) {
                    turnsSnapshot
                } else {
                    turnsSnapshot + (SceneTransitionProtocol.NARRATOR_ROLE to recap)
                }
                if (seedTurns.isNotEmpty()) {
                    coroutineScope.launch { runCatching { client.seedHistory(seedTurns) } }
                }
            }
        } else {
            onStatusCallback(
                if (!microphoneInitializationComplete) "Voice connected; starting microphone…"
                else listeningStatus("Listening")
            )
        }
    }

    /**
     * The provider confirmed this connect attempt resumed its prior server-side session (see
     * VoiceClient.seedHistory's fallback role). Only meaningful mid-reconnect; recorded here and
     * consumed by [onSessionReady] before the reconnect loop resets it for the next attempt.
     */
    override fun onSessionResumed() {
        resumedThisAttempt = true
    }

    private fun playPatientAudio(pcmData: ByteArray): Boolean {
        return audioIO.playAudio(pcmData)
            .onSuccess { playbackErrorReported.set(false) }
            .onFailure { error ->
                if (playbackErrorReported.compareAndSet(false, true)) {
                    onStatusCallback(
                        "Error: Patient audio playback failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }.isSuccess
    }

    override fun onAudioInterrupted() {
        if (!acceptingPlayback) return
        listeningStatusJob?.cancel()
        playbackCompletionJob?.cancel()
        playbackCompletionJob = null
        synchronized(playbackTimingLock) {
            estimatedPlaybackEndNanos = Long.MIN_VALUE
            providerTurnComplete = false
        }
        sessionSafety.markAiAudioInterrupted()
        synchronized(playbackStateLock) {
            stretcher.reset()
        }
        synchronized(playbackIoLock) {
            if (acceptingPlayback) audioIO.clearPlaybackQueue()
        }
        onStatusCallback(listeningStatus("AI audio interrupted - listening"))
    }

    private fun listeningStatus(healthyStatus: String): String = when {
        // Typed demo/mock never has a microphone to begin with (see start()'s early return
        // above) — reporting that absence as an "Error:" on every AI turn is what made a normal,
        // fully-expected typed session look broken. Only a backend that actually tried and failed
        // to start the mic should ever surface the error text below.
        !usesLiveMicrophone -> "Typed demo ready - microphone off"
        !microphoneInitializationComplete -> "Voice connected; starting microphone…"
        microphoneAvailable -> healthyStatus
        else -> "Error: Voice connected, but the microphone is unavailable. Use Type instead."
    }

    /**
     * §5 DEGRADED frame, backed by a real signal instead of a UI-side guess: the transport died
     * unexpectedly (see [VoiceClientListener.onConnectionLost]), so gate the mic, tell the user
     * plainly what's happening (their transcript is already safe — every turn is persisted as it
     * arrives), and retry [VoiceClient.connect] with exponential backoff. Never resends
     * [kickoffText] on a reconnect — that's the one-time "model speaks first" opener, not
     * something to repeat mid-conversation. If every attempt fails, the session stays exactly as
     * the user left it (transcript intact) so "End here & analyze what I have" — already offered
     * by the DEGRADED UI — has something real to work with.
     */
    @Synchronized
    override fun onConnectionLost() {
        if (shutdownStarted.get() || manuallyPaused.get() || !usesLiveMicrophone) return
        val lostImmediatelyAfterReconnect = reconnectReadyBeforeJobExit.getAndSet(false)
        updateMicGate(providerReady = false)
        onConnectionStateCallback(VoiceConnectionState.RECONNECTING)
        reconnectJob?.takeIf { it.isActive }?.let { activeReconnect ->
            // A freshly reconnected socket can fail in the small interval after readiness wakes
            // the current loop but before its Job completes. Close the mic immediately and queue
            // exactly one new reconnect cycle after that Job exits.
            if (lostImmediatelyAfterReconnect && reconnectAfterCurrent.compareAndSet(false, true)) {
                activeReconnect.invokeOnCompletion {
                    reconnectAfterCurrent.set(false)
                    if (!shutdownStarted.get() && !providerReady) onConnectionLost()
                }
            }
            return
        }
        synchronized(playbackIoLock) { audioIO.clearPlaybackQueue() }
        val launchedReconnect = coroutineScope.launch(start = CoroutineStart.LAZY) {
            var delayMillis = VoiceSessionSafety.RECONNECT_BASE_DELAY_MILLIS
            for (attempt in 1..VoiceSessionSafety.RECONNECT_MAX_ATTEMPTS) {
                if (shutdownStarted.get()) return@launch
                onStatusCallback(
                    "Connection lost — your transcript is safe. Retrying… " +
                        "(attempt $attempt of ${VoiceSessionSafety.RECONNECT_MAX_ATTEMPTS})"
                )
                delay(delayMillis)
                if (shutdownStarted.get()) return@launch

                // client.connect() only kicks off the handshake for the OkHttp-based providers —
                // it returns as soon as the socket object exists, long before the session is
                // actually usable. Wait for the provider's own readiness signal instead of
                // treating a non-throwing connect() call as proof the reconnect worked.
                val readyGate = CompletableDeferred<Unit>()
                pendingReconnectGate.set(readyGate)
                resumedThisAttempt = false
                var becameReady = false
                try {
                    client.connect(activeSystemPrompt, modelId, activeVoice.name)
                    becameReady = withTimeoutOrNull(RECONNECT_READY_TIMEOUT_MILLIS) {
                        readyGate.await()
                    } != null
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    // fall through to backoff below
                } finally {
                    pendingReconnectGate.compareAndSet(readyGate, null)
                }
                if (becameReady) return@launch
                delayMillis = (delayMillis * 2).coerceAtMost(VoiceSessionSafety.RECONNECT_MAX_DELAY_MILLIS)
            }
            if (!shutdownStarted.get()) {
                onConnectionStateCallback(VoiceConnectionState.FAILED)
                onStatusCallback(
                    "Couldn't reconnect after ${VoiceSessionSafety.RECONNECT_MAX_ATTEMPTS} attempts. " +
                        "Your transcript is saved — end the session to analyze what you have."
                )
            }
        }
        reconnectJob = launchedReconnect
        launchedReconnect.invokeOnCompletion { reconnectReadyBeforeJobExit.set(false) }
        launchedReconnect.start()
    }

    companion object {
        // Low enough for a quiet speaker, high enough to reject normal PCM room-floor noise.
        private const val MANUAL_TURN_SPEECH_LEVEL = 0.002f
        private const val PCM16_MONO_BYTES_PER_FRAME = 2L
        private const val AUDIO_STREAM_IDLE_GRACE_MILLIS = 200L
        private const val AUDIO_STREAM_IDLE_GRACE_NANOS =
            AUDIO_STREAM_IDLE_GRACE_MILLIS * 1_000_000L
        const val FINAL_TRANSCRIPT_DRAIN_MILLIS = 600L
        // Bounds one reconnect attempt's wait for onSessionReady before moving on to the next
        // backoff step. Must cover GeminiLiveClient sweeping its whole fallback chain within
        // one attempt: current candidates × the per-candidate setup watchdog, plus
        // connect/TLS overhead (see GeminiLiveClient.CANDIDATE_LIVE_MODELS). A beta session can
        // sweep that chain twice — once more with the scene-transition tool declared blocking (see
        // GeminiLiveClient.retryWithBlockingTools) — so budget for double the candidate count.
        const val RECONNECT_READY_TIMEOUT_MILLIS = 60_000L
        // Cap on the seedHistory() fallback replay — recent turns only, not the whole session,
        // to keep a very long "hangout" conversation's post-reconnect reseed bounded.
        const val MAX_RESUME_TURNS = 30

        /** Key for the counterpart the session started with, in [characterVoices]. */
        private const val ORIGINAL_CHARACTER_KEY = ""

        // Tighter than MAX_RESUME_TURNS on purpose: a character switch replays this history on a
        // brand-new session, and Gemini Live re-bills replayed context on every subsequent turn.
        private const val SCENE_SWITCH_SEED_TURNS = 14

        // How much of that window the new character hears word for word. Everything older is
        // folded into one digest line, because a replayed turn is re-billed on every subsequent
        // turn of the new session and the oldest ones earn that least (see compressSeedTurns).
        private const val SCENE_SWITCH_VERBATIM_TURNS = 6
        private const val SEED_DIGEST_MAX_CHARS = 700

        private const val VOICE_PICK_ATTEMPTS = 12

        /**
         * Whether a character-switch chip opens its session in the background while the learner
         * decides (see [prewarmSceneSwitch]) instead of on the tap.
         *
         * On means the switch is near-instant and a declined chip has opened a session that was
         * never spoken to; off means every switch pays the full handshake in front of the learner
         * and nothing speculative is ever opened. The trade is a latency win against a cost that
         * should be nil but has not been measured on real traffic — this constant is where that
         * decision lives once it has been.
         */
        private const val PREWARM_CHARACTER_SWITCH = true

        // Only the same-scene changes of the current character are ever tracked, and the gate caps
        // those at SceneTransitionGate.MAX_PROPOSALS_PER_SESSION — this is a defensive bound, not
        // an expected one, and stays above that cap deliberately.
        private const val MAX_TRACKED_SCENE_CHANGES = 6
    }
}
