package com.example.medvoicetrainer.voice

import kotlin.math.sqrt

/**
 * RMS of 16-bit PCM that maps to a full-scale mic-level reading. Tuned to a firm speaking
 * voice (well below the 32767 clipping ceiling) so ordinary speech fills most of the meter.
 */
const val MIC_LEVEL_FULL_SCALE = 6000.0

/**
 * Normalized 0f..1f loudness of one chunk of 16-bit little-endian mono PCM, for the live mic
 * waveform (see `ui/screens/VoiceWaveform.kt`).
 *
 * Shared by the live-session meter ([VoiceManager]'s forwarded-audio level) and the push-to-talk
 * recorder ([AttemptRecorder]) so both meters answer the same question — "is the app hearing me
 * right now?" — with the same sensitivity. A silent or echo-suppressed chunk reads ~0 and the
 * meter flattens on its own.
 */
fun pcm16MicLevel(pcm: ByteArray): Float {
    var index = 0
    var sumSquares = 0.0
    var count = 0
    while (index + 1 < pcm.size) {
        val low = pcm[index].toInt() and 0xff
        val high = pcm[index + 1].toInt()
        val sample = ((high shl 8) or low).toShort().toInt()
        sumSquares += (sample.toDouble() * sample.toDouble())
        count += 1
        index += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sumSquares / count)
    // Map RMS (0..~32767) onto 0..1 against a firm-speaking-voice full scale, then sqrt to lift
    // quieter speech so the wave still visibly moves without background hiss pinning the floor.
    val normalized = (rms / MIC_LEVEL_FULL_SCALE).coerceIn(0.0, 1.0)
    return sqrt(normalized).toFloat()
}
