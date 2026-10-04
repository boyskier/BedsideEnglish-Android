package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learner-visible source of truth for team communication tasks.
 *
 * Facts used by the simulated colleague must be authored here (or in the chart rendered below),
 * rather than being hidden only in `persona_override`. This is deliberately available before and
 * during a session: a handoff assesses structured communication, not memory or guesswork.
 */
private data class TeamBrief(
    val caseType: String,
    val title: String,
    val task: String,
    val urgency: String,
    val patient: String,
    val location: String,
    val situation: String,
    val background: List<String>,
    val currentStatus: List<String>,
    val keyData: List<String>,
    val treatments: List<String>,
    val request: String,
    val mustInclude: List<String>,
    /** True for a Nursing-track case, whose brief is a nursing card rather than a team handoff. */
    val nursing: Boolean = false,
    val stationMinutes: Int? = null,
)

private fun JSONObject.stringList(key: String): List<String> {
    val values: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until values.length()).mapNotNull { values.optString(it).trim().takeIf(String::isNotEmpty) }
}

private fun teamBriefFrom(caseJson: String): TeamBrief? = runCatching {
    val root = JSONObject(caseJson)
    val brief = root.optJSONObject("team_brief") ?: return null
    TeamBrief(
        caseType = brief.optString("case_type", "patient_case"),
        title = brief.optString("title").trim(),
        task = brief.optString("task", root.optString("communication_task")).trim(),
        urgency = brief.optString("urgency", root.optString("urgency")).trim(),
        patient = brief.optString("patient").trim(),
        location = brief.optString("location").trim(),
        situation = brief.optString("situation").trim(),
        background = brief.stringList("background"),
        currentStatus = brief.stringList("current_status"),
        keyData = brief.stringList("key_data"),
        treatments = brief.stringList("treatments"),
        request = brief.optString("your_request").trim(),
        mustInclude = brief.stringList("must_include"),
        nursing = root.optString("session_mode") == "nursing",
        stationMinutes = root.optInt("station_minutes", 0).takeIf { it > 0 },
    )
}.getOrNull()

@Composable
private fun TeamBriefSection(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            values.forEach { value ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TeamBriefCards(brief: TeamBrief) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (brief.title.isNotEmpty()) Text(brief.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                listOf(brief.task, brief.urgency.uppercase()).filter(String::isNotEmpty).joinToString(" · ").takeIf(String::isNotEmpty)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (brief.patient.isNotEmpty()) Text(brief.patient, fontWeight = FontWeight.SemiBold)
                if (brief.location.isNotEmpty()) Text(brief.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (brief.situation.isNotEmpty()) TeamBriefSection("Situation", listOf(brief.situation))
        TeamBriefSection("Background", brief.background)
        TeamBriefSection("Current status", brief.currentStatus)
        TeamBriefSection("Key data", brief.keyData)
        TeamBriefSection("Already done / pending", brief.treatments)
        if (brief.request.isNotEmpty()) TeamBriefSection("Your task", listOf(brief.request))
        TeamBriefSection(
            when (brief.caseType) {
                // OET cards list tasks, and every one is scored — say so in the exam's own terms.
                "oet_roleplay" -> "Tasks on your card (each one is scored)"
                "interview" -> "What to show the interviewer"
                else -> if (brief.nursing) "Tasks (each one is scored)" else "Include in your message"
            },
            brief.mustInclude,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamCommunicationBriefSheet(
    caseJson: String,
    onDismiss: () -> Unit,
    onStart: (() -> Unit)? = null,
    /**
     * False when the current voice backend cannot actually run this scenario — the keyless "demo"
     * backend only knows its three scripted patients, so starting anything else from it produces a
     * session with no counterpart. Mirrors FollowUpBriefingScreen's `canStart`.
     */
    canStart: Boolean = true,
    /** Shown in place of the Start button's normal affordance when [canStart] is false. */
    blockedReason: String? = null,
) {
    val t = LocalTranslate.current
    val brief = remember(caseJson) { teamBriefFrom(caseJson) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, contentDescription = null)
                Column {
                    Text(
                        t(
                            when {
                                brief?.caseType == "skill_drill" -> "Communication skill drill"
                                brief?.caseType == "oet_roleplay" -> "OET role-play card"
                                brief?.caseType == "interview" -> "Interview brief"
                                brief?.nursing == true -> "Nursing scenario brief"
                                else -> "Team handoff brief"
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (brief?.caseType) {
                            // OET gives 3 minutes to read the card; the role-play itself runs ~5.
                            "oet_roleplay" -> t("Exam format: read the card for up to 3 minutes, then a {n}-minute role-play. The card stays open during the conversation.")
                                .replace("{n}", (brief?.stationMinutes ?: 5).toString())
                            else -> t("Keep this brief open during the conversation.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (brief == null) {
                Text("This scenario has no learner briefing. Please report this case.")
            } else {
                TeamBriefCards(brief)
            }
            if (onStart != null) {
                if (!canStart && blockedReason != null) {
                    Text(
                        t(blockedReason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { onStart() },
                    enabled = brief != null && canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("Start")) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(t("Back")) }
            }
        }
    }
}
