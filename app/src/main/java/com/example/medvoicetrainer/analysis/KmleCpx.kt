package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Korean CPX track (한국 의사국가시험 실기 대비): a Korean medical student interviews a
 * Korean-speaking standardized patient and is graded on a Korean CPX-style checklist — medical
 * content, not English.
 *
 * Deliberately free of Android and Compose so all of it is unit-testable. The content lives in
 * `data/kmle_cpx/`: `common.json` holds the items every station shares (opening, the generic
 * history/exam/education steps, PPI, the diagnosis-and-plan judgement) and one file per
 * presentation under `presentations/` holds the symptom-specific items plus the ids of the
 * existing English cases that present that way. The English case supplies the clinical facts;
 * this object turns it into a Korean session and a Korean scorecard.
 *
 * Sessions are stored under [SESSION_MODE] with [ANALYSIS_DOMAIN], so they never reach the English
 * dashboards, SRS, pronunciation, or correction pipelines.
 */
object KmleCpx {

    const val SESSION_MODE = "kmle_cpx"
    const val ANALYSIS_DOMAIN = "kmle"
    const val COMMON_ASSET = "kmle_cpx/common.json"
    const val PRESENTATION_DIR = "kmle_cpx/presentations"

    /**
     * `data/kmle_cpx/case_index.json`: every encounter case in the library, mapped to the station
     * whose checklist fits how that patient presents, with the Korean door-card complaint. This is
     * what makes the whole English case library usable in Korean, not just the cases a
     * presentation file lists itself.
     */
    const val CASE_INDEX_ASSET = "kmle_cpx/case_index.json"

    /** The catch-all station for complaints no symptom station fits (see `presentations/general.json`). */
    const val GENERAL_PRESENTATION = "general"

    /** Key of the block embedded in the composed case JSON (see [composeCaseJson]). */
    const val CASE_KEY = "kmle_cpx"

    const val STATUS_DONE = "done"
    const val STATUS_PARTIAL = "partial"
    const val STATUS_MISSED = "missed"
    const val STATUS_NA = "na"
    const val STATUS_UNSCORED = "unscored"

    const val SECTION_OPENING = "opening"
    const val SECTION_HISTORY = "history"
    const val SECTION_EXAM = "physical_exam"
    const val SECTION_EDUCATION = "education"
    const val SECTION_CONTENT = "clinical_content"
    const val SECTION_PPI = "ppi"

    private val DEFAULT_SECTION_ORDER = listOf(
        SECTION_OPENING, SECTION_HISTORY, SECTION_EXAM, SECTION_EDUCATION, SECTION_CONTENT, SECTION_PPI,
    )
    private val DEFAULT_SECTION_LABELS = mapOf(
        SECTION_OPENING to "면담 시작",
        SECTION_HISTORY to "병력청취",
        SECTION_EXAM to "신체진찰",
        SECTION_EDUCATION to "환자교육·마무리",
        SECTION_CONTENT to "진단·계획 (의학 내용)",
        SECTION_PPI to "환자-의사 관계 (PPI)",
    )

    /** Conditions an item may declare in `applies`; anything else is treated as "always". */
    val APPLIES_VALUES = setOf("female", "male", "female_reproductive_age", "female_over_45", "child", "adult")

    // ── Station tasks and practice scope ──────────────────────────────────────────────────
    //
    // The exam's problem sheet lists up to three tasks: take the history, examine, and discuss the
    // likely diagnosis and plan. A station declares which it has (`tasks`; a counselling station
    // may have no examination), and the learner picks how far to go this time (the scope): a
    // student early in training often practises the history alone, whatever the sheet says.

    const val TASK_HISTORY = "history"
    const val TASK_EXAM = "physical_exam"
    const val TASK_EDUCATION = "education"
    val ALL_TASKS = listOf(TASK_HISTORY, TASK_EXAM, TASK_EDUCATION)

    const val SCOPE_HISTORY = "history"
    const val SCOPE_HISTORY_EXAM = "history_exam"
    const val SCOPE_FULL = "full"
    val SCOPES = listOf(SCOPE_FULL, SCOPE_HISTORY_EXAM, SCOPE_HISTORY)

    fun scopeLabel(scope: String): String = when (scope) {
        SCOPE_HISTORY -> "병력청취만"
        SCOPE_HISTORY_EXAM -> "병력청취 + 신체진찰"
        else -> "전체 (병력·진찰·설명)"
    }

    fun normalizeScope(raw: String?): String = raw?.trim()?.takeIf { it in SCOPES } ?: SCOPE_FULL

    private fun scopeTasks(scope: String): Set<String> = when (normalizeScope(scope)) {
        SCOPE_HISTORY -> setOf(TASK_HISTORY)
        SCOPE_HISTORY_EXAM -> setOf(TASK_HISTORY, TASK_EXAM)
        else -> ALL_TASKS.toSet()
    }

    /** The tasks this session asks for, in sheet order: the station's own, narrowed by the scope. */
    fun effectiveTasks(presentation: Presentation, scope: String): List<String> {
        val allowed = scopeTasks(scope)
        return presentation.tasks.filter { it in allowed }.ifEmpty { listOf(TASK_HISTORY) }
    }

    data class Item(
        val id: String,
        val text: String,
        val say: String = "",
        val tags: List<String> = emptyList(),
        val key: Boolean = false,
        val applies: String = "",
        val conditional: Boolean = false,
        /** PPI and clinical-content items carry a longer description for the grader. */
        val description: String = "",
        /**
         * True for a line taken from the case's own English `teaching.must_ask`. The grader returns
         * a Korean label for it, which the card shows instead of the English objective.
         */
        val fromCase: Boolean = false,
        /** A task ([TASK_EXAM], [TASK_EDUCATION]) this item only makes sense with, e.g. PPI's exam manner. */
        val requires: String = "",
    )

    data class DifferentialGroup(val key: String, val label: String, val examples: List<String>)

    data class Presentation(
        val id: String,
        val title: String,
        val englishTitle: String,
        val category: String,
        val doorComplaint: String,
        val groups: List<DifferentialGroup>,
        val history: List<Item>,
        val physicalExam: List<Item>,
        val education: List<Item>,
        val graderNotes: List<String>,
        val caseIds: List<String>,
        /**
         * Score the case's own `teaching.must_ask` lines as history items. Set by the catch-all
         * station, whose generic checklist has nothing symptom-specific; symptom stations already
         * cover those questions and only pass them to the grader as reference.
         */
        val scoreCaseMustAsk: Boolean = false,
        /** The sheet's tasks for this station ([ALL_TASKS] unless the file narrows them). */
        val tasks: List<String> = ALL_TASKS,
        /** Per-task wording that replaces the common sheet stems (a counselling station's own verbs). */
        val taskStems: Map<String, String> = emptyMap(),
        /** The official 48-item list entry this station trains (`kmle_item`), blank for a supplementary station. */
        val kmleItem: String = "",
    )

    /** The problem sheet's fixed wording (`common.json` → `door_card`). */
    data class DoorCardConfig(
        val title: String = "문제 (제한시간 {minutes}분)",
        val places: Map<String, String> = mapOf("outpatient" to "외래", "emergency" to "응급실", "ward" to "병동"),
        val taskIntro: String = "응시자는 이 환자에게",
        val taskStems: Map<String, String> = mapOf(
            TASK_HISTORY to "증상과 관련한 병력을 청취하",
            TASK_EXAM to "증상과 관련한 신체진찰을 시행하",
            TASK_EDUCATION to "추정진단과 향후 계획을 환자와 논의하",
        ),
        val notes: List<String> = emptyList(),
    )

    /** Korean name pools by sex and age band (`common.json` → `patient_names`). */
    data class NamePools(val surnames: List<String>, val given: Map<String, Map<String, List<String>>>) {
        val isEmpty: Boolean get() = surnames.isEmpty() || given.isEmpty()
    }

    data class Common(
        val disclaimer: String,
        val stationMinutes: Int,
        val sectionOrder: List<String>,
        val sectionLabels: Map<String, String>,
        val weights: Map<String, Double>,
        val opening: List<Item>,
        val historyBefore: List<Item>,
        val historyAfter: List<Item>,
        val examBefore: List<Item>,
        val examAfter: List<Item>,
        val education: List<Item>,
        val ppi: List<Item>,
        val clinicalContent: List<Item>,
        /** Top of the PPI scale: 4 (미흡 … 아주 우수, the exam's own scale) or 5 for sessions stored before it. */
        val ppiScale: Int = 5,
        val ppiLevels: List<String> = emptyList(),
        val doorCard: DoorCardConfig = DoorCardConfig(),
        val names: NamePools = NamePools(emptyList(), emptyMap()),
    ) {
        fun label(section: String): String = sectionLabels[section] ?: DEFAULT_SECTION_LABELS[section] ?: section

        /** "아주 우수" for the top of a 4-level scale; a plain "n점" when the snapshot carries no labels. */
        fun ppiLevelLabel(score: Int?): String = when {
            score == null -> "채점 안 됨"
            ppiLevels.size == ppiScale && score in 1..ppiScale -> ppiLevels[score - 1]
            else -> "${score}점"
        }
    }

    /** One graded line of the session's checklist. [key] is unique across the whole checklist. */
    data class ChecklistItem(val key: String, val section: String, val item: Item)

    data class ChecklistSection(val key: String, val label: String, val items: List<ChecklistItem>)

    // ── Parsing ────────────────────────────────────────────────────────────────────────────

    fun parseItems(raw: JSONArray?): List<Item> {
        if (raw == null) return emptyList()
        return (0 until raw.length()).mapNotNull { i ->
            val o = raw.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val text = o.optString("text").trim()
            if (id.isEmpty() || text.isEmpty()) return@mapNotNull null
            Item(
                id = id,
                text = text,
                say = o.optString("say").trim(),
                tags = strings(o.optJSONArray("tags")),
                key = o.optBoolean("key", false),
                applies = o.optString("applies").trim().lowercase(),
                conditional = o.optBoolean("conditional", false),
                description = o.optString("description").trim(),
                requires = o.optString("requires").trim(),
            )
        }
    }

    fun parsePresentation(json: JSONObject): Presentation? {
        val id = json.optString("id").trim()
        val title = json.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val groups = json.optJSONArray("differential_groups")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val g = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = g.optString("key").trim()
                if (key.isEmpty()) null else DifferentialGroup(key, g.optString("label").trim(), strings(g.optJSONArray("examples")))
            }
        }.orEmpty()
        return Presentation(
            id = id,
            title = title,
            englishTitle = json.optString("english_title").trim(),
            category = json.optString("category").trim().ifEmpty { "기타" },
            doorComplaint = json.optString("door_complaint").trim(),
            groups = groups,
            history = parseItems(json.optJSONArray("history")),
            physicalExam = parseItems(json.optJSONArray("physical_exam")),
            education = parseItems(json.optJSONArray("education")),
            graderNotes = strings(json.optJSONArray("grader_notes")),
            caseIds = strings(json.optJSONArray("case_ids")),
            scoreCaseMustAsk = json.optBoolean("score_case_must_ask", false),
            tasks = strings(json.optJSONArray("tasks")).filter { it in ALL_TASKS }.ifEmpty { ALL_TASKS }
                .let { declared -> ALL_TASKS.filter { it in declared } },
            taskStems = stringMap(json.optJSONObject("task_stems")),
            kmleItem = json.optString("kmle_item").trim(),
        )
    }

    fun parsePresentation(text: String): Presentation? =
        runCatching { parsePresentation(JSONObject(text)) }.getOrNull()

    fun parseCommon(json: JSONObject): Common {
        val labels = json.optJSONObject("section_labels")?.let { o ->
            o.keys().asSequence().associateWith { o.optString(it) }
        }.orEmpty()
        val weights = json.optJSONObject("weights")?.let { o ->
            o.keys().asSequence().associateWith { o.optDouble(it, 0.0) }
        }.orEmpty()
        return Common(
            disclaimer = json.optString("disclaimer").trim(),
            stationMinutes = json.optInt("station_minutes", 0).coerceAtLeast(0),
            sectionOrder = strings(json.optJSONArray("section_order")).ifEmpty { DEFAULT_SECTION_ORDER },
            sectionLabels = labels,
            weights = weights,
            opening = parseItems(json.optJSONArray("opening")),
            historyBefore = parseItems(json.optJSONArray("history_before")),
            historyAfter = parseItems(json.optJSONArray("history_after")),
            examBefore = parseItems(json.optJSONArray("physical_exam_before")),
            examAfter = parseItems(json.optJSONArray("physical_exam_after")),
            education = parseItems(json.optJSONArray("education")),
            ppi = parseItems(json.optJSONArray("ppi")),
            clinicalContent = parseItems(json.optJSONArray("clinical_content")),
            ppiScale = json.optInt("ppi_scale", 5).takeIf { it in 2..10 } ?: 5,
            ppiLevels = strings(json.optJSONArray("ppi_levels")),
            doorCard = json.optJSONObject("door_card")?.let { o ->
                val defaults = DoorCardConfig()
                DoorCardConfig(
                    title = o.optString("title").trim().ifEmpty { defaults.title },
                    places = defaults.places + stringMap(o.optJSONObject("places")),
                    taskIntro = o.optString("task_intro").trim().ifEmpty { defaults.taskIntro },
                    taskStems = defaults.taskStems + stringMap(o.optJSONObject("task_stems")),
                    notes = strings(o.optJSONArray("notes")),
                )
            } ?: DoorCardConfig(),
            names = json.optJSONObject("patient_names")?.let { o ->
                val given = listOf("female", "male").associateWith { sex ->
                    val bands = o.optJSONObject(sex)
                    bands?.keys()?.asSequence()?.associateWith { band -> strings(bands.optJSONArray(band)) }.orEmpty()
                }
                NamePools(strings(o.optJSONArray("surnames")), given)
            } ?: NamePools(emptyList(), emptyMap()),
        )
    }

    fun parseCommon(text: String): Common = parseCommon(runCatching { JSONObject(text) }.getOrDefault(JSONObject()))

    /** `speaker` value for a case whose history comes from a family member (see [IndexEntry]). */
    const val SPEAKER_GUARDIAN = "guardian"

    /**
     * One case's row in `case_index.json`. [speaker] is "guardian" when the patient cannot give
     * their own history (unconscious, acutely confused, in active labour): a family member answers,
     * as in a real CPX station of that kind. Children always get a guardian, marked or not.
     */
    data class IndexEntry(
        val caseId: String,
        val presentationId: String,
        val doorComplaint: String,
        val speaker: String = "",
    )

    fun parseCaseIndex(text: String): Map<String, IndexEntry> {
        val cases = runCatching { JSONObject(text).optJSONObject("cases") }.getOrNull() ?: return emptyMap()
        return cases.keys().asSequence().mapNotNull { id ->
            val row = cases.optJSONObject(id) ?: return@mapNotNull null
            val presentation = row.optString("presentation").trim().ifEmpty { GENERAL_PRESENTATION }
            id to IndexEntry(id, presentation, row.optString("door_complaint").trim(), row.optString("speaker").trim())
        }.toMap()
    }

    /**
     * Every case that can be played at [presentation]: the ones its own file lists, then every case
     * the index files under it. Order is stable so tests and the rotation are deterministic.
     */
    fun casePool(presentation: Presentation, index: Map<String, IndexEntry>): List<String> =
        (presentation.caseIds + index.values.filter { it.presentationId == presentation.id }.map { it.caseId }.sorted())
            .distinct()

    /** The case's own `teaching.must_ask` objectives, as checklist items (English text). */
    fun caseMustAsk(case: JSONObject): List<Item> {
        val rows = case.optJSONObject("teaching")?.optJSONArray("must_ask") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val objective = rows.optJSONObject(i)?.optString("objective")?.trim().orEmpty()
            if (objective.isEmpty()) null else Item(id = "case_${i + 1}", text = objective, fromCase = true)
        }
    }

    // ── Patient facts ──────────────────────────────────────────────────────────────────────

    /** Case `gender` normalised to "female" / "male" / "". */
    fun genderOf(case: JSONObject): String {
        val g = case.optString("gender").trim().lowercase()
        return when {
            g.startsWith("f") || g == "woman" || g == "girl" -> "female"
            g.startsWith("m") || g == "man" || g == "boy" -> "male"
            else -> ""
        }
    }

    fun ageOf(case: JSONObject): Int? {
        val raw = case.opt("age") ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            else -> Regex("\\d+").find(raw.toString())?.value?.toIntOrNull()
        }?.takeIf { it in 0..120 }
    }

    /** Whether an item's `applies` condition holds for this patient. Unknown facts never exclude. */
    fun applies(item: Item, age: Int?, gender: String): Boolean = when (item.applies) {
        "female" -> gender != "male"
        "male" -> gender != "female"
        "female_reproductive_age" -> gender != "male" && (age == null || age in 12..55)
        "female_over_45" -> gender != "male" && (age == null || age >= 45)
        "child" -> age == null || age < 13
        "adult" -> age == null || age >= 13
        else -> true
    }

    fun isChild(age: Int?): Boolean = age != null && age < 13

    /** "45세 여자" / "7세 남자아이" / "환자" when the case gives neither. */
    fun patientLabel(age: Int?, gender: String): String {
        val child = isChild(age)
        val sex = when (gender) {
            "female" -> if (child) "여자아이" else "여자"
            "male" -> if (child) "남자아이" else "남자"
            else -> if (child) "아이" else "환자"
        }
        return if (age != null) "${age}세 $sex" else sex
    }

    /** The door note the learner reads before knocking, in the Korean CPX style. */
    fun doorNote(
        presentation: Presentation,
        age: Int?,
        gender: String,
        doorComplaint: String = "",
        guardian: Boolean = isChild(age),
    ): String {
        val who = patientLabel(age, gender)
        val complaint = doorComplaint.trim()
            .ifEmpty { presentation.doorComplaint }
            .ifEmpty { "'${presentation.title}'라고" }
        // Every label patientLabel can produce ends in a vowel, so the subject particle is always 가.
        val withGuardian = if (guardian || isChild(age)) " 보호자와 함께" else ""
        return "${who}가$withGuardian $complaint 왔다."
    }

    // ── Name and problem sheet ─────────────────────────────────────────────────────────────

    /** True when the last syllable of [word] has a final consonant (받침), which picks 은/이 over 는/가. */
    fun hasBatchim(word: String): Boolean {
        val last = word.trim().lastOrNull() ?: return false
        if (last !in '가'..'힣') return false
        return (last - '가') % 28 != 0
    }

    private fun ageBand(age: Int?): String = when {
        age == null -> "adult"
        age < 13 -> "child"
        age < 25 -> "teen"
        age < 45 -> "adult"
        age < 65 -> "middle"
        else -> "elder"
    }

    /**
     * A Korean name for this case's patient, fixed by the case id so the sheet, the patient and
     * History always agree. The given name fits the patient's generation (순자 at 78, 서윤 at 4).
     */
    fun koreanName(caseId: String, age: Int?, gender: String, pools: NamePools): String {
        if (pools.isEmpty) return ""
        val sex = if (gender == "male") "male" else if (gender == "female") "female" else {
            if ((caseId.hashCode() and 1) == 0) "female" else "male"
        }
        val bands = pools.given[sex].orEmpty()
        val given = bands[ageBand(age)].orEmpty().ifEmpty { bands.values.flatten() }
        if (given.isEmpty()) return ""
        val surname = pools.surnames[Math.floorMod("s:$caseId".hashCode(), pools.surnames.size)]
        return surname + given[Math.floorMod("g:$caseId".hashCode(), given.size)]
    }

    /** Where the sheet says the patient came: 외래 / 응급실 / 병동. */
    private fun placeKey(case: JSONObject): String {
        val authored = case.optString("care_setting").trim().lowercase()
        val system = case.optString("system").trim().lowercase()
        return when {
            authored in setOf("emergency_department", "emergency room", "ed") || system == "em" -> "emergency"
            authored in setOf("inpatient", "ward", "hospital_ward") -> "ward"
            else -> "outpatient"
        }
    }

    /**
     * The problem sheet the learner reads on entering, in the exam's own layout: age, sex and name,
     * where they came, the four vital signs, and the tasks. The chief complaint is not on the sheet
     * (the exam dropped it in July 2026) — the patient says it — unless [complaintHint] is set for
     * practice.
     */
    data class SituationCard(
        val title: String,
        val intro: String,
        /** "혈압: 118/76 mmHg" … in the sheet's order; empty when the case has no authored vitals. */
        val vitals: List<String>,
        val taskIntro: String,
        val tasks: List<String>,
        val complaintHint: String,
        val notes: List<String>,
        /** Results already in hand before the encounter ("LDL cholesterol: 168 mg/dL (<130)"), chart-style English. */
        val results: List<String> = emptyList(),
    )

    /** The composed case's pre-encounter results as sheet lines. */
    fun sheetResults(case: JSONObject): List<String> {
        val rows = case.optJSONObject(CASE_KEY)?.optJSONArray("sheet_results") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            val test = r.optString("test").trim()
            val value = r.optString("value").trim()
            if (test.isEmpty() || value.isEmpty()) return@mapNotNull null
            val unit = r.optString("unit").trim()
            val range = r.optString("reference_range").trim()
            buildString {
                append(test).append(": ").append(value)
                if (unit.isNotEmpty()) append(' ').append(unit)
                if (range.isNotEmpty()) append(" (참고치 ").append(range).append(')')
            }
        }
    }

    fun situationCard(session: SessionCase, stationMinutes: Int = session.common.stationMinutes): SituationCard {
        val cfg = session.common.doorCard
        val name = session.patientName
        val label = patientLabel(session.age, session.gender)
        val place = cfg.places[placeKey(session.case)] ?: "외래"
        val child = isChild(session.age)
        val who = when {
            name.isEmpty() -> label
            child -> "$label $name"
            else -> "$label $name 씨"
        }
        val subjectParticle = if (hasBatchim(who)) "이" else "가"
        val companion = if (session.usesGuardian) " 보호자와 함께" else ""
        val intro = "$who$subjectParticle$companion ${place}에 왔다."

        val v = session.script?.vitals
        val vitals = if (v == null || v.isEmpty) emptyList() else buildList {
            if (v.bloodPressure.isNotBlank()) add("혈압: ${v.bloodPressure} mmHg")
            v.heartRate?.let { add("맥박수: ${it}회/분") }
            v.respRate?.let { add("호흡수: ${it}회/분") }
            v.tempC?.let { add("체온: ${SpScript.formatTemp(it)}") }
            // The exam sheet shows four signs; a low saturation is worth the fifth line.
            v.spo2?.takeIf { it < 95 }?.let { add("산소포화도: ${it}%") }
        }

        val tasks = effectiveTasks(session.presentation, session.scope)
        val stems = tasks.map { session.presentation.taskStems[it] ?: cfg.taskStems[it].orEmpty() }.filter { it.isNotBlank() }
        val lines = stems.mapIndexed { i, stem -> if (i == stems.lastIndex) "${stem}시오." else "${stem}고," }

        val complaint = session.doorComplaint.ifBlank { session.presentation.doorComplaint }
        return SituationCard(
            title = cfg.title.replace("{minutes}", stationMinutes.toString()),
            intro = intro,
            vitals = vitals,
            taskIntro = cfg.taskIntro,
            tasks = lines,
            complaintHint = complaint.removeSuffix("고").trim(),
            notes = cfg.notes,
            results = sheetResults(session.case),
        )
    }

    // ── Mock exam ──────────────────────────────────────────────────────────────────────────

    /**
     * Estimated pass rule for a practice circuit, modelled on the published rule (total at or above
     * the sum of station cut scores — 718/1000 at the 89th exam — and enough stations passed, 6 of
     * 10). The real cut scores are set per station by a standard-setting panel and are not public,
     * so the app uses one flat line per station. Always shown as an estimate.
     */
    const val MOCK_STATION_CUT = 72
    const val MOCK_TOTAL_CUT_PERCENT = 72

    data class MockVerdict(val total: Int, val max: Int, val stationsPassed: Int, val stationsNeeded: Int, val passed: Boolean)

    fun mockVerdict(scores: List<Int?>): MockVerdict {
        val max = scores.size * 100
        val total = scores.sumOf { it ?: 0 }
        val passedStations = scores.count { (it ?: 0) >= MOCK_STATION_CUT }
        // 6 of 10 in the exam; scaled to the circuit length, rounded up.
        val needed = kotlin.math.ceil(scores.size * 0.6).toInt()
        val passed = scores.isNotEmpty() && total * 100 >= max * MOCK_TOTAL_CUT_PERCENT && passedStations >= needed
        return MockVerdict(total, max, passedStations, needed, passed)
    }

    /** Tag a composed session case as station [index] of mock exam [examId]. */
    fun withMockExam(caseJson: String, examId: String, index: Int): String {
        val case = runCatching { JSONObject(caseJson) }.getOrNull() ?: return caseJson
        val block = case.optJSONObject(CASE_KEY) ?: return caseJson
        block.put("mock_exam", examId).put("mock_index", index)
        return case.toString()
    }

    fun mockExamId(caseJson: String): String =
        runCatching { JSONObject(caseJson).optJSONObject(CASE_KEY)?.optString("mock_exam").orEmpty() }.getOrDefault("")

    /** Rewrite the stored scope of a composed session case (the learner changed it on the sheet). */
    fun withScope(caseJson: String, scope: String): String {
        val case = runCatching { JSONObject(caseJson) }.getOrNull() ?: return caseJson
        val block = case.optJSONObject(CASE_KEY) ?: return caseJson
        block.put("scope", normalizeScope(scope))
        return case.toString()
    }

    /** One scripted finding, and whether the learner performed the examination that reveals it. */
    data class ExamReviewRow(
        val maneuver: String,
        val name: String,
        val finding: String,
        val painful: Boolean,
        val performed: Boolean,
    )

    /** Maneuvers the learner's turns performed, in the order first performed. */
    fun performedManeuvers(session: SessionCase, turns: List<Pair<String, String>>): List<String> {
        val vocabulary = session.maneuvers.values
        if (vocabulary.isEmpty()) return emptyList()
        return turns.filter { (role, text) -> (role == "doctor" || role == "user") && text.isNotBlank() }
            .flatMap { SpScript.detect(it.second, vocabulary, korean = true) }
            .distinct()
    }

    fun examReview(session: SessionCase, turns: List<Pair<String, String>>): List<ExamReviewRow> {
        val exam = session.script?.exam.orEmpty()
        if (exam.isEmpty()) return emptyList()
        val performed = performedManeuvers(session, turns).toSet()
        return exam.map { f ->
            ExamReviewRow(f.maneuver, session.maneuverKo(f.maneuver), f.finding, f.painful, f.maneuver in performed)
        }
    }

    fun sessionTitle(presentation: Presentation, age: Int?, gender: String, doorComplaint: String = ""): String {
        // The catch-all station's title says nothing about the patient, so its sessions are named
        // by their own complaint instead ("눈이 빨갛고 아프다고" → "눈이 빨갛고 아프다").
        val name = if (presentation.id == GENERAL_PRESENTATION && doorComplaint.isNotBlank()) {
            doorComplaint.trim().removeSuffix("고")
        } else presentation.title
        return "$name · ${patientLabel(age, gender)}"
    }

    // ── Checklist assembly ─────────────────────────────────────────────────────────────────

    /**
     * The graded checklist for one patient, in section order. Items whose `applies` condition
     * excludes this patient are dropped up front, so the grader is never asked about them.
     */
    fun checklist(
        common: Common,
        presentation: Presentation,
        age: Int?,
        gender: String,
        caseItems: List<Item> = emptyList(),
        tasks: List<String> = presentation.tasks,
    ): List<ChecklistSection> {
        val usedKeys = mutableSetOf<String>()
        fun keyed(section: String, items: List<Item>): List<ChecklistItem> =
            items.filter { applies(it, age, gender) && (it.requires.isEmpty() || it.requires in tasks) }.map { item ->
                var key = "$section.${item.id}"
                var n = 2
                while (!usedKeys.add(key)) key = "$section.${item.id}_${n++}"
                ChecklistItem(key, section, item)
            }

        val education = run {
            val selfCare = common.education.indexOfFirst { it.id == "self_care" }
            if (selfCare < 0) common.education + presentation.education
            else common.education.take(selfCare + 1) + presentation.education + common.education.drop(selfCare + 1)
        }
        val examined = TASK_EXAM in tasks
        val educated = TASK_EDUCATION in tasks
        val bySection = mapOf(
            SECTION_OPENING to keyed(SECTION_OPENING, common.opening),
            SECTION_HISTORY to keyed(
                SECTION_HISTORY,
                common.historyBefore + presentation.history +
                    (if (presentation.scoreCaseMustAsk) caseItems else emptyList()) +
                    common.historyAfter,
            ),
            SECTION_EXAM to if (examined) keyed(SECTION_EXAM, common.examBefore + presentation.physicalExam + common.examAfter) else emptyList(),
            SECTION_EDUCATION to if (educated) keyed(SECTION_EDUCATION, education) else emptyList(),
            SECTION_CONTENT to if (educated) keyed(SECTION_CONTENT, common.clinicalContent) else emptyList(),
            SECTION_PPI to keyed(SECTION_PPI, common.ppi),
        )
        val order = (common.sectionOrder + DEFAULT_SECTION_ORDER).distinct()
        return order.mapNotNull { key ->
            val items = bySection[key].orEmpty()
            if (items.isEmpty()) null else ChecklistSection(key, common.label(key), items)
        }
    }

    // ── Session case ───────────────────────────────────────────────────────────────────────

    /**
     * Turn an English case into a Korean CPX session case.
     *
     * The presentation and common rubric are embedded verbatim so the session is graded against
     * exactly the checklist it started with, even if the asset changes in a later release — the
     * same snapshot rule every other mode follows. Investigation results and doorknob scripts are
     * removed: a CPX station ends with the plan, and the English result-release matching could
     * never fire on Korean speech anyway.
     */
    fun composeCaseJson(
        baseCaseJson: String,
        presentationJson: String,
        commonJson: String,
        doorComplaint: String = "",
        speaker: String = "",
        scope: String = SCOPE_FULL,
        patientName: String? = null,
        maneuversJson: String = "",
    ): String {
        val case = runCatching { JSONObject(baseCaseJson) }.getOrDefault(JSONObject())
        val presentation = JSONObject(presentationJson)
        val common = runCatching { JSONObject(commonJson) }.getOrDefault(JSONObject())
        // The name is fixed here, once, so a later change to the name pools can never rename the
        // patient of a stored session.
        // Results the clinician already has (a biopsy report, the screening labs that brought the
        // patient in) belong on the problem sheet, as the exam prints them there. They leave the
        // top level so the English pre-encounter results table never opens for a CPX session.
        val sheetResults = case.optJSONArray("available_results") ?: JSONArray()
        val name = patientName ?: koreanName(
            case.optString("id"), ageOf(case), genderOf(case), parseCommon(common).names,
        )
        listOf(
            "investigation_events", "available_results", "doorknob_disclosure", "doorknob_probability",
            "kickoff_text", "persona_override", "coaching_mode",
            // English objectives drive the live English coverage checklist, which Korean speech
            // can never tick; the CPX checklist is graded after the session instead.
            "learning_objectives",
        ).forEach(case::remove)
        case.put("session_mode", SESSION_MODE)
        common.optInt("station_minutes", 0).takeIf { it > 0 }?.let { case.put("station_minutes", it) }
        case.put(
            CASE_KEY,
            JSONObject()
                .put("presentation", presentation)
                .put("common", common)
                .put("door_complaint", doorComplaint.trim())
                .put("speaker", speaker.trim())
                .put("scope", normalizeScope(scope))
                .put("patient_name", name)
                .put("maneuvers", usedManeuvers(case, maneuversJson))
                .put("sheet_results", sheetResults),
        )
        return case.toString()
    }

    /** The vocabulary rows this case's script refers to, embedded so the snapshot is self-contained. */
    private fun usedManeuvers(case: JSONObject, maneuversJson: String): JSONArray {
        val used = SpScript.parse(case)?.exam?.map { it.maneuver }?.toSet().orEmpty()
        val out = JSONArray()
        if (used.isEmpty() || maneuversJson.isBlank()) return out
        val rows = runCatching { JSONObject(maneuversJson).optJSONArray("maneuvers") }.getOrNull() ?: return out
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("id") in used) out.put(row)
        }
        return out
    }

    fun isKmleCase(caseJson: String): Boolean =
        runCatching { JSONObject(caseJson).optString("session_mode") == SESSION_MODE }.getOrDefault(false)

    fun isKmleCase(caseData: Map<String, Any?>): Boolean =
        caseData["session_mode"]?.toString()?.trim() == SESSION_MODE

    fun isKmleSession(mode: String?, analysisDomain: String?): Boolean =
        mode == SESSION_MODE || analysisDomain == ANALYSIS_DOMAIN

    data class SessionCase(
        val case: JSONObject,
        val presentation: Presentation,
        val common: Common,
        val age: Int?,
        val gender: String,
        /** This case's own door-card complaint from the index; blank means the station's. */
        val doorComplaint: String = "",
        val speaker: String = "",
        /** How far this session goes ([SCOPES]); sessions stored before scopes existed are [SCOPE_FULL]. */
        val scope: String = SCOPE_FULL,
        /** The Korean name on the sheet; blank for sessions stored before names existed. */
        val patientName: String = "",
    ) {
        /** A family member answers: every child, and any adult the index marks as unable to. */
        val usesGuardian: Boolean get() = isChild(age) || speaker == SPEAKER_GUARDIAN
        val caseItems: List<Item> by lazy { caseMustAsk(case) }
        val tasks: List<String> by lazy { effectiveTasks(presentation, scope) }
        val checklist: List<ChecklistSection> by lazy { checklist(common, presentation, age, gender, caseItems, tasks) }
        /** The one-line complaint summary ("45세 여자가 소변볼 때 아프다고 왔다.") used in titles and the grader. */
        val doorNote: String get() = doorNote(presentation, age, gender, doorComplaint, usesGuardian)
        /** The case's authored standardized-patient script, when it has one. */
        val script: SpScript.Script? by lazy { SpScript.parse(case) }
        val situationCard: SituationCard by lazy { situationCard(this) }
        /** The maneuver vocabulary rows embedded at compose time (only those the script uses). */
        val maneuvers: Map<String, SpScript.Maneuver> by lazy {
            SpScript.parseManeuvers(JSONObject().put("maneuvers", case.optJSONObject(CASE_KEY)?.optJSONArray("maneuvers") ?: JSONArray()).toString())
        }

        /** Korean name of a maneuver, falling back to its English name or id. */
        fun maneuverKo(id: String): String = maneuvers[id]?.ko ?: id.replace('_', ' ')
    }

    /** Read back what [composeCaseJson] embedded, or null for anything that is not a CPX case. */
    fun sessionCase(caseJson: String): SessionCase? {
        val case = runCatching { JSONObject(caseJson) }.getOrNull() ?: return null
        val block = case.optJSONObject(CASE_KEY) ?: return null
        val presentation = block.optJSONObject("presentation")?.let(::parsePresentation) ?: return null
        val common = parseCommon(block.optJSONObject("common") ?: JSONObject())
        return SessionCase(
            case, presentation, common, ageOf(case), genderOf(case),
            block.optString("door_complaint"), block.optString("speaker"),
            normalizeScope(block.optString("scope")), block.optString("patient_name").trim(),
        )
    }

    // ── Live patient ───────────────────────────────────────────────────────────────────────

    private fun careSettingKo(case: JSONObject): String {
        val authored = case.optString("care_setting").trim().lowercase()
        val system = case.optString("system").trim().lowercase()
        return when {
            authored in setOf("emergency_department", "emergency room", "ed") || system == "em" -> "응급실"
            authored in setOf("inpatient", "ward", "hospital_ward") -> "병동"
            else -> "외래 진료실"
        }
    }

    private fun factOr(case: JSONObject, key: String, fallback: String): String =
        case.optString(key).trim().ifEmpty { fallback }

    /**
     * System prompt for the Korean standardized patient. The case facts stay in English (that is
     * how the library is authored) and the persona is told to live them as a Korean patient,
     * speaking only natural spoken Korean and only as much as each question asks for.
     */
    fun buildPatientPrompt(caseJson: String): String {
        val session = sessionCase(caseJson)
        val case = session?.case ?: runCatching { JSONObject(caseJson) }.getOrDefault(JSONObject())
        val age = ageOf(case)
        val gender = genderOf(case)
        // The door card is the most specific Korean statement of why this patient came; the
        // catch-all station's title ("기타 증상") would tell the patient nothing.
        val title = session?.doorNote
            ?: session?.presentation?.title.orEmpty()
        val sexKo = when (gender) { "female" -> "여자"; "male" -> "남자"; else -> "알 수 없음" }
        val objective = case.optJSONObject("reference_soap")?.optString("objective")?.trim().orEmpty()
        val guardian = when {
            isChild(age) -> GUARDIAN_RULES
            session?.usesGuardian == true -> FAMILY_RULES
            else -> ""
        }
        val name = session?.patientName.orEmpty()
        val nameLine = when {
            name.isEmpty() -> "- 이름: 사례의 이름이 한국 이름이 아니면 같은 성별의 흔한 한국 이름을 하나 정해 끝까지 씁니다."
            isChild(age) -> "- 아이 이름: $name (학생이 확인하면 이 이름으로 답합니다)"
            session?.usesGuardian == true -> "- 환자 이름: $name (학생이 확인하면 이 이름으로 답합니다)"
            else -> "- 이름: $name (학생이 확인하면 이 이름으로 답합니다)"
        }
        val complaint = session?.doorComplaint?.ifBlank { session.presentation.doorComplaint }.orEmpty().removeSuffix("고").trim()
        val script = session?.script
        val facts = script?.history.orEmpty()
        val exam = script?.exam.orEmpty()
        val factBlock = if (facts.isEmpty()) "" else "\n\n[대본 — 이 증례의 사실. 학생이 그 주제를 물을 때만, 물은 만큼만 자연스러운 한국어로 바꿔 말합니다. 대본과 다르게 말하지 않습니다.]\n" +
            facts.joinToString("\n") { "- ${it.topic}: ${it.answer}" }
        val examBlock = if (exam.isEmpty()) {
            "- 진찰 참고(학생이 그 부위를 진찰할 때만 반영, 먼저 말하지 않음): ${objective.ifEmpty { "not provided" }}"
        } else {
            "- 진찰할 때의 반응(여기 적힌 대로만 반응합니다):\n" + exam.joinToString("\n") { f ->
                "  · ${session?.maneuverKo(f.maneuver) ?: f.maneuver}: " +
                    if (f.painful) "아파합니다. \"아, 거기 아파요\"처럼 짧게 반응하고 어디가 아픈지 말합니다." else "아프거나 불편하지 않습니다."
            }
        }

        return """
당신은 한국 의사국가시험 실기(CPX)의 표준화 환자입니다. 진료실에 들어온 학생의사와 한국어로만 대화합니다.

[환자 정보] 아래 사례는 영어로 적혀 있지만, 당신은 한국에 사는 한국인 환자입니다.
- 나이: ${age?.let { "${it}세" } ?: "알 수 없음"}, 성별: $sexKo
$nameLine
- 진료 장소: ${careSettingKo(case)}
- 학생이 받은 문제지에는 나이, 성별, 이름, 활력징후만 있고 주호소는 없습니다. 왜 왔는지는 당신이 말해야 학생이 압니다.
- 오늘 온 이유(당신이 말할 주호소): ${if (complaint.isNotEmpty()) "\"$complaint\"" else if (title.isNotEmpty()) "\"$title\"" else "사례의 주호소"}
- 사례의 주호소(영어 원문): ${factOr(case, "chief_complaint", "not provided")}

[숨겨진 정보] 학생이 적절히 물어볼 때만, 물어본 만큼만 말합니다.
- 현병력: ${factOr(case, "hpi_details", "not provided")}
- 환자의 생각(무엇 때문이라고 생각하는지): ${factOr(case, "ideas", "not provided")}
- 걱정: ${factOr(case, "concerns", "not provided")}
- 기대(원하는 것): ${factOr(case, "expectations", "not provided")}
- 과거력: ${factOr(case, "pmh", "none")}
- 복용 약: ${factOr(case, "medications", "none")}
- 사회력: ${factOr(case, "social_hx", "not provided")}
$examBlock$factBlock

[말투]
- 목소리는 차분하고 담담한 진료실 환자의 말투로 합니다. 밝고 들뜬 상담원·진행자처럼 웃으며 말하거나 과장된 억양을 쓰지 않습니다. 환자의 나이와 사례에 적힌 상태에 맞춰 말의 속도와 호흡을 자연스럽게 조절하되, 대본에 없는 통증·피로·혼란이나 노인 말투를 지어내지 않습니다. 보호자 역할이면 환자가 아니라 성인 보호자의 목소리로 말합니다.
- 모든 대답은 자연스러운 한국어 구어체(해요체)로 합니다. 영어 단어나 의학용어를 쓰지 않습니다. 약은 "혈압약", "당뇨약"처럼 환자가 실제로 부르는 대로 말합니다.
- 사례에 외국의 제도, 상표명, 지명이 나오면 한국 상황에 맞게 자연스럽게 바꿔 말하되, 증상·기간·횟수·수치 같은 임상 사실은 절대 바꾸지 않습니다. 화씨는 섭씨로, 파운드는 킬로그램으로 바꿔 말합니다.
- 사례와 대본에 없는 증상이나 병력은 지어내지 않습니다. 없는 것을 물으면 "아니요, 그런 건 없어요"나 "잘 모르겠어요"처럼 짧게 답합니다.
- '환자의 생각'에 진단명이 적혀 있어도 환자는 그 병명을 모릅니다(이전에 진단받은 병은 예외). "혹시 방광에 염증이 생긴 건 아닌가 싶어요"처럼 일상적인 말로 막연하게 짐작만 합니다.

[얼마나 말할지] 실제 진료실의 환자처럼, 사례 요약을 읽듯 말하지 않습니다.
- 방금 받은 질문에만 대답하고 멈춥니다. 대부분 한 문장이고, 몇 마디로 끝나는 대답도 많습니다("사흘 됐어요." "아니요." "여기 오른쪽이요.").
- 예/아니요 질문에는 예나 아니요와 짧은 말만, 언제·어디·얼마나 질문에는 그 사실만 답합니다.
- "어디가 불편해서 오셨어요?" 같은 열린 질문에는 가장 불편한 것 하나만 한두 문장으로 말합니다. 시작 시기, 양상, 동반 증상, 과거력은 학생이 물어볼 때까지 말하지 않습니다.
- 대본의 한 줄에 여러 사실이 있어도 그 줄 전체를 번역하거나 요약해서 답하지 않습니다. 지금 질문한 한 가지 사실만 골라 짧게 말하고 멈춥니다. 묻지 않은 동반 증상, 없는 증상 목록, 위험 신호, 진단 단서는 덧붙이지 않습니다.
- "소변에 특이한 게 없었나요?"처럼 넓게 물으면 실제 관찰한 변화 하나만 일상어로 답합니다. 거품·피·냄새·색을 체크리스트처럼 나열하지 않습니다. "혈뇨는 없고 거품뇨도 없어요" 같은 대답은 금지합니다. 학생이 피를 직접 물었을 때만 "피는 못 봤어요"처럼 답합니다. 거품을 직접 물었을 때만 그 사실을 답합니다. 이 예시는 말하는 방식일 뿐이며, 증상 유무는 반드시 이 사례의 사실을 따릅니다.
- "또 불편한 건 없으세요?"에도 실제 있는 불편 하나만 말합니다. 구체적으로 물어본 사실은 숨기거나 일부러 모른다고 하지 말고, 묻지 않은 세부사항만 남겨 둡니다. 질문이 불분명하면 "어떤 걸 말씀하시는 거예요?"처럼 한 번 확인합니다.
- 생각·걱정·기대는 학생이 묻거나 분명히 기회를 줄 때까지 먼저 꺼내지 않습니다. 다만 학생이 설명을 마쳤는데 걱정하던 병 이야기가 없으면 "혹시 ~은 아닌가요?"라고 한 번 물어봅니다.
- 학생이 질문을 두 개 한꺼번에 하면 실제 사람처럼 하나만 대답하고 다시 묻게 둘 수 있습니다.
- 대답할 때마다 되묻거나 고맙다고 하지 않습니다. 짧게 대답하고 조용히 기다리는 것이 자연스럽습니다.
- 감정은 말투와 표현으로 드러내고, 말을 길게 하지 않습니다.

[신체진찰] 이 연습은 음성으로만 진행됩니다.
- 학생이 진찰하겠다고 하면 협조합니다("네", "누웠어요", "앉았어요").
- 누르거나 두드릴 때의 통증은 위의 진찰 반응(없으면 현병력과 진찰 참고)에 근거가 있을 때만 "아, 거기 아파요"라고 반응합니다. 근거가 없으면 "괜찮아요"라고 합니다.
- 체온, 혈압, 청진 소리 같은 객관적 소견은 환자가 말하지 않습니다. 학생이 결과를 물으면 "선생님이 보신 대로요"처럼 넘깁니다.
- 학생이 진찰을 말로만 하고 넘어가도 괜찮습니다. 진찰 결과를 대신 말해 주거나 진찰을 재촉하지 않습니다.

[태도]
- 의사처럼 설명하거나 진단명을 먼저 말하지 않습니다. 가르치거나, 힌트를 주거나, 채점하지 않습니다. 연습이나 시험이라는 말을 하지 않습니다.
- 학생이 어려운 의학용어를 쓰면 무슨 뜻인지 되묻습니다.
- 성관계나 음주처럼 민감한 질문을 양해 없이 갑자기 받으면 살짝 당황하며 짧게 답합니다. 배려하며 물으면 솔직하게 답합니다.
- 학생이 진료를 정리하고 궁금한 점을 물으면, 사례의 걱정과 관련된 질문을 한 가지 합니다.
- 진료를 먼저 끝내거나 나가지 않습니다. 학생이 마무리하면 "네, 알겠습니다" 정도로 짧게 답합니다.
- 학생이 먼저 말을 걸 때까지 기다립니다.$guardian
""".trim()
    }

    private const val GUARDIAN_RULES = """

[보호자 역할] 환자가 어린아이이므로, 당신은 아이와 함께 온 엄마 또는 아빠(보호자)로서 대답합니다. 아이의 증상은 보호자가 관찰한 대로 말하고, 보호자 자신의 걱정도 같은 규칙에 따라 물어볼 때만 말합니다."""

    private const val FAMILY_RULES = """

[보호자 역할] 환자가 지금 스스로 병력을 말하기 어려운 상태이므로, 당신은 환자와 함께 온 가족(배우자, 자녀, 부모 중 사례에 맞는 사람)으로서 대답합니다. 환자의 증상과 경과는 가족이 곁에서 보고 들은 만큼만 말하고, 환자 본인만 알 수 있는 느낌은 "본인은 잘 말을 못 해요"처럼 넘깁니다. 가족 자신의 걱정도 같은 규칙에 따라 물어볼 때만 말합니다. 학생이 환자를 직접 진찰하겠다고 하면 협조합니다."""

    // ── Grading ────────────────────────────────────────────────────────────────────────────

    /** The ground-truth slice of the English case the grader needs, kept compact. */
    fun groundTruth(case: JSONObject): JSONObject {
        val out = JSONObject()
        listOf("age", "gender", "chief_complaint", "hpi_details", "ideas", "concerns", "expectations", "pmh", "medications", "social_hx")
            .forEach { key -> case.opt(key)?.takeIf { it != JSONObject.NULL }?.let { out.put(key, it) } }
        case.optJSONObject("teaching")?.let { t ->
            val teaching = JSONObject()
            listOf("diagnosis", "one_liner", "differentials", "red_flags").forEach { k -> t.opt(k)?.let { teaching.put(k, it) } }
            out.put("teaching", teaching)
        }
        case.optJSONObject("reference_soap")?.let { out.put("reference_soap", it) }
        case.optJSONObject("clinical_knowledge")?.let { k ->
            val knowledge = JSONObject()
            listOf("topic", "dangerous_errors", "management_principles").forEach { key -> k.opt(key)?.let { knowledge.put(key, it) } }
            out.put("clinical_knowledge", knowledge)
        }
        // What the patient was scripted to say and the findings the exam would have shown, so the
        // grader can tell a question that was asked from a fact that was merely volunteered.
        case.optJSONObject(SpScript.CASE_KEY)?.let { out.put(SpScript.CASE_KEY, it) }
        case.optJSONObject(CASE_KEY)?.optJSONArray("sheet_results")?.takeIf { it.length() > 0 }
            ?.let { out.put("results_on_problem_sheet", it) }
        return out
    }

    /** The PPI and diagnosis-and-plan items this session actually scores (its checklist decides). */
    fun scoredPpi(session: SessionCase): List<Item> =
        session.checklist.firstOrNull { it.key == SECTION_PPI }?.items?.map { it.item }.orEmpty()

    fun scoredContent(session: SessionCase): List<Item> =
        session.checklist.firstOrNull { it.key == SECTION_CONTENT }?.items?.map { it.item }.orEmpty()

    fun formatTranscript(turns: List<Pair<String, String>>, guardian: Boolean = false): String {
        val other = if (guardian) "보호자" else "환자"
        return turns.filter { it.second.isNotBlank() }.mapIndexed { i, (role, text) ->
            val who = if (role == "doctor" || role == "user") "학생의사" else other
            "[${i + 1}] $who: ${text.trim()}"
        }.joinToString("\n")
    }

    const val GRADER_SYSTEM_PROMPT = """당신은 한국 의과대학에서 CPX(진료수행시험)를 평가하는 임상 교수입니다. 학생의사와 표준화 환자의 대화 기록을 읽고, 주어진 채점표의 항목을 하나씩 판정합니다. 모든 문장은 한국어로 쓰고, 설명 없이 JSON 객체 하나만 출력합니다.

판정 원칙:
- done: 대화에서 그 내용을 분명히 묻거나 수행하거나 설명했다.
- 환자가 먼저 나열한 사실만으로 학생이 그 세부 항목을 물었다고 인정하지 않는다. "소변에 특이한 게 있나요?"라는 포괄적 질문 뒤 환자가 피·거품을 스스로 언급했어도, 각각을 구체적으로 확인하는 학생의 질문이 없으면 혈뇨·거품 여부 질문 항목은 done이 아니다. 학생이 실제로 확인한 범위에 따라 partial 또는 missed로 판정한다.
- partial: 시도했지만 불완전하다(일부만 물음, 핵심 없이 대충 넘어감, 환자가 이해할 수 없게 설명함).
- missed: 대화에 없다.
- na: 이 환자와 상황에 해당하지 않는 항목일 때만 쓴다. "해당 시" 항목에서 해당 상황이 아니면 na.
- 음성 연습이므로, 학생이 무엇을 어떻게 진찰하는지 말로 구체적으로 밝히면 그 진찰을 수행한 것으로 본다. "배 좀 볼게요"처럼 방법과 부위가 불분명하면 partial.
- evidence에는 학생의 말을 짧게 그대로 인용한다. 없으면 무엇이 빠졌는지 쓴다. 인용을 지어내지 않는다.
- 음성 인식 오류로 보이는 어색한 단어는 뜻으로 판단한다.
- 의학 내용은 CASE GROUND TRUTH(실제 진단과 교육 자료)와 채점 참고를 기준으로 판단한다. 학생이 환자에게 실제로 말하지 않은 생각은 짐작하지 않는다.
- safety_flags에는 학생이 환자에게 말한 틀린 의학 정보, 위험한 조언, 놓치면 위험한 상황을 놓친 것만 넣는다. 없으면 빈 배열."""

    /** (system, user) prompts for grading one session. */
    fun buildGradingPrompts(session: SessionCase, turns: List<Pair<String, String>>): Pair<String, String> {
        val checklistLines = session.checklist
            .filter { it.key != SECTION_PPI && it.key != SECTION_CONTENT }
            .joinToString("\n\n") { section ->
                "## ${section.label}\n" + section.items.joinToString("\n") { ci ->
                    buildString {
                        append("- ").append(ci.key).append(": ").append(ci.item.text)
                        if (ci.item.say.isNotEmpty()) append(" (예: \"").append(ci.item.say).append("\")")
                        if (ci.item.key) append(" [핵심]")
                        if (ci.item.conditional) append(" [해당 시]")
                        if (ci.item.fromCase) append(" [증례 핵심 질문 — label에 한국어 항목명]")
                    }
                }
            }
        val ppiItems = scoredPpi(session)
        val contentItems = scoredContent(session)
        val ppiLines = ppiItems.joinToString("\n") { "- ${it.id}: ${it.text} — ${it.description}" }
        val contentLines = contentItems.joinToString("\n") { "- ${it.id}: ${it.text} — ${it.description}" }
        val scale = session.common.ppiScale
        val scaleText = if (session.common.ppiLevels.size == scale) {
            session.common.ppiLevels.mapIndexed { i, label -> "${i + 1} $label" }.joinToString(", ")
        } else "1 매우 미흡, 3 보통, 5 매우 우수"
        val taskText = session.tasks.joinToString(", ") {
            when (it) { TASK_HISTORY -> "병력청취"; TASK_EXAM -> "신체진찰"; else -> "추정진단·계획 설명" }
        }
        val groups = session.presentation.groups.joinToString("\n") { "- ${it.label}: ${it.examples.joinToString(", ")}" }
        val notes = session.presentation.graderNotes.joinToString("\n") { "- $it" }
        // Symptom stations score their own history lines; the case's must-ask list still tells the
        // grader what this particular patient's history hinges on.
        val caseMustAsk = if (session.presentation.scoreCaseMustAsk) "" else session.caseItems
            .joinToString("\n") { "- ${it.text}" }

        val contentBlock = if (contentItems.isEmpty()) "" else """

# 진단·계획 — 각 항목을 good(적절) / partial(부분적) / poor(부적절하거나 틀림) / not_stated(말하지 않음)로 평가하세요
$contentLines"""
        val contentJson = if (contentItems.isEmpty()) "" else """
  "clinical_content": [
    {"id": "<진단·계획 id>", "rating": "good | partial | poor | not_stated", "comment": "<한두 문장. 무엇이 맞고 무엇이 빠졌는지>"}
  ],"""

        val user = """
# 스테이션
증상: ${session.presentation.title}
환자 요약: ${session.doorNote}
이번 연습 범위: $taskText (범위 밖의 일은 채점하지 않습니다. 학생이 하지 않았다고 감점하지 마세요.)

# 감별 범주 (이 증상에서 생각해야 할 질환)
${groups.ifEmpty { "- (없음)" }}

# 채점 참고 (검사·치료 계획 판단 기준)
${notes.ifEmpty { "- (없음)" }}
${if (caseMustAsk.isEmpty()) "" else "\n# 이 증례에서 특히 물어야 할 것 (영어, 병력청취 판정 참고용)\n$caseMustAsk\n"}
# CASE GROUND TRUTH (학생에게 보이지 않은 실제 증례, 영어)
${groundTruth(session.case).toString(2)}

# 채점표 — 각 항목을 판정하세요
$checklistLines

# PPI (환자-의사 관계) — 표준화 환자의 입장에서 각 항목을 1~${scale}점으로 평가하세요 ($scaleText)
$ppiLines$contentBlock

# 대화 기록
${formatTranscript(turns, session.usesGuardian)}

# 출력 형식 (JSON 객체 하나만)
{
  "items": [
    {"key": "<채점표 항목 key 그대로>", "status": "done | partial | missed | na", "evidence": "<짧은 인용 또는 빠진 내용>", "label": "<[증례 핵심 질문] 항목만: 한국어 항목명>"}
  ],
  "ppi": [
    {"id": "<PPI id>", "score": <1-$scale 정수>, "comment": "<한 문장>"}
  ],$contentJson
  "student_diagnosis": "<학생이 환자에게 말한 가장 가능성 높은 진단. 없으면 빈 문자열>",
  "actual_diagnosis": "<증례의 실제 진단을 한국어로>",
  "safety_flags": [
    {"issue": "<무엇이 위험했는지>", "severity": "critical | major | minor", "quote": "<학생의 말>"}
  ],
  "summary": "<총평 2~3문장>",
  "strengths": ["<잘한 점, 1~3개>"],
  "improvements": ["<다음에 고칠 점, 가장 중요한 것부터 1~4개>"],
  "model_lines": [
    {"situation": "<어느 순간>", "better": "<이렇게 말하면 더 좋은 한국어 멘트>"}
  ]
}
채점표의 모든 항목을 순서대로 한 번씩 판정하고, PPI${if (contentItems.isEmpty()) "" else "와 진단·계획"}도 모든 id를 채우세요. evidence와 comment는 짧게 쓰세요.
출력은 들여쓰기 없는 간결한 JSON으로 합니다. evidence는 짧은 인용 하나 또는 누락 내용 한 구절만, comment는 짧은 한 문장만 씁니다. 같은 근거를 길게 반복하지 말고 model_lines는 가장 중요한 3개 이내로 제한합니다. 출력 길이를 줄이려고 채점 항목이나 안전 문제를 생략하지 않습니다.
""".trim()
        return GRADER_SYSTEM_PROMPT to user
    }

    private fun strings(raw: JSONArray?): List<String> =
        if (raw == null) emptyList()
        else (0 until raw.length()).mapNotNull { raw.opt(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    private fun stringMap(raw: JSONObject?): Map<String, String> =
        raw?.keys()?.asSequence()?.mapNotNull { k -> raw.optString(k).trim().takeIf { it.isNotEmpty() }?.let { k to it } }?.toMap().orEmpty()
}
