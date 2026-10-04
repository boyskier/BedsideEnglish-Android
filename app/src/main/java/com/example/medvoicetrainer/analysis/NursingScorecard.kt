package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The nursing-specific half of a nursing session's feedback, built from three sources:
 *
 * 1. The LLM's `nursing_assessment` object (requested by [NursingTrack.assessmentContract]):
 *    per-criterion bands or scores, one status per brief `must_include` task, safety flags, and a
 *    stronger rewrite of the learner's weakest line.
 * 2. The case JSON: family, counterpart, framework, and the brief's task list — the authority on
 *    *what* was asked, so a model that drops or rewords a task cannot make it vanish.
 * 3. The transcript, through [NursingSignals]: deterministic checks that need no model at all.
 *
 * Everything numeric that the learner reads as a verdict — the OET scaled-score estimate, the
 * grade, the task-completion fraction, the readiness level — is computed here, in code, from
 * those inputs. The model supplies judgements per criterion; it never supplies the grade. That
 * keeps the verdict stable across providers and re-renders, and testable.
 *
 * Stored nowhere new: the result is rebuilt from `rawEvalJson` + `rawCaseJson` + the transcript,
 * so History shows the identical card and no database migration is needed.
 */
object NursingScorecard {

    /** One criterion as rendered: a band (OET) or a 0–100 score (rubric). */
    data class Criterion(
        val key: String,
        val label: String,
        val score: Double?,
        val max: Int,
        val evidence: String,
        val group: String = "",
    ) {
        val fraction: Double? get() = score?.let { (it / max).coerceIn(0.0, 1.0) }
    }

    data class TaskItem(val item: String, val status: String, val evidence: String)

    data class SafetyFlag(val issue: String, val severity: String, val quote: String)

    data class LineUpgrade(val original: String, val better: String, val why: String)

    data class OetEstimate(
        val raw: Int,
        val rawMax: Int,
        val scaled: Int,
        val grade: String,
        val weakest: List<String>,
    ) {
        val meetsGradeB: Boolean get() = scaled >= GRADE_B_SCALED
    }

    data class Verdict(val level: String, val headline: String)

    data class Scorecard(
        val framework: String,
        val taskFamily: String,
        val counterpart: String,
        val criteria: List<Criterion>,
        val oet: OetEstimate?,
        val taskItems: List<TaskItem>,
        val taskCompletion: Double?,
        val safetyFlags: List<SafetyFlag>,
        val upgrades: List<LineUpgrade>,
        val signals: NursingSignals.Result,
        val verdict: Verdict,
        val learnerWords: Int,
    )

    // ── OET ──────────────────────────────────────────────────────────────────────────────────

    /** The four OET linguistic criteria, each banded 0–6. */
    val OET_LINGUISTIC = listOf(
        "intelligibility" to "Intelligibility",
        "fluency" to "Fluency",
        "appropriateness" to "Appropriateness of language",
        "grammar_expression" to "Resources of grammar & expression",
    )

    /** The five OET clinical-communication criteria, each banded 0–3. */
    val OET_CLINICAL = listOf(
        "relationship_building" to "Relationship-building",
        "patient_perspective" to "Understanding the patient's perspective",
        "providing_structure" to "Providing structure",
        "information_gathering" to "Information-gathering",
        "information_giving" to "Information-giving",
    )

    const val OET_LINGUISTIC_MAX = 6
    const val OET_CLINICAL_MAX = 3
    const val OET_RAW_MAX = 4 * OET_LINGUISTIC_MAX + 5 * OET_CLINICAL_MAX // 39
    const val GRADE_B_SCALED = 350

    /**
     * Raw total treated as the Grade B threshold (350). OET does not publish its raw-to-scaled
     * conversion, so this anchor is a deliberately conservative practice estimate: 28/39 means a
     * performance like 5-4-5-4 on the linguistic criteria with a solid 2 on every clinical one.
     * Setting it slightly high is intentional — an app that tells a nurse "B" when an examiner
     * would say "C+" costs them an exam fee and months.
     */
    const val OET_B_ANCHOR_RAW = 28

    /**
     * Practice estimate of the OET scaled score (0–500, in steps of 10) from a raw criterion total.
     * Piecewise-linear through (0, 0), ([OET_B_ANCHOR_RAW], 350) and ([OET_RAW_MAX], 500).
     */
    fun oetScaledEstimate(raw: Int): Int {
        val r = raw.coerceIn(0, OET_RAW_MAX)
        val scaled = if (r <= OET_B_ANCHOR_RAW) {
            r * GRADE_B_SCALED.toDouble() / OET_B_ANCHOR_RAW
        } else {
            GRADE_B_SCALED + (r - OET_B_ANCHOR_RAW) * (500.0 - GRADE_B_SCALED) / (OET_RAW_MAX - OET_B_ANCHOR_RAW)
        }
        return ((scaled / 10.0).roundToInt() * 10).coerceIn(0, 500)
    }

    /** OET's published grade bands for a scaled score. */
    fun oetGrade(scaled: Int): String = when {
        scaled >= 450 -> "A"
        scaled >= 350 -> "B"
        scaled >= 300 -> "C+"
        scaled >= 200 -> "C"
        scaled >= 100 -> "D"
        else -> "E"
    }

    // ── Building ─────────────────────────────────────────────────────────────────────────────

    /**
     * Build the scorecard for a finished session, or null when the case is not a nursing case.
     *
     * [analysis] may be null or lack `nursing_assessment` (an older session, a provider that
     * ignored the request, a locked evaluation): the card then degrades to the deterministic
     * checks and the brief's task list with unknown status, rather than disappearing.
     */
    fun build(
        caseJson: String,
        analysis: JSONObject?,
        transcript: List<Pair<String, String>>,
    ): Scorecard? {
        val case = runCatching { JSONObject(caseJson.ifBlank { "{}" }) }.getOrNull() ?: return null
        if (case.optString("session_mode").trim().lowercase() != NursingTrack.SESSION_MODE) return null

        val family = case.optString("nursing_task").trim()
        val counterpart = NursingTrack.counterpartOf(case)
        val framework = NursingTrack.frameworkOf(case)
        val assessment = analysis?.optJSONObject("nursing_assessment")

        val criteria = if (framework == NursingTrack.FRAMEWORK_OET) {
            oetCriteria(assessment)
        } else {
            rubricCriteria(assessment, analysis)
        }
        val oet = if (framework == NursingTrack.FRAMEWORK_OET) oetEstimate(criteria) else null

        val mustInclude = NursingTrack.mustInclude(case)
        val taskItems = taskItems(mustInclude, assessment?.optJSONArray("task_items"))
        val judged = taskItems.filter { it.status in setOf("done", "partial", "missed") }
        val taskCompletion = if (judged.isEmpty()) null
        else judged.sumOf { if (it.status == "done") 1.0 else if (it.status == "partial") 0.5 else 0.0 } / judged.size

        val safetyFlags = safetyFlags(assessment?.optJSONArray("safety_flags"))
        val upgrades = upgrades(assessment?.optJSONArray("line_upgrades"))
        val signals = NursingSignals.analyze(transcript, family, counterpart, case.optString("urgency"))

        val verdict = verdict(framework, criteria, oet, taskCompletion, safetyFlags, signals.learnerWords)

        return Scorecard(
            framework = framework,
            taskFamily = family,
            counterpart = counterpart,
            criteria = criteria,
            oet = oet,
            taskItems = taskItems,
            taskCompletion = taskCompletion,
            safetyFlags = safetyFlags,
            upgrades = upgrades,
            signals = signals,
            verdict = verdict,
            learnerWords = signals.learnerWords,
        )
    }

    /** Minimum learner words for a verdict to mean anything; below it the card says "too short". */
    const val MIN_WORDS_FOR_VERDICT = 60

    fun verdict(
        framework: String,
        criteria: List<Criterion>,
        oet: OetEstimate?,
        taskCompletion: Double?,
        safetyFlags: List<SafetyFlag>,
        learnerWords: Int,
    ): Verdict {
        val critical = safetyFlags.any { it.severity == "critical" }
        if (learnerWords < MIN_WORDS_FOR_VERDICT) {
            return Verdict("too_short", "Too short to judge — try the full scenario to get a verdict.")
        }
        if (critical) {
            return Verdict("not_yet", "Fix the safety issue first — it would fail this task on its own.")
        }
        if (framework == NursingTrack.FRAMEWORK_OET) {
            if (oet == null) return Verdict("unscored", "Not enough criterion evidence for an OET estimate.")
            val tasksOk = taskCompletion == null || taskCompletion >= 0.75
            return when {
                oet.meetsGradeB && tasksOk -> Verdict("ready", "In the Grade B range on this role-play (practice estimate).")
                oet.meetsGradeB -> Verdict("close", "Grade B-level language, but you missed part of the card's tasks.")
                oet.scaled >= 300 -> Verdict("close", "Close to Grade B — work on your weakest criteria below.")
                else -> Verdict("not_yet", "Below Grade B for now — focus on the weakest criteria below.")
            }
        }
        val scored = criteria.mapNotNull { it.fraction }
        if (scored.isEmpty()) return Verdict("unscored", "The rubric scores for this session are unavailable.")
        val criteriaAverage = scored.average()
        val composite = if (taskCompletion == null) criteriaAverage else criteriaAverage * 0.7 + taskCompletion * 0.3
        val major = safetyFlags.any { it.severity == "major" }
        return when {
            composite >= 0.80 && !major && (taskCompletion ?: 1.0) >= 0.8 ->
                Verdict("ready", "Ready for the real thing — this would hold up on a real shift.")
            composite >= 0.65 ->
                Verdict("close", "Nearly there — tighten the items marked below.")
            else -> Verdict("not_yet", "Not yet — repeat this scenario after reviewing the checks below.")
        }
    }

    private fun oetCriteria(assessment: JSONObject?): List<Criterion> {
        val bands = assessment?.optJSONObject("criteria")
        fun criterion(key: String, label: String, max: Int, group: String): Criterion {
            val entry = bands?.opt(key)
            val (band, evidence) = when (entry) {
                is JSONObject -> entry.opt("band").toBandOrNull(max) to entry.optString("evidence").trim()
                is Number, is String -> entry.toBandOrNull(max) to ""
                else -> null to ""
            }
            return Criterion(key, label, band?.toDouble(), max, evidence, group)
        }
        return OET_LINGUISTIC.map { (k, l) -> criterion(k, l, OET_LINGUISTIC_MAX, "linguistic") } +
            OET_CLINICAL.map { (k, l) -> criterion(k, l, OET_CLINICAL_MAX, "clinical") }
    }

    /** Null when any of the nine bands is missing: a partial sum would silently read as a low grade. */
    fun oetEstimate(criteria: List<Criterion>): OetEstimate? {
        if (criteria.size != OET_LINGUISTIC.size + OET_CLINICAL.size || criteria.any { it.score == null }) return null
        val raw = criteria.sumOf { it.score!!.roundToInt() }
        val scaled = oetScaledEstimate(raw)
        val lowest = criteria.minOf { it.fraction ?: 1.0 }
        val weakest = criteria.filter { (it.fraction ?: 1.0) <= lowest + 1e-9 }.map { it.label }
        return OetEstimate(raw, OET_RAW_MAX, scaled, oetGrade(scaled), weakest)
    }

    /**
     * Rubric criteria: one per metric key the model scored under `nursing_assessment.criteria`
     * (0–100). The five stored clinical-schema scores are *not* reused here — they are already
     * shown as the score bars, and repeating them would make two different numbers look like one.
     */
    private fun rubricCriteria(assessment: JSONObject?, analysis: JSONObject?): List<Criterion> {
        val scores = assessment?.optJSONObject("criteria") ?: return emptyList()
        val labels = assessment.optJSONObject("criteria_labels")
        return scores.keys().asSequence().sorted().mapNotNull { key ->
            val entry = scores.opt(key)
            val (value, evidence, label) = when (entry) {
                is JSONObject -> Triple(entry.opt("score").toScoreOrNull(), entry.optString("evidence").trim(), entry.optString("label").trim())
                else -> Triple(entry.toScoreOrNull(), "", "")
            }
            value ?: return@mapNotNull null
            val display = label.ifEmpty { labels?.optString(key).orEmpty() }.ifEmpty { humanize(key) }
            Criterion(key, display, value, 100, evidence)
        }.toList()
    }

    /**
     * Align the model's task statuses with the brief's own `must_include` list. The brief is the
     * authority on what the tasks are: matching is by index first (the contract asks for the same
     * order), then by text overlap, and a task the model skipped is reported as "unknown" rather
     * than silently dropped or counted as missed.
     */
    fun taskItems(mustInclude: List<String>, returned: JSONArray?): List<TaskItem> {
        val parsed = (0 until (returned?.length() ?: 0)).mapNotNull { i ->
            val o = returned?.optJSONObject(i) ?: return@mapNotNull null
            Triple(o.optString("item").trim(), normalizeStatus(o.optString("status")), o.optString("evidence").trim())
        }
        if (mustInclude.isEmpty()) {
            return parsed.filter { it.first.isNotEmpty() }.map { TaskItem(it.first, it.second, it.third) }
        }
        val used = mutableSetOf<Int>()
        return mustInclude.mapIndexed { index, task ->
            val byIndex = parsed.getOrNull(index)?.takeIf { index !in used && overlap(task, it.first) >= 0.3 }
            val match = byIndex?.also { used += index } ?: parsed.withIndex()
                .filter { it.index !in used }
                .maxByOrNull { overlap(task, it.value.first) }
                ?.takeIf { overlap(task, it.value.first) >= 0.3 }
                ?.also { used += it.index }?.value
            TaskItem(task, match?.second ?: "unknown", match?.third.orEmpty())
        }
    }

    fun normalizeStatus(raw: String): String = when (raw.trim().lowercase()) {
        "done", "passed", "complete", "completed", "met", "yes", "true" -> "done"
        "partial", "partially", "partly", "partially_done", "partially met" -> "partial"
        "missed", "failed", "not done", "no", "false", "missing", "not_done" -> "missed"
        else -> "unknown"
    }

    private fun safetyFlags(array: JSONArray?): List<SafetyFlag> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val o = array?.optJSONObject(i) ?: return@mapNotNull null
            val issue = o.optString("issue").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val severity = when (o.optString("severity").trim().lowercase()) {
                "critical", "high", "severe" -> "critical"
                "major", "moderate", "medium" -> "major"
                else -> "minor"
            }
            SafetyFlag(issue, severity, o.optString("quote").trim())
        }.sortedBy { listOf("critical", "major", "minor").indexOf(it.severity) }.take(5)

    private fun upgrades(array: JSONArray?): List<LineUpgrade> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val o = array?.optJSONObject(i) ?: return@mapNotNull null
            val better = o.optString("better").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            LineUpgrade(o.optString("original").trim(), better, o.optString("why").trim())
        }.take(3)

    // ── Serialisation (the EvaluationResult carries the card as JSON, like follow-up does) ──

    fun toJson(card: Scorecard): JSONObject = JSONObject().apply {
        put("framework", card.framework)
        put("task_family", card.taskFamily)
        put("counterpart", card.counterpart)
        put("learner_words", card.learnerWords)
        put("criteria", JSONArray().apply {
            card.criteria.forEach { c ->
                put(JSONObject().put("key", c.key).put("label", c.label).put("score", c.score ?: JSONObject.NULL)
                    .put("max", c.max).put("evidence", c.evidence).put("group", c.group))
            }
        })
        card.oet?.let { o ->
            put("oet", JSONObject().put("raw", o.raw).put("raw_max", o.rawMax).put("scaled", o.scaled)
                .put("grade", o.grade).put("meets_b", o.meetsGradeB).put("weakest", JSONArray(o.weakest)))
        }
        put("task_items", JSONArray().apply {
            card.taskItems.forEach { put(JSONObject().put("item", it.item).put("status", it.status).put("evidence", it.evidence)) }
        })
        put("task_completion", card.taskCompletion ?: JSONObject.NULL)
        put("safety_flags", JSONArray().apply {
            card.safetyFlags.forEach { put(JSONObject().put("issue", it.issue).put("severity", it.severity).put("quote", it.quote)) }
        })
        put("line_upgrades", JSONArray().apply {
            card.upgrades.forEach { put(JSONObject().put("original", it.original).put("better", it.better).put("why", it.why)) }
        })
        put("checks", JSONArray().apply {
            card.signals.checks.forEach {
                put(JSONObject().put("id", it.id).put("label", it.label).put("passed", it.passed)
                    .put("detail", it.detail).put("tip", it.tip))
            }
        })
        put("jargon", JSONArray().apply {
            card.signals.jargon.forEach { put(JSONObject().put("term", it.term).put("plain", it.plain).put("count", it.count)) }
        })
        card.signals.talkShare?.let { put("talk_share", it) }
        put("verdict", JSONObject().put("level", card.verdict.level).put("headline", card.verdict.headline))
    }

    /** Convenience for the two call sites (live finish and History): "{}" when not a nursing case. */
    fun buildJson(caseJson: String, rawAnalysis: String?, transcript: List<Pair<String, String>>): String = runCatching {
        val clean = rawAnalysis?.replace("```json", "")?.replace("```", "")?.trim().orEmpty()
        val analysis = runCatching { JSONObject(clean.ifBlank { "{}" }) }.getOrNull()
        build(caseJson, analysis, transcript)?.let { toJson(it).toString() } ?: "{}"
    }.getOrDefault("{}")

    // ── Progress across sessions (drives the picker's per-card and track summary lines) ──────

    /** One finished nursing session, reduced to what progress needs. */
    data class Attempt(val caseId: String, val createdAt: String, val scorecardJson: String)

    data class CaseProgress(
        val attempts: Int,
        val lastLevel: String,
        val lastOetGrade: String?,
        val lastOetScaled: Int?,
        val bestOetScaled: Int?,
    )

    /** Per-case progress, keyed by case id. Order of [attempts] does not matter; `createdAt` decides "last". */
    fun progressByCase(attempts: List<Attempt>): Map<String, CaseProgress> =
        attempts.filter { it.caseId.isNotBlank() }.groupBy { it.caseId }.mapValues { (_, list) ->
            val ordered = list.sortedBy { it.createdAt }
            val cards = ordered.map { runCatching { JSONObject(it.scorecardJson) }.getOrNull() ?: JSONObject() }
            val last = cards.last()
            val oetScores = cards.mapNotNull { it.optJSONObject("oet")?.optInt("scaled", -1)?.takeIf { v -> v >= 0 } }
            CaseProgress(
                attempts = list.size,
                lastLevel = last.optJSONObject("verdict")?.optString("level").orEmpty(),
                lastOetGrade = last.optJSONObject("oet")?.optString("grade")?.takeIf(String::isNotBlank),
                lastOetScaled = last.optJSONObject("oet")?.optInt("scaled", -1)?.takeIf { it >= 0 },
                bestOetScaled = oetScores.maxOrNull(),
            )
        }

    /**
     * Mean OET estimate over the learner's most recent OET role-plays (any case), or null with
     * fewer than [minimum] scored attempts — one role-play is too noisy to call a level.
     */
    fun recentOetEstimate(attempts: List<Attempt>, window: Int = 3, minimum: Int = 2): Int? {
        val recent = attempts.sortedByDescending { it.createdAt }.mapNotNull {
            runCatching { JSONObject(it.scorecardJson).optJSONObject("oet")?.optInt("scaled", -1) }
                .getOrNull()?.takeIf { v -> v >= 0 }
        }.take(window)
        if (recent.size < minimum) return null
        return ((recent.average() / 10.0).roundToInt() * 10).coerceIn(0, 500)
    }

    /** Short English line for a card, e.g. "Done ×2 · last: OET B ≈380". */
    fun progressLabel(progress: CaseProgress): String {
        val last = when {
            progress.lastOetGrade != null && progress.lastOetScaled != null ->
                "OET ${progress.lastOetGrade} ≈${progress.lastOetScaled}"
            else -> when (progress.lastLevel) {
                "ready" -> "ready"
                "close" -> "nearly there"
                "not_yet" -> "not yet"
                "too_short" -> "too short"
                else -> ""
            }
        }
        return "Done ×${progress.attempts}" + if (last.isNotEmpty()) " · last: $last" else ""
    }

    /** Transcript pairs from a stored session's raw transcript JSON (`[{role, text}]`). */
    fun transcriptPairs(rawTranscript: String?): List<Pair<String, String>> = runCatching {
        val arr = JSONArray(rawTranscript.orEmpty().ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val turn = arr.optJSONObject(i) ?: return@mapNotNull null
            turn.optString("role") to turn.optString("text")
        }
    }.getOrDefault(emptyList())

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun Any?.toBandOrNull(max: Int): Int? {
        val d = when (this) {
            is Number -> toDouble()
            is String -> trim().substringBefore('/').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!d.isFinite()) return null
        return d.roundToInt().coerceIn(0, max)
    }

    private fun Any?.toScoreOrNull(): Double? {
        val d = when (this) {
            is Number -> toDouble()
            is String -> trim().removeSuffix("%").substringBefore('/').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!d.isFinite()) return null
        // Same convention as the main score parser: a 0–10 value is a tenth-scale score.
        return (if (d <= 10.0) d * 10.0 else d).coerceIn(0.0, 100.0)
    }

    private fun humanize(key: String): String =
        key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private val TOKEN = Regex("[a-z0-9]+")
    private val STOP = setOf("the", "a", "an", "and", "or", "to", "of", "for", "you", "your", "that", "they", "what", "with", "is", "are", "in", "on", "it", "so", "do", "not", "be")

    private fun tokens(text: String): Set<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.filter { it.length > 2 && it !in STOP }.toSet()

    /** Share of the brief task's content words that also appear in the model's restatement. */
    private fun overlap(task: String, returned: String): Double {
        val a = tokens(task)
        if (a.isEmpty()) return 0.0
        return a.intersect(tokens(returned)).size.toDouble() / a.size
    }
}
