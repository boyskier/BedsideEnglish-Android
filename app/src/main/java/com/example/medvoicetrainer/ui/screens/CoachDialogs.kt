package com.example.medvoicetrainer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.medvoicetrainer.analysis.Coach
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.voice.AudioIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Compose UI for app/ui/coach.py's one-time primers and re-openable tour. Decision/content
 * logic lives in Coach.kt; this file is purely presentation, mirroring
 * PostSessionMomentDialogs.kt's split.
 */

/** A dismissible info banner — used for both the feedback explainer and the first-session tip
 * (Python has two differently-styled one-off widgets for these; Compose gets one reusable shape). */
@Composable
fun InfoBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTranslate.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = t("checkin.dismiss"), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Ported from app/ui/coach.py's how_it_works(): a calm overview of the practice loop and the
 * seven practice modes. Re-openable from the Wiki/Help screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksDialog(onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text(t("tour.close")) } },
        title = { Text(t("tour.title"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(t("tour.loop_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Coach.practiceLoopSteps().forEach { step ->
                    Text(t(step), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(t("tour.modes_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    t("tour.modes_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Coach.practiceModes().forEach { mode ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(mode.emoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(t(mode.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(t(mode.description), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    )
}

/**
 * Ported from app/ui/coach.py's audio_preflight()/_AudioPreflightDialog: a one-time primer
 * before the user's first real voice session — headphones reminder + a live mic-level test using
 * a temporary AudioIO() capture, entirely separate from the live session's VoiceManager (this
 * dialog runs and finishes before startSession is ever called). Not yet verified on-device (no
 * build/emulator access in this environment) — purely additive, doesn't touch the live audio
 * pipeline, but treat as unexercised code per MIGRATION_MASTER.md's honesty convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPreflightDialog(onProceed: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val t = LocalTranslate.current
    var hasRecordPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasRecordPermission = granted
    }

    var isMetering by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var micStatus by remember { mutableStateOf("") }
    val audioIO = remember { AudioIO() }

    fun stopMeter() {
        isMetering = false
        audioIO.stopRecording()
        micStatus = when {
            peak >= Coach.MIC_OK_PEAK -> t("preflight.mic_ok")
            peak > 0 -> t("preflight.mic_quiet")
            else -> micStatus
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (isMetering) audioIO.stopRecording() }
    }

    LaunchedEffect(isMetering) {
        if (!isMetering) return@LaunchedEffect
        val elapsedMs = Coach.MIC_METER_SECONDS * 1000L
        var waited = 0L
        while (isActive && waited < elapsedMs && isMetering) {
            delay(100)
            waited += 100
        }
        if (isMetering) stopMeter()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(onClick = { if (isMetering) stopMeter(); onProceed() }) {
                Text(t("preflight.start"))
            }
        },
        dismissButton = { TextButton(onClick = { if (isMetering) stopMeter(); onCancel() }) { Text(t("preflight.cancel")) } },
        title = { Text(t("preflight.title"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(t(Coach.HEADPHONES_TITLE_KEY), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(t(Coach.HEADPHONES_BODY_KEY), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))

                Text(t(Coach.MIC_CHECK_TITLE_KEY), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(t(Coach.MIC_CHECK_BODY_KEY), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (!hasRecordPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@OutlinedButton
                            }
                            if (isMetering) {
                                stopMeter()
                            } else {
                                peak = 0f
                                level = 0f
                                micStatus = t("preflight.listening")
                                isMetering = true
                                val recording = audioIO.startRecording(
                                    onChunkReceived = { chunk ->
                                        val rms = pcm16Rms(chunk)
                                        level = rms
                                        if (rms > peak) peak = rms
                                    },
                                    onError = { message ->
                                        isMetering = false
                                        micStatus = t("preflight.mic_error").replace("{err}", message)
                                    },
                                )
                                recording.onFailure { error ->
                                    isMetering = false
                                    micStatus = t("preflight.mic_error").replace("{err}", error.message ?: error.javaClass.simpleName)
                                }
                            }
                        }
                    ) {
                        Text(if (isMetering) t("preflight.stop_btn") else t("preflight.test_btn"))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    LinearProgressIndicator(
                        progress = { (level / 3000f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (micStatus.isNotEmpty()) {
                    Text(micStatus, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                }

                Text(t(Coach.FLOW_TITLE_KEY), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(t(Coach.FLOW_BODY_KEY), style = MaterialTheme.typography.bodySmall)
            }
        }
    )
}

/** RMS of a little-endian 16-bit PCM chunk, on the same ~0-3000+ scale mic_check.py's
 * OK_PEAK=200 threshold assumes for a normally-spoken utterance close to the mic. */
private fun pcm16Rms(chunk: ByteArray): Float {
    if (chunk.size < 2) return 0f
    var sumSquares = 0.0
    var count = 0
    var i = 0
    while (i + 1 < chunk.size) {
        val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toInt()
        sumSquares += (sample * sample).toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0f
    return kotlin.math.sqrt(sumSquares / count).toFloat()
}
