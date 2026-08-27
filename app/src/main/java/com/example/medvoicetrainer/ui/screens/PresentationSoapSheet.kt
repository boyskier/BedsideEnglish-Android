package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.PresentationBuilder
import com.example.medvoicetrainer.ui.LocalTranslate
import org.json.JSONObject

data class PresentationSoapNotes(
    val aiGenerated: String,
    val studentWritten: String?,
) {
    companion object {
        fun fromCaseJson(caseJson: String): PresentationSoapNotes? = runCatching {
            val root = JSONObject(caseJson)
            val ai = root.optString(PresentationBuilder.AI_SOAP_FIELD).trim()
            val student = root.optString(PresentationBuilder.STUDENT_SOAP_FIELD).trim()
                .takeIf { it.isNotBlank() }
            if (ai.isBlank() && student == null) null else PresentationSoapNotes(ai, student)
        }.getOrNull()
    }
}

private data class SoapSection(val shortLabel: String, val title: String, val body: String)

private fun soapSections(note: String): List<SoapSection> {
    val labels = listOf(
        Triple("S", "Subjective", "subjective"),
        Triple("O", "Objective", "objective"),
        Triple("A", "Assessment", "assessment"),
        Triple("P", "Plan", "plan"),
    )

    // Learner-authored notes are stored as a four-field JSON object.
    runCatching { JSONObject(note) }.getOrNull()?.let { root ->
        val sections = labels.mapNotNull { (short, title, key) ->
            root.optString(key).trim().takeIf { it.isNotBlank() }
                ?.let { SoapSection(short, title, it) }
        }
        if (sections.isNotEmpty()) return sections
    }

    // AI notes use the familiar S:/O:/A:/P: text form. Keep any unusual response readable as one
    // block instead of trying to be clever and accidentally dropping clinical content.
    val marker = Regex("(?im)^(S|O|A|P|Subjective|Objective|Assessment|Plan)\\s*:\\s*")
    val matches = marker.findAll(note).toList()
    if (matches.isNotEmpty()) {
        return matches.mapIndexedNotNull { index, match ->
            val rawLabel = match.groupValues[1]
            val short = rawLabel.first().uppercase()
            val title = labels.first { it.first == short }.second
            val end = matches.getOrNull(index + 1)?.range?.first ?: note.length
            note.substring(match.range.last + 1, end).trim().takeIf { it.isNotBlank() }
                ?.let { SoapSection(short, title, it) }
        }
    }

    return note.trim().takeIf { it.isNotBlank() }
        ?.let { listOf(SoapSection("", "SOAP note", it)) }
        .orEmpty()
}

/**
 * A read-only, swipe-to-close reference over the live presentation. Opening it never changes the
 * microphone state, so the learner can glance at a section while continuing to speak.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationSoapSheet(notes: PresentationSoapNotes, onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    var selected by remember(notes) { mutableIntStateOf(0) }
    val choices = buildList {
        if (notes.aiGenerated.isNotBlank()) add(t("AI SOAP") to notes.aiGenerated)
        notes.studentWritten?.let { add(t("My SOAP") to it) }
    }
    if (selected !in choices.indices) selected = 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, contentDescription = null)
                Column {
                    Text(t("SOAP reference"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        t("Use it as a prompt, not a script. Your microphone stays live."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (choices.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { index, (label, _) ->
                        FilterChip(
                            selected = selected == index,
                            onClick = { selected = index },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val sections = soapSections(choices[selected].second)
                items(sections.size) { index ->
                    val section = sections[index]
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                if (section.shortLabel.isBlank()) section.title else "${section.shortLabel}  ${section.title}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(section.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
