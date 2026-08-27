package com.example.medvoicetrainer.voice

import com.example.medvoicetrainer.analysis.ShadowingEngine
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.api.OpenAIService
import java.io.ByteArrayOutputStream

/**
 * Ported from app/voice/dictation.py — push-to-talk speech-to-text for chat-style windows
 * (e.g. the Debrief-with-Tutor voice mode). STT provider is chosen from whichever key the user
 * has configured: OpenAI's dedicated transcription model first, otherwise Gemini audio
 * understanding. Claude has no audio input, so an Anthropic-only setup has no STT backend.
 */
object Dictation {

    /** OpenAI tts-1 accepts up to 4096 input characters; debrief replies are 1-2 paragraphs. */
    private const val TTS_MAX_CHARS = 4000

    const val DICTATION_SAMPLE_RATE = 16000
    const val MIN_DICTATION_SECONDS = 0.4
    const val MAX_DICTATION_BYTES = 16000 * 2 * 300 // ~5 min at 16 kHz PCM16

    private const val TRANSCRIBE_INSTRUCTION =
        "Transcribe this audio recording verbatim. The speaker is a non-native " +
            "English speaker (a medical professional) and may occasionally mix in " +
            "words from their native language — transcribe those words as spoken. " +
            "Return ONLY the transcription text with no commentary, no labels, and " +
            "no quotation marks. If the audio contains no intelligible speech, " +
            "return an empty string."

    /** Wrap raw PCM16 mono in an in-memory WAV container (44-byte canonical header). */
    fun pcm16ToWavBytes(pcm: ByteArray, sampleRate: Int = DICTATION_SAMPLE_RATE): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        val byteRate = sampleRate * 1 * 16 / 8
        val blockAlign = 1 * 16 / 8
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeLeInt(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun writeLeShort(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }

        out.write("RIFF".toByteArray())
        writeLeInt(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeLeInt(16) // PCM header size
        writeLeShort(1) // PCM format
        writeLeShort(1) // mono
        writeLeInt(sampleRate)
        writeLeInt(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(16) // bits per sample
        out.write("data".toByteArray())
        writeLeInt(dataSize)
        out.write(pcm)

        return out.toByteArray()
    }

    /** Pick a transcription provider from the keys the user has configured. */
    fun resolveSttBackend(openAiApiKey: String, geminiApiKey: String): String? {
        if (openAiApiKey.isNotBlank()) return "openai"
        if (geminiApiKey.isNotBlank()) return "gemini"
        return null
    }

    /** Transcribe raw PCM16 mono speech to text. Throws IllegalArgumentException when unusable. */
    suspend fun transcribePcm16(
        pcm: ByteArray,
        sampleRate: Int = DICTATION_SAMPLE_RATE,
        openAiApiKey: String,
        geminiApiKey: String,
        backend: String? = null
    ): String {
        if (pcm.isEmpty() || pcm.size < (MIN_DICTATION_SECONDS * sampleRate * 2).toInt()) {
            throw IllegalArgumentException("Recording too short — hold the mic button and speak.")
        }
        val trimmed = if (pcm.size > MAX_DICTATION_BYTES) pcm.copyOf(MAX_DICTATION_BYTES) else pcm
        val resolvedBackend = backend ?: resolveSttBackend(openAiApiKey, geminiApiKey)
            ?: throw IllegalArgumentException(
                "Voice input needs an OpenAI or Gemini API key for speech recognition. Add one in Preferences."
            )

        val wavBytes = pcm16ToWavBytes(trimmed, sampleRate)
        return if (resolvedBackend == "openai") {
            OpenAIService.transcribeAudio(openAiApiKey, wavBytes)
        } else {
            try {
                com.example.medvoicetrainer.api.withGeminiAudioFallback("gemini-3.5-flash") { m ->
                    GeminiService.generateContentWithAudio(
                        geminiApiKey,
                        m,
                        wavBytes,
                        sampleRate,
                        TRANSCRIBE_INSTRUCTION,
                        mimeType = "audio/wav"
                    )
                }.trim()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * Synthesize a tutor reply -> (PCM16 mono bytes, sample_rate) or null.
     * Backend selection mirrors the shadowing tab's `tts_backend` setting; unlike the shadowing
     * tab's 500-char snippet, the whole reply is kept (clamped to [TTS_MAX_CHARS]) since a
     * debrief reply is 1-2 paragraphs the shadowing 500-char cut would truncate mid-sentence.
     * "windows" (the Python default, via pyttsx3) has no Android equivalent — the caller should
     * route that case to a native android.speech.tts.TextToSpeech implementation instead.
     */
    suspend fun synthesizeReply(
        ttsBackend: String,
        openAiApiKey: String,
        geminiApiKey: String,
        text: String
    ): Pair<ByteArray, Int>? {
        val trimmed = text.trim().take(TTS_MAX_CHARS)
        if (trimmed.isEmpty()) return null
        return when (ttsBackend) {
            // Unlike ShadowingEngine.synthesizeOpenai (500-char snippets), a debrief reply
            // keeps its full length (already clamped to TTS_MAX_CHARS above).
            "openai" -> if (openAiApiKey.isBlank()) null else try {
                OpenAIService.synthesizeSpeech(openAiApiKey, trimmed) to 24000
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                null
            }
            "gemini" -> ShadowingEngine.synthesizeGemini(geminiApiKey, trimmed)
            else -> null
        }
    }

    fun appendSpeechSegment(existing: String, newSegment: String): String {
        if (existing.isBlank()) return newSegment.trim()
        if (newSegment.isBlank()) return existing.trim()
        val e = existing.trimEnd()
        val n = newSegment.trimStart()
        if (n.firstOrNull()?.isLetterOrDigit() == true && e.lastOrNull()?.isLetterOrDigit() == true) {
            return "$e $n"
        }
        if (e.endsWith("-") || e.endsWith(" ") || n.startsWith(" ") || n.startsWith(",") || n.startsWith(".") || n.startsWith("?") || n.startsWith("!")) {
            return "$e$n"
        }
        return "$e $n"
    }

    private var timeoutCancelledOnStop: Boolean = false

    fun stop() {
        timeoutCancelledOnStop = true
    }

    fun isSilenceTimeoutCancelled(): Boolean = timeoutCancelledOnStop
}
