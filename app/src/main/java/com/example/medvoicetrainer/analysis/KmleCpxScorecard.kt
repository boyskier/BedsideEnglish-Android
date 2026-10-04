package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The Korean CPX feedback card, built from the session's case snapshot and the grader's raw JSON.
 *
 * The grader only judges each checklist line (done / partial / missed / not applicable), each PPI
 * behaviour (1-5) and each diagnosis-and-plan point; every number the learner sees is computed
 * here from those judgements with the weights in `common.json`. That keeps the score reproducible
 * — History rebuilds the same card from the same two stored strings — and stops a model from
 * handing out a score its own item-by-item verdicts do not support.
 */
object KmleCpxScorecard {

    data class ScoredItem(
        val key: String,
        val section: String,
        val text: String,
        val say: String,
        val critical: Boolean,
        val status: String,
        val evidence: String,
        /** True when the learner corrected the grader's verdict for this line (see [withOverride]). */
        val overridden: Boolean = false,
    )

    data class PpiScore(
        val id: String,
        val label: String,
        val description: String,
        val score: Int?,
        val comment: String,
        /** The level's own name ("우수"), or "n점" for a snapshot without level names. */
        val level: String = "",
        val scale: Int = 5,
    )

    data class ContentScore(val id: String, val label: String, val description: String, val rating: String, val comment: String)

    data class SectionScore(
        val key: String,
        val label: String,
        val weight: Double,
        /** 0-100, or null when nothing in the section could be scored. */
        val percent: Int?,
        val items: List<ScoredItem> = emptyList(),
    )

    data class SafetyFlag(val issue: String, val severity: String, val quote: String)

    data class ModelLine(val situation: String, val better: String)

    data class Scorecard(
        val presentationTitle: String,
        val doorNote: String,
        val disclaimer: String,
        /** 0-100 weighted total, or null when the session was not graded. */
        val overall: Int?,
        val sections: List<SectionScore>,
        val ppi: List<PpiScore>,
        val content: List<ContentScore>,
        val studentDiagnosis: String,
        val actualDiagnosis: String,
        val safetyFlags: List<SafetyFlag>,
        val summary: String,
        val strengths: List<String>,
        val improvements: List<String>,
        val modelLines: List<ModelLine>,
        /** Checklist lines marked [핵심] that were missed or only partly done. */
        val missedKeyItems: List<ScoredItem>,
        /** True when no analysis ran (no API key): the checklist is shown for self-review only. */
        val locked: Boolean,
        /** What this session asked for ("병력청취만" …). */
        val scopeLabel: String = "",
        /** The problem sheet as the learner saw it. */
        val situationCard: KmleCpx.SituationCard? = null,
    )

    /** Credit for a checklist status; null means the line does not count toward the section. */
    fun credit(status: String): Double? = when (status) {
        KmleCpx.STATUS_DONE -> 1.0
        KmleCpx.STATUS_PARTIAL -> 0.5
        KmleCpx.STATUS_MISSED -> 0.0
        else -> null
    }

    fun contentCredit(rating: String): Double? = when (rating) {
        "good" -> 1.0
        "partial" -> 0.5
        "poor", "not_stated" -> 0.0
        else -> null
    }

    fun ppiCredit(score: Int?, scale: Int = 5): Double? =
        score?.takeIf { scale > 1 && it in 1..scale }?.let { (it - 1).toDouble() / (scale - 1) }

    fun normalizeStatus(raw: String): String = when (raw.trim().lowercase()) {
        "done", "yes", "met", "complete", "completed", "pass", "passed", "수행" -> KmleCpx.STATUS_DONE
        "partial", "partially", "partly", "부분" -> KmleCpx.STATUS_PARTIAL
        "missed", "no", "not_done", "not done", "missing", "fail", "미수행" -> KmleCpx.STATUS_MISSED
        "na", "n/a", "not_applicable", "not applicable", "해당없음" -> KmleCpx.STATUS_NA
        else -> KmleCpx.STATUS_UNSCORED
    }

    fun normalizeRating(raw: String): String = when (raw.trim().lowercase()) {
        "good", "correct", "appropriate", "적절" -> "good"
        "partial", "partially", "부분" -> "partial"
        "poor", "incorrect", "wrong", "inappropriate", "부적절" -> "poor"
        "not_stated", "not stated", "none", "missing", "언급 없음" -> "not_stated"
        else -> ""
    }

    fun ratingLabel(rating: String): String = when (rating) {
        "good" -> "적절"
        "partial" -> "부분적"
        "poor" -> "부적절"
        "not_stated" -> "언급 없음"
        else -> "채점 안 됨"
    }

    fun statusLabel(status: String): String = when (status) {
        KmleCpx.STATUS_DONE -> "수행"
        KmleCpx.STATUS_PARTIAL -> "부분"
        KmleCpx.STATUS_MISSED -> "누락"
        KmleCpx.STATUS_NA -> "해당 없음"
        else -> "채점 안 됨"
    }

    /** Extract the JSON object from a model reply that may wrap it in fences or prose. */
    fun parseModelJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        runCatching { return JSONObject(cleaned) }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    /** Build the card, or null when [caseJson] is not a CPX session case. */
    fun build(caseJson: String, rawModelJson: String?): Scorecard? {
        val session = KmleCpx.sessionCase(caseJson) ?: return null
        val root = parseModelJson(rawModelJson)
        val locked = root == null || root.optBoolean("_locked", false)
        return build(session, if (locked) null else root)
    }

    fun build(session: KmleCpx.SessionCase, root: JSONObject?): Scorecard {
        val common = session.common
        val verdicts = mutableMapOf<String, Pair<String, String>>()
        val labels = mutableMapOf<String, String>()
        root?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").ifBlank { o.optString("id") }.trim()
                if (key.isEmpty() || key in verdicts) continue
                verdicts[key] = normalizeStatus(o.optString("status")) to o.optString("evidence").trim()
                o.optString("label").trim().takeIf { it.isNotEmpty() }?.let { labels[key] = it }
            }
        }
        // The learner's own corrections ("I did ask that; the transcript garbled it") win over the
        // grader's verdict. They live beside the reply so History rebuilds the corrected card.
        val overrides = root?.optJSONObject(OVERRIDES_KEY)?.let { o ->
            o.keys().asSequence().mapNotNull { k ->
                normalizeStatus(o.optString(k)).takeIf { it != KmleCpx.STATUS_UNSCORED }?.let { k to it }
            }.toMap()
        }.orEmpty()
        val ppiVerdicts = mutableMapOf<String, Pair<Int?, String>>()
        root?.optJSONArray("ppi")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || id in ppiVerdicts) continue
                val score = when (val s = o.opt("score")) {
                    is Number -> s.toDouble().roundToInt()
                    is String -> s.trim().toDoubleOrNull()?.roundToInt()
                    else -> null
                }?.takeIf { it in 1..common.ppiScale }
                ppiVerdicts[id] = score to o.optString("comment").trim()
            }
        }
        val contentVerdicts = mutableMapOf<String, Pair<String, String>>()
        root?.optJSONArray("clinical_content")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || id in contentVerdicts) continue
                contentVerdicts[id] = normalizeRating(o.optString("rating")) to o.optString("comment").trim()
            }
        }

        val ppi = KmleCpx.scoredPpi(session).map { item ->
            val (score, comment) = ppiVerdicts[item.id] ?: (null to "")
            PpiScore(item.id, item.text, item.description, score, comment, common.ppiLevelLabel(score), common.ppiScale)
        }
        val content = KmleCpx.scoredContent(session).map { item ->
            val (rating, comment) = contentVerdicts[item.id] ?: ("" to "")
            ContentScore(item.id, item.text, item.description, rating, comment)
        }

        val sections = session.checklist.map { section ->
            when (section.key) {
                KmleCpx.SECTION_PPI -> SectionScore(
                    section.key, section.label, common.weights[section.key] ?: 0.0,
                    percentOf(ppi.map { ppiCredit(it.score, common.ppiScale) }),
                )
                KmleCpx.SECTION_CONTENT -> SectionScore(
                    section.key, section.label, common.weights[section.key] ?: 0.0,
                    percentOf(content.map { contentCredit(it.rating) }),
                )
                else -> {
                    val items = section.items.map { ci ->
                        val (status, evidence) = verdicts[ci.key] ?: (KmleCpx.STATUS_UNSCORED to "")
                        // "Not applicable" is only honoured for a line that can genuinely not apply;
                        // anything else the grader waves away is still something the student owed.
                        val resolved = if (status == KmleCpx.STATUS_NA && !ci.item.conditional && ci.item.applies.isEmpty() && !isContextual(ci)) {
                            KmleCpx.STATUS_MISSED
                        } else status
                        // A case's own must-ask line is authored in English; show the grader's Korean
                        // label when it gave one.
                        val text = if (ci.item.fromCase) labels[ci.key] ?: ci.item.text else ci.item.text
                        val corrected = overrides[ci.key]
                        ScoredItem(
                            ci.key, section.key, text, ci.item.say, ci.item.key,
                            corrected ?: resolved, evidence, overridden = corrected != null && corrected != resolved,
                        )
                    }
                    SectionScore(
                        section.key, section.label, common.weights[section.key] ?: 0.0,
                        percentOf(items.map { credit(it.status) }), items,
                    )
                }
            }
        }

        val overall = if (root == null) null else weightedOverall(sections)
        val missedKey = sections.flatMap { it.items }
            .filter { it.critical && (it.status == KmleCpx.STATUS_MISSED || it.status == KmleCpx.STATUS_PARTIAL) }

        return Scorecard(
            presentationTitle = session.presentation.title,
            doorNote = session.doorNote,
            disclaimer = common.disclaimer,
            overall = overall,
            sections = sections,
            ppi = ppi,
            content = content,
            studentDiagnosis = root?.optString("student_diagnosis")?.trim().orEmpty(),
            actualDiagnosis = root?.optString("actual_diagnosis")?.trim().orEmpty()
                .ifEmpty { session.case.optJSONObject("teaching")?.optString("diagnosis")?.trim().orEmpty() },
            safetyFlags = parseSafetyFlags(root?.optJSONArray("safety_flags")),
            summary = root?.optString("summary")?.trim().orEmpty(),
            strengths = strings(root?.optJSONArray("strengths")),
            improvements = strings(root?.optJSONArray("improvements")),
            modelLines = parseModelLines(root?.optJSONArray("model_lines")),
            missedKeyItems = missedKey,
            locked = root == null,
            scopeLabel = KmleCpx.scopeLabel(session.scope),
            situationCard = session.situationCard,
        )
    }

    /**
     * Common history lines that legitimately do not apply to every patient even without an
     * explicit condition (a family history is not owed for a sprained ankle, nor a social history
     * for a toddler's rash), so the grader's "not applicable" is accepted for them.
     */
    private val CONTEXTUAL_COMMON_KEYS = setOf("history.family", "history.social", "education.feasibility")

    private fun isContextual(item: KmleCpx.ChecklistItem): Boolean = item.key in CONTEXTUAL_COMMON_KEYS

    fun percentOf(credits: List<Double?>): Int? {
        val counted = credits.filterNotNull()
        if (counted.isEmpty()) return null
        return (counted.sum() / counted.size * 100).roundToInt().coerceIn(0, 100)
    }

    /** Weighted mean of the section percents; sections with nothing scorable drop out of the weights. */
    fun weightedOverall(sections: List<SectionScore>): Int? {
        val scored = sections.filter { it.percent != null && it.weight > 0 }
        val totalWeight = scored.sumOf { it.weight }
        if (scored.isEmpty() || totalWeight <= 0) return null
        return (scored.sumOf { it.weight * it.percent!! } / totalWeight).roundToInt().coerceIn(0, 100)
    }

    private fun parseSafetyFlags(arr: JSONArray?): List<SafetyFlag> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val issue = o.optString("issue").trim()
            if (issue.isEmpty()) null
            else SafetyFlag(issue, o.optString("severity").trim().lowercase().ifEmpty { "major" }, o.optString("quote").trim())
        }
    }

    private fun parseModelLines(arr: JSONArray?): List<ModelLine> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val better = o.optString("better").trim()
            if (better.isEmpty()) null else ModelLine(o.optString("situation").trim(), better)
        }
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.opt(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    const val OVERRIDES_KEY = "_overrides"

    /**
     * The stored grader reply with the learner's correction for one checklist line. Passing the
     * grader's own verdict back removes the correction.
     */
    fun withOverride(rawEvalJson: String?, key: String, status: String, graderStatus: String? = null): String? {
        val root = parseModelJson(rawEvalJson) ?: return null
        if (root.optBoolean("_locked", false)) return null
        val overrides = root.optJSONObject(OVERRIDES_KEY) ?: JSONObject().also { root.put(OVERRIDES_KEY, it) }
        if (status == graderStatus) overrides.remove(key) else overrides.put(key, status)
        return root.toString()
    }

    /** Stored in `rawEvalJson` when no analysis key was available, so History shows a locked card. */
    fun lockedPlaceholder(): String = JSONObject().put("_locked", true).toString()
}
