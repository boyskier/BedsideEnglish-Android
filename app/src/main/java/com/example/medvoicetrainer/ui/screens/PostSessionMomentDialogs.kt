package com.example.medvoicetrainer.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.medvoicetrainer.analysis.Milestone
import com.example.medvoicetrainer.export.ShareCardImage
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.PostSessionMoment

/**
 * Compose UI for the post-session retention moments (Category C in PORTING_STATUS.md), ported
 * from app/ui/milestone_dialog.py, checkin_prompt.py, and tomorrow_toast.py. The decision logic
 * lives in Milestones.kt/CheckinPrompt.kt/TomorrowToast.kt and MainViewModel's
 * evaluatePostSessionMoments(); this file is purely presentation.
 */
@Composable
fun PostSessionMomentHost(viewModel: MainViewModel) {
    val moment by viewModel.postSessionMoment.collectAsStateWithLifecycle()
    when (val m = moment) {
        is PostSessionMoment.MilestoneMoment -> MilestoneDialog(m.milestones, m.stats.sessions, m.stats.minutes, m.stats.streak, m.stats.mastered) {
            viewModel.dismissPostSessionMoment()
        }
        is PostSessionMoment.Toast -> TomorrowToastBar(m.content) { viewModel.dismissPostSessionMoment() }
        null -> {}
    }
}

/**
 * Save [bitmap] to the app cache and hand it to another app via a FileProvider content:// Uri.
 * The authority must match the `<provider>` declared in AndroidManifest.xml
 * (`${applicationId}.fileprovider`); `context.packageName` resolves to the same applicationId
 * at runtime, so it's used here rather than duplicating the literal string.
 */
internal fun shareBitmapCard(context: android.content.Context, bitmap: android.graphics.Bitmap, fileName: String, chooserTitle: String) {
    val file = ShareCardImage.saveBitmapForSharing(context, bitmap, fileName)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}

/** Ported from app/ui/milestone_dialog.py. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilestoneDialog(milestones: List<Milestone>, sessions: Int, minutes: Int, streak: Int, mastered: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val t = LocalTranslate.current
    val headline = milestones.maxByOrNull { it.threshold }?.label ?: t("milestone.title")
    var cardBitmap by remember(headline) {
        mutableStateOf(ShareCardImage.renderMilestoneCard(headline, sessions, minutes, streak, mastered))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text(t("common.nice")) } },
        dismissButton = {
            TextButton(onClick = { shareBitmapCard(context, cardBitmap, "milestone_card.png", t("milestone.share_chooser_title")) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(t("common.share"))
            }
        },
        title = { Text("🎉 " + t("milestone.title"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (milestones.size > 1) {
                    Text(t("milestone.more_this_session").replace("{count}", (milestones.size - 1).toString()), style = MaterialTheme.typography.bodySmall)
                }
                Image(
                    bitmap = cardBitmap.asImageBitmap(),
                    contentDescription = t("milestone.card_alt"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
    )
}

/** Ported from app/ui/tomorrow_toast.py — non-modal, self-dismissing "see you tomorrow" banner. */
@Composable
fun TomorrowToastBar(content: com.example.medvoicetrainer.analysis.ToastContent, onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    var visible by remember { mutableStateOf(true) }
    // §14 "Motion": the slide-in/out becomes an instant cut under reduced motion.
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()

    LaunchedEffect(content) {
        kotlinx.coroutines.delay(com.example.medvoicetrainer.analysis.TomorrowToast.AUTO_CLOSE_MS)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = if (reducedMotion) snap() else androidx.compose.animation.core.spring()) { it },
        exit = slideOutVertically(animationSpec = if (reducedMotion) snap() else androidx.compose.animation.core.spring()) { it }
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(content.headline, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        content.subline?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(content.footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { visible = false; onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = t("checkin.dismiss"))
                    }
                }
            }
        }
    }
}
