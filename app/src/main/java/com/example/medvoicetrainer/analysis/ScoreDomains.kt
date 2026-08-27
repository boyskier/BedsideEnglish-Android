package com.example.medvoicetrainer.analysis

import org.json.JSONObject

object ScoreDomains {

    private val SURVIVAL_METRIC_KEYS = setOf(
        "naturalness", "interaction", "comprehension_repair", "fluency"
    )

    private val EVERYDAY_MODES = setOf("survival", "lounge")
    private val EVERYDAY_EVAL_NAMES = setOf("survival english eval", "free english lounge eval")

    fun isSurvivalCase(caseData: Map<String, Any?>?): Boolean {
        val c = caseData ?: emptyMap()
        if (c["survival_mode"] as? Boolean == false) return false
        return (c["survival_mode"] as? Boolean == true) ||
               ((c["mode"]?.toString() ?: "").trim().lowercase() == "survival") ||
               (c["id"]?.toString()?.startsWith("survival_") == true)
    }

    fun isEverydayCase(caseData: Map<String, Any?>?): Boolean {
        val c = caseData ?: emptyMap()
        val mode = (c["analysis_domain"]?.toString() ?: c["session_mode"]?.toString() ?: c["mode"]?.toString() ?: c["system"]?.toString() ?: "").trim().lowercase()
        
        return isSurvivalCase(c) ||
               EVERYDAY_MODES.contains(mode) ||
               mode == "everyday" ||
               (c["id"]?.toString()?.startsWith("lounge_") == true)
    }

    /** Returns all averageable score columns for the target case / session domain. */
    fun getScoreColumns(caseData: Map<String, Any?>?): List<String> {
        return if (isEverydayCase(caseData)) {
            listOf("naturalness_score", "interaction_score", "repair_score", "fluency_score")
        } else {
            listOf("grammar_score", "medical_accuracy_score", "clinical_reasoning_score", "professionalism_score", "fluency_score")
        }
    }

    /** Faithfully ports `ScoreDomains.py:score_keys(session)` — inspects legacy rows & embedded cases. */
    fun getScoreKeysForSession(session: Map<String, Any?>): List<String> {
        val domain = session["analysis_domain"]?.toString() ?: ""
        if (domain.trim().lowercase() == "everyday") {
            return listOf("naturalness_score", "interaction_score", "repair_score", "fluency_score")
        }
        val mode = session["mode"]?.toString() ?: ""
        if (EVERYDAY_MODES.contains(mode.trim().lowercase())) {
            return listOf("naturalness_score", "interaction_score", "repair_score", "fluency_score")
        }

        val rawCaseStr = session["raw_case_json"]?.toString() ?: "{}"
        try {
            val obj = JSONObject(rawCaseStr)
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keys()) {
                map[key] = obj.opt(key)
            }
            if (isEverydayCase(map)) {
                return listOf("naturalness_score", "interaction_score", "repair_score", "fluency_score")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // fall back to checking legacy survival metric presence
        }

        val presentKeys = session.keys
        val hasOnlySurvivalKeys = SURVIVAL_METRIC_KEYS.any { presentKeys.contains(it) || presentKeys.contains("${it}_score") } &&
                !presentKeys.contains("medical_accuracy_score")
        if (hasOnlySurvivalKeys) {
            return listOf("naturalness_score", "interaction_score", "repair_score", "fluency_score")
        }

        return listOf("grammar_score", "medical_accuracy_score", "clinical_reasoning_score", "professionalism_score", "fluency_score")
    }

    fun inferAnalysisDomain(
        caseData: Map<String, Any?>? = null,
        evalData: Map<String, Any?>? = null,
        mode: String? = null,
        evalTemplate: String? = null
    ): String {
        val eData = evalData ?: emptyMap()
        val metricsRaw = eData["metrics"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val metricKeys = metricsRaw.keys.map { it.toString() }.toSet()
        
        val evalNames = setOf(
            evalTemplate?.trim()?.lowercase() ?: "",
            eData["name"]?.toString()?.trim()?.lowercase() ?: ""
        )

        val everyday = EVERYDAY_MODES.contains(mode?.trim()?.lowercase() ?: "") ||
                       isEverydayCase(caseData) ||
                       metricKeys.intersect(setOf("naturalness", "interaction", "comprehension_repair")).isNotEmpty() ||
                       evalNames.intersect(EVERYDAY_EVAL_NAMES).isNotEmpty()

        return if (everyday) "everyday" else "clinical"
    }

    fun isEverydaySession(session: Map<String, Any?>): Boolean {
        if ((session["analysis_domain"]?.toString() ?: "").trim().lowercase() == "everyday") return true
        if (EVERYDAY_MODES.contains((session["mode"]?.toString() ?: "").trim().lowercase())) return true
        
        val rawCaseStr = session["raw_case_json"]?.toString() ?: "{}"
        try {
            val obj = JSONObject(rawCaseStr)
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keys()) {
                map[key] = obj.opt(key)
            }
            return isEverydayCase(map)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return false
        }
    }

    /** Faithful port of `is_survival_session` — recognizes current + legacy Survival rows. */
    fun isSurvivalSession(session: Map<String, Any?>): Boolean {
        if ((session["mode"]?.toString() ?: "").trim().lowercase() == "survival") return true
        val rawCaseStr = session["raw_case_json"]?.toString() ?: "{}"
        return try {
            val obj = JSONObject(rawCaseStr)
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keys()) {
                map[key] = obj.opt(key)
            }
            isSurvivalCase(map)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            false
        }
    }

    fun clinicalSessions(sessions: List<Map<String, Any?>>?): List<Map<String, Any?>> {
        return (sessions ?: emptyList()).filter { !isEverydaySession(it) }
    }

    fun normalizeSurvivalScores(scores: Map<String, Any?>?): Map<String, Any> {
        val s = scores ?: emptyMap()
        
        val fluency = s["fluency"] ?: s["communication_fluency"]
        val naturalness = s["naturalness"] ?: s["grammar"]
        val interaction = s["interaction"] ?: s["medical_accuracy"] ?: fluency
        val repair = s["comprehension_repair"] ?: s["clinical_reasoning"]

        val result = mutableMapOf<String, Any>()
        if (naturalness != null) result["naturalness"] = naturalness
        if (interaction != null) result["interaction"] = interaction
        if (repair != null) result["comprehension_repair"] = repair
        if (fluency != null) result["fluency"] = fluency

        return result
    }
}
