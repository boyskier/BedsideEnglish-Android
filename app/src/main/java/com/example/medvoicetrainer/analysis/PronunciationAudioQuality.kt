package com.example.medvoicetrainer.analysis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class PronunciationAudioQuality(
    val accepted: Boolean,
    val durationMs: Long,
    val rms: Double,
    val peak: Int,
    val voicedFrameRatio: Double,
    val clippingRatio: Double,
    val estimatedSnrDb: Double,
    val reason: String? = null,
    /**
     * Stable machine-readable form of [reason] (see [PronunciationAudioQualityAnalyzer.Reason]),
     * so the UI can show a localized, actionable hint instead of the English analyst prose.
     */
    val reasonCode: String? = null
)

/** Deterministic, offline pre-flight gate. No audio or metrics leave the device here. */
object PronunciationAudioQualityAnalyzer {
    /** Stable reason codes; the UI maps these to localized "what to do about it" hints. */
    object Reason {
        const val NO_AUDIO = "no_audio"
        const val TOO_SHORT = "too_short"
        const val TOO_QUIET = "too_quiet"
        const val CLIPPED = "clipped"
        const val BROADBAND_NOISE = "broadband_noise"
        const val NO_SPEECH = "no_speech"
        const val LOW_SNR = "low_snr"
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val FRAME_MS = 20
    private const val MIN_DURATION_MS = 350L
    private const val MIN_PEAK = 500
    private const val MIN_RMS = 120.0
    private const val MAX_CLIPPING_RATIO = 0.02
    private const val MIN_VOICED_RATIO = 0.10
    private const val MIN_SNR_DB = 7.0

    fun analyze(pcm: ByteArray, sampleRate: Int = 16_000): PronunciationAudioQuality {
        val usable = pcm.size - (pcm.size % BYTES_PER_SAMPLE)
        if (usable < BYTES_PER_SAMPLE || sampleRate <= 0) {
            return rejected("No usable microphone audio was captured.", Reason.NO_AUDIO)
        }
        val samples = IntArray(usable / 2)
        var sumSquares = 0.0
        var peak = 0
        var clipped = 0
        var zeroCrossings = 0
        var byteIndex = 0
        for (i in samples.indices) {
            val low = pcm[byteIndex].toInt() and 0xff
            val high = pcm[byteIndex + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            samples[i] = sample
            if (i > 0 && (samples[i - 1] < 0) != (sample < 0)) zeroCrossings += 1
            val magnitude = abs(sample)
            peak = maxOf(peak, magnitude)
            if (magnitude >= 32_500) clipped += 1
            sumSquares += sample.toDouble() * sample
            byteIndex += 2
        }

        val durationMs = samples.size * 1_000L / sampleRate
        val rms = sqrt(sumSquares / samples.size)
        val clippingRatio = clipped.toDouble() / samples.size
        val zeroCrossingRate = zeroCrossings.toDouble() / samples.size
        val frameSamples = (sampleRate * FRAME_MS / 1_000).coerceAtLeast(1)
        val frameRms = samples.asList().chunked(frameSamples).map { frame ->
            sqrt(frame.sumOf { it.toDouble() * it } / frame.size)
        }.sorted()
        val noise = percentile(frameRms, 0.2).coerceAtLeast(20.0)
        val speech = percentile(frameRms, 0.85).coerceAtLeast(noise)
        val hasMeasurableNoiseFloor = speech >= noise * 1.8
        val voicedThreshold = maxOf(220.0, noise * 2.5)
        val voicedRatio = if (hasMeasurableNoiseFloor) {
            frameRms.count { it >= voicedThreshold }.toDouble() / frameRms.size
        } else if (rms >= MIN_RMS) {
            // A short, continuously voiced word may contain no silence from which to estimate a
            // floor. Treat it as voiced and let Gemini judge it instead of inventing bad SNR.
            1.0
        } else {
            0.0
        }
        val snr = if (hasMeasurableNoiseFloor) {
            20.0 * log10((speech + 1.0) / (noise + 1.0))
        } else {
            99.0
        }

        val (reason, reasonCode) = when {
            durationMs < MIN_DURATION_MS ->
                "The learner turn was too short for reliable pronunciation analysis." to Reason.TOO_SHORT
            peak < MIN_PEAK || rms < MIN_RMS ->
                "The microphone signal was too quiet; move closer or check microphone gain." to Reason.TOO_QUIET
            clippingRatio > MAX_CLIPPING_RATIO ->
                "The microphone signal clipped; move farther from the microphone." to Reason.CLIPPED
            !hasMeasurableNoiseFloor && zeroCrossingRate > 0.35 ->
                "The recording was dominated by steady broadband noise rather than clear speech." to
                    Reason.BROADBAND_NOISE
            voicedRatio < MIN_VOICED_RATIO ->
                "Too little clear speech was detected in this learner turn." to Reason.NO_SPEECH
            snr < MIN_SNR_DB ->
                "Background noise or speaker echo was too strong for reliable pronunciation feedback." to
                    Reason.LOW_SNR
            else -> null to null
        }
        return PronunciationAudioQuality(
            accepted = reason == null,
            durationMs = durationMs,
            rms = rms,
            peak = peak,
            voicedFrameRatio = voicedRatio,
            clippingRatio = clippingRatio,
            estimatedSnrDb = snr,
            reason = reason,
            reasonCode = reasonCode
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.lastIndex) * fraction).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun rejected(reason: String, reasonCode: String) = PronunciationAudioQuality(
        accepted = false,
        durationMs = 0,
        rms = 0.0,
        peak = 0,
        voicedFrameRatio = 0.0,
        clippingRatio = 0.0,
        estimatedSnrDb = 0.0,
        reason = reason,
        reasonCode = reasonCode
    )
}
