package com.example.medvoicetrainer.voice

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    // Overridable only so JVM tests can point the real socket machinery at a MockWebServer.
    private val wsBaseUrl: String = LIVE_WS_URL,
    private val setupTimeoutMillis: Long = SETUP_TIMEOUT_MILLIS,
    private val socketUpgradeTimeoutMillis: Long = SOCKET_UPGRADE_TIMEOUT_MILLIS,
) : VoiceClient {
    private data class QueuedFrame(
        val payload: String,
        val audioBytes: Int = 0,
        // Non-null for a scene-transition toolResponse, so a frame answering a function call can
        // be recognized (and dropped) if the session it belonged to is gone — see
        // markSetupComplete. Null for every ordinary frame, which is every frame outside the beta.
        val sceneTransitionId: String? = null,
    )

    private data class AttemptInfo(
        val model: String,
        val systemPrompt: String,
        val attemptId: Long,
        val resumptionHandle: String?,
        val voice: String,
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // For WebSockets OkHttp applies callTimeout only to the HTTP/TLS upgrade. Once upgraded,
        // the live stream remains unlimited; a half-open upgrade can no longer hang forever.
        .callTimeout(socketUpgradeTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    private val stateLock = Any()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupWatchdogJob: Job? = null
    private val pendingFrames = mutableListOf<QueuedFrame>()
    private val userTranscript = TurnTranscriptBuffer()
    private val assistantTranscript = TurnTranscriptBuffer()
    private val fallbackAssistantText = TurnTranscriptBuffer()
    private var pendingTurnUsage: VoiceApiUsage? = null

    // Volatile because it is not only set once before connect(): VoiceManager's pre-warmed scene
    // switch reassigns it on a live client, from the switch coroutine, while this client's socket
    // reader thread is the one dereferencing it. Without this a promoted session could keep
    // delivering its first events to the listener it was brought up under.
    @Volatile
    private var listener: VoiceClientListener? = null
    private var webSocket: WebSocket? = null
    private var socketOpen = false
    private var setupComplete = false
    private var connectGeneration = 0L
    private var nextAttemptId = 0L
    private var activeAttemptId = 0L
    private var closed = false
    private var queuedAudioBytes = 0
    private var selectedModel = DEFAULT_GEMINI_LIVE_MODEL

    // Live models come and go and a preview model may simply not be enabled for a given API key.
    // Mirroring app/voice/gemini_client.py's _candidate_models(), connect() sweeps an ordered list
    // of candidates and, whenever a handshake fails *before* setupComplete, advances to the next
    // one instead of surfacing a dead session. Only a drop *after* a successful setup counts as a
    // real mid-session connection loss (delegated to the listener's reconnect path).
    private var candidateModels: List<String> = CANDIDATE_LIVE_MODELS
    private var candidateIndex = 0
    private var activeSystemPrompt = ""
    // The voice name (see VoiceCatalog) chosen once for this whole session by the caller (usually
    // VoiceManager, via VoiceCatalog.randomVoice) and resent unchanged on every connect() —
    // including a mid-session reconnect — so the character's voice never changes mid-conversation.
    private var activeVoice = ""
    // Once a candidate reaches setupComplete it is remembered and retried first on the next
    // connect() (e.g. VoiceManager's mid-session reconnect), so recovery skips the dead candidates.
    private var lastGoodModel: String? = null

    // Gemini Live session-resumption handle (BidiGenerateContentSetup.sessionResumption). Set
    // from sessionResumptionUpdate server messages and re-sent on every subsequent connect()
    // attempt so a mid-session socket drop restores the server-side conversation instead of
    // starting a blank one. Survives across connect() calls on purpose — that is the whole
    // point of a mid-session reconnect carrying it — and is cleared only when an attempt that
    // used it fails before setup (the handle itself may be the problem) or on close().
    private var resumptionHandle: String? = null

    // Per-attempt snapshot of whichever resumptionHandle value (if any) was actually sent in
    // that attempt's setup message, keyed by attemptId. Lets markSetupComplete/handleSocketDown/
    // the setup watchdog tell "this attempt used a handle" apart from "this attempt started
    // fresh" without racing a concurrent sessionResumptionUpdate that changes the live field
    // mid-attempt.
    private val attemptResumptionHandle = HashMap<Long, String?>()

    // Survival "Advanced Beta" only (see SceneTransition.kt): when false — the default, and the
    // only value an ordinary session ever sets — buildSetupMessage emits no `tools` key at all and
    // toolCall frames can never arrive, so the beta feature is genuinely absent rather than idle.
    @Volatile
    private var sceneTransitionsEnabled = false

    // Function-call name per outstanding proposal id, so the toolResponse mirrors the name the
    // server actually sent instead of assuming the declared constant. Entries are removed when the
    // proposal is answered or cancelled, and dropped wholesale on close().
    private val pendingSceneTransitionCalls = HashMap<String, String>()

    // Whether the tool is being declared asynchronously (SceneTransitionProtocol's
    // BEHAVIOR_NON_BLOCKING) — the mode that lets the model keep talking after proposing a change
    // instead of freezing until the learner answers. Turned off for the rest of the session only by
    // downgradeToBlockingTools(), i.e. after a server has refused every candidate model with it.
    @Volatile
    private var nonBlockingTools = true

    // True once the whole candidate chain has been swept with blocking tools as well, so the
    // downgrade is attempted at most once per connect() and a genuinely dead session still
    // surfaces its error instead of sweeping forever.
    private var blockingToolsFallbackTried = false

    // Whether any candidate in the current connect() sweep got as far as an open socket. A sweep
    // where none did failed at the network or the API key, not at anything in the setup message,
    // so re-sweeping it with different tools would only double the wait before the real error.
    private var socketOpenedThisConnect = false

    // What the *live* session's setup message actually declared. Read when building a response
    // frame, because `scheduling` is only meaningful on a call that was declared asynchronous, and
    // a session that came up through the downgrade above declared the opposite of the default.
    @Volatile
    private var nonBlockingToolsDeclared = false

    override fun setListener(listener: VoiceClientListener) {
        this.listener = listener
    }

    override suspend fun connect(systemPrompt: String, model: String, voice: String) {
        val requested = normalizeGeminiLiveModel(model)
        val (previousSocket, generation) = synchronized(stateLock) {
            val previous = webSocket
            webSocket = null
            socketOpen = false
            setupComplete = false
            setupWatchdogJob?.cancel()
            setupWatchdogJob = null
            activeAttemptId = 0L
            closed = false
            connectGeneration += 1L
            activeSystemPrompt = systemPrompt
            activeVoice = voice
            candidateModels = buildCandidateModels(requested)
            candidateIndex = 0
            // One downgrade attempt per connect() sweep. nonBlockingTools itself is deliberately
            // NOT reset: once a server has refused asynchronous tools for this key, every later
            // reconnect on this client should go straight to the mode that worked.
            blockingToolsFallbackTried = false
            socketOpenedThisConnect = false
            // Stale entries from a prior connect() cycle's attemptIds are dead weight now —
            // resumptionHandle itself is intentionally NOT cleared here (see its declaration).
            attemptResumptionHandle.clear()
            previous to connectGeneration
        }
        // A VoiceClient instance normally reconnects only after its old socket is already down,
        // but explicitly replacing it here prevents two live sockets if connect() is called twice.
        previousSocket?.cancel()
        openCandidateSocket(generation)
    }

    private fun buildCandidateModels(requested: String): List<String> =
        orderedGeminiLiveCandidates(requested, lastGoodModel, CANDIDATE_LIVE_MODELS)

    private fun openCandidateSocket(generation: Long) {
        val candidate = synchronized(stateLock) {
            if (closed || generation != connectGeneration) return
            socketOpen = false
            setupComplete = false
            selectedModel = candidateModels[candidateIndex]
            nextAttemptId += 1L
            activeAttemptId = nextAttemptId
            val handleForAttempt = resumptionHandle
            attemptResumptionHandle[activeAttemptId] = handleForAttempt
            AttemptInfo(selectedModel, activeSystemPrompt, activeAttemptId, handleForAttempt, activeVoice)
        }
        val (model, systemPrompt, attemptId, resumptionHandleForAttempt, voiceForAttempt) = candidate

        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val request = Request.Builder().url("$wsBaseUrl?key=$encodedKey").build()

        val callback = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val active = synchronized(stateLock) {
                    val isActive = !closed && generation == connectGeneration &&
                        activeAttemptId == attemptId
                    if (isActive) {
                        this@GeminiLiveClient.webSocket = webSocket
                        socketOpen = true
                        socketOpenedThisConnect = true
                    }
                    isActive
                }
                if (!active) {
                    webSocket.cancel()
                    return
                }
                listener?.onStatus("Gemini socket connected; configuring session ($model)")
                // connect() may replace this socket while the status callback above is running.
                val stillActive = synchronized(stateLock) {
                    !closed && generation == connectGeneration &&
                        activeAttemptId == attemptId &&
                        this@GeminiLiveClient.webSocket === webSocket
                }
                if (!stillActive ||
                    !webSocket.send(
                        buildSetupMessage(model, systemPrompt, resumptionHandleForAttempt, voiceForAttempt)
                    )
                ) {
                    handleSocketDown(
                        webSocket,
                        "setup message was rejected by the WebSocket",
                        generation,
                        attemptId,
                    )
                    webSocket.cancel()
                    return
                }
                startSetupWatchdog(webSocket, model, generation, attemptId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(webSocket, text, generation, attemptId, model)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini normally sends JSON text frames, but WebSocket intermediaries and test
                // servers may preserve it as a binary UTF-8 frame. Ignoring that frame means the
                // setupComplete event is lost and every mic/text input remains queued forever.
                handleServerMessage(webSocket, bytes.utf8(), generation, attemptId, model)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = response?.let { "HTTP ${it.code} ${it.message}" }
                    ?: t.message
                    ?: t.javaClass.simpleName
                handleSocketDown(webSocket, redactReason(reason), generation, attemptId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // OkHttp never delivers onClosed (or onFailure) for a server-initiated close
                // unless we acknowledge it with our own close frame — the reader loop has
                // already stopped. Gemini rejects an unavailable model exactly this way, so
                // without this ack the session would hang at "configuring session" forever
                // with the typed/spoken input queued and no candidate fallback.
                runCatching { webSocket.close(1000, null) }
                handleSocketDown(
                    webSocket,
                    reason.ifBlank { "closed by server (code $code)" },
                    generation,
                    attemptId,
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // code 1000 from our own close() never reaches here as "active" (close() nulls
                // the field first), so any close seen while still active is the server/network
                // ending the session on us, not something the user asked for.
                handleSocketDown(
                    webSocket,
                    reason.ifBlank { "closed (code $code)" },
                    generation,
                    attemptId,
                )
            }
        }
        val socket = try {
            client.newWebSocket(request, callback)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            tryNextCandidate(redactReason(error.message ?: error.javaClass.simpleName), generation)
            return
        }
        val accepted = synchronized(stateLock) {
            if (closed || generation != connectGeneration || activeAttemptId != attemptId) {
                false
            } else {
                if (webSocket == null) webSocket = socket
                webSocket === socket
            }
        }
        if (!accepted) socket.cancel()
    }

    /**
     * Bounds the wait for setupComplete on one candidate socket. A server that accepts the
     * connection but never answers the setup message (and never closes) would otherwise leave
     * the session in "configuring" limbo with no callback ever firing to advance the fallback
     * chain. The check-and-detach is atomic with markSetupComplete, so a setup that lands at
     * the same instant can never be torn down.
     */
    private fun startSetupWatchdog(
        socket: WebSocket,
        model: String,
        generation: Long,
        attemptId: Long,
    ) {
        lateinit var watchdog: Job
        watchdog = watchdogScope.launch(start = CoroutineStart.LAZY) {
            delay(setupTimeoutMillis)
            val timedOut = synchronized(stateLock) {
                if (closed || generation != connectGeneration || activeAttemptId != attemptId ||
                    webSocket !== socket || setupComplete
                ) {
                    return@synchronized false
                }
                webSocket = null
                activeAttemptId = 0L
                socketOpen = false
                if (setupWatchdogJob === watchdog) setupWatchdogJob = null
                true
            }
            if (timedOut) {
                clearHandleIfAttemptUsedIt(attemptId)
                socket.cancel()
                tryNextCandidate(
                    "$model setup timed out after ${setupTimeoutMillis / 1000}s",
                    generation,
                )
            }
        }
        val installed = synchronized(stateLock) {
            if (closed || generation != connectGeneration || activeAttemptId != attemptId ||
                webSocket !== socket || setupComplete
            ) {
                false
            } else {
                setupWatchdogJob?.cancel()
                setupWatchdogJob = watchdog
                true
            }
        }
        if (installed) watchdog.start() else watchdog.cancel()
    }

    /**
     * A socket died. wasActive is false when this races in after close()/a newer connect() already
     * replaced the field — a stale callback for a socket the caller walked away from, which must
     * never trigger anything. If the socket never reached setupComplete this is a handshake failure
     * (bad/unavailable model, auth, transient network on connect): advance to the next candidate.
     * Only a drop after a good setup is a genuine mid-session loss handed to the reconnect path.
     */
    private fun handleSocketDown(
        webSocket: WebSocket,
        reason: String,
        generation: Long,
        attemptId: Long,
    ) {
        val (wasActive, hadSetup) = synchronized(stateLock) {
            val active = !closed && generation == connectGeneration &&
                activeAttemptId == attemptId &&
                (this@GeminiLiveClient.webSocket == null ||
                    this@GeminiLiveClient.webSocket === webSocket)
            val hadSetup = setupComplete
            if (active) {
                // Detach immediately: a dying socket fires several listener callbacks
                // (onClosing then onClosed, or cancel() then onFailure) and only the first
                // may advance the candidate chain / trigger the reconnect path.
                this@GeminiLiveClient.webSocket = null
                activeAttemptId = 0L
                socketOpen = false
                setupComplete = false
                setupWatchdogJob?.cancel()
                setupWatchdogJob = null
            }
            active to hadSetup
        }
        if (!wasActive) return
        if (hadSetup) {
            if (isActiveGeneration(generation)) listener?.onConnectionLost()
        } else {
            // A pre-setup failure while this attempt's setup message carried a resumption
            // handle may mean the handle itself was rejected (expired/invalid) — clear it so
            // the next attempt (the next model candidate here, or a later reconnect) starts
            // fresh instead of repeating the same rejected handle against every candidate.
            clearHandleIfAttemptUsedIt(attemptId)
            tryNextCandidate(reason, generation)
        }
    }

    private fun clearHandleIfAttemptUsedIt(attemptId: Long) {
        synchronized(stateLock) {
            if (attemptResumptionHandle.remove(attemptId) != null) resumptionHandle = null
        }
    }

    private fun tryNextCandidate(reason: String, generation: Long) {
        val nextModel = synchronized(stateLock) {
            if (closed || generation != connectGeneration) return
            candidateIndex += 1
            candidateModels.getOrNull(candidateIndex)
        }
        if (nextModel != null) {
            if (!isActiveGeneration(generation)) return
            listener?.onStatus("Voice model unavailable — trying $nextModel")
            openCandidateSocket(generation)
        } else {
            // Every candidate refused a setup message that declared the scene-transition tool
            // asynchronously. That declaration is the one thing this session asks for that an
            // ordinary session does not, so before calling the whole session dead, sweep the chain
            // once more in the blocking mode the feature shipped with — a beta that runs with
            // frozen pauses is still far better than a Survival session that will not start.
            // Requires that some candidate actually opened a socket: a sweep that never got that
            // far failed at the network or the key, where re-sweeping only delays the real error.
            if (retryWithBlockingTools(generation)) {
                if (!isActiveGeneration(generation)) return
                listener?.onStatus("Voice tools unavailable — retrying without them")
                openCandidateSocket(generation)
                return
            }
            // Every candidate failed before a usable session. Surface why, then let the caller's
            // reconnect path (VoiceManager) decide whether to retry the whole sweep after a backoff.
            if (!isActiveGeneration(generation)) return
            listener?.onError(
                com.example.medvoicetrainer.api.ApiError.userMessage(
                    com.example.medvoicetrainer.api.ApiError.fromProviderMessage("Gemini Live", reason),
                    "Gemini Live couldn't establish a voice connection. Check your API key and selected voice model."
                )
            )
            if (!isActiveGeneration(generation)) return
            listener?.onConnectionLost()
        }
    }

    /**
     * Rewind the candidate chain for one more sweep with the scene-transition tool declared in the
     * ordinary blocking mode, and report whether that sweep should now happen. Returns false — and
     * changes nothing — for every session that is not the beta, has already downgraded, has already
     * used its one retry, or never opened a socket at all.
     */
    private fun retryWithBlockingTools(generation: Long): Boolean = synchronized(stateLock) {
        if (closed || generation != connectGeneration) return false
        if (!sceneTransitionsEnabled || !nonBlockingTools) return false
        if (blockingToolsFallbackTried || !socketOpenedThisConnect) return false
        nonBlockingTools = false
        blockingToolsFallbackTried = true
        candidateIndex = 0
        true
    }

    private fun isActiveGeneration(generation: Long): Boolean = synchronized(stateLock) {
        !closed && generation == connectGeneration
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                // mediaChunks is deprecated in v1beta; audio is the current realtime field.
                put("audio", JSONObject().apply {
                    put("data", Base64.encodeToString(pcmData, Base64.NO_WRAP))
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }.toString()
        sendOrQueue(payload, audioBytes = pcmData.size)
    }

    override suspend fun finishUserTurn(): Boolean {
        // With automatic activity detection enabled, audioStreamEnd flushes Gemini's cached audio
        // and closes the current microphone stream. A later audio chunk opens a new stream, so the
        // ordinary always-on microphone can continue immediately after this manual boundary.
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        }.toString()
        sendOrQueue(payload)
        return true
    }

    override suspend fun sendKickoffText(text: String) {
        if (text.isBlank()) return
        sendRealtimeText(text)
    }

    override fun enableSceneTransitions(enabled: Boolean) {
        sceneTransitionsEnabled = enabled
    }

    /**
     * Answer one outstanding `propose_scene_transition` call. Routed through [sendOrQueue] like
     * every other frame, so an answer produced while a reconnect is in flight survives instead of
     * being dropped — a Live model that called a function stays silent until it gets a response.
     */
    override suspend fun respondToSceneTransition(
        id: String,
        outcome: SceneTransitionOutcome,
        note: String,
    ) {
        if (id.isBlank()) return
        // Only a call this session still has outstanding may be answered. An id leaves the map
        // exactly when the call it names stops existing: the provider cancelled it, close() tore
        // the session down, or a reconnect landed on a fresh server-side context that never heard
        // of it (see markSetupComplete). Answering one of those sends a response for an id the
        // server does not know, which it rejects with an error frame — taking a socket that had
        // just recovered straight back down. The solo errand makes this reachable rather than
        // theoretical: its acceptance is deliberately held back while the learner reads the fact
        // card, so it can arrive tens of seconds after the call that prompted it died.
        val name = synchronized(stateLock) { pendingSceneTransitionCalls.remove(id) } ?: return
        sendOrQueue(
            SceneTransitionProtocol.buildToolResponse(
                id,
                name,
                outcome,
                nonBlockingToolsDeclared,
                note = note.ifBlank { outcome.note },
            ),
            sceneTransitionId = id,
        )
    }

    override suspend fun sendText(text: String) {
        if (text.isBlank()) return
        // Current live models accept subsequent text through realtimeInput. In particular,
        // Gemini 3.1 only permits clientContent for initial-history seeding.
        sendRealtimeText(text)
    }

    /**
     * Fallback for a reconnect whose new socket did NOT resume the prior server-side session
     * (see [resumptionHandle] and [VoiceClientListener.onSessionResumed]) — replays the recent
     * transcript as inert conversation history via `clientContent` (the same mechanism Gemini
     * reserves for initial-history seeding; see [sendText]'s comment) so the model has the
     * scene's actual history instead of starting blank. `turnComplete: false` is deliberate: this
     * must not itself provoke a new model turn, only make the history available going forward.
     */
    override suspend fun seedHistory(turns: List<Pair<String, String>>) {
        val nonEmpty = turns.filter { it.second.isNotBlank() }
        if (nonEmpty.isEmpty()) return
        val payload = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    nonEmpty.forEach { (role, text) ->
                        put(JSONObject().apply {
                            // The narrator role carries the app's own stage directions (see
                            // SceneTransitionProtocol): instructions handed to the actor, not lines
                            // the character spoke, so they ride the user side of the channel. A
                            // "model" turn here would have the character reading stage directions
                            // aloud as its own past dialogue.
                            put(
                                "role",
                                when (role) {
                                    "user", "doctor", SceneTransitionProtocol.NARRATOR_ROLE -> "user"
                                    else -> "model"
                                },
                            )
                            put("parts", JSONArray().put(JSONObject().put("text", text)))
                        })
                    }
                })
                put("turnComplete", false)
            })
        }.toString()
        sendOrQueue(payload)
    }

    override suspend fun close() {
        // Usage the server already reported for a turn that never reached turnComplete is still
        // billed. Dropping it (which clearTurnBuffersLocked below does) is what made an
        // interrupted session's stored cost unfalsifiably low, so hand it to the listener first.
        val unflushedUsage = synchronized(stateLock) {
            pendingTurnUsage.also { pendingTurnUsage = null }
        }
        unflushedUsage?.let { listener?.onUsage(it) }
        val socket = synchronized(stateLock) {
            closed = true
            connectGeneration += 1L
            activeAttemptId = 0L
            socketOpen = false
            setupComplete = false
            queuedAudioBytes = 0
            pendingFrames.clear()
            clearTurnBuffersLocked()
            setupWatchdogJob?.cancel()
            setupWatchdogJob = null
            resumptionHandle = null
            attemptResumptionHandle.clear()
            pendingSceneTransitionCalls.clear()
            webSocket.also { webSocket = null }
        }
        socket?.close(1000, "User requested close")
    }

    private fun buildSetupMessage(
        model: String,
        systemPrompt: String,
        resumptionHandle: String?,
        voice: String,
    ): String =
        JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$model")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voice.ifBlank { "Puck" }),
                            )
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                // Empty config objects enable independent input and output audio transcription.
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        put("prefixPaddingMs", 100)
                        put("silenceDurationMs", 600)
                    })
                })
                // Restores the prior turn history server-side on a mid-session reconnect
                // instead of starting the model with a blank context — see resumptionHandle's
                // declaration and handleServerMessage's sessionResumptionUpdate handling. An
                // empty object (no "handle") still opts into receiving update events for a
                // *future* reconnect even on the very first connect of a session.
                put("sessionResumption", JSONObject().apply {
                    if (!resumptionHandle.isNullOrEmpty()) put("handle", resumptionHandle)
                })
                // Lets a Live session run well past the default context-bound duration cap by
                // having the server compress older turns instead of dropping the connection —
                // the actual fix for long Survival "hangout" conversations (10-20+ minutes).
                put("contextWindowCompression", JSONObject().apply {
                    put("slidingWindow", JSONObject())
                })
                // Survival "Advanced Beta" only. Absent (not empty) for every other session, so an
                // ordinary Survival/Encounter setup message is unchanged and its model can never
                // emit a toolCall in the first place.
                if (sceneTransitionsEnabled) {
                    val async = nonBlockingTools
                    nonBlockingToolsDeclared = async
                    put("tools", SceneTransitionProtocol.toolsDeclaration(nonBlocking = async))
                }
            })
        }.toString()

    private fun sendRealtimeText(text: String) {
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().put("text", text))
        }.toString()
        sendOrQueue(payload)
    }

    private fun sendOrQueue(
        payload: String,
        audioBytes: Int = 0,
        sceneTransitionId: String? = null,
    ) {
        val frame = QueuedFrame(payload, audioBytes, sceneTransitionId)
        var failedSocket: WebSocket? = null
        var failedAttemptId = 0L
        var failedGeneration = 0L
        synchronized(stateLock) {
            // A capture coroutine can arrive just after close(). Never let old-session input refill
            // the queue and leak into a later connect() on the same client instance.
            if (closed) return
            val socket = webSocket
            if (setupComplete && socketOpen && socket != null) {
                if (!socket.send(payload)) {
                    // Preserve the frame for VoiceManager's reconnect instead of reporting an
                    // error after it has already been irretrievably dropped.
                    enqueueLocked(frame)
                    failedSocket = socket
                    failedAttemptId = activeAttemptId
                    failedGeneration = connectGeneration
                }
            } else {
                enqueueLocked(frame)
            }
        }
        failedSocket?.let { socket ->
            handleSocketDown(
                socket,
                "Gemini Live WebSocket rejected an outgoing message",
                failedGeneration,
                failedAttemptId,
            )
            socket.cancel()
        }
    }

    private fun enqueueLocked(frame: QueuedFrame) {
        if (frame.audioBytes > 0) {
            while (queuedAudioBytes + frame.audioBytes > MAX_QUEUED_AUDIO_BYTES) {
                val index = pendingFrames.indexOfFirst { it.audioBytes > 0 }
                if (index < 0) break
                queuedAudioBytes -= pendingFrames.removeAt(index).audioBytes
            }
            if (frame.audioBytes > MAX_QUEUED_AUDIO_BYTES) return
            queuedAudioBytes += frame.audioBytes
        }
        pendingFrames += frame
    }

    private fun markSetupComplete(
        webSocket: WebSocket,
        generation: Long,
        attemptId: Long,
        model: String,
    ) {
        var sendFailed = false
        var resumed = false
        var abandonedSceneCalls: List<String> = emptyList()
        synchronized(stateLock) {
            if (closed || generation != connectGeneration || activeAttemptId != attemptId ||
                this.webSocket !== webSocket
            ) return
            setupComplete = true
            setupWatchdogJob?.cancel()
            setupWatchdogJob = null
            // This candidate handshake succeeded — prefer it first on any later reconnect so the
            // dead candidates ahead of it in the list aren't tried again.
            lastGoodModel = model
            // A non-null value here means this attempt's setup message carried a resumption
            // handle and the server accepted the whole handshake — i.e. it actually resumed the
            // prior conversation rather than starting blank.
            resumed = attemptResumptionHandle.remove(attemptId) != null
            if (!resumed) {
                // This session has never heard of the previous one's function calls. Flushing a
                // queued answer would send a response for an id this server does not know (which
                // it answers with an error frame, taking the freshly recovered socket back down),
                // and the model that asked the question died with the old socket. Drop those
                // answers and tell the listener the proposals are gone so a chip still on screen
                // closes instead of waiting for a conversation that no longer exists.
                if (pendingSceneTransitionCalls.isNotEmpty()) {
                    abandonedSceneCalls = pendingSceneTransitionCalls.keys.toList()
                    pendingSceneTransitionCalls.clear()
                }
                pendingFrames.removeAll { it.sceneTransitionId != null }
            }
            var sentCount = 0
            while (sentCount < pendingFrames.size) {
                if (!webSocket.send(pendingFrames[sentCount].payload)) break
                sentCount += 1
            }
            // Drop the sent prefix in one shift. `repeat(sentCount) { removeAt(0) }` re-shifted the
            // whole remaining backlog per frame, i.e. quadratic in a queue that can hold ten
            // seconds of 16 kHz audio — and this runs on the reconnect path, when it is full.
            var reclaimedAudioBytes = 0
            for (index in 0 until sentCount) reclaimedAudioBytes += pendingFrames[index].audioBytes
            pendingFrames.subList(0, sentCount).clear()
            queuedAudioBytes = (queuedAudioBytes - reclaimedAudioBytes).coerceAtLeast(0)
            sendFailed = pendingFrames.isNotEmpty()
        }
        if (sendFailed) {
            handleSocketDown(
                webSocket,
                "Gemini rejected queued input after setup",
                generation,
                attemptId,
            )
            webSocket.cancel()
            return
        }
        if (!isActiveSocket(webSocket, generation, attemptId)) return
        listener?.onStatus("Connected to Gemini Live ($model)")
        // Which tool mode this session actually came up in. Fired before onSessionReady so a
        // listener that adapts to it (a shorter chip timeout, say) has the answer before the first
        // proposal can possibly arrive — and on every setup, because a mid-session reconnect is
        // where a session silently changes mode.
        if (sceneTransitionsEnabled) {
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            listener?.onSceneTransitionModeResolved(nonBlockingToolsDeclared)
        }
        if (resumed) {
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            listener?.onSessionResumed()
        }
        // Before onSessionReady, so a stale chip is gone by the time the caller starts reseeding
        // this session's context.
        for (id in abandonedSceneCalls) {
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            listener?.onSceneTransitionCancelled(id)
        }
        if (!isActiveSocket(webSocket, generation, attemptId)) return
        listener?.onSessionReady()
    }

    private fun handleServerMessage(
        webSocket: WebSocket,
        text: String,
        generation: Long,
        attemptId: Long,
        model: String,
    ) {
        // Candidate fallbacks share one connect generation. Socket identity plus attempt id is
        // therefore required to reject late frames from a model that was already detached.
        if (!isActiveSocket(webSocket, generation, attemptId)) return
        try {
            val message = JSONObject(text)
            message.optJSONObject("error")?.let {
                val reason = redactReason(it.optString("message", it.toString()))
                // A provider error is terminal for the active socket. Before setup this advances
                // the model candidates; after setup it enters VoiceManager's reconnect path.
                handleSocketDown(
                    webSocket,
                    "Gemini Live server error: $reason",
                    generation,
                    attemptId,
                )
                webSocket.cancel()
                return
            }
            message.optJSONObject("usageMetadata")
                ?.let { metadata -> parseGeminiLiveUsage(metadata, model) }
                ?.let { usage -> synchronized(stateLock) { pendingTurnUsage = usage } }
            if (message.has("setupComplete")) {
                markSetupComplete(webSocket, generation, attemptId, model)
            }
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            message.optJSONObject("serverContent")?.let(::handleServerContent)
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            message.optJSONObject("sessionResumptionUpdate")?.let { update ->
                val newHandle = update.optString("newHandle").takeIf { it.isNotEmpty() }
                val resumable = update.optBoolean("resumable", false)
                synchronized(stateLock) {
                    if (resumable && newHandle != null) {
                        resumptionHandle = newHandle
                    } else if (!resumable) {
                        // The server is telling us the current handle chain can no longer be
                        // resumed (e.g. it aged out) — stop offering it on the next reconnect
                        // so that attempt starts fresh instead of being rejected again.
                        resumptionHandle = null
                    }
                }
            }
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            if (sceneTransitionsEnabled) {
                handleSceneTransitionFrames(webSocket, message, generation, attemptId)
            }
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            message.optJSONObject("goAway")?.let {
                listener?.onStatus(
                    "Gemini session will close soon${
                        it.optString("timeLeft").takeIf(String::isNotBlank)?.let { value -> ": $value" } ?: ""
                    }",
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            if (isActiveSocket(webSocket, generation, attemptId)) {
                listener?.onError("Gemini Live sent an unreadable voice event. Please try again.")
            }
        }
    }

    /**
     * Survival "Advanced Beta": turn `toolCall` / `toolCallCancellation` frames into listener
     * events. A call the app cannot act on (unknown function, unknown transition type, or no
     * listener attached) is answered with a decline immediately rather than left dangling — the
     * model blocks on a function response, so silently ignoring one would freeze the conversation.
     */
    private fun handleSceneTransitionFrames(
        webSocket: WebSocket,
        message: JSONObject,
        generation: Long,
        attemptId: Long,
    ) {
        val rawToolCall = message.optJSONObject("toolCall")
        if (rawToolCall != null) {
            val proposals = SceneTransitionProtocol.parseToolCall(message)
            val recognizedIds = proposals.map { it.id }.toSet()
            synchronized(stateLock) {
                proposals.forEach { pendingSceneTransitionCalls[it.id] = it.name }
            }
            // Unrecognized calls in the same frame still need an answer.
            rawToolCall.optJSONArray("functionCalls")?.let { calls ->
                for (index in 0 until calls.length()) {
                    val call = calls.optJSONObject(index) ?: continue
                    val id = call.optString("id").trim()
                    if (id.isEmpty() || id in recognizedIds) continue
                    val name = call.optString("name").trim()
                    sendOrQueue(
                        SceneTransitionProtocol.buildToolResponse(
                            id,
                            name,
                            SceneTransitionOutcome.EXPIRED_UNANSWERED,
                            nonBlockingToolsDeclared,
                        ),
                        sceneTransitionId = id,
                    )
                }
            }
            for (proposal in proposals) {
                if (!isActiveSocket(webSocket, generation, attemptId)) return
                val target = listener
                if (target == null) {
                    runBlockingDecline(proposal.id)
                } else {
                    target.onSceneTransitionProposed(proposal)
                }
            }
        }
        val cancelled = SceneTransitionProtocol.parseToolCallCancellation(message)
        if (cancelled.isEmpty()) return
        synchronized(stateLock) { cancelled.forEach(pendingSceneTransitionCalls::remove) }
        for (id in cancelled) {
            if (!isActiveSocket(webSocket, generation, attemptId)) return
            listener?.onSceneTransitionCancelled(id)
        }
    }

    /** Decline path used when there is no listener to route a proposal to. */
    private fun runBlockingDecline(id: String) {
        val name = synchronized(stateLock) { pendingSceneTransitionCalls.remove(id) }
            ?: SceneTransitionProtocol.TOOL_NAME
        sendOrQueue(
            SceneTransitionProtocol.buildToolResponse(
                id,
                name,
                // Nobody rejected this — there was no one to show it to.
                SceneTransitionOutcome.EXPIRED_UNANSWERED,
                nonBlockingToolsDeclared,
            ),
            sceneTransitionId = id,
        )
    }

    private fun isActiveSocket(
        socket: WebSocket,
        generation: Long,
        attemptId: Long,
    ): Boolean = synchronized(stateLock) {
        !closed && generation == connectGeneration && activeAttemptId == attemptId &&
            webSocket === socket
    }

    private fun handleServerContent(content: JSONObject) {
        val interrupted = content.optBoolean("interrupted", false)

        synchronized(stateLock) {
            content.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.let(userTranscript::append)
            // An interruption event can carry the last output-transcription fragment. Keep it:
            // the learner may have started speaking only after local playback finished, while
            // Gemini was still finalising the preceding response server-side.
            content.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.let(assistantTranscript::append)
        }

        val modelTurn = content.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!interrupted) handleModelPart(part)
            }
        }

        if (interrupted) {
            // Stop queued audio immediately, but retain transcript buffers until turnComplete.
            // Gemini documents outputTranscription as independently delivered with no ordering
            // guarantee, while an interrupted turn still proceeds to turnComplete. Taking or
            // clearing here either loses the spoken text or splits late fragments into two turns.
            listener?.onAudioInterrupted()
            listener?.onStatus("Gemini response interrupted")
        }

        if (content.optBoolean("turnComplete", false)) {
            completeTurn()
        }
    }

    private fun handleModelPart(part: JSONObject) {
        part.optJSONObject("inlineData")?.let { inline ->
            val mimeType = inline.optString("mimeType")
            val encoded = inline.optString("data")
            if (encoded.isNotEmpty() && (mimeType.isEmpty() || mimeType.startsWith("audio/"))) {
                // Gemini native-audio output is already 24 kHz PCM16.
                listener?.onAudioReceived(Base64.decode(encoded, Base64.DEFAULT))
            }
        }
        part.optString("text").takeIf { it.isNotEmpty() }?.let { text ->
            synchronized(stateLock) { fallbackAssistantText.append(text) }
        }
    }

    private fun completeTurn() {
        val (completed, usage) = synchronized(stateLock) {
            val user = userTranscript.take()
            val transcribedAssistant = assistantTranscript.take()
            val fallback = fallbackAssistantText.take()
            val finalUsage = pendingTurnUsage
            pendingTurnUsage = null
            (user to transcribedAssistant.ifEmpty { fallback }) to finalUsage
        }
        dispatchGeminiCompletedTurn(listener, completed.first, completed.second, usage)
    }

    private fun clearTurnBuffersLocked() {
        userTranscript.clear()
        assistantTranscript.clear()
        fallbackAssistantText.clear()
        pendingTurnUsage = null
    }

    /** Never let a provider/OkHttp error echo the API key back into the on-screen status. */
    private fun redactReason(reason: String): String {
        val withoutExactKey = if (apiKey.isNotEmpty()) reason.replace(apiKey, "[redacted]") else reason
        return withoutExactKey.replace(KEY_QUERY_PARAM_RE, "key=[redacted]").take(500)
    }

    private companion object {
        const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val SOCKET_UPGRADE_TIMEOUT_MILLIS = 10_000L
        // Compiled once; redactReason runs on every socket error and status message.
        val KEY_QUERY_PARAM_RE = Regex("key=[^&\\s]+")
        const val GEMINI_INPUT_SAMPLE_RATE = 16_000
        const val MAX_QUEUED_AUDIO_BYTES = GEMINI_INPUT_SAMPLE_RATE * 2 * 10
        // Per-candidate wait for setupComplete. All candidates at this bound stay inside
        // VoiceManager.RECONNECT_READY_TIMEOUT_MILLIS so one reconnect attempt can sweep the
        // whole chain.
        const val SETUP_TIMEOUT_MILLIS = 8_000L

        // Current Live model chain, tried in order after the user's selected model. Do not use
        // "native-audio-latest" (not a documented model id) or gemini-2.0-flash-live-001 (shut
        // down in December 2025). Keep the first entry aligned with DEFAULT_GEMINI_LIVE_MODEL.
        val CANDIDATE_LIVE_MODELS = listOf(
            DEFAULT_GEMINI_LIVE_MODEL,
            "gemini-2.5-flash-native-audio-preview-12-2025",
        )
    }
}

/**
 * Dispatches the real-time audio boundary before slower transcript consumers. Kept Android-free
 * and internal so the ordering contract has a focused regression test.
 */
internal fun dispatchGeminiCompletedTurn(
    listener: VoiceClientListener?,
    userTranscript: String,
    assistantTranscript: String,
    usage: VoiceApiUsage?,
) {
    // This callback controls the echo guard and the learner's "Your turn" state. MainViewModel
    // may normalize, score checklist coverage, and persist during the transcript callbacks.
    listener?.onTurnComplete()
    if (userTranscript.isNotEmpty()) listener?.onTranscript("user", userTranscript, true)
    if (assistantTranscript.isNotEmpty()) {
        listener?.onTranscript("patient", assistantTranscript, true)
    }
    usage?.let { listener?.onUsage(it) }
}

/**
 * Convert Gemini's provider-reported, per-response usage into billable modality buckets.
 * Prompt counts intentionally include reprocessed Live context; summing these records therefore
 * preserves Gemini Live's compounding billing model.
 */
internal fun parseGeminiLiveUsage(metadata: JSONObject, model: String): VoiceApiUsage {
    fun modalityCounts(arrayName: String): Pair<Int, Int> {
        val details = metadata.optJSONArray(arrayName) ?: return 0 to 0
        var text = 0
        var audio = 0
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val count = detail.optInt("tokenCount", 0).coerceAtLeast(0)
            when (detail.optString("modality").uppercase()) {
                "AUDIO" -> audio += count
                "TEXT" -> text += count
            }
        }
        return text to audio
    }

    val prompt = modalityCounts("promptTokensDetails")
    val response = modalityCounts("responseTokensDetails")
    val cached = modalityCounts("cacheTokensDetails")
    // Tool-use prompt tokens are billed at the ordinary input rates but are reported *separately*
    // from promptTokenCount, so reading only the prompt buckets dropped them entirely. This app
    // declares functionDeclarations for Survival scene transitions (see buildSetupMessage), so
    // these are real charges on any session with the beta enabled.
    val toolUse = modalityCounts("toolUsePromptTokensDetails")
    val promptTotal = metadata.optInt("promptTokenCount", prompt.first + prompt.second)
        .coerceAtLeast(0)
    val responseTotal = metadata.optInt(
        "responseTokenCount",
        metadata.optInt("candidatesTokenCount", response.first + response.second),
    ).coerceAtLeast(0)
    val cachedTotal = metadata.optInt("cachedContentTokenCount", cached.first + cached.second)
        .coerceAtLeast(0)
    val toolUseTotal = metadata.optInt("toolUsePromptTokenCount", toolUse.first + toolUse.second)
        .coerceAtLeast(0)

    // Old/partial server schemas may omit modality details. Preserve the provider total in the
    // text bucket instead of silently dropping billable tokens; current Live responses provide
    // complete modality details, so audio is normally classified precisely.
    val inputUnclassified = (promptTotal - prompt.first - prompt.second).coerceAtLeast(0)
    val outputUnclassified = (responseTotal - response.first - response.second).coerceAtLeast(0)
    val cachedUnclassified = (cachedTotal - cached.first - cached.second).coerceAtLeast(0)
    val toolUseUnclassified = (toolUseTotal - toolUse.first - toolUse.second).coerceAtLeast(0)

    // thoughtsTokenCount is documented as a sibling of the response count, not a subset of it, so
    // it is billed on top at the text-output rate. Guard the case where a server build folds it
    // into responseTokenCount anyway: totalTokenCount, when present, is the authority on what the
    // turn actually cost, and charging thinking twice would silently inflate every session.
    val rawThinking = metadata.optInt("thoughtsTokenCount", 0).coerceAtLeast(0)
    val totalTokenCount = metadata.optInt("totalTokenCount", 0).coerceAtLeast(0)
    val thinking = if (totalTokenCount > 0) {
        val accountedWithoutThinking = promptTotal + responseTotal + toolUseTotal
        rawThinking.coerceAtMost((totalTokenCount - accountedWithoutThinking).coerceAtLeast(0))
    } else {
        rawThinking
    }

    return VoiceApiUsage(
        provider = "gemini",
        model = model,
        // Tool-use prompt tokens fold into the ordinary input buckets by modality: they bill at
        // the same rates, and keeping them separate would need a schema/DB migration to persist.
        inputTextTokens = prompt.first + inputUnclassified + toolUse.first + toolUseUnclassified,
        inputAudioTokens = prompt.second + toolUse.second,
        outputTextTokens = response.first + outputUnclassified,
        outputAudioTokens = response.second,
        cachedInputTextTokens = cached.first + cachedUnclassified,
        cachedInputAudioTokens = cached.second,
        thinkingTokens = thinking,
    )
}
