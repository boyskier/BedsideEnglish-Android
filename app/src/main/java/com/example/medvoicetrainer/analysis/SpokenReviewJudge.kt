package com.example.medvoicetrainer.analysis

import android.util.Log
import com.example.medvoicetrainer.api.GeminiService

private const val TAG = "SpokenReviewJudge"

/**
 * Audio judges for the two spoken-review loops:
 *
 *  - Redo (feedback screen): the learner re-says a corrected sentence right after the session.
 *    Unlike [SpeakingAttemptJudge] (pure intelligibility), this also verifies the correction
 *    actually landed — reproducing the old error is an automatic retry even if perfectly clear.
 *  - Transfer (SRS screen): the learner must use the corrected form in ONE new sentence that
 *    fits a fresh scenario. Judged on (a) the target language point being used correctly and
 *    (b) the sentence fitting the situation — never on accent.
 *
 * Same operating rules as SpeakingAttemptJudge: Gemini-only, explicitly user-initiated, and
 * any failure returns null so the UI degrades to self-grading. Responses reuse
 * [SpeakingAttemptJudge.parseJudgment]'s pass/retry JSON contract.
 */
object SpokenReviewJudge {

    private val REDO_PROMPT = """
        You are a clinical-English coach. The learner (native language: %s) made this mistake in a
        practice session and is now redoing it out loud:

        <old_mistake>
        %s
        </old_mistake>
        <target_sentence>
        %s
        </target_sentence>
        <pronunciation_focus>
        %s
        </pronunciation_focus>

        Listen to the audio and judge whether the learner produced the TARGET sentence — the
        corrected form — clearly enough that a patient or examiner would comfortably understand it.

        Rules:
        - Judge intelligibility and whether the correction landed, NOT accent. A clearly foreign
          accent still passes.
        - Small harmless word swaps that keep the corrected language point intact still pass.
        - If they slipped back into the old mistake (or the specific error the correction fixes),
          return "retry" and say exactly which words to change.
        - When pronunciation_focus is not blank, verify that specific sound/stress issue improved;
          context making the intended word guessable is not enough to pass the same audible error.
        - If the audio is silent, empty, or clearly not an attempt, return "retry" with feedback
          saying no clear attempt was heard.
        - On "pass": one short, encouraging sentence. On "retry": name at most 3 specific words
          with one concrete tip each.

        Return ONLY a valid JSON object, nothing else:
        {
          "verdict": "pass" | "retry",
          "feedback": "<one or two short sentences>",
          "focus_words": ["<word>", ...]
        }
    """.trimIndent()

    private val TRANSFER_PROMPT = """
        You are a clinical-English coach running a TRANSFER drill: the learner (native language: %s)
        previously made a mistake, learned the corrected form, and must now use it in a NEW
        situation — proving they can retrieve it, not just recognise it.

        <situation>
        %s
        </situation>
        <target_form>
        %s
        </target_form>
        <their_old_mistake>
        %s
        </their_old_mistake>

        Listen to the audio: the learner responds to the situation with one sentence of their own
        that should use the target form (or a natural variant that keeps the same corrected
        language point — e.g. the same fixed grammar, article, or phrasing).

        Rules:
        - Judge two things only: (1) the target language point is used correctly, (2) the sentence
          is an understandable, plausible response to the situation. NOT accent, NOT eloquence.
        - Repeating the old mistake, or a sentence that dodges the target form entirely, is "retry".
        - A short sentence is fine. Creative variation that keeps the correction intact passes.
        - If the audio is silent, empty, or clearly not an attempt, return "retry" with feedback
          saying no clear attempt was heard.
        - On "pass": one short sentence on what worked. On "retry": one concrete tip and, at most,
          3 focus words.

        Return ONLY a valid JSON object, nothing else:
        {
          "verdict": "pass" | "retry",
          "feedback": "<one or two short sentences>",
          "focus_words": ["<word>", ...]
        }
    """.trimIndent()

    internal fun buildRedoPrompt(
        l1Lang: String,
        original: String,
        target: String,
        focusHint: String = ""
    ): String = REDO_PROMPT.format(
        PronunciationEngine.l1Name(l1Lang),
        original.take(300),
        target.take(400),
        focusHint.take(500)
    )

    internal fun buildTransferPrompt(
        l1Lang: String,
        scenario: String,
        target: String,
        original: String
    ): String = TRANSFER_PROMPT.format(
        PronunciationEngine.l1Name(l1Lang), scenario.take(300), target.take(400), original.take(300)
    )

    private suspend fun judgeWithPrompt(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        sampleRate: Int,
        prompt: String
    ): SpeakingJudgment? {
        if (apiKey.isBlank()) {
            SpeakingAttemptJudge.lastFailureReason = "Gemini API key is not set"
            return null
        }
        if (audioBytes.size < SpeakingAttemptJudge.MIN_AUDIO_BYTES) {
            SpeakingAttemptJudge.lastFailureReason = "Recording was too short to judge"
            return null
        }
        val audio = if (audioBytes.size > SpeakingAttemptJudge.MAX_AUDIO_BYTES) {
            audioBytes.copyOf(SpeakingAttemptJudge.MAX_AUDIO_BYTES)
        } else {
            audioBytes
        }
        return try {
            val raw = com.example.medvoicetrainer.api.withGeminiAudioFallback(model) { m ->
                GeminiService.generateContentWithAudio(apiKey, m, audio, sampleRate, prompt)
            }
            SpeakingAttemptJudge.parseJudgment(raw).also {
                SpeakingAttemptJudge.lastFailureReason = if (it == null) {
                    "Gemini response wasn't a valid judgment"
                } else {
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            SpeakingAttemptJudge.lastFailureReason = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Spoken review judge call failed", e)
            null
        }
    }

    suspend fun judgeRedo(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        sampleRate: Int = 16_000,
        targetText: String,
        originalText: String,
        l1Lang: String = "ko",
        focusHint: String = ""
    ): SpeakingJudgment? {
        if (targetText.isBlank()) return null
        return judgeWithPrompt(
            apiKey, model, audioBytes, sampleRate,
            buildRedoPrompt(l1Lang, originalText, targetText, focusHint)
        )
    }

    suspend fun judgeTransfer(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        sampleRate: Int = 16_000,
        scenario: String,
        targetText: String,
        originalText: String,
        l1Lang: String = "ko"
    ): SpeakingJudgment? {
        if (targetText.isBlank() || scenario.isBlank()) return null
        return judgeWithPrompt(
            apiKey, model, audioBytes, sampleRate,
            buildTransferPrompt(l1Lang, scenario, targetText, originalText)
        )
    }
}
