package com.example.medvoicetrainer.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

data class LearnerAudioClip(
    val relativePath: String,
    val durationMs: Long,
)

/** Stores learner-only turns as small, app-private AAC/M4A clips. */
class LearnerAudioStore(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
        const val MAX_TURN_SECONDS = 120
        private const val BYTES_PER_SAMPLE = 2
        private const val AUDIO_DIR = "learner_audio"
        private const val DRILL_DIR = "drills"
        private const val SILENCE_FRAME_MS = 20
        private const val SILENCE_RMS_THRESHOLD = 120.0
        private const val EDGE_PADDING_MS = 180
        private const val CODEC_TIMEOUT_US = 10_000L

        internal fun trimEdgeSilence(pcm: ByteArray): ByteArray {
            val usableSize = pcm.size - (pcm.size % BYTES_PER_SAMPLE)
            if (usableSize <= 0) return ByteArray(0)
            val samplesPerFrame = SAMPLE_RATE * SILENCE_FRAME_MS / 1_000
            val bytesPerFrame = samplesPerFrame * BYTES_PER_SAMPLE
            val paddingBytes = (SAMPLE_RATE * EDGE_PADDING_MS / 1_000) * BYTES_PER_SAMPLE
            var firstVoiced = -1
            var lastVoicedEnd = -1
            var frameStart = 0
            while (frameStart < usableSize) {
                val frameEnd = minOf(usableSize, frameStart + bytesPerFrame)
                var sumSquares = 0L
                var samples = 0
                var index = frameStart
                while (index + 1 < frameEnd) {
                    val low = pcm[index].toInt() and 0xff
                    val high = pcm[index + 1].toInt()
                    val sample = ((high shl 8) or low).toShort().toInt()
                    sumSquares += sample.toLong() * sample.toLong()
                    samples += 1
                    index += 2
                }
                val rms = if (samples == 0) 0.0 else sqrt(sumSquares.toDouble() / samples)
                if (rms >= SILENCE_RMS_THRESHOLD) {
                    if (firstVoiced < 0) firstVoiced = frameStart
                    lastVoicedEnd = frameEnd
                }
                frameStart = frameEnd
            }
            if (firstVoiced < 0) return ByteArray(0)
            val start = (firstVoiced - paddingBytes).coerceAtLeast(0).let { it - (it % 2) }
            val end = (lastVoicedEnd + paddingBytes).coerceAtMost(usableSize).let { it - (it % 2) }
            return pcm.copyOfRange(start, end)
        }

        /**
         * Directory name for one drill slot. The readable prefix keeps the folder greppable while
         * the hash suffix keeps two phrases that sanitize to the same prefix apart.
         */
        internal fun slotDirName(slotId: String): String {
            val readable = slotId.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(40)
            val suffix = Integer.toHexString(slotId.hashCode())
            return if (readable.isEmpty()) "slot_$suffix" else "${readable}_$suffix"
        }
    }

    fun saveTurn(sessionId: Int, transcriptIndex: Int, rawPcm: ByteArray): LearnerAudioClip? {
        val maxBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_TURN_SECONDS
        val bounded = if (rawPcm.size > maxBytes) rawPcm.copyOf(maxBytes) else rawPcm
        val pcm = trimEdgeSilence(bounded)
        if (pcm.isEmpty()) return null

        val sessionDir = File(context.filesDir, "$AUDIO_DIR/session_$sessionId").apply { mkdirs() }
        val output = File(sessionDir, "turn_${transcriptIndex.toString().padStart(3, '0')}.m4a")
        return try {
            encodeAacM4a(pcm, output)
            LearnerAudioClip(
                relativePath = output.relativeTo(context.filesDir).invariantSeparatorsPath,
                durationMs = (pcm.size / BYTES_PER_SAMPLE) * 1_000L / SAMPLE_RATE,
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            output.delete()
            null
        }
    }

    /**
     * Save one short practice take that belongs to a drill slot rather than a session turn (Say It
     * listen-and-repeat). Kept under the same `learner_audio` root as session clips so the existing
     * "save my voice clips" switch, backup/restore, and [resolve]'s containment check cover it
     * unchanged; [recordedAt] names the file so takes of one phrase stay ordered on disk.
     */
    fun saveDrillTake(
        slotId: String,
        rawPcm: ByteArray,
        recordedAt: Long = System.currentTimeMillis(),
    ): LearnerAudioClip? {
        if (slotId.isBlank()) return null
        val maxBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_TURN_SECONDS
        val bounded = if (rawPcm.size > maxBytes) rawPcm.copyOf(maxBytes) else rawPcm
        val pcm = trimEdgeSilence(bounded)
        if (pcm.isEmpty()) return null

        val slotDir = File(context.filesDir, "$AUDIO_DIR/$DRILL_DIR/${slotDirName(slotId)}").apply { mkdirs() }
        val output = File(slotDir, "take_$recordedAt.m4a")
        return try {
            encodeAacM4a(pcm, output)
            LearnerAudioClip(
                relativePath = output.relativeTo(context.filesDir).invariantSeparatorsPath,
                durationMs = (pcm.size / BYTES_PER_SAMPLE) * 1_000L / SAMPLE_RATE,
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            output.delete()
            null
        }
    }

    /** Delete one clip by its stored relative path (containment-checked by [resolve]). */
    fun deleteClip(relativePath: String) {
        resolve(relativePath)?.delete()
    }

    fun resolve(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val filesDir = context.filesDir ?: return null
        return runCatching {
            val root = File(filesDir, AUDIO_DIR).canonicalFile
            val candidate = File(filesDir, relativePath).canonicalFile
            candidate.takeIf {
                it.isFile && it.path.startsWith(root.path + File.separator)
            }
        }.getOrNull()
    }

    fun deleteAudioReferencedBy(rawTranscript: String) {
        runCatching {
            val turns = JSONArray(rawTranscript.ifBlank { "[]" })
            for (index in 0 until turns.length()) {
                val path = turns.optJSONObject(index)?.optString("learner_audio_path").orEmpty()
                resolve(path)?.delete()
            }
        }
    }

    private fun encodeAacM4a(pcm: ByteArray, output: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        inputBuffer.clear()
                        val remaining = pcm.size - inputOffset
                        if (remaining <= 0) {
                            val pts = (inputOffset / BYTES_PER_SAMPLE) * 1_000_000L / SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            var size = minOf(inputBuffer.remaining(), remaining)
                            size -= size % BYTES_PER_SAMPLE
                            inputBuffer.put(pcm, inputOffset, size)
                            val pts = (inputOffset / BYTES_PER_SAMPLE) * 1_000_000L / SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            inputOffset += size
                        }
                    }
                }

                var draining = true
                while (draining) {
                    val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed twice" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxerStarted && outputBuffer != null) { "AAC muxer was not ready" }
                                outputBuffer!!.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (outputDone) draining = false
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
