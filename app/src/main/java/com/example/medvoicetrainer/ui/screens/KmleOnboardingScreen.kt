package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.medvoicetrainer.analysis.UnlockSheet
import com.example.medvoicetrainer.api.ProviderStatus
import com.example.medvoicetrainer.ui.MainViewModel

/**
 * First run for the Korean CPX track, in Korean. The English onboarding tour is about practising
 * medical English, which is not what someone who picked this track came for; this screen says what
 * the CPX practice does, and connects a free Gemini key (verified before it is saved) or starts
 * without one — study sheets and the offline demo patient work either way.
 */
@Composable
fun KmleOnboardingScreen(viewModel: MainViewModel) {
    val uriHandler = LocalUriHandler.current
    val statuses by viewModel.providerStatusMap.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var formatError by remember { mutableStateOf(false) }
    val status = statuses["gemini"]?.status
    val verified = submitted != null && status == ProviderStatus.VERIFIED

    fun verify() {
        val clean = UnlockSheet.normalizePastedGeminiKey(draft)
        draft = clean
        if (!UnlockSheet.looksLikeGeminiKey(clean)) {
            formatError = true
            submitted = null
            viewModel.clearProviderVerification("gemini")
            return
        }
        formatError = false
        submitted = clean
        viewModel.verifyProviderKey("gemini", clean)
    }

    LaunchedEffect(verified) {
        val key = submitted
        if (verified && key != null) {
            runCatching { viewModel.completeOnboardingWithGeminiKey(key, "ko") }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.MedicalServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("CPX, 혼자서도 친구와도", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(
                "국시 실기 진료문항을 그대로 연습합니다. 문제지를 읽고 한국어 표준화 환자와 12분 동안 진료하면, " +
                    "체크리스트·환자-의사 관계(PPI)·의학 내용으로 바로 채점해 드려요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "국시 48개 임상표현 전부 + 보충 스테이션, 1,100개가 넘는 증례",
                        "2026년 국시 형식 문제지 · 종료 2분 전 알림 · 실전 모의고사",
                        "친구와 역할극을 녹음하면 AI가 채점 (친구용 환자 대본 제공)",
                        "놓친 항목, 모범 멘트, 실제 진찰 소견까지 보여 주는 결과표",
                        "무료 · 광고 없음 · 기록은 이 기기에만 저장",
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Text("시작하기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(
                "AI 환자와 채점은 본인의 Gemini API 키로 작동합니다. Google AI Studio에서 무료 키를 만들면 비용 없이 쓸 수 있어요.\n" +
                    "1. 아래 버튼으로 AI Studio를 열고 Google 계정으로 로그인\n2. 'Create API key'로 키 만들기\n3. 키를 복사해 아래에 붙여넣기",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") } }) {
                Text("무료 키 만들기 (AI Studio)")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    submitted = null
                    formatError = false
                    viewModel.clearProviderVerification("gemini")
                },
                label = { Text("Gemini API 키") },
                singleLine = true,
                isError = formatError || (submitted != null && status == ProviderStatus.INVALID_KEY),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                formatError -> Text("Gemini 키 형식이 아니에요. 'AIza'로 시작하는 키를 붙여넣어 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                submitted != null && status == ProviderStatus.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("키를 확인하는 중…", style = MaterialTheme.typography.bodySmall)
                }
                submitted != null && status != null && status != ProviderStatus.VERIFIED && status != ProviderStatus.VERIFYING ->
                    Text(
                        statuses["gemini"]?.message?.takeIf { it.isNotBlank() } ?: "키를 확인하지 못했어요. 다시 확인해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            Button(onClick = ::verify, enabled = draft.isNotBlank() && status != ProviderStatus.VERIFYING, modifier = Modifier.fillMaxWidth()) {
                Text("키 확인하고 시작")
            }
            Text(
                "무료 등급에서는 Google이 대화 내용을 서비스 개선에 쓸 수 있어요. 실제 환자 정보나 개인 정보는 말하지 마세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            TextButton(onClick = { viewModel.completeOnboardingWithDemo("ko") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("키 없이 먼저 둘러보기 (공부하기·체험 환자)")
            }
        }
    }
}
