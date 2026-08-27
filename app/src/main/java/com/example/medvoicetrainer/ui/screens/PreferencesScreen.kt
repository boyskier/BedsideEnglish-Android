package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.PracticeExperience
import com.example.medvoicetrainer.ui.realBackendForSavedCredentials
import com.example.medvoicetrainer.ui.components.SettingsSection
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * "Check for updates" row for the About section.
 *
 * Reports every outcome, including "you're already on the latest build" — a check that answers
 * nothing reads as broken. When Google Play cannot run the in-app flow (a sideloaded or debug APK,
 * Play signed out, no network) it falls back to opening the store listing instead of showing an
 * error the tester can do nothing about.
 */
@Composable
private fun UpdateCheckRow() {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = com.example.medvoicetrainer.update.LocalInAppUpdate.current
    var checking by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = {
            if (controller == null) {
                com.example.medvoicetrainer.update.InAppUpdateManager.openPlayStoreListing(context)
                return@OutlinedButton
            }
            checking = true
            controller.checkForUpdates(userInitiated = true) { result ->
                checking = false
                val message = when (result) {
                    com.example.medvoicetrainer.update.ManualCheckResult.STARTED ->
                        t("update.started")
                    com.example.medvoicetrainer.update.ManualCheckResult.ALREADY_DOWNLOADED ->
                        t("update.ready_title")
                    com.example.medvoicetrainer.update.ManualCheckResult.UP_TO_DATE ->
                        t("update.up_to_date")
                    com.example.medvoicetrainer.update.ManualCheckResult.UNAVAILABLE ->
                        t("update.play_fallback")
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                if (result == com.example.medvoicetrainer.update.ManualCheckResult.UNAVAILABLE) {
                    com.example.medvoicetrainer.update.InAppUpdateManager.openPlayStoreListing(context)
                }
            }
        },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (checking) t("update.checking") else t("update.check_now"),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** §11 "minicrumb" section header — small, uppercase, muted, used for subgroups inside a section. */
@Composable
private fun PrefsGroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp
    )
}

/**
 * One switch row: bold title, muted explanation underneath, switch on the right.
 *
 * Every toggle in Preferences was hand-rolling this same Row/Column/Switch shape with slightly
 * different spacing, which is a large part of why the screen read as noise. One shape for all of
 * them makes the list scannable.
 */
@Composable
private fun PrefsSwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A compact radio option for a multi-state preference, styled to sit inside a switch-row list. */
@Composable
private fun PrefsRadioRow(
    selected: Boolean,
    title: String,
    detail: String?,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PracticeExperienceOption(
    selected: Boolean,
    title: String,
    detail: String,
    supporting: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Per-provider "✓ Active" / "Not set" / verification status pill and test button. */
@Composable
private fun ProviderStatusPill(
    active: Boolean,
    status: com.example.medvoicetrainer.api.ProviderVerificationResult? = null,
    onVerify: () -> Unit = {}
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (label, color) = when (status?.status) {
            com.example.medvoicetrainer.api.ProviderStatus.VERIFYING -> t("prefs.key_verifying") to MaterialTheme.colorScheme.tertiary
            com.example.medvoicetrainer.api.ProviderStatus.VERIFIED -> "✓ " + t("prefs.key_verified") to MaterialTheme.colorScheme.primary
            com.example.medvoicetrainer.api.ProviderStatus.INVALID_KEY -> "⚠ " + t("prefs.key_invalid") to MaterialTheme.colorScheme.error
            com.example.medvoicetrainer.api.ProviderStatus.ERROR -> "⚠ " + t("prefs.key_error") to MaterialTheme.colorScheme.error
            else -> if (active) "✓ " + t("prefs.key_active") to MaterialTheme.colorScheme.primary else t("prefs.key_not_set") to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            color = if (label.startsWith("✓")) MaterialTheme.colorScheme.primaryContainer else if (label.startsWith("⚠")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (active) {
            OutlinedButton(
                onClick = onVerify,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(t("prefs.key_test"), fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCombobox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    candidates: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(candidates, value) {
        if (value.isNotBlank() && value !in candidates) candidates + value else candidates
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Stable ids for the accordion so only one group is open at a time. */
private const val SECTION_CONNECTION = "connection"
private const val SECTION_EXPERIENCE = "experience"
private const val SECTION_PRACTICE = "practice"
private const val SECTION_PRIVACY = "privacy"
private const val SECTION_TRANSFER = "transfer"
private const val SECTION_ADVANCED = "advanced"
private const val SECTION_ABOUT = "about"

/**
 * Preferences, organised as six collapsed groups instead of one flat scroll of ~30 controls.
 *
 * Nothing was removed: every key, model id, toggle, and link that used to be here is still here.
 * What changed is that each group now states its own current value in its collapsed header
 * ("Gemini · OpenAI connected", "Korean · 1.0×"), so the common visit — check or change one thing —
 * no longer requires scrolling past everything else. Provider/model plumbing that a typical
 * learner should never touch is gathered under Advanced, which stays closed by default.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PreferencesScreen(
    viewModel: MainViewModel,
    focusProvider: String? = null,
    onNavigateBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onOpenImport: () -> Unit = {}
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val currentOpenAiKey by viewModel.openAiApiKey.collectAsStateWithLifecycle()
    val currentClaudeKey by viewModel.claudeApiKey.collectAsStateWithLifecycle()
    val currentModel by viewModel.geminiModel.collectAsStateWithLifecycle()
    val currentGeminiVoiceModel by viewModel.geminiVoiceModel.collectAsStateWithLifecycle()
    val currentOpenAiVoiceModel by viewModel.openAiVoiceModel.collectAsStateWithLifecycle()
    val currentClaudeModel by viewModel.claudeModel.collectAsStateWithLifecycle()
    val currentOpenAiModel by viewModel.openAiModel.collectAsStateWithLifecycle()
    val providerStatusMap by viewModel.providerStatusMap.collectAsStateWithLifecycle()
    val analysisModelsMap by viewModel.analysisModelsMap.collectAsStateWithLifecycle()
    val voiceModelsMap by viewModel.voiceModelsMap.collectAsStateWithLifecycle()
    val currentL1 by viewModel.nativeLanguage.collectAsStateWithLifecycle()
    // val currentUiLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val analysisBackend by viewModel.analysisBackend.collectAsStateWithLifecycle()
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val pronunciationAnalysisEnabled by viewModel.pronunciationAnalysisEnabled.collectAsStateWithLifecycle()
    val currentAzureSpeechKey by viewModel.azureSpeechKey.collectAsStateWithLifecycle()
    val currentAzureSpeechRegion by viewModel.azureSpeechRegion.collectAsStateWithLifecycle()
    val learnerAudioSavingEnabled by viewModel.learnerAudioSavingEnabled.collectAsStateWithLifecycle()
    val strictListenerEnabled by viewModel.strictListenerEnabled.collectAsStateWithLifecycle()
    val correctionAutoSaveEnabled by viewModel.correctionAutoSaveEnabled.collectAsStateWithLifecycle()
    val nativeExplanationsEnabled by viewModel.nativeExplanationsEnabled.collectAsStateWithLifecycle()
    val currentPracticeExperience by viewModel.practiceExperience.collectAsStateWithLifecycle()

    var tempPronunciationEnabled by remember { mutableStateOf(pronunciationAnalysisEnabled) }
    var tempAzureSpeechKey by remember { mutableStateOf(currentAzureSpeechKey) }
    var tempAzureSpeechRegion by remember { mutableStateOf(currentAzureSpeechRegion) }
    var tempLearnerAudioSavingEnabled by remember { mutableStateOf(learnerAudioSavingEnabled) }
    var tempStrictListenerEnabled by remember { mutableStateOf(strictListenerEnabled) }
    var tempCorrectionAutoSaveEnabled by remember { mutableStateOf(correctionAutoSaveEnabled) }
    var tempNativeExplanationsEnabled by remember { mutableStateOf(nativeExplanationsEnabled) }
    var tempPracticeExperience by remember(currentPracticeExperience) {
        mutableStateOf(currentPracticeExperience ?: PracticeExperience.ALL_FEATURES)
    }

    var tempGeminiKey by remember { mutableStateOf(currentKey) }
    var tempGeminiModel by remember { mutableStateOf(currentModel) }
    var tempGeminiVoiceModel by remember { mutableStateOf(currentGeminiVoiceModel) }
    var tempOpenAiVoiceModel by remember { mutableStateOf(currentOpenAiVoiceModel) }
    var tempClaudeModel by remember { mutableStateOf(currentClaudeModel) }
    var tempOpenAiModel by remember { mutableStateOf(currentOpenAiModel) }
    var tempL1 by remember { mutableStateOf(currentL1) }

    // Keys may live outside the StateFlow (env/.env fallback), so the "what was loaded" baseline
    // has to be captured once — it is what the unsaved-changes badge compares against.
    val claudeKeyBaseline = remember { currentClaudeKey.ifEmpty { viewModel.getApiKeyForBackend("claude") } }
    val openAiKeyBaseline = remember { currentOpenAiKey.ifEmpty { viewModel.getApiKeyForBackend("openai") } }
    var tempClaudeKey by remember { mutableStateOf(claudeKeyBaseline) }
    var tempOpenAiKey by remember { mutableStateOf(openAiKeyBaseline) }
    var tempAnalysisBackend by remember { mutableStateOf(analysisBackend) }
    // Demo/mock are internal preview/dev transports, not user-selectable voice providers.
    var tempVoiceBackend by remember(voiceBackend, currentKey, openAiKeyBaseline) {
        mutableStateOf(
            realBackendForSavedCredentials(voiceBackend, currentKey, openAiKeyBaseline)
                .takeIf { it in setOf("gemini", "openai") }
                ?: "gemini"
        )
    }

    // Persisted immediately rather than behind Save, but hoisted here so the collapsed section
    // headers can summarise them.
    val defaultSpeed by viewModel.defaultPlaybackSpeed.collectAsStateWithLifecycle()
    val guidedModeEnabled by viewModel.guidedModeEnabled.collectAsStateWithLifecycle()
    val openBookEnabled by viewModel.openBookEnabled.collectAsStateWithLifecycle()
    val phrasebookEnabled by viewModel.phrasebookEnabled.collectAsStateWithLifecycle()
    val openBookMicPolicy by viewModel.openBookMicPolicy.collectAsStateWithLifecycle()
    var reminderEnabled by remember { mutableStateOf(viewModel.isPracticeReminderEnabled()) }

    // §13 "Invalid / expired API key" routing: ErrorDialogUi passes which provider's key was
    // rejected, so the offending field can be scrolled to, focused, and visually flagged instead
    // of just landing on Preferences' top and leaving the user to guess which field is wrong.
    val geminiKeyBringIntoView = remember { BringIntoViewRequester() }
    val geminiKeyFocus = remember { FocusRequester() }
    val openAiKeyBringIntoView = remember { BringIntoViewRequester() }
    val openAiKeyFocus = remember { FocusRequester() }
    val claudeKeyBringIntoView = remember { BringIntoViewRequester() }
    val claudeKeyFocus = remember { FocusRequester() }
    val errorBorder = BorderStroke(2.dp, MaterialTheme.colorScheme.error)

    // A key-repair deep link opens Providers straight away. On an ordinary Settings visit, open
    // the experience picker so the app-wide practice choice is immediately visible.
    var expandedSection by rememberSaveable {
        mutableStateOf<String?>(if (focusProvider != null) SECTION_CONNECTION else SECTION_EXPERIENCE)
    }
    fun toggleSection(id: String) {
        expandedSection = if (expandedSection == id) null else id
    }

    // TalkBack click labels for the accordion headers, shared by all seven groups.
    val expandLabel = t("prefs.section_expand")
    val collapseLabel = t("prefs.section_collapse")

    LaunchedEffect(focusProvider) {
        if (focusProvider == null) return@LaunchedEffect
        expandedSection = SECTION_CONNECTION
        // The field only exists once the section's AnimatedVisibility has composed it, so the
        // focus/scroll request has to wait for that frame rather than run in the same one.
        kotlinx.coroutines.delay(250)
        runCatching {
            when (focusProvider) {
                "gemini" -> { geminiKeyBringIntoView.bringIntoView(); geminiKeyFocus.requestFocus() }
                "openai" -> { openAiKeyBringIntoView.bringIntoView(); openAiKeyFocus.requestFocus() }
                "claude" -> { claudeKeyBringIntoView.bringIntoView(); claudeKeyFocus.requestFocus() }
            }
        }
    }

    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val apiUsageEvents by viewModel.apiUsageEvents.collectAsStateWithLifecycle()
    var showCostAnalytics by remember { mutableStateOf(false) }
    var showExportBackupDialog by remember { mutableStateOf(false) }
    var selectedImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingExportIncludesAudio by remember { mutableStateOf(true) }
    // The run itself lives in the ViewModel, so leaving this screen mid-backup no longer kills it.
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val backupBusy = backupState.running
    val canSaveBackupToDownloads = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    val exportBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
            com.example.medvoicetrainer.export.UserBackupManager.MIME_TYPE
        )
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = ""
        if (uri != null) viewModel.startUserBackupExport(uri, password, pendingExportIncludesAudio)
    }
    val importBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> selectedImportUri = uri }

    if (showExportBackupDialog) {
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var includeAudio by remember { mutableStateOf(true) }
        var understood by remember { mutableStateOf(false) }
        val valid = password.length >= com.example.medvoicetrainer.export.UserBackupManager.MIN_PASSWORD_LENGTH &&
            password == confirmation && understood
        AlertDialog(
            onDismissRequest = { showExportBackupDialog = false },
            title = { Text(t("prefs.backup_export_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("prefs.backup_export_notice"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(t("prefs.backup_password")) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(t("prefs.backup_password_confirm")) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = confirmation.isNotEmpty() && password != confirmation,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeAudio, onCheckedChange = { includeAudio = it })
                        Text(t("prefs.backup_include_audio"), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text(t("prefs.backup_password_warning"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        showExportBackupDialog = false
                        if (canSaveBackupToDownloads) {
                            viewModel.startUserBackupExportToDownloads(password, includeAudio)
                        } else {
                            pendingExportPassword = password
                            pendingExportIncludesAudio = includeAudio
                            exportBackupLauncher.launch(
                                "bedside-english-${java.time.LocalDate.now()}.${com.example.medvoicetrainer.export.UserBackupManager.FILE_EXTENSION}"
                            )
                        }
                    }
                ) {
                    Text(
                        if (canSaveBackupToDownloads) {
                            t("prefs.backup_save_to_downloads")
                        } else {
                            t("prefs.backup_choose_location")
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingExportPassword = password
                        pendingExportIncludesAudio = includeAudio
                        showExportBackupDialog = false
                        exportBackupLauncher.launch(
                            "bedside-english-${java.time.LocalDate.now()}.${com.example.medvoicetrainer.export.UserBackupManager.FILE_EXTENSION}"
                        )
                    }
                ) { Text(t("prefs.backup_choose_other_location")) }
            }
        )
    }

    selectedImportUri?.let { importUri ->
        var password by remember(importUri) { mutableStateOf("") }
        var replaceUnderstood by remember(importUri) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!backupBusy) selectedImportUri = null },
            title = { Text(t("prefs.backup_restore_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("prefs.backup_restore_notice"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(t("prefs.backup_password")) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !backupBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = replaceUnderstood,
                            onCheckedChange = { replaceUnderstood = it },
                            enabled = !backupBusy
                        )
                        Text(t("prefs.backup_replace_warning"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (backupBusy) BackupProgressBlock(backupState, t)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy && replaceUnderstood &&
                        password.length >= com.example.medvoicetrainer.export.UserBackupManager.MIN_PASSWORD_LENGTH,
                    onClick = {
                        viewModel.startUserBackupRestore(importUri, password)
                        selectedImportUri = null
                    }
                ) { Text(t("prefs.backup_restore_action")) }
            },
            dismissButton = {
                TextButton(enabled = !backupBusy, onClick = { selectedImportUri = null }) {
                    Text(t("Cancel"))
                }
            }
        )
    }

    if (backupBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    if (backupState.operation == com.example.medvoicetrainer.ui.BackupOperation.RESTORE) {
                        t("prefs.backup_restore_title")
                    } else {
                        t("prefs.backup_export_title")
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (backupState.operation == com.example.medvoicetrainer.ui.BackupOperation.RESTORE) {
                            t("prefs.backup_restoring")
                        } else {
                            t("prefs.backup_working")
                        }
                    )
                    BackupProgressBlock(backupState, t)
                    Text(
                        t("prefs.backup_keep_open"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }

    backupState.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = viewModel::clearBackupResult,
            title = { Text(t("prefs.backup_error_title")) },
            text = { Text(t(failure.messageKey)) },
            confirmButton = {
                TextButton(onClick = viewModel::clearBackupResult) { Text(t("OK")) }
            }
        )
    }

    // An export just reports; a restore replaced everything the running process still has cached in
    // memory, so it must end in a restart.
    backupState.summary?.takeIf { backupState.operation == com.example.medvoicetrainer.ui.BackupOperation.EXPORT }
        ?.let { summary ->
            AlertDialog(
                onDismissRequest = viewModel::clearBackupResult,
                title = { Text(t("prefs.backup_export_complete_title")) },
                text = {
                    Text(
                        t("prefs.backup_export_success")
                            .replace("{sessions}", summary.sessionCount.toString())
                            .replace("{audio}", summary.audioFileCount.toString())
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::clearBackupResult) { Text(t("OK")) }
                }
            )
        }

    backupState.summary?.takeIf { backupState.operation == com.example.medvoicetrainer.ui.BackupOperation.RESTORE }
        ?.let { summary ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(t("prefs.backup_restore_complete_title")) },
                text = {
                    Text(
                        t("prefs.backup_restore_complete")
                            .replace("{sessions}", summary.sessionCount.toString())
                            .replace("{audio}", summary.audioFileCount.toString())
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Restarting the task is the clean path. When the launcher component cannot
                        // be resolved the learner must still be able to leave this dialog, so fall
                        // back to closing the app rather than trapping them in a modal with no exit.
                        val component = context.packageManager
                            .getLaunchIntentForPackage(context.packageName)?.component
                        if (component != null) {
                            context.startActivity(android.content.Intent.makeRestartActivityTask(component))
                        } else {
                            context.findActivity()?.finishAffinity()
                        }
                    }) { Text(t("prefs.backup_restart")) }
                }
            )
        }
    if (showCostAnalytics) {
        androidx.activity.compose.BackHandler { showCostAnalytics = false }
        ApiCostAnalyticsScreen(
            sessions = sessions,
            apiUsageEvents = apiUsageEvents,
            onClose = { showCostAnalytics = false }
        )
        return
    }

    val languageOptions = listOf(
        "ko" to "prefs.lang_korean", "es" to "prefs.lang_spanish", "zh" to "prefs.lang_chinese",
        "ar" to "prefs.lang_arabic", "hi" to "prefs.lang_hindi", "pt" to "prefs.lang_portuguese",
        "tl" to "prefs.lang_tagalog", "ja" to "prefs.lang_japanese", "id" to "prefs.lang_indonesian", "vi" to "prefs.lang_vietnamese"
    )
    /*
    // Use each language's own name here. A learner who can no longer read the current app UI
    // must still be able to recognize and switch to their preferred display language.
    val displayLanguageOptions = listOf(
        "ko" to "한국어", "en" to "English", "es" to "Español", "zh" to "中文",
        "ar" to "العربية", "hi" to "हिन्दी", "pt" to "Português", "tl" to "Filipino",
        "ja" to "日本語", "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt", "ru" to "Русский"
    )
    */
    fun analysisBackendLabel(backend: String) = when (backend) {
        "gemini" -> "Gemini"
        "openai" -> "OpenAI"
        "claude" -> "Claude"
        else -> backend
    }
    fun voiceBackendLabel(backend: String) = when (backend) {
        "gemini" -> "Gemini Live"
        "openai" -> "OpenAI Realtime"
        else -> "Gemini Live"
    }

    // Each collapsed header answers "what is this set to right now?" so the common visit does not
    // require expanding anything at all.
    val connectedProviders = listOfNotNull(
        "Gemini".takeIf { tempGeminiKey.isNotBlank() },
        "OpenAI".takeIf { tempOpenAiKey.isNotBlank() },
        "Claude".takeIf { tempClaudeKey.isNotBlank() }
    )
    val connectionSummary = if (connectedProviders.isEmpty()) {
        t("prefs.summary_no_keys")
    } else {
        t("prefs.summary_keys").replace("{providers}", connectedProviders.joinToString(" · "))
    }
    val experienceSummary = if (tempPracticeExperience == PracticeExperience.ALL_FEATURES) "All features" else "Everyday English only"
    val practiceSummary = t("prefs.summary_practice")
        .replace("{language}", t(languageOptions.firstOrNull { it.first == tempL1 }?.second ?: "prefs.lang_other"))
        .replace("{speed}", "${(defaultSpeed * 100).roundToInt() / 100f}×")
    val privacyOn = listOfNotNull(
        t("prefs.short_voice_clips").takeIf { tempLearnerAudioSavingEnabled },
        t("prefs.short_pronunciation").takeIf { tempPronunciationEnabled },
        t("prefs.short_reminder").takeIf { reminderEnabled }
    )
    val privacySummary = if (privacyOn.isEmpty()) {
        t("prefs.summary_privacy_none")
    } else {
        t("prefs.summary_privacy_on").replace("{items}", privacyOn.joinToString(" · "))
    }
    val advancedSummary = t("prefs.summary_advanced")
        .replace("{analysis}", analysisBackendLabel(tempAnalysisBackend))
        .replace("{voice}", voiceBackendLabel(tempVoiceBackend))
    val versionLine = "v${com.example.medvoicetrainer.BuildConfig.VERSION_NAME} " +
        "(${com.example.medvoicetrainer.BuildConfig.VERSION_CODE})"

    // Only the Save-gated fields count here; the switches that persist on tap are already applied.
    val savedClaudeKey = currentClaudeKey.ifEmpty { claudeKeyBaseline }
    val savedOpenAiKey = currentOpenAiKey.ifEmpty { openAiKeyBaseline }
    val isDirty = tempGeminiKey != currentKey ||
        tempOpenAiKey != savedOpenAiKey ||
        tempClaudeKey != savedClaudeKey ||
        tempGeminiModel != currentModel ||
        tempOpenAiModel != currentOpenAiModel ||
        tempClaudeModel != currentClaudeModel ||
        tempGeminiVoiceModel != currentGeminiVoiceModel ||
        tempOpenAiVoiceModel != currentOpenAiVoiceModel ||
        tempAnalysisBackend != analysisBackend ||
        tempVoiceBackend != voiceBackend ||
        tempPracticeExperience != (currentPracticeExperience ?: PracticeExperience.ALL_FEATURES) ||
        tempL1 != currentL1 ||
        tempPronunciationEnabled != pronunciationAnalysisEnabled ||
        tempStrictListenerEnabled != strictListenerEnabled ||
        tempCorrectionAutoSaveEnabled != correctionAutoSaveEnabled ||
        tempNativeExplanationsEnabled != nativeExplanationsEnabled ||
        tempLearnerAudioSavingEnabled != learnerAudioSavingEnabled ||
        tempAzureSpeechKey != currentAzureSpeechKey ||
        tempAzureSpeechRegion != currentAzureSpeechRegion

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("prefs.title")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = t("Back"))
                    }
                }
            )
        },
        bottomBar = {
            // Save used to sit at the very bottom of an ~2500dp scroll. It is now always reachable,
            // and says whether there is actually anything to save.
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (isDirty) {
                        Text(
                            t("prefs.unsaved_badge"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.updateGeminiApiKey(tempGeminiKey)
                            viewModel.updateGeminiModel(tempGeminiModel)
                            viewModel.updateOpenAiModel(tempOpenAiModel)
                            viewModel.updateClaudeModel(tempClaudeModel)
                            viewModel.updateVoiceModel("gemini", tempGeminiVoiceModel)
                            viewModel.updateVoiceModel("openai", tempOpenAiVoiceModel)
                            viewModel.setApiKeyForBackend("openai", tempOpenAiKey)
                            viewModel.setApiKeyForBackend("claude", tempClaudeKey)
                            viewModel.updateAnalysisBackend(tempAnalysisBackend)
                            viewModel.updateVoiceBackend(tempVoiceBackend)
                            viewModel.updatePracticeExperience(tempPracticeExperience)
                            viewModel.updateNativeLanguage(tempL1)
                            viewModel.updatePronunciationAnalysisEnabled(tempPronunciationEnabled)
                            viewModel.updateStrictListenerEnabled(tempStrictListenerEnabled)
                            viewModel.updateCorrectionAutoSaveEnabled(tempCorrectionAutoSaveEnabled)
                            viewModel.updateNativeExplanationsEnabled(tempNativeExplanationsEnabled)
                            viewModel.updateAzureSpeechCredentials(
                                tempAzureSpeechKey,
                                tempAzureSpeechRegion
                            )
                            viewModel.updateLearnerAudioSavingEnabled(tempLearnerAudioSavingEnabled)
                            android.widget.Toast.makeText(context, t("prefs.saved"), android.widget.Toast.LENGTH_SHORT).show()
                            onSaved()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(t("prefs.save_changes"))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ───────────────────────── Providers & keys ─────────────────────────
            SettingsSection(
                icon = Icons.Default.Key,
                title = t("prefs.section_connection"),
                summary = connectionSummary,
                expanded = expandedSection == SECTION_CONNECTION,
                onToggle = { toggleSection(SECTION_CONNECTION) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t("onboarding.v2.no_collection"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gemini", fontWeight = FontWeight.Bold)
                    ProviderStatusPill(
                        active = tempGeminiKey.isNotBlank(),
                        status = providerStatusMap["gemini"],
                        onVerify = { viewModel.verifyProviderKey("gemini", tempGeminiKey) }
                    )
                }
                OutlinedTextField(
                    value = tempGeminiKey,
                    onValueChange = {
                        tempGeminiKey = it
                        viewModel.clearProviderVerification("gemini")
                    },
                    label = { Text(t("prefs.gemini_api_key")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(geminiKeyBringIntoView)
                        .focusRequester(geminiKeyFocus)
                        .then(if (focusProvider == "gemini") Modifier.border(errorBorder, RoundedCornerShape(4.dp)) else Modifier),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = tempGeminiKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeGeminiKey(tempGeminiKey),
                    supportingText = {
                        if (tempGeminiKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeGeminiKey(tempGeminiKey)) {
                            Text(t("prefs.key_format_gemini"))
                        }
                    }
                )
                // Google AI Studio is where the key is issued AND where its real quota/usage lives;
                // this app's Cost Analytics screen only ever shows a local estimate. Both destinations
                // sit right under the key field so neither requires hunting for a URL.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { uriHandler.openUri(GEMINI_API_KEY_URL) }) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("prefs.get_gemini_key"), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { uriHandler.openUri(GOOGLE_AI_STUDIO_USAGE_URL) }) {
                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("prefs.ai_studio_usage"), style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OpenAI", fontWeight = FontWeight.Bold)
                    ProviderStatusPill(
                        active = tempOpenAiKey.isNotBlank(),
                        status = providerStatusMap["openai"],
                        onVerify = { viewModel.verifyProviderKey("openai", tempOpenAiKey) }
                    )
                }
                OutlinedTextField(
                    value = tempOpenAiKey,
                    onValueChange = {
                        tempOpenAiKey = it
                        viewModel.clearProviderVerification("openai")
                    },
                    label = { Text(t("prefs.openai_api_key")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(openAiKeyBringIntoView)
                        .focusRequester(openAiKeyFocus)
                        .then(if (focusProvider == "openai") Modifier.border(errorBorder, RoundedCornerShape(4.dp)) else Modifier),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = tempOpenAiKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeOpenAiKey(tempOpenAiKey),
                    supportingText = {
                        if (tempOpenAiKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeOpenAiKey(tempOpenAiKey)) {
                            Text(t("prefs.key_format_openai"))
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Claude", fontWeight = FontWeight.Bold)
                    ProviderStatusPill(
                        active = tempClaudeKey.isNotBlank(),
                        status = providerStatusMap["claude"],
                        onVerify = { viewModel.verifyProviderKey("claude", tempClaudeKey) }
                    )
                }
                OutlinedTextField(
                    value = tempClaudeKey,
                    onValueChange = {
                        tempClaudeKey = it
                        viewModel.clearProviderVerification("claude")
                    },
                    label = { Text(t("prefs.anthropic_api_key")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(claudeKeyBringIntoView)
                        .focusRequester(claudeKeyFocus)
                        .then(if (focusProvider == "claude") Modifier.border(errorBorder, RoundedCornerShape(4.dp)) else Modifier),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = tempClaudeKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeClaudeKey(tempClaudeKey),
                    supportingText = {
                        if (tempClaudeKey.isNotEmpty() && !com.example.medvoicetrainer.analysis.UnlockSheet.looksLikeClaudeKey(tempClaudeKey)) {
                            Text(t("prefs.key_format_claude"))
                        }
                    }
                )
            }

            // ───────────────────────── Practice & feedback ─────────────────────────
            // This is intentionally the second Settings card: the choice determines which parts
            // of the app are shown, so it should not be buried in practice-detail controls.
            SettingsSection(
                icon = Icons.Default.Tune,
                title = t("Practice experience"),
                summary = experienceSummary,
                expanded = expandedSection == SECTION_EXPERIENCE,
                onToggle = { toggleSection(SECTION_EXPERIENCE) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                Text(
                    "Choose which practice modes appear. This never deletes or filters your history or saved reviews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PracticeExperienceOption(
                    selected = tempPracticeExperience == PracticeExperience.ALL_FEATURES,
                    title = t("All features"),
                    detail = "Patient conversations + everyday English",
                    supporting = "Includes every practice mode and feature.",
                    onClick = { tempPracticeExperience = PracticeExperience.ALL_FEATURES }
                )
                PracticeExperienceOption(
                    selected = tempPracticeExperience == PracticeExperience.EVERYDAY_ENGLISH,
                    title = t("Everyday English only"),
                    detail = "Everyday speaking and listening",
                    supporting = "Patient-communication tools are hidden; history and reviews stay visible.",
                    onClick = { tempPracticeExperience = PracticeExperience.EVERYDAY_ENGLISH }
                )
            }

            SettingsSection(
                icon = Icons.Default.School,
                title = t("prefs.section_practice"),
                summary = practiceSummary,
                expanded = expandedSection == SECTION_PRACTICE,
                onToggle = { toggleSection(SECTION_PRACTICE) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                /*
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("prefs.display_language_title"), fontWeight = FontWeight.Bold)
                    Text(
                        t("prefs.ui_language_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        displayLanguageOptions.forEach { (code, label) ->
                            FilterChip(
                                selected = currentUiLanguage == code,
                                onClick = { viewModel.updateUiLanguage(code) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                HorizontalDivider()
                */

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("prefs.native_language_title"), fontWeight = FontWeight.Bold)
                    Text(
                        t("prefs.native_language_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        languageOptions.forEach { (code, labelKey) ->
                            FilterChip(
                                selected = tempL1 == code,
                                onClick = { tempL1 = code },
                                label = { Text(t(labelKey)) }
                            )
                        }
                    }
                }

                // The default patient speed row, persisted and applied automatically at the start
                // of every session (see MainViewModel.startSessionInternal).
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("prefs.default_speed_title"), fontWeight = FontWeight.Bold)
                            Text(
                                t("prefs.default_speed_detail"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${((defaultSpeed * 100).roundToInt() / 100f)}x",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = defaultSpeed,
                        onValueChange = { raw ->
                            val clean = ((raw * 100).roundToInt() / 100f)
                            viewModel.updateDefaultPlaybackSpeed(clean)
                        },
                        valueRange = 0.5f..2.5f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Beginner guided scaffold toggle (GuidedCueEngine) — the live "try saying ___" cue
                // in Foundations / coaching_mode encounters. Persists immediately, not behind Save.
                PrefsSwitchRow(
                    title = t("prefs.guided_mode_title"),
                    detail = t("prefs.guided_mode_detail"),
                    checked = guidedModeEnabled,
                    onCheckedChange = { viewModel.updateGuidedModeEnabled(it) }
                )
                // Open Book (OpenBookEngine) — the staged answer sheet in clinical encounters, for
                // a learner whose clinical knowledge rather than their English is what stalls them.
                PrefsSwitchRow(
                    title = t("prefs.open_book_title"),
                    detail = t("prefs.open_book_detail"),
                    checked = openBookEnabled,
                    onCheckedChange = { viewModel.updateOpenBookEnabled(it) }
                )
                if (openBookEnabled) {
                    // Three states rather than a switch: the old on/off could not express "mute
                    // while I read the diagnosis, but not while I'm reading sentences out loud",
                    // which is what almost everyone actually wants.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("prefs.open_book_mic_title"), fontWeight = FontWeight.Bold)
                        Text(
                            t("prefs.open_book_mic_detail"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        com.example.medvoicetrainer.analysis.OpenBookMicPolicy.entries.forEach { policy ->
                            PrefsRadioRow(
                                selected = openBookMicPolicy == policy,
                                title = t("prefs.open_book_mic.${policy.key}"),
                                detail = t("prefs.open_book_mic.${policy.key}_detail"),
                                onSelect = { viewModel.updateOpenBookMicPolicy(policy) }
                            )
                        }
                    }
                }
                // Everyday Phrasebook (EverydayPhrasebook) — the same scaffold for survival/lounge/
                // listening, where nothing is hidden but the English sentence itself. Shares the
                // microphone policy above rather than adding a second one.
                PrefsSwitchRow(
                    title = t("prefs.phrasebook_title"),
                    detail = t("prefs.phrasebook_detail"),
                    checked = phrasebookEnabled,
                    onCheckedChange = { viewModel.updatePhrasebookEnabled(it) }
                )
                // Strict-listener (exam) mode: telephone band + higher "understood" bar.
                PrefsSwitchRow(
                    title = t("prefs.strict_listener_title"),
                    detail = t("prefs.strict_listener_detail"),
                    checked = tempStrictListenerEnabled,
                    onCheckedChange = { tempStrictListenerEnabled = it }
                )
                // Write correction/coaching explanations in the learner's native language.
                PrefsSwitchRow(
                    title = t("prefs.native_explanations_title"),
                    detail = t("prefs.native_explanations_detail"),
                    checked = tempNativeExplanationsEnabled,
                    onCheckedChange = { tempNativeExplanationsEnabled = it }
                )
                // Default-on auto-save of high-confidence corrections; learners can opt out.
                PrefsSwitchRow(
                    title = t("prefs.auto_save_title"),
                    detail = t("prefs.auto_save_detail"),
                    checked = tempCorrectionAutoSaveEnabled,
                    onCheckedChange = { tempCorrectionAutoSaveEnabled = it }
                )
            }

            // ───────────────────────── Data & privacy ─────────────────────────
            SettingsSection(
                icon = Icons.Default.PrivacyTip,
                title = t("prefs.section_privacy"),
                summary = privacySummary,
                expanded = expandedSection == SECTION_PRIVACY,
                onToggle = { toggleSection(SECTION_PRIVACY) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                PrefsSwitchRow(
                    title = t("prefs.voice_clips_title"),
                    detail = t("prefs.voice_clips_detail"),
                    checked = tempLearnerAudioSavingEnabled,
                    onCheckedChange = { tempLearnerAudioSavingEnabled = it }
                )
                // Ported from preferences_window.py's pronunciation-analysis toggle. The long
                // rationale sits under the switch rather than above it, so the control stays first.
                PrefsSwitchRow(
                    title = t("prefs.pronunciation_title"),
                    detail = t("prefs.pronunciation_warning"),
                    checked = tempPronunciationEnabled,
                    onCheckedChange = { tempPronunciationEnabled = it }
                )
                Text(
                    t("prefs.pronunciation_value"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // §A1 (early-UX): the off-switch for the opt-in next-day practice reminder (offered
                // on the first-session feedback screen). Toggling on here (re)schedules from now.
                val reminderCtx = androidx.compose.ui.platform.LocalContext.current
                val reminderNotifTitle = t("early.reminder_notification_title")
                val reminderNotifBody = t("early.reminder_notification_body")
                PrefsSwitchRow(
                    title = t("early.reminder_pref_title"),
                    detail = t("early.reminder_pref_detail"),
                    checked = reminderEnabled,
                    onCheckedChange = { on ->
                        reminderEnabled = on
                        viewModel.setPracticeReminderEnabled(on)
                        if (on) {
                            com.example.medvoicetrainer.PracticeReminder.schedule(
                                reminderCtx, 20L * 60L * 60L * 1000L, reminderNotifTitle, reminderNotifBody
                            )
                        } else {
                            com.example.medvoicetrainer.PracticeReminder.cancel(reminderCtx)
                        }
                    }
                )
            }

            // ───────────────────────── Import & export ─────────────────────────
            SettingsSection(
                icon = Icons.Default.ImportExport,
                title = t("prefs.section_transfer"),
                summary = t("prefs.summary_transfer"),
                expanded = expandedSection == SECTION_TRANSFER,
                onToggle = { toggleSection(SECTION_TRANSFER) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                // Import — the permanent home for "Import a Conversation" after its dashboard card
                // is dismissed (see DashboardScreen). Paste a ChatGPT/Gemini transcript to have it
                // scored and tracked like a native session.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t("dashboard.import_card_title"), fontWeight = FontWeight.Bold)
                        Text(
                            t("dashboard.import_card_body"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onOpenImport) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("prefs.import"))
                    }
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("prefs.backup_title"), fontWeight = FontWeight.Bold)
                    Text(
                        t("prefs.backup_detail"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showExportBackupDialog = true },
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("prefs.backup_export_action"))
                        }
                        Button(
                            onClick = {
                                importBackupLauncher.launch(
                                    arrayOf(
                                        com.example.medvoicetrainer.export.UserBackupManager.MIME_TYPE,
                                        "application/zip",
                                        "*/*"
                                    )
                                )
                            },
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("prefs.backup_restore_action"))
                        }
                    }
                }
            }

            // ───────────────────────── Advanced ─────────────────────────
            // Backends, model ids, and the specialist pronunciation engine. Correct defaults ship
            // for all of these; a learner who never opens this group loses nothing.
            SettingsSection(
                icon = Icons.Default.Tune,
                title = t("prefs.section_advanced"),
                summary = advancedSummary,
                expanded = expandedSection == SECTION_ADVANCED,
                onToggle = { toggleSection(SECTION_ADVANCED) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                Text(
                    t("prefs.advanced_intro"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrefsGroupLabel(t("prefs.feedback_ai_title"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tempAnalysisBackend == "gemini", onClick = { tempAnalysisBackend = "gemini" }, label = { Text("Gemini") })
                        FilterChip(selected = tempAnalysisBackend == "openai", onClick = { tempAnalysisBackend = "openai" }, label = { Text("OpenAI") })
                        FilterChip(selected = tempAnalysisBackend == "claude", onClick = { tempAnalysisBackend = "claude" }, label = { Text("Claude") })
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrefsGroupLabel(t("prefs.voice_backend_title"))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(selected = tempVoiceBackend == "gemini", onClick = { tempVoiceBackend = "gemini" }, label = { Text("Gemini Live") })
                        FilterChip(selected = tempVoiceBackend == "openai", onClick = { tempVoiceBackend = "openai" }, label = { Text("OpenAI Realtime") })
                    }
                }

                val geminiAnalysisCandidates = analysisModelsMap["gemini"] ?: listOf(
                    "gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.5-pro", "gemini-3.1-pro",
                    "gemini-2.5-pro", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite"
                )
                val openAiAnalysisCandidates = analysisModelsMap["openai"] ?: listOf("gpt-5.1", "gpt-5.6-sol", "gpt-5.6-luna")
                val claudeAnalysisCandidates = analysisModelsMap["claude"] ?: listOf("claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5")

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrefsGroupLabel(t("prefs.analysis_models_title"))
                    ModelCombobox(
                        value = tempGeminiModel,
                        onValueChange = { tempGeminiModel = it },
                        label = t("prefs.model_gemini_analysis"),
                        candidates = geminiAnalysisCandidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelCombobox(
                        value = tempOpenAiModel,
                        onValueChange = { tempOpenAiModel = it },
                        label = t("prefs.model_openai_analysis"),
                        candidates = openAiAnalysisCandidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelCombobox(
                        value = tempClaudeModel,
                        onValueChange = { tempClaudeModel = it },
                        label = t("prefs.model_claude_analysis"),
                        candidates = claudeAnalysisCandidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val geminiVoiceCandidates = voiceModelsMap["gemini"] ?: listOf("gemini-3.1-flash-live-preview", "gemini-2.0-flash-exp")
                val openAiVoiceCandidates = voiceModelsMap["openai"] ?: listOf("gpt-realtime-2.1", "gpt-4o-realtime-preview")

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrefsGroupLabel(t("prefs.live_voice_models_title"))
                    ModelCombobox(
                        value = tempGeminiVoiceModel,
                        onValueChange = { tempGeminiVoiceModel = it },
                        label = t("prefs.model_gemini_voice"),
                        candidates = geminiVoiceCandidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelCombobox(
                        value = tempOpenAiVoiceModel,
                        onValueChange = { tempOpenAiVoiceModel = it },
                        label = t("prefs.model_openai_voice"),
                        candidates = openAiVoiceCandidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrefsGroupLabel(t("prefs.azure_title"))
                    Text(
                        if (tempAzureSpeechKey.isNotBlank() && tempAzureSpeechRegion.isNotBlank()) {
                            t("prefs.azure_active_detail")
                        } else {
                            t("prefs.azure_optional_detail")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = tempAzureSpeechKey,
                        onValueChange = { tempAzureSpeechKey = it },
                        label = { Text(t("prefs.azure_key")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = tempAzureSpeechRegion,
                        onValueChange = { tempAzureSpeechRegion = it.trim() },
                        label = { Text(t("prefs.azure_region")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError =
                            tempAzureSpeechRegion.isNotBlank() &&
                                !Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,38}[A-Za-z0-9]$")
                                    .matches(tempAzureSpeechRegion),
                        supportingText = {
                            when {
                                tempAzureSpeechKey.isBlank() != tempAzureSpeechRegion.isBlank() ->
                                    Text(t("prefs.azure_both_required"))
                                tempAzureSpeechRegion.isNotBlank() &&
                                    !Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,38}[A-Za-z0-9]$")
                                        .matches(tempAzureSpeechRegion) ->
                                    Text(t("prefs.azure_region_invalid"))
                            }
                        }
                    )
                    Text(
                        t("prefs.azure_score_principle"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ───────────────────────── About & usage ─────────────────────────
            SettingsSection(
                icon = Icons.Default.Info,
                title = t("prefs.section_about"),
                summary = versionLine,
                expanded = expandedSection == SECTION_ABOUT,
                onToggle = { toggleSection(SECTION_ABOUT) },
                expandLabel = expandLabel,
                collapseLabel = collapseLabel,
            ) {
                OutlinedButton(
                    onClick = { showCostAnalytics = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("prefs.cost_analytics_button"), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "Bedside English: Talk & Train — $versionLine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The app checks Google Play on every launch on its own; this is the manual route
                // for a tester who was just told a new build is out and does not want to wait for
                // the next cold start. It deliberately bypasses the "not now" cooldown.
                UpdateCheckRow()
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    fun openLink(url: String) {
                        try {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            // No app can handle the link; silently ignore.
                        }
                    }
                    // The shared product page is the public landing page for both apps.
                    OutlinedButton(onClick = { openLink("https://bedsideenglish.github.io/") }) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("prefs.landing_page"))
                    }
                    OutlinedButton(onClick = { openLink("https://github.com/${com.example.medvoicetrainer.analysis.ErrorDialog.GITHUB_REPO}") }) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("GitHub")
                    }
                    OutlinedButton(onClick = { openLink("https://bedsideenglish.github.io/privacy.html") }) {
                        Icon(Icons.Default.PrivacyTip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("prefs.privacy_policy"))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Determinate progress for a backup whenever the total is known, indeterminate otherwise.
 *
 * A learner who is shown only a spinning bar for several minutes has no way to tell a slow export
 * from a hung one — which is exactly how the first version of this feature read.
 */
@Composable
private fun BackupProgressBlock(
    state: com.example.medvoicetrainer.ui.BackupUiState,
    t: (String) -> String,
) {
    val progress = state.progress
    val stageKey = when (progress?.stage) {
        com.example.medvoicetrainer.export.UserBackupStage.WRITING_DATA -> "prefs.backup_stage_writing_data"
        com.example.medvoicetrainer.export.UserBackupStage.WRITING_AUDIO -> "prefs.backup_stage_writing_audio"
        com.example.medvoicetrainer.export.UserBackupStage.READING_DATA -> "prefs.backup_stage_reading_data"
        com.example.medvoicetrainer.export.UserBackupStage.READING_AUDIO -> "prefs.backup_stage_reading_audio"
        com.example.medvoicetrainer.export.UserBackupStage.APPLYING -> "prefs.backup_stage_applying"
        else -> "prefs.backup_stage_preparing"
    }
    val fraction = progress?.fraction

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val detail = when {
            progress == null -> t(stageKey)
            progress.totalFiles > 0 -> t(stageKey)
                .replace("{done}", progress.completedFiles.toString())
                .replace("{total}", progress.totalFiles.toString())
            progress.completedFiles > 0 -> t(stageKey)
                .replace("{done}", progress.completedFiles.toString())
                .replace("{total}", "…")
            else -> t(stageKey).replace("{done}", "0").replace("{total}", "…")
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

/** LocalContext is not always the Activity itself; walk the wrapper chain before giving up. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
