package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.ImportSpeakerOrder
import com.example.medvoicetrainer.analysis.TranscriptImportParser
import com.example.medvoicetrainer.ui.ImportDomain
import com.example.medvoicetrainer.ui.MainViewModel

/**
 * "Paste a ChatGPT/Gemini live-mode conversation and score it here" — the product answer to
 * "why not just use my subscription's voice mode instead of paying for API calls in this app".
 * See docs/DIFFERENTIATION_ANSWERS.md Q1: the conversation itself can be free; what this screen
 * sells is running it through the same analysis/checklist/SRS pipeline a live session gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTranscriptScreen(
    viewModel: MainViewModel,
    prefillText: String? = null,
    onConsumedPrefill: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val uiState by viewModel.importUiState.collectAsStateWithLifecycle()
    var domain by remember { mutableStateOf(ImportDomain.CLINICAL) }
    var transcriptText by remember { mutableStateOf("") }
    var showWhyThisHelps by remember { mutableStateOf(false) }
    var speakerOrder by remember { mutableStateOf(ImportSpeakerOrder.LEARNER_FIRST) }

    // Speaker-less text is parsed by alternating roles, so who spoke first decides which half of
    // the transcript gets scored as the learner. Getting it backwards would grade the assistant's
    // English as the user's, so the toggle is surfaced — but only when the paste actually has no
    // labels to go on. A labelled paste needs no guess and gets no extra control.
    val needsSpeakerOrder = remember(transcriptText) {
        transcriptText.isNotBlank() && !TranscriptImportParser.hasSpeakerLabels(transcriptText)
    }
    // Only the first couple of turns are ever shown, and only in the guessing case — no reason to
    // re-parse the whole paste on every keystroke when the text carries its own labels.
    val previewTurns = remember(transcriptText, speakerOrder, needsSpeakerOrder) {
        if (needsSpeakerOrder) TranscriptImportParser.parse(transcriptText, speakerOrder).take(2) else emptyList()
    }

    // A share-sheet hand-off (Android ACTION_SEND) lands here once, then is cleared so rotating
    // the screen or navigating away and back doesn't silently re-paste stale shared text.
    // The banner keys off its own flag: prefillText is cleared on the very next recomposition,
    // so keying the banner on it only flashed it.
    var receivedFromShare by remember { mutableStateOf(false) }
    LaunchedEffect(prefillText) {
        if (!prefillText.isNullOrBlank()) {
            transcriptText = prefillText
            receivedFromShare = true
            onConsumedPrefill()
        }
    }

    if (uiState.isAnalyzing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(t("import.analyzing"))
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(t("import.title")) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = t("import.subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (receivedFromShare) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = t("import.shared_banner"),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // "Why import instead of just chatting there?" — collapsed by default, answers the
            // objection right where the solution to it lives.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWhyThisHelps = !showWhyThisHelps }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = t("import.why_this_helps_title"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showWhyThisHelps) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                    if (showWhyThisHelps) {
                        Text(
                            text = t("import.why_this_helps_body"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Text(
                text = t("import.domain_label"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ImportDomain.CLINICAL to "import.domain_clinical",
                    ImportDomain.SURVIVAL to "import.domain_survival",
                    ImportDomain.INTERVIEW to "import.domain_interview"
                ).forEach { (option, labelKey) ->
                    Surface(
                        color = if (domain == option) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { domain = option }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = domain == option, onClick = { domain = option })
                            Spacer(Modifier.width(4.dp))
                            Text(t(labelKey))
                        }
                    }
                }
            }

            Text(
                text = t("import.paste_label"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = t("import.paste_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = transcriptText,
                onValueChange = {
                    transcriptText = it
                    if (uiState.error != null) viewModel.clearImportError()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                placeholder = { Text(t("import.paste_placeholder")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                isError = uiState.error != null
            )

            if (needsSpeakerOrder) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = t("import.speaker_order_title"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = t("import.speaker_order_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ImportSpeakerOrder.LEARNER_FIRST to "import.speaker_order_me",
                                ImportSpeakerOrder.OTHER_FIRST to "import.speaker_order_them"
                            ).forEach { (option, labelKey) ->
                                FilterChip(
                                    selected = speakerOrder == option,
                                    onClick = { speakerOrder = option },
                                    label = { Text(t(labelKey)) }
                                )
                            }
                        }
                        // A two-line preview is the cheapest way to make a wrong guess obvious
                        // before it is baked into a scored, persisted session.
                        previewTurns.forEach { (role, text) ->
                            Text(
                                text = "${if (role == "doctor") t("import.speaker_you") else t("import.speaker_them")}: " +
                                    text.take(60),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (role == "doctor") FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = { viewModel.importTranscript(domain, transcriptText, speakerOrder) },
                enabled = transcriptText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FactCheck, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(t("import.analyze_button"))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
