package com.example.medvoicetrainer.ui

import org.json.JSONArray
import org.json.JSONObject

/** Lightweight learner-owned labels for bundled Patient Encounter cases. */
enum class EncounterCaseTag(val storageValue: String) {
    FAVORITE("favorite"),
    TODO("todo"),
    DIFFICULT("difficult");

    companion object {
        fun fromStorage(value: String): EncounterCaseTag? = entries.firstOrNull {
            it.storageValue == value
        }
    }
}

/**
 * Stores case labels in one portable preference instead of adding a Room table for bundled cases.
 * Unknown or malformed entries are ignored so a damaged/forward-version value cannot break Practice.
 */
internal object EncounterCaseTagStore {
    const val SETTING_KEY = "encounter_case_tags"

    fun decode(raw: String): Map<String, Set<EncounterCaseTag>> {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        return buildMap {
            root.keys().forEach { caseId ->
                if (caseId.isBlank()) return@forEach
                val values = root.optJSONArray(caseId) ?: return@forEach
                val tags = buildSet {
                    repeat(values.length()) { index ->
                        EncounterCaseTag.fromStorage(values.optString(index))?.let(::add)
                    }
                }
                if (tags.isNotEmpty()) put(caseId, tags)
            }
        }
    }

    fun encode(tagsByCase: Map<String, Set<EncounterCaseTag>>): String {
        val root = JSONObject()
        tagsByCase.toSortedMap().forEach { (caseId, tags) ->
            if (caseId.isNotBlank() && tags.isNotEmpty()) {
                root.put(
                    caseId,
                    JSONArray(tags.sortedBy { it.ordinal }.map(EncounterCaseTag::storageValue)),
                )
            }
        }
        return root.toString()
    }

    fun toggle(
        tagsByCase: Map<String, Set<EncounterCaseTag>>,
        caseId: String,
        tag: EncounterCaseTag,
    ): Map<String, Set<EncounterCaseTag>> {
        if (caseId.isBlank()) return tagsByCase
        val nextTags = tagsByCase[caseId].orEmpty().toMutableSet().apply {
            if (!add(tag)) remove(tag)
        }
        return tagsByCase.toMutableMap().apply {
            if (nextTags.isEmpty()) remove(caseId) else put(caseId, nextTags.toSet())
        }.toMap()
    }
}
