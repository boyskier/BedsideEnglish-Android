package com.example.medvoicetrainer.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Short single-attempt microphone recorder for pronunciation practice (Shadowing
 * record-and-check, SRS speaking review). Wraps its own [AudioIO] so it never touches the
 * live-session capture path; produces 16 kHz PCM16 mono, capped at [MAX_SECONDS], in the same
 * shape [com.example.medvoicetrainer.analysis.SpeakingAttemptJudge] and Gemini expect.
 */
class AttemptRecorder {
    companion object {
        const val SAMPLE_RATE = AudioIO.RECORD_SAMPLE_RATE
        const val MAX_SECONDS = 45
        private const val MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
    }

    private val audioIO = AudioIO()
    private val buffer = java.io.ByteArrayOutputStream()

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Start capturing. Returns false if the microphone could not start.
     *
     * [onLevel] receives the normalized 0f..1f loudness of each captured chunk (~every 20–40 ms) so
     * callers can drive a live mic waveform; it is invoked on the capture thread and its failures are
     * swallowed, since a meter must never be able to kill the recording it is metering.
     *
     * Captures through [MediaRecorder.AudioSource.VOICE_RECOGNITION] with the platform's speech
     * effects attached (falling back to a plain MIC capture where the device has neither). That is
     * the same input path a dictation app uses, and it is what lets a single phrase recorded on the
     * built-in mic — held at arm's length, no earphones, ordinary room noise — still reach the
     * intelligibility judge as recognizable speech.
     */
    fun start(onLevel: ((Float) -> Unit)? = null, onError: (String) -> Unit = {}): Boolean {
        if (isRecording) return false
        synchronized(buffer) { buffer.reset() }
        val result = audioIO.startRecording(
            onChunkReceived = { chunk ->
                synchronized(buffer) {
                    val remaining = MAX_BYTES - buffer.size()
                    if (remaining > 0) buffer.write(chunk, 0, minOf(chunk.size, remaining))
                }
                onLevel?.let { report -> runCatching { report(pcm16MicLevel(chunk)) } }
            },
            onError = { message ->
                isRecording = false
                onError(message)
            },
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            enhanceForSpeech = true,
        )
        isRecording = result.isSuccess
        return isRecording
    }

    /** Stop capturing and return the recorded PCM (possibly empty). */
    fun stop(): ByteArray {
        audioIO.stopRecording()
        isRecording = false
        synchronized(buffer) {
            val pcm = buffer.toByteArray()
            buffer.reset()
            return pcm
        }
    }

    /** Stop capturing and discard whatever was recorded. */
    fun cancel() {
        audioIO.stopRecording()
        isRecording = false
        synchronized(buffer) { buffer.reset() }
    }
}

/**
 * One-shot playback of a recorded attempt (static-mode AudioTrack at the recorder's own sample
 * rate — AudioIO's streaming track is fixed at the 24 kHz provider playback rate and can't be
 * reused here). Returns the playing track so the caller can stop/release it, or null on failure.
 */
fun playAttemptPcm(
    pcm: ByteArray,
    sampleRate: Int = AttemptRecorder.SAMPLE_RATE,
    onComplete: () -> Unit = {},
): AudioTrack? {
    if (pcm.size < 2) return null
    return try {
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            pcm.size,
            AudioTrack.MODE_STATIC,
        )
        if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        val written = track.write(pcm, 0, pcm.size)
        if (written <= 0) {
            runCatching { track.release() }
            return null
        }
        track.setNotificationMarkerPosition(written / 2)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) = onComplete()
            override fun onPeriodicNotification(t: AudioTrack?) = Unit
        })
        track.play()
        track
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }
}
