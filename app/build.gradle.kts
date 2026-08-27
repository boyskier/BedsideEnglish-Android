plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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
            "drills", "chart_drills", "team_communication", "foundations", "teachback"
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
                    "urgency"
                ).forEach { key ->
                    if (json.containsKey(key)) entry[key] = json[key]?.toString() ?: ""
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
        versionCode = 15
        versionName = "1.1.4"
        buildConfigField(
            "String",
            "AI_REPORT_ENDPOINT",
            // The production reporting endpoint is intentionally excluded from public source.
            "\"\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
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
    // without Robolectric. See docs/migration/PORTING_STATUS.md.
    testImplementation("org.json:json:20240303")
    // Drives GeminiLiveClient's real OkHttp socket machinery against a local server so the
    // setup-rejection/candidate-fallback paths are regression-tested on the JVM.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
