package com.example.medvoicetrainer.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal const val DEFAULT_GEMINI_ANALYSIS_MODEL = "gemini-3.5-flash"
private const val NETWORK_CONNECT_TIMEOUT_MS = 15_000
private const val NETWORK_READ_TIMEOUT_MS = 60_000
private const val AUDIO_READ_TIMEOUT_MS = 120_000
private const val AUDIO_MIN_READ_TIMEOUT_MS = 5_000

private val RETIRED_GEMINI_ANALYSIS_MODELS = setOf(
    "gemini-2.5-flash",
    "gemini-2.0-flash",
    "gemini-2.0-flash-001",
    "gemini-2.0-flash-exp",
    "gemini-2.0-flash-lite"
)

internal fun normalizeGeminiAnalysisModel(model: String): String {
    val normalized = model.trim().removePrefix("models/")
    return if (normalized.isEmpty() || normalized in RETIRED_GEMINI_ANALYSIS_MODELS) {
        DEFAULT_GEMINI_ANALYSIS_MODEL
    } else {
        normalized
    }
}

/**
 * Gemini's `generateContent` inline audio only decodes container formats (WAV, MP3, FLAC, …).
 * It does NOT decode the raw `audio/pcm;rate=N` blobs that the Live/streaming API accepts —
 * that MIME type is only valid on the WebSocket Live API. Sending raw PCM to generateContent
 * makes the model "hear" silence, so pronunciation judges wrongly report "no clear attempt was
 * heard" on a perfectly good recording. Detect a raw-PCM MIME type, wrap the bytes in a
 * canonical 44-byte WAV header (mono, 16-bit), and relabel it `audio/wav`. Anything already in a
 * container format is passed through untouched.
 */
internal fun containerizeInlineAudio(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
    val mime = mimeType.trim().lowercase()
    val isRawPcm = mime.startsWith("audio/pcm") ||
        mime.startsWith("audio/l16") ||
        mime.startsWith("audio/x-raw")
    if (!isRawPcm) return bytes to mimeType
    val sampleRate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 16000
    return pcm16ToWavBytes(bytes, sampleRate) to "audio/wav"
}

/** Wrap raw PCM16 mono in an in-memory WAV container (44-byte canonical header). */
private fun pcm16ToWavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
    val rate = if (sampleRate > 0) sampleRate else 16000
    val byteRate = rate * 1 * 16 / 8
    val blockAlign = 1 * 16 / 8
    val dataSize = pcm.size
    val out = java.io.ByteArrayOutputStream(44 + dataSize)

    fun writeLeInt(v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }
    fun writeLeShort(v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }

    out.write("RIFF".toByteArray())
    writeLeInt(36 + dataSize)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    writeLeInt(16) // PCM header size
    writeLeShort(1) // PCM format
    writeLeShort(1) // mono
    writeLeInt(rate)
    writeLeInt(byteRate)
    writeLeShort(blockAlign)
    writeLeShort(16) // bits per sample
    out.write("data".toByteArray())
    writeLeInt(dataSize)
    out.write(pcm)

    return out.toByteArray()
}

internal fun isGeminiAnalysisFallbackError(message: String?): Boolean {
    val msg = message.orEmpty().lowercase()
    return "http 404" in msg ||
        "not_found" in msg ||
        "not found" in msg ||
        "no longer available" in msg ||
        "429" in msg ||
        "resourceexhausted" in msg ||
        "quota" in msg
}

internal fun isGeminiAnalysisFallbackError(error: Throwable): Boolean =
    (error as? ApiRequestException)?.kind in setOf(ApiFailureKind.MODEL_UNAVAILABLE, ApiFailureKind.QUOTA) ||
        isGeminiAnalysisFallbackError(error.message)

/**
 * True only for quota / rate-limit exhaustion (HTTP 429 / RESOURCE_EXHAUSTED), the free-tier
 * "N requests per day per model" case. Narrower than [isGeminiAnalysisFallbackError] (which also
 * covers a retired/404 model) so the UI can show a specific "daily free limit reached" message
 * instead of a generic failure or a raw error blob.
 */
internal fun isGeminiQuotaError(message: String?): Boolean {
    val msg = message.orEmpty().lowercase()
    return "429" in msg ||
        "resource_exhausted" in msg ||
        "resourceexhausted" in msg ||
        "quota" in msg ||
        "rate limit" in msg ||
        "rate-limit" in msg
}

internal fun isGeminiQuotaError(error: Throwable): Boolean =
    ApiError.isQuota(error) || isGeminiQuotaError(error.message)

/**
 * Every currently-available (non-retired) Gemini model this app falls through to for text and
 * audio-grounded analysis, ordered from "primary" quality down to "most abundant free-tier
 * quota" as a last resort. The free tier tracks requests-per-day PER MODEL, not per project or
 * per API key, so each entry here is its own separate daily allowance — falling through this
 * list on a 429 genuinely buys a fresh bucket of requests rather than retrying an already-
 * exhausted one. Deliberately spans every model family/tier the free tier still offers (3.6 and
 * 3.5 Flash, 3.1 and 2.5 Pro, 3.1/3.5/2.5 Flash-Lite) to maximize total daily headroom.
 * gemini-2.5-flash is deliberately excluded — see RETIRED_GEMINI_ANALYSIS_MODELS — but its
 * Flash-Lite and Pro siblings are still current, so they're included instead.
 */
internal val GEMINI_FREE_TIER_MODEL_CHAIN = listOf(
    DEFAULT_GEMINI_ANALYSIS_MODEL, // gemini-3.5-flash
    "gemini-3.6-flash",
    "gemini-3.1-pro",
    "gemini-2.5-pro",
    "gemini-3.1-flash-lite",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash-lite"
)

/**
 * Same chain, reused for the inline-audio judges (pronunciation, spoken-review, per-turn
 * correction) — every entry above is multimodal and accepts audio input, so no separate list is
 * needed.
 */
internal val GEMINI_AUDIO_ANALYSIS_FALLBACKS = GEMINI_FREE_TIER_MODEL_CHAIN

/**
 * Fallback chain for low-stakes calls that must never compete with a session's final analysis for
 * quota — currently ShadowingEngine's once-per-session pass. Leads with the highest-RPD Flash-Lite
 * models and only reaches for a full Flash model as a last resort; deliberately excludes the Pro
 * tier so a routine check never spends the day's scarce Pro quota.
 *
 * The per-turn checklist scoring this chain was originally sized for (10-30+ calls a session) no
 * longer makes any model call: see CoverageEngine, which is now deterministic and local.
 */
internal val GEMINI_COVERAGE_MODEL_CHAIN = listOf(
    "gemini-3.1-flash-lite",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-3.6-flash",
    DEFAULT_GEMINI_ANALYSIS_MODEL
)

/**
 * Run [block] against [startModel], then fall through [fallbackChain] on a missing-model /
 * quota error (see [isGeminiAnalysisFallbackError]). A non-fallback error (bad request, network,
 * parse) is rethrown immediately; if every model is exhausted the last fallback error is
 * rethrown so the caller can surface it. Distinct-preserves order so a model is never tried
 * twice.
 */
internal suspend fun <T> withGeminiModelFallback(
    startModel: String,
    fallbackChain: List<String>,
    block: suspend (String) -> T
): T {
    val models = (listOf(normalizeGeminiAnalysisModel(startModel)) + fallbackChain).distinct()
    var lastFallbackError: Exception? = null
    for (model in models) {
        try {
            return block(model)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (isGeminiAnalysisFallbackError(e)) {
                lastFallbackError = e
                continue
            }
            throw e
        }
    }
    throw lastFallbackError ?: Exception("No Gemini model in the fallback chain was available.")
}

/**
 * Run [block] against the learner's configured audio model, then fall through
 * [GEMINI_AUDIO_ANALYSIS_FALLBACKS] on missing-model / quota errors. Thin wrapper over
 * [withGeminiModelFallback] kept for the existing audio-judge call sites.
 */
internal suspend fun <T> withGeminiAudioFallback(
    startModel: String,
    block: suspend (String) -> T
): T = withGeminiModelFallback(startModel, GEMINI_AUDIO_ANALYSIS_FALLBACKS, block)

internal data class GeminiFallbackResult<T>(val value: T, val modelUsed: String)

/** Same fallback policy, but preserves the actual model for auditability and trend validity. */
internal suspend fun <T> withGeminiAudioFallbackTracked(
    startModel: String,
    block: suspend (String) -> T
): GeminiFallbackResult<T> = withGeminiModelFallback(
    startModel,
    GEMINI_AUDIO_ANALYSIS_FALLBACKS
) { model ->
    GeminiFallbackResult(block(model), model)
}

/** Text-only counterpart used after a specialist speech engine has produced structured evidence. */
internal suspend fun <T> withGeminiTextFallbackTracked(
    startModel: String,
    block: suspend (String) -> T
): GeminiFallbackResult<T> = withGeminiModelFallback(
    startModel,
    GEMINI_COVERAGE_MODEL_CHAIN
) { model ->
    GeminiFallbackResult(block(model), model)
}

object GeminiService {
    data class InlineAudioPart(
        val bytes: ByteArray,
        val mimeType: String,
        val label: String = ""
    )

    fun getBaseUrl(model: String): String = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    /**
     * Open a JSON POST connection to Gemini with the credential in the `x-goog-api-key` header
     * instead of a `?key=` query parameter.
     *
     * A key in the query string leaks anywhere the request URL gets reproduced: HttpURLConnection
     * exceptions whose message *is* the URL (java.io.FileNotFoundException), crash reports, and the
     * access log of any TLS-inspecting proxy between the learner and Google. That matters here
     * because ErrorDialog.handle() puts a full stack trace behind a "copy the details below and
     * report it" button pointed at a public repo — one URL-bearing exception message on that path
     * and the learner pastes their own key into an issue. Header auth removes the channel outright,
     * and is what ModelsFetcher already uses against this same API; GeminiLiveClient still needs
     * redactReason() only because a WebSocket handshake has nowhere else to put the key.
     */
    private fun openJsonConnection(url: String, apiKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey.trim())
        conn.doOutput = true
        conn.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
        conn.readTimeout = NETWORK_READ_TIMEOUT_MS
        return conn
    }

    /**
     * Normalize a `usageMetadata` block into [LlmUsage].
     *
     * `thoughtsTokenCount` is billed at the output rate but is reported separately from
     * `candidatesTokenCount`, so an output count that omits it under-reports every call to a
     * thinking-enabled model. Each call site here used to inline its own parse and only some of
     * them added it, which is how the main session-analysis path ended up billing no thinking
     * tokens at all while the auxiliary paths billed them correctly.
     */
    // internal, not private, so the cross-language golden harness can pin it:
    // the desktop port of this file (ledger LOG-31) has no other way to prove it
    // counts thinking tokens the same way. Module-visible only; not app API.
    internal fun parseUsageMetadata(usageMeta: JSONObject?, model: String): LlmUsage = LlmUsage(
        inputTokens = usageMeta?.optInt("promptTokenCount", 0) ?: 0,
        outputTokens = (usageMeta?.optInt("candidatesTokenCount", 0) ?: 0) +
            (usageMeta?.optInt("thoughtsTokenCount", 0) ?: 0),
        cachedTokens = usageMeta?.optInt("cachedContentTokenCount", 0) ?: 0,
        modelUsed = model,
    )

    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Gemini API key is empty. Please configure it in Settings.")
        }

        try {
            val conn = openJsonConnection(getBaseUrl(model), apiKey)

            // Build request payload
            val payload = JSONObject()
            
            // Contents
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", prompt)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            payload.put("contents", contentsArray)

            // System Instruction if provided
            if (systemInstruction != null) {
                val systemInstructionObj = JSONObject()
                val systemPartsArray = JSONArray()
                val systemPartObj = JSONObject()
                systemPartObj.put("text", systemInstruction)
                systemPartsArray.put(systemPartObj)
                systemInstructionObj.put("parts", systemPartsArray)
                payload.put("systemInstruction", systemInstructionObj)
            }

            // Generation config (force JSON if we are evaluating or doing structured tasks)
            val generationConfig = JSONObject()
            if (prompt.contains("JSON") || prompt.contains("json")) {
                generationConfig.put("responseMimeType", "application/json")
            }
            payload.put("generationConfig", generationConfig)

            // Write output
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseText)
                responseJson.optJSONObject("usageMetadata")?.let { meta ->
                    val input = meta.optInt("promptTokenCount", 0)
                    val output = meta.optInt("candidatesTokenCount", 0) +
                        meta.optInt("thoughtsTokenCount", 0)
                    val cached = meta.optInt("cachedContentTokenCount", 0)
                    com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                        "gemini", model, "generation", input, output,
                        com.example.medvoicetrainer.analysis.CostTracker.computeAnalysisCost(
                            "gemini", input, output, cached, model
                        ),
                        false,
                    )
                }

                // Extract text from Gemini response structure:
                // candidates[0].content.parts[0].text
                val candidates = responseJson.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).getString("text")
                    }
                }
                throw ApiError.unexpectedResponse("Gemini")
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details available"
                throw ApiError.fromHttpResponse("Gemini", responseCode, errorText)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw e
        }
    }

    /** Same as [generateContent] but also returns normalized token usage for cost tracking. */
    suspend fun generateContentWithUsage(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null
    ): Pair<String, LlmUsage> = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Gemini API key is empty. Please configure it in Settings.")
        }

        val conn = openJsonConnection(getBaseUrl(model), apiKey)

        val payload = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        partsArray.put(partObj)
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        payload.put("contents", contentsArray)

        if (systemInstruction != null) {
            val systemInstructionObj = JSONObject()
            val systemPartsArray = JSONArray()
            val systemPartObj = JSONObject()
            systemPartObj.put("text", systemInstruction)
            systemPartsArray.put(systemPartObj)
            systemInstructionObj.put("parts", systemPartsArray)
            payload.put("systemInstruction", systemInstructionObj)
        }

        val generationConfig = JSONObject()
        if (prompt.contains("JSON") || prompt.contains("json")) {
            generationConfig.put("responseMimeType", "application/json")
        }
        payload.put("generationConfig", generationConfig)

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)

            val usage = parseUsageMetadata(responseJson.optJSONObject("usageMetadata"), model)

            val candidates = responseJson.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                if (parts.length() > 0) {
                    return@withContext parts.getJSONObject(0).getString("text") to usage
                }
            }
            throw ApiError.unexpectedResponse("Gemini")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details available"
            throw ApiError.fromHttpResponse("Gemini", responseCode, errorText)
        }
    }

    /** generateContent with one inline audio blob + a text prompt, for audio-grounded analysis (e.g. pronunciation). */
    suspend fun generateContentWithAudio(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        sampleRate: Int,
        prompt: String,
        mimeType: String? = null
    ): String = generateContentWithAudioParts(
        apiKey = apiKey,
        model = model,
        audioParts = listOf(
            InlineAudioPart(
                bytes = audioBytes,
                mimeType = mimeType ?: "audio/pcm;rate=$sampleRate"
            )
        ),
        prompt = prompt
    )

    /**
     * JSON-mode generateContent request with ordered, labelled audio parts. Labelling each clip
     * lets pronunciation analysis bind a finding to the exact learner turn instead of asking the
     * model to guess where a word occurred in one long, truncated recording.
     */
    suspend fun generateContentWithAudioParts(
        apiKey: String,
        model: String,
        audioParts: List<InlineAudioPart>,
        prompt: String,
        responseSchema: JSONObject? = null,
        /**
         * Caller's remaining time budget. `HttpURLConnection` reads block, so a coroutine-level
         * `withTimeoutOrNull` around this call cannot actually cut a slow response short — the
         * socket's own read timeout is the only thing that can. Callers running under a deadline
         * (pronunciation analysis) pass what they have left so one slow round-trip can't overrun
         * it by the full default.
         */
        readTimeoutMs: Long? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Gemini API key is empty. Please configure it in Settings.")
        }
        require(audioParts.isNotEmpty()) { "At least one audio part is required." }

        val normalizedModel = normalizeGeminiAnalysisModel(model)
        val conn = openJsonConnection(getBaseUrl(normalizedModel), apiKey)
        conn.connectTimeout = 30_000
        conn.readTimeout = readTimeoutMs
            ?.coerceIn(AUDIO_MIN_READ_TIMEOUT_MS.toLong(), AUDIO_READ_TIMEOUT_MS.toLong())
            ?.toInt()
            ?: AUDIO_READ_TIMEOUT_MS

        val payload = JSONObject()
        val partsArray = JSONArray()

        // Put the task first, then preserve label/audio order for exact turn grounding.
        partsArray.put(JSONObject().put("text", prompt))
        audioParts.forEach { part ->
            if (part.label.isNotBlank()) {
                partsArray.put(JSONObject().put("text", part.label))
            }
            // generateContent can't decode raw PCM; wrap it in WAV so the model actually "hears"
            // the recording instead of judging it as silence (see containerizeInlineAudio).
            val (sendBytes, sendMime) = containerizeInlineAudio(part.bytes, part.mimeType)
            partsArray.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", sendMime)
                        .put("data", Base64.encodeToString(sendBytes, Base64.NO_WRAP))
                )
            )
        }

        val contentObj = JSONObject()
        contentObj.put("parts", partsArray)
        payload.put("contents", JSONArray().put(contentObj))
        val generationConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", 4096)
            .put("temperature", 0.1)
        responseSchema?.let { generationConfig.put("responseSchema", it) }
        payload.put("generationConfig", generationConfig)

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optJSONObject("usageMetadata")?.let { meta ->
                val usage = LlmUsage(
                    inputTokens = meta.optInt("promptTokenCount", 0),
                    outputTokens = meta.optInt("candidatesTokenCount", 0) +
                        meta.optInt("thoughtsTokenCount", 0),
                    cachedTokens = meta.optInt("cachedContentTokenCount", 0),
                    modelUsed = normalizedModel,
                )
                com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                    provider = "gemini",
                    model = normalizedModel,
                    operation = "audio_analysis",
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    costUsd = com.example.medvoicetrainer.analysis.CostTracker.computeAnalysisCost(
                        "gemini", usage.inputTokens, usage.outputTokens, usage.cachedTokens, normalizedModel
                    ),
                    estimated = false,
                )
            }
            val candidates = responseJson.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                if (parts.length() > 0) {
                    return@withContext parts.getJSONObject(0).getString("text")
                }
            }
            throw ApiError.unexpectedResponse("Gemini")
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details available"
            throw ApiError.fromHttpResponse("Gemini", responseCode, errorText)
        }
    }

    /**
     * Audio-output-capable models tried in order for [synthesizeSpeech]. Leads with the
     * dedicated TTS preview model (its own separate, if tiny, free-tier quota bucket) then falls
     * through the same general-purpose models known to support responseModalities=AUDIO. Do not
     * put a retired model here (see RETIRED_GEMINI_ANALYSIS_MODELS) — gemini-2.0-flash was
     * previously hardcoded here and silently produced no audio once Google retired it.
     */
    private val GEMINI_TTS_MODEL_CHAIN = listOf(
        "gemini-3.1-flash-tts-preview",
        DEFAULT_GEMINI_ANALYSIS_MODEL,
        "gemini-3.1-flash-lite",
        "gemini-3.6-flash"
    )

    /** Default narration framing for [synthesizeSpeech] — unchanged from before [voiceName]/[instruction] existed. */
    private const val DEFAULT_TTS_INSTRUCTION =
        "Read this sentence clearly and naturally, as a doctor speaking to a patient."

    /**
     * Synthesize speech via Gemini's audio-output modality. Returns (audio bytes, sample rate),
     * or null if every model in [GEMINI_TTS_MODEL_CHAIN] fails. Detects a WAV container in the
     * response and unwraps it; otherwise assumes raw PCM16 at 24kHz (Gemini's native TTS output
     * rate). [voiceName], when given, must be one of [com.example.medvoicetrainer.voice.VoiceCatalog.GEMINI_VOICES];
     * [instruction] replaces the default doctor-to-patient framing (e.g. for Listening Lab's
     * accent-narration instructions) while keeping the same anti-leak "output only the audio"
     * suffix so the model never speaks the instruction itself.
     */
    suspend fun synthesizeSpeech(
        apiKey: String,
        text: String,
        voiceName: String? = null,
        instruction: String = DEFAULT_TTS_INSTRUCTION,
    ): Pair<ByteArray, Int>? = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) return@withContext null
        for (model in GEMINI_TTS_MODEL_CHAIN) {
            trySynthesizeSpeech(apiKey, model, text, voiceName, instruction)?.let { return@withContext it }
        }
        null
    }

    private fun trySynthesizeSpeech(
        apiKey: String,
        model: String,
        text: String,
        voiceName: String?,
        instruction: String,
    ): Pair<ByteArray, Int>? {
        return try {
            val conn = openJsonConnection(getBaseUrl(model), apiKey)

            val payload = JSONObject()
            val contentObj = JSONObject()
            val partObj = JSONObject()
            partObj.put(
                "text",
                "$instruction Output ONLY the audio for this text, absolutely nothing else: $text"
            )
            contentObj.put("parts", JSONArray().put(partObj))
            payload.put("contents", JSONArray().put(contentObj))
            val generationConfig = JSONObject()
            generationConfig.put("responseModalities", JSONArray().put("AUDIO"))
            if (!voiceName.isNullOrBlank()) {
                val speechConfig = JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))
                )
                generationConfig.put("speechConfig", speechConfig)
            }
            payload.put("generationConfig", generationConfig)

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optJSONObject("usageMetadata")?.let { meta ->
                val input = meta.optInt("promptTokenCount", 0)
                val output = meta.optInt("candidatesTokenCount", 0)
                val rates = when (model) {
                    "gemini-3.1-flash-tts-preview" -> 1.00 to 20.00
                    else -> 1.50 to 9.00
                }
                com.example.medvoicetrainer.analysis.ApiCostRecorder.record(
                    provider = "gemini",
                    model = model,
                    operation = "tts",
                    inputTokens = input,
                    outputTokens = output,
                    costUsd = (input * rates.first + output * rates.second) / 1_000_000.0,
                    estimated = false,
                )
            }
            val candidates = responseJson.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                val data = inlineData.optString("data", "")
                if (data.isEmpty()) continue
                val audioBytes = Base64.decode(data, Base64.DEFAULT)
                return if (isWav(audioBytes)) unwrapWav(audioBytes) else audioBytes to 24000
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    internal fun isWav(bytes: ByteArray): Boolean {   // internal: pinned by the golden harness
        return bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
    }

    internal fun unwrapWav(bytes: ByteArray): Pair<ByteArray, Int> {   // internal: pinned by the golden harness
        fun leInt(offset: Int) = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        val sampleRate = leInt(24)
        // Canonical 44-byte header; assumes no extra chunks before "data".
        val frames = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else ByteArray(0)
        return frames to sampleRate
    }

    suspend fun evaluateSession(
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = com.example.medvoicetrainer.analysis.EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): String {
        val (systemPrompt, prompt) = com.example.medvoicetrainer.analysis.EvalPromptBuilder
            .buildEvalPrompts(transcript, caseJson, nativeLanguage, analysisDomain, rubricContext)
        return generateContent(apiKey, model, prompt, systemPrompt)
    }

    private const val GEMINI_MAX_OUTPUT_TOKENS = 32768

    internal fun isValidJson(text: String): Boolean {   // internal: pinned by the golden harness
        return try {
            JSONObject(text)
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            try {
                JSONArray(text)
                true
            } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (e2: Exception) {
                false
            }
        }
    }

    /** One JSON-mode analysis call; returns (text, usage, finishReason). Does not retry. */
    private suspend fun callAnalysisOnce(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): Triple<String, LlmUsage, String> = withContext(Dispatchers.IO) {
        val conn = openJsonConnection(getBaseUrl(model), apiKey)

        val payload = JSONObject()
        val contentObj = JSONObject()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        contentObj.put("parts", JSONArray().put(partObj))
        payload.put("contents", JSONArray().put(contentObj))

        val systemInstructionObj = JSONObject()
        systemInstructionObj.put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        payload.put("systemInstruction", systemInstructionObj)

        val generationConfig = JSONObject()
        generationConfig.put("maxOutputTokens", GEMINI_MAX_OUTPUT_TOKENS)
        generationConfig.put("responseMimeType", "application/json")
        generationConfig.put("temperature", 0.1)
        payload.put("generationConfig", generationConfig)

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details available"
            throw ApiError.fromHttpResponse("Gemini", responseCode, errorText)
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val responseJson = JSONObject(responseText)

        val usage = parseUsageMetadata(responseJson.optJSONObject("usageMetadata"), model)

        val candidates = responseJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return@withContext Triple("", usage, "")
        }
        val firstCandidate = candidates.getJSONObject(0)
        val finishReason = firstCandidate.optString("finishReason", "")
        val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
        val text = if (parts != null && parts.length() > 0) parts.getJSONObject(0).optString("text", "") else ""
        Triple(text.trim(), usage, finishReason)
    }

    /**
     * Ported from app/analysis/analysis_providers.py's _call_gemini. Some models intermittently
     * stop mid-JSON with finishReason=STOP; such a response is retried once, then falls through
     * to the next model. If every model returns broken JSON, the longest attempt is returned so
     * the caller's tolerant parser can still salvage the complete leading sections. A model-
     * unavailable 404 or 429/quota error skips to the next model rather than retrying it.
     */
    suspend fun evaluateSessionWithUsage(
        apiKey: String,
        model: String,
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        analysisDomain: String = com.example.medvoicetrainer.analysis.EvalPromptBuilder.DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): Pair<String, LlmUsage> {
        val (systemPrompt, prompt) = com.example.medvoicetrainer.analysis.EvalPromptBuilder
            .buildEvalPrompts(transcript, caseJson, nativeLanguage, analysisDomain, rubricContext)
        return evaluatePromptsWithUsage(apiKey, model, systemPrompt, prompt)
    }

    /**
     * The chain walk itself, given prompts that are already built.
     *
     * Split out of [evaluateSessionWithUsage] so the cross-language golden harness can drive it
     * with a stub [call] and pin the discarded-usage arithmetic — the part most likely to drift
     * silently, since a wrong total is still a plausible-looking number. Every existing call site
     * goes on using [evaluateSessionWithUsage]; nothing about its behaviour changes.
     */
    internal suspend fun evaluatePromptsWithUsage(
        apiKey: String,
        model: String,
        systemPrompt: String,
        prompt: String,
        call: suspend (String, String, String, String) -> Triple<String, LlmUsage, String> =
            { key, m, p, sp -> callAnalysisOnce(key, m, p, sp) },
    ): Pair<String, LlmUsage> {
        val modelsToTry = (listOf(normalizeGeminiAnalysisModel(model)) + GEMINI_FREE_TIER_MODEL_CHAIN).distinct()
        var bestRaw = ""
        var bestUsage: LlmUsage? = null
        var lastFallbackError: Exception? = null
        // Google bills every attempt, including the ones that came back as truncated JSON or hit
        // MAX_TOKENS and sent us on to the next model. Returning only the winning attempt's usage
        // charged the learner for one call when the chain may have made several, so discarded
        // attempts accumulate here and are folded into whatever we ultimately return.
        var discardedUsage = LlmUsage(modelUsed = normalizeGeminiAnalysisModel(model))
        fun discard(usage: LlmUsage) {
            discardedUsage = discardedUsage.copy(
                inputTokens = discardedUsage.inputTokens + usage.inputTokens,
                outputTokens = discardedUsage.outputTokens + usage.outputTokens,
                cachedTokens = discardedUsage.cachedTokens + usage.cachedTokens,
            )
        }
        fun withDiscarded(usage: LlmUsage) = usage.copy(
            inputTokens = usage.inputTokens + discardedUsage.inputTokens,
            outputTokens = usage.outputTokens + discardedUsage.outputTokens,
            cachedTokens = usage.cachedTokens + discardedUsage.cachedTokens,
        )

        for (m in modelsToTry) {
            var brokeOutToNextModel = false
            for (retry in 0 until 2) {
                try {
                    val (rawText, usage, finishReason) = call(apiKey, m, prompt, systemPrompt)
                    val problem = when {
                        finishReason == "MAX_TOKENS" -> "hit the $GEMINI_MAX_OUTPUT_TOKENS-token output limit"
                        finishReason.isNotEmpty() && finishReason != "STOP" -> "stopped early ($finishReason)"
                        !isValidJson(rawText) -> "returned incomplete JSON despite finishing normally"
                        else -> ""
                    }
                    if (problem.isEmpty()) {
                        // Any earlier salvage candidate was also a billed call.
                        bestUsage?.let { discard(it) }
                        return rawText to withDiscarded(usage)
                    }
                    if (rawText.length > bestRaw.length) {
                        // The previous best is now a discarded attempt in its own right.
                        bestUsage?.let { discard(it) }
                        bestRaw = rawText
                        bestUsage = usage
                    } else {
                        discard(usage)
                    }
                    if (retry == 0) continue
                    brokeOutToNextModel = true
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (isGeminiAnalysisFallbackError(e)) {
                        lastFallbackError = e
                        brokeOutToNextModel = true
                    } else {
                        throw e
                    }
                }
                if (brokeOutToNextModel) break
            }
        }

        if (bestRaw.isNotEmpty()) {
            return bestRaw to withDiscarded(bestUsage ?: LlmUsage())
        }
        if (lastFallbackError != null) {
            throw ApiError.allModelsUnavailable()
        }
        throw Exception("Gemini fallback list is empty.")
    }
}
