package com.example.medvoicetrainer.ui

import com.example.medvoicetrainer.analysis.EvalPromptBuilder
import com.example.medvoicetrainer.analysis.FluencyMetrics
import com.example.medvoicetrainer.analysis.FollowUpEvaluation
import com.example.medvoicetrainer.analysis.Intelligibility
import com.example.medvoicetrainer.analysis.ScoringCalibration
import com.example.medvoicetrainer.analysis.transcriptWithPythonRoles
import com.example.medvoicetrainer.db.SessionEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single stored correction plus the final triage decision the learner made in the live
 * feedback screen (persisted onto [SessionEntity.corrections] by
 * MainViewModel.persistCorrectionDecisions). History has no interactive accept/reject flow — it
 * only replays what already happened during the session.
 */
data class HistoryCorrectionRecord(
    val correction: SrsCorrection,
    val decision: String,
    val learnerPrediction: String?
)

private fun jsonToShallowMap(json: String): Map<String, Any?> = try {
    val obj = JSONObject(json.ifBlank { "{}" })
    val map = mutableMapOf<String, Any?>()
    obj.keys().forEach { key -> map[key] = obj.opt(key) }
    map
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    emptyMap()
}

private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> = buildMap {
    for (key in obj.keys()) {
        put(key, when (val value = obj.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> (0 until value.length()).map { index ->
                when (val child = value.opt(index)) {
                    is JSONObject -> jsonObjectToMap(child)
                    JSONObject.NULL -> null
                    else -> child
                }
            }
            else -> value
        })
    }
}

/** Parses the grounded corrections this session stored, keeping each item's final decision. */
fun SessionEntity.parsedHistoryCorrections(): List<HistoryCorrectionRecord> {
    val result = mutableListOf<HistoryCorrectionRecord>()
    val array = try {
        JSONArray(corrections?.ifBlank { "[]" } ?: "[]")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        JSONArray()
    }
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val original = item.optString("original").trim()
        val corrected = item.optString("corrected").trim()
        if (original.isBlank() || corrected.isBlank() || original.equals(corrected, ignoreCase = true)) continue
        val turnIndex = if (item.has("turn_index") && !item.isNull("turn_index")) {
            item.optInt("turn_index", -1).takeIf { it >= 0 }
        } else null
        val l1Hypothesis = item.opt("l1_hypothesis")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()?.trim().orEmpty()
        val correction = try {
            SrsCorrection(
                original = original,
                corrected = corrected,
                explanation = item.optString("explanation"),
                category = item.optString("category", "other"),
                turnIndex = turnIndex,
                confidence = item.optDouble("confidence", 1.0),
                feedbackType = item.optString("feedback_type", "error"),
                l1Hypothesis = l1Hypothesis,
                evidenceSource = item.optString("evidence_source", "transcript"),
                patternId = item.optString("pattern_id")
            )
        } catch (_: IllegalArgumentException) {
            continue
        }
        result.add(
            HistoryCorrectionRecord(
                correction = correction,
                decision = item.optString("decision", "pending"),
                learnerPrediction = item.opt("learner_prediction")
                    ?.takeUnless { it == JSONObject.NULL }?.toString()
            )
        )
    }
    return result
}

/**
 * Reconstructs the same [EvaluationResult] shape FeedbackScreen renders right after a session
 * ends, but entirely from what a [SessionEntity] persisted — so History can show the identical
 * report later. Fluency and intelligibility metrics are deterministic functions of the stored
 * transcript, so they're recomputed rather than re-parsed out of the raw LLM response blob (which
 * only some sessions kept around); everything else reads straight off the entity's own columns.
 */
fun SessionEntity.toEvaluationResult(): EvaluationResult {
    // The evaluation response records why feedback was unavailable. Preserve it in History so a
    // real session with no analysis key is not presented as the fully local scripted demo.
    val evaluationMetadata = runCatching { JSONObject(rawEvalJson.orEmpty()) }.getOrNull()
    val evaluationLocked = evaluationMetadata?.optBoolean("_locked", false) == true
    val isTypedDemo = evaluationMetadata?.optBoolean(
        "_demo",
        voiceBackend.equals("demo", ignoreCase = true) || voiceBackend.equals("mock", ignoreCase = true)
    ) ?: (voiceBackend.equals("demo", ignoreCase = true) || voiceBackend.equals("mock", ignoreCase = true))

    val historyCorrections = parsedHistoryCorrections()
    val corrections = historyCorrections.map { it.correction }

    val checklistChecked = mutableListOf<Pair<String, Boolean>>()
    val checklistMaps = mutableListOf<Map<String, Any?>>()
    var storedChecklistArray: JSONArray? = null
    try {
        val arr = JSONArray(checklistResults?.ifBlank { "[]" } ?: "[]")
        storedChecklistArray = arr
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val passed = if (item.has("passed")) item.optBoolean("passed") else item.optBoolean("elicited")
            checklistChecked.add(item.optString("item") to passed)
            checklistMaps.add(jsonObjectToMap(item))
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        // Leave empty — ChecklistContent renders its own "no checklist" empty state.
    }

    val everyday = EvalPromptBuilder.isEverydayDomain(analysisDomain)
    val pythonTranscriptJson = transcriptWithPythonRoles()
    val fluencyMetrics = FluencyMetrics.computeFluencyMetrics(pythonTranscriptJson)
        ?.let { FluencyMetrics.addWpm(it, durationSeconds.toDouble()) }

    val transcriptMaps: List<Map<String, Any?>> = try {
        val arr = JSONArray(pythonTranscriptJson)
        (0 until arr.length()).map { i ->
            val turn = arr.optJSONObject(i)
            mapOf("role" to turn?.optString("role"), "text" to turn?.optString("text"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyList()
    }
    val misconceptionTranscript = transcriptMaps.map { turn ->
        val role = when (turn["role"]?.toString()?.lowercase()) {
            "user", "learner", "doctor" -> "doctor"
            else -> "patient"
        }
        role to turn["text"]?.toString().orEmpty()
    }
    val misconceptionReviewJson = if (everyday) {
        "[]"
    } else {
        com.example.medvoicetrainer.analysis.MisconceptionReview.sanitizeEvaluationArray(
            evaluationMetadata?.optJSONArray("misconception_review"),
            misconceptionTranscript,
        ).toString()
    }

    val intelligibility = Intelligibility.computeIntelligibilityMetrics(
        transcriptMaps,
        mode = if (everyday) "everyday" else "clinical"
    ).ifEmpty { null }

    val caseDataMap = jsonToShallowMap(rawCaseJson)
    val storedReliabilityBadge = try {
        JSONObject(rawClaudeResponse.orEmpty()).optJSONObject("score_reliability")
            ?.takeIf { it.has("user_word_count") && it.has("evidence_rate") }
            ?.let(::jsonObjectToMap)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }
    val reliabilityBadge = storedReliabilityBadge ?: try {
        val overallScores = if (everyday) {
            mapOf(
                "naturalness_score" to grammarScore / 10.0,
                "interaction_score" to medicalAccuracyScore / 10.0,
                "comprehension_repair_score" to clinicalReasoningScore / 10.0,
                "fluency_score" to fluencyScore / 10.0
            )
        } else {
            mapOf(
                "grammar_score" to grammarScore / 10.0,
                "medical_accuracy_score" to medicalAccuracyScore / 10.0,
                "clinical_reasoning_score" to clinicalReasoningScore / 10.0,
                "professionalism_score" to professionalismScore / 10.0,
                "fluency_score" to fluencyScore / 10.0
            )
        }
        ScoringCalibration.buildScoreReliability(
            analysis = mapOf("overall_scores" to overallScores, "checklist_results" to checklistMaps),
            transcript = transcriptMaps,
            evalData = null,
            caseData = caseDataMap
        )
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }

    val followUpFeedbackJson = try {
        val caseRoot = JSONObject(rawCaseJson.ifBlank { "{}" })
        val isFollowUp = mode == "follow_up" || caseRoot.optString("encounter_type") == "follow_up"
        if (!isFollowUp) {
            "{}"
        } else {
            val cleanRaw = rawEvalJson?.replace("```json", "")?.replace("```", "")?.trim().orEmpty()
            val rawRoot = JSONObject(cleanRaw.ifBlank { "{}" })
            val completeness = FollowUpEvaluation.completeness(storedChecklistArray)
                ?: FollowUpEvaluation.boundedLegacyCompleteness(
                    rawRoot.opt("follow_up_completeness")?.takeUnless { it == JSONObject.NULL }
                )
            JSONObject()
                .put("is_follow_up", true)
                .put(
                    "follow_up_completeness",
                    completeness ?: JSONObject.NULL,
                )
                .put("shared_plan", rawRoot.optJSONObject("shared_plan") ?: JSONObject())
                .toString()
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        "{}"
    }

    return EvaluationResult(
        grammarScore = grammarScore,
        medicalAccuracy = medicalAccuracyScore,
        clinicalReasoning = clinicalReasoningScore,
        professionalism = professionalismScore,
        fluencyScore = fluencyScore,
        summaryFeedback = summaryFeedback.orEmpty(),
        soapNote = soapNote.orEmpty(),
        corrections = corrections,
        rawCorrectionsJson = this.corrections ?: "[]",
        wordCount = userWordCount,
        wpm = wordsPerMinute,
        fillerRate = 0.0,
        checklistChecked = checklistChecked,
        reliability = (reliabilityBadge?.get("confidence") as? String)?.uppercase(java.util.Locale.ROOT) ?: "MEDIUM",
        analysisDomain = analysisDomain,
        caseName = caseName,
        evaluationLocked = evaluationLocked,
        isTypedDemo = isTypedDemo,
        checklistResultsJson = checklistResults ?: "[]",
        historyCompleteness = historyCompleteness,
        iceElicited = iceElicited != 0,
        empathyMarkersJson = empathyMarkersFound ?: "[]",
        ankiCardsJson = ankiCards ?: "[]",
        shadowingItemsJson = "[]",
        commitmentResultsJson = "[]",
        referenceSoap = referenceSoap ?: "",
        fluencyMetrics = fluencyMetrics,
        intelligibility = intelligibility,
        reliabilityBadge = reliabilityBadge,
        followUpFeedbackJson = followUpFeedbackJson,
        misconceptionReviewJson = misconceptionReviewJson,
    )
}
