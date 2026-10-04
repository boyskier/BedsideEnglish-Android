package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.medvoicetrainer.analysis.KmleCpx
import com.example.medvoicetrainer.analysis.KmleCpxScorecard
import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.ui.KmleCpxResult
import com.example.medvoicetrainer.ui.MainViewModel
import kotlinx.coroutines.launch

// Korean CPX track screens. The whole track is Korean-only by design (it prepares Korean students
// for the Korean licensing exam), so its copy is written in Korean directly rather than routed
// through the English-keyed I18n table.

private val DONE_COLOR = Color(0xFF2E7D32)
private val PARTIAL_COLOR = Color(0xFFF9A825)
private val MISSED_COLOR = Color(0xFFC62828)

private fun statusColor(status: String): Color = when (status) {
    KmleCpx.STATUS_DONE -> DONE_COLOR
    KmleCpx.STATUS_PARTIAL -> PARTIAL_COLOR
    KmleCpx.STATUS_MISSED -> MISSED_COLOR
    else -> Color.Gray
}

private fun ratingColor(rating: String): Color = when (rating) {
    "good" -> DONE_COLOR
    "partial" -> PARTIAL_COLOR
    "poor", "not_stated" -> MISSED_COLOR
    else -> Color.Gray
}

private fun scoreColor(percent: Int?): Color = when {
    percent == null -> Color.Gray
    percent >= 80 -> DONE_COLOR
    percent >= 60 -> PARTIAL_COLOR
    else -> MISSED_COLOR
}

private fun shortDate(createdAt: String): String =
    createdAt.take(16).replace('T', ' ')

/** Score for one stored CPX session, or null when it was never graded. */
private fun storedOverall(session: SessionEntity): Int? =
    KmleCpxScorecard.build(session.rawCaseJson, session.rawEvalJson)?.overall

/** A started-then-abandoned station (nothing said, nothing graded) is not a practice record. */
private fun isRecord(session: SessionEntity): Boolean =
    session.learnerTurnCount > 0 || !session.rawEvalJson.isNullOrBlank()

private fun presentationIdOf(session: SessionEntity): String =
    KmleCpx.sessionCase(session.rawCaseJson)?.presentation?.id.orEmpty()

// ── Home: station picker ─────────────────────────────────────────────────────────────────────

/** Korean names for the case library's body-system folders (`data/cases/<system>/`). */
private val SYSTEM_LABELS = mapOf(
    "allergy" to "알레르기", "cardio" to "순환기", "cs" to "흉부외과", "derm" to "피부과",
    "em" to "응급의학", "endo" to "내분비", "ent" to "이비인후과", "fm" to "가정의학",
    "gi" to "소화기", "gs" to "일반외과", "hemato" to "혈액종양", "id" to "감염",
    "nephro" to "신장", "neuro" to "신경과", "ns" to "신경외과", "obgyn" to "산부인과",
    "opht" to "안과", "ortho" to "정형외과", "peds" to "소아청소년과", "pmr" to "재활의학",
    "ps" to "성형외과", "psych" to "정신건강의학", "pulm" to "호흡기", "rheum" to "류마티스",
    "urology" to "비뇨의학",
)

private fun systemLabel(system: String): String = SYSTEM_LABELS[system] ?: system

private fun difficultyLabel(difficulty: String): String = when (difficulty) {
    "beginner" -> "기초"
    "intermediate" -> "중급"
    "advanced" -> "고급"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KmleCpxHomeScreen(
    viewModel: MainViewModel,
    onStart: (MainViewModel.KmleCpxLaunch) -> Unit,
    onOpenHistory: () -> Unit,
    onStartPeer: (MainViewModel.KmleCpxLaunch) -> Unit = viewModel::openKmlePeer,
    onOpenSettings: () -> Unit = {},
) {
    val geminiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var studying by rememberSaveable { mutableStateOf(false) }
    val onOpenStudy = { studying = true }
    val allSessions by viewModel.kmleSessions.collectAsStateWithLifecycle()
    val kmlePrefs by viewModel.kmlePrefs.collectAsStateWithLifecycle()
    val sessions = remember(allSessions) { allSessions.filter(::isRecord) }
    var presentations by remember { mutableStateOf<List<KmleCpx.Presentation>?>(null) }
    var poolSizes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        presentations = viewModel.loadKmlePresentations()
        poolSizes = viewModel.kmlePoolSizes()
    }

    // "전체 케이스" lists the whole case library; loaded only once the learner opens that view.
    var browseAll by rememberSaveable { mutableStateOf(false) }
    var caseRows by remember { mutableStateOf<List<MainViewModel.KmleCaseRow>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(browseAll) {
        if (browseAll && caseRows == null) caseRows = viewModel.loadKmleCaseRows()
    }
    val playedCaseIds = remember(sessions) { sessions.mapNotNull { it.caseId }.toSet() }

    // One pass over stored sessions: latest score per presentation, plus the overall recent mean.
    val scores = remember(sessions) {
        sessions.mapNotNull { s -> storedOverall(s)?.let { Triple(presentationIdOf(s), s.createdAt, it) } }
    }
    val latestByPresentation = remember(scores) {
        scores.groupBy { it.first }.mapValues { (_, rows) -> rows.maxByOrNull { it.second }!!.third }
    }
    val recentMean = remember(scores) {
        scores.sortedByDescending { it.second }.take(10).map { it.third }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    }
    // Coverage of the official list: which of the 48 presentations this learner has met at least once.
    val practicedIds = remember(sessions) { sessions.map(::presentationIdOf).toSet() }
    val officialItems = remember(presentations) { presentations.orEmpty().filter { it.kmleItem.isNotBlank() }.groupBy { it.kmleItem } }
    val practicedItems = remember(officialItems, practicedIds) {
        officialItems.filterValues { stations -> stations.any { it.id in practicedIds } }.keys
    }

    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf<String?>(null) }
    var pendingLaunch by remember { mutableStateOf<MainViewModel.KmleCpxLaunch?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    // Set only for a station pick, where "다른 환자" draws another case from the same station.
    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun prepare(key: String, title: String, load: suspend () -> MainViewModel.KmleCpxLaunch?, retry: (() -> Unit)?) {
        if (preparing != null) return
        preparing = key
        scope.launch {
            val launch = load()
            preparing = null
            if (launch == null) {
                loadError = "'$title' 증례를 불러오지 못했습니다."
            } else {
                pendingTitle = title
                pendingRetry = retry
                pendingLaunch = launch
            }
        }
    }

    fun preparePresentation(p: KmleCpx.Presentation) {
        prepare(p.id, p.title, { viewModel.prepareKmleCpxSession(p.id) }, { preparePresentation(p) })
    }

    fun prepareCase(row: MainViewModel.KmleCaseRow) {
        prepare(row.caseId, row.presentationTitle, { viewModel.prepareKmleCpxCase(row.caseId) }, null)
    }

    // Keeps the open station sheet across the S49 dialog, which takes the study screen out of composition.
    val studyState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (studying && pendingLaunch == null) {
        studyState.SaveableStateProvider("kmle_study") {
            KmleStudyScreen(
                viewModel = viewModel,
                onClose = { studying = false },
                onPractice = { p -> preparePresentation(p) },
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MedicalServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "CPX 연습 (한국어)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "증상이나 케이스를 고르면 국시 형식의 문제지를 보여 드려요. 표준화 환자와 한국어로 병력청취, 신체진찰, " +
                            "설명까지 진행하면 체크리스트, 환자-의사 관계(PPI), 의학 내용으로 채점합니다. 병력청취만 연습할 수도 있어요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatBlock("연습한 스테이션", "${sessions.size}회")
                        StatBlock("최근 10회 평균", recentMean?.let { "${it}점" } ?: "—")
                        if (officialItems.isNotEmpty()) StatBlock("국시 임상표현", "${practicedItems.size} / ${officialItems.size}")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenHistory) { Text("채점 기록 보기") }
                        OutlinedButton(onClick = onOpenStudy) { Text("공부하기") }
                    }
                    val untried = officialItems.filterKeys { it !in practicedItems }
                    if (untried.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { untried.values.random().random().let(::preparePresentation) }) {
                            Text("안 해 본 임상표현 하기 (${untried.size}개 남음)")
                        }
                    }
                }
            }
        }
        if (geminiKey.isBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFF8E1),
                    border = BorderStroke(1.dp, Color(0xFFFFB300)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("무료로 시작하기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color(0xFF3E2723))
                        Text(
                            "이 앱은 무료이고 광고가 없어요. AI 환자와 채점은 본인의 Gemini API 키로 돌아가는데, " +
                                "Google AI Studio의 무료 키로 비용 없이 연습할 수 있어요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF3E2723),
                        )
                        Text(
                            "1. aistudio.google.com 에 Google 계정으로 로그인\n2. 'Get API key' → 'Create API key'\n3. 키를 복사해 설정 → API 키에 붙여넣기",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF3E2723),
                        )
                        Text(
                            "무료 등급에서는 Google이 대화를 서비스 개선에 쓸 수 있어요. 실제 환자 정보나 개인 정보는 말하지 마세요. 키 없이도 '공부하기'는 쓸 수 있어요.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF5D4037),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") } }) { Text("무료 키 만들기") }
                            OutlinedButton(onClick = onOpenSettings) { Text("설정 열기") }
                        }
                    }
                }
            }
        }
        item {
            val preparingMock by viewModel.kmleMockPreparing.collectAsStateWithLifecycle()
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("실전 모의고사", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text(
                        "국시처럼 서로 다른 임상표현 스테이션을 연달아 봅니다. 12분 자동 종료, 힌트 없음, 결과는 마지막에 한꺼번에.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val onMockFailed = { loadError = "모의고사 스테이션을 불러오지 못했습니다." }
                        Button(onClick = { viewModel.startKmleMockExam(9, onMockFailed) }, enabled = !preparingMock) { Text("9개 스테이션") }
                        OutlinedButton(onClick = { viewModel.startKmleMockExam(3, onMockFailed) }, enabled = !preparingMock) { Text("빠르게 3개") }
                        if (preparingMock) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !browseAll, onClick = { browseAll = false }, label = { Text("증상별") })
                FilterChip(selected = browseAll, onClick = { browseAll = true }, label = { Text("전체 케이스") })
            }
        }
        if (!browseAll) {
            val list = presentations
            if (list == null) {
                item { LoadingRow() }
            } else if (list.isEmpty()) {
                item { Text("표시할 증상이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                list.groupBy { it.category }.forEach { (category, group) ->
                    item(key = "header_$category") { GroupHeader(category) }
                    items(group, key = { it.id }) { p ->
                        PresentationCard(
                            presentation = p,
                            caseCount = poolSizes[p.id] ?: p.caseIds.size,
                            latestScore = latestByPresentation[p.id],
                            loading = preparing == p.id,
                            onClick = { preparePresentation(p) },
                        )
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("증상, 진료과, 나이로 찾기") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val rows = caseRows
            if (rows == null) {
                item { LoadingRow() }
            } else {
                val q = query.trim()
                val filtered = if (q.isEmpty()) rows else rows.filter { row ->
                    row.doorComplaint.contains(q) || row.presentationTitle.contains(q) ||
                        systemLabel(row.system).contains(q) || KmleCpx.patientLabel(row.age, row.gender).contains(q)
                }
                item {
                    Text(
                        "${filtered.size}개 케이스 · 진단은 채점 후에 공개됩니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                filtered.groupBy { it.system }.forEach { (system, group) ->
                    item(key = "system_$system") { GroupHeader("${systemLabel(system)} (${group.size})") }
                    items(group, key = { "case_${it.caseId}" }) { row ->
                        CaseRowCard(
                            row = row,
                            played = row.caseId in playedCaseIds,
                            loading = preparing == row.caseId,
                            onClick = { prepareCase(row) },
                        )
                    }
                }
            }
        }
    }

    val launch = pendingLaunch
    if (launch != null) {
        val retry = pendingRetry
        DoorNoteDialog(
            title = pendingTitle,
            launch = launch,
            prefs = kmlePrefs,
            onPrefs = viewModel::updateKmlePrefs,
            onScope = { scope -> pendingLaunch = viewModel.rescopeKmleLaunch(launch, scope) },
            onPeer = {
                pendingLaunch = null
                onStartPeer(launch)
            },
            onStart = {
                pendingLaunch = null
                onStart(launch)
            },
            onAnotherPatient = if (retry == null) null else {
                {
                    pendingLaunch = null
                    retry()
                }
            },
            onDismiss = { pendingLaunch = null },
        )
    }
    loadError?.let { message ->
        AlertDialog(
            onDismissRequest = { loadError = null },
            confirmButton = { TextButton(onClick = { loadError = null }) { Text("확인") } },
            title = { Text("불러오기 실패") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CaseRowCard(
    row: MainViewModel.KmleCaseRow,
    played: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${KmleCpx.patientLabel(row.age, row.gender)} · ${row.doorComplaint}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOf(row.presentationTitle, difficultyLabel(row.difficulty), if (played) "연습함" else "")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PresentationCard(
    presentation: KmleCpx.Presentation,
    caseCount: Int,
    latestScore: Int?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(presentation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "증례 ${caseCount}개 · " + presentation.groups.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else if (latestScore != null) {
                ScorePill(latestScore)
            }
        }
    }
}

@Composable
private fun ScorePill(score: Int?) {
    Surface(shape = CircleShape, color = scoreColor(score).copy(alpha = 0.15f)) {
        Text(
            score?.let { "${it}점" } ?: "미채점",
            color = scoreColor(score),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The problem sheet in the exam's layout (2026 format): title with the time limit, one line with
 * age, sex, name and place, the four vital signs, and the boxed task list. Shared by the pre-station
 * dialog, the in-session "문제" sheet and the result card.
 */
@Composable
fun KmleProblemSheet(
    card: KmleCpx.SituationCard,
    complaintHint: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFFFDF7),
        contentColor = Color(0xFF1B1B1B),
        border = BorderStroke(1.dp, Color(0xFFD6D0C4)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(card.intro, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (complaintHint && card.complaintHint.isNotBlank()) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFF3CD)) {
                    Text(
                        "주호소 (연습용 힌트): ${card.complaintHint}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (card.vitals.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("[활력징후]", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    card.vitals.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (card.results.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("[검사 결과]", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    card.results.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color(0xFF8A8475)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.taskIntro, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    card.tasks.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

/**
 * The in-station sheet: the problem sheet stays "on the desk" for the whole station, and below it
 * the findings of the examinations performed so far (study aid, when enabled).
 */
@Composable
fun KmleStationSheet(
    session: KmleCpx.SessionCase,
    revealedManeuvers: List<String>,
    showFindings: Boolean,
    showComplaint: Boolean,
    showChecklistHint: Boolean = false,
    onDismiss: () -> Unit,
) {
    val script = session.script
    val findings = if (script == null) emptyList()
    else com.example.medvoicetrainer.analysis.SpScript.findingsFor(script, revealedManeuvers)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("문제지", fontWeight = FontWeight.Black) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                KmleProblemSheet(session.situationCard, complaintHint = showComplaint)
                if (showChecklistHint) {
                    Text("체크리스트 힌트 (초보용)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    session.checklist.filter { it.key != KmleCpx.SECTION_PPI && it.key != KmleCpx.SECTION_CONTENT }.forEach { section ->
                        Text(section.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        section.items.forEach { ci ->
                            Column {
                                Text(
                                    "• ${ci.item.text}" + if (ci.item.key) " ★" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (ci.item.key) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (ci.item.say.isNotBlank()) {
                                    Text("  \"${ci.item.say}\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                if (showFindings && script != null && script.exam.isNotEmpty()) {
                    Text("진찰 소견 (학습용)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (findings.isEmpty()) {
                        Text(
                            "아직 진찰한 부위가 없어요. \"배를 눌러 보겠습니다\"처럼 무엇을 어떻게 진찰하는지 말하면 소견이 여기에 나타납니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    findings.forEach { f ->
                        Column {
                            Text(
                                session.maneuverKo(f.maneuver) + if (f.painful) " · 통증 있음" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (f.painful) MISSED_COLOR else Color.Unspecified,
                            )
                            Text(f.finding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoorNoteDialog(
    title: String,
    launch: MainViewModel.KmleCpxLaunch,
    prefs: MainViewModel.KmlePrefs,
    onPrefs: (MainViewModel.KmlePrefs) -> Unit,
    onScope: (String) -> Unit,
    onPeer: () -> Unit,
    onStart: () -> Unit,
    onAnotherPatient: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val card = launch.situationCard
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (card != null) {
                    KmleProblemSheet(card, complaintHint = prefs.showComplaint)
                } else {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(launch.doorNote, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(14.dp).fillMaxWidth())
                    }
                }
                Text("연습 범위", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KmleCpx.SCOPES.forEach { scope ->
                        FilterChip(
                            selected = launch.scope == scope,
                            onClick = { onScope(scope) },
                            label = { Text(KmleCpx.scopeLabel(scope)) },
                        )
                    }
                }
                PrefSwitch("주호소 미리 보기 (학교 실습 형식)", prefs.showComplaint) { onPrefs(prefs.copy(showComplaint = it)) }
                PrefSwitch("진찰하면 소견 보여 주기 (학습용)", prefs.showFindings) { onPrefs(prefs.copy(showFindings = it)) }
                PrefSwitch("실전 타이머: 시간이 끝나면 자동 종료", prefs.strictTimer) { onPrefs(prefs.copy(strictTimer = it)) }
                PrefSwitch("진료 중 체크리스트 힌트 보기 (초보용)", prefs.showChecklistHint) { onPrefs(prefs.copy(showChecklistHint = it)) }
                Text(
                    "2026년 국시처럼 문제지에는 주호소가 없습니다. 환자에게 직접 물어보세요. 환자는 물어본 것에만 짧게 대답합니다. " +
                        "신체진찰은 무엇을 어떻게 하는지 말로 하세요. 종료 2분 전에 알려 드립니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // One column for every action: AlertDialog lays a multi-button confirm slot out beside the
        // dismiss row, and on a phone the two collide.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("입실 · AI 환자와 시작") }
                OutlinedButton(onClick = onPeer, modifier = Modifier.fillMaxWidth()) { Text("친구와 역할극 (AI 채점)") }
                Row {
                    if (onAnotherPatient != null) TextButton(onClick = onAnotherPatient) { Text("다른 환자") }
                    TextButton(onClick = onDismiss) { Text("취소") }
                }
            }
        },
    )
}

@Composable
private fun PrefSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── History ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun KmleCpxHistoryScreen(viewModel: MainViewModel) {
    val sessions by viewModel.kmleSessions.collectAsStateWithLifecycle()
    val background by viewModel.backgroundAnalysisSessionIds.collectAsStateWithLifecycle()
    val rows = remember(sessions) { sessions.filter(::isRecord).map { it to storedOverall(it) } }
    var confirmDelete by remember { mutableStateOf<SessionEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "CPX 채점 기록",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    "아직 기록이 없습니다. 홈에서 증상을 골라 첫 스테이션을 시작해 보세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows, key = { it.first.id }) { (session, overall) ->
            val graded = overall != null
            val inProgress = session.id in background
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.openKmleResult(session) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.caseName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            shortDate(session.createdAt) + " · ${session.durationSeconds / 60}분",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!graded && !inProgress && session.learnerTurnCount > 0) {
                            TextButton(
                                onClick = { viewModel.retrySessionAnalysis(session) },
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("채점하기") }
                        }
                    }
                    if (inProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        ScorePill(overall)
                    }
                    IconButton(onClick = { confirmDelete = session }) {
                        Icon(Icons.Default.Delete, contentDescription = "기록 삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    confirmDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("기록 삭제") },
            text = { Text("'${session.caseName}' 기록을 휴지통으로 옮길까요?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKmleSession(session.id)
                    confirmDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

// ── Result card ──────────────────────────────────────────────────────────────────────────────

@Composable
fun KmleCpxResultScreen(
    result: KmleCpxResult,
    onClose: () -> Unit,
    onOverride: ((key: String, status: String) -> Unit)? = null,
) {
    val card = result.scorecard
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "닫기") }
                Text("CPX 채점 결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OverviewCard(result)
                if (card.locked) {
                    InfoCard(
                        "채점되지 않았어요",
                        "분석용 API 키가 없어 채점하지 못했습니다. 설정에서 키를 입력한 뒤 CPX 기록에서 '채점하기'를 누르세요. " +
                            "아래 체크리스트로 스스로 점검해 볼 수 있습니다.",
                    )
                }
                card.situationCard?.let { sheet -> ProblemSheetCard(sheet) }
                if (card.safetyFlags.isNotEmpty()) SafetyCard(card.safetyFlags)
                if (!card.locked && card.content.isNotEmpty()) DiagnosisCard(card)
                if (card.missedKeyItems.isNotEmpty()) MissedKeyCard(card.missedKeyItems)
                if (card.summary.isNotBlank() || card.strengths.isNotEmpty() || card.improvements.isNotEmpty()) {
                    FeedbackTextCard(card)
                }
                if (!card.locked && onOverride != null) {
                    Text(
                        "채점이 틀렸다고 생각하면 항목의 상태 표시를 눌러 고칠 수 있어요. 점수가 다시 계산됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                card.sections.filter { it.items.isNotEmpty() }.forEach { SectionCard(it, if (card.locked) null else onOverride) }
                if (result.examReview.isNotEmpty()) ExamReviewCard(result.examReview)
                if (!card.locked) {
                    if (card.content.isNotEmpty()) {
                        ContentCard(card.content, card.sections.firstOrNull { it.key == KmleCpx.SECTION_CONTENT }?.percent)
                    }
                    PpiCard(card.ppi, card.sections.firstOrNull { it.key == KmleCpx.SECTION_PPI }?.percent)
                }
                if (card.modelLines.isNotEmpty()) ModelLinesCard(card.modelLines)
                TranscriptCard(result.transcript)
                if (card.disclaimer.isNotBlank()) {
                    Text(
                        card.disclaimer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ResultCard(title) { Text(body, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun OverviewCard(result: KmleCpxResult) {
    val card = result.scorecard
    ResultCard(result.caseName) {
        Text(
            listOf(card.doorNote, card.scopeLabel.takeIf { it.isNotBlank() }?.let { "연습 범위: $it" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                card.overall?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = scoreColor(card.overall),
            )
            Text(" / 100", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        card.sections.forEach { section ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${section.label} (${section.weight.toInt()}%)", style = MaterialTheme.typography.bodySmall)
                    Text(section.percent?.let { "$it" } ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { (section.percent ?: 0) / 100f },
                    color = scoreColor(section.percent),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun SafetyCard(flags: List<KmleCpxScorecard.SafetyFlag>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("환자 안전 경고", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            }
            flags.forEach { flag ->
                val severity = when (flag.severity) { "critical" -> "심각"; "minor" -> "경미"; else -> "주의" }
                Text("[$severity] ${flag.issue}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (flag.quote.isNotBlank()) {
                    Text("“${flag.quote}”", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiagnosisCard(card: KmleCpxScorecard.Scorecard) {
    ResultCard("진단") {
        LabeledLine("내가 말한 추정진단", card.studentDiagnosis.ifBlank { "말하지 않음" })
        LabeledLine("실제 진단", card.actualDiagnosis.ifBlank { "—" })
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MissedKeyCard(items: List<KmleCpxScorecard.ScoredItem>) {
    ResultCard("놓친 핵심 항목") {
        items.forEach { item ->
            Text("• ${item.text} (${KmleCpxScorecard.statusLabel(item.status)})", style = MaterialTheme.typography.bodyMedium)
            if (item.say.isNotBlank()) {
                Text("  예: ${item.say}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FeedbackTextCard(card: KmleCpxScorecard.Scorecard) {
    ResultCard("총평") {
        if (card.summary.isNotBlank()) Text(card.summary, style = MaterialTheme.typography.bodyMedium)
        if (card.strengths.isNotEmpty()) {
            Text("잘한 점", style = MaterialTheme.typography.labelLarge, color = DONE_COLOR)
            card.strengths.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (card.improvements.isNotEmpty()) {
            Text("다음에 고칠 점", style = MaterialTheme.typography.labelLarge, color = MISSED_COLOR)
            card.improvements.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SectionCard(section: KmleCpxScorecard.SectionScore, onOverride: ((String, String) -> Unit)? = null) {
    var expanded by remember(section.key) { mutableStateOf(section.key == KmleCpx.SECTION_HISTORY) }
    val done = section.items.count { it.status == KmleCpx.STATUS_DONE }
    val counted = section.items.count { KmleCpxScorecard.credit(it.status) != null }
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(section.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                    Text(
                        if (counted > 0) "수행 $done / $counted 항목" else "채점된 항목 없음",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScorePill(section.percent)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "접기" else "펼치기")
            }
            if (expanded) {
                section.items.forEach { ChecklistRow(it, onOverride) }
            }
        }
    }
}

@Composable
private fun ChecklistRow(item: KmleCpxScorecard.ScoredItem, onOverride: ((String, String) -> Unit)? = null) {
    var menu by remember(item.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor(item.status).copy(alpha = 0.15f),
                modifier = if (onOverride != null) Modifier.clickable { menu = true } else Modifier,
            ) {
                Text(
                    KmleCpxScorecard.statusLabel(item.status) + if (item.overridden) "*" else "",
                    color = statusColor(item.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(min = 36.dp),
                )
            }
            if (onOverride != null) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(KmleCpx.STATUS_DONE, KmleCpx.STATUS_PARTIAL, KmleCpx.STATUS_MISSED, KmleCpx.STATUS_NA).forEach { s ->
                        DropdownMenuItem(
                            text = { Text(KmleCpxScorecard.statusLabel(s)) },
                            onClick = {
                                menu = false
                                onOverride(item.key, s)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.text + if (item.critical) " ★" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.critical) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (item.evidence.isNotBlank()) {
                Text(item.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.overridden) {
                Text("* 내가 고친 판정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.say.isNotBlank() && item.status != KmleCpx.STATUS_DONE && item.status != KmleCpx.STATUS_NA) {
                Text("예: ${item.say}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ContentCard(content: List<KmleCpxScorecard.ContentScore>, percent: Int?) {
    ResultCard("진단·계획 (의학 내용)${percent?.let { " · ${it}점" } ?: ""}") {
        content.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(KmleCpxScorecard.ratingLabel(c.rating), color = ratingColor(c.rating), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            if (c.comment.isNotBlank()) {
                Text(c.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PpiCard(ppi: List<KmleCpxScorecard.PpiScore>, percent: Int?) {
    ResultCard("환자-의사 관계 (PPI)${percent?.let { " · ${it}점" } ?: ""}") {
        Text(
            "PPI는 실제 시험에서 표준화 환자가 직접 느낀 대로 평가합니다. 여기서는 대화 기록만으로 추정하므로 말투·표정·태도는 반영되지 않고, 정확도가 체크리스트보다 낮을 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ppi.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    p.score?.let { if (p.level.isNotBlank() && !p.level.endsWith("점")) p.level else "$it / ${p.scale}" } ?: "—",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = p.score?.let { scoreColor(((it - 1) * 100) / (p.scale - 1).coerceAtLeast(1)) } ?: Color.Gray,
                )
            }
            if (p.comment.isNotBlank()) {
                Text(p.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProblemSheetCard(sheet: KmleCpx.SituationCard) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("문제지", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "접기" else "펼치기")
            }
            if (expanded) KmleProblemSheet(sheet, complaintHint = true)
        }
    }
}

/** Every scripted finding, marked by whether the learner examined for it — the part a transcript alone cannot teach. */
@Composable
private fun ExamReviewCard(rows: List<KmleCpx.ExamReviewRow>) {
    val done = rows.count { it.performed }
    ResultCard("이 환자의 실제 진찰 소견 (진찰함 $done / ${rows.size})") {
        Text(
            "학습용 소견입니다. 실제 시험에서는 표준화 환자의 반응이나 모형으로 확인합니다. 소견은 의무기록처럼 영어로 적혀 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (row.performed) DONE_COLOR else Color.Gray).copy(alpha = 0.15f),
                ) {
                    Text(
                        if (row.performed) "진찰함" else "안 함",
                        color = if (row.performed) DONE_COLOR else Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(min = 40.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name + if (row.painful) " · 압통/통증 있음" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.painful) MISSED_COLOR else Color.Unspecified,
                    )
                    Text(row.finding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ModelLinesCard(lines: List<KmleCpxScorecard.ModelLine>) {
    ResultCard("이렇게 말해 보세요") {
        lines.forEach { line ->
            if (line.situation.isNotBlank()) {
                Text(line.situation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("“${line.better}”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TranscriptCard(transcript: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("대화 기록", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "접기" else "펼치기")
            }
            if (expanded) {
                transcript.filter { it.second.isNotBlank() }.forEach { (role, text) ->
                    val doctor = role == "doctor"
                    Text(
                        (if (doctor) "나: " else "환자: ") + text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (doctor) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
