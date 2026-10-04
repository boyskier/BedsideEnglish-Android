package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * The exam's tenth room: 기본진료술기, one of nine hands-on skills scored by a faculty examiner on a
 * model or standardized patient. A voice model cannot judge hand technique, so this is what
 * students actually drill with: the step checklist (preparation → patient → procedure → wrap-up,
 * must-not-miss steps marked), common errors, key numbers, and a 12-minute self-check.
 * Content: `data/kmle_cpx/osce/<id>.json`.
 */
object KmleOsce {
    const val DIR = "kmle_cpx/osce"
    val PHASES = listOf("준비", "환자", "술기", "마무리")

    data class Step(val id: String, val phase: String, val text: String, val say: String, val key: Boolean)

    data class Skill(
        val id: String,
        val title: String,
        val officialSkill: String,
        val group: String,
        val scenario: String,
        val equipment: List<String>,
        val steps: List<Step>,
        val commonErrors: List<String>,
        val notes: List<String>,
    )

    fun parse(text: String): Skill? = runCatching { parse(JSONObject(text)) }.getOrNull()

    fun parse(o: JSONObject): Skill? {
        val id = o.optString("id").trim()
        val title = o.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val steps = o.optJSONArray("steps")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val sid = s.optString("id").trim()
                val stext = s.optString("text").trim()
                if (sid.isEmpty() || stext.isEmpty()) null
                else Step(sid, s.optString("phase").trim().ifEmpty { "술기" }, stext, s.optString("say").trim(), s.optBoolean("key", false))
            }
        }.orEmpty()
        if (steps.isEmpty()) return null
        return Skill(
            id = id,
            title = title,
            officialSkill = o.optString("official_skill").trim(),
            group = o.optString("group").trim().ifEmpty { "기타" },
            scenario = o.optString("scenario").trim(),
            equipment = strings(o.optJSONArray("equipment")),
            steps = steps,
            commonErrors = strings(o.optJSONArray("common_errors")),
            notes = strings(o.optJSONArray("notes")),
        )
    }

    /** Self-check result: share of steps ticked, and the must-not-miss steps left unticked. */
    data class SelfCheck(val done: Int, val total: Int, val missedKey: List<Step>) {
        val percent: Int get() = if (total == 0) 0 else done * 100 / total
    }

    fun selfCheck(skill: Skill, ticked: Set<String>): SelfCheck {
        val done = skill.steps.count { it.id in ticked }
        return SelfCheck(done, skill.steps.size, skill.steps.filter { it.key && it.id !in ticked })
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.opt(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }
}
