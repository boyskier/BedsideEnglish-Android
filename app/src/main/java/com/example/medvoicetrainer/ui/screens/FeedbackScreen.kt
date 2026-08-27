package com.example.medvoicetrainer.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.medvoicetrainer.BuildConfig
import com.example.medvoicetrainer.PracticeReminder
import com.example.medvoicetrainer.analysis.Coach
import com.example.medvoicetrainer.analysis.FluencyMetrics
import com.example.medvoicetrainer.analysis.PracticePlanCard
import com.example.medvoicetrainer.analysis.ReflectionPromptReason
import com.example.medvoicetrainer.analysis.SessionFeeling
import com.example.medvoicetrainer.ui.EvaluationResult
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.formatSoapForDisplay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal data class ChecklistFeedbackItem(
    val item: String,
    val required: Boolean,
    val passed: Boolean,
    val evidence: String,
    val status: String,
)

internal data class ShadowingFeedbackItem(
    val original: String,
    val target: String,
    val focus: String
)

internal fun parseChecklistResults(rawJson: String): List<ChecklistFeedbackItem> = try {
    val array = JSONArray(rawJson.ifBlank { "[]" })
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val evidence = item.opt("evidence")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                .orEmpty()
            add(
                ChecklistFeedbackItem(
                    item = item.optString("item"),
                    required = item.optBoolean("required"),
                    passed = if (item.has("passed")) item.optBoolean("passed") else item.optBoolean("elicited"),
                    evidence = evidence,
                    status = item.optString("status").ifBlank {
                        if (item.optBoolean("passed")) "passed" else "failed"
                    },
                )
            )
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    emptyList()
}

internal fun parseShadowingItems(rawJson: String): List<ShadowingFeedbackItem> = try {
    val array = JSONArray(rawJson.ifBlank { "[]" })
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val original = item.optString("original").ifBlank { item.optString("student_said") }
            val target = item.optString("target").ifBlank { item.optString("ideal_version") }
            val focus = item.optString("focus").ifBlank { item.optString("why") }
            if (target.isNotBlank()) add(ShadowingFeedbackItem(original, target, focus))
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    emptyList()
}

internal fun parseStringArray(rawJson: String): List<String> = try {
    val array = JSONArray(rawJson.ifBlank { "[]" })
    buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    emptyList()
}

internal fun jsonArraySize(rawJson: String): Int = try {
    JSONArray(rawJson.ifBlank { "[]" }).length()
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    onNavigateBack: () -> Unit,
    onNavigateToDebrief: () -> Unit,
    onPresentToAttending: (() -> Unit)? = null,
    /**
     * Opens "Say It: Everyday" on the phrase drill. Optional so the screen still renders in any
     * host that has nowhere to send the learner; the recap then simply reports and stops there.
     */
    onOpenPhraseDrill: ((List<com.example.medvoicetrainer.analysis.EverydayPhrase>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val everyday = evaluation.analysisDomain.equals("everyday", ignoreCase = true)
    val sections = remember(everyday) { feedbackSections(everyday) }
    val transcript by viewModel.lastCompletedTranscript.collectAsStateWithLifecycle()
    val openBookAidsUsed by viewModel.lastCompletedOpenBookAids.collectAsStateWithLifecycle()
    val phrasebookAids by viewModel.lastCompletedPhrasebookAids.collectAsStateWithLifecycle()
    val presentationLaunch by viewModel.lastCompletedPresentationLaunch.collectAsStateWithLifecycle()
    val analysisBackend by viewModel.analysisBackend.collectAsStateWithLifecycle()
    val audioClips by viewModel.lastCompletedAudioClips.collectAsStateWithLifecycle()
    val correctionDecisions by viewModel.correctionDecisions.collectAsStateWithLifecycle()
    val acceptedCorrections = remember(evaluation, correctionDecisions) {
        evaluation.corrections.filter {
            correctionDecisions[it.decisionKey()] == com.example.medvoicetrainer.ui.CorrectionDecision.ACCEPTED
        }
    }
    val expansion = rememberFeedbackExpansion(everyday)
    val sessionReflection by viewModel.sessionReflection.collectAsStateWithLifecycle()
    LaunchedEffect(evaluation) { viewModel.prepareSessionReflection() }
    LaunchedEffect(sessionReflection.autoPromptReason) {
        if (sessionReflection.autoPromptReason != null) expansion.expand(FeedbackSection.REFLECT)
    }
    var csvExportStatus by remember { mutableStateOf<String?>(null) }
    var selfAssessmentSaved by rememberSaveable(evaluation) { mutableStateOf(false) }
    var soapSaved by rememberSaveable(evaluation) { mutableStateOf(false) }
    var showAiReportDialog by rememberSaveable(evaluation) { mutableStateOf(false) }

    val checklistItems = remember(evaluation.checklistResultsJson) {
        parseChecklistResults(evaluation.checklistResultsJson)
    }
    val empathyMarkers = remember(evaluation.empathyMarkersJson) {
        parseStringArray(evaluation.empathyMarkersJson)
    }
    val shadowingItems = remember(acceptedCorrections) {
        acceptedCorrections.take(5).map {
            ShadowingFeedbackItem(it.original, it.corrected, it.explanation)
        }
    }
    val cardCount = acceptedCorrections.size
    val practicePlan = remember(evaluation, everyday) {
        if (evaluation.evaluationLocked) {
            emptyList()
        } else {
            com.example.medvoicetrainer.analysis.LearningRoadmap.buildPostSessionPracticePlan(
                everyday = everyday,
                correctionCount = evaluation.corrections.size,
                communicationFluency = evaluation.fluencyScore,
                wpm = evaluation.fluencyMetrics?.wordsPerMinute ?: evaluation.wpm.takeIf { it > 0 },
                fillerDensity = evaluation.fluencyMetrics?.fillerDensity ?: evaluation.fillerRate,
                medicalAccuracy = if (everyday) null else evaluation.medicalAccuracy,
                clinicalReasoning = if (everyday) null else evaluation.clinicalReasoning,
                professionalism = if (everyday) null else evaluation.professionalism,
                naturalness = if (everyday) evaluation.grammarScore else null,
                interaction = if (everyday) evaluation.medicalAccuracy else null,
                comprehensionRepair = if (everyday) evaluation.clinicalReasoning else null,
                caseName = evaluation.caseName
            )
        }
    }

    // Shared with every other practice screen so model sentences are spoken the same way — cloze
    // blanks become a pause instead of a spoken row of underscores, and recording an attempt can
    // silence the voice (see rememberEnglishTts / ModelSpeechControl).
    val ttsHandle = rememberEnglishTts()
    val ttsReady = ttsHandle.ready
    val speakTarget: (String, Int) -> Unit = { text, _ -> ttsHandle.speak(text) }
    val speakText: (String) -> Unit = { text -> ttsHandle.speak(text) }
    // Record-and-check AI judging is Gemini-gated exactly like PronunciationEngine; when
    // unavailable the panels degrade to listen/self-compare only.
    val judgeAvailable = remember(evaluation) { viewModel.isSpeakingJudgeAvailable() }
    val judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)? =
        if (judgeAvailable) {
            { target, pcm -> viewModel.judgeSpeakingAttempt(target, pcm) }
        } else {
            null
        }

    // Spoken redo loop: re-say the top corrections out loud while the mistake is still fresh.
    // A verdict feeds straight back into the SRS ladder via applySpokenRedoResult.
    val redoTargets = remember(evaluation, correctionDecisions) {
        if (evaluation.evaluationLocked) {
            emptyList()
        } else {
            com.example.medvoicetrainer.analysis.RedoEngine.selectRedoTargets(
                evaluation.corrections.filter {
                    correctionDecisions[it.decisionKey()] == com.example.medvoicetrainer.ui.CorrectionDecision.ACCEPTED
                }.map {
                    com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget(
                        original = it.original,
                        corrected = it.corrected,
                        explanation = it.explanation,
                        category = it.category
                    )
                }
            )
        }
    }
    val redoJudge: (suspend (com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)? =
        if (judgeAvailable) {
            { target, pcm ->
                viewModel.judgeRedoAttempt(
                    targetText = target.corrected,
                    originalText = target.original,
                    audioPcm = pcm,
                    focusHint = target.explanation
                )
            }
        } else {
            null
        }

    val t = LocalTranslate.current
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val isLiveSession = voiceBackend !in setOf("demo", "mock")
    LaunchedEffect(presentationLaunch?.id) {
        if (presentationLaunch != null && onPresentToAttending != null) {
            com.example.medvoicetrainer.analysis.Telemetry.track(
                "team_communication_exposed",
                mapOf("surface" to "feedback", "task" to "attending_presentation")
            )
        }
    }

    // One-time banners are a strict priority queue, never a stack: on a learner's first live
    // session the explainer, the cost/quota reassurance (§2 early-UX) and the next-day reminder
    // opt-in (§6) were all eligible at once and pushed the score below the fold. Whatever loses
    // the queue stays pending and surfaces on a later session instead.
    // Decided once per visit and never promoted mid-screen: dismissing one must not immediately
    // deal the next, or the learner still faces all three, just in sequence.
    val bannerForThisVisit = remember {
        activeFeedbackBanner(
            explainerPending = !viewModel.isCoachPrimerSeen(Coach.KEY_FEEDBACK_EXPLAINER),
            costPrimerPending = isLiveSession && !evaluation.evaluationLocked &&
                !viewModel.isCoachPrimerSeen(Coach.KEY_FIRST_COST_PRIMER),
            reminderPending = isLiveSession &&
                !viewModel.isCoachPrimerSeen(Coach.KEY_REMINDER_OPTIN)
        )
    }
    var bannerDismissed by remember { mutableStateOf(false) }
    var reminderScheduled by remember { mutableStateOf(false) }
    val activeBanner = bannerForThisVisit.takeUnless { bannerDismissed }
    // "Seen" is recorded only when a banner actually reaches the screen — marking all three on
    // first composition would silently burn the two the queue never showed.
    LaunchedEffect(bannerForThisVisit) {
        when (bannerForThisVisit) {
            FeedbackBanner.EXPLAINER -> viewModel.markCoachPrimerSeen(Coach.KEY_FEEDBACK_EXPLAINER)
            FeedbackBanner.COST_PRIMER -> viewModel.markCoachPrimerSeen(Coach.KEY_FIRST_COST_PRIMER)
            // The reminder opt-in is only "seen" once it is acted on — see below.
            FeedbackBanner.REMINDER_OPTIN, null -> Unit
        }
    }

    val reminderTitle = t("early.reminder_notification_title")
    val reminderBody = t("early.reminder_notification_body")
    val scheduleReminder = {
        // ~20 hours out lands the nudge around the same time next day for most learners.
        PracticeReminder.schedule(context, 20L * 60L * 60L * 1000L, reminderTitle, reminderBody)
        viewModel.markCoachPrimerSeen(Coach.KEY_REMINDER_OPTIN)
        viewModel.setPracticeReminderEnabled(true)
        reminderScheduled = true
        bannerDismissed = true
    }
    val dismissReminderOptin = {
        viewModel.markCoachPrimerSeen(Coach.KEY_REMINDER_OPTIN)
        bannerDismissed = true
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) scheduleReminder() else dismissReminderOptin() }
    val onReminderOptIn = {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scheduleReminder()
        }
    }

    val triageStats = remember(evaluation, correctionDecisions) {
        correctionTriageStats(evaluation.corrections, correctionDecisions)
    }
    val expandLabel = t("feedback.section_expand")
    val collapseLabel = t("feedback.section_collapse")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Session Feedback")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = t("Back"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (evaluation.caseName.isNotBlank()) item {
                Text(
                    text = evaluation.caseName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (presentationLaunch != null && onPresentToAttending != null) item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (presentationLaunch?.plannedContinuation == true) {
                                t("Continue the full clinical loop")
                            } else {
                                t("Next step: present this patient")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            t("Give a focused 60-90 second presentation. The attending already has the patient ground truth and will probe omissions, reasoning, and your plan."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(
                            onClick = {
                                com.example.medvoicetrainer.analysis.Telemetry.track(
                                    "team_communication_started",
                                    mapOf("surface" to "feedback", "task" to "attending_presentation")
                                )
                                onPresentToAttending()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Start attending presentation"))
                        }
                    }
                }
            }
            // Names the Open Book scaffold if it was used. The analysis is explicitly told not to
            // deduct for it (AnalysisPromptBuilder's PRACTICE AIDS note), but a session run with
            // the diagnosis on screen must not read back later as an unaided one.
            item { OpenBookUsedBadge(openBookAidsUsed) }
            // The everyday counterpart: which of the expressions the phrasebook offered the learner
            // actually said. Reported as the successes, with the drill one tap away for the rest.
            item {
                PhrasebookRecapCard(
                    aids = phrasebookAids,
                    onOpenDrill = onOpenPhraseDrill
                        ?.takeIf { phrasebookAids.unused.isNotEmpty() },
                )
            }
            if (
                BuildConfig.AI_REPORT_ENDPOINT.isNotBlank() &&
                !evaluation.evaluationLocked &&
                evaluation.summaryFeedback.isNotBlank()
            ) item {
                OutlinedButton(
                    onClick = { showAiReportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(t("ai_report.feedback_action"))
                }
            }
            item {
            if (reminderScheduled) {
                InfoBanner(t("early.reminder_scheduled"), onDismiss = { reminderScheduled = false })
            } else {
                when (activeBanner) {
                    FeedbackBanner.EXPLAINER -> InfoBanner(
                        t(Coach.FEEDBACK_EXPLAINER_KEY),
                        onDismiss = { bannerDismissed = true }
                    )
                    FeedbackBanner.COST_PRIMER -> InfoBanner(
                        t("early.cost_primer"),
                        onDismiss = { bannerDismissed = true }
                    )
                    FeedbackBanner.REMINDER_OPTIN -> ReminderOptInCard(
                        title = t("early.reminder_title"),
                        body = t("early.reminder_body"),
                        optInLabel = t("early.reminder_optin"),
                        declineLabel = t("early.reminder_decline"),
                        onOptIn = onReminderOptIn,
                        onDecline = dismissReminderOptin
                    )
                    null -> Unit
                }
            }
            }

            sections.forEach { section ->
                item {
                val headline = when (section) {
                    FeedbackSection.OVERVIEW -> overviewHeadline(evaluation, everyday, t)
                    FeedbackSection.FIX -> correctionsHeadline(triageStats, evaluation.evaluationLocked, t)
                    FeedbackSection.DELIVERY -> deliveryHeadline(
                        evaluation.fluencyMetrics,
                        evaluation.intelligibility
                    )
                    FeedbackSection.REFLECT -> reflectHeadline(
                        practicePlan.size,
                        selfAssessmentSaved,
                        t
                    )
                    FeedbackSection.TRANSCRIPT ->
                        t("feedback.headline_transcript").replace("{n}", transcript.size.toString())
                    FeedbackSection.CLINICAL -> clinicalHeadline(checklistItems, soapSaved, t)
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
                            SummaryFeedbackContent(
                                evaluation, everyday, cardCount, acceptedCorrections
                            )
                            HorizontalDivider()
                            ScoresContent(evaluation, everyday)
                        }
                        FeedbackSection.FIX -> FixGroupContent(
                            viewModel = viewModel,
                            evaluation = evaluation,
                            everyday = everyday,
                            cardCount = cardCount,
                            exportStatus = csvExportStatus,
                            audioClips = audioClips,
                            ttsReady = ttsReady,
                            onSpeakText = speakText,
                            onSpeakTarget = speakTarget,
                            shadowingItems = shadowingItems,
                            judgeAttempt = judgeAttempt,
                            redoTargets = redoTargets,
                            redoJudge = redoJudge,
                            expandLabel = expandLabel,
                            collapseLabel = collapseLabel,
                            onRedoResult = { target, pass ->
                                viewModel.applySpokenRedoResult(
                                    target.original, target.corrected, target.category, pass
                                )
                            },
                            onExportCsv = {
                                scope.launch {
                                    val file = viewModel.exportLastSessionCsv()
                                    if (file == null) {
                                        csvExportStatus = "No cards were available to export."
                                    } else {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            // The real CSV type, so spreadsheet apps offer
                                            // themselves in the chooser instead of the file
                                            // arriving as pasted text.
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_TITLE, file.name)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(intent, "Export cards as CSV")
                                        )
                                        csvExportStatus = "CSV file ready."
                                    }
                                }
                            }
                        )
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
                        FeedbackSection.REFLECT -> {
                            if (!evaluation.evaluationLocked) {
                                SessionReflectionBlock(
                                    promptReason = sessionReflection.autoPromptReason,
                                    selectedFeeling = sessionReflection.selectedFeeling,
                                    practicePlan = practicePlan,
                                    onSelectFeeling = viewModel::submitSessionFeeling,
                                    onSelectSection = { expansion.expand(it) },
                                    onPracticeNow = onNavigateToDebrief,
                                    expandLabel = expandLabel,
                                    collapseLabel = collapseLabel
                                )
                            }
                            NextPracticeContent(
                                locked = evaluation.evaluationLocked,
                                practicePlan = practicePlan,
                                onSelectSection = { expansion.expand(it) },
                                onPracticeNow = onNavigateToDebrief
                            )
                            SelfAssessmentBlock(
                                viewModel = viewModel,
                                evaluation = evaluation,
                                everyday = everyday,
                                saved = selfAssessmentSaved,
                                onSavedChange = { selfAssessmentSaved = it },
                                expandLabel = expandLabel,
                                collapseLabel = collapseLabel
                            )
                        }
                        FeedbackSection.TRANSCRIPT -> TranscriptContent(transcript, audioClips)
                        FeedbackSection.CLINICAL -> {
                            MisconceptionReviewBlock(
                                viewModel = viewModel,
                                evaluation = evaluation,
                                allowDeepReview = true,
                            )
                            ChecklistContent(evaluation, checklistItems, empathyMarkers)
                            SoapBlock(
                                viewModel = viewModel,
                                evaluation = evaluation,
                                saved = soapSaved,
                                onSavedChange = { soapSaved = it },
                                expandLabel = expandLabel,
                                collapseLabel = collapseLabel
                            )
                        }
                    }
                }
                }
            }

            // The speaking tutor is a pure *language* coach (it never grades medicine or facts —
            // see TutorPromptBuilder), so it applies to everyday practice just as much as to a
            // clinical encounter. Only gate it behind a real, unlocked evaluation.
            if (!evaluation.evaluationLocked) item {
                Button(
                    onClick = onNavigateToDebrief,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (everyday) t("Practice with the English Coach") else t("Practice with the Speaking Tutor"))
                }
            }
            item { OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t("Exit feedback"))
            } }
        }
    }

    if (BuildConfig.AI_REPORT_ENDPOINT.isNotBlank() && showAiReportDialog) {
        AiResponseReportDialog(
            aiResponse = evaluation.summaryFeedback,
            surface = "feedback_summary",
            provider = analysisBackend,
            model = viewModel.getModelForBackend(analysisBackend),
            onDismiss = { showAiReportDialog = false },
        )
    }
}

// --- Collapsed-header headlines -------------------------------------------------------------
// Each group's conclusion, readable without expanding anything. These are the reason collapsing
// is not information loss.

internal fun overallScoreOf(evaluation: EvaluationResult, everyday: Boolean): Int {
    val scores = if (everyday) {
        listOf(
            evaluation.grammarScore,
            evaluation.medicalAccuracy,
            evaluation.clinicalReasoning,
            evaluation.fluencyScore
        )
    } else {
        listOf(
            evaluation.grammarScore,
            evaluation.medicalAccuracy,
            evaluation.clinicalReasoning,
            evaluation.professionalism,
            evaluation.fluencyScore
        )
    }
    return scores.average().roundToInt()
}

internal fun overviewHeadline(
    evaluation: EvaluationResult,
    everyday: Boolean,
    t: (String) -> String
): String {
    if (evaluation.evaluationLocked) return t("demo.locked_title")
    val confidence = (evaluation.reliabilityBadge?.get("confidence") as? String)
        ?: evaluation.reliability.lowercase(Locale.ROOT)
    return "${overallScoreOf(evaluation, everyday)}/100 · " +
        t("feedback.confidence_$confidence".takeIf {
            confidence in setOf("high", "medium", "low")
        } ?: "feedback.confidence_medium")
}

internal fun correctionsHeadline(
    stats: CorrectionTriageStats,
    locked: Boolean,
    t: (String) -> String
): String {
    if (locked) return t("demo.locked_title")
    if (stats.total == 0) return t("feedback.headline_no_corrections")
    val parts = mutableListOf(
        t("feedback.headline_corrections").replace("{n}", stats.total.toString()),
        t("feedback.headline_saved").replace("{n}", stats.saved.toString())
    )
    if (stats.pending > 0) {
        parts += t("feedback.headline_to_review").replace("{n}", stats.pending.toString())
    }
    return parts.joinToString(" · ")
}

internal fun reflectHeadline(
    planCount: Int,
    selfAssessmentSaved: Boolean,
    t: (String) -> String
): String = listOfNotNull(
    planCount.takeIf { it > 0 }
        ?.let { t("feedback.headline_plan").replace("{n}", it.toString()) },
    if (selfAssessmentSaved) t("feedback.headline_self_done") else t("feedback.headline_self_pending")
).joinToString(" · ")

internal fun clinicalHeadline(
    checklistItems: List<ChecklistFeedbackItem>,
    soapSaved: Boolean,
    t: (String) -> String
): String {
    val soapPart = if (soapSaved) t("feedback.headline_soap_written") else t("feedback.headline_soap_missing")
    if (checklistItems.isEmpty()) return soapPart
    val applicable = checklistItems.filterNot { it.status == "not_applicable" }
    val required = applicable.filter { it.required }
    return t("feedback.headline_checklist")
        .replace("{p}", applicable.count { it.passed }.toString())
        .replace("{t}", applicable.size.toString())
        .replace("{rp}", required.count { it.passed }.toString())
        .replace("{rt}", required.size.toString()) + " · " + soapPart
}

@Composable
private fun TranscriptContent(
    transcript: List<Pair<String, String>>,
    audioClips: Map<Int, com.example.medvoicetrainer.voice.LearnerAudioClip>,
) {
    val t = LocalTranslate.current
    Text(
        text = t("feedback.transcript_saved_note"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = t("feedback.audio_saved_note"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
    if (transcript.isEmpty()) {
        Text(t("feedback.no_transcript"))
    } else {
        PlayableTranscript(transcriptTurns(transcript, audioClips))
    }
}

@Composable
private fun LockedFeedbackPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(LocalTranslate.current("AI feedback is locked"), fontWeight = FontWeight.Bold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
/** (background, foreground, icon-text) for evidence confidence — color is never the only
 * signal, text always ships alongside it. */
private fun evidenceConfidenceBadgeStyle(reliability: String): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, String> =
    with(LocalTranslate.current) { when (reliability.uppercase()) {
        "LOW" -> Triple(
            com.example.medvoicetrainer.ui.theme.DangerContainer,
            com.example.medvoicetrainer.ui.theme.DangerRedStrong,
            "⚠ ${this("feedback.evidence_confidence_low")}"
        )
        "MEDIUM" -> Triple(
            androidx.compose.ui.graphics.Color(0xFFFEF3C7),
            androidx.compose.ui.graphics.Color(0xFF92400E),
            "~ ${this("feedback.evidence_confidence_medium")}"
        )
        else -> Triple(
            androidx.compose.ui.graphics.Color(0xFFDCFCE7),
            com.example.medvoicetrainer.ui.theme.SuccessGreenStrong,
            "✓ ${this("feedback.evidence_confidence_high")}"
        )
    } }

@Composable
internal fun SummaryFeedbackContent(
    evaluation: EvaluationResult,
    everyday: Boolean,
    cardCount: Int,
    acceptedCorrections: List<com.example.medvoicetrainer.ui.SrsCorrection>
) {
    val translate = LocalTranslate.current
    if (evaluation.evaluationLocked) {
        AssistChip(
            onClick = {},
            label = { Text(translate("demo.locked_title")) },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
        )
    } else {
        val overall = overallScoreOf(evaluation, everyday)
        // Prefer the rich word-count/evidence-gated badge (ScoringCalibration.
        // buildScoreReliability) when this evaluation has one; falls back to the plain
        // HIGH/MEDIUM/LOW name for demo/legacy evaluations that predate it.
        val badge = evaluation.reliabilityBadge
        val confidence = (badge?.get("confidence") as? String) ?: evaluation.reliability.lowercase()
        val wordCount = (badge?.get("user_word_count") as? Number)?.toInt()
            ?: evaluation.fluencyMetrics?.userWordCount ?: evaluation.wordCount
        val (badgeBg, badgeFg, badgeText) = evidenceConfidenceBadgeStyle(confidence)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(translate("OVERALL"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            // §14 "Font scaling": at large system font scales this badge's text can outgrow
            // the row — cap it to the remaining space and ellipsize rather than overflow past
            // the screen edge (not on-device-verified at 200%, see PORTING_STATUS).
            Surface(color = badgeBg, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    "$badgeText · $wordCount words",
                    color = badgeFg,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The score number itself is never allowed to truncate — it's fixed-width digits
            // (max 3) and always renders in full regardless of scale.
            Text("$overall", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text(
                "/ 100${if (evaluation.caseName.isNotBlank()) " · ${evaluation.caseName}" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
    Text(
        evaluation.summaryFeedback.ifBlank { translate("feedback.no_summary") },
        style = MaterialTheme.typography.bodyLarge
    )
    // §6 "★ FIX THIS FIRST": one highest-impact fix, ahead of any score bars, so a reader
    // who opens nothing else still leaves with the lesson. Deterministic — the session's
    // first recorded correction, not a second LLM call.
    acceptedCorrections.firstOrNull()?.let { first ->
        Surface(
            color = com.example.medvoicetrainer.ui.theme.DangerContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(translate("★ FIX THIS FIRST"), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = com.example.medvoicetrainer.ui.theme.DangerRedStrong)
                Text(
                    first.explanation.ifBlank { "Say it like this: \"${first.corrected}\"" },
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFF7F1D1D)
                )
            }
        }
    }
    if (!evaluation.evaluationLocked && cardCount > 0) {
        Text(
            translate("feedback.cards_generated").replace("{n}", cardCount.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Plan cards whose CTA matches a group on this same page just open that group — that's the most
// useful "reaction" to the tap now that everything lives on one scroll. Everything else (drills
// that live outside the feedback screen, or "try it again now" cards) falls back to the same
// speaking-tutor hand-off as the button at the bottom of the screen.
private fun practiceCtaSection(cta: String): FeedbackSection? = when (cta) {
    "Practice corrections", "Use Shadowing", "Shadow turn" -> FeedbackSection.FIX
    "Review transcript" -> FeedbackSection.TRANSCRIPT
    else -> null
}

private fun reflectionPlanFor(
    feeling: SessionFeeling,
    practicePlan: List<PracticePlanCard>
): PracticePlanCard? = when (feeling) {
    SessionFeeling.STUCK -> practicePlan.firstOrNull {
        it.cta in setOf("Practice corrections", "Use Shadowing", "Practice clarification")
    } ?: practicePlan.firstOrNull()
    SessionFeeling.EFFORTFUL -> practicePlan.firstOrNull {
        it.cta.contains("Repeat", ignoreCase = true) ||
            it.cta.contains("Retry", ignoreCase = true) ||
            it.cta.contains("pauses", ignoreCase = true)
    } ?: practicePlan.getOrNull(1) ?: practicePlan.firstOrNull()
    SessionFeeling.COMFORTABLE -> practicePlan.lastOrNull {
        it.cta in setOf("Repeat session", "Retry the situation")
    } ?: practicePlan.lastOrNull()
}

@Composable
private fun SessionReflectionBlock(
    promptReason: ReflectionPromptReason?,
    selectedFeeling: SessionFeeling?,
    practicePlan: List<PracticePlanCard>,
    onSelectFeeling: (SessionFeeling) -> Unit,
    onSelectSection: (FeedbackSection) -> Unit,
    onPracticeNow: () -> Unit,
    expandLabel: String,
    collapseLabel: String
) {
    val t = LocalTranslate.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(promptReason) {
        if (promptReason != null) expanded = true
    }
    val feelingLabel = when (selectedFeeling) {
        SessionFeeling.STUCK -> t("reflection.feeling_stuck")
        SessionFeeling.EFFORTFUL -> t("reflection.feeling_effortful")
        SessionFeeling.COMFORTABLE -> t("reflection.feeling_comfortable")
        null -> t("reflection.optional")
    }
    FeedbackSubBlock(
        title = t("reflection.title"),
        headline = feelingLabel,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        expandLabel = expandLabel,
        collapseLabel = collapseLabel
    ) {
        Text(
            when (promptReason) {
                ReflectionPromptReason.RETRY -> t("reflection.question_retry")
                ReflectionPromptReason.NEW_MODE -> t("reflection.question_new_mode")
                ReflectionPromptReason.PERFORMANCE_SHIFT -> t("reflection.question_shift")
                null -> t("reflection.question_general")
            },
            fontWeight = FontWeight.SemiBold
        )
        Text(
            t("reflection.local_note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(
            SessionFeeling.STUCK to t("reflection.feeling_stuck"),
            SessionFeeling.EFFORTFUL to t("reflection.feeling_effortful"),
            SessionFeeling.COMFORTABLE to t("reflection.feeling_comfortable")
        ).forEach { (feeling, label) ->
            FilterChip(
                selected = selectedFeeling == feeling,
                onClick = { onSelectFeeling(feeling) },
                label = { Text(label) },
                leadingIcon = if (selectedFeeling == feeling) {
                    { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val recommendation = selectedFeeling?.let { reflectionPlanFor(it, practicePlan) }
        if (recommendation != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(t("reflection.next_step"), fontWeight = FontWeight.Bold)
                    Text(recommendation.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        recommendation.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            practiceCtaSection(recommendation.cta)?.let(onSelectSection)
                                ?: onPracticeNow()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(recommendation.cta)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextPracticeContent(
    locked: Boolean,
    practicePlan: List<PracticePlanCard>,
    onSelectSection: (FeedbackSection) -> Unit,
    onPracticeNow: () -> Unit
) {
    val t = LocalTranslate.current
    Text(t("feedback.next_practice_tab"), fontWeight = FontWeight.SemiBold)
    if (locked) {
        LockedFeedbackPanel(t("demo.locked_next"))
        return
    }
    if (practicePlan.isEmpty()) {
        Text(t("Keep practising this case to build a personalized next step."))
        return
    }
    // Only the top two suggestions are actionable in one sitting; the rest stay one tap away
    // rather than reading as a backlog.
    var showAllPlans by rememberSaveable { mutableStateOf(false) }
    val visiblePlans = if (showAllPlans) practicePlan else practicePlan.take(2)
    visiblePlans.forEach { planCard ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    practiceCtaSection(planCard.cta)?.let(onSelectSection) ?: onPracticeNow()
                },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(planCard.title, fontWeight = FontWeight.Bold)
                Text(planCard.target, style = MaterialTheme.typography.bodySmall)
                Text(
                    planCard.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "→ ${planCard.cta}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    if (practicePlan.size > visiblePlans.size) {
        TextButton(onClick = { showAllPlans = true }) {
            Text(
                t("feedback.show_more_plans")
                    .replace("{n}", (practicePlan.size - visiblePlans.size).toString())
            )
        }
    } else if (showAllPlans && practicePlan.size > 2) {
        TextButton(onClick = { showAllPlans = false }) { Text(t("feedback.show_fewer")) }
    }
}

@Composable
internal fun ScoresContent(evaluation: EvaluationResult, everyday: Boolean) {
    val t = LocalTranslate.current
    if (evaluation.evaluationLocked) {
        LockedFeedbackPanel(
            t(if (everyday) "demo.locked_scores_everyday" else "demo.locked_scores")
        )
        return
    }

    if (everyday) {
        listOf(
            t("Naturalness") to evaluation.grammarScore,
            t("Interaction") to evaluation.medicalAccuracy,
            t("Comprehension & Repair") to evaluation.clinicalReasoning,
            t("Fluency") to evaluation.fluencyScore
        ).forEach { (label, score) -> ScoreMetric(t(label), score) }
    } else {
        // English-first: the language metrics ARE the grade and lead the card; the two clinical
        // metrics follow as clearly-labeled realism context, so the learner doesn't read this as
        // "the app graded my diagnosis" (the exact misperception feature 1 targets).
        Text(
            t("How you communicated in English"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        listOf(
            t("Grammar & Expression") to evaluation.grammarScore,
            t("Fluency") to evaluation.fluencyScore,
            t("Rapport & Professionalism") to evaluation.professionalism
        ).forEach { (label, score) -> ScoreMetric(t(label), score) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            t("Clinical realism — context only, not your English grade"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(
            t("Medical Accuracy") to evaluation.medicalAccuracy,
            t("Clinical Reasoning") to evaluation.clinicalReasoning
        ).forEach { (label, score) -> ScoreMetric(t(label), score) }
    }
    val evidenceConfidence = when (evaluation.reliability.uppercase()) {
        "HIGH" -> t("feedback.evidence_confidence_high")
        "LOW" -> t("feedback.evidence_confidence_low")
        else -> t("feedback.evidence_confidence_medium")
    }
    Text(
        "${t("Evidence confidence")}: $evidenceConfidence",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ScoreMetric(label: String, score: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${score.roundToInt()}/100", fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { (score.coerceIn(0.0, 100.0) / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private data class SelfAssessmentMetric(val key: String, val label: String)

/** Self-assessment is reflection, not a required step — collapsed by default and labelled optional. */
@Composable
private fun SelfAssessmentBlock(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    everyday: Boolean,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    expandLabel: String,
    collapseLabel: String
) {
    val t = LocalTranslate.current
    var expanded by rememberSaveable(evaluation) { mutableStateOf(false) }
    FeedbackSubBlock(
        title = t("feedback.self_assessment_tab"),
        headline = if (saved) t("feedback.headline_self_done") else t("feedback.optional_now"),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        expandLabel = expandLabel,
        collapseLabel = collapseLabel
    ) {
        SelfAssessmentContent(
            viewModel = viewModel,
            evaluation = evaluation,
            everyday = everyday,
            saved = saved,
            onSavedChange = onSavedChange
        )
    }
}

@Composable
private fun SelfAssessmentContent(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    everyday: Boolean,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit
) {
    val t = LocalTranslate.current
    val metrics = remember(everyday) {
        if (everyday) {
            listOf(
                SelfAssessmentMetric("naturalness", t("Naturalness")),
                SelfAssessmentMetric("interaction", t("Interaction")),
                SelfAssessmentMetric("comprehension_repair", t("Comprehension & Repair")),
                SelfAssessmentMetric("fluency", t("Fluency"))
            )
        } else {
            listOf(
                SelfAssessmentMetric("grammar", t("Grammar")),
                SelfAssessmentMetric("medical_accuracy", t("Medical Accuracy")),
                SelfAssessmentMetric("clinical_reasoning", t("Clinical Reasoning")),
                SelfAssessmentMetric("professionalism", t("Professionalism")),
                SelfAssessmentMetric("communication_fluency", t("Fluency"))
            )
        }
    }
    // AI scores keyed to match the self-assessment metric keys (both on the app's 0–100 scale),
    // so FeedbackEngine.computeSelfDelta can pair them. Order follows `metrics` so the rendered
    // delta list matches the sliders above it.
    val aiScores: Map<String, Double> = remember(evaluation, everyday) {
        if (everyday) {
            linkedMapOf(
                "naturalness" to evaluation.grammarScore,
                "interaction" to evaluation.medicalAccuracy,
                "comprehension_repair" to evaluation.clinicalReasoning,
                "fluency" to evaluation.fluencyScore
            )
        } else {
            linkedMapOf(
                "grammar" to evaluation.grammarScore,
                "medical_accuracy" to evaluation.medicalAccuracy,
                "clinical_reasoning" to evaluation.clinicalReasoning,
                "professionalism" to evaluation.professionalism,
                "communication_fluency" to evaluation.fluencyScore
            )
        }
    }
    val values = remember(evaluation, everyday) {
        mutableStateMapOf<String, Float>().apply { metrics.forEach { put(it.key, 50f) } }
    }
    // Per-metric AI − self delta, computed on submit (empty until then, or when locked).
    var delta by remember(evaluation) { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val labelForKey = remember(metrics) { metrics.associate { it.key to it.label } }

    Text(t("feedback.self_assessment_q"), fontWeight = FontWeight.SemiBold)
    Text(
        t("Rate each dimension from 0 (needs work) to 100 (excellent), then compare with the AI's scores."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (evaluation.evaluationLocked) {
        Text(
            t("Your ratings can still be saved; an AI comparison is unavailable for this session."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    metrics.forEach { metric ->
        val value = values[metric.key] ?: 50f
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(metric.label)
                Text(value.roundToInt().toString(), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value,
                onValueChange = {
                    values[metric.key] = it
                    onSavedChange(false)
                },
                valueRange = 0f..100f,
                steps = 99
            )
        }
    }
    Button(
        onClick = {
            val selfScores = values.mapValues { (_, value) -> value.toDouble() }
            viewModel.saveSelfAssessment(selfScores, everyday)
            delta = if (evaluation.evaluationLocked) {
                emptyMap()
            } else {
                com.example.medvoicetrainer.analysis.FeedbackEngine.computeSelfDelta(aiScores, selfScores)
            }
            onSavedChange(true)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(t("feedback.submit_self_assessment"))
    }
    if (saved) {
        Text(t("Self-assessment saved."), color = MaterialTheme.colorScheme.primary)
    }
    if (saved && delta.isNotEmpty()) {
        HorizontalDivider()
        Text(t("You vs. the AI"), fontWeight = FontWeight.Bold)
        Text(
            t("A positive gap means the AI scored you higher than you scored yourself."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        delta.forEach { (key, d) ->
            SelfDeltaRow(
                label = labelForKey[key] ?: key,
                ai = aiScores[key] ?: 0.0,
                self = values[key]?.toDouble() ?: 0.0,
                delta = d
            )
        }
    }
}

@Composable
internal fun SelfDeltaRow(label: String, ai: Double, self: Double, delta: Double) {
    val gapColor = when {
        kotlin.math.abs(delta) < 5.0 -> MaterialTheme.colorScheme.primary
        delta > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val sign = if (delta > 0) "+" else ""
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                "you ${self.roundToInt()} · AI ${ai.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "$sign${delta.roundToInt()}",
                fontWeight = FontWeight.Bold,
                color = gapColor
            )
        }
    }
}

/**
 * "Fix & practice" — everything the learner *does* about this session's errors, in the order the
 * work actually happens: triage the findings, then say the saved ones out loud, then shadow.
 * Shadowing used to be its own tab even though its items are just the accepted corrections, so it
 * is now a collapsed block here instead of a separate destination.
 */
@Composable
private fun FixGroupContent(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    everyday: Boolean,
    cardCount: Int,
    exportStatus: String?,
    audioClips: Map<Int, com.example.medvoicetrainer.voice.LearnerAudioClip>,
    ttsReady: Boolean,
    onSpeakText: (String) -> Unit,
    onSpeakTarget: (String, Int) -> Unit,
    shadowingItems: List<ShadowingFeedbackItem>,
    judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?,
    redoTargets: List<com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget>,
    redoJudge: (suspend (com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?,
    expandLabel: String,
    collapseLabel: String,
    onRedoResult: (com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget, Boolean) -> Unit,
    onExportCsv: () -> Unit
) {
    val t = LocalTranslate.current
    if (evaluation.evaluationLocked) {
        LockedFeedbackPanel(t("demo.locked_corrections"))
        return
    }
    CorrectionsContent(
        viewModel = viewModel,
        evaluation = evaluation,
        audioClips = audioClips,
        ttsReady = ttsReady,
        onSpeakText = onSpeakText
    )
    // Placed after triage, not before it: redo targets are derived from *accepted* corrections,
    // so the panel is empty until the learner has worked the list above.
    if (redoTargets.isNotEmpty()) {
        SpokenRedoPanel(
            targets = redoTargets,
            ttsReady = ttsReady,
            onSpeakText = onSpeakText,
            redoJudge = redoJudge,
            onRedoResult = onRedoResult
        )
    }
    if (shadowingItems.isNotEmpty()) {
        var shadowingExpanded by rememberSaveable(evaluation) { mutableStateOf(false) }
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
                onSpeak = onSpeakTarget,
                judgeAttempt = judgeAttempt
            )
        }
    }
    if (cardCount > 0) {
        OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("${t("feedback.export_csv")} ($cardCount)")
        }
    }
    exportStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CorrectionsContent(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    audioClips: Map<Int, com.example.medvoicetrainer.voice.LearnerAudioClip>,
    ttsReady: Boolean,
    onSpeakText: (String) -> Unit
) {
    val t = LocalTranslate.current
    val context = LocalContext.current
    val correctionDecisions by viewModel.correctionDecisions.collectAsStateWithLifecycle()
    val predictions by viewModel.correctionPredictions.collectAsStateWithLifecycle()
    // The learner's own phrasebook, so a better wording offered here can be kept and drilled
    // instead of being read once and never met again — see MyPhrasebook.
    val myPhrasebook by viewModel.myPhrasebook.collectAsStateWithLifecycle()
    val stats = remember(evaluation, correctionDecisions) {
        correctionTriageStats(evaluation.corrections, correctionDecisions)
    }
    // Highest-impact findings first, so the preview cut is the top of the list that matters
    // rather than whatever order the model emitted.
    val ordered = remember(evaluation) { orderCorrectionsByImpact(evaluation.corrections) }
    var showAllCorrections by rememberSaveable(evaluation) { mutableStateOf(false) }
    val visible = if (showAllCorrections) ordered else ordered.take(CORRECTION_PREVIEW_COUNT)

    // Learner-clip playback for pronunciation corrections (same MediaPlayer pattern as
    // PlayableTranscript, keyed by correction index).
    val audioStore = remember(context.applicationContext) {
        com.example.medvoicetrainer.voice.LearnerAudioStore(context.applicationContext)
    }
    val clipPlayer = remember { android.media.MediaPlayer() }
    val playbackHandler = remember {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    var scheduledStop by remember { mutableStateOf<Runnable?>(null) }
    var playingCorrection by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(clipPlayer) {
        onDispose {
            scheduledStop?.let { playbackHandler.removeCallbacks(it) }
            runCatching { clipPlayer.release() }
        }
    }

    // Discoverability fix (docs/DIFFERENTIATION_ANSWERS.md Q2): pronunciation analysis exists
    // and is L1-specific. If a learner turned the default-on feature off, surface the route
    // back to it here instead of leaving it buried in Preferences.
    val pronunciationAnalysisEnabled by viewModel.pronunciationAnalysisEnabled.collectAsStateWithLifecycle()
    val nativeLanguageForHint by viewModel.nativeLanguage.collectAsStateWithLifecycle()
    val hasPronunciationCorrection = evaluation.corrections.any {
        it.category.startsWith("pronunciation", ignoreCase = true)
    }
    if (!pronunciationAnalysisEnabled &&
        !hasPronunciationCorrection &&
        com.example.medvoicetrainer.analysis.PronunciationEngine.l1Name(nativeLanguageForHint) != "unknown"
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = t("feedback.pronunciation_discovery_hint"),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (evaluation.corrections.isNotEmpty()) {
        Text(
            t("feedback.review_lesson_title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            t("feedback.correction_auto_note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = {
                stats.reviewed.toFloat() / stats.total.coerceAtLeast(1)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            t("feedback.review_lesson_progress")
                .replace("{done}", stats.reviewed.toString())
                .replace("{total}", stats.total.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Escape hatch from per-item triage fatigue: save every still-pending high-confidence
        // error at once (style + pronunciation stay per-item). Keeps the mistake tracker fed
        // on a tired day without lowering the bar on judgment-call items.
        if (stats.pendingBulkAcceptable > 1) {
            OutlinedButton(
                onClick = { viewModel.acceptAllHighConfidenceCorrections() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    t("feedback.save_all_high_confidence")
                        .replace("{count}", stats.pendingBulkAcceptable.toString())
                )
            }
        }
    }
    if (evaluation.corrections.isEmpty()) {
        Text(t("feedback.no_corrections"))
        return
    }
    visible.forEachIndexed { index, correction ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val decision = correctionDecisions[correction.decisionKey()]
                    ?: com.example.medvoicetrainer.ui.CorrectionDecision.PENDING
                val prediction = predictions[correction.decisionKey()]
                val revealed =
                    decision != com.example.medvoicetrainer.ui.CorrectionDecision.PENDING ||
                        prediction != null
                val pronunciationCandidate =
                    correction.category.contains("pronunciation", ignoreCase = true)

                Text(
                    "#${index + 1} · ${if (revealed) correction.category else t("feedback.review_spot_issue")}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    if (!revealed && pronunciationCandidate) {
                        t("feedback.review_listen_first")
                    } else {
                        correction.original
                    },
                    color = if (revealed) {
                        com.example.medvoicetrainer.ui.theme.DangerRedStrong
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (revealed) {
                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                    } else {
                        androidx.compose.ui.text.style.TextDecoration.None
                    },
                    fontWeight = if (revealed) FontWeight.Normal else FontWeight.SemiBold
                )

                if (!revealed) {
                    Text(
                        t("feedback.review_predict_prompt"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.predictCorrection(correction, true) },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.review_predict_error")) }
                        OutlinedButton(
                            onClick = { viewModel.predictCorrection(correction, false) },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.review_predict_okay")) }
                    }
                    TextButton(
                        onClick = {
                            viewModel.decideCorrection(
                                correction,
                                com.example.medvoicetrainer.ui.CorrectionDecision.STT_ERROR
                            )
                        }
                    ) {
                        Text(t("feedback.correction_stt_error"))
                    }
                } else {
                    prediction?.let {
                        Text(
                            if (it == "error") {
                                t("feedback.review_prediction_error")
                            } else {
                                t("feedback.review_prediction_okay")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            correction.corrected,
                            color = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        // The one affordance that turns this screen from a report into a loop: the
                        // better wording goes into the learner's own phrasebook, where Say It
                        // drills it like any other phrase. Offered on every correction, not only
                        // everyday ones — a clinical sentence is just as worth re-saying.
                        KeepExpressionButton(
                            kept = com.example.medvoicetrainer.analysis.MyPhrasebook
                                .contains(myPhrasebook, correction.corrected),
                            onKeep = {
                                viewModel.addToMyPhrasebook(
                                    english = correction.corrected,
                                    original = correction.original,
                                    note = correction.explanation,
                                    source = evaluation.caseName,
                                )
                            },
                        )
                    }
                    Text(
                        if (correction.feedbackType == "style") {
                            t("feedback.correction_type_style")
                        } else {
                            t("feedback.correction_type_error")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (correction.explanation.isNotBlank()) {
                        Text(correction.explanation, style = MaterialTheme.typography.bodySmall)
                    }
                    if (correction.patternId.isNotBlank()) {
                        Text(
                            t("feedback.review_rule")
                                .replace("{value}", correction.patternId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (correction.l1Hypothesis.isNotBlank()) {
                        Text(
                            t("feedback.correction_l1_hypothesis")
                                .replace("{value}", correction.l1Hypothesis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        t("feedback.correction_confidence")
                            .replace(
                                "{value}",
                                "${(correction.confidence * 100).roundToInt()}%"
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (
                    revealed &&
                    decision == com.example.medvoicetrainer.ui.CorrectionDecision.PENDING
                ) {
                    Text(
                        t("feedback.review_final_decision"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.decideCorrection(
                                    correction,
                                    com.example.medvoicetrainer.ui.CorrectionDecision.ACCEPTED
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_save")) }
                        OutlinedButton(
                            onClick = {
                                viewModel.decideCorrection(
                                    correction,
                                    com.example.medvoicetrainer.ui.CorrectionDecision.NOT_ERROR
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
                                viewModel.decideCorrection(
                                    correction,
                                    com.example.medvoicetrainer.ui.CorrectionDecision.STT_ERROR
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_stt_error")) }
                        OutlinedButton(
                            onClick = {
                                viewModel.decideCorrection(
                                    correction,
                                    com.example.medvoicetrainer.ui.CorrectionDecision.STYLE_ONLY
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("feedback.correction_style_only")) }
                    }
                } else if (
                    decision != com.example.medvoicetrainer.ui.CorrectionDecision.PENDING
                ) {
                    val statusKey = when (decision) {
                        com.example.medvoicetrainer.ui.CorrectionDecision.ACCEPTED -> "feedback.correction_saved"
                        com.example.medvoicetrainer.ui.CorrectionDecision.NOT_ERROR -> "feedback.correction_marked_not_error"
                        com.example.medvoicetrainer.ui.CorrectionDecision.STT_ERROR -> "feedback.correction_marked_stt"
                        com.example.medvoicetrainer.ui.CorrectionDecision.STYLE_ONLY -> "feedback.correction_marked_style"
                        else -> "feedback.correction_candidate_note"
                    }
                    Text(
                        t(statusKey),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = { viewModel.resetCorrectionDecision(correction) }
                    ) {
                        Text(t("feedback.correction_change_decision"))
                    }
                }
                if (pronunciationCandidate) {
                    val clipFile = remember(correction.turnIndex, audioClips) {
                        correction.turnIndex
                            ?.let { audioClips[it] }
                            ?.let { audioStore.resolve(it.relativePath) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (playingCorrection == index) {
                                    scheduledStop?.let { playbackHandler.removeCallbacks(it) }
                                    runCatching { clipPlayer.reset() }
                                    playingCorrection = null
                                } else if (clipFile != null) {
                                    runCatching {
                                        scheduledStop?.let { playbackHandler.removeCallbacks(it) }
                                        clipPlayer.reset()
                                        clipPlayer.setDataSource(clipFile.absolutePath)
                                        clipPlayer.setOnPreparedListener { player ->
                                            val startMs = correction.audioStartMs ?: 0
                                            if (startMs > 0) player.seekTo(startMs)
                                            player.start()
                                            val endMs = correction.audioEndMs
                                            if (endMs != null && endMs > startMs) {
                                                val stop = Runnable {
                                                    if (playingCorrection == index) {
                                                        runCatching { clipPlayer.reset() }
                                                        playingCorrection = null
                                                    }
                                                }
                                                scheduledStop = stop
                                                playbackHandler.postDelayed(
                                                    stop,
                                                    (endMs - startMs).toLong()
                                                )
                                            }
                                        }
                                        clipPlayer.setOnCompletionListener { playingCorrection = null }
                                        clipPlayer.setOnErrorListener { _, _, _ ->
                                            playingCorrection = null
                                            true
                                        }
                                        playingCorrection = index
                                        clipPlayer.prepareAsync()
                                    }.onFailure { playingCorrection = null }
                                }
                            },
                            enabled = clipFile != null
                        ) {
                            Icon(
                                if (playingCorrection == index) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (
                                    correction.audioStartMs != null &&
                                    correction.audioEndMs != null
                                ) {
                                    t("pron.listen_target_clip")
                                } else {
                                    t("pron.listen_mine")
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = { onSpeakText(correction.corrected) },
                            enabled = ttsReady
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(t("pron.listen_correct"))
                        }
                    }
                    if (clipFile == null) {
                        Text(
                            t("pron.no_clip"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    // Triage fatigue is the real cost of a long findings list: only the top few are shown up
    // front, and the rest stay one tap away instead of scrolling past as a wall of homework.
    if (ordered.size > visible.size) {
        TextButton(
            onClick = { showAllCorrections = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                t("feedback.show_more_corrections")
                    .replace("{n}", (ordered.size - visible.size).toString())
            )
        }
    } else if (showAllCorrections && ordered.size > CORRECTION_PREVIEW_COUNT) {
        TextButton(
            onClick = { showAllCorrections = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(t("feedback.show_fewer"))
        }
    }
}

/**
 * The spoken REDO loop: the top corrections, re-said out loud before leaving the feedback
 * screen — reading a correction is recognition, saying it is retrieval. Each target reuses the
 * standard listen → record → replay → AI-check panel; an AI verdict (or, without a Gemini key,
 * the learner's own judgment) is applied to the SRS ladder via [onRedoResult].
 */
@Composable
private fun SpokenRedoPanel(
    targets: List<com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget>,
    ttsReady: Boolean,
    onSpeakText: (String) -> Unit,
    redoJudge: (suspend (com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?,
    onRedoResult: (com.example.medvoicetrainer.analysis.RedoEngine.RedoTarget, Boolean) -> Unit
) {
    val t = LocalTranslate.current
    val done = remember(targets) { mutableStateMapOf<Int, Boolean>() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🎤 " + t("redo.title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${done.count { it.value }}/${targets.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                t("redo.subtitle"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            targets.forEachIndexed { index, target ->
                key(target.corrected) {
                    var verdict by remember {
                        mutableStateOf<com.example.medvoicetrainer.analysis.SpeakingJudgment?>(null)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "#${index + 1} · ${target.category}" +
                                    if (done[index] == true) "  ✓" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (done[index] == true) {
                                    com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                target.original,
                                style = MaterialTheme.typography.bodySmall,
                                color = com.example.medvoicetrainer.ui.theme.DangerRedStrong,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                            Text(
                                target.corrected,
                                fontWeight = FontWeight.Bold,
                                color = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                            )
                            if (done[index] != true) {
                                PronunciationAttemptPanel(
                                    targetText = target.corrected,
                                    ttsReady = ttsReady,
                                    onSpeakTarget = { onSpeakText(target.corrected) },
                                    judge = redoJudge?.let { j -> { pcm -> j(target, pcm) } },
                                    onVerdict = { verdict = it }
                                )
                                if (redoJudge != null) {
                                    verdict?.let { v ->
                                        Button(
                                            onClick = {
                                                onRedoResult(target, v.pass)
                                                done[index] = true
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                t("pron.apply_result") + " · " +
                                                    t(if (v.pass) "GOT IT" else "STILL LEARNING")
                                            )
                                        }
                                    }
                                } else {
                                    // No AI judge: the learner grades their own attempt, exactly
                                    // like the SRS flashcard fallback.
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            onRedoResult(target, false)
                                            done[index] = true
                                        }) {
                                            Text(t("STILL LEARNING"))
                                        }
                                        Button(onClick = {
                                            onRedoResult(target, true)
                                            done[index] = true
                                        }) {
                                            Text(t("GOT IT"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChecklistContent(
    evaluation: EvaluationResult,
    checklistItems: List<ChecklistFeedbackItem>,
    empathyMarkers: List<String>
) {
    val t = LocalTranslate.current
    if (evaluation.evaluationLocked) {
        LockedFeedbackPanel(if (evaluation.isTypedDemo) t("demo.locked_checklist") else t("Unanalyzed"))
        return
    }

    val applicable = checklistItems.filterNot { it.status == "not_applicable" }
    val passed = applicable.count { it.passed }
    val required = applicable.filter { it.required }
    val requiredPassed = required.count { it.passed }
    val completeness = if (evaluation.historyCompleteness <= 1.0) {
        evaluation.historyCompleteness * 100.0
    } else {
        evaluation.historyCompleteness
    }
    Text("${t("Passed")} $passed/${applicable.size} · ${t("Required")} $requiredPassed/${required.size}")
    val followUpFeedback = remember(evaluation.followUpFeedbackJson) {
        runCatching { JSONObject(evaluation.followUpFeedbackJson) }.getOrDefault(JSONObject())
    }
    val isFollowUp = followUpFeedback.optBoolean("is_follow_up")
    if (isFollowUp) {
        val followUpCompleteness = followUpFeedback.optDouble("follow_up_completeness", Double.NaN)
        if (followUpCompleteness.isFinite()) {
            val percent = if (followUpCompleteness <= 1.0) followUpCompleteness * 100.0 else followUpCompleteness
            Text("${t("Follow-up completeness:")} ${percent.roundToInt()}%")
        }
    } else {
        Text("${t("History completeness:")} ${completeness.roundToInt()}%")
        Text("${t("ICE elicited:")} ${if (evaluation.iceElicited) t("Yes") else t("No")}")
    }

    if (checklistItems.isEmpty()) {
        Text(t("feedback.no_checklist"))
    } else {
        checklistItems.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = if (item.status == "not_applicable") {
                    MaterialTheme.colorScheme.surfaceVariant
                } else if (item.passed) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                }
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${when { item.status == "not_applicable" -> "—"; item.passed -> "✓"; else -> "✗" }} ${item.item}" +
                            if (item.required) " · ${t("Required")}" else "",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (item.status == "not_applicable") {
                            "${t("Not applicable:")} ${item.evidence.ifBlank { t("Not applicable to this visit.") }}"
                        } else {
                            "${t("Evidence:")} ${item.evidence.ifBlank { t("No supporting quote returned.") }}"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (empathyMarkers.isNotEmpty()) {
        HorizontalDivider()
        Text(t("Empathy markers"), fontWeight = FontWeight.Bold)
        empathyMarkers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (isFollowUp) {
        val sharedPlan = followUpFeedback.optJSONObject("shared_plan") ?: JSONObject()
        val planRows = listOf(
            "Next step" to sharedPlan.optString("next_step").trim(),
            "Monitoring / follow-up" to sharedPlan.optString("monitoring_interval").trim(),
            "Safety net" to sharedPlan.optString("safety_net").trim(),
            "Understanding check" to sharedPlan.optString("understanding_check").trim(),
        )
        HorizontalDivider()
        Text(t("Agreed shared plan"), fontWeight = FontWeight.Bold)
        planRows.forEach { (label, value) ->
            val displayed = value.ifEmpty { t("Not demonstrated in this encounter") }
            Text(
                "${t(label)}: $displayed",
                style = MaterialTheme.typography.bodySmall,
                color = if (value.isEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun FluencyContent(evaluation: EvaluationResult) {
    val t = LocalTranslate.current
    val fm = evaluation.fluencyMetrics
    if (fm == null) {
        Text(t("feedback.no_fluency"))
        return
    }
    val (grade, _) = FluencyMetrics.fluencyGrade(fm)
    Text(grade, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    OfflineMetric(t("WPM"), fm.wordsPerMinute?.roundToInt()?.toString() ?: "—")
    OfflineMetric(t("Filler density"), "${String.format(Locale.US, "%.1f", fm.fillerDensity)}%")
    OfflineMetric(t("Words"), fm.userWordCount.toString())
    OfflineMetric(t("Turns"), fm.userTurnCount.toString())
    OfflineMetric(t("Average words / turn"), String.format(Locale.US, "%.1f", fm.avgTurnLength))
    Text(
        "You're ${fm.confidenceBand}. " + t("These are deterministic measurements from your real words."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OfflineMetric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

internal data class BlindFeedbackObservation(
    val turnIndex: Int,
    val intended: String,
    val heard: String,
    val outcome: com.example.medvoicetrainer.analysis.IntelligibilityOutcome,
    val uncertainWords: List<String>,
    val criticalDifferences: List<com.example.medvoicetrainer.analysis.CriticalMeaningDifference>
)

internal fun parseBlindFeedbackObservations(value: Any?): List<BlindFeedbackObservation> {
    fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
            raw.optString(index).trim().takeIf(String::isNotBlank)
        }
        else -> emptyList()
    }

    fun criticalDifferences(raw: Any?): List<com.example.medvoicetrainer.analysis.CriticalMeaningDifference> {
        val values: List<Any?> = when (raw) {
            is List<*> -> raw
            is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
            else -> emptyList()
        }
        return values.mapNotNull { item ->
            val type: String
            val expected: String
            val heard: String
            when (item) {
                is Map<*, *> -> {
                    type = item["type"]?.toString().orEmpty()
                    expected = item["expected"]?.toString().orEmpty()
                    heard = item["heard"]?.toString().orEmpty()
                }
                is JSONObject -> {
                    type = item.optString("type")
                    expected = item.optString("expected")
                    heard = item.optString("heard")
                }
                else -> return@mapNotNull null
            }
            if (expected.isBlank()) null else {
                com.example.medvoicetrainer.analysis.CriticalMeaningDifference(
                    type = type,
                    expected = expected,
                    heard = heard.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    val values: List<Any?> = when (value) {
        is List<*> -> value
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        else -> emptyList()
    }
    return values.mapNotNull { item ->
        val turnIndex: Int
        val intended: String
        val heard: String
        val outcome: String
        val uncertain: Any?
        val differences: Any?
        when (item) {
            is Map<*, *> -> {
                turnIndex = (item["turn_index"] as? Number)?.toInt() ?: -1
                intended = item["intended"]?.toString().orEmpty()
                heard = item["heard"]?.toString().orEmpty()
                outcome = item["outcome"]?.toString().orEmpty()
                uncertain = item["uncertain_words"]
                differences = item["critical_differences"]
            }
            is JSONObject -> {
                turnIndex = item.optInt("turn_index", -1)
                intended = item.optString("intended")
                heard = item.optString("heard")
                outcome = item.optString("outcome")
                uncertain = item.opt("uncertain_words")
                differences = item.opt("critical_differences")
            }
            else -> return@mapNotNull null
        }
        if (turnIndex < 0 || intended.isBlank() || heard.isBlank()) null else {
            BlindFeedbackObservation(
                turnIndex = turnIndex,
                intended = intended,
                heard = heard,
                outcome = com.example.medvoicetrainer.analysis.IntelligibilityOutcome
                    .fromWireName(outcome),
                uncertainWords = stringList(uncertain),
                criticalDifferences = criticalDifferences(differences)
            )
        }
    }.distinctBy { it.turnIndex }
}

@Composable
internal fun IntelligibilityContent(
    evaluation: EvaluationResult,
    everyday: Boolean,
    ttsReady: Boolean,
    onSpeakText: (String) -> Unit,
    judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?
) {
    val t = LocalTranslate.current
    val data = evaluation.intelligibility
    if (data == null) {
        Text(t("feedback.no_intelligibility"))
        return
    }
    Text(
        if (everyday) t("feedback.intelligibility_header_everyday")
        else t("feedback.intelligibility_header"),
        fontWeight = FontWeight.Bold
    )
    if (data["acoustic_measured"] == true) {
        val acousticOutcome = com.example.medvoicetrainer.analysis.IntelligibilityOutcome
            .fromWireName(data["acoustic_outcome"]?.toString())
        val observations = remember(data) {
            parseBlindFeedbackObservations(data["blind_observations"])
        }
        val attentionTurns = observations
            .filter { it.outcome != com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COMFORTABLE }
            .take(5)
        Surface(
            color = when (acousticOutcome) {
                com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COMFORTABLE ->
                    MaterialTheme.colorScheme.primaryContainer
                com.example.medvoicetrainer.analysis.IntelligibilityOutcome.CRITICAL_MISMATCH ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(t("feedback.blind_audio_title"), fontWeight = FontWeight.Bold)
                Text(
                    when (acousticOutcome) {
                        com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COMFORTABLE ->
                            t("pron.outcome_comfortable")
                        com.example.medvoicetrainer.analysis.IntelligibilityOutcome.EFFORTFUL ->
                            t("pron.outcome_effortful")
                        com.example.medvoicetrainer.analysis.IntelligibilityOutcome.CRITICAL_MISMATCH ->
                            t("pron.outcome_critical")
                        com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COULD_NOT_ASSESS ->
                            t("pron.outcome_unassessable")
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                OfflineMetric(
                    t("feedback.blind_audio_turns"),
                    "${data["blind_understood_turns"] ?: 0}/${data["blind_total_turns"] ?: 0}"
                )
                Text(
                    data["acoustic_principle"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (attentionTurns.isNotEmpty()) {
            HorizontalDivider()
            Text(t("feedback.blind_audio_review"), fontWeight = FontWeight.Bold)
            Text(
                t("feedback.blind_audio_review_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            attentionTurns.forEach { observation ->
                BlindObservationCard(
                    observation = observation,
                    ttsReady = ttsReady,
                    onSpeakText = onSpeakText,
                    judgeAttempt = judgeAttempt
                )
            }
        }
        HorizontalDivider()
        Text(t("feedback.transcript_clarity_title"), fontWeight = FontWeight.Bold)
    }
    Text(
        "${data["grade"] ?: ""} (${data["score"] ?: "?"}/10)",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(data["principle"]?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
    listOf(
        t("Avg words / turn") to data["avg_words_per_turn"],
        t("Long turns") to data["long_turn_count"],
        t("Repair phrases") to data["repair_phrase_count"]
    ).forEach { (label, value) -> value?.let { OfflineMetric(label, it.toString()) } }
    if (!everyday) {
        data["signpost_count"]?.let { OfflineMetric(t("Signposts"), it.toString()) }
        data["jargon_density"]?.let { OfflineMetric(t("Jargon density"), "$it%") }
    }
    @Suppress("UNCHECKED_CAST")
    val coaching = data["coaching"] as? List<String> ?: emptyList()
    if (coaching.isNotEmpty()) {
        HorizontalDivider()
        Text(t("feedback.coach_focus"), fontWeight = FontWeight.Bold)
        coaching.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BlindObservationCard(
    observation: BlindFeedbackObservation,
    ttsReady: Boolean,
    onSpeakText: (String) -> Unit,
    judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?
) {
    val t = LocalTranslate.current
    val critical = observation.outcome ==
        com.example.medvoicetrainer.analysis.IntelligibilityOutcome.CRITICAL_MISMATCH
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (critical) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when (observation.outcome) {
                    com.example.medvoicetrainer.analysis.IntelligibilityOutcome.EFFORTFUL ->
                        t("pron.outcome_effortful")
                    com.example.medvoicetrainer.analysis.IntelligibilityOutcome.CRITICAL_MISMATCH ->
                        t("pron.outcome_critical")
                    com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COULD_NOT_ASSESS ->
                        t("pron.outcome_unassessable")
                    com.example.medvoicetrainer.analysis.IntelligibilityOutcome.COMFORTABLE ->
                        t("pron.outcome_comfortable")
                },
                fontWeight = FontWeight.Bold
            )
            Text(t("pron.intended_label"), style = MaterialTheme.typography.labelSmall)
            Text(observation.intended)
            Text(t("pron.heard_label"), style = MaterialTheme.typography.labelSmall)
            Text("“${observation.heard}”", fontWeight = FontWeight.SemiBold)
            if (observation.criticalDifferences.isNotEmpty()) {
                Text(
                    t("pron.critical_difference"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                observation.criticalDifferences.forEach { difference ->
                    Text(
                        "• ${difference.expected} → " +
                            (difference.heard ?: t("pron.not_heard")),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (observation.uncertainWords.isNotEmpty()) {
                Text(
                    "${t("pron.focus_words")} ${observation.uncertainWords.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                t("feedback.blind_audio_retry"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PronunciationAttemptPanel(
                targetText = observation.intended,
                ttsReady = ttsReady,
                onSpeakTarget = { onSpeakText(observation.intended) },
                judge = judgeAttempt?.let { judge ->
                    { pcm -> judge(observation.intended, pcm) }
                }
            )
        }
    }
}

/** Writing a SOAP note is optional homework, not part of reading your feedback — collapsed. */
@Composable
internal fun MisconceptionReviewBlock(
    viewModel: MainViewModel?,
    evaluation: EvaluationResult,
    allowDeepReview: Boolean,
) {
    val t = LocalTranslate.current
    val findings = remember(evaluation.misconceptionReviewJson) {
        com.example.medvoicetrainer.analysis.MisconceptionReview.parseJson(evaluation.misconceptionReviewJson)
    }
    val requestState = viewModel?.deepClinicalReviewState?.collectAsStateWithLifecycle()?.value
    var expanded by rememberSaveable(evaluation) { mutableStateOf(findings.isNotEmpty()) }
    FeedbackSubBlock(
        title = t("feedback.misconception_title"),
        headline = if (findings.isEmpty()) t("feedback.misconception_none") else
            t("feedback.misconception_count").replace("{n}", findings.size.toString()),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        expandLabel = t("Expand"),
        collapseLabel = t("Collapse"),
    ) {
        if (findings.isEmpty()) {
            Text(
                t("feedback.misconception_empty_explainer"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                t("feedback.misconception_grounded_note"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            findings.forEachIndexed { index, finding ->
                if (index > 0) HorizontalDivider()
                MisconceptionFindingCard(
                    finding = finding,
                    loading = requestState?.loadingKey == finding.key,
                    error = requestState?.takeIf { it.errorKey == finding.key }?.errorMessage,
                    allowDeepReview = allowDeepReview && viewModel != null,
                    onDeepReview = { viewModel?.requestDeepClinicalReview(finding.toJson().toString()) },
                    onClearError = { viewModel?.clearDeepClinicalReviewError() },
                )
            }
        }
    }
}

@Composable
private fun MisconceptionFindingCard(
    finding: com.example.medvoicetrainer.analysis.MisconceptionFinding,
    loading: Boolean,
    error: String?,
    allowDeepReview: Boolean,
    onDeepReview: () -> Unit,
    onClearError: () -> Unit,
) {
    val t = LocalTranslate.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t("feedback.misconception_topic").replace(
                    "{topic}", finding.topic.ifBlank { finding.classification.replace('_', ' ') },
                ),
                fontWeight = FontWeight.Bold,
            )
            AssistChip(onClick = {}, label = { Text(t("feedback.severity_${finding.severity}")) })
        }
        Text(t("feedback.you_said"), fontWeight = FontWeight.SemiBold)
        Text("“${finding.learnerClaim}”")
        Text(t("feedback.correct_concept"), fontWeight = FontWeight.SemiBold)
        Text(finding.correctConcept)
        if (finding.whyInThisPatient.isNotEmpty()) {
            Text(t("feedback.why_this_patient"), fontWeight = FontWeight.SemiBold)
            finding.whyInThisPatient.forEach { Text("• $it") }
        }
        if (finding.reasoningRepair.isNotBlank()) {
            Text(t("feedback.reasoning_repair"), fontWeight = FontWeight.SemiBold)
            Text(finding.reasoningRepair)
        }
        Text(
            t("feedback.score_impact").replace(
                "{scores}", finding.scoreImpact.joinToString(", ") { it.replace('_', ' ') },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (finding.deepReviewJson.isNotBlank()) {
            DeepClinicalReviewContent(finding.deepReviewJson)
        } else if (allowDeepReview) {
            Button(onClick = onDeepReview, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) t("feedback.deep_review_loading") else t("feedback.deep_review_action"))
            }
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClearError) { Text(t("Dismiss")) }
        }
    }
}

@Composable
private fun DeepClinicalReviewContent(rawJson: String) {
    val t = LocalTranslate.current
    val root = remember(rawJson) { runCatching { JSONObject(rawJson) }.getOrNull() } ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(t("feedback.deep_review_title"), fontWeight = FontWeight.Bold)
            Text(root.optString("explanation"))
            val reasoning = parseStringArray(root.optJSONArray("patient_specific_reasoning")?.toString() ?: "[]")
            if (reasoning.isNotEmpty()) {
                Text(t("feedback.patient_reasoning"), fontWeight = FontWeight.SemiBold)
                reasoning.forEach { Text("• $it") }
            }
            Text(t("feedback.general_rule"), fontWeight = FontWeight.SemiBold)
            Text(root.optString("general_rule"))
            root.optString("memory_hook").takeIf { it.isNotBlank() }?.let {
                Text(t("feedback.memory_hook"), fontWeight = FontWeight.SemiBold)
                Text(it)
            }
            val boundaries = parseStringArray(root.optJSONArray("boundaries_and_exceptions")?.toString() ?: "[]")
            if (boundaries.isNotEmpty()) {
                Text(t("feedback.boundaries"), fontWeight = FontWeight.SemiBold)
                boundaries.forEach { Text("• $it") }
            }
            HorizontalDivider()
            Text(t("feedback.check_understanding"), fontWeight = FontWeight.SemiBold)
            Text(root.optString("check_question"))
            var answerVisible by rememberSaveable(rawJson) { mutableStateOf(false) }
            TextButton(onClick = { answerVisible = !answerVisible }) {
                Text(if (answerVisible) t("feedback.hide_answer") else t("feedback.show_answer"))
            }
            if (answerVisible) Text(root.optString("check_answer"))
            root.optString("uncertainty_note").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SoapBlock(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    expandLabel: String,
    collapseLabel: String
) {
    val t = LocalTranslate.current
    var expanded by rememberSaveable(evaluation) { mutableStateOf(false) }
    FeedbackSubBlock(
        title = t("feedback.soap_tab"),
        headline = if (saved) t("feedback.headline_soap_written") else t("feedback.optional_now"),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        expandLabel = expandLabel,
        collapseLabel = collapseLabel
    ) {
        SoapContent(viewModel, evaluation, saved, onSavedChange)
    }
}

@Composable
private fun SoapContent(
    viewModel: MainViewModel,
    evaluation: EvaluationResult,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit
) {
    val t = LocalTranslate.current
    // Structured S/O/A/P editor (mirrors session_base.py's _check_soap_note_dialog four fields),
    // serialized to the same {subjective,objective,assessment,plan} JSON the DB writer and
    // AnalysisPromptBuilder's <STUDENT_SOAP> block expect.
    var subjective by remember(evaluation) { mutableStateOf("") }
    var objective by remember(evaluation) { mutableStateOf("") }
    var assessment by remember(evaluation) { mutableStateOf("") }
    var plan by remember(evaluation) { mutableStateOf("") }
    val anyFilled = listOf(subjective, objective, assessment, plan).any { it.isNotBlank() }

    Text(t("feedback.soap_yours"), fontWeight = FontWeight.Bold)
    Text(
        t("Write your own SOAP note, then compare it against the AI and reference notes below."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SoapField(t("Subjective (S)"), subjective) { subjective = it; onSavedChange(false) }
    SoapField(t("Objective (O)"), objective) { objective = it; onSavedChange(false) }
    SoapField(t("Assessment (A)"), assessment) { assessment = it; onSavedChange(false) }
    SoapField(t("Plan (P)"), plan) { plan = it; onSavedChange(false) }
    OutlinedButton(
        onClick = {
            val soapJson = JSONObject()
                .put("subjective", subjective.trim())
                .put("objective", objective.trim())
                .put("assessment", assessment.trim())
                .put("plan", plan.trim())
                .toString()
            viewModel.saveStudentSoapNote(soapJson)
            onSavedChange(true)
        },
        enabled = anyFilled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(t("Save my SOAP note"))
    }
    if (saved) Text(t("SOAP note saved."), color = MaterialTheme.colorScheme.primary)

    HorizontalDivider()
    if (evaluation.evaluationLocked) {
        LockedFeedbackPanel(t("demo.locked_soap"))
        return
    }
    Text(t("AI-generated SOAP"), fontWeight = FontWeight.Bold)
    Text(formatSoapForDisplay(evaluation.soapNote).ifBlank { t("feedback.no_soap") })
    HorizontalDivider()
    Text(t("feedback.soap_reference"), fontWeight = FontWeight.Bold)
    Text(evaluation.referenceSoap.ifBlank { t("feedback.soap_na") })
}

@Composable
private fun SoapField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text(label) }
    )
}

@Composable
internal fun ShadowingContent(
    evaluation: EvaluationResult,
    everyday: Boolean,
    items: List<ShadowingFeedbackItem>,
    ttsReady: Boolean,
    onSpeak: (String, Int) -> Unit,
    judgeAttempt: (suspend (String, ByteArray) -> com.example.medvoicetrainer.analysis.SpeakingJudgment?)?
) {
    val t = LocalTranslate.current
    if (evaluation.evaluationLocked) {
        LockedFeedbackPanel(
            t(if (everyday) "demo.locked_shadowing_everyday" else "demo.locked_shadowing")
        )
        return
    }
    Text(
        t(if (everyday) "feedback.shadowing_intro_everyday" else "feedback.shadowing_intro"),
        style = MaterialTheme.typography.bodySmall
    )
    if (items.isEmpty()) {
        Text(t("feedback.no_shadowing_material"))
        return
    }
    items.forEachIndexed { index, item ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("#${index + 1}", style = MaterialTheme.typography.labelSmall)
                if (item.original.isNotBlank()) {
                    Text(t("feedback.shadowing_you_said"), fontWeight = FontWeight.Bold)
                    Text(item.original, color = MaterialTheme.colorScheme.error)
                }
                Text(t("feedback.shadowing_ideal_version"), fontWeight = FontWeight.Bold)
                Text(item.target, color = MaterialTheme.colorScheme.primary)
                if (item.focus.isNotBlank()) {
                    Text("${t("Focus:")} ${item.focus}", style = MaterialTheme.typography.bodySmall)
                }
                // Listen → imitate → record → AI check: closes the shadowing loop instead
                // of stopping at "listen then imitate" with no feedback on the imitation.
                PronunciationAttemptPanel(
                    targetText = item.target,
                    ttsReady = ttsReady,
                    onSpeakTarget = { onSpeak(item.target, index) },
                    judge = judgeAttempt?.let { judge -> { pcm -> judge(item.target, pcm) } }
                )
                if (!ttsReady) {
                    Text(t("Preparing Android text-to-speech…"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * §6 (early-UX): the one-time next-day practice reminder opt-in, shown on the first live-session
 * feedback screen that the banner queue lets through. A two-button inline card, never a modal —
 * declining is one tap and it never blocks the feedback the learner actually came to see.
 */
@Composable
private fun ReminderOptInCard(
    title: String,
    body: String,
    optInLabel: String,
    declineLabel: String,
    onOptIn: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOptIn, modifier = Modifier.weight(1f)) { Text(optInLabel) }
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text(declineLabel) }
            }
        }
    }
}
