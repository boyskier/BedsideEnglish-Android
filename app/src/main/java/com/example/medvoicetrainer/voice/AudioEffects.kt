package com.example.medvoicetrainer.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

object AudioEffects {
    data class NoiseProfile(
        val label: String,
        val targetSnrDb: Double?,
        val description: String
    )

    val NOISE_PROFILES = mapOf(
        "clean" to NoiseProfile("Clean studio", null, "No added environment; use for clean replay and baseline scoring."),
        "cafe" to NoiseProfile("Busy cafe", 15.0, "Moderate broadband room noise with low conversational modulation."),
        "transit" to NoiseProfile("Bus / station", 11.0, "Stronger low-frequency vehicle hum plus broadband station noise."),
        "hallway" to NoiseProfile("Echoing hallway", 20.0, "Light delayed reflection and quiet room noise."),
        "phone" to NoiseProfile("Phone call", 18.0, "Telephone-band filtering with low background noise.")
    )

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val v = if (s.isNaN() || s.isInfinite()) 0f else s
            sum += v * v
        }
        return sqrt((sum / samples.size)).toFloat()
    }

    fun sanitizePcm(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val s = samples[i]
            out[i] = if (s.isNaN() || s.isInfinite()) 0f else s
        }
        return out
    }

    private fun applySafetyLimiter(samples: FloatArray, peak: Float = 30000f): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val clean = sanitizePcm(samples)
        var maxAbs = 0f
        for (s in clean) {
            val a = abs(s)
            if (a > maxAbs) maxAbs = a
        }
        val out = FloatArray(samples.size)
        val scale = if (maxAbs > peak) peak / maxAbs else 1f
        for (i in samples.indices) {
            var v = samples[i] * scale
            if (v < -32768f) v = -32768f
            if (v > 32767f) v = 32767f
            out[i] = v
        }
        return out
    }

    /**
     * Second-order Butterworth (Q≈0.707) applied in-place, RBJ-cookbook coefficients. Highpass and
     * lowpass share this so a cascade band-limits the signal. Operates on Float PCM samples.
     */
    private fun biquad(
        samples: FloatArray,
        sampleRate: Int,
        cutoffHz: Double,
        highpass: Boolean
    ) {
        if (samples.isEmpty() || sampleRate <= 0) return
        val w0 = 2.0 * Math.PI * (cutoffHz / sampleRate)
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.70710678)
        val b0: Double; val b1: Double; val b2: Double
        if (highpass) {
            b0 = (1.0 + cosW0) / 2.0
            b1 = -(1.0 + cosW0)
            b2 = (1.0 + cosW0) / 2.0
        } else {
            b0 = (1.0 - cosW0) / 2.0
            b1 = 1.0 - cosW0
            b2 = (1.0 - cosW0) / 2.0
        }
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0
        val na1 = a1 / a0; val na2 = a2 / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            samples[i] = y0.toFloat()
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
        }
    }

    /**
     * Band-limit PCM16 to the ~300–3400 Hz telephone band. Used by strict-listener speaking
     * practice to make the AI listener face the same narrow-band signal an on-call attendant hears
     * over the phone, instead of full-fidelity studio audio the ASR finds unrealistically easy.
     */
    fun telephoneBandlimit(pcm: ByteArray, sampleRate: Int): ByteArray {
        if (pcm.isEmpty() || sampleRate <= 0) return pcm
        val usableLen = pcm.size - (pcm.size % 2)
        if (usableLen <= 0) return pcm
        val shortBuffer = ByteBuffer.wrap(pcm, 0, usableLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shortBuffer.remaining())
        for (i in samples.indices) samples[i] = shortBuffer.get(i).toFloat()
        if (samples.isEmpty()) return pcm
        biquad(samples, sampleRate, 300.0, highpass = true)
        biquad(samples, sampleRate, minOf(3400.0, sampleRate / 2.0 - 100.0), highpass = false)
        val limited = applySafetyLimiter(samples)
        val outBuffer = ByteBuffer.allocate(usableLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in limited) outBuffer.putShort(s.toInt().toShort())
        return outBuffer.array()
    }

    /** Peak a normalized attempt is lifted to: loud, with ~3 dB of headroom before clipping. */
    private const val SPEECH_TARGET_PEAK = 22_000f

    /** Ceiling on the lift, so room tone in a genuinely silent recording is not amplified into "speech". */
    private const val MAX_SPEECH_GAIN = 12f

    /** Below this peak there is no speech to rescue — leave the buffer alone and let the gate report it. */
    private const val MIN_NORMALIZABLE_PEAK = 200f

    /**
     * Lift a quiet phone-held-at-arm's-length attempt to a healthy level before it is sent to an
     * ASR/LLM listener. A learner practising on the built-in mic without earphones routinely lands
     * 15–25 dB below what the same phrase records at through a headset, and a blind listener
     * answers "could not assess" to that signal even though the pronunciation was fine.
     *
     * Peak-normalization only: a single gain for the whole utterance, so the relative loudness of
     * the syllables (the thing being judged) is untouched, unlike a compressor.
     */
    fun normalizeForSpeech(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        if (sampleRate <= 0) return pcm
        val usableLen = pcm.size - (pcm.size % 2)
        if (usableLen < 2) return pcm
        var peak = 0
        var index = 0
        while (index < usableLen) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val magnitude = abs(((high shl 8) or low).toShort().toInt())
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        if (peak < MIN_NORMALIZABLE_PEAK || peak >= SPEECH_TARGET_PEAK) return pcm
        val gain = minOf(MAX_SPEECH_GAIN, SPEECH_TARGET_PEAK / peak)
        if (gain <= 1.05f) return pcm

        val out = ByteArray(usableLen)
        index = 0
        while (index < usableLen) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32_768, 32_767)
            out[index] = (scaled and 0xff).toByte()
            out[index + 1] = ((scaled shr 8) and 0xff).toByte()
            index += 2
        }
        return out
    }

    fun applyEnvironment(
        pcm: ByteArray,
        sampleRate: Int,
        profile: String = "clean",
        seed: String = "0"
    ): ByteArray {
        if (pcm.isEmpty() || profile == "clean" || !NOISE_PROFILES.containsKey(profile)) {
            return pcm
        }

        // Telephone-band filtering is a real band-limit, not just background noise.
        val prefiltered = if (profile == "phone") telephoneBandlimit(pcm, sampleRate) else pcm

        val usableLen = prefiltered.size - (prefiltered.size % 2)
        val shortBuffer = ByteBuffer.wrap(prefiltered, 0, usableLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shortBuffer.remaining())
        for (i in samples.indices) {
            samples[i] = shortBuffer.get(i).toFloat()
        }

        if (samples.isEmpty()) return pcm

        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(seed.toByteArray(Charsets.UTF_8))
        var seedLong = 0L
        for (i in 0..7) {
            seedLong = seedLong or ((digest[i].toLong() and 0xFF) shl (8 * i))
        }
        val rng = Random(seedLong)

        // Basic phone filter approximation (skipping FFT for simplicity, just bandpass roughly or leave as is + noise)
        // For a true phone filter we'd need IIR/FIR. We will just add the noise for now to avoid heavy FFT implementations.
        
        var currentSamples = samples

        if (profile == "hallway") {
            val delay = max(1, (sampleRate * 0.075).toInt())
            val reflected = FloatArray(samples.size)
            for (i in delay until samples.size) {
                reflected[i] = samples[i - delay] * 0.24f
            }
            for (i in samples.indices) {
                currentSamples[i] += reflected[i]
            }
        }

        val signalRms = max(rms(currentSamples), 1.0f)
        val noise = FloatArray(samples.size)
        for (i in noise.indices) {
            noise[i] = (rng.nextGaussian()).toFloat()
        }

        if (profile == "cafe") {
            for (i in noise.indices) {
                val t = i.toFloat() / sampleRate
                val env = 0.72f + 0.28f * (sin(2.0 * Math.PI * 3.7 * t)).toFloat() * (sin(2.0 * Math.PI * 3.7 * t)).toFloat()
                noise[i] *= env
            }
        } else if (profile == "transit") {
            for (i in noise.indices) {
                val t = i.toFloat() / sampleRate
                noise[i] += 1.8f * (sin(2.0 * Math.PI * 78.0 * t)).toFloat()
            }
        }

        val noiseRms = max(rms(noise), 1e-6f)
        val snrDb = NOISE_PROFILES[profile]?.targetSnrDb ?: 60.0
        val targetNoiseRms = (signalRms / Math.pow(10.0, snrDb / 20.0)).toFloat()
        
        val noiseScale = targetNoiseRms / noiseRms
        for (i in samples.indices) {
            currentSamples[i] += noise[i] * noiseScale
        }

        currentSamples = applySafetyLimiter(currentSamples)

        val outBuffer = ByteBuffer.allocate(usableLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in currentSamples.indices) {
            outBuffer.putShort(currentSamples[i].toInt().toShort())
        }
        return outBuffer.array()
    }

    fun measurePcm(pcm: ByteArray, sampleRate: Int): Map<String, Any?> {
        if (pcm.isEmpty()) {
            return mapOf(
                "duration_seconds" to 0.0,
                "rms_dbfs" to null,
                "peak_dbfs" to null,
                "clipped_samples" to null
            )
        }
        val usableLen = pcm.size - (pcm.size % 2)
        val shortBuffer = ByteBuffer.wrap(pcm, 0, usableLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shortBuffer.remaining())
        var maxAbs = 0f
        var clipped = 0
        for (i in samples.indices) {
            val v = shortBuffer.get(i).toFloat()
            samples[i] = v
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
            if (a >= 32767f) clipped++
        }
        
        val rmsVal = rms(samples)
        
        return mapOf(
            "duration_seconds" to samples.size.toDouble() / max(1, sampleRate),
            "rms_dbfs" to 20 * log10(max(rmsVal, 1e-9f) / 32768.0),
            "peak_dbfs" to 20 * log10(max(maxAbs, 1e-9f) / 32768.0),
            "clipped_samples" to clipped
        )
    }
}
