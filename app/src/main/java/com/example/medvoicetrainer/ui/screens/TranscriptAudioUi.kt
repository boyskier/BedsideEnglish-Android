package com.example.medvoicetrainer.ui.screens

import android.media.MediaPlayer
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.voice.LearnerAudioClip
import com.example.medvoicetrainer.voice.LearnerAudioStore
import org.json.JSONArray

internal data class StoredTranscriptTurn(
    val role: String,
    val text: String,
    val learnerAudioPath: String? = null,
    val learnerAudioDurationMs: Long = 0,
)

internal fun transcriptTurns(
    transcript: List<Pair<String, String>>,
    clips: Map<Int, LearnerAudioClip>,
): List<StoredTranscriptTurn> = transcript.mapIndexed { index, (role, text) ->
    val clip = clips[index]
    StoredTranscriptTurn(role, text, clip?.relativePath, clip?.durationMs ?: 0)
}

internal fun parseStoredTranscript(rawJson: String): List<StoredTranscriptTurn> = try {
    val turns = JSONArray(rawJson.ifBlank { "[]" })
    buildList {
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val role = turn.optString("role")
            // Sessions recorded before SafetyDisclaimerFilter existed still have the provider's
            // "not medical advice" boilerplate baked into their stored AI turns, so History
            // cleans it on the way out too rather than only at capture time. Learner turns are
            // never touched, and neither are the beta's stage directions — that text is the app's
            // own, so running a provider-output filter over it could only ever damage it. A turn
            // that was nothing but boilerplate is dropped.
            val text = if (
                role == "doctor" ||
                role == com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE
            ) {
                turn.optString("text")
            } else {
                com.example.medvoicetrainer.analysis.SafetyDisclaimerFilter.strip(turn.optString("text"))
            }
            if (text.isBlank()) continue
            add(
                StoredTranscriptTurn(
                    role = role,
                    text = text,
                    learnerAudioPath = turn.optString("learner_audio_path").takeIf { it.isNotBlank() },
                    learnerAudioDurationMs = turn.optLong("learner_audio_duration_ms", 0),
                )
            )
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
    emptyList()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayableTranscript(turns: List<StoredTranscriptTurn>) {
    val context = LocalContext.current
    val t = LocalTranslate.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val audioStore = remember(context.applicationContext) { LearnerAudioStore(context.applicationContext) }
    val player = remember { MediaPlayer() }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var playbackErrorIndex by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    turns.forEachIndexed { index, turn ->
        // Survival "Advanced Beta" stage direction: nobody said this line, so it must not be
        // rendered with a speaker label — labelled "AI" it reads as something the learner's partner
        // actually spoke, which is exactly what the analysis prompt is told never to do with it.
        if (turn.role == com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.labelMedium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            return@forEachIndexed
        }
        val audioFile = remember(turn.learnerAudioPath) {
            turn.learnerAudioPath?.let(audioStore::resolve)
        }
        val hasAudio = turn.role == "doctor" && audioFile != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tap = replay my own voice (learner turns only); long-press = copy the line's
                // text, which works on every turn including the AI's — the fastest way to get a
                // phrase out of a session and into notes/a dictionary without an export.
                .combinedClickable(
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(turn.text))
                        // API 33+ shows its own system copy confirmation; a Toast there would
                        // double up.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, t("feedback.transcript_copied"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClick = onClick@{
                        if (!hasAudio) return@onClick
                        if (playingIndex == index) {
                            runCatching { player.reset() }
                            playingIndex = null
                        } else {
                            playbackErrorIndex = null
                            runCatching {
                                player.reset()
                                player.setDataSource(audioFile!!.absolutePath)
                                player.setOnPreparedListener { prepared -> prepared.start() }
                                player.setOnCompletionListener { playingIndex = null }
                                player.setOnErrorListener { _, _, _ ->
                                    playingIndex = null
                                    playbackErrorIndex = index
                                    true
                                }
                                playingIndex = index
                                player.prepareAsync()
                            }.onFailure {
                                playingIndex = null
                                playbackErrorIndex = index
                            }
                        }
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (hasAudio) {
                Icon(
                    imageVector = if (playingIndex == index) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (playingIndex == index) t("feedback.audio_stop") else t("feedback.audio_play"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (turn.role == "doctor") t("feedback.transcript_you") else t("feedback.transcript_ai"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (turn.role == "doctor") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    )
                    // Long-press-to-copy is invisible unless it's named, so the same caption line
                    // carries both gestures: with a clip it reads "0:07 · tap to play · hold to
                    // copy", without one just "hold to copy".
                    val hint = buildList {
                        if (hasAudio && turn.learnerAudioDurationMs > 0) {
                            add(formatClipDuration(turn.learnerAudioDurationMs))
                            add(t("feedback.tap_to_play"))
                        }
                        add(t("feedback.hold_to_copy"))
                    }.joinToString(" · ")
                    Text(
                        text = "  $hint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                if (turn.role == "doctor" && !hasAudio) {
                    Text(
                        text = t("feedback.audio_unavailable"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (playbackErrorIndex == index) {
                    Text(
                        text = t("feedback.audio_playback_failed"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

private fun formatClipDuration(durationMs: Long): String {
    val seconds = (durationMs / 1_000L).coerceAtLeast(1)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
