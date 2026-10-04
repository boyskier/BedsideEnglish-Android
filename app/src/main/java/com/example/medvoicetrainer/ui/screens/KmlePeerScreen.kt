package com.example.medvoicetrainer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.medvoicetrainer.analysis.KmleCpx
import com.example.medvoicetrainer.analysis.KmlePeer
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.StationChime
import com.example.medvoicetrainer.voice.PeerRecorder
import kotlinx.coroutines.delay

/**
 * 친구와 역할극: one phone between two students. The "doctor" reads the problem sheet, the partner
 * reads the patient's role card (from the case's authored script), the phone records the station,
 * and the recording is transcribed and graded by the same checklist as an AI-patient station.
 */
@Composable
fun KmlePeerScreen(viewModel: MainViewModel, launch: MainViewModel.KmleCpxLaunch, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val session = remember(launch.caseJson) { KmleCpx.sessionCase(launch.caseJson) }
    val stage by viewModel.kmlePeerStage.collectAsStateWithLifecycle()
    val error by viewModel.kmlePeerError.collectAsStateWithLifecycle()
    val recorder = remember { PeerRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf<java.io.File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var startError by remember { mutableStateOf<String?>(null) }
    val stationSeconds = (session?.common?.stationMinutes ?: 12).coerceAtLeast(1) * 60

    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            recorder.stopQuietly()
        }
    }
    // Same rule as the disabled back arrow: Back must not silently drop a station being recorded.
    androidx.activity.compose.BackHandler(enabled = recording) { }
    LaunchedEffect(recording) {
        view.keepScreenOn = recording
        var warned = false
        var ended = false
        while (recording) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (!warned && elapsed >= stationSeconds - 120) {
                warned = true
                StationChime.play(double = false)
            }
            if (!ended && elapsed >= stationSeconds) {
                ended = true
                StationChime.play(double = true)
            }
            delay(500)
        }
    }

    fun stopAndGrade() {
        val file = recorder.stop()
        recording = false
        recorded = file
        if (file == null) {
            startError = "녹음된 소리가 없어요. 마이크를 확인하고 다시 녹음해 주세요."
        } else {
            viewModel.gradeKmlePeerRecording(file, elapsed)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !recording && stage == null) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "닫기")
                }
                Column(Modifier.weight(1f)) {
                    Text("친구와 역할극 · AI 채점", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text(launch.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("학생의사") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("환자 역할 (친구용)") })
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (session == null) {
                    Text("이 스테이션을 불러오지 못했어요.")
                } else if (tab == 0) {
                    KmleProblemSheet(session.situationCard)
                    Text(
                        "휴대폰을 두 사람 사이에 두고 녹음을 시작하세요. 진찰은 실제로 하면서 무엇을 하는지 말로도 해 주세요 — " +
                            "채점은 녹음된 말로만 합니다. 끝나면 받아 적은 대화로 체크리스트, PPI, 의학 내용을 채점합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    RoleCardView(KmlePeer.roleCard(session))
                }
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val busy = stage != null
                    when {
                        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(if (stage == "transcribing") "녹음을 받아 적는 중이에요…" else "채점하는 중이에요…")
                        }
                        recording -> {
                            val warn = elapsed >= stationSeconds - 120
                            Text(
                                "● 녹음 중 %02d:%02d / %02d:00".format(elapsed / 60, elapsed % 60, stationSeconds / 60) +
                                    if (elapsed >= stationSeconds) " · 시험 종료" else if (warn) " · 종료 2분 전" else "",
                                color = if (warn) Color(0xFFE65100) else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    (error ?: startError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!recording) {
                            Button(
                                onClick = {
                                    startError = null
                                    if (!hasMic) {
                                        permission.launch(Manifest.permission.RECORD_AUDIO)
                                        return@Button
                                    }
                                    runCatching { recorder.start() }
                                        .onSuccess {
                                            recorded = null
                                            startedAt = System.currentTimeMillis()
                                            elapsed = 0
                                            recording = true
                                        }
                                        .onFailure { startError = "녹음을 시작하지 못했어요: ${it.message}" }
                                },
                                enabled = !busy && session != null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (recorded == null) "녹음 시작" else "다시 녹음")
                            }
                            val retry = recorded
                            if (retry != null && error != null && !busy) {
                                OutlinedButton(onClick = { viewModel.gradeKmlePeerRecording(retry, elapsed) }, modifier = Modifier.weight(1f)) {
                                    Text("다시 채점")
                                }
                            }
                        } else {
                            Button(
                                onClick = ::stopAndGrade,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("종료하고 채점")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleCardView(card: KmlePeer.RoleCard) {
    Surface(color = Color(0xFFFFF3CD), shape = RoundedCornerShape(10.dp)) {
        Text(
            "학생의사 역할은 이 화면을 보지 마세요. 환자 역할을 맡은 친구만 읽습니다.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.who, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(card.speakerNote, style = MaterialTheme.typography.bodyMedium)
            if (card.complaint.isNotBlank()) Text("오늘 온 이유: ${card.complaint}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Text("연기 규칙", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            card.rules.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            val ice = listOf("생각" to card.ideas, "걱정" to card.concerns, "기대" to card.expectations).filter { it.second.isNotBlank() }
            if (ice.isNotEmpty()) {
                HorizontalDivider()
                Text("생각 · 걱정 · 기대 (물어보면 말하기, 병명은 말하지 않기)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                ice.forEach { (label, text) -> Text("$label: $text", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (card.facts.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("대본 (그 주제를 물으면 한국어로 짧게)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                card.facts.forEach { fact ->
                    Column {
                        Text(fact.topic, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(fact.answer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    } else {
        Text(
            "이 증례에는 아직 상세 대본이 없어요. 위의 생각·걱정·기대와 상식적인 선에서 연기하고, 모르는 건 \"잘 모르겠어요\"로 답하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (card.examReactions.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("진찰 반응표", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                card.examReactions.forEach { (name, pain) ->
                    Text(
                        "• $name: " + if (pain) "아파하기 (\"아, 거기 아파요\")" else "아프지 않음",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pain) Color(0xFFC62828) else Color.Unspecified,
                        fontWeight = if (pain) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
