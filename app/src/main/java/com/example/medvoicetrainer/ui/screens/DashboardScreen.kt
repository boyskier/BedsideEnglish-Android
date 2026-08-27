package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.analysis.clinicalTranscriptsWithPythonRoles
import com.example.medvoicetrainer.analysis.toAnalysisMap
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

/**
 * The dashboard adapts to where the learner is in their journey (RETENTION_FUNNEL.md Stage 4:
 * "a dashboard full of numbers → decision paralysis"). NEW hides the analytics wall behind a
 * single get-started action; DEMO speaks demo-tour truth instead of clinical missions
 * (ONBOARDING_POST_DEMO_FLOW.md §3.5); RETURNING shows the full insight set; POWER users keep an
 * action-first top with insights tucked into a collapsible section for a fast daily return.
 */
private enum class DashStage { NEW, DEMO, RETURNING, POWER }

// §5 (early-UX): number of analyzed sessions before the full analytics wall opens by default for a
// RETURNING learner. Below this, insights stay collapsed-but-discoverable so the first few sessions
// aren't a wall of near-empty cards; at/above it, the wall opens by default to build the review
// habit (still collapsible). Power users (see DashStage.POWER) always default collapsed regardless.
private const val INSIGHTS_HABIT_THRESHOLD = 4

// How much of each engine's output Home actually shows.
//
// Every one of these engines is happy to return more than fits on a phone dashboard, and for a
// long time Home printed all of it: four roadmap cards (four lines each), three ranked priorities,
// an unbounded progress-bullet list, eight error bars, and ten vocabulary rows — on one screen,
// under a mission card that had already said what to do next. None of that is deleted; it is
// ranked output, so the tail is by construction the part that changes nothing, and the full
// versions live where a learner goes to study them (Practice, History, the SRS tab).
private const val HOME_ROADMAP_CARDS = 2
private const val HOME_DIAGNOSTIC_PRIORITIES = 2
private const val HOME_PROGRESS_BULLETS = 2
private const val HOME_ERROR_CLUSTERS = 5
private const val HOME_VOCAB_TERMS = 3

// Stable keys for the per-group "the learner has opened this before" flags that retire each
// group's one-line hint (see MainViewModel.openedInsightGroups). Persisted, so never rename.
private const val INSIGHT_GROUP_PATHWAY = "pathway"
private const val INSIGHT_GROUP_STANDING = "standing"
private const val INSIGHT_GROUP_WEAK_SPOTS = "weak_spots"

// Error-item domains that belong to the review queue (mirrors AppDao's `domain IN (...)` filters).
// Hoisted to a constant because the two filters below apply it per item — building a throwaway set
// for every element of the error list was the actual cost of those otherwise-cheap predicates.
private val REVIEWABLE_DOMAINS = setOf("clinical", "everyday")

// Time-of-day dashboard greeting: replaces a hardcoded "Welcome, {app name}" (the app name isn't a
// user name — there's no learner name to greet with) with a Claude-style contextual line, purely
// from the device's local clock/timezone (no permission, not sent anywhere, so no Play Data Safety
// entry). Bands with a "witty" off-hours read (late_night/early_morning/late_evening) get two
// variants so the wording doesn't feel identical every time; the day-of-year seed keeps the pick
// stable for the whole day instead of flickering on every recomposition.
private fun timeOfDayGreetingKey(hour: Int, daySeed: Int): String {
    val (band, variantCount) = when (hour) {
        in 0..4 -> "late_night" to 2
        in 5..7 -> "early_morning" to 2
        in 8..11 -> "morning" to 1
        in 12..16 -> "afternoon" to 1
        in 17..20 -> "evening" to 1
        else -> "late_evening" to 2 // 21..23
    }
    val variant = (daySeed % variantCount) + 1
    return "dashboard.greeting.$band.$variant"
}

/**
 * "just now" / "23 min ago" / "6 h ago" for the Home recovery card.
 *
 * Only ever renders inside SessionRecovery's window, so hours is the coarsest unit needed; an
 * unreadable timestamp falls back to no suffix rather than to a wrong one.
 */
private fun recoveryAgeLabel(
    session: com.example.medvoicetrainer.db.SessionEntity,
    now: java.time.LocalDateTime,
    t: (String) -> String,
): String {
    val minutes = com.example.medvoicetrainer.db.SessionRecovery.ageOf(session, now)
        ?.toMinutes()
        ?.coerceAtLeast(0)
        ?: return ""
    return when {
        minutes < 2L -> t("just now")
        minutes < 60L -> "$minutes " + t("min ago")
        else -> "${minutes / 60L} " + t("h ago")
    }
}

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToTab: (Int) -> Unit,
    onStartCase: (String, String, String) -> Unit,
    onStartExam: (String, String, String) -> Unit = onStartCase,
    onOpenSettings: () -> Unit = {},
    onOpenImport: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val apiUsageEvents by viewModel.apiUsageEvents.collectAsStateWithLifecycle()
    val errorItems by viewModel.errorItems.collectAsStateWithLifecycle()
    val dashboardAnalysis by viewModel.dashboardAnalysis.collectAsStateWithLifecycle()
    val importCardDismissed by viewModel.importCardDismissed.collectAsStateWithLifecycle()
    val dismissedRecoverySessionIds by viewModel.dismissedRecoverySessionIds.collectAsStateWithLifecycle()

    // Both of these walk the full history (computeStreak parses a date per session), so they are
    // keyed to their inputs rather than recomputed on every recomposition of this screen — which
    // is frequent, since a handful of DB flows and four group-expansion toggles all invalidate it.
    val streakCount = remember(sessions) {
        com.example.medvoicetrainer.analysis.LifetimeStatsEngine.computeStreak(sessions)
    }
    // Count accepted language items whose review is
    // actually due (dueAt <= now), not just "any active item" — was previously mismarked
    // "simplified due logic" and counted every active item regardless of its schedule.
    val nowIsoForDue = remember { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()) }
    val dueErrorsList = remember(errorItems, nowIsoForDue) {
        errorItems.filter {
            (it.domain in REVIEWABLE_DOMAINS ||
                it.category.startsWith("pronunciation", ignoreCase = true)) &&
                it.state != "mastered" &&
                it.state != "observed" &&
                it.dueAt <= nowIsoForDue
        }
    }
    val dueReviews = dueErrorsList.size

    val mistakeGenomeReport = remember(errorItems) {
        com.example.medvoicetrainer.analysis.MistakeGenomeEngine.buildMistakeGenome(errorItems)
    }
    // Snapshot percentages reveal which error category dominates; this complementary seven-day
    // comparison shows whether those mistakes are actually recurring more or less often.
    val mistakeGenomeTrend = remember(errorItems) {
        com.example.medvoicetrainer.analysis.MistakeGenomeEngine.buildRecurrenceTrend(errorItems)
    }
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current

    // Ported from app/ui/dashboard_tab.py's get_beginner_guided_path/get_diagnostic_profile/
    // get_progress_report cards — these analysis engines existed in Kotlin already but were
    // never called from any screen (confirmed dead code, see MIGRATION_MASTER.md).
    val sessionMaps = dashboardAnalysis.sessionMaps
    // Ported from dashboard_tab.py's _draw_categories (app/analysis/l1_stats.py): where a
    // learner's tagged corrections cluster by error category, shaped by their first language.
    val errorProfiles = dashboardAnalysis.errorProfiles
    val guidedPath = dashboardAnalysis.guidedPath
    val diagnosticProfile = dashboardAnalysis.diagnosticProfile
    val errorStats = dashboardAnalysis.errorStats
    val progressReport = dashboardAnalysis.progressReport

    // Mirrors queries.py's get_daily_mission(): due_count + open debrief commitments feed the
    // same real build_daily_mission() port used for the tomorrow-toast preview in MainViewModel.
    val commitmentsList by viewModel.commitments.collectAsStateWithLifecycle()
    val openCommitments = remember(commitmentsList) {
        commitmentsList.filter { it.status == "open" }.sortedByDescending { it.createdAt }
    }
    val mission = dashboardAnalysis.mission

    // Ported from app/analysis/learning_roadmap.py's build_us_clinical_english_roadmap — was
    // previously an invented "Level 1-5" stub unrelated to the real Python file (same "wrongly
    // marked Y" pattern as daily_mission.py above; see MIGRATION_MASTER.md).
    val roadmapCards = dashboardAnalysis.roadmapCards

    // Mirrors queries.py's get_confidence_trend()/get_mastered_this_week() — ported from
    // dashboard_tab.py's "Confidence this week" card (_refresh_confidence_card), which had no
    // Kotlin equivalent until now despite ShareReport.buildWeeklyConfidenceCard/
    // ShareCardImage.renderWeeklyCard already existing unwired (see PORTING_STATUS.md).
    val totalAnalyzed = remember(sessions) { com.example.medvoicetrainer.analysis.LifetimeStatsEngine.totalSessionsAnalyzed(sessions) }
    val (nowIsoForTrend, weekAgoIso, priorAgoIso) = remember {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val now = java.util.Calendar.getInstance()
        val nowStr = fmt.format(now.time)
        val weekAgo = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -7) }
        val priorAgo = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -14) }
        Triple(nowStr, fmt.format(weekAgo.time), fmt.format(priorAgo.time))
    }
    val confidenceTrend = remember(sessions, nowIsoForTrend) {
        com.example.medvoicetrainer.analysis.ConfidenceTrendEngine.computeTrend(sessions, nowIsoForTrend, weekAgoIso, priorAgoIso)
    }
    val masteredThisWeek = remember(errorItems, weekAgoIso) {
        errorItems.count {
            (it.domain in REVIEWABLE_DOMAINS ||
                it.category.startsWith("pronunciation", ignoreCase = true)) &&
                it.state == "mastered" &&
                it.lastSeen >= weekAgoIso
        }
    }

    // Ported from app/analysis/vocabulary_tracker.py, fed by get_all_clinical_transcripts()'s
    // equivalent (clinical sessions only, matching dashboard_tab.py's _refresh_vocabulary).
    val context = LocalContext.current
    // Build classification and transcripts from the same Room snapshot. dashboardAnalysis is
    // computed on a background dispatcher and may still contain its initial empty sessionMaps
    // while sessions has already emitted rows; indexing across those two snapshots crashes Home.
    val clinicalTranscripts = remember(sessions) {
        sessions.clinicalTranscriptsWithPythonRoles()
    }
    val latestPresentationLaunch = remember(sessions) {
        sessions.asSequence()
            .sortedByDescending { it.createdAt }
            .mapNotNull { com.example.medvoicetrainer.analysis.PresentationBuilder.buildLaunch(it.toAnalysisMap()) }
            .firstOrNull()
    }
    var presentationNudgeDismissed by rememberSaveable(
        latestPresentationLaunch?.sourceSessionId ?: -1
    ) { mutableStateOf(false) }
    val vocabCoverage = remember(clinicalTranscripts) {
        com.example.medvoicetrainer.analysis.VocabularyTracker.calculateVocabularyCoverage(context, clinicalTranscripts)
    }

    // Shared route resolver for DailyMissionEngine/LearningRoadmap routes (both share the same
    // {tab, system, caseId, action, category} shape as GuidedPathRoute above). "action":"review"
    // mirrors encounter_tab.py's "My Mistakes" button; an "interview" tab route has no single
    // case to launch directly (just a category filter), so it hands off to the Practice tab
    // where the Interview sub-tab lives, matching the closest reachable behavior today.
    suspend fun startRouteCase(tab: String, system: String?, caseId: String?, action: String?, title: String) {
        when {
            action == "present_latest" -> {
                latestPresentationLaunch?.let { launch ->
                    com.example.medvoicetrainer.analysis.Telemetry.track(
                        "team_communication_started",
                        mapOf("surface" to "home", "task" to "attending_presentation")
                    )
                    onStartCase(launch.id, launch.title, launch.caseJson)
                } ?: onNavigateToTab(3)
            }
            action == "review" -> {
                val review = viewModel.buildReviewSessionCase()
                if (review != null) {
                    val (id, name, json) = review
                    onStartCase(id, name, json)
                }
            }
            tab == "interview" -> onNavigateToTab(1)
            caseId != null -> {
                val dir = when (system) {
                    "foundations" -> "cases/foundations"
                    "drills" -> "cases/drills"
                    null -> "cases"
                    else -> "cases/$system"
                }
                val jsonStr = withContext(Dispatchers.IO) {
                    try {
                        viewModel.loadAsset("$dir/$caseId.json")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        null
                    }
                }
                if (jsonStr != null) {
                    onStartCase(caseId, title, jsonStr)
                }
            }
            else -> onNavigateToTab(1)
        }
    }

    fun startMissionRoute(route: com.example.medvoicetrainer.analysis.MissionRoute?, title: String) {
        if (route == null) {
            onNavigateToTab(1)
            return
        }
        coroutineScope.launch { startRouteCase(route.tab, route.system, route.caseId, route.action, title) }
    }

    fun startRoadmapRoute(route: com.example.medvoicetrainer.analysis.RoadmapRoute, title: String) {
        coroutineScope.launch { startRouteCase(route.tab, route.system, route.caseId, route.action, title) }
    }

    fun startGuidedStep(step: com.example.medvoicetrainer.analysis.GuidedStep) {
        coroutineScope.launch {
            val route = step.route
            if (route.tab == "exam") {
                val json = withContext(Dispatchers.IO) {
                    val scenario = com.example.medvoicetrainer.analysis.ExamMode.listExamScenarios(route.kind)
                        .firstOrNull { it["id"] == route.scenarioId }
                    scenario?.let { s ->
                        val obj = JSONObject()
                        s.forEach { (k, v) -> obj.put(k, v) }
                        obj.toString()
                    }
                }
                if (json != null) {
                    onStartExam(route.scenarioId ?: step.key, step.title, json)
                }
            } else {
                val dir = when (route.system) {
                    "foundations" -> "cases/foundations"
                    "drills" -> "cases/drills"
                    null -> "cases"
                    else -> "cases/${route.system}"
                }
                val jsonStr = withContext(Dispatchers.IO) {
                    try {
                        viewModel.loadAsset("$dir/${route.caseId}.json")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        null
                    }
                }
                if (jsonStr != null) {
                    onStartCase(route.caseId ?: step.key, step.title, jsonStr)
                }
            }
        }
    }

    // --- Journey stage: drives progressive disclosure of the cards below ---
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val isDemo = voiceBackend == "demo"
    // §B1 (early-UX): a live learner uses their OWN key, so surface the running weekly API spend
    // right on the dashboard — an anxious novice shouldn't have to hunt for it. Demo/mock sessions
    // are billed nothing, so this is only shown (and only nonzero) for a real live backend.
    val isLiveBackend = voiceBackend !in setOf("demo", "mock")
    val weeklyCostUsd = remember(sessions, apiUsageEvents, weekAgoIso) {
        sessions.filter { it.createdAt >= weekAgoIso }.sumOf { it.totalCostUsd } +
            apiUsageEvents.filter { it.createdAt >= weekAgoIso }.sumOf { it.costUsd }
    }
    val weeklyCostEstimated = remember(sessions, apiUsageEvents, weekAgoIso) {
        sessions.any { it.createdAt >= weekAgoIso && it.costEstimated } ||
            apiUsageEvents.any { it.createdAt >= weekAgoIso && it.estimated }
    }
    val completedDemo = remember(sessions) { viewModel.getCompletedDemoCaseIds() }
    val demoProgress = remember(completedDemo) { com.example.medvoicetrainer.analysis.DemoTour.tourProgress(completedDemo) }
    val demoComplete = remember(completedDemo) { com.example.medvoicetrainer.analysis.DemoTour.isTourComplete(completedDemo) }
    val nextDemoCase = remember(completedDemo) {
        val nextId = com.example.medvoicetrainer.analysis.DemoTour.getFirstUnseenDemoCaseId(completedDemo)
        com.example.medvoicetrainer.analysis.DemoTour.DEMO_CASES.firstOrNull { it.id == nextId }
    }
    val stage = when {
        isDemo -> DashStage.DEMO
        sessions.isEmpty() -> DashStage.NEW
        sessions.size >= 12 -> DashStage.POWER
        else -> DashStage.RETURNING
    }
    // §5 (early-UX): an early RETURNING learner (their first few analyzed sessions) shouldn't be
    // dropped straight onto the full ~10-card analytics wall built from a single data point — the
    // cards are near-empty and read as decision paralysis. They keep the focused actions (mission +
    // SRS) with insights collapsed-but-discoverable; the groups open by default only once there's
    // enough history to make them meaningful. Power users keep their action-first collapsed default.
    val insightsHabitReached = stage == DashStage.RETURNING && totalAnalyzed >= INSIGHTS_HABIT_THRESHOLD
    // The analytics section used to sit behind ONE "Insights & analytics" toggle, so opening any
    // single question ("where am I weak?") dragged nine unrelated cards on screen with it. Splitting
    // it into independently collapsible themed groups fixed that, but four of them was one question
    // too many: "my level" and "progress" are not two questions a learner asks, they are two halves
    // of one — where do I stand, and am I moving? They are now a single "How I'm doing" group, which
    // is three headers on Home instead of four, in decision order: what to practise next → how I'm
    // doing → what keeps going wrong.
    //
    // Only the action group opens by default, and only once there's enough history to fill it. The
    // other two stay shut because their headers now carry the live figure (see InsightsGroup's
    // `value`) — the learner reads the number without the cards, which is cheaper than any amount
    // of tidying inside them.
    var pathwayExpanded by rememberSaveable(stage, insightsHabitReached) { mutableStateOf(insightsHabitReached) }
    var standingExpanded by rememberSaveable(stage) { mutableStateOf(false) }
    var weakSpotsExpanded by rememberSaveable(stage) { mutableStateOf(false) }
    val showActions = stage == DashStage.RETURNING || stage == DashStage.POWER
    val showInsights = stage == DashStage.RETURNING || stage == DashStage.POWER

    // --- Error-rate trend (recent vs earlier window, per 100 words) ---
    // A falling error rate is a much stronger progress signal than an ever-growing raw count.
    val errorTrend = remember(sessionMaps) {
        if (sessionMaps.size >= 4) {
            val half = sessionMaps.size / 2
            com.example.medvoicetrainer.analysis.L1Stats.categoryErrorRateTrend(
                recentSessions = sessionMaps.take(half),
                previousSessions = sessionMaps.drop(half)
            )
        } else {
            emptyList()
        }
    }
    val thisWeekTrend = confidenceTrend.thisWeek
    // A group header must never be shown for a group that would then render nothing. "How I'm
    // doing" needs no such flag: the diagnostic profile and the vocab meter both render an honest
    // empty state, so that group always has something to say even when the week's cards don't.
    // The guided path is onboarding progress, not learning progress. It belongs in the NEW-stage
    // hero, where it helps a first-time learner get started, but must not occupy an insights
    // header after that with a misleading "3/7" progress count.
    val hasPathwayGroup = roadmapCards.isNotEmpty()
    val hasWeakSpotsGroup = mistakeGenomeReport.totalItems > 0 || errorProfiles.isNotEmpty()

    // --- Collapsed-header figures -------------------------------------------------------------
    // One live number per group, so a shut group still answers its question. Number first in every
    // one of them: the chip truncates from the right, and the figure must be what survives.
    val pathwayValue = roadmapCards.firstOrNull()?.status?.takeIf { it.isNotBlank() }
        ?: mission.title
    // Diagnostic's level reads "B2+ clinical communicator"; the header wants the band alone. Before
    // a learner has been diagnosed there is no band to show, so the vocab meter answers instead —
    // it is the one figure in this group that exists from the very first session.
    val standingValue = remember(diagnosticProfile, vocabCoverage) {
        val diagnosed = diagnosticProfile["has_profile"] == true
        val band = diagnosticProfile["level"]?.toString()?.substringBefore(" ")?.takeIf { it.isNotBlank() }
        if (diagnosed && band != null) band else "${vocabCoverage.percentage}%"
    }
    val weakSpotsValue = mistakeGenomeReport.topCategories.firstOrNull()
        ?.let { "${it.percentage}% ${it.label}" }
        ?: errorProfiles.entries.maxByOrNull { it.value.count }?.let { (key, profile) ->
            "${profile.count} " + (com.example.medvoicetrainer.analysis.L1Stats.CATEGORY_LABELS[key] ?: key)
        }
    // Make the collapsed weak-spots header state the current conclusion, including weekly
    // recurrence when a prior window exists. A first window deliberately has no synthetic delta.
    val weakSpotsSummary = remember(mistakeGenomeReport, mistakeGenomeTrend, t) {
        if (mistakeGenomeReport.totalItems <= 0) {
            null
        } else {
            val recurrence = when {
                !mistakeGenomeTrend.hasData -> null
                !mistakeGenomeTrend.hasPriorWindow ->
                    t("dashboard.genome_summary_recent")
                        .replace("{count}", mistakeGenomeTrend.recentTotal.toString())
                else -> {
                    val delta = mistakeGenomeTrend.totalDelta
                    val signed = if (delta > 0) "+$delta" else delta.toString()
                    t("dashboard.genome_summary_delta")
                        .replace("{count}", mistakeGenomeTrend.recentTotal.toString())
                        .replace("{delta}", signed)
                }
            }
            listOfNotNull(mistakeGenomeReport.headline, recurrence).joinToString(" · ")
        }
    }

    // Which group hints the learner has already outgrown (see MainViewModel.openedInsightGroups).
    val openedInsightGroups by viewModel.openedInsightGroups.collectAsStateWithLifecycle()

    // §13 "Process death mid-session": every turn is already persisted (persistTranscriptSnapshot),
    // so a session lost to a crash or a failed analysis call is recoverable, not silently gone.
    //
    // What this card must NOT do is treat every un-analyzed row as a loss. It used to key off
    // `isUnanalyzedSession` alone — blank feedback — which is equally true of a session the
    // learner deliberately discarded, so pressing "Discard without analyzing" was answered by the
    // app asking about that same session again on the next launch. SessionRecovery now reads the
    // recorded end reason instead, and additionally requires a session substantial enough to be
    // worth analyzing and recent enough to still be on the learner's mind (see its constants).
    // Everything it filters out remains in History, which keeps the same retry action.
    val nowForRecovery = remember(sessions) { java.time.LocalDateTime.now() }
    val recoverableSession = remember(sessions, dismissedRecoverySessionIds, nowForRecovery) {
        com.example.medvoicetrainer.db.SessionRecovery.firstRecoverable(
            sessions = sessions,
            dismissedIds = dismissedRecoverySessionIds,
            now = nowForRecovery,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        // This must be content padding, not modifier padding. Modifier.padding(bottom = 96.dp)
        // shrinks the LazyColumn's viewport and leaves a permanent blank band above the navigation
        // bar, which also makes cards at the bottom appear clipped. Content padding preserves the
        // full viewport while leaving enough scroll-out room for the Home FAB.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            if (recoverableSession != null && stage != DashStage.DEMO) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🌙", style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier.weight(1f)) {
                        // "from 14:30" is unreadable for anything but the last few minutes — it
                        // can't say whether that was this afternoon or yesterday's. Within the
                        // recovery window an elapsed time answers the only question the learner
                        // actually has ("is this the one I just lost?").
                        Text(
                            t("Session interrupted") + " " +
                                recoveryAgeLabel(recoverableSession, nowForRecovery, t),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "\"${recoverableSession.caseName}\" — " + t("dashboard.recovery_card_note"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // One decision, not two. The former "Resume" sibling re-opened a paid live
                    // socket on a conversation the learner had already left; getting feedback on
                    // what was actually said is the whole point of recovering the transcript.
                    TextButton(onClick = { viewModel.retrySessionAnalysis(recoverableSession) }) {
                        Text(t("Analyze"))
                    }
                    IconButton(
                        onClick = { viewModel.dismissRecoverySessionCard(recoverableSession.id) }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = t("checkin.dismiss")
                        )
                    }
                }
            }
            }
        }
        // --- Welcome Header ---
        item {
            val greetingKey = remember {
                val now = java.time.LocalDateTime.now()
                timeOfDayGreetingKey(now.hour, now.dayOfYear)
            }
            Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = t(greetingKey),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (stage) {
                        DashStage.NEW -> t("Let's get your first case in")
                        DashStage.DEMO -> t("Demo tour — scripted practice patients")
                        else -> t("session.ready_for_session")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            // Trailing badge — a live streak once earned; a neutral DEMO tag while touring; nothing
            // for a brand-new user (a "0 Days" streak is discouraging, not motivating).
            if (stage == DashStage.DEMO) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = t("DEMO"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else if (streakCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = t("Streak"),
                            tint = MaterialTheme.colorScheme.onTertiary
                        )
                        Text(
                            text = if (streakCount == 1) "1 Day" else "$streakCount Days",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            }
        }

        val launch = latestPresentationLaunch
        if (launch != null && stage != DashStage.DEMO && !presentationNudgeDismissed) {
            item {
                PresentationNudge(
                    patientTitle = launch.title,
                    onPresent = {
                        com.example.medvoicetrainer.analysis.Telemetry.track(
                            "team_communication_started",
                            mapOf("surface" to "home_nudge", "task" to "attending_presentation")
                        )
                        presentationNudgeDismissed = true
                        onStartCase(launch.id, launch.title, launch.caseJson)
                    },
                    onDismiss = { presentationNudgeDismissed = true },
                )
            }
        }

        // --- Stage-specific hero (replaces the mission/SRS pair for NEW & DEMO users) ---
        when (stage) {
            DashStage.DEMO -> item {
                DemoTourHero(
                    done = demoProgress.first,
                    total = demoProgress.second,
                    tourComplete = demoComplete,
                    nextCase = nextDemoCase,
                    onPlayNext = { nextDemoCase?.let { viewModel.startDemoSession(it.id) } },
                    onOpenSettings = onOpenSettings
                )
            }
            DashStage.NEW -> {
                // §B2 (early-UX): the generic guided path opens with a "Try a no-API demo" step, but
                // a key-entered learner (the only kind in the NEW stage) runs everything live — that
                // label and route are wrong for them. Skip the demo step so the first guided rep is
                // the gentle live rapport case instead of a mislabeled live "demo".
                val heroStep = guidedPath.nextStep?.let { next ->
                    if (next.key == "demo") {
                        guidedPath.steps.firstOrNull { it.key != "demo" && !it.completed } ?: next
                    } else {
                        next
                    }
                }
                item {
                    GetStartedHero(
                        guidedStepTitle = heroStep?.title,
                        onStartGuided = { heroStep?.let { startGuidedStep(it) } },
                        onBrowsePractice = { onNavigateToTab(1) }
                    )
                }
            }
            else -> Unit
        }

        // Already had this conversation somewhere else (ChatGPT/Gemini live mode, on your
        // subscription)? Import it instead of re-doing it here — see
        // docs/DIFFERENTIATION_ANSWERS.md Q1. This is a one-time nudge, not a permanent Home
        // fixture: it can be dismissed with the ✕, after which the feature lives in
        // Preferences → Import (see PreferencesScreen). Hidden entirely once dismissed.
        if (!importCardDismissed) {
            item {
            Card(
                onClick = onOpenImport,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t("dashboard.import_card_title"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = t("dashboard.import_card_body"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ✕ dismiss — tucked into the card's trailing edge, tab-close style. Removes the
                    // card from Home for good; import stays available under Preferences.
                    IconButton(onClick = { viewModel.setImportCardDismissed(true) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = t("dashboard.import_card_dismiss"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }

        if (showActions) {
        item {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- Daily Mission Card ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Assignment,
                        contentDescription = t("Mission"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = t("DAILY ADAPTIVE MISSION"),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = mission.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = mission.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                mission.basis?.let { basis ->
                    Spacer(modifier = Modifier.height(8.dp))
                    MissionBasisNote(basis)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (mission.priority == "srs_review") {
                            onNavigateToTab(2) // Navigate to SRS tab
                        } else {
                            startMissionRoute(mission.route, mission.title)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mission.cta.uppercase())
                        Icon(Icons.Default.PlayArrow, contentDescription = t("Start"))
                    }
                }
            }
        }

        // Fast entry by real ward task, independent of the adaptive learning taxonomy.
        ClinicalCommunicationQuickStarts(
            onStartSbar = {
                coroutineScope.launch {
                    startRouteCase("encounter", "drills", "handover_night_shift", null, "SBAR phone handover")
                }
            },
            onStartConsult = {
                coroutineScope.launch {
                    startRouteCase("encounter", "team_communication", "consult_cardio_stable", null, "Request a consult")
                }
            },
            onStartReadBack = {
                coroutineScope.launch {
                    startRouteCase("encounter", "drills", "drill_clarify_orders", null, "Clarify and read back")
                }
            },
        )

        // --- Pending SRS Card ---
        // De-duplicated against the mission above: when today's adaptive mission IS the SRS review
        // (same due count, same "go to SRS" action), a second red card repeating that count is just
        // noise — the top complaint about this screen. So this card appears only when there's
        // something due AND the mission is pointing somewhere else; the empty "nothing pending"
        // state is dropped from Home entirely (it added a card without adding information).
        if (dueReviews > 0 && mission.priority != "srs_review") {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t("SPACED REPETITION (SRS)"),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$dueReviews " + t("Speech Mistakes Pending"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { onNavigateToTab(2) }, // Navigate to SRS Screen
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(t("PRACTICE"))
                }
            }
        }
        }

        // §B1 (early-UX): running weekly API-spend for live (own-key) learners. Condensed to a
        // single slim line — the amount is reassurance a novice can glance at, not a headline that
        // earns a full three-line card competing with the mission for attention.
        if (isLiveBackend) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = t("early.weekly_cost_label"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = (if (weeklyCostEstimated) t("Estimated") + " " else "") +
                        "$" + String.format(java.util.Locale.US, "%.2f", weeklyCostUsd),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        } // end action group column
        }
        } // end showActions

        // The analytics wall is always one tap away, never in the way — and it is no longer one
        // wall. Each of the three groups below is independently collapsible and carries its own
        // headline figure while shut, so a learner can read the answer they arrived for without
        // dragging any cards on screen at all. Per-group defaults are set with the state above.
        if (showInsights && hasPathwayGroup) item {
            InsightsGroup(
                title = t("feedback.next_practice_tab"),
                subtitle = t("dashboard.group.pathway_note"),
                value = pathwayValue,
                hintSeen = INSIGHT_GROUP_PATHWAY in openedInsightGroups,
                icon = Icons.Default.Route,
                expanded = pathwayExpanded,
                onToggle = {
                    pathwayExpanded = !pathwayExpanded
                    if (pathwayExpanded) viewModel.markInsightGroupOpened(INSIGHT_GROUP_PATHWAY)
                }
            ) {
                // --- Learning Roadmap (app/analysis/learning_roadmap.py's build_us_clinical_english_roadmap) ---
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t("Clinical English Roadmap"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The engine ranks four routes; Home shows the two it ranked highest.
                        // Each card is a four-line block (status, title, reason, CTA), so all four
                        // was sixteen lines of "what to do next" stacked directly under a mission
                        // card that had already answered the question — the single densest patch
                        // of prose on this screen. The rest of the ranking is not lost: it is the
                        // Practice tab, which is where choosing between routes belongs.
                        roadmapCards.take(HOME_ROADMAP_CARDS).forEach { card ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { startRoadmapRoute(card.route, card.title) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = card.status,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = card.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = card.reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = card.cta,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }

        // "Where I stand" and "am I improving" were two groups; they are one question with two
        // tenses, so they are one group. Ordered the way the question is actually asked: what am I
        // now (level) → what changed this week (confidence, progress, error-rate trend) → the slow
        // cumulative meter (vocabulary).
        if (showInsights) item {
            InsightsGroup(
                title = t("dashboard.group.standing"),
                subtitle = t("dashboard.group.standing_note"),
                value = standingValue,
                hintSeen = INSIGHT_GROUP_STANDING in openedInsightGroups,
                icon = Icons.Default.Assessment,
                expanded = standingExpanded,
                onToggle = {
                    standingExpanded = !standingExpanded
                    if (standingExpanded) viewModel.markInsightGroupOpened(INSIGHT_GROUP_STANDING)
                }
            ) {
                // --- Diagnostic Profile (app/analysis/diagnostic.py) ---
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t("Diagnostic Profile"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val diagnosed = diagnosticProfile["has_profile"] == true
                            Text(
                                text = diagnosticProfile["level"]?.toString() ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            // The engine's summary reads "Estimated level: <level>. Overall
                            // practice score <n>/10." — for a diagnosed learner its first half is
                            // the line directly above it, printed twice. Keep only the half that
                            // adds something. Undiagnosed, the summary is a genuine call to action
                            // ("run the 10-minute diagnostic"), so it stays whole.
                            if (diagnosed) {
                                Text(
                                    text = t("Overall") + " ${diagnosticProfile["overall"] ?: "—"}/10",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = diagnosticProfile["summary"]?.toString() ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            @Suppress("UNCHECKED_CAST")
                            val priorities = diagnosticProfile["priorities"] as? List<String> ?: emptyList()
                            // Three ranked priorities on a Home card is a to-do list nobody reads
                            // past the first item; the weakest two are the ones that change what
                            // the learner practises next.
                            priorities.take(HOME_DIAGNOSTIC_PRIORITIES).forEach { priority ->
                                Text(text = "• $priority", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // The "am I improving?" half of this group — formerly a group of its own. Renders
                // nothing when there is no week to compare against, so no empty header appears.
                ProgressCards(
                    totalAnalyzed = totalAnalyzed,
                    thisWeekTrend = thisWeekTrend,
                    confidenceTrend = confidenceTrend,
                    masteredThisWeek = masteredThisWeek,
                    streakCount = streakCount,
                    errorStats = errorStats,
                    progressReport = progressReport,
                    errorTrend = errorTrend,
                )

                // --- OET Layman Term Vocabulary Coverage (app/analysis/vocabulary_tracker.py) ---
                // Ported from dashboard_tab.py's _refresh_vocabulary: was previously a hardcoded
                // 5-word list unconnected to any real usage tracking — fixed this round.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t("OET Layman Vocab Dashboard"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        // Was the longest thing on Home by a distance: a count, a bar, then two
                        // headed lists of up to five two-column rows — twelve-plus lines, most of
                        // them a jargon gloss the learner is not reading on a dashboard. The meter
                        // is the point; the words are examples of what the meter means. Three of
                        // each, on one line each, keeps that meaning at a fifth of the height.
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${vocabCoverage.usedCount} / ${vocabCoverage.totalCount} (${vocabCoverage.percentage}%)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            LinearProgressIndicator(
                                progress = { (vocabCoverage.percentage / 100.0).toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                            // Newest first: these are the words this learner has just started
                            // reaching for, which is the half of the meter worth celebrating.
                            val recentlyUnlocked = vocabCoverage.unlocked.asReversed()
                                .take(HOME_VOCAB_TERMS)
                                .joinToString(" · ") { it.laymanTerm }
                            if (recentlyUnlocked.isNotEmpty()) {
                                VocabTermLine(
                                    label = t("Unlocked"),
                                    terms = recentlyUnlocked,
                                    labelColor = MaterialTheme.colorScheme.secondary
                                )
                            }
                            val stillLocked = vocabCoverage.locked
                                .take(HOME_VOCAB_TERMS)
                                .joinToString(" · ") { it.laymanTerm }
                            if (stillLocked.isNotEmpty()) {
                                VocabTermLine(
                                    label = t("Still locked"),
                                    terms = stillLocked,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Keep this group visible even before corrections have accumulated. Hiding it made the
        // dashboard appear to have only two insight groups and gave learners no indication of
        // where their future error patterns will be surfaced.
        if (showInsights) item {
            InsightsGroup(
                title = t("dashboard.group.weak_spots"),
                subtitle = t("dashboard.group.weak_spots_note"),
                value = weakSpotsValue,
                hintSeen = INSIGHT_GROUP_WEAK_SPOTS in openedInsightGroups,
                collapsedSummary = weakSpotsSummary,
                icon = Icons.Default.TrackChanges,
                expanded = weakSpotsExpanded,
                onToggle = {
                    weakSpotsExpanded = !weakSpotsExpanded
                    if (weakSpotsExpanded) viewModel.markInsightGroupOpened(INSIGHT_GROUP_WEAK_SPOTS)
                }
            ) {
                // --- Mistake Genome (app/analysis/mistake_genome.py) ---
                // Ported from dashboard_tab.py's _refresh_mistake_genome. The engine also returns a
                // headline, which this card no longer prints — see the note inside on why.
                if (mistakeGenomeReport.totalItems > 0) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = t("Your common mistakes"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                // The engine's headline is "Dominant pattern: <label>" — the same
                                // label the very next row prints, with a percentage attached, and
                                // now the same one on this group's collapsed header too. Three
                                // copies of one fact. The ranked rows are the better telling, so
                                // only the advice line (the one thing here that says what to *do*)
                                // survives above them.
                                Text(
                                    text = mistakeGenomeReport.advice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                mistakeGenomeReport.topCategories.take(3).forEach { pattern ->
                                    // The share is the standing profile; the adjacent movement is
                                    // how that share changed in the current seven-day window.
                                    val movement = mistakeGenomeTrend.categories
                                        .firstOrNull { it.category == pattern.category }
                                        ?.takeIf {
                                            mistakeGenomeTrend.hasPriorWindow &&
                                                it.shareDeltaPoints != 0.0
                                        }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pattern.label,
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            movement?.let { trend ->
                                                Text(
                                                    text = String.format(
                                                        java.util.Locale.US,
                                                        "%s%.1f%%p",
                                                        if (trend.improved) "▼" else "▲",
                                                        kotlin.math.abs(trend.shareDeltaPoints)
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (trend.improved) {
                                                        com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                                                    } else {
                                                        com.example.medvoicetrainer.ui.theme.DangerRedStrong
                                                    }
                                                )
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.errorContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "${pattern.percentage}%",
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                    }
                                }
                                if (mistakeGenomeTrend.hasData) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (mistakeGenomeTrend.hasPriorWindow) {
                                            t("dashboard.genome_window_delta")
                                                .replace("{days}", mistakeGenomeTrend.windowDays.toString())
                                                .replace("{recent}", mistakeGenomeTrend.recentTotal.toString())
                                                .replace("{prior}", mistakeGenomeTrend.priorTotal.toString())
                                        } else {
                                            t("dashboard.genome_window_first")
                                                .replace("{days}", mistakeGenomeTrend.windowDays.toString())
                                                .replace("{recent}", mistakeGenomeTrend.recentTotal.toString())
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Where your English errors cluster (app/analysis/l1_stats.py) ---
                // Ported from dashboard_tab.py's _draw_categories: a per-category frequency chart of
                // tagged corrections (top 8), distinct from the SRS-backed "common mistakes" card above.
                if (errorProfiles.isNotEmpty()) {
                    // Eight labelled bars is a chart nobody reads to the bottom of; the tail is
                    // single-digit counts that change nothing about what to practise.
                    val topCategories = errorProfiles.entries
                        .sortedByDescending { it.value.count }
                        .take(HOME_ERROR_CLUSTERS)
                    val maxCount = topCategories.maxOf { it.value.count }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = t("dashboard.confirmed_error_patterns"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = t("dashboard.confirmed_error_patterns_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                topCategories.forEach { (key, profile) ->
                                    val label = com.example.medvoicetrainer.analysis.L1Stats.CATEGORY_LABELS[key] ?: key
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = if (profile.opportunities > 0) {
                                                    "${profile.count}/${profile.opportunities} · " +
                                                        String.format(
                                                            java.util.Locale.US,
                                                            "%.1f%%",
                                                            profile.errorRatePercent ?: 0.0
                                                        )
                                                } else {
                                                    "${profile.count} · " +
                                                        String.format(
                                                            java.util.Locale.US,
                                                            "%.1f/100 words",
                                                            profile.per100Words
                                                        )
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(
                                                        if (maxCount > 0) {
                                                            profile.count.toFloat() / maxCount
                                                        } else {
                                                            0f
                                                        }
                                                    )
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!hasWeakSpotsGroup) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = t("error_tracker.no_mistakes"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = t(
                                    "Not enough data yet — finish a few more practice sessions and " +
                                        "I'll build a personalised drill from your specific mistakes."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun PresentationNudge(
    patientTitle: String,
    onPresent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    LaunchedEffect(patientTitle) {
        com.example.medvoicetrainer.analysis.Telemetry.track(
            "team_communication_exposed",
            mapOf("surface" to "home_nudge", "task" to "attending_presentation")
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t("Ready to present this case?"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    patientTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onPresent) { Text(t("Present")) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = t("Dismiss"))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ClinicalCommunicationQuickStarts(
    onStartSbar: () -> Unit,
    onStartConsult: () -> Unit,
    onStartReadBack: () -> Unit,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        com.example.medvoicetrainer.analysis.Telemetry.track(
            "team_communication_exposed",
            mapOf("surface" to "home", "task" to "quick_starts")
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(t("Ward communication"), fontWeight = FontWeight.Bold)
                    Text(
                        t("SBAR, consult, and order read-back shortcuts"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) t("Collapse") else t("Expand"),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider()
                    // These actions must remain fully readable when the device uses a large
                    // font scale. A weighted Row forces all three buttons into one line, which
                    // clips the last label instead of offering any horizontal scroll. FlowRow
                    // keeps the compact single-row layout where it fits and wraps safely where
                    // it does not.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onStartSbar) {
                            Text(t("SBAR"), maxLines = 1)
                        }
                        OutlinedButton(onClick = onStartConsult) {
                            Text(t("Consult"), maxLines = 1)
                        }
                        OutlinedButton(onClick = onStartReadBack) {
                            Text(t("Read-back"), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three "am I moving?" cards — this week's confidence, the progress report, and the per-category
 * error-rate trend.
 *
 * Extracted from the dashboard body when "my level" and "progress" merged into one group: they are
 * the back half of that group's content, and inlining ~150 lines of cards into an already long
 * screen body was the reason the two were separate groups in the first place. Renders nothing at
 * all when it has no data, so the caller can include it unconditionally.
 */
@Composable
private fun ProgressCards(
    totalAnalyzed: Int,
    thisWeekTrend: com.example.medvoicetrainer.analysis.ConfidenceWindow?,
    confidenceTrend: com.example.medvoicetrainer.analysis.ConfidenceTrend,
    masteredThisWeek: Int,
    streakCount: Int,
    errorStats: com.example.medvoicetrainer.analysis.ErrorTrackerStats.Stats,
    progressReport: com.example.medvoicetrainer.analysis.ProgressReport,
    errorTrend: List<com.example.medvoicetrainer.analysis.L1Stats.CategoryTrend>,
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Confidence This Week (app/ui/dashboard_tab.py's _refresh_confidence_card) ---
        if (totalAnalyzed > 0 && thisWeekTrend != null) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = t("Confidence This Week"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val minutesText = if (thisWeekTrend.totalSpeakingMinutes > 0) Math.round(thisWeekTrend.totalSpeakingMinutes).toString() else "—"
                    Text(
                        text = t("You spoke") + " $minutesText " + t("min across") + " ${thisWeekTrend.sessionCount} " + t("session(s) this week."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val wpmDelta = com.example.medvoicetrainer.analysis.ConfidenceTrendEngine.pctDelta(
                        thisWeekTrend.avgWpm, confidenceTrend.priorWeek?.avgWpm
                    )
                    val fillerDelta = com.example.medvoicetrainer.analysis.ConfidenceTrendEngine.pctDelta(
                        thisWeekTrend.avgFillerRate, confidenceTrend.priorWeek?.avgFillerRate, lowerIsBetter = true
                    )
                    val deltaParts = mutableListOf<String>()
                    if (wpmDelta != null && wpmDelta > 0) deltaParts.add(t("words/min ↑") + " $wpmDelta%")
                    if (fillerDelta != null && fillerDelta > 0) deltaParts.add(t("hesitation ↓") + " $fillerDelta%")
                    if (deltaParts.isNotEmpty()) {
                        Text(
                            text = deltaParts.joinToString("   "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (masteredThisWeek > 0) {
                        Text(
                            text = t("Errors graduated this week:") + " $masteredThisWeek",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            val bitmap = com.example.medvoicetrainer.export.ShareCardImage.renderWeeklyCard(
                                sessionCount = thisWeekTrend.sessionCount,
                                totalSpeakingMinutes = thisWeekTrend.totalSpeakingMinutes,
                                avgWpm = thisWeekTrend.avgWpm,
                                streak = streakCount,
                                avgFillerRate = thisWeekTrend.avgFillerRate,
                                mastered = errorStats.mastered,
                                active = errorStats.active
                            )
                            shareBitmapCard(context, bitmap, "weekly_confidence_card.png", t("Share your weekly progress"))
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(t("Share"))
                    }
                }
            }
        }

        // --- Progress Report (app/analysis/progress_report.py) ---
        if (progressReport.hasData) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = t("Progress Report"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = progressReport.headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = progressReport.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Headline, summary and an unbounded bullet list is three passes
                        // over the same story. The bullets are the specifics, so the first
                        // two survive and the tail goes; the full report is in History.
                        progressReport.bullets.take(HOME_PROGRESS_BULLETS).forEach { bullet ->
                            Text(text = "• $bullet", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // --- Error-rate trend card (its windowing is computed with errorTrend above) ---
        if (errorTrend.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = t("dashboard.error_trend_title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = t("dashboard.error_trend_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        errorTrend.forEach { trend ->
                            val label = com.example.medvoicetrainer.analysis.L1Stats.CATEGORY_LABELS[trend.category] ?: trend.category
                            val arrow = when {
                                trend.improved -> "▼"
                                trend.delta > 0.0 -> "▲"
                                else -> "→"
                            }
                            val arrowColor = when {
                                trend.improved -> com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                                trend.delta > 0.0 -> com.example.medvoicetrainer.ui.theme.DangerRedStrong
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    text = String.format(
                                        java.util.Locale.US,
                                        "%.1f → %.1f /100w %s",
                                        trend.previousPer100,
                                        trend.recentPer100,
                                        arrow
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = arrowColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One line of the vocabulary meter: a heading and its example terms, inline rather than stacked.
 *
 * The terms used to be one two-column row each, with the clinical jargon on the right. That gloss
 * belongs in the wordlist, not on a dashboard whose job here is to show a meter moving.
 */
@Composable
private fun VocabTermLine(label: String, terms: String, labelColor: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = terms,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The mission card's "why this one" line: the engine's selection rule, folded away by default.
 *
 * Collapsed it is one quiet row — a rank line the learner can read in a glance without the card
 * growing. Expanded it gives the actual rule (due items first, your own commitment next, weakest
 * score, then a dated rotation), because the rotation branch is the one people read as random when
 * it is a fixed five-day cycle. Deliberately not a button: the mission's own CTA stays the only
 * thing on this card that looks tappable-to-start.
 */
@Composable
private fun MissionBasisNote(basis: com.example.medvoicetrainer.analysis.MissionBasis) {
    var expanded by rememberSaveable(basis.label) { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = tint.copy(alpha = 0.07f),
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button) { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = tint.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = basis.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = tint.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = basis.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 4.dp)
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------------------- */
/* Stage-specific hero cards + collapsible insight groups (redesign additions).          */
/* ------------------------------------------------------------------------------------- */

/** DEMO stage hero: speaks tour-progress truth, never a clinical mission it can't deliver. */
@Composable
private fun DemoTourHero(
    done: Int,
    total: Int,
    tourComplete: Boolean,
    nextCase: com.example.medvoicetrainer.analysis.DemoCaseInfo?,
    onPlayNext: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (tourComplete) {
                    t("dash.demo_finished_label")
                } else {
                    t("dash.demo_tour_label")
                        .replace("{n}", done.coerceAtMost(total).toString())
                        .replace("{total}", total.toString())
                },
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            // Progress dots.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(total) { i ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (i < done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!tourComplete && nextCase != null) {
                Text(
                    text = t("dash.demo_next_label").replace("{patient}", "${nextCase.emoji} ${nextCase.label}"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = nextCase.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onPlayNext,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("dash.demo_next_cta"), fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = t("demo_next.complete_title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = t("dash.demo_finished"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("dash.demo_unlock_cta"), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onPlayNext) { Text(t("dash.demo_replay")) }
                // §13 "Demo tour exhausted": "decide later" must not mean "nothing to do" —
                // Survival English practice keeps working keyless even after the tour ends.
                Text(
                    t("dash.demo_survival_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** NEW stage hero: one clear first action + a three-step "how it works", nothing else. */
@Composable
private fun GetStartedHero(
    guidedStepTitle: String?,
    onStartGuided: () -> Unit,
    onBrowsePractice: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = t("dash.get_started.start_here"),
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = t("dash.get_started.ready_title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            HowItWorksStep("1", t("dash.get_started.step1"))
            HowItWorksStep("2", t("dash.get_started.step2"))
            HowItWorksStep("3", t("dash.get_started.step3"))
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = if (guidedStepTitle != null) onStartGuided else onBrowsePractice,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (guidedStepTitle != null) t("dash.get_started.start_guided").replace("{step}", guidedStepTitle.uppercase()) else t("dash.get_started.browse_cases"),
                    fontWeight = FontWeight.Bold
                )
            }
            if (guidedStepTitle != null) {
                TextButton(onClick = onBrowsePractice, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(t("dash.get_started.browse_all"))
                }
            }
            // §1 (early-UX): a key-entered learner skips the scripted demo and lands straight in a
            // live, unscripted voice encounter — the hardest possible first rep. Name the typing
            // escape hatch up front so speaking anxiety doesn't turn into a first-session abandon.
            Spacer(Modifier.height(6.dp))
            Text(
                text = t("early.hero_type_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun HowItWorksStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * One themed, independently collapsible analytics group (pathway / standing / weak spots).
 *
 * Replaces the single "Insights & analytics" toggle that used to gate all nine cards at once: a
 * learner opening "am I improving?" no longer has to scroll past the roadmap, the vocab meter and
 * two error breakdowns to reach it.
 *
 * The collapsed header is where this screen earns its keep. It carries [value] — the one live
 * number the group is about — so the common case is answered *without opening anything*. That is
 * the actual fix for a Home screen that reads as too wordy: not tidier cards behind the toggle,
 * but not needing to open the toggle at all.
 *
 * [subtitle] names the question the group answers. It is onboarding for a learner facing several
 * shut groups, so it shows only until they have opened this one ([hintSeen]), and never at the
 * same time as [value] — a header gets one supporting line, never two.
 */
@Composable
private fun InsightsGroup(
    title: String,
    subtitle: String,
    value: String?,
    hintSeen: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** A live finding that replaces the generic onboarding hint while collapsed. */
    collapsedSummary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    // §14 "Motion": Compose doesn't read the system's "Remove animations" setting on its own, so
    // this expand/fade becomes an instant cut when it's on.
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // A live conclusion earns its row even after onboarding; otherwise the
                    // beginner hint disappears once the learner has opened this group.
                    if (!expanded && !collapsedSummary.isNullOrBlank()) {
                        Text(
                            text = collapsedSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!expanded && !hintSeen) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // The headline number rides on the header itself, between title and chevron, so a
            // shut group still reports. Hidden while open, where the cards say it better.
            if (!expanded && collapsedSummary.isNullOrBlank() && !value.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = value,
                        // Capped so a long category name can never squeeze the group's own title.
                        // Every caller puts the number first for the same reason: truncation eats
                        // the label tail, never the figure the learner came for.
                        modifier = Modifier
                            .widthIn(max = 132.dp)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) t("Collapse") else t("Expand"),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reducedMotion) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = if (reducedMotion) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}
