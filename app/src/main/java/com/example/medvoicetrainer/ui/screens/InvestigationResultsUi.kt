package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.ClinicalResult
import com.example.medvoicetrainer.analysis.InvestigationEvent
import com.example.medvoicetrainer.analysis.InvestigationResults
import com.example.medvoicetrainer.ui.LocalTranslate

@Composable
fun PreEncounterResultsScreen(
    caseName: String,
    results: List<ClinicalResult>,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val t = LocalTranslate.current
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = t("Back"), tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        t("Results available before the encounter"),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                    Text(caseName, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                t("Review only the case-relevant results already available to the clinician. You can reopen them during the conversation."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ClinicalResultsTable(results)
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(t("Start encounter"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestigationResultsSheet(
    availableResults: List<ClinicalResult>,
    authoredEvents: List<InvestigationEvent>,
    pendingEvents: List<InvestigationEvent>,
    revealedEvents: List<InvestigationEvent>,
    onOrder: (String) -> Unit,
    onViewResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalTranslate.current
    val activeIds = (pendingEvents + revealedEvents).mapTo(mutableSetOf()) { it.id }
    val orderableEvents = authoredEvents.filter { it.id !in activeIds }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(t("Tests & results"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (availableResults.isNotEmpty()) {
                Text(t("Available before encounter"), style = MaterialTheme.typography.labelLarge)
                ClinicalResultsTable(availableResults)
            }
            if (orderableEvents.isNotEmpty()) {
                Text(t("Order during encounter"), style = MaterialTheme.typography.labelLarge)
                orderableEvents.forEach { event ->
                    InvestigationOrderRow(
                        event = event,
                        actionLabel = t("Order"),
                        onAction = { onOrder(event.id) },
                    )
                }
            }
            if (pendingEvents.isNotEmpty()) {
                Text(t("Results available"), style = MaterialTheme.typography.labelLarge)
                pendingEvents.forEach { event ->
                    InvestigationOrderRow(
                        event = event,
                        actionLabel = t("View result"),
                        onAction = { onViewResult(event.id) },
                    )
                }
            }
            if (revealedEvents.isNotEmpty()) {
                Text(t("Reviewed results"), style = MaterialTheme.typography.labelLarge)
            }
            revealedEvents.forEach { event ->
                Text(event.title, style = MaterialTheme.typography.labelLarge)
                ClinicalResultsTable(event.results)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InvestigationOrderRow(
    event: InvestigationEvent,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                event.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun InvestigationResultPrompt(
    event: InvestigationEvent,
    onView: () -> Unit,
) {
    val t = LocalTranslate.current
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t("Result available"),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    event.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onView) {
                Text(t("View result"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ClinicalResultsTable(results: List<ClinicalResult>) {
    val t = LocalTranslate.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Test"), modifier = Modifier.weight(1.25f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(t("Result"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(t("Reference"), modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            results.forEach { result ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 7.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1.25f)) {
                        Text(result.test, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        listOf(result.specimen, result.collectedAt).filter(String::isNotBlank).joinToString(" · ")
                            .takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            InvestigationResults.valueWithUnit(result),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = flagColor(result.flag),
                        )
                        result.flag.takeIf(String::isNotBlank)?.let {
                            Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = flagColor(it))
                        }
                    }
                    Text(
                        result.referenceRange.ifBlank { "—" },
                        modifier = Modifier.weight(0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.note.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun flagColor(flag: String): Color = when (flag.lowercase()) {
    "critical" -> MaterialTheme.colorScheme.error
    "high", "low", "abnormal", "positive" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}
