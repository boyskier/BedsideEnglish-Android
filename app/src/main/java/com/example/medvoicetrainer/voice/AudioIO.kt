package com.example.medvoicetrainer.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class AudioIO {
    private enum class RecordingState { IDLE, STARTING, RECORDING, STOPPING }

    companion object {
        const val RECORD_SAMPLE_RATE = 16000
        const val PLAYBACK_SAMPLE_RATE = 24000
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val RECORDING_THREAD_JOIN_MILLIS = 750L
        private const val RECORDING_THREAD_INTERRUPT_JOIN_MILLIS = 250L
    }

    private val recordingLock = Any()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var recordingState = RecordingState.IDLE
    private var recordingGeneration = 0L
    @Volatile
    private var isRecording = false
    @Volatile
    private var voiceEffects: List<AudioEffect> = emptyList()

    /**
     * [audioSource] falls back to [MediaRecorder.AudioSource.MIC] whenever the requested source
     * cannot be opened, so a device without it still records.
     *
     * [enhanceForSpeech] attaches the platform's echo canceller / noise suppressor / automatic gain
     * control to the capture session when the device offers them. That is what makes a single-phrase
     * attempt recorded on the built-in mic — no headset, speaker a few centimetres from the mic —
     * usable by a speech recognizer; the live-session path leaves it off and keeps the raw stream
     * the realtime provider does its own processing on.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        onChunkReceived: (ByteArray) -> Unit,
        onError: (String) -> Unit = {},
        audioSource: Int = MediaRecorder.AudioSource.MIC,
        enhanceForSpeech: Boolean = false,
    ): Result<Unit> {
        val generation = synchronized(recordingLock) {
            if (recordingState != RecordingState.IDLE) {
                return Result.failure(IllegalStateException("Microphone recording is already running"))
            }
            recordingState = RecordingState.STARTING
            recordingGeneration += 1L
            recordingGeneration
        }
        val minBufferSize = try {
            AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE, CHANNELS, AUDIO_ENCODING)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            finishStarting(generation)
            return Result.failure(IllegalStateException("Could not query microphone buffer size", error))
        }
        if (minBufferSize <= 0) {
            finishStarting(generation)
            return Result.failure(
                IllegalStateException("Microphone does not support 16 kHz mono PCM (code $minBufferSize)"),
            )
        }

        val sources = listOf(audioSource, MediaRecorder.AudioSource.MIC).distinct()
        var opened: AudioRecord? = null
        var lastFailure: Exception? = null
        for (source in sources) {
            val candidate = try {
                AudioRecord(source, RECORD_SAMPLE_RATE, CHANNELS, AUDIO_ENCODING, minBufferSize * 2)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                lastFailure = IllegalStateException("Could not create the microphone recorder", error)
                continue
            }
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                lastFailure = IllegalStateException("Android could not initialize the microphone")
                continue
            }
            try {
                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Android did not enter the recording state")
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                runCatching { candidate.stop() }
                runCatching { candidate.release() }
                lastFailure = IllegalStateException("Could not start microphone recording", error)
                continue
            }
            opened = candidate
            break
        }
        val recorder = opened
        if (recorder == null) {
            finishStarting(generation)
            return Result.failure(lastFailure ?: IllegalStateException("Could not start microphone recording"))
        }
        if (enhanceForSpeech) voiceEffects = attachVoiceEffects(recorder.audioSessionId)

        val reader = Thread(
            { readMicrophone(recorder, minBufferSize, onChunkReceived, onError) },
            "MedVoiceTrainer-Microphone",
        ).apply { isDaemon = true }

        val accepted = synchronized(recordingLock) {
            if (recordingState == RecordingState.STARTING && recordingGeneration == generation) {
                audioRecord = recorder
                recordingThread = reader
                recordingState = RecordingState.RECORDING
                isRecording = true
                true
            } else {
                false
            }
        }
        if (!accepted) {
            closeRecorder(recorder)
            finishStarting(generation)
            return Result.failure(IllegalStateException("Microphone start was cancelled"))
        }

        return try {
            reader.start()
            val stillOwned = synchronized(recordingLock) {
                recordingState == RecordingState.RECORDING &&
                    audioRecord === recorder && recordingThread === reader
            }
            if (stillOwned) Result.success(Unit)
            else Result.failure(IllegalStateException("Microphone start was cancelled"))
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            synchronized(recordingLock) {
                if (audioRecord === recorder) {
                    audioRecord = null
                    recordingThread = null
                    isRecording = false
                    recordingState = RecordingState.STOPPING
                }
            }
            closeRecorder(recorder)
            finishStopping()
            Result.failure(IllegalStateException("Could not start the microphone reader", error))
        }
    }

    fun stopRecording() {
        val (recorder, reader) = synchronized(recordingLock) {
            when (recordingState) {
                RecordingState.IDLE -> return
                RecordingState.STARTING -> {
                    // Invalidate initialization. The starter observes this before publishing the
                    // recorder, closes it, and returns the state to IDLE.
                    recordingGeneration += 1L
                    recordingState = RecordingState.STOPPING
                    isRecording = false
                    return
                }
                RecordingState.STOPPING -> return
                RecordingState.RECORDING -> Unit
            }
            recordingGeneration += 1L
            recordingState = RecordingState.STOPPING
            isRecording = false
            val current = audioRecord
            val currentThread = recordingThread
            audioRecord = null
            recordingThread = null
            current to currentThread
        }
        // stop() unblocks a native read. Release only after the reader has exited so release()
        // never races AudioRecord.read() on another thread.
        recorder?.let(::requestRecorderStop)
        if (reader != null && reader !== Thread.currentThread()) {
            runCatching { reader.join(RECORDING_THREAD_JOIN_MILLIS) }
            if (reader.isAlive) {
                reader.interrupt()
                runCatching { reader.join(RECORDING_THREAD_INTERRUPT_JOIN_MILLIS) }
            }
        }
        recorder?.let(::releaseRecorder)
        finishStopping()
    }

    private fun finishStarting(generation: Long) {
        synchronized(recordingLock) {
            if (recordingState == RecordingState.STARTING && recordingGeneration == generation) {
                recordingState = RecordingState.IDLE
            } else if (recordingState == RecordingState.STOPPING && audioRecord == null) {
                recordingState = RecordingState.IDLE
            }
        }
    }

    private fun finishStopping() {
        synchronized(recordingLock) {
            if (recordingState == RecordingState.STOPPING && audioRecord == null) {
                recordingThread = null
                recordingState = RecordingState.IDLE
            }
        }
    }

    private fun readMicrophone(
        recorder: AudioRecord,
        bufferSize: Int,
        onChunkReceived: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize)
        while (isCurrentRecorder(recorder)) {
            val readSize = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                failRecorder(recorder, "Microphone read failed: ${error.message ?: error.javaClass.simpleName}", onError)
                return
            }
            when {
                readSize > 0 -> {
                    try {
                        onChunkReceived(buffer.copyOf(readSize))
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                        failRecorder(
                            recorder,
                            "Microphone processing failed: ${error.message ?: error.javaClass.simpleName}",
                            onError,
                        )
                        return
                    }
                }
                readSize == 0 -> {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                else -> {
                    failRecorder(recorder, audioReadFailureMessage(readSize), onError)
                    return
                }
            }
        }
    }

    private fun isCurrentRecorder(recorder: AudioRecord): Boolean = synchronized(recordingLock) {
        isRecording && audioRecord === recorder
    }

    private fun failRecorder(recorder: AudioRecord, message: String, onError: (String) -> Unit) {
        val ownsRecorder = synchronized(recordingLock) {
            if (recordingState != RecordingState.RECORDING || audioRecord !== recorder) {
                false
            } else {
                recordingGeneration += 1L
                recordingState = RecordingState.STOPPING
                isRecording = false
                audioRecord = null
                if (recordingThread === Thread.currentThread()) recordingThread = null
                true
            }
        }
        if (!ownsRecorder) return
        closeRecorder(recorder)
        finishStopping()
        runCatching { onError(message) }
    }

    private fun closeRecorder(recorder: AudioRecord) {
        requestRecorderStop(recorder)
        releaseRecorder(recorder)
    }

    private fun requestRecorderStop(recorder: AudioRecord) {
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        releaseVoiceEffects()
        runCatching { recorder.release() }
    }

    /**
     * Best-effort: every one of these is optional hardware/DSP that many devices simply do not
     * expose, and a failure to attach one must never stop the recording. AGC is what rescues a
     * quiet far-field attempt; NS and AEC are what keep room noise and the phone's own speaker
     * from swamping it when the learner practises without earphones.
     */
    private fun attachVoiceEffects(sessionId: Int): List<AudioEffect> {
        if (sessionId == AudioRecord.ERROR || sessionId == AudioRecord.ERROR_BAD_VALUE) return emptyList()
        val effects = mutableListOf<AudioEffect>()
        fun add(available: () -> Boolean, create: () -> AudioEffect?) {
            runCatching {
                if (!available()) return
                val effect = create() ?: return
                if (effect.setEnabled(true) == AudioEffect.SUCCESS) effects += effect
                else runCatching { effect.release() }
            }
        }
        add({ AcousticEchoCanceler.isAvailable() }) { AcousticEchoCanceler.create(sessionId) }
        add({ NoiseSuppressor.isAvailable() }) { NoiseSuppressor.create(sessionId) }
        add({ AutomaticGainControl.isAvailable() }) { AutomaticGainControl.create(sessionId) }
        return effects
    }

    private fun releaseVoiceEffects() {
        val effects = voiceEffects
        if (effects.isEmpty()) return
        voiceEffects = emptyList()
        effects.forEach { effect ->
            runCatching { effect.setEnabled(false) }
            runCatching { effect.release() }
        }
    }

    @Synchronized
    fun playAudio(pcmData: ByteArray): Result<Unit> {
        if (pcmData.isEmpty()) return Result.success(Unit)
        return runCatching {
            val track = audioTrack ?: createPlaybackTrack().also { audioTrack = it }
            var offset = 0
            while (offset < pcmData.size) {
                val written = track.write(pcmData, offset, pcmData.size - offset)
                if (written <= 0) {
                    throw IllegalStateException(audioWriteFailureMessage(written))
                }
                offset += written
            }
        }.onFailure {
            val failedTrack = audioTrack
            audioTrack = null
            failedTrack?.let(::releasePlaybackTrack)
        }
    }

    private fun createPlaybackTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("Patient audio output is unavailable (buffer code $minBufferSize)")
        }
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
            AudioTrack.MODE_STREAM,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            releasePlaybackTrack(track)
            throw IllegalStateException("Android could not initialize patient audio playback")
        }
        try {
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("Android did not start patient audio playback")
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            releasePlaybackTrack(track)
            throw error
        }
        return track
    }

    /** Drop queued model speech immediately while keeping the stream reusable for the next turn. */
    @Synchronized
    fun clearPlaybackQueue() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: IllegalStateException) {
            // A concurrent lifecycle stop may already have released the track.
        }
    }

    @Synchronized
    fun stopPlayback() {
        val track = audioTrack
        audioTrack = null
        track?.let(::releasePlaybackTrack)
    }

    private fun releasePlaybackTrack(track: AudioTrack) {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

}

/** Convert AudioRecord.read()'s negative result into a stable, user-facing diagnostic. */
internal fun audioReadFailureMessage(code: Int): String = when (code) {
    AudioRecord.ERROR_DEAD_OBJECT -> "Microphone disconnected while recording"
    AudioRecord.ERROR_INVALID_OPERATION -> "Microphone is no longer in a valid recording state"
    AudioRecord.ERROR_BAD_VALUE -> "Microphone returned an invalid audio buffer error"
    AudioRecord.ERROR -> "Android reported a microphone read error"
    else -> "Microphone stopped with audio read error $code"
}

/** Convert AudioTrack.write()'s non-positive result into a stable diagnostic. */
internal fun audioWriteFailureMessage(code: Int): String = when (code) {
    AudioTrack.ERROR_DEAD_OBJECT -> "Patient audio output disconnected"
    AudioTrack.ERROR_INVALID_OPERATION -> "Patient audio output is not in a valid playback state"
    AudioTrack.ERROR_BAD_VALUE -> "Patient audio output rejected the audio buffer"
    AudioTrack.ERROR -> "Android reported a patient audio playback error"
    else -> "Patient audio playback stopped with write error $code"
}
