package com.example.medvoicetrainer.voice

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/**
 * Time-domain playback-rate change (WSOLA) for the patient's audio stream.
 *
 * [process] is called from the provider's WebSocket read thread for every audio frame that
 * arrives, so all of its working memory is allocated once and reused. It used to build a new
 * input buffer per chunk (`FloatArray(inBuffer.size + samples.size)` plus two copies), a fresh
 * `FloatArray(hop)`/`FloatArray(frame)` several times per synthesis hop, a list of those, and
 * then a flattened copy of the whole list — tens of allocations and a full input-buffer copy for
 * every chunk of a multi-minute conversation. The arithmetic below is unchanged; only the
 * buffering around it is.
 */
class WsolaStretcher(private val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "Invalid audio sample rate: $sampleRate" }
    }

    private var speed: Float = 1.0f
    private val hop: Int = if (sampleRate < 8000) max(8, (sampleRate * 0.005).toInt()) else max(32, (sampleRate * 0.015).toInt())
    private val frame: Int = 2 * hop
    private val tol: Int = if (sampleRate < 8000) max(4, (sampleRate * 0.0025).toInt()) else max(16, (sampleRate * 0.0075).toInt())

    // Pending input samples live in inBuffer[0 until inCount]; capacity beyond inCount is scratch.
    private var inBuffer: FloatArray = FloatArray(0)
    private var inCount: Int = 0

    // The previous frame's second half, cross-faded into the next one. Allocated once; `hasTail`
    // replaces the old nullable field so the array itself can be reused across hops.
    private val tail = FloatArray(hop)
    private var hasTail: Boolean = false

    // Synthesis output for the current process() call, reused between calls.
    private var outBuffer: FloatArray = FloatArray(0)
    private var outCount: Int = 0

    private var pos: Float = 0f
    private var isClosed: Boolean = false

    private val fadeUp = FloatArray(hop)
    private val fadeDown = FloatArray(hop)

    init {
        for (i in 0 until hop) {
            val ramp = i.toFloat() / (hop - 1).toFloat() * PI.toFloat()
            fadeUp[i] = 0.5f - 0.5f * cos(ramp)
            fadeDown[i] = 1.0f - fadeUp[i]
        }
    }

    // Read-only views of the geometry for the golden exporter (ledger LOG-29c). The fields
    // stay private: a hop that changed by one sample would walk the two apps apart with no
    // error anywhere, so the constants are pinned, not opened for writing.
    // Amplitude at or below which a sample counts as the padding finish() fed itself.
    internal val FINISH_SILENCE_FLOOR = 8

    internal fun hopForGolden(): Int = hop
    internal fun frameForGolden(): Int = frame
    internal fun tolForGolden(): Int = tol

    fun setSpeed(newSpeed: Float) {
        speed = Math.round(newSpeed.coerceIn(0.5f, 2.5f) * 100.0f) / 100.0f
    }

    fun getSpeed() = speed

    fun close() {
        isClosed = true
        reset()
        // Only on close is it worth handing the working memory back.
        inBuffer = FloatArray(0)
        outBuffer = FloatArray(0)
    }

    fun isClosed(): Boolean = isClosed

    fun process(data: ByteArray): ByteArray {
        if (isClosed || data.isEmpty()) return ByteArray(0)

        if (abs(speed - 1.0f) < 0.001f) {
            val leftover = drainPassthrough()
            return leftover + data
        }

        val pairCount = data.size / 2
        if (pairCount == 0) return ByteArray(0)

        ensureInCapacity(inCount + pairCount)
        var idx = inCount
        val maxByteIdx = pairCount * 2
        for (i in 0 until maxByteIdx step 2) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt() shl 8
            inBuffer[idx++] = (high or low).toShort().toFloat()
        }
        inCount += pairCount

        return runWsola()
    }

    private fun ensureInCapacity(required: Int) {
        if (inBuffer.size >= required) return
        // Geometric growth so a steady stream stops reallocating almost immediately.
        var capacity = if (inBuffer.isEmpty()) max(required, frame * 4) else inBuffer.size
        while (capacity < required) capacity *= 2
        inBuffer = inBuffer.copyOf(capacity)
    }

    private fun ensureOutCapacity(required: Int) {
        if (outBuffer.size >= required) return
        var capacity = if (outBuffer.isEmpty()) max(required, hop * 8) else outBuffer.size
        while (capacity < required) capacity *= 2
        outBuffer = outBuffer.copyOf(capacity)
    }

    private fun drainPassthrough(): ByteArray {
        val out = if (inCount > 0) {
            val start = pos.toInt()
            if (start in 0 until inCount) {
                floatArrayToShortBytes(inBuffer, start, inCount - start)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        reset()
        return out
    }

    fun reset() {
        inCount = 0
        outCount = 0
        hasTail = false
        pos = 0f
    }

    fun finish(): ByteArray {
        if (isClosed) return ByteArray(0)
        if (abs(speed - 1.0f) < 0.001f) {
            return drainPassthrough()
        }

        // Future silence lets WSOLA emit the final speech frame.
        val padding = ByteArray((sampleRate / 8) * 2) // ~125ms of silence
        val outBytes = process(padding)
        reset()
        return trimSyntheticPadding(outBytes)
    }

    /**
     * Drop the padding [finish] just fed itself, keeping a short tail (ledger LOG-38).
     *
     * The silence above is an input, not audio the model produced, and returning it means every
     * AI turn at a non-unity speed ends with up to an eighth of a second of dead air before the
     * microphone reopens. To a learner that reads as the app being slow to listen, and it is
     * longest exactly where it is least wanted — the slower the speed, the longer the padding
     * stretches. Ported from the desktop's speed_control.finish, which has always trimmed it.
     */
    internal fun trimSyntheticPadding(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return ByteArray(0)
        var lastActive = -1
        for (index in 0 until sampleCount) {
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            if (abs(sample) > FINISH_SILENCE_FLOOR) lastActive = index
        }
        // Nothing above the floor at all: the flush produced no speech, so it is
        // all padding. Returning it would be dead air with nothing in front of it.
        if (lastActive < 0) return ByteArray(0)
        val tail = maxOf(1, sampleRate / 100) // ~10ms, so the last frame is not clipped
        val end = minOf(sampleCount, lastActive + tail)
        return pcm.copyOfRange(0, end * 2)
    }

    private fun runWsola(): ByteArray {
        outCount = 0

        while (true) {
            val base = pos.roundToInt()

            if (!hasTail) {
                if (base + frame > inCount) break
                // Emit the frame's first half and keep its second half as the next cross-fade tail.
                appendOut(inBuffer, base, hop)
                System.arraycopy(inBuffer, base + hop, tail, 0, hop)
                hasTail = true

                pos += hop * speed
                continue
            }

            var lo = base - tol
            val hi = base + frame + tol
            if (lo < 0) lo = 0
            if (hi > inCount) break

            var bestOffset = 0
            var maxCorr = -Double.MAX_VALUE

            val searchLen = hi - lo - frame
            if (searchLen > 0) {
                for (offset in 0..searchLen) {
                    var corr = 0.0
                    val segStart = lo + offset
                    for (i in 0 until hop) {
                        corr += inBuffer[segStart + i] * tail[i]
                    }
                    if (corr > maxCorr) {
                        maxCorr = corr
                        bestOffset = offset
                    }
                }
            }

            // start + frame <= hi <= inCount (bestOffset <= searchLen), so both reads below are
            // inside the pending input.
            val start = lo + bestOffset
            ensureOutCapacity(outCount + hop)
            for (i in 0 until hop) {
                outBuffer[outCount + i] = tail[i] * fadeDown[i] + inBuffer[start + i] * fadeUp[i]
            }
            outCount += hop
            // Only after the cross-fade has consumed the old tail.
            System.arraycopy(inBuffer, start + hop, tail, 0, hop)

            pos += hop * speed
        }

        var keepFrom = pos.toInt() - tol
        if (keepFrom > 0) {
            keepFrom = min(keepFrom, inCount)
            val remain = inCount - keepFrom
            // Compacted in place — destination 0 is below the source offset, so the copy is safe.
            System.arraycopy(inBuffer, keepFrom, inBuffer, 0, remain)
            inCount = remain
            pos -= keepFrom
        }

        if (outCount == 0) return ByteArray(0)
        return floatArrayToShortBytes(outBuffer, 0, outCount)
    }

    private fun appendOut(source: FloatArray, sourceOffset: Int, length: Int) {
        ensureOutCapacity(outCount + length)
        System.arraycopy(source, sourceOffset, outBuffer, outCount, length)
        outCount += length
    }

    private fun floatArrayToShortBytes(arr: FloatArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length * 2)
        var idx = 0
        for (n in 0 until length) {
            var v = arr[offset + n].toInt()
            if (v > 32767) v = 32767
            if (v < -32768) v = -32768
            val s = v.toShort()
            out[idx++] = (s.toInt() and 0xFF).toByte()
            out[idx++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
