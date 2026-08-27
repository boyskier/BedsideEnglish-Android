package com.example.medvoicetrainer.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Ported from app/voice/capabilities.py — declared voice-backend capabilities and deterministic
 * speed planning. UI code should use this registry instead of inferring capability from a
 * backend name.
 */

data class VoiceBackendCapabilities(
    val backend: String,
    val displayName: String,
    val nativeSpeedMin: Double?,
    val nativeSpeedMax: Double?,
    val nativeSpeedTurnBoundaryOnly: Boolean,
    val accentControl: String,
    val voiceControl: String,
    val localSpeedBuffering: String
) {
    val hasNativeSpeed: Boolean get() = nativeSpeedMin != null && nativeSpeedMax != null
}

data class SpeedPlan(
    val requested: Double,
    val providerSpeed: Double,
    val localSpeed: Double,
    val bufferFullTurn: Boolean,
    val experimental: Boolean,
    val explanation: String
)

object Capabilities {

    val BACKEND_CAPABILITIES: Map<String, VoiceBackendCapabilities> = mapOf(
        "openai" to VoiceBackendCapabilities(
            backend = "openai",
            displayName = "OpenAI Realtime",
            nativeSpeedMin = 0.25,
            nativeSpeedMax = 1.5,
            nativeSpeedTurnBoundaryOnly = true,
            accentControl = "prompt_or_custom_voice",
            voiceControl = "built_in_or_custom_voice",
            localSpeedBuffering = "residual_above_native_limit"
        ),
        "gemini" to VoiceBackendCapabilities(
            backend = "gemini",
            displayName = "Gemini Live",
            nativeSpeedMin = null,
            nativeSpeedMax = null,
            nativeSpeedTurnBoundaryOnly = false,
            accentControl = "prompt_best_effort",
            voiceControl = "prebuilt_voice",
            localSpeedBuffering = "streaming_jitter_buffer"
        ),
        "demo" to VoiceBackendCapabilities(
            backend = "demo",
            displayName = "Typed demo",
            nativeSpeedMin = null,
            nativeSpeedMax = null,
            nativeSpeedTurnBoundaryOnly = false,
            accentControl = "none",
            voiceControl = "none",
            localSpeedBuffering = "none"
        ),
        "mock" to VoiceBackendCapabilities(
            backend = "mock",
            displayName = "Mock",
            nativeSpeedMin = null,
            nativeSpeedMax = null,
            nativeSpeedTurnBoundaryOnly = false,
            accentControl = "none",
            voiceControl = "none",
            localSpeedBuffering = "none"
        )
    )

    fun capabilitiesFor(backend: String): VoiceBackendCapabilities {
        return BACKEND_CAPABILITIES[backend] ?: BACKEND_CAPABILITIES.getValue("mock")
    }

    /**
     * Split one learner-facing speed into provider and local components.
     *
     * OpenAI performs the supported 0.25x-1.5x adjustment server-side. Above 1.5x, local WSOLA
     * supplies only the residual multiplier. Gemini has no documented native speed field, so
     * incoming chunks are stretched locally behind a short jitter buffer. Speeds above 1.5x are
     * deliberately labelled experimental regardless of backend.
     */
    fun planPlaybackSpeed(backend: String, requestedRaw: Double): SpeedPlan {
        if (requestedRaw.isNaN()) {
            return SpeedPlan(
                requested = 1.0,
                providerSpeed = 1.0,
                localSpeed = 1.0,
                bufferFullTurn = false,
                experimental = false,
                explanation = "Default speed due to NaN input"
            )
        }
        val requested = min(2.5, max(0.5, requestedRaw))
        val experimental = requested > 1.5
        val caps = capabilitiesFor(backend)

        if (backend == "openai" && caps.hasNativeSpeed) {
            val provider = min(caps.nativeSpeedMax!!, max(caps.nativeSpeedMin!!, requested))
            val local = requested / provider
            return SpeedPlan(
                requested = requested,
                providerSpeed = provider,
                localSpeed = local,
                bufferFullTurn = false,
                experimental = experimental,
                explanation = if (!experimental) "OpenAI native speed" else "OpenAI 1.5x native speed plus experimental local residual"
            )
        }

        if (backend == "gemini") {
            return SpeedPlan(
                requested = requested,
                providerSpeed = 1.0,
                localSpeed = requested,
                bufferFullTurn = false,
                experimental = experimental,
                explanation = "Low-latency streaming local speed; Gemini has no native speed field"
            )
        }

        if (backend == "demo") {
            return SpeedPlan(
                requested = requested,
                providerSpeed = 1.0,
                localSpeed = requested,
                bufferFullTurn = false,
                experimental = experimental,
                explanation = "Demo backend local speed"
            )
        }

        return SpeedPlan(
            requested = requested,
            providerSpeed = 1.0,
            localSpeed = 1.0,
            bufferFullTurn = false,
            experimental = experimental,
            explanation = "No live audio output"
        )
    }

    fun checkBluetoothSco(action: () -> Boolean): Boolean {
        return try {
            action()
        } catch (e: SecurityException) {
            false
        }
    }

    fun allocateAudioBuffer(minBufferSize: Int, fallbackSize: Int = 4096): ByteArray {
        val size = if (minBufferSize <= 0) fallbackSize else minBufferSize
        return ByteArray(size)
    }

    private var sttPausedDuringRouting: Boolean = false

    fun onRoutingSwitchStarted() {
        sttPausedDuringRouting = true
    }

    fun onRoutingSwitchEnded() {
        sttPausedDuringRouting = false
    }

    fun isSttPausedDuringRouting(): Boolean = sttPausedDuringRouting
}
