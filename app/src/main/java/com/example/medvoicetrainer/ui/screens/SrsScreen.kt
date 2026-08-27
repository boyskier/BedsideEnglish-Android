package com.example.medvoicetrainer.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.ErrorTrackerStats
import com.example.medvoicetrainer.db.ErrorItemEntity
import com.example.medvoicetrainer.export.AnkiExporter
import com.example.medvoicetrainer.export.SrsExportItem
import com.example.medvoicetrainer.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun dueSrsItems(
    items: List<ErrorItemEntity>,
    nowIso: String
): List<ErrorItemEntity> = items
    .filter {
        (it.domain in setOf("clinical", "everyday") ||
            it.category.startsWith("pronunciation", ignoreCase = true)) &&
            it.state != "mastered" &&
            it.state != "observed" &&
            it.dueAt <= nowIso
    }
    .sortedBy { it.dueAt }

/**
 * A single day's review deck is capped so a returning learner who let cards pile up meets a
 * finishable session, not an intimidating backlog (the classic SRS abandonment trap). The rest
 * stay due and surface again tomorrow. Oldest-due-first ordering means the cap never hides the
 * most overdue items.
 */
internal const val SRS_DAILY_CAP = 15

/** A due card only offers a "focus round" once its pattern has at least this many due cards. */
internal const val SRS_FOCUS_ROUND_MIN = 3

/**
 * Group key for a card in the focus-round chips: pronunciation items collapse to "pronunciation",
 * grammar/vocabulary items to their canonical L1 category ("articles", "konglish", …). Memorising a
 * fix and drilling a whole *rule family* in one sitting are different, complementary skills.
 */
internal fun srsFocusCategory(item: ErrorItemEntity): String =
    if (item.category.startsWith("pronunciation", ignoreCase = true)) {
        "pronunciation"
    } else {
        com.example.medvoicetrainer.analysis.L1Stats.normalizeCategory(item.category) ?: "other"
    }

/** Categories among [items] with at least [SRS_FOCUS_ROUND_MIN] cards, most-common first. */
internal fun srsFocusCategories(items: List<ErrorItemEntity>): List<Pair<String, Int>> =
    items.groupingBy { srsFocusCategory(it) }
        .eachCount()
        .filter { it.value >= SRS_FOCUS_ROUND_MIN }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }

/** Human label for a focus-round chip, reusing the L1 chart's category labels. */
internal fun srsFocusCategoryLabel(category: String, t: (String) -> String): String =
    if (category == "pronunciation") {
        t("srs.focus_pronunciation")
    } else {
        com.example.medvoicetrainer.analysis.L1Stats.CATEGORY_LABELS[category] ?: category
    }

private enum class SrsMode { PRACTICE, ALL_MISTAKES }

/**
 * Write the whole mistake list as a CSV file into the FileProvider-exposed cache dir. Returns null
 * when there is nothing exportable, so the caller can say so instead of handing the user an empty
 * file.
 */
private suspend fun exportErrorItemsToCsvFile(
    context: android.content.Context,
    items: List<ErrorItemEntity>
): java.io.File? = withContext(Dispatchers.IO) {
    val cards = AnkiExporter.extractSrsCards(
        items.map {
            SrsExportItem(
                original = it.original,
                corrected = it.corrected,
                explanation = it.explanation,
                category = it.category
            )
        }
    )
    if (cards.isEmpty()) return@withContext null
    val dir = java.io.File(context.cacheDir, "shared_cards").apply { mkdirs() }
    java.io.File(dir, "BedsideEnglish_Mistakes.csv").also {
        it.writeText(AnkiExporter.buildCsv(cards), Charsets.UTF_8)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrsScreen(
    viewModel: MainViewModel,
    onNavigateToTab: (Int) -> Unit = {},
    onStartCase: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val errorItems by viewModel.errorItems.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(SrsMode.PRACTICE) }

    val context = LocalContext.current
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val scope = rememberCoroutineScope()
    var csvExportStatus by remember { mutableStateOf<String?>(null) }
    var speakingReviewMessage by remember { mutableStateOf<String?>(null) }
    val startSpeakingReview: () -> Unit = {
        scope.launch {
            val review = viewModel.buildReviewSessionCase()
            if (review == null) {
                speakingReviewMessage = t(
                    "Not enough data yet — finish a few more practice sessions and I'll build a personalised drill from your specific mistakes."
                )
            } else {
                val (id, title, json) = review
                onStartCase(id, title, json)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = t("nav.my_mistakes"),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // Shares the mistake list as a real CSV file. The `.txt` version this replaced was
            // Anki-importable but nothing else could read it, so most share targets rendered it
            // as a wall of text. CSV opens in any spreadsheet app and still imports into Anki —
            // AnkiExporter.buildCsv keeps the `#separator`/`#columns`/`#tags column` directives.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val file = exportErrorItemsToCsvFile(context, errorItems)
                        if (file == null) {
                            csvExportStatus = t("srs.export_anki_none")
                            return@launch
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            // The real CSV type, so spreadsheet apps offer themselves in the
                            // chooser instead of the file arriving as pasted text.
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TITLE, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        csvExportStatus = null
                        context.startActivity(
                            Intent.createChooser(shareIntent, t("srs.export_csv_btn"))
                        )
                    }
                }
            ) {
                Text(t("srs.export_csv_btn"))
            }
        }

        csvExportStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Mode switch: flashcard drill (Practice) vs full mistake browser (All Mistakes),
        // ported from error_tracker_window.py which was a companion window to the drill,
        // not a replacement for it.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == SrsMode.PRACTICE,
                onClick = { mode = SrsMode.PRACTICE },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(t("Practice"))
            }
            SegmentedButton(
                selected = mode == SrsMode.ALL_MISTAKES,
                onClick = { mode = SrsMode.ALL_MISTAKES },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(t("All Mistakes"))
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                SrsMode.PRACTICE -> PracticeFlashcards(
                    viewModel = viewModel,
                    errorItems = errorItems,
                    t = t,
                    onSwitchToAllMistakes = { mode = SrsMode.ALL_MISTAKES },
                    onNavigateToTab = onNavigateToTab,
                    onStartSpeakingReview = startSpeakingReview,
                )
                SrsMode.ALL_MISTAKES -> ErrorTrackerBrowser(errorItems = errorItems, t = t)
            }
        }
    }

    speakingReviewMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { speakingReviewMessage = null },
            confirmButton = {
                TextButton(onClick = { speakingReviewMessage = null }) { Text(t("OK")) }
            },
            icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            title = { Text(t("Practice My Mistakes")) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun PracticeFlashcards(
    viewModel: MainViewModel,
    errorItems: List<ErrorItemEntity>,
    t: (String) -> String,
    onSwitchToAllMistakes: () -> Unit = {},
    onNavigateToTab: (Int) -> Unit = {},
    onStartSpeakingReview: () -> Unit = {},
) {
    val nowIso = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    }
    val allDueItems = remember(errorItems, nowIso) { dueSrsItems(errorItems, nowIso) }
    val focusCategories = remember(allDueItems) { srsFocusCategories(allDueItems) }
    var categoryFocus by remember { mutableStateOf<String?>(null) }
    // A focus can go stale as cards graduate; fall back to the full deck.
    val requestedFocus = categoryFocus
    val activeFocus = if (requestedFocus != null && focusCategories.any { it.first == requestedFocus }) requestedFocus else null
    val focusedDue = remember(allDueItems, activeFocus) {
        if (activeFocus == null) allDueItems else allDueItems.filter { srsFocusCategory(it) == activeFocus }
    }
    val dueItems = remember(focusedDue) { focusedDue.take(SRS_DAILY_CAP) }
    val deferredCount = focusedDue.size - dueItems.size
    val weeklyPronunciation = remember(errorItems) { viewModel.pronunciationWeeklyProgress() }
    var currentIndex by remember { mutableStateOf(0) }
    var isRevealed by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Every due card now has a spoken step — pronunciation cards get the say-it-again review,
    // all other cards get the transfer challenge (use the fix in a NEW sentence) — so the TTS
    // engine is spun up whenever any card is due.
    val ttsHandle = if (dueItems.isNotEmpty()) rememberEnglishTts() else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = t("Review your recorded grammatical slips and mispronunciations using spaced repetition."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        )
        PersonalizedSpeakingReviewCard(onStart = onStartSpeakingReview)
        if (weeklyPronunciation.attempts > 0) {
            val focus = weeklyPronunciation.focusPattern
                ?.replace('_', '/')
                ?.let { " · next focus: $it" }
                .orEmpty()
            Text(
                text = "Pronunciation this week: ${weeklyPronunciation.passed}/${weeklyPronunciation.attempts} clear " +
                    "(${weeklyPronunciation.passRatePercent}%)$focus",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (deferredCount > 0) {
            Text(
                text = t("srs.daily_cap_hint").replace("{count}", deferredCount.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            )
        }
        // Focus round: when one rule family has several cards due, offer to drill just that family
        // in one sitting — repeating "I have a headache" once doesn't fix the next dropped article,
        // but a whole articles round trains the rule, not the sentence.
        if (focusCategories.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeFocus == null,
                    onClick = { categoryFocus = null },
                    label = { Text(t("srs.focus_all")) }
                )
                focusCategories.forEach { (cat, count) ->
                    FilterChip(
                        selected = activeFocus == cat,
                        onClick = { categoryFocus = cat; currentIndex = 0; isRevealed = false },
                        label = { Text("${srsFocusCategoryLabel(cat, t)} ($count)") }
                    )
                }
            }
        }

        if (dueItems.isEmpty()) {
            // §13 "SRS: zero due" — "All caught up 🎉" + this week's graduation count, quiet
            // links onward. No fake urgency, no red — this is a good state, not an empty one.
            val weekAgoIso = remember(nowIso) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time)
            }
            val graduatedThisWeek = remember(errorItems, weekAgoIso) {
                errorItems.count {
                    (it.domain in setOf("clinical", "everyday") ||
                        it.category.startsWith("pronunciation", ignoreCase = true)) &&
                        it.state == "mastered" &&
                        it.lastSeen >= weekAgoIso
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = t("Perfect"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = t("All caught up 🎉"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (graduatedThisWeek > 0) {
                            "$graduatedThisWeek mistake${if (graduatedThisWeek == 1) "" else "s"} graduated this week."
                        } else {
                            t("No clinical mistakes due for review. Practice some patient encounters to capture new feedback!")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSwitchToAllMistakes) {
                            Text(t("All Mistakes"))
                        }
                        TextButton(onClick = { onNavigateToTab(1) }) {
                            Text(t("Start a session"))
                        }
                    }
                }
            }
        } else {
            val safeIndex = if (currentIndex >= dueItems.size) 0 else currentIndex
            if (currentIndex >= dueItems.size) {
                currentIndex = 0
            }

            val item = dueItems[safeIndex]

            // Progress Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("CARD") + " ${safeIndex + 1} " + t("OF") + " ${dueItems.size}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = t("Streak:") + " ${item.correctStreak}🔥",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Linear Progress Bar
            LinearProgressIndicator(
                progress = { (safeIndex + 1).toFloat() / dueItems.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .graphicsLayer { clip = true; shape = RoundedCornerShape(4.dp) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer
            )

            // Flashcard container
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .clickable { isRevealed = !isRevealed },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRevealed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!isRevealed) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = t("Question"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = t("INCORRECT UTTERANCE"),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "\"${item.original}\"",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = t("Tap Card to Reveal Correction"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LocalLibrary,
                            contentDescription = t("Answer"),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = t("SENIOR ATTENDING PHRASING"),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "\"${item.corrected}\"",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center
                        )
                        if (item.explanation.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = item.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Speaking review for pronunciation cards: hear the target, say it aloud, get an
            // intelligibility verdict that can grade the card. Manual grading below stays
            // available as the fallback (no Gemini key, no mic, or the user disagrees).
            val isPronunciationCard = item.category.contains("pronunciation", ignoreCase = true)
            if (isRevealed && isPronunciationCard && ttsHandle != null) {
                androidx.compose.runtime.key(item.key) {
                    var verdict by remember {
                        mutableStateOf<com.example.medvoicetrainer.analysis.SpeakingJudgment?>(null)
                    }
                    val drillStages = remember(item.key, item.seenCount) {
                        com.example.medvoicetrainer.analysis.PronunciationDrillBuilder.build(
                            target = item.corrected,
                            category = item.category,
                            domain = item.domain
                        )
                    }
                    var drillStageIndex by remember { mutableStateOf(0) }
                    val drillStage = drillStages.getOrNull(drillStageIndex)
                    val judgeAvailable = remember { viewModel.isSpeakingJudgeAvailable() }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                t("srs.speaking_review"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (drillStage != null) {
                                Text(
                                    "${drillStage.label} · ${drillStageIndex + 1}/${drillStages.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (drillStage.instruction.isNotBlank()) {
                                    Text(
                                        drillStage.instruction,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                            { pcm ->
                                                viewModel.judgeSpeakingAttempt(
                                                    targetText = drillStage.text,
                                                    audioPcm = pcm,
                                                    focusHint = item.explanation
                                                )
                                            }
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
                            verdict?.let { v ->
                                Button(
                                    onClick = {
                                        viewModel.submitSrsAnswer(item, v.pass)
                                        verdict = null
                                        isRevealed = false
                                        currentIndex = (safeIndex + 1) % dueItems.size
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        t("pron.apply_result") +
                                            " · " + t(if (v.pass) "GOT IT" else "STILL LEARNING")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Transfer challenge for every non-pronunciation card: retrieve the corrected form
            // inside a NEW situation (deterministic frame, rotated by seenCount so each review
            // of the same mistake gets a different scene). An AI verdict can grade the card;
            // the manual buttons below stay as the fallback.
            if (isRevealed && !isPronunciationCard && ttsHandle != null) {
                androidx.compose.runtime.key(item.key) {
                    val transferPrompt = remember(item.key, item.seenCount, item.patternId) {
                        com.example.medvoicetrainer.analysis.TransferDrillEngine.buildTransferPrompt(
                            corrected = item.corrected,
                            domain = item.domain,
                            variantIndex = item.seenCount,
                            category = item.category,
                            patternId = item.patternId
                        )
                    }
                    var verdict by remember {
                        mutableStateOf<com.example.medvoicetrainer.analysis.SpeakingJudgment?>(null)
                    }
                    val judgeAvailable = remember { viewModel.isSpeakingJudgeAvailable() }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                t("srs.transfer_challenge"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                transferPrompt.scenario,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                t("srs.transfer_instruction") + " \"${transferPrompt.targetPhrase}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PronunciationAttemptPanel(
                                targetText = item.corrected,
                                ttsReady = ttsHandle.ready,
                                onSpeakTarget = { ttsHandle.speak(transferPrompt.scenario) },
                                judge = if (judgeAvailable) {
                                    { pcm ->
                                        viewModel.judgeTransferAttempt(
                                            scenario = transferPrompt.scenario,
                                            targetText = item.corrected,
                                            originalText = item.original,
                                            audioPcm = pcm
                                        )
                                    }
                                } else {
                                    null
                                },
                                onVerdict = { verdict = it }
                            )
                            verdict?.let { v ->
                                Button(
                                    onClick = {
                                        viewModel.submitSrsAnswer(item, v.pass)
                                        verdict = null
                                        isRevealed = false
                                        currentIndex = (safeIndex + 1) % dueItems.size
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        t("pron.apply_result") +
                                            " · " + t(if (v.pass) "GOT IT" else "STILL LEARNING")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Control Buttons
            if (isRevealed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.submitSrsAnswer(item, false)
                            isRevealed = false
                            currentIndex = (safeIndex + 1) % dueItems.size
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(t("STILL LEARNING"))
                    }
                    Button(
                        onClick = {
                            viewModel.submitSrsAnswer(item, true)
                            isRevealed = false
                            currentIndex = (safeIndex + 1) % dueItems.size
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(t("GOT IT"))
                    }
                }
            } else {
                Button(
                    onClick = { isRevealed = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("REVEAL CORRECT PHRASING"))
                }
            }
        }
    }
}

@Composable
private fun PersonalizedSpeakingReviewCard(onStart: () -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = t("Practice My Mistakes"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = t("Speak the corrections from your previous sessions in a short, personalised conversation."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStart) {
                    Text(t("Speak through my mistakes"))
                }
            }
        }
    }
}

/**
 * Compose port of `error_tracker_window.py`'s `ErrorTrackerWindow`: browse every tracked
 * mistake with its mastery state, streak progress and next-review date. A `LazyColumn` of
 * expandable cards stands in for the desktop Treeview + fixed detail pane — tapping a card
 * expands it in place to show the full was/aim/why text, instead of a separate panel.
 */
@Composable
private fun ErrorTrackerBrowser(
    errorItems: List<ErrorItemEntity>,
    t: (String) -> String
) {
    // Accepted clinical/everyday mistakes plus confirmed pronunciation items. A single
    // pronunciation observation remains hidden until it recurs in another session.
    val trackedItems = remember(errorItems) {
        errorItems.filter {
            (it.domain in setOf("clinical", "everyday") ||
                it.category.startsWith("pronunciation", ignoreCase = true)) &&
                it.state != "observed"
        }
    }
    val stats = remember(trackedItems) { ErrorTrackerStats.stats(trackedItems) }
    val sortedItems = remember(trackedItems) { ErrorTrackerStats.sortedForDisplay(trackedItems) }
    val dueNow = remember(sortedItems) { sortedItems.count { ErrorTrackerStats.dueStr(it) == "due now" } }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (stats.total == 0) {
            Text(
                text = t("error_tracker.no_mistakes"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = t("Finish a Patient Encounter and your tagged language errors will start showing up here, then graduate as you master them."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            return
        }

        // Summary line, mirroring the Python f-string exactly (only the "due now" phrase is
        // routed through i18n there too — "active"/"mastered"/"stubborn" are left literal):
        // f"{active} active · {mastered} mastered · {due_now} due now" + optional leech clause.
        val summary = buildString {
            append("${stats.active} active · ${stats.mastered} mastered · $dueNow ")
            append(t("error_tracker.due_now"))
            if (stats.leech > 0) {
                append(" · ⚠ ${stats.leech} stubborn")
            }
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = t("error_tracker.hint_text"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        // Progress-dots legend. The translated strings already embed their icon
        // (e.g. "● = correct review"), matching error_tracker_window.py's legend_items.
        val legendScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(legendScrollState),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendEntry(t("error_tracker.progress_dots_legend"), bold = true)
            LegendEntry(t("error_tracker.progress_correct"))
            LegendEntry(t("error_tracker.progress_remaining"))
            LegendEntry(t("error_tracker.progress_stubborn"))
            LegendEntry(t("error_tracker.progress_mastered"))
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedItems, key = { it.key }) { item ->
                ErrorTrackerRow(item = item)
            }
        }
    }
}

@Composable
private fun LegendEntry(text: String, bold: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    )
}

@Composable
private fun ErrorTrackerRow(item: ErrorItemEntity) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    var expanded by remember { mutableStateOf(false) }
    val leech = ErrorTrackerStats.isLeech(item)
    val titleColor = when {
        leech -> MaterialTheme.colorScheme.error
        item.state == "mastered" -> androidx.compose.ui.graphics.Color(0xFF15803D)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ErrorTrackerStats.stateIcon(item),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = ErrorTrackerStats.mistakeLine(item),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = item.category.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ErrorTrackerStats.progressDots(item),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${if (item.seenCount > 0) item.seenCount else 1} ${t("repeats")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Text(
                    text = ErrorTrackerStats.dueStr(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            if (expanded) {
                val was = item.original.trim()
                val aim = item.corrected.trim()
                val why = item.explanation.trim()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (was.isNotEmpty()) {
                        Text(text = "${t("Was:")}  $was", style = MaterialTheme.typography.bodySmall)
                    }
                    if (aim.isNotEmpty()) {
                        Text(text = "${t("Aim:")}  $aim", style = MaterialTheme.typography.bodySmall)
                    }
                    if (why.isNotEmpty()) {
                        Text(text = "${t("Why:")}  $why", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
