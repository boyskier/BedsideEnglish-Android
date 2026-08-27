package com.example.medvoicetrainer.analysis

import android.util.Log
import com.example.medvoicetrainer.api.AzureSpeechService
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.api.withGeminiTextFallbackTracked
import org.json.JSONObject

private const val TAG = "SpeakingAttemptJudge"

/**
 * Judges a single short speaking attempt (the learner reading one target sentence aloud)
 * against the app's intelligibility principle: "comfortably understood, not native-sounding"
 * (see Intelligibility.kt). Used by the Shadowing record-and-check loop and the SRS speaking
 * review for pronunciation cards.
 *
 * Same operating rules as PronunciationEngine: Gemini-only, invoked only on an explicit user
 * action (tapping record/check is the opt-in), and gracefully degrades — any failure returns
 * null and the UI falls back to self-comparison (play own recording vs. TTS target).
 */

data class SpeakingJudgment(
    val pass: Boolean,
    val feedback: String,
    val focusWords: List<String> = emptyList(),
    val outcome: IntelligibilityOutcome =
        if (pass) IntelligibilityOutcome.COMFORTABLE else IntelligibilityOutcome.EFFORTFUL,
    val intendedText: String = "",
    val heardTranscript: String = "",
    val uncertainWords: List<String> = emptyList(),
    val criticalDifferences: List<CriticalMeaningDifference> = emptyList()
)

object SpeakingAttemptJudge {

    internal data class BlindAttemptObservation(
        val heardTranscript: String,
        val uncertainWords: List<String>,
        val comprehensibility: Int,
        val criticalItemsClear: Boolean,
        val confidence: Double
    )

    /**
     * Best-effort diagnostic for the most recent [judge] failure on this process — e.g. "HTTP
     * 404: ..." or "Gemini response did not parse as a judgment". [judge] itself always returns
     * null on failure (never throws), so the UI can't otherwise tell a missing key apart from a
     * bad model name apart from a network error. Not concurrency-safe against overlapping judge
     * calls (last writer wins); it's a hint for a single user-initiated tap, not a source of truth.
     * Also written to by [SpokenReviewJudge], which shares this same diagnostic slot.
     */
    @Volatile
    var lastFailureReason: String? = null
        internal set

    /** ~0.25 s of 16 kHz PCM16 mono — anything shorter cannot contain a sentence attempt. */
    const val MIN_AUDIO_BYTES = 8_000

    /** 60 s cap — a single-sentence attempt never legitimately needs more. */
    const val MAX_AUDIO_BYTES = 16_000 * 2 * 60

    /**
     * Intentionally contains no target text. This first call is the actual listening test; a
     * target-aware model is too likely to "hear" the script it was shown.
     */
    internal fun buildBlindPrompt(): String = """
        Act as a careful first-time listener to one short English utterance. You do NOT have a
        reference script. Write only what the audio itself supports; do not repair grammar and do
        not guess a likely sentence from context.

        Judge intelligibility, not accent. A clearly non-native accent is fine when the words are
        comfortable to understand.

        Return ONLY this JSON object:
        {
          "heard_transcript": "<exactly what you believe you heard>",
          "uncertain_words": ["<uncertain or guessed word>", ...],
          "comprehensibility": 1,
          "critical_items_clear": true,
          "confidence": 0.0
        }

        comprehensibility is 1 (not assessable/very hard) through 5 (effortless).
        critical_items_clear is false if a medication, name, number, dose, negation, side, or time
        is hard to hear. You do not know the intended wording, so judge clarity only.
    """.trimIndent()

    private val COACHING_PROMPT = """
        You are a conservative pronunciation coach. A separate AI listener already transcribed
        the audio WITHOUT seeing the target. Do not change that transcript or decide pass/fail.

        Intended sentence:
        <target>%s</target>

        Blind listener heard:
        <heard>%s</heard>

        Blind listener uncertainty:
        %s

        Learner's native language: %s
        %s

        Give one short actionable tip for the smallest pronunciation, stress, or rhythm change
        likely to make the differing/uncertain words easier to catch. Do not coach accent toward
        native-likeness. Name at most 3 focus words.

        Return ONLY:
        {
          "verdict": "retry",
          "feedback": "<one or two short sentences>",
          "focus_words": ["<word>", ...]
        }
    """.trimIndent()

    /**
     * The coaching prompt, filled in.
     *
     * Both [judge] and [judgeWithAzure] built this with the same five arguments; extracted so
     * they cannot drift apart and so the cross-language golden harness can pin the text
     * (MedVoiceTrainer ledger LOG-05). [focusBlock] is what differs between them: the Gemini path
     * passes an earlier coaching hypothesis, the Azure path leads with specialist evidence.
     */
    internal fun buildCoachingPrompt(
        targetText: String,
        heardTranscript: String,
        uncertainWords: List<String>,
        l1Lang: String,
        focusBlock: String,
    ): String = COACHING_PROMPT.format(
        PhraseBlanks.describedForPrompt(targetText).take(400),
        heardTranscript.take(400),
        uncertainWords.joinToString().ifBlank { "(none)" },
        PronunciationEngine.l1Name(l1Lang),
        focusBlock,
    )

    /**
     * Send one attempt to Gemini and return the verdict, or null on any failure
     * (missing key, short audio, network/parse errors) — never throws.
     */
    suspend fun judge(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        sampleRate: Int = 16_000,
        targetText: String,
        l1Lang: String = "ko",
        focusHint: String = "",
        strict: Boolean = false
    ): SpeakingJudgment? {
        if (apiKey.isBlank() || targetText.isBlank()) {
            lastFailureReason = "Gemini API key is not set"
            return null
        }
        if (audioBytes.size < MIN_AUDIO_BYTES) {
            lastFailureReason = "Recording was too short to judge"
            return null
        }
        // A cloze phrase ("Hello, my name is ___.") is graded on its fixed frame only; the blank
        // is the learner's own words and would otherwise read as a missed word plus an added
        // meaning-critical item. See PhraseBlanks.
        val openEnded = PhraseBlanks.hasBlank(targetText)
        val referenceText =
            if (openEnded) PhraseBlanks.comparisonTarget(targetText) else targetText.trim()
        if (referenceText.isBlank()) {
            lastFailureReason = "This phrase has no fixed words to grade"
            return null
        }
        val bounded = if (audioBytes.size > MAX_AUDIO_BYTES) audioBytes.copyOf(MAX_AUDIO_BYTES) else audioBytes
        // Strict mode hands the listener the same narrow-band signal a phone order carries.
        val audio = if (strict) {
            com.example.medvoicetrainer.voice.AudioEffects.telephoneBandlimit(bounded, sampleRate)
        } else {
            bounded
        }
        return try {
            val blindCall = com.example.medvoicetrainer.api.withGeminiAudioFallbackTracked(model) { m ->
                GeminiService.generateContentWithAudio(
                    apiKey,
                    m,
                    audio,
                    sampleRate,
                    buildBlindPrompt()
                )
            }
            val blind = parseBlindObservation(blindCall.value)
            if (blind == null) {
                lastFailureReason = "Gemini response wasn't a valid blind transcript"
                Log.w(TAG, "Gemini response did not parse as a blind observation: ${blindCall.value}")
                return null
            }
            val assessment = IntelligibilityComparator.assess(
                expected = referenceText,
                heard = blind.heardTranscript,
                uncertainWords = blind.uncertainWords,
                comprehensibility = blind.comprehensibility,
                confidence = blind.confidence,
                criticalItemsClear = blind.criticalItemsClear,
                strict = strict,
                openEnded = openEnded
            )
            val automaticFeedback = when (assessment.outcome) {
                IntelligibilityOutcome.COMFORTABLE ->
                    "The blind listener heard the intended message comfortably."
                IntelligibilityOutcome.EFFORTFUL ->
                    "The message was partly understood, but some words required listener effort."
                IntelligibilityOutcome.CRITICAL_MISMATCH ->
                    "A meaning-critical word sounded different to the blind listener."
                IntelligibilityOutcome.COULD_NOT_ASSESS ->
                    "The blind listener could not assess this recording reliably. Try again closer to the microphone."
            }
            val focusBlock = if (focusHint.isBlank()) {
                "There is no preselected sound target; coach the clearest listening blocker."
            } else {
                "Earlier coaching focus (a hypothesis, not the verdict): ${focusHint.take(500)}"
            }
            val coaching = if (assessment.outcome == IntelligibilityOutcome.COMFORTABLE) {
                null
            } else {
                runCatching {
                    val coachingPrompt = buildCoachingPrompt(
                        targetText, blind.heardTranscript, blind.uncertainWords, l1Lang, focusBlock
                    )
                    val raw = com.example.medvoicetrainer.api.withGeminiAudioFallback(
                        blindCall.modelUsed
                    ) { m ->
                        GeminiService.generateContentWithAudio(
                            apiKey,
                            m,
                            audio,
                            sampleRate,
                            coachingPrompt
                        )
                    }
                    parseJudgment(raw)
                }.getOrNull()
            }
            lastFailureReason = null
            SpeakingJudgment(
                pass = assessment.comfortablyUnderstood,
                feedback = coaching?.feedback?.takeIf { it.isNotBlank() } ?: automaticFeedback,
                focusWords = (
                    assessment.criticalDifferences.flatMap { listOf(it.expected, it.heard.orEmpty()) }
                        .filterNot { it.isBlank() || it == "(none)" } +
                        coaching?.focusWords.orEmpty() +
                        blind.uncertainWords
                    ).distinct().take(3),
                outcome = assessment.outcome,
                intendedText = targetText,
                heardTranscript = blind.heardTranscript,
                uncertainWords = blind.uncertainWords,
                criticalDifferences = assessment.criticalDifferences
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Never surfaces as an exception (the caller degrades to self-comparison on null),
            // but the reason is kept here — and logged — so "check with AI silently fails" is
            // actually diagnosable instead of a total black box.
            lastFailureReason = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Speaking attempt judge call failed", e)
            null
        }
    }

    /**
     * Specialist path. Pass/fail is based exclusively on target-blind Azure STT plus the local
     * meaning comparator. Reference-aware pronunciation assessment runs only after a failed
     * listening result, and Gemini (when configured) receives text evidence rather than audio.
     */
    suspend fun judgeWithAzure(
        azureKey: String,
        azureRegion: String,
        geminiApiKey: String,
        geminiModel: String,
        audioBytes: ByteArray,
        sampleRate: Int = 16_000,
        targetText: String,
        l1Lang: String = "ko",
        focusHint: String = "",
        strict: Boolean = false
    ): SpeakingJudgment? {
        if (azureKey.isBlank() || azureRegion.isBlank() || targetText.isBlank()) return null
        if (audioBytes.size < MIN_AUDIO_BYTES) {
            lastFailureReason = "Recording was too short to judge"
            return null
        }
        val openEnded = PhraseBlanks.hasBlank(targetText)
        val referenceText =
            if (openEnded) PhraseBlanks.comparisonTarget(targetText) else targetText.trim()
        if (referenceText.isBlank()) {
            lastFailureReason = "This phrase has no fixed words to grade"
            return null
        }
        return try {
            val capped = if (audioBytes.size > MAX_AUDIO_BYTES) {
                audioBytes.copyOf(MAX_AUDIO_BYTES)
            } else {
                audioBytes
            }
            val bounded = if (strict) {
                com.example.medvoicetrainer.voice.AudioEffects.telephoneBandlimit(capped, sampleRate)
            } else {
                capped
            }
            val mimeType = "audio/pcm;rate=$sampleRate"
            val blind = AzureSpeechService.transcribeBlind(
                subscriptionKey = azureKey,
                region = azureRegion,
                audioBytes = bounded,
                mimeType = mimeType
            )
            val comprehensibility = when {
                blind.confidence >= 0.88 && blind.uncertainWords.isEmpty() -> 5
                blind.confidence >= 0.72 && blind.uncertainWords.size <= 1 -> 4
                blind.confidence >= 0.55 -> 3
                blind.confidence > 0.0 -> 2
                else -> 1
            }
            val assessment = IntelligibilityComparator.assess(
                expected = referenceText,
                heard = blind.text,
                uncertainWords = blind.uncertainWords,
                comprehensibility = comprehensibility,
                confidence = blind.confidence,
                criticalItemsClear =
                    blind.confidence >= 0.72 && blind.uncertainWords.size <= 1,
                strict = strict,
                openEnded = openEnded
            )
            val automaticFeedback = when (assessment.outcome) {
                IntelligibilityOutcome.COMFORTABLE ->
                    "The blind listener heard the intended message comfortably."
                IntelligibilityOutcome.EFFORTFUL ->
                    "The message was partly understood, but some words required listener effort."
                IntelligibilityOutcome.CRITICAL_MISMATCH ->
                    "A meaning-critical word sounded different to the blind listener."
                IntelligibilityOutcome.COULD_NOT_ASSESS ->
                    "The listener could not assess this recording reliably. Try again closer to the microphone."
            }

            var specialistTip = ""
            val specialistFocus = mutableListOf<String>()
            if (
                assessment.outcome != IntelligibilityOutcome.COMFORTABLE &&
                assessment.outcome != IntelligibilityOutcome.COULD_NOT_ASSESS
            ) {
                val source = PronunciationAudioSource(
                    turnIndex = 0,
                    transcriptText = referenceText,
                    byteSize = bounded.size,
                    mimeType = mimeType,
                    loadAudio = { bounded }
                )
                val observation = BlindIntelligibilityObservation(
                    turnIndex = 0,
                    expectedTranscript = referenceText,
                    heardTranscript = blind.text,
                    uncertainWords = blind.uncertainWords,
                    comprehensibility = comprehensibility,
                    confidence = blind.confidence,
                    criticalItemsClear =
                        blind.confidence >= 0.72 && blind.uncertainWords.size <= 1,
                    wordMatchRate = assessment.wordMatchRate,
                    criticalDifferences = assessment.criticalDifferences,
                    outcome = assessment.outcome
                )
                val evidence = runCatching {
                    AzureSpeechService.assessPronunciation(
                        subscriptionKey = azureKey,
                        region = azureRegion,
                        audioBytes = bounded,
                        referenceText = referenceText,
                        mimeType = mimeType
                    )
                }.getOrNull()
                val corrections = evidence?.let {
                    PronunciationEngine.correctionsFromAzureEvidence(source, observation, it)
                }.orEmpty()
                specialistTip = corrections.firstOrNull()?.explanation.orEmpty()
                specialistFocus += corrections.map { it.corrected }
            }

            val coaching = if (
                assessment.outcome == IntelligibilityOutcome.COMFORTABLE ||
                geminiApiKey.isBlank()
            ) {
                null
            } else {
                runCatching {
                    val focusBlock = buildString {
                        if (specialistTip.isNotBlank()) {
                            append("Specialist speech evidence: ")
                            append(specialistTip.take(500))
                            append('\n')
                        }
                        if (focusHint.isNotBlank()) {
                            append("Earlier coaching hypothesis: ")
                            append(focusHint.take(300))
                        }
                    }.ifBlank { "No additional specialist sound detail was available." }
                    val prompt = buildCoachingPrompt(
                        targetText, blind.text, blind.uncertainWords, l1Lang, focusBlock
                    )
                    val raw = withGeminiTextFallbackTracked(geminiModel) { model ->
                        GeminiService.generateContent(geminiApiKey, model, prompt)
                    }.value
                    parseJudgment(raw)
                }.getOrNull()
            }
            lastFailureReason = null
            SpeakingJudgment(
                pass = assessment.comfortablyUnderstood,
                feedback = coaching?.feedback?.takeIf { it.isNotBlank() }
                    ?: specialistTip.takeIf { it.isNotBlank() }
                    ?: automaticFeedback,
                focusWords = (
                    assessment.criticalDifferences.flatMap {
                        listOf(it.expected, it.heard.orEmpty())
                    } + coaching?.focusWords.orEmpty() + specialistFocus + blind.uncertainWords
                    ).filterNot { it.isBlank() || it == "(none)" }.distinct().take(3),
                outcome = assessment.outcome,
                intendedText = targetText,
                heardTranscript = blind.text,
                uncertainWords = blind.uncertainWords,
                criticalDifferences = assessment.criticalDifferences
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            lastFailureReason = "Azure Speech: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Azure speaking attempt judge failed", e)
            null
        }
    }

    internal fun parseBlindObservation(raw: String): BlindAttemptObservation? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.split("```").getOrElse(1) { text }
            if (text.startsWith("json")) text = text.substring(4)
        }
        return try {
            val obj = JSONObject(text.trim())
            val rawHeard = obj.optString("heard_transcript", "").trim()
            val comprehensibility = obj.optInt("comprehensibility", 0)
            val confidence = obj.optDouble("confidence", -1.0)
            if (comprehensibility !in 1..5 || confidence !in 0.0..1.0) {
                return null
            }
            val heard = if (rawHeard.isBlank()) {
                if (comprehensibility == 1) "[No reliable words heard]" else return null
            } else {
                rawHeard
            }
            val uncertain = buildList {
                val array = obj.optJSONArray("uncertain_words")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.take(8)
            BlindAttemptObservation(
                heardTranscript = heard.take(500),
                uncertainWords = uncertain,
                comprehensibility = comprehensibility,
                criticalItemsClear = obj.optBoolean("critical_items_clear", false),
                confidence = confidence
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            null
        }
    }

    internal fun parseJudgment(raw: String): SpeakingJudgment? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.split("```").getOrElse(1) { text }
            if (text.startsWith("json")) text = text.substring(4)
        }
        text = text.trim()
        return try {
            val obj = JSONObject(text)
            val verdict = obj.optString("verdict", "").trim().lowercase()
            if (verdict != "pass" && verdict != "retry") return null
            val focus = buildList {
                val arr = obj.optJSONArray("focus_words")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            SpeakingJudgment(
                pass = verdict == "pass",
                feedback = obj.optString("feedback", "").trim(),
                focusWords = focus.take(3)
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            null
        }
    }
}
