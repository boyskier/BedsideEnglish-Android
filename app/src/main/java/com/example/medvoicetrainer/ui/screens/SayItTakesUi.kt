package com.example.medvoicetrainer.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.IntelligibilityOutcome
import com.example.medvoicetrainer.analysis.SayItTake
import com.example.medvoicetrainer.analysis.SayItTakeLog
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.components.InlineDisclosure
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One m4a player shared by every phrase card on a screen. A player per card would mean a dozen
 * live [MediaPlayer]s on a list that mostly sits idle, and two takes could talk over each other;
 * one player also makes "which take is playing" a single, unambiguous piece of screen state.
 */
internal class SavedTakePlayer {
    private val player = MediaPlayer()

    var playingPath by mutableStateOf<String?>(null)
        private set
    var failedPath by mutableStateOf<String?>(null)
        private set

    fun toggle(take: SayItTake, file: File) {
        if (playingPath == take.path) {
            stop()
            return
        }
        // The model voice and the learner's own take must never play at once — the whole point of
        // a replay is hearing yourself.
        ModelSpeechControl.stopSpeaking()
        failedPath = null
        runCatching {
            player.reset()
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared -> prepared.start() }
            player.setOnCompletionListener { playingPath = null }
            player.setOnErrorListener { _, _, _ ->
                playingPath = null
                failedPath = take.path
                true
            }
            playingPath = take.path
            player.prepareAsync()
        }.onFailure {
            playingPath = null
            failedPath = take.path
        }
    }

    fun stop() {
        runCatching { player.reset() }
        playingPath = null
    }

    fun release() {
        runCatching { player.release() }
        playingPath = null
    }
}

@Composable
internal fun rememberSavedTakePlayer(): SavedTakePlayer {
    val handle = remember { SavedTakePlayer() }
    DisposableEffect(handle) { onDispose { handle.release() } }
    return handle
}

/**
 * The learner's kept recordings for one phrase, behind the same one-tap disclosure the rest of the
 * app uses for detail: a phrase drilled all week must not push the practice controls off screen.
 *
 * Takes whose audio no longer exists (a backup restored without voice clips, storage cleared) are
 * simply not offered — an index entry is not a promise that the file survived.
 */
@Composable
internal fun SavedTakesSection(
    takes: List<SayItTake>,
    resolve: (String) -> File?,
    player: SavedTakePlayer,
    onDelete: (SayItTake) -> Unit,
) {
    val t = LocalTranslate.current
    val playable = remember(takes) { takes.mapNotNull { take -> resolve(take.path)?.let { take to it } } }
    if (playable.isEmpty()) return

    InlineDisclosure(title = t("sayit.takes_show"), dense = true) {
        playable.forEach { (take, file) ->
            val isPlaying = player.playingPath == take.path
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { player.toggle(take, file) }) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = t(if (isPlaying) "feedback.audio_stop" else "feedback.audio_play"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(formatTakeTimestamp(take.recordedAt))
                            if (take.durationMs > 0) append(" · ${formatTakeDuration(take.durationMs)}")
                            takeOutcomeMark(take.outcome)?.let { append("  $it") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (player.failedPath == take.path) {
                        Text(
                            t("feedback.audio_playback_failed"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = {
                    if (isPlaying) player.stop()
                    onDelete(take)
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = t("sayit.take_delete"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Text(
            t("sayit.takes_note").replace("{n}", SayItTakeLog.MAX_PER_PHRASE.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Blank until an AI check has graded the take — never a bare glyph with no state behind it. */
private fun takeOutcomeMark(outcome: String): String? = when (outcome) {
    IntelligibilityOutcome.COMFORTABLE.name -> "✓"
    IntelligibilityOutcome.EFFORTFUL.name, IntelligibilityOutcome.CRITICAL_MISMATCH.name -> "↻"
    else -> null
}

private val takeTimestampFormat = DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun formatTakeTimestamp(epochMillis: Long): String = if (epochMillis <= 0) {
    "—"
} else {
    runCatching {
        takeTimestampFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("—")
}

private fun formatTakeDuration(durationMs: Long): String {
    val seconds = (durationMs / 1_000L).coerceAtLeast(1)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
