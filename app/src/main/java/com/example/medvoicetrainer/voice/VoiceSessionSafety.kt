package com.example.medvoicetrainer.voice

import kotlin.math.sqrt

/**
 * Thread-safe, Android-free state used by [VoiceManager] for the parts of the live-audio
 * contract that must be deterministic and unit-testable.
 *
 * The recorder owns each non-silent input array.  Echo suppression returns a cached immutable
 * zero array of the same size, avoiding a fresh [ByteArray] allocation for every mic callback.
 */
internal class VoiceSessionSafety(
    private val nowNanos: () -> Long = { System.nanoTime() },
    echoTailMillis: Long = ECHO_TAIL_MILLIS,
    micWarningAfterMillis: Long = MIC_WARNING_AFTER_MILLIS,
    private val quietMicThreshold: Double = QUIET_MIC_RMS_THRESHOLD
) {
    companion object {
        const val DEFAULT_ECHO_PREVENTION = true
        const val ECHO_TAIL_MILLIS = 250L
        const val MIC_WARNING_AFTER_MILLIS = 12_000L
        const val QUIET_MIC_RMS_THRESHOLD = 200.0

        // §5 DEGRADED frame ("Retrying… (attempt N of RECONNECT_MAX_ATTEMPTS)") — an unverified
        // guess pending an on-device check against real provider latency (spec §16 R1 territory),
        // kept in one place so it's a one-line tune, not a hunt through VoiceManager.
        const val RECONNECT_MAX_ATTEMPTS = 5
        const val RECONNECT_BASE_DELAY_MILLIS = 1_000L
        const val RECONNECT_MAX_DELAY_MILLIS = 16_000L

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private val echoTailNanos = echoTailMillis.coerceAtLeast(0L) * NANOS_PER_MILLI
    private val micWarningAfterNanos =
        micWarningAfterMillis.coerceAtLeast(0L) * NANOS_PER_MILLI
    private val lock = Any()

    private var running = false
    private var sessionStartedAtNanos = 0L
    private var micPeakRms = 0.0
    private var micWarningDelivered = false

    private var aiAudioActive = false
    private var echoTailUntilNanos = Long.MIN_VALUE
    private var playbackStateVersion = 0L
    private var cachedSilence = ByteArray(0)

    fun startSession() = synchronized(lock) {
        running = true
        sessionStartedAtNanos = nowNanos()
        micPeakRms = 0.0
        micWarningDelivered = false
        aiAudioActive = false
        echoTailUntilNanos = Long.MIN_VALUE
        playbackStateVersion += 1L
    }

    fun stopSession() = synchronized(lock) {
        running = false
        aiAudioActive = false
        echoTailUntilNanos = Long.MIN_VALUE
        playbackStateVersion += 1L
    }

    /** Returns true only for the first audio chunk of a model turn. */
    fun markAiAudioStarted(): Boolean = synchronized(lock) {
        if (!running) return@synchronized false
        val isNewTurn = !aiAudioActive
        if (isNewTurn) playbackStateVersion += 1L
        aiAudioActive = true
        // While a turn is active, a finite tail deadline from the previous turn is irrelevant.
        echoTailUntilNanos = Long.MAX_VALUE
        isNewTurn
    }

    /** Returns a token with which a delayed "Listening" status can reject stale turns. */
    fun markAiTurnComplete(): Long = synchronized(lock) {
        markAiTurnCompleteLocked()
    }

    /** Atomically completes playback only when audio is still active. */
    fun markAiTurnCompleteIfActive(): Long? = synchronized(lock) {
        if (!running || !aiAudioActive) return@synchronized null
        markAiTurnCompleteLocked()
    }

    private fun markAiTurnCompleteLocked(): Long {
        if (!running || !aiAudioActive) return playbackStateVersion
        aiAudioActive = false
        echoTailUntilNanos = saturatingAdd(nowNanos(), echoTailNanos)
        playbackStateVersion += 1L
        return playbackStateVersion
    }

    /** Provider-side barge-in/cancellation stops output immediately, without an echo tail. */
    fun markAiAudioInterrupted(): Long = synchronized(lock) {
        if (!running) return@synchronized playbackStateVersion
        aiAudioActive = false
        echoTailUntilNanos = Long.MIN_VALUE
        playbackStateVersion += 1L
        playbackStateVersion
    }

    fun canAnnounceListening(turnToken: Long): Boolean = synchronized(lock) {
        running &&
            turnToken == playbackStateVersion &&
            !aiAudioActive &&
            nowNanos() >= echoTailUntilNanos
    }

    /**
     * Select the exact bytes that should go to the provider for this recorder callback.
     * The original [chunk] instance is returned when live speech is allowed; callers may use
     * referential equality to decide whether the real chunk belongs in pronunciation capture.
     */
    fun prepareMicChunk(
        chunk: ByteArray,
        echoPrevention: Boolean = DEFAULT_ECHO_PREVENTION
    ): ByteArray = synchronized(lock) {
        val now = nowNanos()
        val suppress = running && echoPrevention &&
            (aiAudioActive || now < echoTailUntilNanos)
        if (suppress) {
            silenceOfSizeLocked(chunk.size)
        } else {
            if (running) updateMicPeakLocked(chunk)
            chunk
        }
    }

    fun emergencyMute(chunk: ByteArray): ByteArray = synchronized(lock) {
        chunk.fill(0)
        chunk
    }

    /** Marks and returns the quiet-mic warning exactly once per session. */
    fun shouldEmitMicWarning(): Boolean = synchronized(lock) {
        if (!running || micWarningDelivered) return@synchronized false
        if (nowNanos() - sessionStartedAtNanos < micWarningAfterNanos) {
            return@synchronized false
        }
        if (micPeakRms >= quietMicThreshold) return@synchronized false
        micWarningDelivered = true
        true
    }

    fun peakMicRms(): Double = synchronized(lock) { micPeakRms }

    private fun silenceOfSizeLocked(size: Int): ByteArray {
        if (cachedSilence.size != size) cachedSilence = ByteArray(size)
        return cachedSilence
    }

    private fun updateMicPeakLocked(chunk: ByteArray) {
        var index = 0
        var sampleCount = 0
        var sumSquares = 0L
        while (index + 1 < chunk.size) {
            val low = chunk[index].toInt() and 0xff
            val high = chunk[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            sumSquares += sample.toLong() * sample.toLong()
            sampleCount += 1
            index += 2
        }
        if (sampleCount == 0) return
        val rms = sqrt(sumSquares.toDouble() / sampleCount.toDouble())
        if (rms > micPeakRms) micPeakRms = rms
    }

    private fun saturatingAdd(value: Long, increment: Long): Long {
        if (increment <= 0L) return value
        return if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}

/** Typed demo/dev mock is a strict no-microphone path. */
internal fun shouldStartLiveMicrophone(isMock: Boolean, apiKey: String): Boolean =
    !isMock && apiKey.isNotBlank()
