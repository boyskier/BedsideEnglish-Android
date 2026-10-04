package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.medvoicetrainer.analysis.KmleCpx
import com.example.medvoicetrainer.analysis.KmleCpxScorecard
import com.example.medvoicetrainer.ui.MainViewModel

/**
 * 공부하기: every station's checklist as a study sheet (no API, no microphone) and the learner's own
 * most-missed items across graded stations. The sheet is the app's own rubric — the same one the
 * grader uses — so studying it is studying exactly what will be scored.
 */
@Composable
fun KmleStudyScreen(viewModel: MainViewModel, onClose: () -> Unit, onPractice: (KmleCpx.Presentation) -> Unit) {
    val sessions by viewModel.kmleSessions.collectAsStateWithLifecycle()
    var presentations by remember { mutableStateOf<List<KmleCpx.Presentation>>(emptyList()) }
    var common by remember { mutableStateOf<KmleCpx.Common?>(null) }
    LaunchedEffect(Unit) {
        presentations = viewModel.loadKmlePresentations()
        common = viewModel.loadKmleCommon()
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showOsce by rememberSaveable { mutableStateOf(false) }
    val weak = remember(sessions) { weakItems(sessions) }

    if (showOsce) {
        KmleOsceScreen(viewModel, onClose = { showOsce = false })
        return
    }

    val open = presentations.firstOrNull { it.id == openId }
    if (open != null) {
        androidx.activity.compose.BackHandler { openId = null }
        StationStudySheet(open, common, weak.filter { it.presentationId == open.id || it.presentationId == "" }, onBack = { openId = null }, onPractice = { onPractice(open) })
        return
    }
    androidx.activity.compose.BackHandler(onBack = onClose)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "뒤로") }
                Text("공부하기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clickable { showOsce = true },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("기본진료술기 (OSCE) 체크리스트", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                    Text(
                        "심폐소생술, 제세동, 기관삽관, 드레싱, 국소마취, 봉합, 정맥주사·수혈, 정맥·동맥 채혈 — 단계별 셀프 체크와 12분 타이머",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (weak.isNotEmpty()) {
            item {
                ResultLikeCard("내가 자주 놓치는 항목") {
                    Text(
                        "채점된 스테이션에서 누락·부분 수행이 많았던 항목입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    weak.take(10).forEach { w ->
                        Column {
                            Text("${w.text}  · ${w.missed}/${w.seen}회 놓침", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (w.say.isNotBlank()) Text("예: ${w.say}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        val official = presentations.filter { it.kmleItem.isNotBlank() }.groupBy { it.kmleItem }.toSortedMap()
        item { SectionHeader("국시 임상표현 (${official.size}개 항목)") }
        official.forEach { (item, stations) ->
            items(stations, key = { "study_${it.id}" }) { p ->
                StudyRow(p, subtitle = item) { openId = p.id }
            }
        }
        val extra = presentations.filter { it.kmleItem.isBlank() }
        if (extra.isNotEmpty()) {
            item { SectionHeader("보충 스테이션 (국시 목록 밖)") }
            items(extra, key = { "study_extra_${it.id}" }) { p -> StudyRow(p, subtitle = p.category) { openId = p.id } }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun StudyRow(p: KmleCpx.Presentation, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultLikeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            content()
        }
    }
}

@Composable
private fun StationStudySheet(
    p: KmleCpx.Presentation,
    common: KmleCpx.Common?,
    weak: List<WeakItem>,
    onBack: () -> Unit,
    onPractice: () -> Unit,
) {
    val groupLabels = p.groups.associate { it.key to it.label }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "뒤로") }
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(listOf(p.kmleItem, p.englishTitle).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Button(onClick = onPractice, modifier = Modifier.fillMaxWidth()) { Text("이 증상으로 연습하기") } }
        if (p.groups.isNotEmpty()) {
            item {
                ResultLikeCard("문제 해결 흐름 (감별 범주)") {
                    p.groups.forEach { g ->
                        Text(g.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(g.examples.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val relevantWeak = weak.take(5)
        if (relevantWeak.isNotEmpty()) {
            item {
                ResultLikeCard("이 스테이션에서 내가 놓친 것") {
                    relevantWeak.forEach { Text("• ${it.text} (${it.missed}/${it.seen})", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { StudyItems("병력청취 (증상별 항목)", p.history, groupLabels) }
        if (KmleCpx.TASK_EXAM in p.tasks && p.physicalExam.isNotEmpty()) item { StudyItems("신체진찰", p.physicalExam, groupLabels) }
        if (KmleCpx.TASK_EDUCATION in p.tasks && p.education.isNotEmpty()) item { StudyItems("환자교육 (증상별 항목)", p.education, groupLabels) }
        if (p.graderNotes.isNotEmpty()) {
            item {
                ResultLikeCard("검사·치료 핵심 (채점 기준)") {
                    p.graderNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        common?.let { c ->
            item {
                ResultLikeCard("모든 스테이션 공통 흐름") {
                    val lines = c.opening + c.historyBefore + c.historyAfter + c.examBefore + c.examAfter + c.education
                    lines.forEach { item ->
                        Column {
                            Text("• ${item.text}", style = MaterialTheme.typography.bodyMedium)
                            if (item.say.isNotBlank()) Text("  예: ${item.say}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StudyItems(title: String, items: List<KmleCpx.Item>, groupLabels: Map<String, String>) {
    ResultLikeCard(title) {
        items.forEach { item ->
            Column {
                Text(
                    item.text + if (item.key) " ★" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.key) FontWeight.Bold else FontWeight.Normal,
                    color = if (item.key) Color(0xFFC62828) else Color.Unspecified,
                )
                val tags = item.tags.mapNotNull { groupLabels[it] }
                if (tags.isNotEmpty() || item.conditional) {
                    Text(
                        (tags.joinToString(", ") + if (item.conditional) " · 해당 시" else "").trim(' ', '·'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.say.isNotBlank()) Text("\"${item.say}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("★ 놓치면 안 되는 항목", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One checklist line and how often the learner missed it across graded stations. */
data class WeakItem(val presentationId: String, val text: String, val say: String, val missed: Int, val seen: Int)

/**
 * Most-missed checklist lines, from every graded CPX session. Common lines (greeting, interim
 * summary, ICE…) are pooled across stations (presentationId ""), station lines stay per station.
 */
fun weakItems(sessions: List<com.example.medvoicetrainer.db.SessionEntity>): List<WeakItem> {
    data class Tally(var missed: Int = 0, var seen: Int = 0, var say: String = "", var text: String = "")
    val tally = linkedMapOf<Pair<String, String>, Tally>()
    sessions.forEach { s ->
        val session = KmleCpx.sessionCase(s.rawCaseJson) ?: return@forEach
        val card = KmleCpxScorecard.build(s.rawCaseJson, s.rawEvalJson) ?: return@forEach
        if (card.locked) return@forEach
        val stationItemIds = (session.presentation.history + session.presentation.physicalExam + session.presentation.education).map { it.id }.toSet()
        card.sections.flatMap { it.items }.forEach { item ->
            if (KmleCpxScorecard.credit(item.status) == null || item.key.contains(".case_")) return@forEach
            val itemId = item.key.substringAfter('.')
            val owner = if (itemId in stationItemIds) session.presentation.id else ""
            val t = tally.getOrPut(owner to item.key) { Tally() }
            t.seen++
            t.text = item.text
            t.say = item.say
            if (item.status != KmleCpx.STATUS_DONE) t.missed++
        }
    }
    return tally.filter { it.value.missed > 0 }
        .map { (k, t) -> WeakItem(k.first, t.text, t.say, t.missed, t.seen) }
        .sortedWith(compareByDescending<WeakItem> { it.missed.toDouble() / it.seen }.thenByDescending { it.missed })
}
