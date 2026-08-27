package com.example.medvoicetrainer.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import kotlin.math.roundToInt

/**
 * The flexible-update prompt: a quiet bottom banner, never a dialog.
 *
 * A learner mid-practice is the wrong person to interrupt with a modal, so the download runs
 * silently and only the "restart to finish" step asks for anything — and even that is dismissible
 * (see [InAppUpdateController.snoozeUpdate]). Callers are responsible for hiding this during a live
 * session; see MainActivity.
 */
@Composable
fun InAppUpdateBanner(modifier: Modifier = Modifier) {
    val controller = LocalInAppUpdate.current ?: return
    val state by controller.state.collectAsStateWithLifecycle()
    val t = LocalTranslate.current

    when (val current = state) {
        is InAppUpdateState.Idle -> Unit

        is InAppUpdateState.Downloading -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    t("update.downloading").replace(
                        "{percent}",
                        (current.fraction * 100f).roundToInt().toString(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Play reports 0 bytes until the transfer really begins; an indeterminate bar there
                // is honest about "we don't know yet" instead of showing a stuck 0%.
                if (current.fraction <= 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { current.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is InAppUpdateState.Installing -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Text(
                t("update.installing"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is InAppUpdateState.ReadyToInstall -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        t("update.ready_title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    t("update.ready_body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { controller.snoozeUpdate() }) {
                        Text(t("update.later"))
                    }
                    TextButton(onClick = { controller.completeUpdate() }) {
                        Text(t("update.restart"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
