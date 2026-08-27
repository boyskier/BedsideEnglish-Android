package com.example.medvoicetrainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.BuildConfig
import com.example.medvoicetrainer.analysis.ListeningDrillEngine
import com.example.medvoicetrainer.analysis.EncounterCaseSearchIndex
import com.example.medvoicetrainer.analysis.EncounterSearchDocument
import com.example.medvoicetrainer.analysis.SurvivalComposer
import com.example.medvoicetrainer.analysis.toAnalysisMap
import com.example.medvoicetrainer.analysis.toDeepMap
import com.example.medvoicetrainer.ui.ActiveSessionState
import com.example.medvoicetrainer.ui.CustomPracticeProfile
import com.example.medvoicetrainer.ui.EncounterCaseTag
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.PracticeExperience
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PracticeCase(
    val id: String,
    val title: String,
    val description: String,
    val jsonContent: String,
    val category: String,
    val system: String = "",
    /** Beginner/intermediate/advanced metadata copied into the lightweight case catalog. */
    val difficulty: String = "",
    /** Compact catalog fields used for offline search without materializing the full case. */
    val diagnosis: String = "",
    val searchText: String = "",
    /** Bundled source loaded only when the learner opens this case. */
    val assetPath: String? = null,
)

private object BundledCaseCatalog {
    @Volatile private var cached: List<JSONObject>? = null

    fun load(viewModel: MainViewModel): List<JSONObject> {
        cached?.let { return it }
        val array = JSONArray(viewModel.loadAsset("case_catalog.json"))
        val loaded = List(array.length()) { array.getJSONObject(it) }
        synchronized(this) {
            cached?.let { return it }
            cached = loaded
        }
        return loaded
    }
}

private suspend fun PracticeCase.materialize(viewModel: MainViewModel): PracticeCase {
    if (jsonContent.isNotEmpty() || assetPath == null) return this
    val loaded = withContext(Dispatchers.IO) { viewModel.loadAsset(assetPath) }
    return copy(jsonContent = loaded)
}

/** Human-readable labels for the Encounter tab's body-system folders (`data/cases/<key>/`). */
private val ENCOUNTER_SYSTEM_LABELS = mapOf(
    "gi" to "GI / Abdominal",
    "cardio" to "Cardiovascular",
    "neuro" to "Neurology",
    "pulm" to "Pulmonary",
    "endo" to "Endocrinology",
    "nephro" to "Nephrology",
    "hemato" to "Hematology & Oncology",
    "rheum" to "Rheumatology",
    "id" to "Infectious Disease",
    "allergy" to "Allergy & Immunology",
    "gs" to "General Surgery",
    "ortho" to "Orthopedic Surgery",
    "ns" to "Neurosurgery",
    "cs" to "Cardiothoracic Surgery",
    "ps" to "Plastic Surgery",
    "urology" to "Urology",
    "obgyn" to "Obstetrics & Gynecology",
    "peds" to "Pediatrics",
    "psych" to "Psychiatry",
    "derm" to "Dermatology",
    "opht" to "Ophthalmology",
    "ent" to "Otolaryngology (ENT)",
    "em" to "Emergency Medicine",
    "pmr" to "Physical Medicine & Rehab",
    "fm" to "Family Medicine"
)

/**
 * Groups the 25 body-system folders into broad specialty families so the Encounter tab's system
 * filter can drill down (category -> subspecialty) instead of showing all 25 chips flat. Systems
 * that genuinely straddle families (e.g. Emergency Medicine, OB/GYN, ENT) are listed under more
 * than one category on purpose, per product decision.
 */
private val ENCOUNTER_SYSTEM_CATEGORIES = listOf(
    "Internal Medicine" to listOf("cardio", "gi", "pulm", "endo", "nephro", "hemato", "rheum", "id", "allergy", "neuro", "em"),
    "Surgery" to listOf("gs", "ortho", "ns", "cs", "ps", "urology", "obgyn", "ent", "em"),
    "Primary & Community" to listOf("fm", "peds", "psych", "derm", "opht", "pmr", "obgyn", "ent"),
)

/**
 * Cases authored for coaching/drill modes (Foundations, Drills) reuse the encounter schema's
 * `patient_name`/`chief_complaint` fields but always populate them (unlike free-form encounter
 * cases), so they make a reliable title/description pair without needing a separate schema.
 */
private fun titleAndDescriptionFrom(json: JSONObject, fallbackId: String, defaultDescription: String): Pair<String, String> {
    val name = json.optString("patient_name", "").ifBlank { json.optString("scenario_name", "") }.ifBlank { fallbackId }
    val complaint = json.optString("chief_complaint", "")
    val description = complaint.ifBlank { json.optString("setup", "") }.ifBlank { defaultDescription }
    return name to description
}

private enum class PracticeTabKey(val translationKey: String) {
    ENCOUNTER("tab.encounter"),
    FOLLOW_UP("tab.follow_up"),
    TEAM_COMMUNICATION("Team Communication"),
    // Scene transitions are now the standard Survival experience.
    SURVIVAL("tab.survival"),
    DRILLS("enc.system_drills"),
    FOUNDATIONS("enc.system_foundations"),
    SAYIT("tab.sayit"),
    LOUNGE("tab.lounge"),
    TEACHBACK("tab.teachback"),
    INTERVIEW("tab.interview"),
    EXAM("tab.exam"),
    LISTENING("listening_lab.title"),
    CUSTOM("tab.custom")
}

private data class PracticeTab(
    val key: PracticeTabKey,
    val label: String,
    val icon: ImageVector
)

/** Spec §4 one-line tile descriptions, keyed by mode — mirrors the mockup copy exactly. */
private fun practiceModeDescription(key: PracticeTabKey, everydayOnly: Boolean = false): String = when (key) {
    PracticeTabKey.ENCOUNTER -> "Full patient history, scored on a clinical checklist"
    PracticeTabKey.FOLLOW_UP -> "Read the prior chart, reassess progress, and agree on the next plan"
    PracticeTabKey.TEAM_COMMUNICATION -> "Present to a senior, hand over, request a consult, and transfer care"
    // Reframed from generic "pharmacy, taxi, small talk" toward the IMG-abroad niche a general
    // app like Speak never builds for — see docs/DIFFERENTIATION_ANSWERS.md Q2. Same composer/
    // data (data/survival_situations.json already ships a "Hospital Hallway" category), new copy.
    PracticeTabKey.SURVIVAL -> "The scene can move, skip ahead, or hand you to someone new"
    PracticeTabKey.DRILLS -> "Short targeted reps on one weak skill"
    PracticeTabKey.FOUNDATIONS -> "Warm-up chat & guided basics"
    PracticeTabKey.SAYIT ->
        if (everydayOnly) "Listen & repeat everyday expressions" else "Listen & repeat key clinical phrases"
    PracticeTabKey.LOUNGE -> "Free talk: debate, article, casual"
    PracticeTabKey.TEACHBACK -> "Explain a diagnosis in plain English"
    PracticeTabKey.INTERVIEW -> "Residency-match Q&A practice"
    PracticeTabKey.EXAM -> "Unlocks after your first scored encounter"
    PracticeTabKey.LISTENING -> "Accent & detail-recall drills"
    PracticeTabKey.CUSTOM -> "Unlocks after your first scored encounter"
}

/**
 * The two core practice loops — Encounter (the graded clinical OSCE loop) and Survival (the
 * keyless, low-barrier everyday-English loop) — get a full-width row each instead of sharing a
 * half-tile with eight equally-weighted modes, per product direction (2026-07).
 */
private val FEATURED_PRACTICE_MODES = setOf(
    PracticeTabKey.ENCOUNTER,
    PracticeTabKey.FOLLOW_UP,
    PracticeTabKey.TEAM_COMMUNICATION,
    PracticeTabKey.SURVIVAL,
)

private enum class EncounterCaseFilter(val tag: EncounterCaseTag?) {
    ALL(null),
    FAVORITES(EncounterCaseTag.FAVORITE),
    TODO(EncounterCaseTag.TODO),
    DIFFICULT(EncounterCaseTag.DIFFICULT),
}

private enum class EncounterDifficulty(val catalogValue: String?) {
    ALL(null),
    BEGINNER("beginner"),
    INTERMEDIATE("intermediate"),
    ADVANCED("advanced"),
}

private data class EncounterFilterOption(
    val value: String?,
    val label: String,
)

private data class EncounterSearchResult(
    val query: String = "",
    val scores: Map<String, Int> = emptyMap(),
)

private fun encounterDifficultyLabel(difficulty: String): String = when (difficulty.lowercase()) {
    "beginner" -> "Beginner"
    "intermediate" -> "Intermediate"
    "advanced" -> "Advanced"
    else -> difficulty.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncounterFilterDropdown(
    label: String,
    selectedLabel: String,
    options: List<EncounterFilterOption>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncounterCatalogFilters(
    categories: List<Pair<String, List<String>>>,
    availableSystems: Set<String>,
    selectedCategory: String?,
    selectedSystem: String?,
    selectedDifficulty: EncounterDifficulty,
    selectedSavedFilter: EncounterCaseFilter,
    onCategorySelected: (String?) -> Unit,
    onSystemSelected: (String?) -> Unit,
    onDifficultySelected: (EncounterDifficulty) -> Unit,
    onSavedFilterSelected: (EncounterCaseFilter) -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val subspecialties = categories
        .firstOrNull { it.first == selectedCategory }
        ?.second
        ?.filter { it in availableSystems }
        .orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EncounterFilterDropdown(
                label = t("Department"),
                selectedLabel = selectedCategory?.let(t) ?: t("All departments"),
                options = listOf(EncounterFilterOption(null, t("All departments"))) +
                    categories.map { (category, _) -> EncounterFilterOption(category, t(category)) },
                modifier = Modifier.weight(1f),
                onSelect = onCategorySelected,
            )
            EncounterFilterDropdown(
                label = t("Specialty"),
                selectedLabel = when {
                    selectedCategory == null -> t("Choose a department first")
                    selectedSystem == null -> t("All specialties in department")
                    else -> ENCOUNTER_SYSTEM_LABELS[selectedSystem] ?: selectedSystem
                },
                options = listOf(EncounterFilterOption(null, t("All specialties in department"))) +
                    subspecialties.map { system ->
                        EncounterFilterOption(system, ENCOUNTER_SYSTEM_LABELS[system] ?: system)
                    },
                modifier = Modifier.weight(1f),
                enabled = selectedCategory != null,
                onSelect = onSystemSelected,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = t("Difficulty"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EncounterDifficulty.entries.forEach { difficulty ->
                    val label = when (difficulty) {
                        EncounterDifficulty.ALL -> t("All levels")
                        EncounterDifficulty.BEGINNER -> t("Beginner")
                        EncounterDifficulty.INTERMEDIATE -> t("Intermediate")
                        EncounterDifficulty.ADVANCED -> t("Advanced")
                    }
                    FilterChip(
                        selected = selectedDifficulty == difficulty,
                        onClick = { onDifficultySelected(difficulty) },
                        label = { Text(label) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = t("My saved cases"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EncounterCaseFilter.entries.filterNot { it == EncounterCaseFilter.ALL }.forEach { filter ->
                    val label = when (filter) {
                        EncounterCaseFilter.FAVORITES -> t("Favorites")
                        EncounterCaseFilter.TODO -> t("Do later")
                        EncounterCaseFilter.DIFFICULT -> t("Marked difficult")
                        EncounterCaseFilter.ALL -> ""
                    }
                    val icon = when (filter) {
                        EncounterCaseFilter.FAVORITES -> Icons.Default.Star
                        EncounterCaseFilter.TODO -> Icons.Default.Schedule
                        EncounterCaseFilter.DIFFICULT -> Icons.Default.Warning
                        EncounterCaseFilter.ALL -> Icons.Default.BookmarkBorder
                    }
                    FilterChip(
                        selected = selectedSavedFilter == filter,
                        onClick = {
                            onSavedFilterSelected(
                                if (selectedSavedFilter == filter) EncounterCaseFilter.ALL else filter,
                            )
                        },
                        label = { Text(label) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EncounterBrowserControls(
    query: String,
    onQueryChange: (String) -> Unit,
    searchBusy: Boolean,
    categories: List<Pair<String, List<String>>>,
    availableSystems: Set<String>,
    selectedCategory: String?,
    selectedSystem: String?,
    selectedDifficulty: EncounterDifficulty,
    selectedSavedFilter: EncounterCaseFilter,
    selectedAccentKey: String,
    selectedStyleKey: String,
    visibleCount: Int,
    totalCount: Int,
    onCategorySelected: (String?) -> Unit,
    onSystemSelected: (String?) -> Unit,
    onDifficultySelected: (EncounterDifficulty) -> Unit,
    onSavedFilterSelected: (EncounterCaseFilter) -> Unit,
    onAccentSelected: (String) -> Unit,
    onStyleSelected: (String) -> Unit,
    onReset: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val activeFilterCount = listOf(
        selectedCategory != null,
        selectedSystem != null,
        selectedDifficulty != EncounterDifficulty.ALL,
        selectedSavedFilter != EncounterCaseFilter.ALL,
        selectedAccentKey != "us",
        selectedStyleKey != "clear",
    ).count { it }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = t("Clear search"))
                        }
                    }
                },
                placeholder = { Text(t("Search diagnosis, symptom, or case")) },
            )
            if (searchBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = t("{visible} of {total} cases")
                        .replace("{visible}", visibleCount.toString())
                        .replace("{total}", totalCount.toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (activeFilterCount > 0) {
                    TextButton(onClick = onReset) { Text(t("Reset")) }
                }
                TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (activeFilterCount > 0) {
                            t("Filters") + " ($activeFilterCount)"
                        } else {
                            t("Filters")
                        }
                    )
                    Icon(
                        if (filtersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            AnimatedVisibility(visible = filtersExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    Text(
                        text = t("Patient voice"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EncounterFilterDropdown(
                            label = t("Patient accent"),
                            selectedLabel = t(VOICE_ACCENTS.first { it.key == selectedAccentKey }.label),
                            options = VOICE_ACCENTS.map { EncounterFilterOption(it.key, t(it.label)) },
                            modifier = Modifier.weight(1f),
                            onSelect = { it?.let(onAccentSelected) },
                        )
                        EncounterFilterDropdown(
                            label = t("Delivery style"),
                            selectedLabel = t(VOICE_SPEECH_STYLES.first { it.key == selectedStyleKey }.label),
                            options = VOICE_SPEECH_STYLES.map { EncounterFilterOption(it.key, t(it.label)) },
                            modifier = Modifier.weight(1f),
                            onSelect = { it?.let(onStyleSelected) },
                        )
                    }
                    EncounterCatalogFilters(
                        categories = categories,
                        availableSystems = availableSystems,
                        selectedCategory = selectedCategory,
                        selectedSystem = selectedSystem,
                        selectedDifficulty = selectedDifficulty,
                        selectedSavedFilter = selectedSavedFilter,
                        onCategorySelected = onCategorySelected,
                        onSystemSelected = onSystemSelected,
                        onDifficultySelected = onDifficultySelected,
                        onSavedFilterSelected = onSavedFilterSelected,
                    )
                }
            }
        }
    }
}

private val TEAM_COMMUNICATION_DRILL_IDS = setOf(
    "handover_night_shift",
    "chart_sbar_acs",
    "drill_register_switching",
    "drill_speak_up",
    "drill_clarify_orders",
)

@Composable
private fun FollowUpBriefingScreen(
    case: PracticeCase,
    canStart: Boolean,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val root = remember(case.jsonContent) { JSONObject(case.jsonContent) }
    var showingSampleQuestions by rememberSaveable(case.id) { mutableStateOf(false) }
    var sampleQuestionsOpened by rememberSaveable(case.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("Back to follow-up cases"))
            }
        }
        item {
            Text(
                text = t(if (showingSampleQuestions) "Sample questions" else "Case notes"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = t(
                    if (showingSampleQuestions) {
                        "Use these as a starting point. You can say them as written or make them your own."
                    } else {
                        "Read what the clinician already knows. The patient's interval story, adherence, side effects, and concerns stay hidden until you ask."
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showingSampleQuestions) {
            item { FollowUpEnglishPlanCard(root) }
        } else {
            item { FollowUpChartCards(root) }
        }
        item {
            TextButton(
                onClick = {
                    showingSampleQuestions = !showingSampleQuestions
                    sampleQuestionsOpened = sampleQuestionsOpened || showingSampleQuestions
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(t(if (showingSampleQuestions) "Back to case notes" else "Open sample questions"))
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null)
                    Text(
                        t("The patient's private interval information is deliberately hidden. Ask focused, nonjudgmental questions to uncover it."),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!canStart) {
            item {
                Text(
                    t("Patient Follow-up needs a connected live voice provider. Add an API key in Settings to start this case."),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Button(
                onClick = {
                    onStart(withFollowUpPracticeFocus(case.jsonContent, sampleQuestionsOpened))
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(t("I have reviewed the chart — start follow-up"))
            }
        }
    }
}

internal fun withFollowUpPracticeFocus(caseJson: String, englishOnly: Boolean): String = runCatching {
    JSONObject(caseJson).apply {
        put("practice_focus", if (englishOnly) "english_speaking" else "clinical_and_english")
        val aids = optJSONObject("practice_aids") ?: JSONObject()
        if (englishOnly) {
            aids.put("follow_up_english_focus", true)
            aids.put("follow_up_question_plan", true)
        } else {
            aids.remove("follow_up_english_focus")
            aids.remove("follow_up_question_plan")
        }
        put("practice_aids", aids)
    }.toString()
}.getOrDefault(caseJson)

/**
 * §4: the Practice hub as a mode grid, not a horizontal tab strip — ten equal tiles would be
 * choice paralysis, so locked tiles (Exam/Custom, pre-first-score) spell out their unlock
 * condition right on the tile instead of dead-tapping.
 *
 * This grid used to open with a "recommended next" strip fed by DailyMissionEngine. That engine is
 * deterministic, and the Dashboard's mission card feeds it the same three inputs, so the strip was
 * guaranteed — not merely likely — to restate the Home card word for word, minus its reason line
 * and its rationale. Two identical recommendations one tab apart read as two separate suggestions
 * that happen to agree; the mission now lives on Home only, where it can carry that rationale.
 */
@Composable
private fun PracticeModeGrid(
    tabs: List<PracticeTab>,
    lockedKeys: Set<PracticeTabKey>,
    onSelectMode: (PracticeTabKey) -> Unit,
    everydayOnly: Boolean = false,
    estimatedSessionCostUsd: Pair<Double, Boolean>? = null
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    // The whole hub is one scrollable grid: the title, cost estimate, and mode tiles scroll
    // together, like History's single LazyColumn.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(t("Practice"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        }
        estimatedSessionCostUsd?.let { (usd, fromHistory) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = t(if (fromHistory) "practice.cost_estimate_history" else "practice.cost_estimate_typical")
                        .replace("{cost}", "$" + "%.3f".format(usd)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        gridItems(
            items = tabs,
            span = { tab -> if (tab.key in FEATURED_PRACTICE_MODES) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
        ) { tab ->
                val locked = tab.key in lockedKeys
                val featured = tab.key in FEATURED_PRACTICE_MODES
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectMode(tab.key) },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            featured -> MaterialTheme.colorScheme.primaryContainer
                            locked -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    if (featured) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tab.label,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    t(practiceModeDescription(tab.key, everydayOnly)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Icon(tab.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                if (locked) Icon(Icons.Default.Lock, contentDescription = t("Locked"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (locked) "${tab.label} 🔒" else tab.label,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                t(practiceModeDescription(tab.key, everydayOnly)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(
    viewModel: MainViewModel,
    onStartCase: (String, String, String) -> Unit,
    onStartFollowUp: (String, String, String) -> Unit,
    onStartSurvival: (String, String, String) -> Unit,
    onStartListening: (String, String, String) -> Unit,
    onStartTeachback: (String, String, String) -> Unit,
    onStartInterview: (String, String, String) -> Unit,
    onStartExam: (String, String, String) -> Unit,
    requestedMode: String? = null,
    requestedPhraseDrill: List<com.example.medvoicetrainer.analysis.EverydayPhrase> = emptyList(),
    onRequestedModeConsumed: () -> Unit = {}
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    // §4: the Practice tab is a mode grid, not a horizontal tab strip — the ScrollableTabRow
    // below now only renders once a tile has been picked (it becomes the "picker" screen's
    // header), with a back arrow returning here. True until the first tile tap each time the
    // tab is (re)entered.
    var showModeGrid by remember { mutableStateOf(true) }
    var lockedModeExplainer by remember { mutableStateOf<PracticeTabKey?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val recentSessionCostSamples by viewModel.recentSessionCostSamples.collectAsStateWithLifecycle()
    val practiceExperience by viewModel.practiceExperience.collectAsStateWithLifecycle()
    val everydayOnly = practiceExperience == PracticeExperience.EVERYDAY_ENGLISH

    val listeningAttempts by viewModel.listeningAttempts.collectAsStateWithLifecycle()
    var listeningDrillJsons by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var adaptivePickMessage by remember { mutableStateOf<String?>(null) }
    var listeningLabCase by remember { mutableStateOf<PracticeCase?>(null) }
    var sayItTopicOpen by remember { mutableStateOf(false) }
    var encounterResultsBriefingCase by remember { mutableStateOf<PracticeCase?>(null) }
    var followUpBriefingCase by remember { mutableStateOf<PracticeCase?>(null) }
    var teamBriefingCase by remember { mutableStateOf<PracticeCase?>(null) }
    /** Which bank the Say It drill opens on, latched when a caller requests the tab. */
    var sayItStartsEveryday by remember { mutableStateOf(false) }
    var sayItSessionPhrases by remember {
        mutableStateOf<List<com.example.medvoicetrainer.analysis.EverydayPhrase>>(emptyList())
    }
    // Drives both selectors reactively. Each selector resolves its own concrete scene so a preview
    // can never be attributed to, or show expressions from, a different case.
    val phrasebookEnabled by viewModel.phrasebookEnabled.collectAsStateWithLifecycle()
    var survivalCatalog by remember { mutableStateOf<SurvivalComposer.Catalog?>(null) }
    var selectedEncounterCategory by remember { mutableStateOf<String?>(null) }
    var selectedEncounterSystem by remember { mutableStateOf<String?>(null) }
    var selectedEncounterDifficulty by rememberSaveable { mutableStateOf(EncounterDifficulty.ALL) }
    var selectedEncounterCaseFilter by rememberSaveable { mutableStateOf(EncounterCaseFilter.ALL) }
    var encounterSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCommunicationTask by remember { mutableStateOf<String?>(null) }
    var encounterAccentKey by remember { mutableStateOf("us") }
    var encounterStyleKey by remember { mutableStateOf("clear") }
    var showTeamCommunicationHelp by remember { mutableStateOf(false) }
    val isUnlocked = recentSessionCostSamples.isNotEmpty()
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val completedSessions by viewModel.sessions.collectAsStateWithLifecycle()
    val encounterCaseTags by viewModel.encounterCaseTags.collectAsStateWithLifecycle()
    val latestPresentationLaunch = remember(completedSessions) {
        completedSessions.asSequence()
            .sortedByDescending { it.createdAt }
            .mapNotNull { session ->
                com.example.medvoicetrainer.analysis.PresentationBuilder.buildLaunch(session.toAnalysisMap())
            }
            .firstOrNull()
    }
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val isVoiceModeMock by viewModel.isVoiceModeMock.collectAsStateWithLifecycle()
    var showDemoIntro by remember { mutableStateOf(false) }
    var contentReportCase by remember { mutableStateOf<PracticeCase?>(null) }

    // §Q1 cost anxiety (docs/DIFFERENTIATION_ANSWERS.md): shown on the mode-grid landing screen so
    // a learner sees a concrete number before ever starting a paid session, not just after. Free
    // backends (demo/mock) show nothing — there is nothing to estimate.
    val analysisBackend by viewModel.analysisBackend.collectAsStateWithLifecycle()
    val estimatedSessionCost = remember(recentSessionCostSamples, voiceBackend, analysisBackend) {
        if (voiceBackend == "demo" || voiceBackend == "mock") {
            null
        } else {
            com.example.medvoicetrainer.analysis.CostTracker.estimateTypicalSessionCostFromSamples(
                recentSessions = recentSessionCostSamples,
                voiceBackend = voiceBackend,
                analysisBackend = analysisBackend,
                analysisModel = viewModel.getModelForBackend(analysisBackend),
                // Live audio-out is priced the same across the Live models, but text-out is not
                // (4.50 on 3.x vs 2.00 on 2.5 native audio), and the audio token-per-second rate
                // differs too — so the projection has to know which one the learner is on.
                voiceModel = viewModel.getVoiceModelForBackend(voiceBackend),
            )
        }
    }

    val allTabs = listOf(
        PracticeTab(PracticeTabKey.ENCOUNTER, t(PracticeTabKey.ENCOUNTER.translationKey), Icons.Default.MedicalServices),
        PracticeTab(PracticeTabKey.FOLLOW_UP, t(PracticeTabKey.FOLLOW_UP.translationKey), Icons.Default.AssignmentInd),
        PracticeTab(PracticeTabKey.TEAM_COMMUNICATION, t(PracticeTabKey.TEAM_COMMUNICATION.translationKey), Icons.Default.Groups),
        PracticeTab(
            PracticeTabKey.SURVIVAL,
            t(PracticeTabKey.SURVIVAL.translationKey),
            if (everydayOnly) Icons.Default.Public else Icons.Default.LocalHospital
        ),
        PracticeTab(PracticeTabKey.DRILLS, t(PracticeTabKey.DRILLS.translationKey), Icons.Default.FitnessCenter),
        PracticeTab(PracticeTabKey.FOUNDATIONS, t(PracticeTabKey.FOUNDATIONS.translationKey), Icons.Default.School),
        PracticeTab(PracticeTabKey.SAYIT, t(PracticeTabKey.SAYIT.translationKey), Icons.Default.Hearing),
        PracticeTab(PracticeTabKey.LOUNGE, t(PracticeTabKey.LOUNGE.translationKey), Icons.Default.Coffee),
        PracticeTab(PracticeTabKey.TEACHBACK, t(PracticeTabKey.TEACHBACK.translationKey), Icons.Default.RecordVoiceOver),
        PracticeTab(PracticeTabKey.INTERVIEW, t(PracticeTabKey.INTERVIEW.translationKey), Icons.Default.Work),
        PracticeTab(PracticeTabKey.EXAM, t(PracticeTabKey.EXAM.translationKey), Icons.Default.FactCheck),
        PracticeTab(PracticeTabKey.LISTENING, t(PracticeTabKey.LISTENING.translationKey), Icons.Default.Headphones),
        PracticeTab(PracticeTabKey.CUSTOM, t(PracticeTabKey.CUSTOM.translationKey), Icons.Default.Edit)
    )

    // §4: only Exam & Custom are gated ("unlocks after your first scored encounter" — spelled
    // out on the tile itself); every other mode is reachable from session 1. Previously this
    // list shrank to just Encounter+Survival before any scored session, hiding 6 modes the
    // spec expects to be immediately reachable — the mode grid's per-tile lock treatment below
    // replaces that blanket filter.
    val tabs = if (everydayOnly) {
        allTabs.filter {
            it.key in setOf(
                PracticeTabKey.SURVIVAL,
                PracticeTabKey.LOUNGE,
                PracticeTabKey.LISTENING,
                // The drill is bank-agnostic (see PhraseSource) and its everyday bank is the only
                // place a learner can practise a phrase they were offered without also having to
                // hold a live conversation, so the everyday experience keeps it.
                PracticeTabKey.SAYIT,
            )
        }
    } else {
        allTabs
    }
    val lockedModeKeys = if (isUnlocked) emptySet() else setOf(PracticeTabKey.EXAM, PracticeTabKey.CUSTOM)
    val selectedTab = tabs.getOrNull(selectedTabIndex) ?: tabs.first()

    LaunchedEffect(selectedTab.key) {
        if (selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION) {
            com.example.medvoicetrainer.analysis.Telemetry.track(
                "team_communication_exposed",
                mapOf("surface" to "practice", "task" to "hub")
            )
        }
    }

    LaunchedEffect(requestedMode, tabs) {
        val requestedKey = when (requestedMode) {
            "survival", "survival_beta" -> PracticeTabKey.SURVIVAL
            "listening" -> PracticeTabKey.LISTENING
            "lounge" -> PracticeTabKey.LOUNGE
            "sayit", "sayit_everyday", "sayit_session" -> PracticeTabKey.SAYIT
            else -> null
        }
        val requestedIndex = requestedKey?.let { key -> tabs.indexOfFirst { it.key == key } } ?: -1
        if (requestedIndex >= 0) {
            // Latched here rather than read at render time: the request is consumed on the next
            // line, and the drill only reads its starting bank on first composition.
            sayItStartsEveryday = requestedMode in setOf("sayit_everyday", "sayit_session")
            sayItSessionPhrases = if (requestedMode == "sayit_session") requestedPhraseDrill else emptyList()
            selectedTabIndex = requestedIndex
            showModeGrid = false
            onRequestedModeConsumed()
        }
    }

    var cases by remember { mutableStateOf<List<PracticeCase>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    // The encounter catalog contains 1,000+ cases. Building and normalizing its search index in
    // composition blocked the first Patient Encounter frame even when the learner never searched.
    // Create it lazily, only after the first non-blank query, and do both indexing and scoring away
    // from the UI thread. The short debounce also prevents a full scan for every intermediate key.
    var encounterSearchIndex by remember(cases) { mutableStateOf<EncounterCaseSearchIndex?>(null) }
    var encounterSearchResult by remember(cases) { mutableStateOf(EncounterSearchResult()) }

    LaunchedEffect(cases, selectedTab.key, encounterSearchQuery.isNotBlank()) {
        if (
            selectedTab.key == PracticeTabKey.ENCOUNTER &&
            encounterSearchQuery.isNotBlank() &&
            encounterSearchIndex == null
        ) {
            encounterSearchIndex = withContext(Dispatchers.Default) {
                EncounterCaseSearchIndex(
                    cases.associate { case ->
                        case.id to EncounterSearchDocument(
                            title = case.title,
                            description = case.description,
                            diagnosis = case.diagnosis,
                            keywords = case.searchText,
                            specialty = ENCOUNTER_SYSTEM_LABELS[case.system] ?: case.system,
                        )
                    }
                )
            }
        }
    }

    LaunchedEffect(encounterSearchIndex, encounterSearchQuery, selectedTab.key) {
        if (selectedTab.key != PracticeTabKey.ENCOUNTER || encounterSearchQuery.isBlank()) {
            encounterSearchResult = EncounterSearchResult()
            return@LaunchedEffect
        }
        val index = encounterSearchIndex ?: return@LaunchedEffect
        val query = encounterSearchQuery
        delay(120)
        val scores = withContext(Dispatchers.Default) { index.scores(query) }
        encounterSearchResult = EncounterSearchResult(query, scores)
    }

    // Exam tab state — mirrors app/ui/exam_tab.py's kind + scenario dropdowns over
    // the already-ported ExamMode.EXAM_SCENARIOS/KIND_LABELS.
    val examKinds = com.example.medvoicetrainer.analysis.ExamMode.KIND_LABELS
    var examKindKey by remember { mutableStateOf(examKinds.keys.first()) }
    var examKindMenuExpanded by remember { mutableStateOf(false) }
    val examScenarios = remember(examKindKey) {
        com.example.medvoicetrainer.analysis.ExamMode.listExamScenarios(examKindKey)
    }
    var examScenarioIndex by remember(examKindKey) { mutableIntStateOf(0) }
    var examScenarioMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isUnlocked) {
        if (selectedTabIndex >= tabs.size) {
            selectedTabIndex = 0
        }
    }

    LaunchedEffect(selectedTab.key) {
        if (selectedTab.key != PracticeTabKey.LISTENING) listeningLabCase = null
        if (selectedTab.key != PracticeTabKey.ENCOUNTER) encounterResultsBriefingCase = null
        if (selectedTab.key != PracticeTabKey.FOLLOW_UP) followUpBriefingCase = null
        if (selectedTab.key != PracticeTabKey.TEAM_COMMUNICATION) teamBriefingCase = null
        if (selectedTab.key != PracticeTabKey.ENCOUNTER) {
            selectedEncounterSystem = null
            selectedEncounterCategory = null
            selectedEncounterDifficulty = EncounterDifficulty.ALL
            selectedEncounterCaseFilter = EncounterCaseFilter.ALL
            encounterSearchQuery = ""
        }
        isLoading = true
        val loadedCases = withContext(Dispatchers.IO) {
            val loadedCases = mutableListOf<PracticeCase>()
            val category = selectedTab.key
            val catalog = if (category in setOf(
                    PracticeTabKey.ENCOUNTER, PracticeTabKey.FOLLOW_UP,
                    PracticeTabKey.TEAM_COMMUNICATION, PracticeTabKey.DRILLS,
                    PracticeTabKey.FOUNDATIONS, PracticeTabKey.TEACHBACK,
                )
            ) BundledCaseCatalog.load(viewModel) else emptyList()

            when (category) {
                PracticeTabKey.ENCOUNTER -> {
                    catalog.filter { it.optString("group") in ENCOUNTER_SYSTEM_LABELS.keys }.forEach { json ->
                        val path = json.optString("asset_path")
                        loadedCases.add(PracticeCase(
                            id = json.optString("id", path.substringAfterLast('/').removeSuffix(".json")),
                            title = json.optString("patient_name", path.substringAfterLast('/')) + " - " + json.optString("chief_complaint", "Encounter"),
                            description = json.optString("hpi_details", "No description available.").take(100) + "...",
                            jsonContent = "", category = "encounter", system = json.optString("group"),
                            difficulty = json.optString("difficulty").lowercase(),
                            diagnosis = json.optString("diagnosis"),
                            searchText = json.optString("search_text"),
                            assetPath = path,
                        ))
                    }
                }
                PracticeTabKey.FOLLOW_UP -> {
                    catalog.filter { it.optString("group") == "follow_up" }.forEach { json ->
                        val path = json.optString("asset_path")
                        loadedCases.add(PracticeCase(
                            id = json.optString("id", path.substringAfterLast('/').removeSuffix(".json")),
                            title = json.optString("visit_title", json.optString("patient_name", "Follow-up")),
                            description = "${json.optString("patient_name")} · ${json.optString("follow_up_reason")}",
                            jsonContent = "", category = "follow_up", system = json.optString("follow_up_type"),
                            assetPath = path,
                        ))
                    }
                }
                PracticeTabKey.DRILLS -> {
                    catalog.filter {
                        it.optString("group") in setOf("drills", "chart_drills") &&
                            it.optString("id") !in TEAM_COMMUNICATION_DRILL_IDS
                    }.forEach { json ->
                        val path = json.optString("asset_path")
                        val fallbackId = path.substringAfterLast('/').removeSuffix(".json")
                        val (title, description) = titleAndDescriptionFrom(json, fallbackId, "Drill scenario.")
                        loadedCases.add(PracticeCase(
                            id = json.optString("id", path.substringAfterLast('/')), title = title,
                            description = description, jsonContent = "", category = "drill", assetPath = path,
                        ))
                    }
                }
                PracticeTabKey.TEAM_COMMUNICATION -> {
                    catalog.filter {
                        it.optString("group") == "team_communication" ||
                            it.optString("id") in TEAM_COMMUNICATION_DRILL_IDS
                    }.forEach { json ->
                        val path = json.optString("asset_path")
                        val fallbackId = path.substringAfterLast('/').removeSuffix(".json")
                        val (fallbackTitle, fallbackDescription) = titleAndDescriptionFrom(json, fallbackId, "Team communication scenario.")
                        val title = json.optString("team_title").ifBlank { fallbackTitle }
                        val description = json.optString("team_description").ifBlank { fallbackDescription }
                        val task = json.optString("communication_task").ifBlank {
                            when {
                                fallbackId.contains("transfer") -> "Transfer"
                                fallbackId.contains("consult") || fallbackId.contains("referral") -> "Consult / referral"
                                fallbackId.contains("order") || fallbackId.contains("readback") -> "Read-back"
                                else -> "Handover / escalation"
                            }
                        }
                        val catalogType = json.optString("team_case_type")
                        loadedCases.add(
                            PracticeCase(
                                id = json.optString("id", fallbackId),
                                title = title,
                                description = description,
                                jsonContent = "",
                                category = "team_communication",
                                system = if (catalogType == "skill_drill") "Skill drill" else task,
                                assetPath = path,
                            )
                        )
                    }
                }
                PracticeTabKey.FOUNDATIONS -> {
                    catalog.filter { it.optString("group") == "foundations" }.forEach { json ->
                        val path = json.optString("asset_path")
                        val fallbackId = path.substringAfterLast('/').removeSuffix(".json")
                        val name = json.optString("patient_name", "").ifBlank { json.optString("scenario_name", "") }.ifBlank { fallbackId }
                        val complaint = json.optString("chief_complaint", "")
                        loadedCases.add(PracticeCase(
                            id = json.optString("id", path.substringAfterLast('/')),
                            title = if (complaint.isNotBlank()) "$name - $complaint" else name,
                            description = json.optString("hpi_details", "").ifBlank { json.optString("setup", "") }.ifBlank { "Foundations practice." },
                            jsonContent = "", category = "encounter", assetPath = path,
                        ))
                    }
                }
                PracticeTabKey.TEACHBACK -> {
                    catalog.filter { it.optString("group") == "teachback" }.forEach { json ->
                        val path = json.optString("asset_path")
                        loadedCases.add(PracticeCase(
                            id = json.optString("id", path.substringAfterLast('/')),
                            title = json.optString("scenario_name", path.substringAfterLast('/').removeSuffix(".json")),
                            description = json.optString("setup", "Teachback scenario."),
                            jsonContent = "", category = "teachback", assetPath = path,
                        ))
                    }
                }
                PracticeTabKey.INTERVIEW -> {
                    viewModel.listAssets("interview_banks").forEach { file ->
                        if (file.endsWith(".json")) {
                            val jsonStr = viewModel.loadAsset("interview_banks/${file}")
                            flattenInterviewBank(jsonStr, file.removeSuffix(".json")).forEach { scenario ->
                                loadedCases.add(
                                    PracticeCase(
                                        id = scenario.id,
                                        title = scenario.title,
                                        description = scenario.description,
                                        jsonContent = scenario.scenarioJson,
                                        category = "interview",
                                    )
                                )
                            }
                        }
                    }
                }
                PracticeTabKey.SURVIVAL -> {
                    // Keep only the five short strings the picker renders. The complete 6.5 MB /
                    // 2,660-situation bank is no longer deep-parsed here; the selected row alone is
                    // loaded when Start is pressed.
                    val catalog = com.example.medvoicetrainer.analysis.SurvivalComposer.catalog(appContext)
                    withContext(Dispatchers.Main) {
                        survivalCatalog = catalog
                    }
                }
                PracticeTabKey.LISTENING -> {
                    val jsonStr = viewModel.loadAsset("listening_drills.json")
                    val drillJsons = mutableListOf<JSONObject>()
                    try {
                        val json = JSONObject(jsonStr)
                        val drills = json.optJSONArray("drills")
                        if (drills != null) {
                            for (i in 0 until drills.length()) {
                                val drill = drills.getJSONObject(i)
                                drillJsons.add(drill)
                                loadedCases.add(PracticeCase(
                                    id = drill.optString("id", "drill_$i"),
                                    title = "${drill.optString("category", "Listening Drill")} · Level ${drill.optInt("difficulty", 1)}",
                                    description = drill.optString("context", "Listen and extract exact details."),
                                    jsonContent = drill.toString(),
                                    category = "listening"
                                ))
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
                    withContext(Dispatchers.Main) { listeningDrillJsons = drillJsons }
                    if (drillJsons.isNotEmpty()) {
                        // Mirrors listening_lab_window.py's "🎲 New adaptive drill" button —
                        // ListeningDrillEngine.chooseAdaptiveDrill picks the drill (due-date and
                        // weak-skill weighted) instead of the learner always choosing manually.
                        loadedCases.add(
                            0,
                            PracticeCase(
                                id = "adaptive_pick",
                                title = "🎲 " + t("Adaptive pick (recommended)"),
                                description = "Chooses your next drill based on due dates and your weakest skills.",
                                jsonContent = "",
                                category = "listening"
                            )
                        )
                    }
                }
                PracticeTabKey.SAYIT,
                PracticeTabKey.LOUNGE,
                PracticeTabKey.EXAM,
                PracticeTabKey.CUSTOM -> Unit
            }
            loadedCases.toList()
        }
        cases = loadedCases
        isLoading = false
    }

    lockedModeExplainer?.let { key ->
        val label = allTabs.first { it.key == key }.label
        AlertDialog(
            onDismissRequest = { lockedModeExplainer = null },
            confirmButton = { TextButton(onClick = { lockedModeExplainer = null }) { Text(t("OK")) } },
            title = { Text("🔒 $label") },
            text = { Text(t("Unlocks after your first scored encounter.")) }
        )
    }

    if (showModeGrid) {
        PracticeModeGrid(
            tabs = tabs,
            lockedKeys = lockedModeKeys,
            estimatedSessionCostUsd = estimatedSessionCost,
            everydayOnly = everydayOnly,
            onSelectMode = { key ->
                if (key in lockedModeKeys) {
                    lockedModeExplainer = key
                } else {
                    selectedTabIndex = tabs.indexOfFirst { it.key == key }.coerceAtLeast(0)
                    showModeGrid = false
                }
            }
        )
        return
    }

    // The dropdowns contain only the three broad departments and their small specialty lists.
    // Case filtering stays over compact catalog rows and is memoized, so opening a menu never
    // materializes any of the 1,000+ full case JSON documents or repeatedly rebuilds the result.
    val encounterSystems = remember(cases, selectedTab.key) {
        if (selectedTab.key == PracticeTabKey.ENCOUNTER) {
            cases.mapNotNull { it.system.takeIf(String::isNotBlank) }.toSet()
        } else {
            emptySet()
        }
    }
    val encounterCategories = remember(encounterSystems) {
        ENCOUNTER_SYSTEM_CATEGORIES.filter { (_, systems) -> systems.any { it in encounterSystems } }
    }
    val specialtyFilteredCases = remember(
        cases,
        selectedTab.key,
        selectedEncounterCategory,
        selectedEncounterSystem,
        selectedCommunicationTask,
    ) {
        when {
            selectedTab.key == PracticeTabKey.ENCOUNTER && selectedEncounterSystem != null ->
                cases.filter { it.system == selectedEncounterSystem }
            selectedTab.key == PracticeTabKey.ENCOUNTER && selectedEncounterCategory != null -> {
                val allowed = ENCOUNTER_SYSTEM_CATEGORIES
                    .firstOrNull { it.first == selectedEncounterCategory }
                    ?.second
                    .orEmpty()
                cases.filter { it.system in allowed }
            }
            selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION && selectedCommunicationTask != null ->
                cases.filter { it.system == selectedCommunicationTask }
            else -> cases
        }
    }
    val difficultyFilteredCases = remember(
        specialtyFilteredCases,
        selectedTab.key,
        selectedEncounterDifficulty,
    ) {
        val requiredDifficulty = selectedEncounterDifficulty.catalogValue
        if (selectedTab.key == PracticeTabKey.ENCOUNTER && requiredDifficulty != null) {
            specialtyFilteredCases.filter { it.difficulty == requiredDifficulty }
        } else {
            specialtyFilteredCases
        }
    }
    val tagFilteredCases = remember(
        difficultyFilteredCases,
        selectedTab.key,
        selectedEncounterCaseFilter,
        encounterCaseTags,
    ) {
        val requiredTag = selectedEncounterCaseFilter.tag
        if (selectedTab.key == PracticeTabKey.ENCOUNTER && requiredTag != null) {
            difficultyFilteredCases.filter { requiredTag in encounterCaseTags[it.id].orEmpty() }
        } else {
            difficultyFilteredCases
        }
    }
    val visibleCases = remember(
        tagFilteredCases,
        selectedTab.key,
        encounterSearchQuery,
        encounterSearchResult,
    ) {
        if (
            selectedTab.key == PracticeTabKey.ENCOUNTER &&
            encounterSearchQuery.isNotBlank() &&
            encounterSearchResult.query == encounterSearchQuery
        ) {
            tagFilteredCases.mapNotNull { case ->
                encounterSearchResult.scores[case.id]?.let { score -> case to score }
            }.sortedByDescending { it.second }.map { it.first }
        } else {
            tagFilteredCases
        }
    }

    // Once a specific listening drill is open, the mode header/tab strip above only eats screen
    // space and duplicates navigation the drill's own back arrow already provides — so it's
    // skipped here to keep the drill immersive (full-height, no chrome above it).
    val listeningDrillOpen = selectedTab.key == PracticeTabKey.LISTENING && listeningLabCase != null
    val sayItDrillOpen = selectedTab.key == PracticeTabKey.SAYIT && sayItTopicOpen
    val encounterResultsBriefingOpen =
        selectedTab.key == PracticeTabKey.ENCOUNTER && encounterResultsBriefingCase != null
    val followUpBriefingOpen = selectedTab.key == PracticeTabKey.FOLLOW_UP && followUpBriefingCase != null
    val teamBriefingOpen = selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION && teamBriefingCase != null

    Column(modifier = Modifier.fillMaxSize()) {
        if (!listeningDrillOpen && !sayItDrillOpen && !encounterResultsBriefingOpen &&
            !followUpBriefingOpen && !teamBriefingOpen
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                IconButton(onClick = { showModeGrid = true }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = t("Back to modes"))
                }
                Text(selectedTab.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(tab.label, fontWeight = FontWeight.Bold) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) }
                    )
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (selectedTab.key == PracticeTabKey.SURVIVAL) {
            val catalog = survivalCatalog
            SurvivalPracticeSelector(
                frames = if (everydayOnly) {
                    catalog?.frames?.filterNot { it.category == "Hospital Hallway" }.orEmpty()
                } else {
                    catalog?.frames.orEmpty()
                },
                personas = catalog?.personas.orEmpty(),
                roles = catalog?.roles.orEmpty(),
                goals = catalog?.goals.orEmpty(),
                twists = catalog?.twists.orEmpty(),
                userStates = catalog?.userStates.orEmpty(),
                rapidFireQuestions = catalog?.rapidFire.orEmpty(),
                appContext = appContext,
                translate = t,
                // The Survival tab stamps `advanced_beta` (plus the category's transition menu)
                // onto every composed case.
                advancedBeta = true,
                sceneTransitionMenuFor = { category ->
                    com.example.medvoicetrainer.analysis.SceneTransitionCatalog
                        .load(appContext)
                        .promptBlockFor(category)
                },
                // Same predicate the session start gates on, so this notice and what actually
                // happens can never disagree — including the case where "openai" is selected but
                // has no key, which falls through to the mock client and does support transitions.
                sceneTransitionsUnsupported = !com.example.medvoicetrainer.voice.supportsSceneTransitions(
                    provider = voiceBackend,
                    isMock = isVoiceModeMock,
                    apiKey = viewModel.getApiKeyForBackend(voiceBackend),
                ),
                onStart = { id, title, caseJson, playbackSpeed ->
                    viewModel.setPlaybackSpeed(playbackSpeed)
                    onStartSurvival(id, title, caseJson)
                },
                phrasePreviewForCase = viewModel::everydayPhrasePreview,
            )
        } else if (selectedTab.key == PracticeTabKey.LISTENING && listeningLabCase != null) {
            val drill = listeningLabCase!!
            key(drill.id) {
                // ListeningLabScreen only consumes the drill JSON. Supplying a local state opens
                // its TTS/recall workflow without creating a live voice session, DB session draft,
                // microphone recorder, or realtime backend connection. Its own back arrow (onBack)
                // is the single way out of the drill now — no separate "back to drills" link above it.
                ListeningLabScreen(
                    viewModel = viewModel,
                    activeSession = ActiveSessionState(
                        isActive = false,
                        mode = "listening",
                        caseId = drill.id,
                        caseName = drill.title,
                        caseJson = drill.jsonContent,
                    ),
                    onBack = { listeningLabCase = null },
                )
            }
        } else if (selectedTab.key == PracticeTabKey.SAYIT) {
            // Listen-and-repeat drill: TTS models each high-frequency clinical chunk, the learner
            // records and gets an intelligibility check. A pure production drill, no live session.
            // Opened from the Everyday home it lands on the everyday bank; from the clinical mode
            // grid it lands where it always has. Either way the learner can switch banks in place.
            ListenAndRepeatScreen(
                viewModel = viewModel,
                initialEveryday = sayItStartsEveryday,
                initialSessionPhrases = sayItSessionPhrases,
                onTopicOpenChanged = { sayItTopicOpen = it },
            )
        } else if (selectedTab.key == PracticeTabKey.CUSTOM) {
            // Ported from app/ui/custom_tab.py: named scenario + free-form persona text, replacing
            // the previous raw-JSON-paste-only UI. PromptBuilder.buildCustomPrompt's result is
            // stored as persona_override, which buildPatientPrompt already checks first (see
            // PromptBuilder.kt) — same mechanism Teachback's persona cases use.
            var customName by remember { mutableStateOf("") }
            var customPersona by remember { mutableStateOf("") }
            var customCriteria by remember { mutableStateOf("") }
            var customDomain by remember { mutableStateOf("clinical") }
            var evalTemplates by remember { mutableStateOf<List<String>>(emptyList()) }
            var selectedEvalTemplate by remember { mutableStateOf("diagnostic_clinical_english") }
            var evalMenuExpanded by remember { mutableStateOf(false) }
            var savedMenuExpanded by remember { mutableStateOf(false) }
            var savedProfiles by remember { mutableStateOf(viewModel.getSavedCustomProfiles()) }
            LaunchedEffect(Unit) {
                evalTemplates = withContext(Dispatchers.IO) {
                    viewModel.listAssets("eval")
                        .filter { it.endsWith(".json") }
                        .map { it.removeSuffix(".json") }
                        .sorted()
                }
                if (selectedEvalTemplate !in evalTemplates && evalTemplates.isNotEmpty()) {
                    selectedEvalTemplate = evalTemplates.first()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = t("Create Custom Case"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("Scenario name")) }
                )
                OutlinedTextField(
                    value = customPersona,
                    onValueChange = { customPersona = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    label = { Text(t("Persona / context")) },
                    placeholder = { Text(t("Describe who the AI should play and the situation, e.g. \"You are a skeptical hospital administrator reviewing a budget request...\"")) }
                )
                Text(t("Evaluation domain"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("clinical" to "Clinical", "everyday" to "Everyday").forEach { (key, label) ->
                        FilterChip(
                            selected = customDomain == key,
                            onClick = {
                                customDomain = key
                                selectedEvalTemplate = if (key == "everyday") {
                                    "survival"
                                } else {
                                    "diagnostic_clinical_english"
                                }
                            },
                            label = { Text(t(label)) }
                        )
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = evalMenuExpanded,
                    onExpandedChange = { evalMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedEvalTemplate,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        label = { Text(t("Eval template")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(evalMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = evalMenuExpanded,
                        onDismissRequest = { evalMenuExpanded = false }
                    ) {
                        evalTemplates.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template) },
                                onClick = {
                                    selectedEvalTemplate = template
                                    customDomain = if (template in setOf("survival", "lounge")) {
                                        "everyday"
                                    } else {
                                        "clinical"
                                    }
                                    evalMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = customCriteria,
                    onValueChange = { customCriteria = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    label = { Text(t("Custom eval criteria")) },
                    placeholder = { Text(t("What should the evaluator pay special attention to?")) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = customName.isNotBlank() && customPersona.isNotBlank(),
                        onClick = {
                            viewModel.saveCustomProfile(
                                CustomPracticeProfile(
                                    name = customName.trim(),
                                    persona = customPersona.trim(),
                                    evalTemplate = selectedEvalTemplate,
                                    analysisDomain = customDomain,
                                    customCriteria = customCriteria.trim()
                                )
                            )
                            savedProfiles = viewModel.getSavedCustomProfiles()
                        }
                    ) { Text(t("Save scenario")) }
                    Box {
                        OutlinedButton(
                            enabled = savedProfiles.isNotEmpty(),
                            onClick = { savedMenuExpanded = true }
                        ) { Text(t("Load saved scenario")) }
                        DropdownMenu(
                            expanded = savedMenuExpanded,
                            onDismissRequest = { savedMenuExpanded = false }
                        ) {
                            savedProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        customName = profile.name
                                        customPersona = profile.persona
                                        selectedEvalTemplate = profile.evalTemplate
                                        customDomain = profile.analysisDomain
                                        customCriteria = profile.customCriteria
                                        savedMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        val name = customName.trim().ifEmpty { "Custom Case" }
                        val caseJson = JSONObject().apply {
                            put("id", "custom_case")
                            put("case_name", name)
                            put("analysis_domain", customDomain)
                            put("eval_template", selectedEvalTemplate)
                            put("custom_criteria_prose", customCriteria.trim())
                            put("persona_override", com.example.medvoicetrainer.analysis.PromptBuilder.buildCustomPrompt(customPersona.trim()))
                        }
                        onStartCase("custom_case", name, caseJson.toString())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("Start Custom Case"))
                }
            }
        } else if (selectedTab.key == PracticeTabKey.LOUNGE) {
            // Ported from app/ui/lounge_tab.py: scenario dropdown + context/topic box (with a
            // YouTube-transcript importer for the article-discussion scenario), feeding
            // PromptBuilder.buildLoungePrompt's {context_text} substitution — previously Kotlin's
            // Lounge tab was just a static case-picker list with no way to supply live context at
            // all (see MIGRATION_MASTER.md's lounge_tab.py row).
            data class LoungeScenario(val name: String, val description: String, val scenarioType: String, val raw: JSONObject)
            var loungeScenarios by remember { mutableStateOf<List<LoungeScenario>>(emptyList()) }
            var loungeIndex by remember { mutableIntStateOf(0) }
            var loungeMenuExpanded by remember { mutableStateOf(false) }
            var loungeContext by remember { mutableStateOf("") }
            var isFetchingTranscript by remember { mutableStateOf(false) }
            var fetchError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                loungeScenarios = withContext(Dispatchers.IO) {
                    viewModel.listAssets("cases/lounge").filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
                        try {
                            val json = JSONObject(viewModel.loadAsset("cases/lounge/$file"))
                            LoungeScenario(
                                name = json.optString("name", file.removeSuffix(".json")),
                                description = json.optString("description", ""),
                                scenarioType = json.optString("scenario_type", ""),
                                raw = json
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            null
                        }
                    }
                }
            }

            val selectedScenario = loungeScenarios.getOrNull(loungeIndex)
            val contextPlaceholder = when (selectedScenario?.scenarioType) {
                "article" -> t("Paste a YouTube link or article text here...")
                "debate" -> t("Enter a controversial topic (e.g., 'Universal healthcare should be mandatory').")
                "casual" -> t("I just went to a great Italian restaurant this weekend.")
                "conflict" -> t("You are an ICU nurse who is angry because I (the resident) took too long to put in the orders.")
                else -> ""
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = t("Free English Lounge"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                ExposedDropdownMenuBox(
                    expanded = loungeMenuExpanded,
                    onExpandedChange = { loungeMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedScenario?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("Scenario")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = loungeMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = loungeMenuExpanded,
                        onDismissRequest = { loungeMenuExpanded = false }
                    ) {
                        loungeScenarios.forEachIndexed { idx, scenario ->
                            DropdownMenuItem(
                                text = { Text(scenario.name) },
                                onClick = {
                                    loungeIndex = idx
                                    loungeMenuExpanded = false
                                    loungeContext = ""
                                    fetchError = null
                                }
                            )
                        }
                    }
                }
                if (!selectedScenario?.description.isNullOrBlank()) {
                    Text(
                        text = selectedScenario!!.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedScenario?.scenarioType == "article") {
                    OutlinedButton(
                        onClick = {
                            val videoId = com.example.medvoicetrainer.utils.YoutubeImporter.extractVideoId(loungeContext.trim())
                            if (videoId == null) {
                                fetchError = t("Please paste a valid YouTube URL in the text box first.")
                            } else {
                                fetchError = null
                                isFetchingTranscript = true
                                coroutineScope.launch {
                                    try {
                                        val transcript = com.example.medvoicetrainer.utils.YoutubeImporter.fetchTranscript(videoId, "auto")
                                        loungeContext = transcript
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                        fetchError = t("Could not fetch transcript:") + " " +
                                            com.example.medvoicetrainer.api.ApiError.userMessage(e)
                                    } finally {
                                        isFetchingTranscript = false
                                    }
                                }
                            }
                        },
                        enabled = !isFetchingTranscript
                    ) {
                        Text(if (isFetchingTranscript) t("Fetching...") else t("Fetch YT Transcript"))
                    }
                }
                OutlinedTextField(
                    value = loungeContext,
                    onValueChange = { loungeContext = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    label = { Text(t("Context / Topic")) },
                    placeholder = { Text(contextPlaceholder) }
                )
                fetchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                // Lounge picks its scenario up front, so unlike Survival this card can offer the
                // scenario's own lines where it authors any — recomputed as the dropdown changes.
                val loungePreview = remember(selectedScenario, phrasebookEnabled) {
                    viewModel.everydayPhrasePreview(selectedScenario?.raw?.toString().orEmpty())
                }
                var loungePreviewOpened by remember(
                    selectedScenario?.raw?.optString("id", selectedScenario.name)
                ) { mutableStateOf(false) }
                val loungeTts = if (loungePreview.isEmpty()) null else rememberEnglishTts()
                ScenePhrasesCard(
                    phrases = loungePreview,
                    ttsReady = loungeTts?.ready == true,
                    onListen = { loungeTts?.speak(it) },
                    onOpened = { loungePreviewOpened = true },
                )
                Button(
                    onClick = {
                        selectedScenario?.let { scenario ->
                            val map = mutableMapOf<String, Any?>()
                            scenario.raw.keys().forEach { key -> map[key] = scenario.raw.opt(key) }
                            val prompt = com.example.medvoicetrainer.analysis.PromptBuilder.buildLoungePrompt(map, loungeContext.trim())
                            val caseJson = JSONObject(scenario.raw.toString()).apply {
                                put("persona_override", prompt)
                                put("case_name", scenario.name)
                            }
                            val concreteJson = caseJson.toString().let { raw ->
                                if (loungePreviewOpened) {
                                    com.example.medvoicetrainer.analysis.EverydayPhrasebook.markPreviewed(raw)
                                } else raw
                            }
                            onStartCase(caseJson.optString("id", scenario.name), scenario.name, concreteJson)
                        }
                    },
                    enabled = selectedScenario != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("Start Lounge Session"))
                }
            }
        } else if (selectedTab.key == PracticeTabKey.EXAM) {
            // Ported from app/ui/exam_tab.py: pick an exam kind, then a scenario within it;
            // ExamMode.buildExamPrompt (already wired into PromptBuilder.buildSystemPrompt's
            // "exam" branch) supplies the in-session persona/rules.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = t("Exam Practice"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = t("Score is AI-estimated practice feedback, not an official result."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = examKindMenuExpanded,
                    onExpandedChange = { examKindMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = examKinds[examKindKey] ?: examKindKey,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("Exam type")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = examKindMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = examKindMenuExpanded,
                        onDismissRequest = { examKindMenuExpanded = false }
                    ) {
                        examKinds.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    examKindKey = key
                                    examKindMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                val selectedScenario = examScenarios.getOrNull(examScenarioIndex)

                ExposedDropdownMenuBox(
                    expanded = examScenarioMenuExpanded,
                    onExpandedChange = { examScenarioMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedScenario?.get("title") as? String ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("Scenario")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = examScenarioMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = examScenarioMenuExpanded,
                        onDismissRequest = { examScenarioMenuExpanded = false }
                    ) {
                        examScenarios.forEachIndexed { idx, scenario ->
                            DropdownMenuItem(
                                text = { Text("${scenario["id"]} - ${scenario["title"]}") },
                                onClick = {
                                    examScenarioIndex = idx
                                    examScenarioMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedScenario != null) {
                    Text(
                        text = "${selectedScenario["learning_objective"] ?: ""}  |  " +
                            t("Station") + ": ${selectedScenario["station_minutes"] ?: "?"} " + t("min"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        selectedScenario?.let { scenario ->
                            val json = JSONObject()
                            scenario.forEach { (k, v) -> json.put(k, v) }
                            onStartExam(
                                scenario["id"] as? String ?: "exam_scenario",
                                scenario["title"] as? String ?: t("Exam"),
                                json.toString()
                            )
                        }
                    },
                    enabled = selectedScenario != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("Start Exam"))
                }
            }
        } else if (selectedTab.key == PracticeTabKey.TEACHBACK) {
            // Ported from app/ui/teachback_tab.py: pick who you're teaching, paste the lecture
            // material, and Teachback.buildTeachbackCase substitutes it into the persona's
            // {material} placeholder — the raw cases/teachback/*.json files are persona
            // templates, not standalone cases, so they can't be started directly.
            val context = androidx.compose.ui.platform.LocalContext.current
            var personas by remember { mutableStateOf<List<com.example.medvoicetrainer.analysis.TeachbackPersona>>(emptyList()) }
            var personaIndex by remember { mutableIntStateOf(0) }
            var personaMenuExpanded by remember { mutableStateOf(false) }
            var material by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                personas = withContext(Dispatchers.IO) {
                    com.example.medvoicetrainer.analysis.Teachback.loadPersonas(context)
                }
            }

            val selectedPersona = personas.getOrNull(personaIndex)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = t("Lecture Teach-back"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = t("Paste your lecture notes, pick who you're teaching, then explain the material out loud."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = personaMenuExpanded,
                    onExpandedChange = { personaMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedPersona?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("Who are you teaching?")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = personaMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = personaMenuExpanded,
                        onDismissRequest = { personaMenuExpanded = false }
                    ) {
                        personas.forEachIndexed { idx, persona ->
                            DropdownMenuItem(
                                text = { Text(persona.name) },
                                onClick = {
                                    personaIndex = idx
                                    personaMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                if (selectedPersona != null) {
                    Text(
                        text = selectedPersona.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = material,
                    onValueChange = { material = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    label = { Text(t("Lecture material")) },
                    placeholder = { Text(t("Paste your lecture notes or outline here...")) }
                )

                if (material.length > com.example.medvoicetrainer.analysis.Teachback.CONDENSE_RECOMMENDED_OVER) {
                    Text(
                        text = t("This is long — consider condensing it first for a tighter session."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = {
                        selectedPersona?.let { persona ->
                            val case = com.example.medvoicetrainer.analysis.Teachback.buildTeachbackCase(persona, material)
                            onStartTeachback(persona.id, persona.name, case.toString())
                        }
                    },
                    enabled = selectedPersona != null && material.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("Start Teach-back"))
                }
            }
        } else if (selectedTab.key == PracticeTabKey.ENCOUNTER && encounterResultsBriefingCase != null) {
            val selected = encounterResultsBriefingCase!!
            val availableResults = remember(selected.jsonContent) {
                com.example.medvoicetrainer.analysis.InvestigationResults
                    .parseAvailableResults(selected.jsonContent)
            }
            PreEncounterResultsScreen(
                caseName = selected.title,
                results = availableResults,
                onBack = { encounterResultsBriefingCase = null },
                onStart = {
                    encounterResultsBriefingCase = null
                    onStartCase(
                        selected.id,
                        selected.title,
                        withEncounterOptions(
                            selected.jsonContent,
                            encounterAccentKey,
                            encounterStyleKey,
                        ),
                    )
                },
            )
        } else if (selectedTab.key == PracticeTabKey.FOLLOW_UP && followUpBriefingCase != null) {
            val selected = followUpBriefingCase!!
            FollowUpBriefingScreen(
                case = selected,
                canStart = voiceBackend != "demo",
                onBack = { followUpBriefingCase = null },
                onStart = { focusedJson -> onStartFollowUp(selected.id, selected.title, focusedJson) },
            )
        } else if (selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION && teamBriefingCase != null) {
            val selected = teamBriefingCase!!
            TeamCommunicationBriefSheet(
                caseJson = selected.jsonContent,
                onDismiss = { teamBriefingCase = null },
                onStart = {
                    com.example.medvoicetrainer.analysis.Telemetry.track(
                        "team_communication_started",
                        mapOf("surface" to "practice", "task" to selected.system)
                    )
                    onStartCase(selected.id, selected.title, selected.jsonContent)
                },
            )
        } else if (voiceBackend == "demo" && selectedTab.key == PracticeTabKey.ENCOUNTER) {
            // Ported from app/ui/session_base.py's keyless-demo entry point: instead of browsing
            // the full case library (which needs a real voice backend), a "demo" voiceBackend
            // user only ever picks among the 3 scripted patients via DemoIntroDialog.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    t("demo.sample.practice_entry"),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = { showDemoIntro = true }) {
                    Text(t("demo.sample.start"))
                }
            }
            if (showDemoIntro) {
                DemoIntroDialog(
                    completedIds = viewModel.getCompletedDemoCaseIds().toSet(),
                    onCancel = { showDemoIntro = false },
                    onStart = { caseId ->
                        showDemoIntro = false
                        viewModel.startDemoSession(caseId)
                    }
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (selectedTab.key == PracticeTabKey.ENCOUNTER) {
                    item {
                        EncounterBrowserControls(
                            query = encounterSearchQuery,
                            onQueryChange = { encounterSearchQuery = it },
                            searchBusy = encounterSearchQuery.isNotBlank() &&
                                encounterSearchResult.query != encounterSearchQuery,
                            categories = encounterCategories,
                            availableSystems = encounterSystems,
                            selectedCategory = selectedEncounterCategory,
                            selectedSystem = selectedEncounterSystem,
                            selectedDifficulty = selectedEncounterDifficulty,
                            selectedSavedFilter = selectedEncounterCaseFilter,
                            selectedAccentKey = encounterAccentKey,
                            selectedStyleKey = encounterStyleKey,
                            visibleCount = visibleCases.size,
                            totalCount = cases.size,
                            onCategorySelected = { category ->
                                selectedEncounterCategory = category
                                selectedEncounterSystem = null
                            },
                            onSystemSelected = { selectedEncounterSystem = it },
                            onDifficultySelected = { selectedEncounterDifficulty = it },
                            onSavedFilterSelected = { selectedEncounterCaseFilter = it },
                            onAccentSelected = { encounterAccentKey = it },
                            onStyleSelected = { encounterStyleKey = it },
                            onReset = {
                                selectedEncounterCategory = null
                                selectedEncounterSystem = null
                                selectedEncounterDifficulty = EncounterDifficulty.ALL
                                selectedEncounterCaseFilter = EncounterCaseFilter.ALL
                                encounterAccentKey = "us"
                                encounterStyleKey = "clear"
                            },
                        )
                    }
                }
                if (selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("Choose one team task"), fontWeight = FontWeight.Bold)
                                Text(
                                    t("Handover, consult, read back an order, or transfer care."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { showTeamCommunicationHelp = true }) {
                                Icon(Icons.Default.HelpOutline, contentDescription = t("What is this?"))
                            }
                        }
                    }
                    latestPresentationLaunch?.let { launch ->
                        item {
                            Card(
                                onClick = {
                                    com.example.medvoicetrainer.analysis.Telemetry.track(
                                        "team_communication_started",
                                        mapOf("surface" to "practice", "task" to "attending_presentation")
                                    )
                                    onStartCase(launch.id, launch.title, launch.caseJson)
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(t("Present your latest patient"), fontWeight = FontWeight.ExtraBold)
                                        Text(launch.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(t("The attending will probe omissions, reasoning, and your plan."), style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            t("Start"),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = t("Start"),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    val tasks = cases.map { it.system }.filter(String::isNotBlank).distinct()
                    if (tasks.size > 1) {
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selectedCommunicationTask == null,
                                    onClick = { selectedCommunicationTask = null },
                                    label = { Text(t("All team tasks")) },
                                )
                                tasks.forEach { task ->
                                    FilterChip(
                                        selected = selectedCommunicationTask == task,
                                        onClick = { selectedCommunicationTask = task },
                                        label = { Text(t(task)) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (selectedTab.key == PracticeTabKey.ENCOUNTER && visibleCases.isEmpty() && !isLoading) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(t("No matching patient cases"), fontWeight = FontWeight.Bold)
                            Text(
                                t("Try another medical term or clear the filters."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (encounterSearchQuery.isNotBlank() || selectedEncounterCategory != null ||
                                selectedEncounterSystem != null || selectedEncounterDifficulty != EncounterDifficulty.ALL ||
                                selectedEncounterCaseFilter != EncounterCaseFilter.ALL) {
                                TextButton(onClick = {
                                    encounterSearchQuery = ""
                                    selectedEncounterCategory = null
                                    selectedEncounterSystem = null
                                    selectedEncounterDifficulty = EncounterDifficulty.ALL
                                    selectedEncounterCaseFilter = EncounterCaseFilter.ALL
                                }) {
                                    Text(t("Clear filters"))
                                }
                            }
                        }
                    }
                }
                items(visibleCases, key = { it.id }) { case ->
                    val caseTags = encounterCaseTags[case.id].orEmpty()
                    PracticeItemCard(
                        title = case.title,
                        description = case.description,
                        contextLabel = if (selectedTab.key == PracticeTabKey.ENCOUNTER) {
                            listOfNotNull(
                                case.difficulty.takeIf(String::isNotBlank)?.let(::encounterDifficultyLabel)?.let(t),
                                ENCOUNTER_SYSTEM_LABELS[case.system],
                                case.diagnosis.takeIf { encounterSearchQuery.isNotBlank() && it.isNotBlank() },
                            ).joinToString(" • ").ifBlank { null }
                        } else if (selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION) {
                            listOfNotNull(
                                case.system.takeIf(String::isNotBlank),
                                case.difficulty.takeIf(String::isNotBlank)?.let(::encounterDifficultyLabel)?.let(t),
                            ).joinToString(" · ").ifBlank { null }
                        } else null,
                        icon = selectedTab.icon.takeUnless { selectedTab.key == PracticeTabKey.ENCOUNTER },
                        encounterTags = caseTags.takeIf { selectedTab.key == PracticeTabKey.ENCOUNTER },
                        onToggleEncounterTag = if (selectedTab.key == PracticeTabKey.ENCOUNTER) {
                            { tag -> viewModel.toggleEncounterCaseTag(case.id, tag) }
                        } else null,
                        onReportContent = if (
                            BuildConfig.AI_REPORT_ENDPOINT.isNotBlank() &&
                            selectedTab.key == PracticeTabKey.ENCOUNTER && case.assetPath != null
                        ) {
                            { contentReportCase = case }
                        } else null,
                        onClick = {
                            when {
                                case.category == "listening" && case.id == "adaptive_pick" -> {
                                    val picked = ListeningDrillEngine.chooseAdaptiveDrill(
                                        listeningDrillJsons.map { it.toDeepMap() },
                                        listeningAttempts.map { it.toAnalysisMap() }
                                    )
                                    val pickedId = picked?.get("id") as? String
                                    val pickedJson = listeningDrillJsons.firstOrNull { it.optString("id") == pickedId }
                                    if (pickedJson != null) {
                                        listeningLabCase = PracticeCase(
                                            id = pickedJson.optString("id", "unknown"),
                                            title = "${pickedJson.optString("category", "Listening Drill")} | " +
                                                "Level ${pickedJson.optInt("difficulty", 1)}",
                                            description = pickedJson.optString("context", "Listen and extract exact details."),
                                            jsonContent = pickedJson.toString(),
                                            category = "listening",
                                        )
                                    } else {
                                        adaptivePickMessage = t("No listening drills are available.")
                                    }
                                }
                                else -> coroutineScope.launch {
                                    val selected = case.materialize(viewModel)
                                    when {
                                        selected.category == "survival" -> onStartSurvival(selected.id, selected.title, selected.jsonContent)
                                        selected.category == "listening" -> listeningLabCase = selected
                                        selected.category == "teachback" -> onStartTeachback(selected.id, selected.title, selected.jsonContent)
                                        selected.category == "interview" -> onStartInterview(selected.id, selected.title, selected.jsonContent)
                                        selected.category == "follow_up" -> followUpBriefingCase = selected
                                        selectedTab.key == PracticeTabKey.TEAM_COMMUNICATION -> teamBriefingCase = selected
                                        selectedTab.key == PracticeTabKey.ENCOUNTER -> {
                                            val hasAvailableResults =
                                                com.example.medvoicetrainer.analysis.InvestigationResults
                                                    .parseAvailableResults(selected.jsonContent)
                                                    .isNotEmpty()
                                            if (hasAvailableResults) {
                                                encounterResultsBriefingCase = selected
                                            } else {
                                                onStartCase(
                                                    selected.id,
                                                    selected.title,
                                                    withEncounterOptions(
                                                        selected.jsonContent,
                                                        encounterAccentKey,
                                                        encounterStyleKey,
                                                    ),
                                                )
                                            }
                                        }
                                        else -> onStartCase(selected.id, selected.title, selected.jsonContent)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showTeamCommunicationHelp) {
        AlertDialog(
            onDismissRequest = { showTeamCommunicationHelp = false },
            confirmButton = {
                TextButton(onClick = { showTeamCommunicationHelp = false }) { Text(t("Got it")) }
            },
            icon = { Icon(Icons.Default.Groups, contentDescription = null) },
            title = { Text(t("What is Team Communication?")) },
            text = {
                Text(
                    t("Pick one real task you do with another clinician. You will practise its structure, urgency, clinical request, and closed-loop confirmation. Listening-only drills live in Listening Lab.")
                )
            },
        )
    }

    adaptivePickMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { adaptivePickMessage = null },
            confirmButton = {
                TextButton(onClick = { adaptivePickMessage = null }) { Text(t("OK")) }
            },
            text = { Text(msg) }
        )
    }

    contentReportCase
        ?.takeIf { BuildConfig.AI_REPORT_ENDPOINT.isNotBlank() }
        ?.let { case ->
        ContentIssueReportDialog(
            contentType = "patient_case",
            contentId = case.id,
            contentTitle = case.title,
            surface = "practice_case_list",
            onDismiss = { contentReportCase = null },
        )
    }
}

/** Stamps the picked accent/delivery style onto a case's JSON so PromptBuilder.buildPatientPrompt picks it up. */
private fun withEncounterOptions(
    jsonContent: String,
    accent: String,
    style: String,
): String {
    val json = JSONObject(jsonContent)
    json.put("listening_accent", accent)
    json.put("speech_style", style)
    return json.toString()
}

private data class VoiceChoice(val key: String, val label: String)

private val VOICE_ACCENTS = listOf(
    VoiceChoice("us", "General American"),
    VoiceChoice("ca", "Canadian"),
    VoiceChoice("uk", "British"),
    VoiceChoice("aus", "Australian"),
    VoiceChoice("nz", "New Zealand"),
    VoiceChoice("in", "Indian"),
    VoiceChoice("ph", "Filipino"),
    VoiceChoice("sg", "Singaporean"),
    VoiceChoice("ng", "Nigerian"),
    VoiceChoice("za", "South African"),
    VoiceChoice("es", "Spanish-speaking"),
    VoiceChoice("fr", "French-speaking"),
    VoiceChoice("de", "German-speaking"),
    VoiceChoice("ie", "Irish"),
    VoiceChoice("sco", "Scottish"),
)

private val VOICE_SPEECH_STYLES = listOf(
    VoiceChoice("clear", "Clear (textbook)"),
    VoiceChoice("natural", "Natural everyday"),
    VoiceChoice("soft", "Soft-spoken & trailing"),
    VoiceChoice("dense", "Information-dense burst"),
    VoiceChoice("street", "Fast & mumbly (hard mode)"),
)

// The counterpart's voice is otherwise picked fully at random across every voice the provider
// offers (see VoiceCatalog) — for Dating & Romance that can land on a same-sex-flirting pairing
// the learner didn't ask for, so that one category lets them pin the gender while the specific
// voice within it still stays random.
private const val SURVIVAL_DATING_CATEGORY = "Dating & Romance"

private val VOICE_GENDER_CHOICES = listOf(
    VoiceChoice("", "Random"),
    VoiceChoice("female", "Female"),
    VoiceChoice("male", "Male"),
)

private typealias SurvivalFrameOption = SurvivalComposer.FrameSummary

/**
 * Picks uniformly among *groups* of options that share the same [SurvivalFrameOption.contentKey],
 * rather than uniformly among individual rows. `survival_situations.json` pads its count with
 * batches of ~20 near-identical frames (same opener/title stem, only an id suffix differs) — a
 * plain uniform draw over rows would land on one of those batches roughly 20x more often than a
 * frame that only appears once, which is exactly the "random situation keeps giving me the same
 * handful of things" bias reported against this picker. Weighting by unique content instead of by
 * row count fixes that without touching the shared data file.
 *
 * Callers group once (via `remember`, keyed on the filtered option list) and reuse the result
 * across repeated presses, so mashing the button doesn't re-run `groupBy` over ~1600 rows each time.
 */
private fun List<List<SurvivalFrameOption>>.randomAcrossGroups(): SurvivalFrameOption? =
    randomOrNull()?.randomOrNull()

/**
 * Fixed row height for the Situation menu. The lazy list inside the menu needs an exact height (see
 * the call site), which only works if every row is exactly this tall — hence the capped line counts
 * on the row's two labels.
 */
private val SURVIVAL_MENU_ROW_HEIGHT = 76.dp
private const val SURVIVAL_MENU_VISIBLE_ROWS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurvivalPracticeSelector(
    frames: List<SurvivalComposer.FrameSummary>,
    personas: List<Map<String, Any?>>,
    roles: List<Map<String, Any?>>,
    goals: List<Map<String, Any?>>,
    twists: List<Map<String, Any?>>,
    userStates: List<Map<String, Any?>>,
    rapidFireQuestions: List<String>,
    appContext: android.content.Context,
    translate: (String) -> String,
    onStart: (String, String, String, Float) -> Unit,
    // Survival "Advanced Beta" only — false for the shipped Survival tab, which then behaves
    // exactly as before (no badge, no extra case fields, no scene-transition menu lookup).
    advancedBeta: Boolean = false,
    sceneTransitionMenuFor: (String) -> String = { "" },
    // True when the selected voice backend has no scene-transition support (see the notice below).
    sceneTransitionsUnsupported: Boolean = false,
    // Everyday Phrasebook's pre-start card. Empty when the feature is switched off, in which case
    // the card renders nothing at all rather than an empty container above the start button.
    phrasePreviewForCase: (String) -> List<com.example.medvoicetrainer.analysis.EverydayPhrase> = { emptyList() },
) {
    val frameOptions = frames
    val framesById = remember(frames) { frames.associateBy { it.id } }
    val allCategories = remember(frameOptions) {
        frameOptions.map { it.category }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    var selectedCategory by remember(frames) { mutableStateOf("") }
    val filteredOptions = remember(frameOptions, selectedCategory) {
        if (selectedCategory.isBlank()) frameOptions
        else frameOptions.filter { it.category == selectedCategory }
    }
    // Recomputed only when the category filter or frame list changes — not on every "Random
    // situation" press — so repeated presses are an O(1) pick over pre-built groups.
    val filteredContentGroups = remember(filteredOptions) {
        filteredOptions.groupBy { it.contentKey }.values.toList()
    }
    var selectedId by remember(frames) {
        mutableStateOf(filteredContentGroups.randomAcrossGroups()?.id.orEmpty())
    }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var situationMenuExpanded by remember { mutableStateOf(false) }
    var accentMenuExpanded by remember { mutableStateOf(false) }
    var styleMenuExpanded by remember { mutableStateOf(false) }
    var genderMenuExpanded by remember { mutableStateOf(false) }
    var surpriseMode by remember { mutableStateOf(false) }
    var rapidFire by remember { mutableStateOf(false) }
    var earOnly by remember { mutableStateOf(true) }
    var accentKey by remember { mutableStateOf("us") }
    var styleKey by remember { mutableStateOf("clear") }
    var voiceGenderKey by remember { mutableStateOf("") }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var startError by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // Width of the Situation field, tracked so the menu's lazy list can be given a *fixed* width.
    // See the menu body for why a fixed width (rather than fillMaxWidth) is load-bearing.
    val density = LocalDensity.current
    var situationAnchorWidth by remember { mutableStateOf(0.dp) }

    LaunchedEffect(filteredOptions) {
        if (filteredOptions.none { it.id == selectedId }) {
            selectedId = filteredContentGroups.randomAcrossGroups()?.id.orEmpty()
        }
    }
    val selectedFrame = remember(filteredOptions, framesById, selectedId) {
        val visibleId = selectedId.takeIf { id -> filteredOptions.any { it.id == id } }
            ?: filteredOptions.firstOrNull()?.id
        visibleId?.let { framesById[it] }
    }
    var phrasePreview by remember {
        mutableStateOf<List<com.example.medvoicetrainer.analysis.EverydayPhrase>>(emptyList())
    }
    var phrasePreviewOpened by remember { mutableStateOf(false) }
    LaunchedEffect(selectedFrame?.id, surpriseMode, rapidFire) {
        phrasePreviewOpened = false
        val sourceJson = if (surpriseMode || rapidFire || selectedFrame == null) {
            ""
        } else {
            withContext(Dispatchers.IO) {
                SurvivalComposer.loadSituation(appContext, selectedFrame.id)
                    ?.let { JSONObject(it).toString() }
                    .orEmpty()
            }
        }
        phrasePreview = withContext(Dispatchers.IO) { phrasePreviewForCase(sourceJson) }
    }
    val previewTts = if (phrasePreview.isEmpty()) null else rememberEnglishTts()
    val selectedAccent = VOICE_ACCENTS.first { it.key == accentKey }
    val selectedStyle = VOICE_SPEECH_STYLES.first { it.key == styleKey }
    val isDatingScenario = !rapidFire && selectedFrame?.category == SURVIVAL_DATING_CATEGORY
    val selectedGender = VOICE_GENDER_CHOICES.first { it.key == voiceGenderKey }
    val canStart = if (rapidFire) rapidFireQuestions.isNotEmpty() else selectedFrame != null

    // Collapsed-state summary for the advanced section, so tuning that *is* switched on (sprint
    // mode, ear-only, a non-default accent or pace) stays readable without opening it.
    val advancedSummary = buildList {
        if (rapidFire) add(translate("Reaction sprint"))
        if (surpriseMode) add(translate("Surprise me"))
        if (earOnly) add(translate("Ear-only"))
        add(selectedAccent.label)
        add(selectedStyle.label)
        add("${(playbackSpeed * 100).roundToInt() / 100f}x")
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = translate("Survival English"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = if (advancedBeta) {
                translate(
                    "Your conversation can move somewhere else, skip ahead " +
                        "in time, hand you to a different person, or send you off alone to fetch " +
                        "information and report back. You choose whether each change happens.",
                )
            } else {
                translate("Choose a real-life situation or let the app surprise you, then tune how it sounds.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Scene transitions ride on Gemini Live's function calling; the OpenAI realtime client does
        // not implement them, so on that backend the beta tab is an ordinary Survival session. Say
        // so here rather than letting the scene changes just never arrive.
        if (advancedBeta && sceneTransitionsUnsupported) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = translate(
                        "Scene changes need the Gemini voice backend. With the current voice " +
                            "setting this runs as an ordinary Survival session — switch the voice " +
                            "backend to Gemini in Preferences to try them.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        ExposedDropdownMenuBox(
            expanded = categoryMenuExpanded,
            onExpandedChange = { categoryMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedCategory.ifBlank { translate("All categories") },
                onValueChange = {},
                readOnly = true,
                label = { Text(translate("Category")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { categoryMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(translate("All categories")) },
                    onClick = {
                        selectedCategory = ""
                        categoryMenuExpanded = false
                    },
                )
                allCategories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category) },
                        onClick = {
                            selectedCategory = category
                            categoryMenuExpanded = false
                        },
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = situationMenuExpanded,
            onExpandedChange = { if (!rapidFire) situationMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = if (rapidFire) {
                    translate("Everyday Reaction Sprint")
                } else {
                    selectedFrame?.title.orEmpty()
                },
                onValueChange = {},
                readOnly = true,
                enabled = !rapidFire,
                label = { Text(translate("Situation")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(situationMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        situationAnchorWidth = with(density) { it.size.width.toDp() }
                    }
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = situationMenuExpanded,
                onDismissRequest = { situationMenuExpanded = false },
            ) {
                // "All categories" means 1400+ rows here. ExposedDropdownMenu lays its content out
                // in a plain scrolling Column, so a forEach composed and measured every single row
                // before the menu could appear — seconds of jank on each open. A LazyColumn only
                // builds the handful of rows actually on screen.
                //
                // But a lazy list inside a menu is only safe if it is given a *fixed size on both
                // axes*. That menu Column wraps its content in Modifier.width(IntrinsicSize.Max)
                // and is itself verticalScroll'd, so it asks its child for both an intrinsic width
                // and an unbounded height. LazyColumn is built on SubcomposeLayout, which throws
                // ("Asking for intrinsic measurements of SubcomposeLayout layouts is not
                // supported") rather than answering — it can't know the size of rows it hasn't
                // composed. Compose's own suggested mitigation is to add a size modifier so the
                // query returns without ever reaching the lazy layout, which is what the fixed
                // width + height below do: Modifier.width(Dp)/height(Dp) resolve to fixed
                // constraints, and that modifier short-circuits the intrinsic query with the known
                // value instead of delegating down.
                //
                // fillMaxWidth() is NOT a substitute: it resolves against incoming constraints
                // rather than to a fixed size, so it forwards the intrinsic query to the lazy list
                // and still crashes. Hence the measured anchor width.
                val menuWidth = if (situationAnchorWidth > 0.dp) situationAnchorWidth else 280.dp
                LazyColumn(
                    modifier = Modifier
                        .width(menuWidth)
                        .height(SURVIVAL_MENU_ROW_HEIGHT * filteredOptions.size.coerceIn(1, SURVIVAL_MENU_VISIBLE_ROWS)),
                ) {
                    items(filteredOptions, key = { it.id }) { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        option.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        option.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                selectedId = option.id
                                situationMenuExpanded = false
                            },
                            modifier = Modifier.height(SURVIVAL_MENU_ROW_HEIGHT),
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                filteredContentGroups.randomAcrossGroups()?.let { selectedId = it.id }
                rapidFire = false
                startError = null
            },
            enabled = filteredOptions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(translate("Random situation"))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                text = when {
                    rapidFire -> translate("The first prompt arrives immediately. Respond naturally and keep moving.")
                    surpriseMode -> translate("The situation is hidden. Listen to the opening line and work it out in real time.")
                    else -> selectedFrame?.publicBrief
                        ?: translate("No Survival situations are available.")
                },
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Directly above the start button, collapsed: this is the last thing the learner sees
        // before the microphone opens, which is the only place a pre-start phrase card is worth
        // anything. Surprise mode composes the scene at start time, so what is offered here is the
        // set of moves that work in any scene rather than lines for one the learner cannot see yet.
        ScenePhrasesCard(
            phrases = phrasePreview,
            ttsReady = previewTts?.ready == true,
            onListen = { previewTts?.speak(it) },
            onOpened = { phrasePreviewOpened = true },
        )

        startError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                startError = null
                isStarting = true
                val selectedFrameId = selectedFrame?.id
                val selectedOptions = SurvivalSessionOptions(
                    surpriseMode = surpriseMode,
                    rapidFire = rapidFire,
                    earOnly = earOnly,
                    accent = accentKey,
                    speechStyle = styleKey,
                    playbackSpeed = playbackSpeed,
                    voiceGenderPreference = if (isDatingScenario) voiceGenderKey else "",
                )
                coroutineScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val frame = if (selectedOptions.rapidFire) {
                                rapidFireSurvivalFrame(rapidFireQuestions.shuffled())
                            } else {
                                val id = requireNotNull(selectedFrameId)
                                requireNotNull(SurvivalComposer.loadSituation(appContext, id)) {
                                    "Could not load Survival situation $id"
                                }
                            }
                            val realization = if (selectedOptions.rapidFire) {
                                SurvivalComposer.composeSurvivalSituation(frame)
                            } else {
                                SurvivalComposer.composeNovelSurvivalSituation(
                                    situation = frame,
                                    personas = personas,
                                    roles = roles,
                                    goals = goals,
                                    twists = twists,
                                    userStates = userStates,
                                )
                            }
                            val case = applySurvivalSessionOptions(
                                realization,
                                selectedOptions,
                            )
                            val finalCase = if (advancedBeta) {
                                applyAdvancedBetaOptions(case, sceneTransitionMenuFor)
                            } else {
                                case
                            }
                            val id = finalCase["id"]?.toString().orEmpty().ifBlank { "survival_practice" }
                            val title = finalCase["title"]?.toString().orEmpty().ifBlank { "Survival English" }
                            val rawCase = JSONObject(finalCase).toString()
                            val persistedCase = if (phrasePreviewOpened) {
                                com.example.medvoicetrainer.analysis.EverydayPhrasebook.markPreviewed(rawCase)
                            } else rawCase
                            Triple(id, title, persistedCase)
                        }
                    }.onSuccess { (id, title, caseJson) ->
                        onStart(id, title, caseJson, selectedOptions.playbackSpeed)
                    }.onFailure { error ->
                        startError = error.message ?: translate("Could not compose this Survival scenario.")
                    }
                    isStarting = false
                }
            },
            enabled = canStart && !isStarting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (isStarting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (rapidFire) translate("Start Reaction Sprint")
                    else translate("Start Survival Scenario"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Everything below is optional tuning (difficulty toggles, voice, pacing). It used to sit
        // between the situation pickers and the start button, which pushed "Start" a full screen
        // down and made it hard to find — collapsed by default, the whole pick-and-start path now
        // fits without scrolling, and the summary line keeps active tuning visible.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = translate("Advanced settings"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = advancedSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SurvivalToggleRow(
                            label = translate("Surprise me"),
                            supportingText = translate("Hide the scene until the conversation has begun."),
                            checked = surpriseMode,
                            enabled = !rapidFire,
                            onCheckedChange = { surpriseMode = it },
                        )
                        SurvivalToggleRow(
                            label = translate("Rapid-fire reaction sprint"),
                            supportingText = translate("React quickly to 12 short everyday prompts."),
                            checked = rapidFire,
                            enabled = rapidFireQuestions.isNotEmpty(),
                            onCheckedChange = {
                                rapidFire = it
                                if (it) surpriseMode = false
                            },
                        )
                        SurvivalToggleRow(
                            label = translate("Ear-only"),
                            supportingText = translate("Keep the partner's words out of sight while you listen."),
                            checked = earOnly,
                            enabled = true,
                            onCheckedChange = { earOnly = it },
                        )

                        ExposedDropdownMenuBox(
                            expanded = accentMenuExpanded,
                            onExpandedChange = { accentMenuExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedAccent.label,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(translate("Accent")) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accentMenuExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = accentMenuExpanded,
                                onDismissRequest = { accentMenuExpanded = false },
                            ) {
                                VOICE_ACCENTS.forEach { accent ->
                                    DropdownMenuItem(
                                        text = { Text(accent.label) },
                                        onClick = {
                                            accentKey = accent.key
                                            accentMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        ExposedDropdownMenuBox(
                            expanded = styleMenuExpanded,
                            onExpandedChange = { styleMenuExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedStyle.label,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(translate("Delivery style")) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(styleMenuExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = styleMenuExpanded,
                                onDismissRequest = { styleMenuExpanded = false },
                            ) {
                                VOICE_SPEECH_STYLES.forEach { style ->
                                    DropdownMenuItem(
                                        text = { Text(style.label) },
                                        onClick = {
                                            styleKey = style.key
                                            styleMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        if (isDatingScenario) {
                            ExposedDropdownMenuBox(
                                expanded = genderMenuExpanded,
                                onExpandedChange = { genderMenuExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = selectedGender.label,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(translate("Counterpart's voice")) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genderMenuExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                )
                                ExposedDropdownMenu(
                                    expanded = genderMenuExpanded,
                                    onDismissRequest = { genderMenuExpanded = false },
                                ) {
                                    VOICE_GENDER_CHOICES.forEach { choice ->
                                        DropdownMenuItem(
                                            text = { Text(choice.label) },
                                            onClick = {
                                                voiceGenderKey = choice.key
                                                genderMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = translate("Playback pace") + ": ${((playbackSpeed * 100).roundToInt() / 100f)}x",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = playbackSpeed,
                            onValueChange = { playbackSpeed = it },
                            valueRange = 0.5f..2.5f,
                            steps = 7,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurvivalToggleRow(
    label: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
fun PracticeItemCard(
    title: String,
    description: String,
    contextLabel: String? = null,
    icon: ImageVector?,
    encounterTags: Set<EncounterCaseTag>? = null,
    onToggleEncounterTag: ((EncounterCaseTag) -> Unit)? = null,
    onReportContent: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var tagMenuExpanded by remember { mutableStateOf(false) }
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val isEncounterCard = encounterTags != null && onToggleEncounterTag != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                contextLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isEncounterCard && encounterTags.orEmpty().any {
                        it == EncounterCaseTag.TODO || it == EncounterCaseTag.DIFFICULT
                    }) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (EncounterCaseTag.TODO in encounterTags.orEmpty()) {
                            EncounterTagBadge(t("Do later"), MaterialTheme.colorScheme.secondaryContainer)
                        }
                        if (EncounterCaseTag.DIFFICULT in encounterTags.orEmpty()) {
                            EncounterTagBadge(t("Difficult"), MaterialTheme.colorScheme.errorContainer)
                        }
                    }
                }
            }
            if (isEncounterCard) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { onToggleEncounterTag?.invoke(EncounterCaseTag.FAVORITE) }) {
                        Icon(
                            imageVector = if (EncounterCaseTag.FAVORITE in encounterTags.orEmpty()) {
                                Icons.Default.Star
                            } else {
                                Icons.Default.StarBorder
                            },
                            contentDescription = if (EncounterCaseTag.FAVORITE in encounterTags.orEmpty()) {
                                t("Remove from favorites")
                            } else {
                                t("Add to favorites")
                            },
                            tint = if (EncounterCaseTag.FAVORITE in encounterTags.orEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { tagMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = t("Case tags"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = tagMenuExpanded,
                            onDismissRequest = { tagMenuExpanded = false },
                        ) {
                            listOf(
                                EncounterCaseTag.TODO to t("Do later"),
                                EncounterCaseTag.DIFFICULT to t("Difficult"),
                            ).forEach { (tag, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = {
                                        Icon(
                                            if (tag in encounterTags.orEmpty()) Icons.Default.Check else Icons.Default.Add,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onToggleEncounterTag?.invoke(tag)
                                        tagMenuExpanded = false
                                    },
                                )
                            }
                            if (onReportContent != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(t("content_report.action")) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Flag, contentDescription = null)
                                    },
                                    onClick = {
                                        tagMenuExpanded = false
                                        onReportContent()
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Start",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EncounterTagBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
