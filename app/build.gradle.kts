import java.util.Properties
import kotlin.math.abs

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

val aiReportEndpoint = providers.environmentVariable("AI_REPORT_ENDPOINT")
    .orElse(providers.gradleProperty("AI_REPORT_ENDPOINT"))
    .getOrElse(localProperties.getProperty("AI_REPORT_ENDPOINT").orEmpty())
    .trim()

// Nursing track visibility. Hidden in release builds until the track launches, visible in debug
// builds so it can be exercised on a device. Force it either way with NURSING_TRACK=true|false as an
// environment variable, a Gradle property (-PNURSING_TRACK=true), or a line in local.properties.
val nursingTrackOverride: String? = providers.environmentVariable("NURSING_TRACK")
    .orElse(providers.gradleProperty("NURSING_TRACK"))
    .orNull
    ?: localProperties.getProperty("NURSING_TRACK")
fun nursingTrackEnabled(default: Boolean): String =
    (nursingTrackOverride?.trim()?.toBooleanStrictOrNull() ?: default).toString()

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val generatedCaseCatalogDir = layout.buildDirectory.dir("generated/caseCatalog")
val teamCommunicationCaseIds = setOf(
    "consult_cardio_stable", "consult_icu_urgent", "consult_missing_data",
    "consult_unclear_question", "referral_followup_pulmonary", "transfer_sepsis_acceptance",
    "chart_sbar_acs", "handover_night_shift", "drill_register_switching",
    "drill_speak_up", "drill_clarify_orders",
)
val validateTeamCommunicationCases by tasks.registering {
    val sourceDir = layout.projectDirectory.dir("../data/cases")
    inputs.dir(sourceDir)
    doLast {
        val parser = groovy.json.JsonSlurper()
        val cases = sourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                val json = runCatching { parser.parse(file) as? Map<*, *> }.getOrNull() ?: return@mapNotNull null
                json["id"]?.toString()?.let { it to json }
            }
            .toMap()
        val missing = teamCommunicationCaseIds - cases.keys
        check(missing.isEmpty()) { "Missing Team Communication cases: ${missing.sorted().joinToString()}" }
        teamCommunicationCaseIds.sorted().forEach { id ->
            val case = cases.getValue(id)
            check(case["session_mode"] == "team_communication") { "$id must route to team_communication" }
            val brief = case["team_brief"] as? Map<*, *>
                ?: error("$id must provide a learner-visible team_brief")
            listOf("case_type", "title", "task", "situation", "your_request", "must_include").forEach { key ->
                val value = brief[key]
                check(value != null && value.toString().isNotBlank()) { "$id team_brief is missing $key" }
                if (key == "must_include") {
                    check(value is Iterable<*> && value.any()) { "$id team_brief must_include must not be empty" }
                }
            }
        }
    }
}
/**
 * Nursing track (MVP, 2026-08). The track is content-only by construction: the runtime reuses the
 * team-brief schema, the session pipeline, and the eval loader unchanged, so extending it means
 * dropping another JSON into `data/cases/nursing/` — nothing in Kotlin has to change.
 *
 * That is exactly why this validation exists. With no per-case ID allow-list to catch a typo (the
 * Team Communication check above pins a fixed set), the only thing standing between a malformed
 * new case and a silently broken tile is this task, so it validates the *shape* every nursing case
 * must have: the task family it files under, an eval rubric that actually ships, and a complete
 * learner-visible brief.
 */
val nursingTaskFamilies = setOf(
    "OET role-play", "Handover & escalation", "Patient education", "Bedside care",
    "Speak up for safety", "Job interview",
)
// Mirrors NursingTrack.PATHWAYS / COUNTERPARTS: the picker's goal chips and the deterministic
// feedback checks read these, so a typo would silently hide a case or skip its checks.
val nursingPathways = setOf("oet", "us", "uk_au")
val nursingCounterparts = setOf("patient", "relative", "physician", "nurse", "pharmacist", "interviewer")
val validateNursingCases by tasks.registering {
    val sourceDir = layout.projectDirectory.dir("../data/cases/nursing")
    val evalDir = layout.projectDirectory.dir("../data/eval")
    inputs.dir(sourceDir)
    inputs.dir(evalDir)
    doLast {
        val parser = groovy.json.JsonSlurper()
        val availableEvalTemplates = evalDir.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .toSet()
        val files = sourceDir.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        check(files.isNotEmpty()) { "data/cases/nursing/ must contain at least one case" }

        val seenIds = mutableMapOf<String, String>()
        files.sortedBy { it.name }.forEach { file ->
            val json = parser.parse(file) as? Map<*, *>
                ?: error("${file.name} is not a JSON object")
            fun field(key: String): String = json[key]?.toString()?.trim().orEmpty()

            val id = field("id")
            check(id.isNotBlank()) { "${file.name} is missing an id" }
            seenIds.put(id, file.name)?.let { previous ->
                error("Duplicate nursing case id '$id' in ${file.name} and $previous")
            }
            check(field("session_mode") == "nursing") { "$id must set session_mode to \"nursing\"" }
            val task = field("nursing_task")
            check(task in nursingTaskFamilies) {
                "$id has nursing_task '$task'; expected one of ${nursingTaskFamilies.sorted().joinToString()}"
            }
            val evalTemplate = field("eval_template")
            check(evalTemplate in availableEvalTemplates) {
                "$id declares eval_template '$evalTemplate', which has no data/eval/$evalTemplate.json"
            }
            listOf("patient_name", "chief_complaint", "kickoff_text", "persona_override").forEach { key ->
                check(field(key).isNotBlank()) { "$id is missing $key" }
            }

            // The brief is the learner's source of truth during the session: facts the simulated
            // counterpart relies on must be authored here, not hidden only in persona_override.
            val brief = json["team_brief"] as? Map<*, *>
                ?: error("$id must provide a learner-visible team_brief")
            listOf("case_type", "title", "task", "situation", "your_request", "must_include").forEach { key ->
                val value = brief[key]
                check(value != null && value.toString().isNotBlank()) { "$id team_brief is missing $key" }
                if (key == "must_include") {
                    check(value is Iterable<*> && value.any()) { "$id team_brief must_include must not be empty" }
                }
            }
            check(brief["task"]?.toString() == task) {
                "$id team_brief.task must match nursing_task ('$task')"
            }
            val pathways = json["nursing_pathways"] as? Iterable<*>
            check(pathways != null && pathways.any()) { "$id must list at least one nursing_pathways entry" }
            pathways.forEach { pathway ->
                check(pathway?.toString() in nursingPathways) {
                    "$id has nursing_pathways entry '$pathway'; expected one of ${nursingPathways.sorted().joinToString()}"
                }
            }
            val counterpart = field("counterpart")
            check(counterpart in nursingCounterparts) {
                "$id has counterpart '$counterpart'; expected one of ${nursingCounterparts.sorted().joinToString()}"
            }
            // OET Speaking role-plays are always a nurse with a patient, relative or carer, and
            // are scored on the OET criteria; anything else would teach the wrong exam.
            if (task == "OET role-play") {
                check(evalTemplate == "nursing_oet_roleplay") { "$id is an OET role-play but is not scored on nursing_oet_roleplay" }
                check(counterpart in setOf("patient", "relative")) { "$id is an OET role-play, so its counterpart must be a patient or relative" }
            }
            if (task == "Job interview") {
                check(counterpart == "interviewer") { "$id is a job interview, so its counterpart must be the interviewer" }
            }
        }
    }
}
/**
 * Korean CPX track (한국 의사국시 CPX). Content-only like the nursing track: a presentation is a
 * JSON file in `data/kmle_cpx/presentations/` whose `case_ids` point at existing English
 * encounter cases. A typo'd case id would silently shrink a station's patient pool, and a broken
 * file would silently drop the station from the picker, so both are build errors.
 */
val kmleCpxApplies = setOf("female", "male", "female_reproductive_age", "female_over_45", "child", "adult")
// Case folders that hold role-plays, drills or other modes rather than standard encounters.
val kmleCpxNonEncounterGroups = setOf(
    "nursing", "lounge", "drills", "foundations", "teachback", "team_communication", "chart_drills", "follow_up",
)
val kmleCpxTasks = setOf("history", "physical_exam", "education")
val validateKmleCpxContent by tasks.registering {
    val contentDir = layout.projectDirectory.dir("../data/kmle_cpx")
    val casesDir = layout.projectDirectory.dir("../data/cases")
    val maneuverFile = layout.projectDirectory.file("../data/exam_maneuvers.json")
    inputs.dir(contentDir)
    inputs.dir(casesDir)
    inputs.file(maneuverFile)
    doLast {
        val parser = groovy.json.JsonSlurper()
        val common = parser.parse(contentDir.file("common.json").asFile) as? Map<*, *>
            ?: error("data/kmle_cpx/common.json is not a JSON object")
        val weights = common["weights"] as? Map<*, *> ?: error("data/kmle_cpx/common.json needs weights")
        val weightSum = weights.values.sumOf { (it as Number).toDouble() }
        check(abs(weightSum - 100.0) < 0.001) { "data/kmle_cpx/common.json weights must sum to 100 (got $weightSum)" }
        val ppiScale = (common["ppi_scale"] as? Number)?.toInt() ?: 5
        val ppiLevels = (common["ppi_levels"] as? List<*>).orEmpty()
        check(ppiLevels.isEmpty() || ppiLevels.size == ppiScale) { "common.json ppi_levels must have ppi_scale ($ppiScale) entries" }
        listOf("ppi", "clinical_content", "education", "opening").forEach { section ->
            (common[section] as? List<*>).orEmpty().forEach { raw ->
                val item = raw as? Map<*, *> ?: return@forEach
                item["requires"]?.toString()?.let { requires ->
                    check(requires in kmleCpxTasks) { "common.json $section.${item["id"]} requires unknown task '$requires'" }
                }
            }
        }

        // The official list of clinical presentations; a station's kmle_item must name one.
        val officialItems = (parser.parse(contentDir.file("official_items.json").asFile) as? Map<*, *>)
            ?.get("items") as? List<*> ?: error("data/kmle_cpx/official_items.json needs an \"items\" array")
        val officialNames = officialItems.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }.toSet()

        // Every case's sp_script must use the shared examination vocabulary.
        val maneuverIds = ((parser.parse(maneuverFile.asFile) as? Map<*, *>)?.get("maneuvers") as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("id")?.toString() }.toSet()
        check(maneuverIds.isNotEmpty()) { "data/exam_maneuvers.json has no maneuvers" }

        val caseIds = mutableSetOf<String>()
        val encounterCaseIds = mutableSetOf<String>()
        casesDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .forEach { file ->
                caseIds += file.nameWithoutExtension
                val json = runCatching { parser.parse(file) as? Map<*, *> }.getOrNull()
                (json?.get("sp_script") as? Map<*, *>)?.let { script ->
                    val caseId = json["id"]?.toString() ?: file.nameWithoutExtension
                    val vitals = script["vitals"] as? Map<*, *>
                    vitals?.get("bp")?.toString()?.let { bp ->
                        check(Regex("^\\d{2,3}/\\d{2,3}$").matches(bp)) { "$caseId sp_script.vitals.bp '$bp' is not systolic/diastolic" }
                    }
                    listOf("temp_c", "hr", "rr", "spo2").forEach { key ->
                        vitals?.get(key)?.let { check(it is Number) { "$caseId sp_script.vitals.$key must be a number" } }
                    }
                    (script["exam"] as? List<*>).orEmpty().forEach { raw ->
                        val maneuver = (raw as? Map<*, *>)?.get("maneuver")?.toString().orEmpty()
                        check(maneuver in maneuverIds) { "$caseId sp_script uses unknown maneuver '$maneuver' (add it to data/exam_maneuvers.json)" }
                    }
                    (script["history"] as? List<*>).orEmpty().forEach { raw ->
                        val row = raw as? Map<*, *>
                        check(row?.get("topic")?.toString()?.isNotBlank() == true && row["answer"]?.toString()?.isNotBlank() == true) {
                            "$caseId sp_script.history has an entry without topic/answer"
                        }
                    }
                }
                val group = file.parentFile.name
                if (group in kmleCpxNonEncounterGroups) return@forEach
                if (json == null) return@forEach
                if (json.containsKey("persona_override") || json.containsKey("session_mode")) return@forEach
                encounterCaseIds += json["id"]?.toString() ?: file.nameWithoutExtension
            }

        val files = contentDir.dir("presentations").asFile.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        check(files.isNotEmpty()) { "data/kmle_cpx/presentations/ must contain at least one presentation" }
        val pools = mutableMapOf<String, MutableSet<String>>()
        files.sortedBy { it.name }.forEach { file ->
            val json = parser.parse(file) as? Map<*, *> ?: error("${file.name} is not a JSON object")
            val id = json["id"]?.toString().orEmpty()
            check(id == file.nameWithoutExtension) { "${file.name} must declare id \"${file.nameWithoutExtension}\"" }
            listOf("title", "door_complaint", "category").forEach { key ->
                check(json[key]?.toString()?.isNotBlank() == true) { "$id is missing $key" }
            }
            (json["tasks"] as? List<*>)?.let { declared ->
                check(declared.all { it.toString() in kmleCpxTasks }) { "$id has an unknown task in $declared" }
                check("history" in declared.map { it.toString() }) { "$id tasks must include history" }
            }
            (json["task_stems"] as? Map<*, *>)?.keys?.forEach { task ->
                check(task.toString() in kmleCpxTasks) { "$id task_stems has unknown task '$task'" }
            }
            json["kmle_item"]?.toString()?.takeIf { it.isNotBlank() }?.let { item ->
                check(item in officialNames) { "$id kmle_item '$item' is not in data/kmle_cpx/official_items.json" }
            }
            val groupKeys = (json["differential_groups"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.get("key")?.toString() }
                .toSet()
            listOf("history", "physical_exam", "education").forEach { section ->
                val seen = mutableSetOf<String>()
                (json[section] as? List<*>).orEmpty().forEach { raw ->
                    val item = raw as? Map<*, *> ?: error("$id.$section has a non-object item")
                    val itemId = item["id"]?.toString().orEmpty()
                    check(itemId.isNotBlank() && item["text"]?.toString()?.isNotBlank() == true) { "$id.$section has an item without id/text" }
                    check(seen.add(itemId)) { "$id.$section repeats item id $itemId" }
                    (item["tags"] as? List<*>).orEmpty().forEach { tag ->
                        check(tag.toString() in groupKeys) { "$id.$section.$itemId uses undeclared tag $tag" }
                    }
                    item["applies"]?.toString()?.let { applies ->
                        check(applies in kmleCpxApplies) { "$id.$section.$itemId has unknown applies '$applies'" }
                    }
                }
            }
            val listed = (json["case_ids"] as? List<*>).orEmpty().map { it.toString() }
            listed.forEach { caseId -> check(caseId in caseIds) { "$id lists case $caseId, which has no data/cases/*/$caseId.json" } }
            pools[id] = listed.toMutableSet()
        }
        check("general" in pools) { "data/kmle_cpx/presentations/general.json (the catch-all station) is missing" }

        // The index is what makes the whole case library playable in Korean: every standard
        // encounter case must be filed under a real station with a Korean door-card complaint.
        val index = (parser.parse(contentDir.file("case_index.json").asFile) as? Map<*, *>)?.get("cases") as? Map<*, *>
            ?: error("data/kmle_cpx/case_index.json needs a \"cases\" object")
        index.forEach { (caseId, raw) ->
            val row = raw as? Map<*, *> ?: error("case_index.json entry $caseId is not an object")
            check(caseId.toString() in caseIds) { "case_index.json lists $caseId, which has no data/cases/*/$caseId.json" }
            val presentation = row["presentation"]?.toString().orEmpty()
            check(presentation in pools) { "case_index.json files $caseId under unknown station '$presentation'" }
            check(row["door_complaint"]?.toString()?.isNotBlank() == true) { "case_index.json entry $caseId has no door_complaint" }
            val speaker = row["speaker"]?.toString().orEmpty()
            check(speaker.isEmpty() || speaker == "guardian") { "case_index.json entry $caseId has unknown speaker '$speaker'" }
            pools.getValue(presentation) += caseId.toString()
        }
        val unindexed = encounterCaseIds - index.keys.map { it.toString() }.toSet()
        check(unindexed.isEmpty()) { "data/kmle_cpx/case_index.json is missing encounter cases: ${unindexed.sorted().take(20).joinToString()}" }
        pools.forEach { (id, pool) -> check(pool.isNotEmpty()) { "Korean CPX station $id has no cases" } }
    }
}
val generateCaseCatalog by tasks.registering {
    val sourceDir = layout.projectDirectory.dir("../data/cases")
    val outputFile = generatedCaseCatalogDir.map { it.file("case_catalog.json") }
    inputs.dir(sourceDir)
    outputs.file(outputFile)

    doLast {
        val includedGroups = setOf(
            "gi", "cardio", "neuro", "pulm", "endo", "nephro", "hemato", "rheum",
            "id", "allergy", "gs", "ortho", "ns", "cs", "ps", "urology", "obgyn",
            "peds", "psych", "derm", "opht", "ent", "em", "pmr", "fm", "follow_up",
            "drills", "chart_drills", "team_communication", "foundations", "teachback",
            "nursing"
        )
        val parser = groovy.json.JsonSlurper()
        val entries = sourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                val relative = file.relativeTo(sourceDir.asFile).invariantSeparatorsPath
                val group = relative.substringBefore('/')
                if (group !in includedGroups) return@mapNotNull null
                val json = runCatching { parser.parse(file) as? Map<*, *> }.getOrNull()
                    ?: return@mapNotNull null
                val entry = linkedMapOf<String, Any?>(
                    "asset_path" to "cases/$relative",
                    "group" to group,
                    "id" to (json["id"]?.toString()
                        ?: if (group == "follow_up") file.nameWithoutExtension else file.name)
                )
                listOf(
                    "difficulty",
                    "patient_name",
                    "chief_complaint",
                    "hpi_details",
                    "scenario_name",
                    "setup",
                    "visit_title",
                    "follow_up_reason",
                    "follow_up_type",
                    "communication_task",
                    // Nursing cards group by task family; the picker's filter chips are built
                    // from this field alone, so it has to survive into the compact catalog.
                    "nursing_task",
                    "counterpart",
                    "station_minutes",
                    "urgency",
                    // The Korean CPX "all cases" browser shows each patient's age and sex without
                    // opening the case file.
                    "age",
                    "gender"
                ).forEach { key ->
                    if (json.containsKey(key)) entry[key] = json[key]?.toString() ?: ""
                }
                // The catalog is flat; NursingTrack.parsePathways reads this comma-joined form.
                (json["nursing_pathways"] as? Iterable<*>)?.let { pathways ->
                    entry["nursing_pathways"] = pathways.joinToString(",")
                }
                // Team scenarios have a learner-facing brief distinct from the AI persona. Keep
                // compact card metadata in the catalog so the picker identifies the real patient
                // and task before opening the full case JSON.
                val teamBrief = json["team_brief"] as? Map<*, *>
                if (teamBrief != null) {
                    entry["team_title"] = teamBrief["title"]?.toString().orEmpty()
                    entry["team_case_type"] = teamBrief["case_type"]?.toString().orEmpty()
                    val patient = teamBrief["patient"]?.toString()?.trim().orEmpty()
                    val situation = teamBrief["situation"]?.toString()?.trim().orEmpty()
                    entry["team_description"] = listOf(patient, situation)
                        .filter { it.isNotEmpty() }
                        .joinToString(" — ")
                }

                // Keep encounter search instant and offline: ship a compact text index in the
                // catalog instead of opening 1,000+ full case JSON files while the user types.
                // Diagnosis gets its own field for relevance ranking; the broader index makes
                // symptoms, learning topics, and differentials discoverable too.
                val teaching = json["teaching"] as? Map<*, *>
                val clinicalKnowledge = json["clinical_knowledge"] as? Map<*, *>
                val diagnosis = teaching?.get("diagnosis")?.toString().orEmpty()
                if (diagnosis.isNotBlank()) entry["diagnosis"] = diagnosis

                val searchParts = mutableListOf<String>()
                fun addSearchValue(value: Any?) {
                    when (value) {
                        is Iterable<*> -> value.forEach(::addSearchValue)
                        is Array<*> -> value.forEach(::addSearchValue)
                        else -> value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(searchParts::add)
                    }
                }
                addSearchValue(diagnosis)
                addSearchValue(teaching?.get("differentials"))
                addSearchValue(clinicalKnowledge?.get("topic"))
                addSearchValue(json["learning_objectives"])
                addSearchValue(json["ideas"])
                addSearchValue(json["chief_complaint"])
                addSearchValue(json["hpi_details"])
                if (searchParts.isNotEmpty()) entry["search_text"] = searchParts.distinct().joinToString("\n")
                entry
            }
            .sortedBy { it["asset_path"].toString() }
            .toList()
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(groovy.json.JsonOutput.toJson(entries), Charsets.UTF_8)
    }
}

android {
    namespace = "com.example.medvoicetrainer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.boyskier.bedsideenglish"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.0"
        buildConfigField(
            "String",
            "AI_REPORT_ENDPOINT",
            aiReportEndpoint.asBuildConfigString()
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "NURSING_TRACK_ENABLED", nursingTrackEnabled(default = true))
        }

        release {
            buildConfigField("boolean", "NURSING_TRACK_ENABLED", nursingTrackEnabled(default = false))
            // R8-only release verification completed before resource shrinking was enabled:
            // assembleRelease succeeded and the release suite matched the pre-R8 baseline.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // The repo-root data/ tree (cases, eval rubrics, interview banks, survival axes,
            // wordlists, user guides) is the single source of truth for scenario/content JSON,
            // kept in the original desktop app's layout so the same files stay usable outside
            // Android. It is merged into the APK's assets at build time alongside
            // src/main/assets (which holds the Android-only i18n/ locale overrides).
            assets.srcDirs("src/main/assets", "../data", generatedCaseCatalogDir)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateCaseCatalog)
    dependsOn(validateTeamCommunicationCases)
    dependsOn(validateNursingCases)
    dependsOn(validateKmleCpxContent)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    // app-update 2.1.0 still pulls Fragment 1.0.0 transitively. Activity Result APIs require
    // Fragment 1.3.0+, so align it explicitly instead of suppressing the release-blocking lint
    // check. R8 removes unused Fragment implementation code from the final APK.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.serialization.json)
    
    // Security & Network
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)

    // Google Play in-app updates. Only functional for builds installed from a Play track (the
    // closed-beta testers); on a sideloaded/debug APK the Play task fails and the app falls back
    // to opening the store listing — see InAppUpdateManager.
    implementation(libs.play.app.update)
    implementation(libs.androidx.work.runtime)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // Real org.json for local unit tests (golden vectors + any JSON-path logic).
    // The Android SDK bundles an org.json *stub* that throws "not mocked" in plain
    // JVM tests; this dependency shadows it so those code paths can be tested
    // without Robolectric. See docs/legacy/migration/PORTING_STATUS.md.
    testImplementation("org.json:json:20240303")
    // Drives GeminiLiveClient's real OkHttp socket machinery against a local server so the
    // setup-rejection/candidate-fallback paths are regression-tested on the JVM.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
