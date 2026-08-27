package com.example.medvoicetrainer.voice

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class OpenAIRealtimeClient(private val apiKey: String) : VoiceClient {
    private data class QueuedFrames(
        val frames: List<String>,
        val audioBytes: Int = 0,
    )

    private val client = OkHttpClient()
    private val stateLock = Any()
    private val pendingFrames = mutableListOf<QueuedFrames>()
    private val assistantBuffers = linkedMapOf<String, TurnTranscriptBuffer>()
    private val userBuffers = linkedMapOf<String, TurnTranscriptBuffer>()
    private val completedAssistantKeys = linkedSetOf<String>()
    private val completedUserKeys = linkedSetOf<String>()

    private var listener: VoiceClientListener? = null
    private var webSocket: WebSocket? = null
    private var socketOpen = false
    private var sessionReady = false
    private var queuedAudioBytes = 0
    private var responseActive = false
    private var discardCurrentOutput = false
    private var turnCompleteEmitted = false
    private var selectedModel = DEFAULT_OPENAI_REALTIME_MODEL
    // The voice name (see VoiceCatalog) chosen once for this session by the caller and resent
    // unchanged on every connect() — including a mid-session reconnect — so the character's voice
    // never changes mid-conversation.
    private var selectedVoice = ""

    // Server-side audio output speed (app/voice/openai_client.py's provider_speed). The official
    // `audio.output.speed` field only takes effect between model turns, so a request made while a
    // response is active is parked in [pendingOutputSpeed] and flushed on response.done / when the
    // session first becomes ready. [currentOutputSpeed] is what the initial session.update declares.
    private var currentOutputSpeed = 1.0
    private var pendingOutputSpeed: Double? = null

    override fun setListener(listener: VoiceClientListener) {
        this.listener = listener
    }

    override suspend fun connect(systemPrompt: String, model: String, voice: String) {
        selectedModel = normalizeOpenAIRealtimeModel(model)
        selectedVoice = voice
        synchronized(stateLock) {
            socketOpen = false
            sessionReady = false
            queuedAudioBytes = 0
            pendingFrames.clear()
            clearTurnStateLocked()
        }

        val encodedModel = URLEncoder.encode(selectedModel, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$encodedModel")
            .addHeader("Authorization", "Bearer $apiKey")
            // Still accepted by the current API and required by older preview aliases.
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(stateLock) {
                    if (this@OpenAIRealtimeClient.webSocket !== webSocket) return
                    socketOpen = true
                }
                listener?.onStatus("OpenAI socket connected; configuring session")
                if (!webSocket.send(buildSessionUpdate(systemPrompt, selectedModel, selectedVoice))) {
                    listener?.onError("OpenAI session setup could not be sent")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@OpenAIRealtimeClient.webSocket === webSocket) {
                    handleMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // OkHttp never delivers onClosed for a server-initiated close unless we
                // acknowledge it with our own close frame — without this the session would
                // hang open forever with no callback firing (see GeminiLiveClient).
                runCatching { webSocket.close(1000, null) }
                handleSocketDown(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleSocketDown(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleSocketDown(webSocket)
            }
        })
    }

    /**
     * A socket died. Not-active means close()/a newer connect() already replaced this socket —
     * a stale callback, never a reconnect trigger. Detaching on first call keeps the several
     * callbacks a dying socket fires (onClosing then onClosed) from triggering onConnectionLost
     * twice. See GeminiLiveClient's identical pattern for the full rationale.
     */
    private fun handleSocketDown(webSocket: WebSocket) {
        val wasActive = synchronized(stateLock) {
            val active = this@OpenAIRealtimeClient.webSocket === webSocket
            if (active) {
                this@OpenAIRealtimeClient.webSocket = null
                socketOpen = false
                sessionReady = false
            }
            active
        }
        if (wasActive) {
            listener?.onConnectionLost()
        }
    }

    override suspend fun sendKickoffText(text: String) {
        if (text.isBlank()) return
        sendOrQueue(textTurnFrames(text))
    }

    override suspend fun requestOutputSpeed(speed: Double) {
        // Park the request; apply now only if no model turn is mid-flight (the field is a
        // turn-boundary control server-side). Otherwise handleResponseDone flushes it.
        val applyNow = synchronized(stateLock) {
            pendingOutputSpeed = clampOutputSpeed(speed)
            !responseActive
        }
        if (applyNow) applyPendingOutputSpeed()
    }

    private fun applyPendingOutputSpeed() {
        var frame: String? = null
        var socket: WebSocket? = null
        val speed = synchronized(stateLock) {
            val pending = pendingOutputSpeed ?: return
            if (!socketOpen || !sessionReady || webSocket == null) return
            currentOutputSpeed = pending
            pendingOutputSpeed = null
            socket = webSocket
            frame = JSONObject().apply {
                put("type", "session.update")
                put("session", JSONObject().apply {
                    put("type", "realtime")
                    put("audio", JSONObject().put("output", JSONObject().put("speed", pending)))
                })
            }.toString()
            pending
        }
        val sent = frame?.let { socket?.send(it) } ?: false
        if (sent) {
            listener?.onStatus("OpenAI speed ${String.format(java.util.Locale.US, "%.2f", speed)}× applies from the next turn")
        }
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray) {
        if (pcmData.size < 2) return
        val audio24k = resample16kTo24k(pcmData)
        val payload = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.encodeToString(audio24k, Base64.NO_WRAP))
        }.toString()
        sendOrQueue(listOf(payload), audioBytes = audio24k.size)
    }

    override suspend fun sendText(text: String) {
        if (text.isBlank()) return
        sendOrQueue(textTurnFrames(text))
    }

    override suspend fun finishUserTurn(): Boolean {
        // A manual commit is valid even while server_vad is configured. Commit creates the user
        // audio item/transcription; response.create is separate and is what asks the model to
        // answer now instead of waiting for another VAD boundary.
        sendOrQueue(
            listOf(
                JSONObject().put("type", "input_audio_buffer.commit").toString(),
                JSONObject().put("type", "response.create").toString(),
            )
        )
        return true
    }

    override suspend fun seedHistory(turns: List<Pair<String, String>>) {
        val frames = turns.filter { it.second.isNotBlank() }.map { (role, text) ->
            val userRole = role == "user" || role == "doctor" ||
                role == SceneTransitionProtocol.NARRATOR_ROLE
            JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", if (userRole) "user" else "assistant")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", if (userRole) "input_text" else "output_text")
                        put("text", text)
                    }))
                })
            }.toString()
        }
        if (frames.isNotEmpty()) sendOrQueue(frames)
    }

    override suspend fun close() {
        val socket = synchronized(stateLock) {
            socketOpen = false
            sessionReady = false
            queuedAudioBytes = 0
            pendingFrames.clear()
            clearTurnStateLocked()
            webSocket.also { webSocket = null }
        }
        socket?.close(1000, "User requested close")
    }

    private fun buildSessionUpdate(systemPrompt: String, model: String, voice: String): String =
        JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "realtime")
                put("model", model)
                put("output_modalities", JSONArray().put("audio"))
                put("instructions", systemPrompt)
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            put("rate", OPENAI_SAMPLE_RATE)
                        })
                        put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 500)
                        })
                    })
                    put("output", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            put("rate", OPENAI_SAMPLE_RATE)
                        })
                        put("voice", voice.ifBlank { "marin" })
                        // Server-side pitch-preserving playback speed (app/voice/openai_client.py
                        // build_session_config): declare the current provider speed up front so a
                        // speed already dialled in before the socket opened is honored on connect.
                        put("speed", clampOutputSpeed(synchronized(stateLock) { currentOutputSpeed }))
                    })
                })
            })
        }.toString()

    private fun textTurnFrames(text: String): List<String> {
        val item = JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", text)
                }))
            })
        }.toString()
        val response = JSONObject().put("type", "response.create").toString()
        return listOf(item, response)
    }

    private fun sendOrQueue(frames: List<String>, audioBytes: Int = 0) {
        var sendFailed = false
        synchronized(stateLock) {
            val socket = webSocket
            if (sessionReady && socketOpen && socket != null) {
                frames.forEach { if (!socket.send(it)) sendFailed = true }
            } else {
                enqueueLocked(QueuedFrames(frames, audioBytes))
            }
        }
        if (sendFailed) listener?.onError("OpenAI WebSocket rejected an outgoing message")
    }

    private fun enqueueLocked(item: QueuedFrames) {
        if (item.audioBytes > 0) {
            while (queuedAudioBytes + item.audioBytes > MAX_QUEUED_AUDIO_BYTES) {
                val index = pendingFrames.indexOfFirst { it.audioBytes > 0 }
                if (index < 0) break
                queuedAudioBytes -= pendingFrames.removeAt(index).audioBytes
            }
            if (item.audioBytes > MAX_QUEUED_AUDIO_BYTES) return
            queuedAudioBytes += item.audioBytes
        }
        pendingFrames += item
    }

    private fun markSessionReady(webSocket: WebSocket) {
        var sendFailed = false
        synchronized(stateLock) {
            if (this.webSocket !== webSocket) return
            sessionReady = true
            pendingFrames.forEach { queued ->
                queued.frames.forEach { if (!webSocket.send(it)) sendFailed = true }
            }
            pendingFrames.clear()
            queuedAudioBytes = 0
        }
        listener?.onStatus("Connected to OpenAI Realtime ($selectedModel)")
        if (sendFailed) listener?.onError("OpenAI rejected queued input after session setup")
        // A speed dialled in before the session was ready (or one that couldn't be sent mid-turn)
        // is flushed now that the socket is live and no response is active yet.
        applyPendingOutputSpeed()
        listener?.onSessionReady()
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        try {
            val event = JSONObject(text)
            when (val type = event.optString("type")) {
                "session.updated" -> markSessionReady(webSocket)
                "session.created" -> listener?.onStatus("OpenAI session created; applying configuration")
                "response.created" -> synchronized(stateLock) {
                    responseActive = true
                    discardCurrentOutput = false
                    turnCompleteEmitted = false
                    assistantBuffers.clear()
                    completedAssistantKeys.clear()
                }

                "response.output_audio_transcript.delta",
                "response.audio_transcript.delta" -> appendAssistantTranscript(event)

                "response.output_audio_transcript.done",
                "response.audio_transcript.done" -> completeAssistantTranscript(event)

                "conversation.item.input_audio_transcription.delta" -> appendUserTranscript(event)
                "conversation.item.input_audio_transcription.completed" -> completeUserTranscript(event)
                "conversation.item.input_audio_transcription.failed" -> synchronized(stateLock) {
                    userBuffers.remove(turnKey(event))
                }

                "response.output_audio.delta",
                "response.audio.delta" -> handleAudioDelta(event)

                "response.output_audio.done",
                "response.audio.done" -> emitTurnCompleteOnce()

                "input_audio_buffer.speech_started" -> handleInterruption()
                "response.cancelled" -> handleCancelledResponse()
                "response.done" -> handleResponseDone(event)
                "error" -> {
                    val error = event.optJSONObject("error")
                    val rawMessage = error?.optString("message").orEmpty().ifBlank { text }
                    listener?.onError(
                        com.example.medvoicetrainer.api.ApiError
                            .fromProviderMessage("OpenAI", rawMessage).message.orEmpty()
                    )
                }

                else -> if (type.endsWith(".failed")) {
                    val error = event.optJSONObject("error")
                    val rawMessage = error?.optString("message").orEmpty().ifBlank { "OpenAI event failed: $type" }
                    listener?.onError(
                        com.example.medvoicetrainer.api.ApiError
                            .fromProviderMessage("OpenAI", rawMessage).message.orEmpty()
                    )
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            listener?.onError("OpenAI sent an unreadable voice event. Please try again.")
        }
    }

    private fun appendAssistantTranscript(event: JSONObject) {
        synchronized(stateLock) {
            if (discardCurrentOutput) return
            responseActive = true
            assistantBuffers.getOrPut(turnKey(event)) { TurnTranscriptBuffer() }
                .append(event.optString("delta"))
        }
    }

    private fun completeAssistantTranscript(event: JSONObject) {
        val transcript = synchronized(stateLock) {
            val key = turnKey(event)
            if (discardCurrentOutput || !completedAssistantKeys.add(key)) {
                assistantBuffers.remove(key)
                return
            }
            val buffer = assistantBuffers.remove(key) ?: TurnTranscriptBuffer()
            buffer.take(event.optString("transcript"))
        }
        if (transcript.isNotEmpty()) listener?.onTranscript("patient", transcript, true)
    }

    private fun appendUserTranscript(event: JSONObject) {
        synchronized(stateLock) {
            userBuffers.getOrPut(turnKey(event)) { TurnTranscriptBuffer() }
                .append(event.optString("delta"))
        }
    }

    private fun completeUserTranscript(event: JSONObject) {
        val transcript = synchronized(stateLock) {
            val key = turnKey(event)
            if (!completedUserKeys.add(key)) {
                userBuffers.remove(key)
                return
            }
            trimCompletedKeysLocked(completedUserKeys)
            val buffer = userBuffers.remove(key) ?: TurnTranscriptBuffer()
            buffer.take(event.optString("transcript"))
        }
        if (transcript.isNotEmpty()) listener?.onTranscript("user", transcript, true)
    }

    private fun handleAudioDelta(event: JSONObject) {
        val discard = synchronized(stateLock) {
            responseActive = true
            discardCurrentOutput
        }
        if (discard) return
        val encoded = event.optString("delta")
        if (encoded.isNotEmpty()) {
            // Realtime PCM output is already 24 kHz; AudioIO is configured for the same rate.
            listener?.onAudioReceived(Base64.decode(encoded, Base64.DEFAULT))
        }
    }

    private fun handleInterruption() {
        val interrupted = synchronized(stateLock) {
            if (!responseActive) return
            discardCurrentOutput = true
            assistantBuffers.clear()
            true
        }
        if (interrupted) {
            listener?.onAudioInterrupted()
            listener?.onStatus("OpenAI response interrupted")
        }
    }

    private fun handleCancelledResponse() {
        synchronized(stateLock) {
            discardCurrentOutput = true
            assistantBuffers.clear()
            responseActive = false
        }
        listener?.onAudioInterrupted()
    }

    private fun handleResponseDone(event: JSONObject) {
        parseOpenAIRealtimeUsage(event, selectedModel)
            ?.let { usage -> listener?.onUsage(usage) }
        val buffered = synchronized(stateLock) {
            val result = if (discardCurrentOutput) {
                emptyList()
            } else {
                assistantBuffers.values.map { it.take() }.filter { it.isNotEmpty() }
            }
            assistantBuffers.clear()
            responseActive = false
            discardCurrentOutput = false
            result
        }
        buffered.forEach { listener?.onTranscript("patient", it, true) }
        emitTurnCompleteOnce()
        // The turn is over — a legal boundary for the official output-speed field. Ports
        // openai_client.py's `await self._apply_pending_output_speed()` on response.done.
        applyPendingOutputSpeed()
    }

    private fun emitTurnCompleteOnce() {
        val shouldEmit = synchronized(stateLock) {
            if (turnCompleteEmitted) false else {
                turnCompleteEmitted = true
                true
            }
        }
        if (shouldEmit) listener?.onTurnComplete()
    }

    // Mirror openai_client.py's _clamp_output_speed: the server field accepts 0.25×–1.5× only.
    private fun clampOutputSpeed(speed: Double): Double {
        val v = if (speed.isNaN()) 1.0 else speed
        return minOf(1.5, maxOf(0.25, v))
    }

    private fun turnKey(event: JSONObject): String {
        val itemId = event.optString("item_id")
        if (itemId.isNotEmpty()) return itemId
        val responseId = event.optString("response_id")
        if (responseId.isNotEmpty()) return responseId
        return "current"
    }

    private fun clearTurnStateLocked() {
        assistantBuffers.clear()
        userBuffers.clear()
        completedAssistantKeys.clear()
        completedUserKeys.clear()
        responseActive = false
        discardCurrentOutput = false
        turnCompleteEmitted = false
    }

    private fun trimCompletedKeysLocked(keys: LinkedHashSet<String>) {
        while (keys.size > MAX_COMPLETED_TRANSCRIPT_IDS) {
            keys.remove(keys.first())
        }
    }

    private fun resample16kTo24k(pcm16Data: ByteArray): ByteArray {
        val inputSamples = pcm16Data.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = (inputSamples * 1.5).toInt()
        val output = ByteBuffer.allocate(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(pcm16Data).order(ByteOrder.LITTLE_ENDIAN)

        for (index in 0 until outputSamples) {
            val source = index / 1.5
            val lower = source.toInt().coerceAtMost(inputSamples - 1)
            val upper = minOf(lower + 1, inputSamples - 1)
            val fraction = source - lower
            val lowSample = input.getShort(lower * 2).toFloat()
            val highSample = input.getShort(upper * 2).toFloat()
            output.putShort((lowSample + fraction * (highSample - lowSample)).toInt().toShort())
        }
        return output.array()
    }

    private companion object {
        const val OPENAI_SAMPLE_RATE = 24_000
        const val MAX_QUEUED_AUDIO_BYTES = OPENAI_SAMPLE_RATE * 2 * 10
        const val MAX_COMPLETED_TRANSCRIPT_IDS = 128
    }
}

/** Parse exact token modality details included in OpenAI's `response.done` event. */
internal fun parseOpenAIRealtimeUsage(event: JSONObject, model: String): VoiceApiUsage? {
    val usage = event.optJSONObject("response")?.optJSONObject("usage")
        ?: event.optJSONObject("usage")
        ?: return null
    val inputDetails = usage.optJSONObject("input_token_details")
        ?: usage.optJSONObject("inputTokenDetails")
        ?: JSONObject()
    val outputDetails = usage.optJSONObject("output_token_details")
        ?: usage.optJSONObject("outputTokenDetails")
        ?: JSONObject()
    val inputTotal = usage.optInt("input_tokens", usage.optInt("inputTokens", 0)).coerceAtLeast(0)
    val outputTotal = usage.optInt("output_tokens", usage.optInt("outputTokens", 0)).coerceAtLeast(0)
    val inputAudio = inputDetails.optInt("audio_tokens", inputDetails.optInt("audioTokens", 0))
        .coerceAtLeast(0)
    val explicitInputText = inputDetails.optInt("text_tokens", inputDetails.optInt("textTokens", -1))
    val inputText = if (explicitInputText >= 0) explicitInputText else (inputTotal - inputAudio)
    val outputAudio = outputDetails.optInt("audio_tokens", outputDetails.optInt("audioTokens", 0))
        .coerceAtLeast(0)
    val explicitOutputText = outputDetails.optInt("text_tokens", outputDetails.optInt("textTokens", -1))
    val outputText = if (explicitOutputText >= 0) explicitOutputText else (outputTotal - outputAudio)
    val cachedTotal = inputDetails.optInt("cached_tokens", inputDetails.optInt("cachedTokens", 0))
        .coerceAtLeast(0)
    val cachedDetails = inputDetails.optJSONObject("cached_tokens_details")
        ?: inputDetails.optJSONObject("cachedTokensDetails")
        ?: JSONObject()
    val cachedAudio = cachedDetails.optInt(
        "audio_tokens",
        cachedDetails.optInt("audioTokens", 0),
    ).coerceAtLeast(0)
    val cachedText = (cachedTotal - cachedAudio).coerceAtLeast(0)
    val reasoning = outputDetails.optInt(
        "reasoning_tokens",
        outputDetails.optInt("reasoningTokens", 0),
    ).coerceAtLeast(0)
    return VoiceApiUsage(
        provider = "openai",
        model = model,
        inputTextTokens = inputText.coerceAtLeast(0),
        inputAudioTokens = inputAudio,
        outputTextTokens = (outputText - reasoning).coerceAtLeast(0),
        outputAudioTokens = outputAudio,
        cachedInputTextTokens = cachedText,
        cachedInputAudioTokens = cachedAudio,
        thinkingTokens = reasoning,
    )
}
