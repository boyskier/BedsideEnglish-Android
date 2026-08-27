package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.reporting.ContentIssueCategory
import com.example.medvoicetrainer.reporting.ContentIssueReport
import com.example.medvoicetrainer.reporting.ContentIssueReportClient
import com.example.medvoicetrainer.ui.LocalTranslate
import java.util.UUID

@Composable
fun ContentIssueReportDialog(
    contentType: String,
    contentId: String,
    contentTitle: String,
    surface: String,
    onDismiss: () -> Unit,
) {
    val t = LocalTranslate.current
    val context = LocalContext.current
    val client = remember { ContentIssueReportClient() }
    val reportId = remember { UUID.randomUUID().toString() }
    var category by remember { mutableStateOf<ContentIssueCategory?>(null) }
    var note by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var submitFailed by remember { mutableStateOf(false) }

    if (submitted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t("content_report.success_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("content_report.success_body_queued"))
                    Text(t("ai_report.report_id_label"), fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(
                            reportId,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        t("ai_report.report_id_help"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(t("ai_report.close")) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("content_report.title")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(t("content_report.instructions"))
                Text(contentTitle, fontWeight = FontWeight.Bold)
                Text(
                    "$contentType · $contentId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    t("content_report.category_label"),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
                ContentIssueCategory.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                category = item
                                submitFailed = false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = category == item,
                            onClick = {
                                category = item
                                submitFailed = false
                            },
                        )
                        Text(t(item.labelKey))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it.take(ContentIssueReportClient.OPTIONAL_NOTE_MAX)
                        submitFailed = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("content_report.note_label")) },
                    supportingText = {
                        Text(
                            t("content_report.note_help")
                                .replace("{n}", note.length.toString())
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                )
                Text(
                    t("content_report.privacy_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (submitFailed) {
                    Text(
                        t("content_report.failure"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("ai_report.cancel")) }
        },
        confirmButton = {
            TextButton(
                enabled = category != null,
                onClick = {
                    val selectedCategory = category ?: return@TextButton
                    submitFailed = false
                    // Queued, not sent: this returns in microseconds and the upload happens in
                    // ReportUploadWorker, so there is no spinner and no wait for the learner.
                    val queued = client.submitInBackground(
                        context,
                        ContentIssueReport(
                            category = selectedCategory,
                            contentType = contentType,
                            contentId = contentId,
                            contentTitle = contentTitle,
                            surface = surface,
                            optionalNote = note,
                            reportId = reportId,
                        ),
                    )
                    if (queued.isSuccess) submitted = true else submitFailed = true
                },
            ) {
                Text(t("content_report.submit"))
            }
        },
    )
}
