package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.SessionEntity
import org.json.JSONArray

/**
 * Bridges Room's [SessionEntity] to the snake_case Map<String, Any?> shape that ported
 * analysis engines expect (mirroring the dict rows app/db/queries.py used to hand back).
 * Keeps the analysis modules themselves free of Android-framework and Room dependencies.
 */
fun SessionEntity.toAnalysisMap(): Map<String, Any?> = mapOf(
    "id" to id.toLong(),
    "created_at" to createdAt,
    "mode" to mode,
    "analysis_domain" to analysisDomain,
    "case_name" to caseName,
    "case_id" to caseId,
    "eval_template" to evalTemplate,
    "voice_backend" to voiceBackend,
    "voice_model" to voiceModel,
    "analysis_model" to analysisModel,
    "duration_seconds" to durationSeconds.toLong(),
    "learner_turn_count" to learnerTurnCount.toLong(),
    "raw_case_json" to rawCaseJson,
    "raw_claude_response" to rawClaudeResponse,
    "raw_eval_json" to rawEvalJson,
    "raw_transcript" to rawTranscript,
    "corrections" to corrections,
    "anki_cards" to ankiCards,
    "checklist_results" to checklistResults,
    "empathy_markers_found" to empathyMarkersFound,
    "grammar_score" to grammarScore,
    "medical_accuracy_score" to medicalAccuracyScore,
    "clinical_reasoning_score" to clinicalReasoningScore,
    "professionalism_score" to professionalismScore,
    "fluency_score" to fluencyScore,
    "self_grammar" to selfGrammar,
    "self_medical_accuracy" to selfMedicalAccuracy,
    "self_clinical_reasoning" to selfClinicalReasoning,
    "self_professionalism" to selfProfessionalism,
    "self_fluency" to selfFluency,
    "self_scores_json" to selfScoresJson,
    "history_completeness" to historyCompleteness,
    "ice_elicited" to iceElicited.toLong(),
    "student_soap_note" to studentSoapNote,
    "soap_note" to soapNote,
    "reference_soap" to referenceSoap,
    "summary_feedback" to summaryFeedback,
    "debrief_chat" to debriefChat,
    "debrief_insights" to debriefInsights,
    "docx_path" to docxPath,
    "claude_input_tokens" to claudeInputTokens.toLong(),
    "claude_output_tokens" to claudeOutputTokens.toLong(),
    "claude_cached_tokens" to claudeCachedTokens.toLong(),
    "claude_cost_usd" to claudeCostUsd,
    "voice_cost_usd" to voiceCostUsd,
    "total_cost_usd" to totalCostUsd,
    "voice_input_text_tokens" to voiceInputTextTokens.toLong(),
    "voice_input_audio_tokens" to voiceInputAudioTokens.toLong(),
    "voice_output_text_tokens" to voiceOutputTextTokens.toLong(),
    "voice_output_audio_tokens" to voiceOutputAudioTokens.toLong(),
    "voice_cached_input_text_tokens" to voiceCachedInputTextTokens.toLong(),
    "voice_cached_input_audio_tokens" to voiceCachedInputAudioTokens.toLong(),
    "voice_thinking_tokens" to voiceThinkingTokens.toLong(),
    "voice_usage_exact" to voiceUsageExact,
    "cost_estimated" to costEstimated,
    "cost_report_path" to costReportPath,
    "deleted_at" to deletedAt,
    "user_word_count" to userWordCount.toLong(),
    "words_per_minute" to wordsPerMinute,
    "filler_rate" to fillerRate,
    "talk_time_ratio" to talkTimeRatio
)

fun List<SessionEntity>.toAnalysisMaps(): List<Map<String, Any?>> = map { it.toAnalysisMap() }

/**
 * Returns the clinical-session transcripts consumed by the dashboard vocabulary tracker.
 *
 * The dashboard's Room session stream and its background-built analysis state can emit at
 * different times. Classifying each [SessionEntity] here keeps the transcript selection tied to
 * one snapshot instead of indexing a second, temporarily shorter list.
 */
fun List<SessionEntity>.clinicalTranscriptsWithPythonRoles(): List<String> =
    filterNot { ScoreDomains.isEverydaySession(it.toAnalysisMap()) }
        .map { it.transcriptWithPythonRoles() }

/**
 * Python's raw transcripts tag the learner's turns "user" (the model's "model"); this app's
 * live session plumbing (MainViewModel.finishSession) tags them "doctor"/"patient" instead for
 * on-screen labeling. Ported analysis functions that read transcript role (e.g.
 * VocabularyTracker.calculateVocabularyCoverage) expect the Python convention — remap here
 * rather than changing the stored convention, which FeedbackScreen/HistoryScreen/DebriefScreen
 * already depend on for display.
 */
fun SessionEntity.transcriptWithPythonRoles(): String {
    return try {
        val turns = JSONArray(rawTranscript)
        for (i in 0 until turns.length()) {
            val turn = turns.getJSONObject(i)
            val role = turn.optString("role")
            turn.put("role", if (role == "doctor") "user" else if (role == "patient") "model" else role)
        }
        turns.toString()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        rawTranscript
    }
}

private fun pythonRole(role: String): String = when (role) {
    "doctor", "user" -> "user"
    "system" -> "system"
    // A "[SCENE CHANGE]" stage direction is nobody's speech (see
    // SceneTransitionProtocol.NARRATOR_ROLE). Folding it into "model" made every scene change the
    // learner accepted count as partner words in the talk-time ratio, i.e. the learner's own share
    // shrank because they changed scene. Preserved rather than dropped: callers index the result
    // against transcript positions (MainViewModel keys pronunciation clips by transcript index),
    // so removing a turn here would misalign audio with text. [transcriptWithPythonRoles] already
    // passes this role through unchanged — this keeps the two remaps agreeing.
    com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE -> role
    else -> "model"
}

/** Same role remap as [transcriptWithPythonRoles], for the live in-memory (role, text) pair list
 * MainViewModel.finishSession works with before it's ever serialized into a SessionEntity. */
fun List<Pair<String, String>>.toPythonRoleTranscriptJson(): String {
    val arr = JSONArray()
    for ((role, text) in this) {
        val turn = org.json.JSONObject()
        turn.put("role", pythonRole(role))
        turn.put("text", text)
        arr.put(turn)
    }
    return arr.toString()
}

fun List<Pair<String, String>>.toPythonRoleTranscriptMaps(): List<Map<String, Any?>> =
    map { (role, text) -> mapOf("role" to pythonRole(role), "text" to text) }
