package com.example.medvoicetrainer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.PronunciationDrillBuilder
import com.example.medvoicetrainer.analysis.PronunciationEvidencePolicy
import com.example.medvoicetrainer.db.ErrorItemEntity
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.theme.AccentAmber
import com.example.medvoicetrainer.ui.theme.DangerRedStrong
import com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Filtering / status helpers kept pure so they can be unit-tested without Compose. */
internal object PronunciationLab {

    fun isPronunciation(item: ErrorItemEntity): Boolean =
        item.category.startsWith("pronunciation", ignoreCase = true)

    fun pronunciationItems(items: List<ErrorItemEntity>): List<ErrorItemEntity> =
        items.filter(::isPronunciation)

    /** Human display label for a stored engine pattern (the part after "pronunciation:"). */
    fun patternLabel(pattern: String): String = when (pattern) {
        "r_l" -> "r · l"
        "f_p" -> "f · p"
        "th" -> "th"
        "final_consonant" -> "final"
        "consonant_cluster" -> "cluster"
        "word_stress" -> "stress"
        "vowel" -> "vowel"
        else -> "other"
    }

    /** Distinct patterns present, most-common first — drives the filter chips. */
    fun patternsPresent(items: List<ErrorItemEntity>): List<String> =
        pronunciationItems(items)
            .groupingBy { PronunciationEvidencePolicy.pattern(it.category) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    enum class Mastery { DUE, LEARNING, OBSERVED, DONE }

    fun mastery(item: ErrorItemEntity, nowIso: String): Mastery = when {
        item.state == "mastered" -> Mastery.DONE
        item.state == "observed" -> Mastery.OBSERVED
        item.dueAt <= nowIso -> Mastery.DUE
        else -> Mastery.LEARNING
    }

    /** Rough completeness bar derived from the SRS state ladder. */
    fun progress(item: ErrorItemEntity): Float = when (item.state) {
        "mastered" -> 1f
        "review" -> 0.75f
        "learning" -> 0.45f
        "observed" -> 0.08f
        else -> 0.18f
    }

    fun dueCount(items: List<ErrorItemEntity>, nowIso: String): Int =
        pronunciationItems(items).count { mastery(it, nowIso) == Mastery.DUE }

    fun masteredCount(items: List<ErrorItemEntity>): Int =
        pronunciationItems(items).count { it.state == "mastered" }

    /** Single-session findings not yet confirmed by a second session — de-emphasized, not homework. */
    fun observedCount(items: List<ErrorItemEntity>): Int =
        pronunciationItems(items).count { it.state == "observed" }
}

/**
 * Pronunciation Lab — the standing home for every pronunciation correction the app has ever
 * captured, so a learner can drill them any time instead of only in the moments right after a
 * session. Data already lives in [MainViewModel.errorItems] (routed there with a
 * `pronunciation:*` category); this screen is a filtered view + the same listen→record→AI-check
 * loop the post-session and SRS flows already use.
 */
@Composable
fun PronunciationLabScreen(viewModel: MainViewModel, onNavigateToTab: (Int) -> Unit = {}) {
    val errorItems by viewModel.errorItems.collectAsStateWithLifecycle()
    val pronItems = remember(errorItems) { PronunciationLab.pronunciationItems(errorItems) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = remember(pronItems, selectedKey) { pronItems.find { it.key == selectedKey } }

    if (selected != null) {
        BackHandler { selectedKey = null }
        PronunciationPracticeDetail(
            viewModel = viewModel,
            item = selected,
            onBack = { selectedKey = null }
        )
    } else {
        PronunciationLabHub(
            viewModel = viewModel,
            pronItems = pronItems,
            onOpen = { selectedKey = it.key },
            onNavigateToTab = onNavigateToTab
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PronunciationLabHub(
    viewModel: MainViewModel,
    pronItems: List<ErrorItemEntity>,
    onOpen: (ErrorItemEntity) -> Unit,
    onNavigateToTab: (Int) -> Unit
) {
    val t = LocalTranslate.current
    val nowIso = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) }

    val patterns = remember(pronItems) { PronunciationLab.patternsPresent(pronItems) }
    var patternFilter by remember { mutableStateOf<String?>(null) } // null = all
    // A filter can go stale if the underlying item graduates/vanishes; fall back to "all".
    // (Local capture so the null check smart-casts — a `by remember` delegate wouldn't.)
    val requested = patternFilter
    val activeFilter = if (requested != null && requested in patterns) requested else null

    val shown = remember(pronItems, activeFilter) {
        val base = if (activeFilter == null) pronItems
        else pronItems.filter { PronunciationEvidencePolicy.pattern(it.category) == activeFilter }
        // Due first, then still-learning, then unconfirmed observations, mastered last; stable
        // within a group by recency.
        base.sortedWith(
            compareBy<ErrorItemEntity> {
                when (PronunciationLab.mastery(it, nowIso)) {
                    PronunciationLab.Mastery.DUE -> 0
                    PronunciationLab.Mastery.LEARNING -> 1
                    PronunciationLab.Mastery.OBSERVED -> 2
                    PronunciationLab.Mastery.DONE -> 3
                }
            }.thenByDescending { it.lastSeen }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = t("nav.pron_lab"),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )

        if (pronItems.isEmpty()) {
            EmptyPronunciationLab(t = t, onNavigateToTab = onNavigateToTab)
            return@Column
        }

        // Summary tiles.
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(t("pron_lab.total"), pronItems.size.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile(t("pron_lab.due"), PronunciationLab.dueCount(pronItems, nowIso).toString(), DangerRedStrong, Modifier.weight(1f))
            StatTile(t("pron_lab.mastered"), PronunciationLab.masteredCount(pronItems).toString(), SuccessGreenStrong, Modifier.weight(1f))
        }

        // Honest visibility for the confirmation policy: a single-session finding is shown but
        // explained, so the lab never looks empty right after a session yet never turns one noisy
        // model finding into homework before a second session confirms it.
        val observedCount = remember(pronItems) { PronunciationLab.observedCount(pronItems) }
        if (observedCount > 0) {
            Text(
                text = t("pron_lab.observed_hint").replace("{count}", observedCount.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Pattern filter chips.
        if (patterns.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeFilter == null,
                    onClick = { patternFilter = null },
                    label = { Text(t("pron_lab.filter_all")) }
                )
                patterns.forEach { p ->
                    FilterChip(
                        selected = activeFilter == p,
                        onClick = { patternFilter = p },
                        label = { Text(PronunciationLab.patternLabel(p)) }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.key }) { item ->
                PronunciationCard(item = item, nowIso = nowIso, t = t, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = valueColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PronunciationCard(
    item: ErrorItemEntity,
    nowIso: String,
    t: (String) -> String,
    onClick: () -> Unit
) {
    val mastery = PronunciationLab.mastery(item, nowIso)
    val (stateLabel, stateColor) = when (mastery) {
        PronunciationLab.Mastery.DUE -> t("pron_lab.state.due") to DangerRedStrong
        PronunciationLab.Mastery.LEARNING -> t("pron_lab.state.learning") to AccentAmber
        PronunciationLab.Mastery.OBSERVED ->
            t("pron_lab.state.observed") to MaterialTheme.colorScheme.onSurfaceVariant
        PronunciationLab.Mastery.DONE -> t("pron_lab.state.mastered") to SuccessGreenStrong
    }
    val pattern = PronunciationEvidencePolicy.pattern(item.category)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.corrected,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.original.isNotBlank() && !item.original.equals(item.corrected, ignoreCase = true)) {
                        Text(
                            item.original,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        PronunciationLab.patternLabel(pattern),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { PronunciationLab.progress(item) },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = stateColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = stateColor)
            }
        }
    }
}

@Composable
private fun EmptyPronunciationLab(t: (String) -> String, onNavigateToTab: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(t("pron_lab.empty_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                t("pron_lab.empty_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = { onNavigateToTab(1) }) { Text(t("Start a session")) }
        }
    }
}

/**
 * Full-focus practice for one collected word: the same ladder (target → contrast → phrase → new
 * context) and the same [PronunciationAttemptPanel] used in SRS, so an AI pass/fail here feeds the
 * exact same spaced-repetition schedule via [MainViewModel.submitSrsAnswer].
 */
@Composable
private fun PronunciationPracticeDetail(
    viewModel: MainViewModel,
    item: ErrorItemEntity,
    onBack: () -> Unit
) {
    val t = LocalTranslate.current
    val ttsHandle = rememberEnglishTts()
    val scrollState = rememberScrollState()

    val drillStages = remember(item.key, item.seenCount) {
        PronunciationDrillBuilder.build(target = item.corrected, category = item.category, domain = item.domain)
    }
    var drillStageIndex by remember(item.key) { mutableStateOf(0) }
    val drillStage = drillStages.getOrNull(drillStageIndex)
    val judgeAvailable = remember { viewModel.isSpeakingJudgeAvailable() }
    var verdict by remember(item.key) { mutableStateOf<com.example.medvoicetrainer.analysis.SpeakingJudgment?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = t("common.back"))
            }
            Text(t("pron_lab.practice_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        // The target word + its coaching tip.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.corrected, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                if (item.explanation.isNotBlank()) {
                    Text(item.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }

        if (drillStage != null) {
            Text(
                "${drillStage.label} · ${drillStageIndex + 1}/${drillStages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${drillStage.text}\"", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (drillStage.instruction.isNotBlank()) {
                        Text(
                            drillStage.instruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val minimalPair = drillStage.minimalPair
                    var discriminationPassed by remember(item.key, drillStageIndex) { mutableStateOf(false) }
                    if (minimalPair != null && !discriminationPassed) {
                        PerceptionDiscriminationQuiz(
                            pair = minimalPair,
                            ttsReady = ttsHandle.ready,
                            onSpeak = { ttsHandle.speak(it) },
                            onPassed = { discriminationPassed = true }
                        )
                    } else {
                        PronunciationAttemptPanel(
                            targetText = drillStage.text,
                            ttsReady = ttsHandle.ready,
                            onSpeakTarget = { ttsHandle.speak(drillStage.text) },
                            judge = if (judgeAvailable) {
                                { pcm -> viewModel.judgeSpeakingAttempt(targetText = drillStage.text, audioPcm = pcm, focusHint = item.explanation) }
                            } else {
                                null
                            },
                            onVerdict = { result ->
                                if (result.pass && drillStageIndex < drillStages.lastIndex) {
                                    drillStageIndex += 1
                                    verdict = null
                                } else {
                                    verdict = result
                                }
                            }
                        )
                    }
                }
            }
        }

        verdict?.let { v ->
            Button(
                onClick = {
                    viewModel.submitSrsAnswer(item, v.pass)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t("pron.apply_result") + " · " + t(if (v.pass) "GOT IT" else "STILL LEARNING"))
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
