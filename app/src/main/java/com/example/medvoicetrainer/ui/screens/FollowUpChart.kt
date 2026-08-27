package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import org.json.JSONObject

/**
 * The clinician-visible half of a Patient Follow-up case.
 *
 * A follow-up case deliberately splits its ground truth in two: `clinician_brief` is what the doctor
 * has already read in the chart, and `patient_private` is what only the simulated patient knows
 * until asked. Only the brief is ever rendered here — see PromptBuilder.buildFollowUpPatientPrompt
 * for the other side of that contract.
 *
 * Lives in its own file rather than inside the Practice tab because the chart is needed twice: once
 * before the visit (the pre-visit review screen) and again during it. An established-patient visit
 * assumes the doctor knows the chart, so making that knowledge vanish the moment the conversation
 * starts turned the mode into a memory test it was never meant to be.
 */

internal fun JSONObject.followUpList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).trim().takeIf(String::isNotEmpty)
    }
}

internal fun JSONObject.followUpEnglishHints(): List<Pair<String, String>> {
    val array = optJSONArray("follow_up_hints") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { hint ->
            val question = hint.optString("question").trim()
            if (question.isEmpty()) null else hint.optString("category").trim() to question
        }
    }
}

@Composable
private fun ChartSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTranslate.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(t(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ChartListSection(title: String, values: List<String>) {
    if (values.isEmpty()) return
    ChartSectionCard(title) {
        values.forEach { value ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", color = MaterialTheme.colorScheme.primary)
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The chart itself, as a plain (non-scrolling) column of cards so the caller decides how it scrolls
 * — a `LazyColumn` item on the briefing screen, a scrollable sheet mid-session.
 */
@Composable
internal fun FollowUpChartCards(
    root: JSONObject,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslate.current
    val brief = remember(root) { root.optJSONObject("clinician_brief") ?: JSONObject() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = root.optString("patient_name").trim()
                if (name.isNotEmpty()) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                // Built from whatever the case actually declares: a missing age used to render as a
                // literal "0" beside two empty separators.
                val demographics = listOf(
                    root.optInt("age", 0).takeIf { it > 0 }?.toString().orEmpty(),
                    root.optString("gender").trim(),
                    root.optString("follow_up_reason").trim(),
                ).filter { it.isNotEmpty() }
                if (demographics.isNotEmpty()) {
                    Text(demographics.joinToString(" · "))
                }
                val followUpType = root.optString("follow_up_type").trim()
                if (followUpType.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(followUpType.replace('_', ' ').uppercase()) },
                    )
                }
            }
        }

        val priorNote = brief.optString("prior_note").trim()
        if (priorNote.isNotEmpty()) {
            ChartSectionCard(t("Previous visit note")) {
                Text(priorNote, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ChartListSection(t("Active problems"), brief.followUpList("active_problems"))
        ChartListSection(t("Current medications"), brief.followUpList("current_medications"))
        ChartListSection(t("Previous plan"), brief.followUpList("previous_plan"))
        ChartListSection(t("Results and observations"), brief.followUpList("results_and_observations"))
        ChartListSection(t("Your tasks today"), brief.followUpList("today_tasks"))
    }
}

@Composable
internal fun FollowUpEnglishPlanCard(root: JSONObject) {
    val t = LocalTranslate.current
    val hints = remember(root) { root.followUpEnglishHints() }
    if (hints.isEmpty()) return

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                t("Conversation plan — example questions"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                t("Use these as a route through the visit. You can say them in your own words."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hints.forEachIndexed { index, (category, question) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${index + 1}. ${category.ifBlank { t("Question") }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(question, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Mid-session chart access. Reads the live session's own `caseJson`, so it shows the same brief the
 * learner reviewed before entering the room no matter which entry point started the session —
 * including any that skips the pre-visit review screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpChartSheet(caseJson: String, onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    // A malformed or absent case must not take the session down with it — the sheet simply has
    // nothing to show, and the conversation continues.
    val root = remember(caseJson) { runCatching { JSONObject(caseJson) }.getOrDefault(JSONObject()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Text(
                    t("Chart review"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            FollowUpChartCards(root)
            if (root.optString("practice_focus") == "english_speaking") {
                FollowUpEnglishPlanCard(root)
            }
        }
    }
}
