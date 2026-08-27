package com.example.medvoicetrainer.voice

/** Provider connection phase kept separate from human-readable status copy. */
enum class VoiceConnectionState { CONNECTING, READY, USER_PAUSED, RECONNECTING, FAILED, CLOSED }

enum class MicrophoneState { STARTING, AVAILABLE, UNAVAILABLE, OFF }

interface VoiceClient {
    fun setListener(listener: VoiceClientListener)

    /**
     * [voice] is the provider-specific prebuilt voice name to speak with (see [VoiceCatalog]) —
     * blank means "use the provider's own default voice". Callers that want session-to-session
     * voice variety pick one via [VoiceCatalog.randomVoice] and pass the same value on every
     * connect() for a given session (including reconnects), so a mid-session reconnect can't
     * change the character's voice out from under the learner.
     */
    suspend fun connect(systemPrompt: String, model: String, voice: String)
    suspend fun sendAudioChunk(pcmData: ByteArray)
    suspend fun sendText(text: String)
    suspend fun close()

    /**
     * Flush the live microphone buffer and ask the provider to treat it as a completed learner
     * turn. This is the manual escape hatch for noisy rooms and server VAD misses.
     *
     * Returns false for transports that have no live audio turn to finish (the offline mock).
     */
    suspend fun finishUserTurn(): Boolean = false

    /**
     * Ask the AI to speak first (interview mode, or any case declaring `kickoff_text`).
     * Ported from the PC voice clients' `voice_config["kickoff_text"]` handling: Gemini uses a
     * realtime-input text turn once setup completes, OpenAI injects a hidden user turn plus
     * response.create. Default no-op — MockVoiceClient already emits its own scripted opener,
     * so it needs no kickoff. Implementations must tolerate being called before the socket is
     * fully ready (buffer the kickoff and flush when the session becomes ready).
     */
    suspend fun sendKickoffText(text: String) {}

    /**
     * Ask the backend to change its own audio output speed (server-side), ported from the PC
     * OpenAI client's `request_output_speed`/`_apply_pending_output_speed` (app/voice/openai_client.py).
     * OpenAI's Realtime API exposes an `audio.output.speed` field (0.25×–1.5×) that changes only
     * between model turns, so implementations should defer the change to the next turn boundary
     * when a response is currently active. Default no-op: Gemini has no documented native speed
     * field and Mock has no audio, so those backends rely entirely on VoiceManager's local WSOLA
     * stretcher instead (see Capabilities.planPlaybackSpeed).
     */
    suspend fun requestOutputSpeed(speed: Double) {}

    /**
     * Fallback context replay for a reconnect that landed on a brand-new provider session
     * (no server-side conversation memory survived — e.g. a Gemini Live resumption handle
     * that expired or was never granted). [turns] is (role, text) in chronological order.
     * Default no-op: only meaningful for providers whose reconnect can silently lose context;
     * Mock has no real transport to lose, and OpenAI's realtime reconnect is out of scope here.
     * Implementations should seed this as inert conversation history, not as new input that
     * would trigger an immediate model turn.
     */
    suspend fun seedHistory(turns: List<Pair<String, String>>) {}

    /**
     * Opt this client in to the Survival "Advanced Beta" scene-transition tool before [connect] is
     * called (see SceneTransition.kt). Default no-op, and the flag is off by default, so every
     * existing caller — and every ordinary Survival/Encounter session — keeps producing exactly the
     * setup message it produced before: the tool is declared to the provider only for a session
     * that asked for it. Implementations must treat this as session configuration, i.e. read it
     * when building the setup/session-update message rather than mutating a live session.
     */
    fun enableSceneTransitions(enabled: Boolean) {}

    /**
     * Answer a pending [SceneTransitionProposal] with how it ended — the learner tapped
     * accept/ignore, the chip timed out, or the app's own rate limit rejected it. Every proposal
     * must be answered exactly once, even where the tool is declared asynchronously: an
     * unanswered call is one the model keeps believing is still open. Default no-op for clients
     * that never surface proposals in the first place.
     *
     * [note] overrides the plain-English instruction the response carries for [outcome] (see
     * [SceneTransitionOutcome.note]); blank keeps that default. Callers pass one only where the
     * default cannot be specific enough — chiefly [SceneTransitionOutcome.NOT_APPLICABLE], whose
     * whole value is telling the model *which* thing about the proposal did not fit.
     */
    suspend fun respondToSceneTransition(
        id: String,
        outcome: SceneTransitionOutcome,
        note: String = "",
    ) {}
}

interface VoiceClientListener {
    fun onStatus(text: String)
    fun onError(message: String)
    fun onTranscript(role: String, text: String, isFinal: Boolean)
    fun onAudioReceived(pcmData: ByteArray)
    /**
     * The model's audible turn has ended. This is the playback/microphone boundary, not a
     * transcript boundary: providers may deliver the final transcript immediately afterwards.
     * Implementations must therefore emit this before dispatching a completed assistant
     * transcript from the same provider event.
     */
    fun onTurnComplete()

    /** Exact billable token breakdown reported by a completed realtime model response. */
    fun onUsage(usage: VoiceApiUsage) {}

    /**
     * Provider reports that the current model-audio turn was cancelled/interrupted (usually
     * barge-in). Default no-op preserves source compatibility for listeners and clients that do
     * not expose this event yet.
     */
    fun onAudioInterrupted() {}

    /**
     * The transport dropped unexpectedly (socket failure, or a close the client didn't ask for)
     * — distinct from [onError], which also covers non-connection problems (malformed
     * messages, a rejected send) that shouldn't trigger a reconnect attempt. Backs
     * docs/design/android-ui-spec.html §5's DEGRADED/PAUSED turn-state: VoiceManager retries
     * [VoiceClient.connect] with backoff on this signal (see VoiceSessionSafety.RECONNECT_*).
     * Default no-op for listeners that don't care (MockVoiceClient never fires this — it has no
     * real transport to lose).
     */
    fun onConnectionLost() {}

    /**
     * The live session is actually usable now — the provider handshake finished AND its own
     * setup/session-update round trip completed (Gemini's setupComplete, OpenAI's
     * session.updated). [VoiceClient.connect] returns as soon as the socket object exists, well
     * before this point, so callers must not treat a non-throwing [VoiceClient.connect] call as
     * proof the session is ready to converse — wait for this signal instead. Default no-op for
     * Mock, which has no real handshake to wait on.
     */
    fun onSessionReady() {}

    /**
     * Fired before [onSessionReady] when the provider confirms this connect attempt actually
     * resumed the prior conversation server-side (Gemini Live session resumption), as opposed
     * to starting a fresh context. Callers use its absence on a reconnect as the signal to fall
     * back to [VoiceClient.seedHistory]. Default no-op for providers/mocks with no resumption
     * concept — those callers should treat every reconnect as needing the fallback.
     */
    fun onSessionResumed() {}

    /**
     * The model asked (via function call) to change the scene — see SceneTransition.kt. Only ever
     * fired by a client that was opted in through [VoiceClient.enableSceneTransitions], so the
     * default no-op is what every non-beta session sees. The listener owns the decision and MUST
     * eventually call [VoiceClient.respondToSceneTransition] for this proposal's id.
     */
    fun onSceneTransitionProposed(proposal: SceneTransitionProposal) {}

    /**
     * The provider withdrew a proposal before it was answered (`toolCallCancellation`). No
     * response should be sent for [id] because the call no longer exists. A UI may keep an already
     * displayed, side-effect-free suggestion and treat a later acceptance as a new local learner
     * action; cancellation only invalidates the provider call itself.
     */
    fun onSceneTransitionCancelled(id: String) {}

    /**
     * Which mode the scene-transition tool was actually declared in for the session that just came
     * up, fired once per successful setup on an opted-in client (before [onSessionReady]).
     *
     * [nonBlocking] is normally true. It is false for a session that had to fall back to an
     * ordinary blocking declaration because the server refused the asynchronous one (see
     * `GeminiLiveClient.retryWithBlockingTools`) — and that difference is not cosmetic: on a
     * blocking session the model stops speaking the moment it proposes and stays mute until the
     * response arrives, so the promise the feature is built on ("propose silently and keep
     * talking") no longer holds. A listener that leaves a chip on screen for the full
     * [SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS] there is choosing half a minute of dead air;
     * [SceneTransitionGate.PROPOSAL_TIMEOUT_BLOCKING_MILLIS] exists for exactly this case.
     */
    fun onSceneTransitionModeResolved(nonBlocking: Boolean) {}
}
