package com.example.medvoicetrainer.voice

import android.content.Context
import com.example.medvoicetrainer.analysis.PronunciationAudioQuality
import com.example.medvoicetrainer.analysis.PronunciationAudioQualityAnalyzer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Short-lived WAV spool for post-session pronunciation analysis. Files live in cache, are never
 * added to the transcript/database, and are deleted after successful analysis or session cancel.
 */
class PronunciationAudioStore(private val context: Context) {

    data class SpoolResult(
        val file: File?,
        val quality: PronunciationAudioQuality
    )

    fun saveTurn(sessionId: Int, transcriptIndex: Int, rawPcm: ByteArray): File? {
        return spoolTurn(sessionId, transcriptIndex, rawPcm).file
    }

    fun spoolTurn(sessionId: Int, transcriptIndex: Int, rawPcm: ByteArray): SpoolResult {
        val maxBytes = LearnerAudioStore.SAMPLE_RATE * 2 * LearnerAudioStore.MAX_TURN_SECONDS
        val bounded = if (rawPcm.size > maxBytes) rawPcm.copyOf(maxBytes) else rawPcm
        val pcm = LearnerAudioStore.trimEdgeSilence(bounded)
        val quality = PronunciationAudioQualityAnalyzer.analyze(pcm, LearnerAudioStore.SAMPLE_RATE)
        if (pcm.size < MIN_PCM_BYTES || !quality.accepted) return SpoolResult(null, quality)

        val sessionDir = sessionDirectory(sessionId).apply { mkdirs() }
        val output = File(sessionDir, "turn_${transcriptIndex.toString().padStart(3, '0')}.wav")
        val file = try {
            FileOutputStream(output).use { stream ->
                stream.write(wavHeader(pcm.size))
                stream.write(pcm)
            }
            output
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            output.delete()
            null
        }
        return SpoolResult(file, quality)
    }

    fun cleanupSession(sessionId: Int) {
        val dir = sessionDirectory(sessionId)
        if (!dir.exists()) return
        // Targets are fixed children under cacheDir; avoid a broad recursive target.
        dir.listFiles()?.forEach { child -> if (child.isFile) child.delete() }
        dir.delete()
    }

    private fun sessionDirectory(sessionId: Int): File =
        File(File(context.cacheDir, CACHE_DIR), "session_${sessionId.coerceAtLeast(0)}")

    private fun wavHeader(pcmBytes: Int): ByteArray = ByteBuffer.allocate(44)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(1.toShort()) // mono
            putInt(LearnerAudioStore.SAMPLE_RATE)
            putInt(LearnerAudioStore.SAMPLE_RATE * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes)
        }
        .array()

    companion object {
        private const val CACHE_DIR = "pronunciation_analysis"
        private const val MIN_PCM_BYTES = 3_200
    }
}
