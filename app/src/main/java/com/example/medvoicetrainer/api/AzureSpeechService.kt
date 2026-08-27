package com.example.medvoicetrainer.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

data class AzureBlindTranscription(
    val text: String,
    val confidence: Double,
    val uncertainWords: List<String>
)

data class AzurePhonemeEvidence(
    val phoneme: String,
    val accuracy: Double?
)

data class AzureWordEvidence(
    val word: String,
    val accuracy: Double?,
    val errorType: String,
    val startMs: Int?,
    val endMs: Int?,
    val phonemes: List<AzurePhonemeEvidence>
)

/**
 * Deliberately excludes Azure's overall PronScore, FluencyScore, CompletenessScore and
 * ProsodyScore. Those values reward native-reference similarity; this app uses the specialist
 * response only to locate a listening blocker after blind STT has already failed.
 */
data class AzurePronunciationEvidence(
    val recognizedText: String,
    val words: List<AzureWordEvidence>
)

object AzureSpeechService {
    private const val BLIND_MAX_SECONDS = 60
    private const val DIAGNOSTIC_MAX_SECONDS = 30
    private val REGION_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")

    internal fun normalizeRegion(region: String): String {
        val normalized = region.trim().lowercase(Locale.ROOT)
        require(REGION_PATTERN.matches(normalized)) { "Azure Speech region is invalid" }
        return normalized
    }

    internal fun endpoint(region: String, language: String = "en-US"): String {
        val safeRegion = normalizeRegion(region)
        val safeLanguage = URLEncoder.encode(language, StandardCharsets.UTF_8.name())
        return "https://$safeRegion.stt.speech.microsoft.com/" +
            "speech/recognition/conversation/cognitiveservices/v1" +
            "?language=$safeLanguage&format=detailed"
    }

    internal fun pronunciationAssessmentHeader(referenceText: String): String {
        val config = JSONObject()
            .put("ReferenceText", referenceText.take(1_000))
            .put("GradingSystem", "HundredMark")
            .put("Granularity", "Phoneme")
            .put("Dimension", "Comprehensive")
            .put("EnableMiscue", true)
            .put("EnableProsodyAssessment", false)
            .put("PhonemeAlphabet", "IPA")
            .put("NBestPhonemeCount", 3)
        return Base64.getEncoder().encodeToString(
            config.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    suspend fun transcribeBlind(
        subscriptionKey: String,
        region: String,
        audioBytes: ByteArray,
        mimeType: String = "audio/wav",
        language: String = "en-US"
    ): AzureBlindTranscription {
        require(subscriptionKey.isNotBlank()) { "Azure Speech key is not set" }
        val wav = azureWavBytes(audioBytes, mimeType)
        require(estimatedWavSeconds(wav) <= BLIND_MAX_SECONDS) {
            "Recording exceeds Azure's 60-second short-audio limit"
        }
        return parseBlindResponse(
            postAudio(subscriptionKey, region, language, wav, pronunciationHeader = null)
        ) ?: throw IllegalStateException("Azure Speech returned no reliable transcript")
    }

    suspend fun assessPronunciation(
        subscriptionKey: String,
        region: String,
        audioBytes: ByteArray,
        referenceText: String,
        mimeType: String = "audio/wav",
        language: String = "en-US"
    ): AzurePronunciationEvidence {
        require(subscriptionKey.isNotBlank()) { "Azure Speech key is not set" }
        require(referenceText.isNotBlank()) { "Pronunciation reference text is empty" }
        val wav = azureWavBytes(audioBytes, mimeType)
        require(estimatedWavSeconds(wav) <= DIAGNOSTIC_MAX_SECONDS) {
            "Recording exceeds Azure's 30-second pronunciation-assessment limit"
        }
        return parsePronunciationResponse(
            postAudio(
                subscriptionKey,
                region,
                language,
                wav,
                pronunciationHeader = pronunciationAssessmentHeader(referenceText)
            )
        ) ?: throw IllegalStateException("Azure Speech returned no pronunciation details")
    }

    private suspend fun postAudio(
        subscriptionKey: String,
        region: String,
        language: String,
        wavBytes: ByteArray,
        pronunciationHeader: String?
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(endpoint(region, language)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", subscriptionKey.trim())
            connection.setRequestProperty(
                "Content-Type",
                "audio/wav; codecs=audio/pcm; samplerate=16000"
            )
            connection.setRequestProperty("Accept", "application/json")
            pronunciationHeader?.let {
                connection.setRequestProperty("Pronunciation-Assessment", it)
            }
            connection.outputStream.use { it.write(wavBytes) }
            val status = connection.responseCode
            val response = (if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                // Never expose the provider's response body (it may be JSON) to UI callers.
                throw ApiError.fromHttpResponse("Azure Speech", status, response)
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseBlindResponse(raw: String): AzureBlindTranscription? {
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("RecognitionStatus", "Success") != "Success") return null
            val nBest = root.optJSONArray("NBest")
            val best = if (nBest != null && nBest.length() > 0) {
                (0 until nBest.length())
                    .mapNotNull(nBest::optJSONObject)
                    .maxByOrNull { it.optDouble("Confidence", 0.0) }
            } else {
                null
            }
            val text = (
                best?.optString("Display").orEmpty().ifBlank {
                    best?.optString("Lexical").orEmpty()
                }.ifBlank {
                    root.optString("DisplayText")
                }
                ).trim()
            if (text.isBlank()) return null
            val confidence = best?.optDouble("Confidence", 0.0)
                ?.takeIf { it in 0.0..1.0 } ?: 0.0
            val uncertainWords = buildList {
                val words = best?.optJSONArray("Words")
                if (words != null) {
                    for (index in 0 until words.length()) {
                        val word = words.optJSONObject(index) ?: continue
                        if (word.optDouble("Confidence", 1.0) < 0.65) {
                            word.optString("Word").trim()
                                .takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
            }.distinct().take(8)
            AzureBlindTranscription(text, confidence, uncertainWords)
        }.getOrNull()
    }

    internal fun parsePronunciationResponse(raw: String): AzurePronunciationEvidence? {
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("RecognitionStatus", "Success") != "Success") return null
            val best = root.optJSONArray("NBest")?.optJSONObject(0) ?: return null
            val recognized = best.optString("Display", root.optString("DisplayText")).trim()
            val wordsJson = best.optJSONArray("Words")
            val words = buildList {
                if (wordsJson != null) {
                    for (index in 0 until wordsJson.length()) {
                        val word = wordsJson.optJSONObject(index) ?: continue
                        val assessment = word.optJSONObject("PronunciationAssessment")
                        val accuracy = scoreFrom(word, assessment, "AccuracyScore")
                        val errorType = assessment?.optString(
                            "ErrorType",
                            word.optString("ErrorType", "None")
                        ) ?: word.optString("ErrorType", "None")
                        val offset = word.optLong("Offset", -1L)
                        val duration = word.optLong("Duration", -1L)
                        val startMs = offset.takeIf { it >= 0 }?.let { (it / 10_000L).toInt() }
                        val endMs = if (offset >= 0 && duration >= 0) {
                            ((offset + duration) / 10_000L).toInt()
                        } else {
                            null
                        }
                        val phonemesJson = word.optJSONArray("Phonemes")
                        val phonemes = buildList {
                            if (phonemesJson != null) {
                                for (phonemeIndex in 0 until phonemesJson.length()) {
                                    val phoneme = phonemesJson.optJSONObject(phonemeIndex) ?: continue
                                    val phonemeAssessment =
                                        phoneme.optJSONObject("PronunciationAssessment")
                                    add(
                                        AzurePhonemeEvidence(
                                            phoneme = phoneme.optString("Phoneme").trim(),
                                            accuracy = scoreFrom(
                                                phoneme,
                                                phonemeAssessment,
                                                "AccuracyScore"
                                            )
                                        )
                                    )
                                }
                            }
                        }.filter { it.phoneme.isNotBlank() }
                        val displayWord = word.optString("Word").trim()
                        if (displayWord.isNotBlank()) {
                            add(
                                AzureWordEvidence(
                                    word = displayWord,
                                    accuracy = accuracy,
                                    errorType = errorType.ifBlank { "None" },
                                    startMs = startMs,
                                    endMs = endMs,
                                    phonemes = phonemes
                                )
                            )
                        }
                    }
                }
            }
            AzurePronunciationEvidence(recognizedText = recognized, words = words)
        }.getOrNull()
    }

    private fun scoreFrom(
        direct: JSONObject,
        nested: JSONObject?,
        key: String
    ): Double? {
        val value = nested?.optDouble(key, Double.NaN)
            ?.takeUnless(Double::isNaN)
            ?: direct.optDouble(key, Double.NaN).takeUnless(Double::isNaN)
        return value?.takeIf { it in 0.0..100.0 }
    }

    private fun azureWavBytes(audioBytes: ByteArray, mimeType: String): ByteArray {
        val (bytes, containerMime) = containerizeInlineAudio(audioBytes, mimeType)
        require(containerMime.lowercase(Locale.ROOT).startsWith("audio/wav")) {
            "Azure Speech requires 16 kHz PCM WAV audio"
        }
        return bytes
    }

    private fun estimatedWavSeconds(bytes: ByteArray): Int {
        val payloadBytes = (bytes.size - 44).coerceAtLeast(0)
        return (payloadBytes + 31_999) / 32_000
    }

    private fun safeError(raw: String): String {
        if (raw.isBlank()) return "No response details"
        return runCatching {
            val json = JSONObject(raw)
            json.optString("message")
                .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
                .ifBlank { "Request rejected" }
        }.getOrDefault("Request rejected").take(300)
    }
}
