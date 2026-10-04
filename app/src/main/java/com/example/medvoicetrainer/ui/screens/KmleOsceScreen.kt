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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.KmleOsce
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.StationChime
import kotlinx.coroutines.delay

/** 기본진료술기: the nine skills by group, then one skill's checklist with a 12-minute self-check. */
@Composable
fun KmleOsceScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    var skills by remember { mutableStateOf<List<KmleOsce.Skill>?>(null) }
    LaunchedEffect(Unit) { skills = viewModel.loadKmleOsceSkills() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = skills?.firstOrNull { it.id == openId }
    if (open != null) {
        androidx.activity.compose.BackHandler { openId = null }
        OsceSkillSheet(open, onBack = { openId = null })
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
                Text("기본진료술기 (OSCE)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            Text(
                "시험의 10번째 방은 모형이나 표준화 환자에게 술기를 하는 12분 스테이션입니다. 손기술은 앱이 채점할 수 없어서, " +
                    "실제로 연습할 때 옆에 두고 단계를 체크하는 셀프 체크리스트로 준비했어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val list = skills
        if (list == null) {
            item { CircularProgressIndicator() }
        } else if (list.isEmpty()) {
            item { Text("술기 자료가 아직 없어요.") }
        } else {
            list.groupBy { it.group }.forEach { (group, rows) ->
                item(key = "g_$group") {
                    Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(rows, key = { "osce_${it.id}" }) { skill ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable { openId = skill.id },
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(skill.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("${skill.steps.size}단계 · 핵심 ${skill.steps.count { it.key }}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OsceSkillSheet(skill: KmleOsce.Skill, onBack: () -> Unit) {
    val view = LocalView.current
    var ticked by rememberSaveable(skill.id) { mutableStateOf(setOf<String>()) }
    var running by rememberSaveable(skill.id) { mutableStateOf(false) }
    var startedAt by rememberSaveable(skill.id) { mutableLongStateOf(0L) }
    var elapsed by remember(skill.id) { mutableIntStateOf(0) }
    var finished by rememberSaveable(skill.id) { mutableStateOf(false) }
    val limit = 12 * 60
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }
    LaunchedEffect(running) {
        view.keepScreenOn = running
        var warned = false
        while (running) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (!warned && elapsed >= limit - 120) { warned = true; StationChime.play(double = false) }
            if (elapsed >= limit) { StationChime.play(double = true); running = false; finished = true }
            delay(500)
        }
    }
    val check = KmleOsce.selfCheck(skill, ticked)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "뒤로") }
                Column(Modifier.weight(1f)) {
                    Text(skill.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(skill.officialSkill.ifBlank { skill.group }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (skill.scenario.isNotBlank()) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFFDF7), contentColor = Color(0xFF1B1B1B), border = BorderStroke(1.dp, Color(0xFFD6D0C4))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("문제 (제한시간 12분)", fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(skill.scenario, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running || finished) "%02d:%02d / 12:00".format(elapsed / 60, elapsed % 60) else "셀프 체크 12:00",
                            fontWeight = FontWeight.Bold,
                            color = if (elapsed >= limit - 120 && (running || finished)) Color(0xFFE65100) else Color.Unspecified,
                        )
                        Text("체크 ${check.done} / ${check.total} (${check.percent}%)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!running) {
                        Button(onClick = {
                            ticked = emptySet(); finished = false; elapsed = 0
                            startedAt = System.currentTimeMillis(); running = true
                        }) { Text(if (finished) "다시" else "시작") }
                    } else {
                        OutlinedButton(onClick = { running = false; finished = true }) { Text("끝") }
                    }
                }
            }
        }
        if (finished && check.missedKey.isNotEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("빠뜨린 핵심 단계", fontWeight = FontWeight.Black)
                        check.missedKey.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (skill.equipment.isNotEmpty()) {
            item { Text("준비물: " + skill.equipment.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
        }
        KmleOsce.PHASES.forEach { phase ->
            val steps = skill.steps.filter { it.phase == phase }
            if (steps.isNotEmpty()) {
                item(key = "phase_$phase") { Text(phase, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                items(steps, key = { "step_${it.id}" }) { step ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ticked = if (step.id in ticked) ticked - step.id else ticked + step.id },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = step.id in ticked, onCheckedChange = { on -> ticked = if (on) ticked + step.id else ticked - step.id })
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(
                                step.text + if (step.key) " ★" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (step.key) FontWeight.Bold else FontWeight.Normal,
                                color = if (step.key) Color(0xFFC62828) else Color.Unspecified,
                            )
                            if (step.say.isNotBlank()) Text("\"${step.say}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (skill.commonErrors.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("자주 하는 실수", fontWeight = FontWeight.Black)
                        skill.commonErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (skill.notes.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("꼭 기억할 것", fontWeight = FontWeight.Black)
                        skill.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
