package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * A case's optional `sp_script`: the standardized-patient script shared by every track.
 *
 * It is authored once, in English, inside the case JSON (see docs/CASE_OPTIONAL_FIELDS.md): the
 * triage vitals, the facts the patient gives when asked (pertinent positives and negatives), and
 * the examination findings, each keyed to a maneuver in `data/exam_maneuvers.json`. The English
 * encounter patient and the Korean CPX patient both read the same script, so fixing a case fixes
 * it everywhere.
 *
 * Nothing here calls a model. Findings are revealed by matching a learner turn against the
 * maneuver vocabulary's trigger phrases, the same local and deterministic way investigation results
 * are released ([InvestigationResults]); the voice model never chooses or invents a finding.
 */
object SpScript {

    const val CASE_KEY = "sp_script"
    const val MANEUVER_ASSET = "exam_maneuvers.json"
    const val RESPONSE_PAIN = "pain"

    data class Vitals(
        val tempC: Double? = null,
        val heartRate: Int? = null,
        val respRate: Int? = null,
        val bloodPressure: String = "",
        val spo2: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = tempC == null && heartRate == null && respRate == null && bloodPressure.isBlank() && spo2 == null
    }

    data class Fact(val topic: String, val answer: String)

    data class Finding(val maneuver: String, val finding: String, val response: String) {
        val painful: Boolean get() = response == RESPONSE_PAIN
    }

    data class Script(val vitals: Vitals, val history: List<Fact>, val exam: List<Finding>) {
        val isEmpty: Boolean get() = vitals.isEmpty && history.isEmpty() && exam.isEmpty()
    }

    data class Maneuver(
        val id: String,
        val en: String,
        val ko: String,
        val triggersEn: List<String>,
        val triggersKo: List<String>,
    )

    // ── Parsing ────────────────────────────────────────────────────────────────────────────

    fun parse(case: JSONObject): Script? {
        val block = case.optJSONObject(CASE_KEY) ?: return null
        val v = block.optJSONObject("vitals")
        val vitals = if (v == null) Vitals() else Vitals(
            tempC = v.optDouble("temp_c").takeIf { !it.isNaN() },
            heartRate = v.optInt("hr", -1).takeIf { it > 0 },
            respRate = v.optInt("rr", -1).takeIf { it > 0 },
            bloodPressure = v.optString("bp").trim(),
            spo2 = v.optInt("spo2", -1).takeIf { it > 0 },
        )
        val history = objects(block.optJSONArray("history")).mapNotNull { o ->
            val topic = o.optString("topic").trim()
            val answer = o.optString("answer").trim()
            if (topic.isEmpty() || answer.isEmpty()) null else Fact(topic, answer)
        }
        val exam = objects(block.optJSONArray("exam")).mapNotNull { o ->
            val maneuver = o.optString("maneuver").trim()
            val finding = o.optString("finding").trim()
            if (maneuver.isEmpty() || finding.isEmpty()) null
            else Finding(maneuver, finding, o.optString("response").trim().lowercase())
        }
        return Script(vitals, history, exam).takeUnless { it.isEmpty }
    }

    fun parse(caseJson: String): Script? = runCatching { parse(JSONObject(caseJson)) }.getOrNull()

    fun parseManeuvers(text: String): Map<String, Maneuver> {
        val rows = runCatching { JSONObject(text).optJSONArray("maneuvers") }.getOrNull() ?: return emptyMap()
        return objects(rows).mapNotNull { o ->
            val id = o.optString("id").trim()
            if (id.isEmpty()) null else Maneuver(
                id = id,
                en = o.optString("en").trim().ifEmpty { id },
                ko = o.optString("ko").trim().ifEmpty { o.optString("en").trim().ifEmpty { id } },
                triggersEn = strings(o.optJSONArray("triggers_en")),
                triggersKo = strings(o.optJSONArray("triggers_ko")),
            )
        }.associateBy { it.id }
    }

    // ── Detection ──────────────────────────────────────────────────────────────────────────

    /**
     * A turn only counts as an examination when it also says the learner is doing something:
     * "피부가 가려우세요?" names the skin but examines nothing, "피부를 좀 볼게요" does. Matching
     * ignores spaces because Korean speech recognition splits and joins words unpredictably.
     */
    private val KO_INTENT = listOf(
        "진찰", "검사하", "검사를하", "확인하겠", "확인할게", "확인해보", "확인해볼", "볼게요", "볼께요", "보겠습니다",
        "보도록하", "만져", "만지겠", "눌러", "누르겠", "누를게", "두드", "들어보", "들어볼", "청진", "재볼", "재보겠",
        "재겠", "측정", "해보세요", "해주세요", "해보시겠", "해볼게", "보여주", "누워", "누우세요", "앉아", "앉으세요",
        "일어서", "따라해", "따라오", "움직여", "벌려", "감아", "떠보", "뻗어", "쥐어", "밀어", "당겨", "비춰", "비추",
    )
    private val EN_INTENT = listOf(
        "examine", "exam", "let me", "i'll", "i will", "i'm going to", "i am going to", "going to", "check", "listen",
        "feel", "press", "look", "take", "measure", "can you", "could you", "please",
    )

    private fun squash(text: String): String = text.lowercase().replace(Regex("\\s+"), "")

    /** Maneuver ids this learner turn performs, in vocabulary order. */
    fun detect(turn: String, maneuvers: Collection<Maneuver>, korean: Boolean): List<String> {
        if (turn.isBlank()) return emptyList()
        return if (korean) {
            val text = squash(turn)
            if (KO_INTENT.none { text.contains(squash(it)) }) return emptyList()
            maneuvers.filter { m -> m.triggersKo.any { t -> t.isNotBlank() && text.contains(squash(t)) } }.map { it.id }
        } else {
            val text = " " + turn.lowercase().replace(Regex("\\s+"), " ") + " "
            if (EN_INTENT.none { text.contains(it) }) return emptyList()
            maneuvers.filter { m -> m.triggersEn.any { t -> t.isNotBlank() && text.contains(t.lowercase()) } }.map { it.id }
        }
    }

    /** The case's findings for [maneuverIds], in the order the learner performed them. */
    fun findingsFor(script: Script, maneuverIds: List<String>): List<Finding> =
        maneuverIds.flatMap { id -> script.exam.filter { it.maneuver == id } }

    // ── Prompt blocks ──────────────────────────────────────────────────────────────────────

    /**
     * The scripted answers for the English standard patient. Appended to its hidden-information
     * block; the persona's own brevity rules still decide how much of each answer is said.
     */
    fun englishPatientBlock(script: Script, maneuvers: Map<String, Maneuver> = emptyMap()): String = buildString {
        if (script.history.isNotEmpty()) {
            append("\n\nSCRIPTED ANSWERS — the facts of this case, one per topic. Give a fact only when the doctor asks about that topic, in your own casual words, and never contradict it. ")
            append("Anything not covered here or above gets a short \"No\" or \"I'm not sure\" — do not invent symptoms. ")
            append("If your Ideas above name a medical diagnosis you were never told, you do not know that term: voice it as a vague lay worry, as the answers here do.\n")
            script.history.forEach { append("- ").append(it.topic).append(": ").append(it.answer).append('\n') }
        }
        if (script.exam.isNotEmpty()) {
            append("\nPHYSICAL EXAM — the doctor may describe examining you. React only as below; never state objective findings (sounds, measurements) yourself.\n")
            script.exam.forEach { f ->
                val name = maneuvers[f.maneuver]?.en ?: f.maneuver.replace('_', ' ')
                append("- ").append(name).append(": ")
                append(if (f.painful) "it hurts when this is done (wince, say where it hurts)" else "no pain or discomfort")
                append('\n')
            }
        }
    }.trimEnd()

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    /** "36.8℃" with one decimal, or "" when unknown. */
    fun formatTemp(tempC: Double?): String =
        tempC?.let { String.format(java.util.Locale.US, "%.1f℃", it) }.orEmpty()

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.opt(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }
}
