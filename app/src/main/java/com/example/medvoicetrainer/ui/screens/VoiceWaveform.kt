package com.example.medvoicetrainer.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.sin

/**
 * Live microphone waveform for the encounter/survival mic bar. The bars react to [level] — the
 * 0f..1f loudness of the audio the app is *actually forwarding to the provider* (see
 * MainViewModel.micLevel / VoiceManager.forwardedMicLevel), not the raw mic — so the learner gets
 * an honest "the app is hearing me right now" signal. When nothing is getting through (the turn is
 * being dropped, the mic is muted, or the session is between turns) [level] is ~0 and the bars
 * settle to a flat centerline, which is itself the cue that this turn is not reaching the model.
 *
 * A shallow traveling shimmer keeps it feeling alive while [active]; under [reducedMotion] the bars
 * are sized straight from [level] with no time-based animation (docs/design/android-ui-spec.html
 * §14 "Motion").
 */
@Composable
fun VoiceWaveform(
    level: Float,
    active: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 15,
) {
    // Smooth the ~10 Hz level callbacks so the bars glide instead of stepping between updates.
    val smoothedLevel by animateFloatAsState(
        targetValue = if (active) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 130, easing = LinearEasing),
        label = "micLevelSmooth",
    )

    // Traveling phase for the shimmer; a constant (unused) 0 under reduced motion.
    val phase: Float = if (reducedMotion || !active) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "waveformPhase")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "waveformPhaseValue",
        )
        animated
    }

    Canvas(modifier = modifier) {
        val n = barCount.coerceAtLeast(2)
        val step = size.width / n
        val barWidth = (step * 0.5f).coerceAtLeast(1f)
        val centerY = size.height / 2f
        val minHalf = size.height * 0.055f   // idle centerline half-thickness
        val maxHalf = size.height * 0.5f - barWidth / 2f

        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)                       // 0..1 across the width
            // Bell envelope so the middle bars reach highest — the symmetric look of the reference.
            val envelope = sin(t * PI).toFloat().coerceIn(0f, 1f)
            // Per-bar shimmer in ~[0.45, 1.0]; flat 1.0 under reduced motion.
            val shimmer = if (reducedMotion || !active) {
                1f
            } else {
                0.45f + 0.55f * ((sin(phase + i * 0.8f).toFloat() + 1f) / 2f)
            }
            val half = minHalf + (maxHalf - minHalf) * smoothedLevel * envelope * shimmer
            val x = step * (i + 0.5f)
            val alpha = 0.30f + 0.70f * (smoothedLevel * envelope)
            drawLine(
                color = color.copy(alpha = alpha.coerceIn(0.18f, 1f)),
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
