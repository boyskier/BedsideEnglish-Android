package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate

/**
 * One-time framing shown on the learner's first Patient Encounter. Potential users mistake
 * Encounter mode for a clinical/diagnosis trainer; this resets the expectation up front — the app
 * practices *speaking English with a patient*, and grades communication, not the medicine. It is a
 * plain dismiss-only dialog (no choice to make) so it never blocks a returning user: it is shown
 * exactly once and then persisted (see MainViewModel.markEnglishFirstIntroSeen).
 */
@Composable
fun EnglishFirstIntroDialog(onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text(t("coach.english_first.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t("coach.english_first.body1"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    t("coach.english_first.body2"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t("coach.english_first.body3"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(t("coach.english_first.cta"))
            }
        },
    )
}
