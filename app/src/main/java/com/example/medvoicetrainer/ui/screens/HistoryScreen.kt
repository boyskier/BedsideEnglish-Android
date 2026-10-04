package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.toAnalysisMap
import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.export.ShareReport
import com.example.medvoicetrainer.ui.CorrectionDecision
import com.example.medvoicetrainer.ui.EvaluationResult
import com.example.medvoicetrainer.ui.HistoryCorrectionRecord
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.correctionDecisionOf
import com.example.medvoicetrainer.ui.SoapNoteText
import com.example.medvoicetrainer.ui.formatSoapForDisplay
import com.example.medvoicetrainer.ui.parsedHistoryCorrections
import com.example.medvoicetrainer.ui.toEvaluationResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import android.content.Intent

internal enum class HistoryModeFilter(val label: String, val modeKey: String?) {
    ALL("All", null),
    ENCOUNTER("Encounter", "encounter"),
    FOLLOW_UP("Follow-up", "follow_up"),
    TEAM_COMMUNICATION("Team communication", "team_communication"),
    NURSING("Nursing", "nursing"),
    PRESENTATION("Presentation", "presentation"),
    EXAM("Exam", "exam"),
    SURVIVAL("Survival", "survival"),
    INTERVIEW("Interview", "interview"),
    TEACHBACK("Teachback", "teachback"),
    LOUNGE("Lounge", "lounge"),
    CUSTOM("Custom", "custom")
}

// historyModeKey runs this for up to three fixed keys on every session in the list, and again
// for each rendered row, and it used to compile a fresh Regex on every one of those calls. The
// key set is closed, so each pattern is built once and reused.
private val JSON_STRING_FIELD_PATTERNS = HashMap<String, Regex>()

private fun jsonStringField(rawJson: String, key: String): String? {
    val pattern = synchronized(JSON_STRING_FIELD_PATTERNS) {
        JSON_STRING_FIELD_PATTERNS.getOrPut(key) {
            Regex(
                "\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"",
                RegexOption.IGNORE_CASE
            )
        }
    }
    return pattern.find(rawJson)?.groupValues?.getOrNull(1)
}

internal fun historyModeKey(session: SessionEntity): String {
    val storedMode = session.mode.trim().lowercase(Locale.ROOT)
    if (storedMode != "encounter") return storedMode

    val caseId = session.caseId.orEmpty().trim().lowercase(Locale.ROOT)
    if (caseId == "custom_case" || caseId.startsWith("custom_")) return "custom"
    if (caseId.startsWith("lounge_")) return "lounge"

    return when {
        jsonStringField(session.rawCaseJson, "system").equals("lounge", ignoreCase = true) -> "lounge"
        jsonStringField(session.rawCaseJson, "eval_template").equals("lounge", ignoreCase = true) -> "lounge"
        jsonStringField(session.rawCaseJson, "id").equals("custom_case", ignoreCase = true) -> "custom"
        else -> storedMode
    }
}

internal fun filterHistorySessions(
    sessions: List<SessionEntity>,
    caseNameQuery: String,
    modeFilter: HistoryModeFilter
): List<SessionEntity> {
    val query = caseNameQuery.trim()
    return sessions.filter { session ->
        val matchesName = query.isEmpty() || session.caseName.contains(query, ignoreCase = true)
        val matchesMode = modeFilter.modeKey == null || historyModeKey(session) == modeFilter.modeKey
        matchesName && matchesMode
    }
}

/**
 * §13 "Analysis fails / quota (429)": there's no explicit "analyzed" column, so a session whose
 * evaluation never came back is detected the same way it manifests — no summary was ever
 * written for it (a genuinely blank/never-run summary vs. a model that returned literally
 * nothing, which finishSession's failure path also never populates).
 */
internal fun isUnanalyzedSession(session: SessionEntity): Boolean = session.summaryFeedback.isNullOrBlank()

// Everyday rows keep naturalness/interaction/repair/fluency in the clinical-named columns and
// mirror fluency into professionalism, so only four columns count — the same overall score
// Feedback (overallScoreOf) and SessionReflection show for that session.
internal fun averageSessionScore(session: SessionEntity): Double = if (session.analysisDomain == "everyday") {
    listOf(
        session.grammarScore,
        session.medicalAccuracyScore,
        session.clinicalReasoningScore,
        session.fluencyScore
    )
} else {
    listOf(
        session.grammarScore,
        session.medicalAccuracyScore,
        session.clinicalReasoningScore,
        session.professionalismScore,
        session.fluencyScore
    )
}.average()

internal fun trendDeltaOf(scores: List<Double>): Double {
    if (scores.size < 2) return 0.0
    val half = (scores.size / 2).coerceAtLeast(1)
    val earlierAvg = scores.take(half).average()
    val laterAvg = scores.takeLast((scores.size - half).coerceAtLeast(1)).average()
    return laterAvg - earlierAvg
}

internal data class SessionMetricDelta(val label: String, val delta: Double)

internal data class SessionAttemptComparison(
    val attemptNumber: Int,
    val totalAttempts: Int,
    val previousCreatedAt: String?,
    val overallDelta: Double?,
    val metricDeltas: List<SessionMetricDelta>
)

internal fun buildSessionAttemptComparison(
    current: SessionEntity,
    sessions: List<SessionEntity>
): SessionAttemptComparison? {
    val caseId = current.caseId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val attempts = (sessions + current)
        .filter { it.caseId?.trim() == caseId }
        .distinctBy { it.id }
        .sortedWith(compareBy<SessionEntity> { it.createdAt }.thenBy { it.id })
    val currentIndex = attempts.indexOfFirst { it.id == current.id }
    if (currentIndex < 0) return null
    val previous = attempts.getOrNull(currentIndex - 1)
        ?: return SessionAttemptComparison(currentIndex + 1, attempts.size, null, null, emptyList())

    val currentMetrics = listOf(
        "Grammar" to current.grammarScore,
        "Accuracy" to current.medicalAccuracyScore,
        "Reasoning" to current.clinicalReasoningScore,
        "Professionalism" to current.professionalismScore,
        "Fluency" to current.fluencyScore
    )
    val previousMetrics = listOf(
        previous.grammarScore,
        previous.medicalAccuracyScore,
        previous.clinicalReasoningScore,
        previous.professionalismScore,
        previous.fluencyScore
    )
    return SessionAttemptComparison(
        attemptNumber = currentIndex + 1,
        totalAttempts = attempts.size,
        previousCreatedAt = previous.createdAt,
        overallDelta = averageSessionScore(current) - averageSessionScore(previous),
        metricDeltas = currentMetrics.mapIndexed { index, (label, score) ->
            SessionMetricDelta(label, score - previousMetrics[index])
        }
    )
}

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onNavigateToTab: (Int) -> Unit,
    onStartCase: (String, String, String) -> Unit,
    onOpenPhraseDrill: (List<com.example.medvoicetrainer.analysis.EverydayPhrase>) -> Unit = {},
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val backgroundAnalysisSessionIds by viewModel.backgroundAnalysisSessionIds.collectAsStateWithLifecycle()
    val apiUsageEvents by viewModel.apiUsageEvents.collectAsStateWithLifecycle()
    var selectedSession by remember { mutableStateOf<SessionEntity?>(null) }
    var pendingTrashSession by remember { mutableStateOf<SessionEntity?>(null) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var caseNameQuery by rememberSaveable { mutableStateOf("") }
    var modeFilter by rememberSaveable { mutableStateOf(HistoryModeFilter.ALL) }
    val visibleSessions = remember(sessions, caseNameQuery, modeFilter) {
        filterHistorySessions(sessions, caseNameQuery, modeFilter)
    }
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current

    var showCostAnalytics by remember { mutableStateOf(false) }
    if (showCostAnalytics) {
        androidx.activity.compose.BackHandler { showCostAnalytics = false }
        ApiCostAnalyticsScreen(
            sessions = sessions,
            apiUsageEvents = apiUsageEvents,
            onClose = { showCostAnalytics = false }
        )
        return
    }

    if (selectedSession != null) {
        val sessionToShow = selectedSession!!
        SessionDetailScreen(
            session = sessionToShow,
            allSessions = sessions,
            viewModel = viewModel,
            onClose = { selectedSession = null },
            onOpenPhraseDrill = { phrases ->
                selectedSession = null
                onOpenPhraseDrill(phrases)
            },
            onPresentToAttending = { session ->
                // Ported from history_tab.py's "Present to Attending" flow via
                // PresentationBuilder.kt (was dead code — nothing built the real persona/case
                // summary/transcript-excerpt prompt; this button used to send a bare
                // {"topic":..., "history":...} payload with no persona_override, which fell
                // through to the generic "You are a patient with a chief complaint of general
                // symptoms" template).
                selectedSession = null
                val presentationCase = com.example.medvoicetrainer.analysis.PresentationBuilder
                    .buildPresentationCase(session.toAnalysisMap())
                if (presentationCase != null) {
                    val json = org.json.JSONObject(presentationCase).toString()
                    val id = presentationCase["id"]?.toString() ?: "attending_presentation"
                    val title = presentationCase["patient_name"]?.toString() ?: "Presenting ${session.caseName}"
                    onNavigateToTab(1)
                    onStartCase(id, title, json)
                }
            }
        )
        return
    }

    // The whole tab is one scrollable list — the header, filter bar, and score trend chart used
    // to live outside the session LazyColumn (fixed on screen) while only the row list scrolled
    // underneath it, so short lists left the chart floating over empty space with nothing readable
    // moving. Putting every fixed section in as LazyColumn items lets the entire page scroll
    // together like a normal feed.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = t("menu.history"),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Text(
                text = t("Review your past medical scenarios, check rubrics, and inspect SOAP notes."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        item {
            HistoryFilterBar(
                query = caseNameQuery,
                selectedMode = modeFilter,
                visibleCount = visibleSessions.size,
                totalCount = sessions.size,
                onQueryChange = { caseNameQuery = it },
                onModeChange = { modeFilter = it },
                onClear = {
                    caseNameQuery = ""
                    modeFilter = HistoryModeFilter.ALL
                }
            )
        }

        if (visibleSessions.isNotEmpty()) {
            item { ScoreTrendChart(sessions = visibleSessions) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showCostAnalytics = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("📊 API Cost & Pricing (2026)"))
                }
                TextButton(onClick = {
                    viewModel.loadTrash()
                    showTrashDialog = true
                }) {
                    Text(t("🗑 Trash"))
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = t("No sessions"),
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        Text(
                            text = t("No Sessions Completed Yet"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = t("Go to the Practice tab to start your first clinical history-taking session!"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        // §13 "History: empty" — the same CTA the NEW dashboard leads with, never a
                        // bare list with nothing to do.
                        Button(onClick = { onNavigateToTab(1) }) {
                            Text(t("▶ START: FIRST ENCOUNTER"))
                        }
                    }
                }
            }
        } else if (visibleSessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(t("No sessions match this search and mode filter."))
                        TextButton(onClick = {
                            caseNameQuery = ""
                            modeFilter = HistoryModeFilter.ALL
                        }) {
                            Text(t("history.clear_btn"))
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = t("RECENT SESSIONS"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(visibleSessions, key = { it.id }) { session ->
                SessionHistoryRow(
                    session = session,
                    isAnalyzingInBackground = session.id in backgroundAnalysisSessionIds,
                    onClick = { selectedSession = session },
                    onTrash = { pendingTrashSession = session },
                    onRetryAnalysis = { viewModel.retrySessionAnalysis(session) }
                )
            }
        }
    }

    if (showTrashDialog) {
        TrashDialog(viewModel = viewModel, onDismiss = { showTrashDialog = false })
    }

    pendingTrashSession?.let { session ->
        MoveSessionToTrashDialog(
            session = session,
            onConfirm = {
                if (selectedSession?.id == session.id) selectedSession = null
                viewModel.trashSession(session.id)
                pendingTrashSession = null
            },
            onDismiss = { pendingTrashSession = null }
        )
    }
}

@Composable
private fun HistoryFilterBar(
    query: String,
    selectedMode: HistoryModeFilter,
    visibleCount: Int,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onModeChange: (HistoryModeFilter) -> Unit,
    onClear: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(t("history.search_label")) },
                placeholder = { Text(t("Search case name")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = t("history.clear_btn"))
                        }
                    }
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryModeFilter.entries.filter {
                    // A Nursing chip in a release where the track is hidden would filter to nothing.
                    it != HistoryModeFilter.NURSING || com.example.medvoicetrainer.BuildConfig.NURSING_TRACK_ENABLED
                }.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(t(mode.label)) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$visibleCount / $totalCount " + t("sessions"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (query.isNotEmpty() || selectedMode != HistoryModeFilter.ALL) {
                    TextButton(onClick = onClear) { Text(t("history.clear_btn")) }
                }
            }
        }
    }
}

@Composable
private fun ScoreTrendChart(sessions: List<SessionEntity>) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val points = remember(sessions) {
        sessions
            .filter { !isUnanalyzedSession(it) }
            .sortedBy { it.createdAt }
            .map { it.createdAt to averageSessionScore(it) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("SCORE TREND"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (points.size >= 2) {
                    val delta = trendDeltaOf(points.map { it.second })
                    Text(
                        text = formatScoreDelta(delta) + " " + t("vs. earlier sessions"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (points.size < 2) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = t("Complete a few more analyzed sessions to see your score trend."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val lineColor = MaterialTheme.colorScheme.primary
                val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val scores = points.map { it.second }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    val stepX = size.width / (scores.size - 1).coerceAtLeast(1)
                    fun yFor(score: Double): Float {
                        val ratio = (score / 100.0).coerceIn(0.0, 1.0)
                        return (size.height * (1 - ratio)).toFloat()
                    }

                    listOf(0.0, 50.0, 100.0).forEach { gridScore ->
                        val y = yFor(gridScore)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val linePath = Path()
                    val fillPath = Path()
                    scores.forEachIndexed { index, score ->
                        val x = stepX * index
                        val y = yFor(score)
                        if (index == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, size.height)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo(stepX * (scores.size - 1), size.height)
                    fillPath.close()

                    drawPath(fillPath, color = fillColor)
                    drawPath(
                        linePath,
                        color = lineColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    scores.forEachIndexed { index, score ->
                        drawCircle(
                            color = lineColor,
                            radius = 3.5.dp.toPx(),
                            center = Offset(stepX * index, yFor(score))
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = points.first().first.substringBefore("T"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = points.last().first.substringBefore("T"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveSessionToTrashDialog(
    session: SessionEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("history.trash_title")) },
        text = {
            Text(
                t("history.trash_confirm")
                    .replace("{count}", "1") + "\n\n${session.caseName}"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(t("history.trash_btn")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Cancel")) }
        }
    )
}

@Composable
fun TrashDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val trash by viewModel.trashSessions.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text(t("history.close")) }
        },
        dismissButton = {
            OutlinedButton(onClick = { viewModel.purgeOldTrash(7) }) {
                Text(t("Empty old trash (7d+)"))
            }
        },
        title = {
            Text(
                text = t("🗑 Trash"),
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (trash.isEmpty()) {
                Text(t("Trash is empty."))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(trash) { session ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(session.caseName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    session.createdAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { viewModel.restoreSession(session.id) }) {
                                Text(t("Restore"))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun SessionHistoryRow(
    session: SessionEntity,
    isAnalyzingInBackground: Boolean = false,
    onClick: () -> Unit,
    onTrash: () -> Unit,
    onRetryAnalysis: () -> Unit = {}
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val unanalyzed = isUnanalyzedSession(session)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.caseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("history.col_mode") + ": ${historyModeKey(session).uppercase(Locale.ROOT)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray
                    )
                    Text(
                        text = session.createdAt.substringBefore("T"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!unanalyzed) {
                    val avg = averageSessionScore(session).roundToInt()
                    Surface(
                        color = if (avg >= 85) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "$avg%",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (avg >= 85) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onTrash) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = t("history.trash_btn"),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        // §13 "Analysis fails / quota (429)": the transcript is the asset — scoring can happen
        // later. Never a dead badge with no way forward.
        if (isAnalyzingInBackground) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = t("Analyzing in background"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else if (unanalyzed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = com.example.medvoicetrainer.ui.theme.DangerContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        t("Unanalyzed"),
                        color = com.example.medvoicetrainer.ui.theme.DangerRedStrong,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                if (session.learnerTurnCount > 0) {
                    TextButton(onClick = onRetryAnalysis) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Retry analysis"))
                    }
                }
            }
        }
        }
    }
}

/**
 * Full-fidelity replay of a past session — every section FeedbackScreen shows right after a
 * session ends (scores, corrections, fluency, intelligibility, shadowing, self-assessment,
 * checklist, SOAP, transcript), reconstructed entirely from what [SessionEntity] persisted (see
 * `toEvaluationResult()`/`parsedHistoryCorrections()`). History previously only surfaced five
 * scores + summary + SOAP + transcript in a cramped AlertDialog; everything else the analysis
 * pipeline computed was silently dropped once the live feedback screen closed.
 *
 * Self-assessment and the SOAP note are read-only replays here (submitting either is tied to "the
 * most recently finished session" in MainViewModel and would target the wrong row). Correction
 * triage is not: leaving findings PENDING is the normal outcome of a long report, so History can
 * resume that triage through MainViewModel's session-scoped [HistoryTriageState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: SessionEntity,
    allSessions: List<SessionEntity>,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onPresentToAttending: (SessionEntity) -> Unit,
    onOpenPhraseDrill: (List<com.example.medvoicetrainer.analysis.EverydayPhrase>) -> Unit = {},
) {
    val context = LocalContext.current
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val scope = rememberCoroutineScope()

    val comparison = remember(session.id, allSessions) {
        buildSessionAttemptComparison(session, allSessions)
    }
    val evaluation = remember(session.id) { session.toEvaluationResult() }
    val historyCorrections = remember(session.id) { session.parsedHistoryCorrections() }
    val everyday = evaluation.analysisDomain.equals("everyday", ignoreCase = true)
    val phrasebookAids = remember(session.id, session.rawCaseJson) {
        com.example.medvoicetrainer.analysis.EverydayPhrasebook.aidsFromCaseJson(session.rawCaseJson)
    }

    // Session-scoped triage state, so findings this session left unreviewed can still be routed
    // into the mistake tracker. Cleared on the way out so no later action targets a stale row.
    val triage by viewModel.historyTriage.collectAsStateWithLifecycle()
    DisposableEffect(session.id) {
        viewModel.beginHistoryTriage(session.id, evaluation, historyCorrections)
        onDispose { viewModel.endHistoryTriage() }
    }
    val triageEnabled = triage?.sessionId == session.id
    // Falls back to what was persisted for the one frame before beginHistoryTriage lands.
    val decisions: Map<String, CorrectionDecision> = triage
        ?.takeIf { triageEnabled }
        ?.decisions
        ?: historyCorrections.associate {
            it.correction.decisionKey() to correctionDecisionOf(it.decision)
        }
    val triageStats = remember(historyCorrections, decisions) {
        correctionTriageStats(historyCorrections.map { it.correction }, decisions)
    }
    val acceptedCorrections = remember(historyCorrections, decisions) {
        historyCorrections
            .filter { decisions[it.correction.decisionKey()] == CorrectionDecision.ACCEPTED }
            .map { it.correction }
    }

    val checklistItems = remember(evaluation.checklistResultsJson) {
        parseChecklistResults(evaluation.checklistResultsJson)
    }
    val empathyMarkers = remember(evaluation.empathyMarkersJson) {
        parseStringArray(evaluation.empathyMarkersJson)
    }
    val shadowingItems = remember(acceptedCorrections) {
        acceptedCorrections.take(5).map { ShadowingFeedbackItem(it.original, it.corrected, it.explanation) }
    }
    val sections = remember(everyday) { feedbackSections(everyday) }
    val expansion = rememberFeedbackExpansion(session.id)
    val hasSelfScores = remember(session.id) {
        listOf(
            session.selfGrammar, session.selfMedicalAccuracy, session.selfClinicalReasoning,
            session.selfProfessionalism, session.selfFluency
        ).any { it > 0.0 }
    }

    // Same shared model voice as the practice screens: blank-aware, and stoppable when the
    // replay panel starts recording an attempt.
    val ttsHandle = rememberEnglishTts()
    val ttsReady = ttsHandle.ready
    val speakText: (String) -> Unit = { text -> ttsHandle.speak(text) }
    val speakTarget: (String, Int) -> Unit = { text, _ -> ttsHandle.speak(text) }
    // Live re-record-and-judge practice is stateless (no DB writes tied to lastSessionId), so it's
    // safe to keep wired up even when replaying an older, non-last session.
    val judgeAvailable = remember { viewModel.isSpeakingJudgeAvailable() }
    val judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)? =
        if (judgeAvailable) {
            { target, pcm -> viewModel.judgeSpeakingAttempt(target, pcm) }
        } else {
            null
        }

    androidx.activity.compose.BackHandler { onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(session.caseName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = t("history.close"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "${historyModeKey(session).uppercase(Locale.ROOT)} · ${session.createdAt.substringBefore("T")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ShareCardImage.renderSessionCard() draws exactly this session's de-identified
                // summary as a 1080x1080 PNG; it existed unwired while this button shared only the
                // plain-text version. Send the image, and keep the text card as EXTRA_TEXT so
                // text-only targets (SMS, notes) still receive something readable.
                OutlinedButton(onClick = {
                    scope.launch {
                        val analysisMap = session.toAnalysisMap()
                        val file = withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching {
                                com.example.medvoicetrainer.export.ShareCardImage.saveBitmapForSharing(
                                    context,
                                    com.example.medvoicetrainer.export.ShareCardImage.renderSessionCard(analysisMap),
                                    "session_card_${session.id}.png"
                                )
                            }.getOrNull()
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, ShareReport.buildShareCard(analysisMap))
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file
                                )
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } else {
                                // Rendering is the only part that can fail here; falling back to
                                // the text card keeps the button working rather than doing nothing.
                                type = "text/plain"
                            }
                        }
                        context.startActivity(Intent.createChooser(shareIntent, t("Share Practice Session")))
                    }
                }) {
                    Text(t("history.share_card_btn"))
                }
                // Ported from history_tab.py's DOCX export (DocxExporter.kt + queries.save_docx_path):
                // render this session's scorecard to a .docx and hand it to the Android share sheet.
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = viewModel.exportSessionDocx(session)
                        if (file != null) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val docxIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(docxIntent, t("Export DOCX")))
                        }
                    }
                }) {
                    Text(t("Export DOCX"))
                }
                if (com.example.medvoicetrainer.analysis.PresentationBuilder.isPresentable(session.toAnalysisMap())) {
                    OutlinedButton(onClick = {
                        com.example.medvoicetrainer.analysis.Telemetry.track(
                            "team_communication_started",
                            mapOf("surface" to "history", "task" to "attending_presentation")
                        )
                        onPresentToAttending(session)
                    }) {
                        Text(t("history.present_case_btn"))
                    }
                }
            }

            PhrasebookRecapCard(
                aids = phrasebookAids,
                onOpenDrill = onOpenPhraseDrill.takeIf { phrasebookAids.unused.isNotEmpty() },
            )

            val parsedTurns = remember(session.rawTranscript) {
                parseStoredTranscript(session.rawTranscript)
            }
            val expandLabel = t("feedback.section_expand")
            val collapseLabel = t("feedback.section_collapse")
            val soapWritten = !session.studentSoapNote.isNullOrBlank()

            sections.forEach { section ->
                val headline = when (section) {
                    FeedbackSection.OVERVIEW -> overviewHeadline(evaluation, everyday, t)
                    FeedbackSection.FIX -> correctionsHeadline(triageStats, false, t)
                    FeedbackSection.DELIVERY ->
                        deliveryHeadline(evaluation.fluencyMetrics, evaluation.intelligibility)
                    FeedbackSection.REFLECT -> reflectHeadline(0, hasSelfScores, t)
                    FeedbackSection.TRANSCRIPT ->
                        t("feedback.headline_transcript").replace("{n}", parsedTurns.size.toString())
                    FeedbackSection.CLINICAL -> clinicalHeadline(checklistItems, soapWritten, t)
                }
                FeedbackAccordionCard(
                    title = t(section.labelKey),
                    headline = headline,
                    expanded = expansion.isExpanded(section),
                    onToggle = { expansion.toggle(section) },
                    expandLabel = expandLabel,
                    collapseLabel = collapseLabel
                ) {
                    when (section) {
                        FeedbackSection.OVERVIEW -> {
                            if (!evaluation.evaluationLocked) {
                                NursingScorecardContent(evaluation.nursingScorecardJson)
                            }
                            SummaryFeedbackContent(
                                evaluation, everyday, acceptedCorrections.size, acceptedCorrections
                            )
                            HorizontalDivider()
                            ScoresContent(evaluation, everyday)
                            comparison?.let { SessionComparisonSummary(it) }
                        }
                        FeedbackSection.FIX -> {
                            HistoryCorrectionsContent(
                                viewModel = viewModel,
                                records = historyCorrections,
                                decisions = decisions,
                                stats = triageStats,
                                triageEnabled = triageEnabled,
                                cardCount = acceptedCorrections.size
                            )
                            if (shadowingItems.isNotEmpty()) {
                                var shadowingExpanded by rememberSaveable(session.id) {
                                    mutableStateOf(false)
                                }
                                FeedbackSubBlock(
                                    title = t("feedback.shadowing_tab"),
                                    headline = t("feedback.headline_shadowing")
                                        .replace("{n}", shadowingItems.size.toString()),
                                    expanded = shadowingExpanded,
                                    onToggle = { shadowingExpanded = !shadowingExpanded },
                                    expandLabel = expandLabel,
                                    collapseLabel = collapseLabel
                                ) {
                                    ShadowingContent(
                                        evaluation = evaluation,
                                        everyday = everyday,
                                        items = shadowingItems,
                                        ttsReady = ttsReady,
                                        onSpeak = speakTarget,
                                        judgeAttempt = judgeAttempt
                                    )
                                }
                            }
                        }
                        FeedbackSection.DELIVERY -> {
                            FluencyContent(evaluation)
                            HorizontalDivider()
                            IntelligibilityContent(
                                evaluation = evaluation,
                                everyday = everyday,
                                ttsReady = ttsReady,
                                onSpeakText = speakText,
                                judgeAttempt = judgeAttempt
                            )
                        }
                        FeedbackSection.REFLECT ->
                            HistorySelfAssessmentContent(session, everyday, hasSelfScores)
                        FeedbackSection.TRANSCRIPT -> if (parsedTurns.isEmpty()) {
                            Text(t("Transcript details could not be parsed."))
                        } else {
                            PlayableTranscript(parsedTurns)
                        }
                        FeedbackSection.CLINICAL -> {
                            MisconceptionReviewBlock(
                                viewModel = null,
                                evaluation = evaluation,
                                allowDeepReview = false,
                            )
                            ChecklistContent(evaluation, checklistItems, empathyMarkers)
                            var soapExpanded by rememberSaveable(session.id) { mutableStateOf(false) }
                            FeedbackSubBlock(
                                title = t("feedback.soap_tab"),
                                headline = if (soapWritten) {
                                    t("feedback.headline_soap_written")
                                } else {
                                    t("feedback.headline_soap_missing")
                                },
                                expanded = soapExpanded,
                                onToggle = { soapExpanded = !soapExpanded },
                                expandLabel = expandLabel,
                                collapseLabel = collapseLabel
                            ) {
                                HistorySoapContent(session, evaluation)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The corrections list for a stored session. Findings whose triage was finished are replayed
 * read-only, but anything still PENDING keeps its accept/reject buttons: leaving a long report
 * half-reviewed is normal, and those findings would otherwise never reach the mistake tracker.
 * Decisions route through MainViewModel's session-scoped triage so they land on *this* row.
 */
@Composable
private fun HistoryCorrectionsContent(
    viewModel: MainViewModel,
    records: List<HistoryCorrectionRecord>,
    decisions: Map<String, CorrectionDecision>,
    stats: CorrectionTriageStats,
    triageEnabled: Boolean,
    cardCount: Int
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    if (records.isEmpty()) {
        Text(t("feedback.no_corrections"))
        return
    }
    var showAll by rememberSaveable(records.size) { mutableStateOf(false) }
    val ordered = remember(records) {
        val byKey = records.associateBy { it.correction.decisionKey() }
        orderCorrectionsByImpact(records.map { it.correction })
            .mapNotNull { byKey[it.decisionKey()] }
    }
    val visible = if (showAll) ordered else ordered.take(CORRECTION_PREVIEW_COUNT)

    if (triageEnabled && stats.pending > 0) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(t("feedback.resume_triage"), fontWeight = FontWeight.Bold)
                Text(
                    t("feedback.resume_triage_hint").replace("{n}", stats.pending.toString()),
                    style = MaterialTheme.typography.bodySmall
                )
                if (stats.pendingBulkAcceptable > 1) {
                    OutlinedButton(
                        onClick = { viewModel.acceptAllHistoryHighConfidenceCorrections() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            t("feedback.save_all_high_confidence")
                                .replace("{count}", stats.pendingBulkAcceptable.toString())
                        )
                    }
                }
            }
        }
    } else if (triageEnabled) {
        Text(
            t("feedback.triage_done"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    visible.forEachIndexed { index, record ->
        val correction = record.correction
        val decision = decisions[correction.decisionKey()] ?: CorrectionDecision.PENDING
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("#${index + 1} · ${correction.category}", style = MaterialTheme.typography.labelSmall)
                Text(
                    correction.original,
                    color = com.example.medvoicetrainer.ui.theme.DangerRedStrong,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                )
                Text(
                    correction.corrected,
                    color = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong,
                    fontWeight = FontWeight.Bold
                )
                if (correction.explanation.isNotBlank()) {
                    Text(correction.explanation, style = MaterialTheme.typography.bodySmall)
                }
                if (correction.patternId.isNotBlank()) {
                    Text(
                        "${t("Rule:")} ${correction.patternId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (correction.l1Hypothesis.isNotBlank()) {
                    Text(
                        t("feedback.correction_l1_hypothesis").replace("{value}", correction.l1Hypothesis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    t("feedback.correction_confidence")
                        .replace("{value}", "${(correction.confidence * 100).roundToInt()}%"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val (decisionLabel, decisionColor) = when (decision) {
                    CorrectionDecision.ACCEPTED ->
                        t("feedback.correction_saved") to com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                    CorrectionDecision.NOT_ERROR ->
                        t("feedback.correction_marked_not_error") to MaterialTheme.colorScheme.onSurfaceVariant
                    CorrectionDecision.STT_ERROR ->
                        t("feedback.correction_marked_stt") to MaterialTheme.colorScheme.onSurfaceVariant
                    CorrectionDecision.STYLE_ONLY ->
                        t("feedback.correction_marked_style") to MaterialTheme.colorScheme.onSurfaceVariant
                    CorrectionDecision.PENDING ->
                        t("Not reviewed") to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(decisionLabel, fontWeight = FontWeight.Bold, color = decisionColor)
                record.learnerPrediction?.let { prediction ->
                    Text(
                        if (prediction == "error") {
                            t("feedback.review_prediction_error")
                        } else {
                            t("feedback.review_prediction_okay")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (triageEnabled && decision == CorrectionDecision.PENDING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.decideHistoryCorrection(
                                    correction, CorrectionDecision.ACCEPTED
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_save")) }
                        OutlinedButton(
                            onClick = {
                                viewModel.decideHistoryCorrection(
                                    correction, CorrectionDecision.NOT_ERROR
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_not_error")) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.decideHistoryCorrection(
                                    correction, CorrectionDecision.STT_ERROR
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_stt_error")) }
                        OutlinedButton(
                            onClick = {
                                viewModel.decideHistoryCorrection(
                                    correction, CorrectionDecision.STYLE_ONLY
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_style_only")) }
                    }
                } else if (triageEnabled) {
                    TextButton(
                        onClick = { viewModel.resetHistoryCorrectionDecision(correction) }
                    ) {
                        Text(t("feedback.correction_change_decision"))
                    }
                }
            }
        }
    }
    if (ordered.size > visible.size) {
        TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                t("feedback.show_more_corrections")
                    .replace("{n}", (ordered.size - visible.size).toString())
            )
        }
    } else if (showAll && ordered.size > CORRECTION_PREVIEW_COUNT) {
        TextButton(onClick = { showAll = false }, modifier = Modifier.fillMaxWidth()) {
            Text(t("feedback.show_fewer"))
        }
    }
    if (cardCount > 0) {
        Text(
            t("feedback.cards_generated").replace("{n}", cardCount.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Read-only self-vs-AI comparison from the already-submitted self-assessment (SessionEntity.self*
 * columns), instead of FeedbackScreen's live editable sliders which save against lastSessionId. */
@Composable
private fun HistorySelfAssessmentContent(
    session: SessionEntity,
    everyday: Boolean,
    hasSelfScores: Boolean
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    if (!hasSelfScores) {
        Text(t("No self-assessment was submitted for this session."))
        return
    }
    val rows = if (everyday) {
        listOf(
            Triple(t("Naturalness"), session.selfGrammar, session.grammarScore),
            Triple(t("Interaction"), session.selfMedicalAccuracy, session.medicalAccuracyScore),
            Triple(t("Comprehension & Repair"), session.selfClinicalReasoning, session.clinicalReasoningScore),
            Triple(t("Fluency"), session.selfFluency, session.fluencyScore)
        )
    } else {
        listOf(
            Triple(t("Grammar"), session.selfGrammar, session.grammarScore),
            Triple(t("Medical Accuracy"), session.selfMedicalAccuracy, session.medicalAccuracyScore),
            Triple(t("Clinical Reasoning"), session.selfClinicalReasoning, session.clinicalReasoningScore),
            Triple(t("Professionalism"), session.selfProfessionalism, session.professionalismScore),
            Triple(t("Fluency"), session.selfFluency, session.fluencyScore)
        )
    }
    Text(t("You vs. the AI"), fontWeight = FontWeight.Bold)
    Text(
        t("A positive gap means the AI scored you higher than you scored yourself."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    rows.forEach { (label, self, ai) ->
        SelfDeltaRow(label = t(label), ai = ai, self = self, delta = ai - self)
    }
}

/** Read-only SOAP replay: shows the learner's already-saved note (if any) plus the AI-generated
 * and reference SOAP notes — no live editor, since saving one is tied to lastSessionId. */
@Composable
private fun HistorySoapContent(session: SessionEntity, evaluation: EvaluationResult) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val studentSoap = session.studentSoapNote?.takeIf { it.isNotBlank() }
    if (studentSoap != null) {
        Text(t("feedback.soap_yours"), fontWeight = FontWeight.Bold)
        Text(formatStudentSoap(studentSoap))
        HorizontalDivider()
    }
    Text(t("AI-generated SOAP"), fontWeight = FontWeight.Bold)
    SoapNoteText(formatSoapForDisplay(evaluation.soapNote).ifBlank { t("feedback.no_soap") })
    Text(
        t("feedback.soap_not_elicited_legend"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider()
    Text(t("feedback.soap_reference"), fontWeight = FontWeight.Bold)
    Text(evaluation.referenceSoap.ifBlank { t("feedback.soap_na") })
}

private fun formatStudentSoap(json: String): String = try {
    val obj = JSONObject(json)
    listOf("subjective" to "S", "objective" to "O", "assessment" to "A", "plan" to "P")
        .joinToString("\n") { (key, label) -> "$label: ${obj.optString(key)}" }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    json
}

@Composable
private fun SessionComparisonSummary(comparison: SessionAttemptComparison) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = t("SAME CASE PROGRESS"),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = t("Attempt") + " ${comparison.attemptNumber} / ${comparison.totalAttempts}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (comparison.previousCreatedAt == null) {
                Text(
                    text = t("First scored attempt for this case."),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = t("Overall vs. previous attempt") + ": " +
                        formatScoreDelta(comparison.overallDelta ?: 0.0) +
                        " · " + comparison.previousCreatedAt.substringBefore("T"),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = comparison.metricDeltas.joinToString("  ·  ") { delta ->
                        t(delta.label) + " " + formatScoreDelta(delta.delta)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

private fun formatScoreDelta(delta: Double): String {
    val arrow = when {
        delta > 0.05 -> "▲"
        delta < -0.05 -> "▼"
        else -> "—"
    }
    return arrow + String.format(Locale.US, "%.1f", kotlin.math.abs(delta))
}

fun parseTranscriptHelper(rawJson: String): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    try {
        val turns = JSONArray(rawJson)
        for (i in 0 until turns.length()) {
            val turn = turns.getJSONObject(i)
            val role = turn.getString("role")
            val text = turn.getString("text")
            list.add(Pair(role, text))
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        // ignore
    }
    return list
}
