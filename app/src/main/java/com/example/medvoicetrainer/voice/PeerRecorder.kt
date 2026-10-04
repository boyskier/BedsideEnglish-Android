package com.example.medvoicetrainer.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a whole in-person role-play (Korean CPX peer mode) to a compressed ADTS AAC file — about
 * 3 MB for a 12-minute station, small enough to send inline to Gemini as `audio/aac`. Unlike the
 * live voice path this never streams: it only captures, and the file is transcribed afterwards.
 *
 * The caller must hold RECORD_AUDIO. Not thread-safe; drive it from one place (the screen).
 */
class PeerRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
        stopQuietly()
        val target = File(context.cacheDir, "kmle_peer_${System.currentTimeMillis()}.aac")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            // MIC rather than VOICE_RECOGNITION: two people sit on either side of the phone, and
            // the far voice should not be gated out as background.
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            throw e
        }
        recorder = r
        file = target
        return target
    }

    /** Stop and return the recording, or null when nothing usable was captured. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val out = file
        return if (ok && out != null && out.length() > 1_000) out else null
    }

    fun stopQuietly() {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
    }

    fun discard() {
        stopQuietly()
        file?.delete()
        file = null
    }
}
