package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Keeps Patient Follow-up navigation and final evaluation on one authored task list.
 *
 * `learning_objectives` drives the live checklist and is therefore the canonical list here too.
 * Model output is evidence for those items, not authority to add, rename, or omit checklist rows.
 */
object FollowUpEvaluation {
    fun isFollowUp(mode: String, caseJson: String): Boolean =
        mode == "follow_up" || runCatching {
            JSONObject(caseJson).optString("encounter_type") == "follow_up"
        }.getOrDefault(false)

    /**
     * Rebuild model checklist output in the exact order and wording used during the live visit.
     * Unknown model rows are discarded and omitted canonical rows become explicit failures.
     */
    fun canonicalChecklist(caseJson: String, modelResults: JSONArray?): JSONArray {
        val objectives = runCatching {
            val array = JSONObject(caseJson).optJSONArray("learning_objectives") ?: return@runCatching emptyList()
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf(String::isNotEmpty)
            }
        }.getOrDefault(emptyList())

        // Legacy/imported follow-up sessions may not have authored objectives. Preserve their
        // returned checklist instead of erasing useful historical feedback.
        if (objectives.isEmpty()) return normalizedLegacyChecklist(modelResults)

        val returnedByName = buildMap<String, JSONObject> {
            val returned = modelResults ?: return@buildMap
            for (index in 0 until returned.length()) {
                val item = returned.optJSONObject(index) ?: continue
                val name = item.optString("item").trim()
                val key = checklistKey(name)
                if (key.isNotEmpty() && key !in this) put(key, item)
            }
        }

        return JSONArray().apply {
            objectives.forEach { objective ->
                val returned = returnedByName[checklistKey(objective)]
                val passed = returned?.let {
                    if (it.has("passed")) it.optBoolean("passed") else it.optBoolean("elicited")
                } ?: false
                put(
                    JSONObject()
                        .put("item", objective)
                        .put("required", true)
                        .put("passed", passed)
                        .put("status", if (passed) "passed" else "failed")
                        .put(
                            "evidence",
                            returned?.opt("evidence")?.takeUnless { it == JSONObject.NULL }
                                ?: JSONObject.NULL,
                        )
                )
            }
        }
    }

    /** Required applicable tasks are the denominator; optional tasks are never allowed to dilute it. */
    fun completeness(checklist: JSONArray?): Double? {
        val applicable = mutableListOf<JSONObject>()
        val required = mutableListOf<JSONObject>()
        val array = checklist ?: return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("status") == "not_applicable") continue
            applicable += item
            if (item.optBoolean("required", false)) required += item
        }
        val denominator = required.ifEmpty { applicable }
        if (denominator.isEmpty()) return null
        return denominator.count { it.optBoolean("passed", false) }.toDouble() / denominator.size
    }

    /** Only used for historical sessions without a checklist; never permits an invalid percentage. */
    fun boundedLegacyCompleteness(raw: Any?): Double? {
        val number = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }?.takeIf(Double::isFinite) ?: return null
        return (if (number > 1.0) number / 100.0 else number).coerceIn(0.0, 1.0)
    }

    private fun normalizedLegacyChecklist(modelResults: JSONArray?): JSONArray {
        val result = JSONArray()
        val source = modelResults ?: return result
        for (index in 0 until source.length()) {
            val original = source.optJSONObject(index) ?: continue
            val name = original.optString("item").trim()
            if (name.isEmpty()) continue
            val status = original.optString("status").ifBlank {
                if (original.optBoolean("passed", original.optBoolean("elicited"))) "passed" else "failed"
            }
            val passed = status != "not_applicable" &&
                original.optBoolean("passed", original.optBoolean("elicited"))
            result.put(
                JSONObject(original.toString())
                    .put("item", name)
                    .put("required", status != "not_applicable" && original.optBoolean("required", true))
                    .put("passed", passed)
                    .put("status", status)
            )
        }
        return result
    }

    /** Tolerate casing and whitespace drift without fuzzy-matching a different clinical task. */
    private fun checklistKey(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}
