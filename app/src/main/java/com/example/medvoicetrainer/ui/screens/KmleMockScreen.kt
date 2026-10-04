package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
 * The mock-exam circuit: between stations it shows the next room's problem sheet (the exam posts it
 * inside the room, so it appears only once the learner "enters"), and after the last station the
 * summary with the estimated verdict. Station cards stay hidden until the end, as on exam day.
 */
@Composable
fun KmleMockExamScreen(
    viewModel: MainViewModel,
    mock: MainViewModel.KmleMockExam,
    onEnter: (MainViewModel.KmleCpxLaunch) -> Unit,
) {
    val sessions by viewModel.kmleSessions.collectAsStateWithLifecycle()
    var confirmQuit by remember { mutableStateOf(false) }

    if (mock.finished) {
        val rows = mock.sessionIds.map { id -> sessions.firstOrNull { it.id == id } }
        val scores = rows.map { s -> s?.let { KmleCpxScorecard.build(it.rawCaseJson, it.rawEvalJson)?.overall } }
        val verdict = KmleCpx.mockVerdict(scores)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (verdict.passed) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("모의고사 결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            "${verdict.total} / ${verdict.max}점 · 통과 스테이션 ${verdict.stationsPassed} / ${scores.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (verdict.passed) "추정 결과: 합격권" else "추정 결과: 불합격권",
                            color = if (verdict.passed) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "참고용 추정입니다. 실제 시험은 스테이션마다 전문가 패널이 정한 기준점수의 합과 통과 스테이션 수로 판정하며, " +
                                "기준점수는 공개되지 않습니다. 여기서는 총점 ${KmleCpx.MOCK_TOTAL_CUT_PERCENT}% 이상, " +
                                "스테이션 ${KmleCpx.MOCK_STATION_CUT}점 이상을 ${verdict.stationsNeeded}개 이상으로 계산했습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (scores.any { it == null }) {
                            Text(
                                "채점되지 않은 스테이션은 0점으로 계산했어요. 기록에서 '채점하기'를 누르면 다시 채점할 수 있어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            itemsIndexed(rows) { i, session ->
                val score = scores[i]
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = session != null) {
                        session?.let(viewModel::openKmleResult)
                    },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mock.launches.getOrNull(i)?.title ?: "${i + 1}번", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if ((score ?: 0) >= KmleCpx.MOCK_STATION_CUT) "통과(추정)" else "미통과(추정)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            score?.let { "${it}점" } ?: "미채점",
                            fontWeight = FontWeight.Black,
                            color = when {
                                score == null -> Color.Gray
                                score >= KmleCpx.MOCK_STATION_CUT -> Color(0xFF2E7D32)
                                else -> Color(0xFFC62828)
                            },
                        )
                    }
                }
            }
            item {
                Button(onClick = viewModel::abandonKmleMockExam, modifier = Modifier.fillMaxWidth()) { Text("모의고사 마치기") }
            }
        }
        return
    }

    val next = mock.current ?: return
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("모의고사 진행 중", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(
            "시험실 ${mock.index + 1} / ${mock.launches.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        LinearProgressIndicator(
            progress = { mock.index / mock.launches.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (mock.index == 0) "실제 시험처럼 진행합니다. 스테이션마다 12분이 지나면 자동으로 끝나고, 진찰 소견이나 힌트는 보이지 않습니다. " +
                "결과는 마지막 스테이션이 끝난 뒤 한꺼번에 보여 드려요."
            else "다음 시험실로 이동하세요. 실제 시험에서는 3분 동안 다음 방 앞에서 기다립니다. 준비되면 입실하세요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = { onEnter(next) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("입실 · 시험 시작", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { confirmQuit = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("모의고사 중단")
        }
    }
    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("모의고사를 중단할까요?") },
            text = { Text("지금까지 본 스테이션은 기록에 남습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmQuit = false
                    viewModel.abandonKmleMockExam()
                }) { Text("중단") }
            },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("계속") } },
        )
    }
}
