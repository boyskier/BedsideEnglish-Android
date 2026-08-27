package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate

/**
 * Anchorable destinations for the first-run coach-mark tour. Each maps to a real on-screen control
 * (a bottom-nav item, the coach FAB, or a top-bar action) whose measured bounds the overlay
 * spotlights. Measured lazily via [Modifier.onGloballyPositioned] at the call sites in
 * MainActivity; a target with no recorded bounds simply falls back to a centered, un-spotlit card.
 */
enum class TutorialTarget { HOME, PRACTICE, PRON_LAB, SRS, HISTORY, COACH_FAB, HELP }

/** One step of the tour: an optional spotlight [target] plus i18n keys for its caption. */
private data class TutorialStep(val target: TutorialTarget?, val titleKey: String, val bodyKey: String)

private fun tutorialSteps(coachFabPresent: Boolean): List<TutorialStep> = buildList {
    add(TutorialStep(null, "tour.welcome_title", "tour.welcome_body"))
    add(TutorialStep(TutorialTarget.HOME, "tour.home_title", "tour.home_body"))
    add(TutorialStep(TutorialTarget.PRACTICE, "tour.practice_title", "tour.practice_body"))
    add(TutorialStep(TutorialTarget.PRON_LAB, "tour.pron_title", "tour.pron_body"))
    add(TutorialStep(TutorialTarget.SRS, "tour.srs_title", "tour.srs_body"))
    add(TutorialStep(TutorialTarget.HISTORY, "tour.history_title", "tour.history_body"))
    // The coach FAB only exists when there's a Gemini key to power it (see MainAppScaffold's FAB
    // gate); a keyless-demo learner never sees it, so don't point at an absent control.
    if (coachFabPresent) add(TutorialStep(TutorialTarget.COACH_FAB, "tour.coach_title", "tour.coach_body"))
    add(TutorialStep(TutorialTarget.HELP, "tour.help_title", "tour.help_body"))
}

/**
 * First-run spotlight tour overlaying the main scaffold. Draws a dimming scrim with a rounded
 * "hole" punched around the current step's real control (via [BlendMode.Clear] on an offscreen
 * layer), plus a caption card + pointer aimed at it. Steps advance with Next/Back; Skip or the
 * final Done both call [onFinish], which the host uses to persist the seen-flag and dismiss.
 *
 * @param anchors live bounds (in root coordinates, px) of each spotlightable control.
 * @param coachFabPresent whether the coach FAB step should be included (mirrors the FAB's own gate).
 */
@Composable
fun FirstRunTutorialOverlay(
    anchors: Map<TutorialTarget, Rect>,
    coachFabPresent: Boolean,
    onFinish: () -> Unit,
) {
    val t = LocalTranslate.current
    val steps = remember(coachFabPresent) { tutorialSteps(coachFabPresent) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Guard against the step list shrinking (e.g. the FAB disappearing mid-tour) leaving a stale
    // index out of bounds.
    val safeIndex = index.coerceIn(0, steps.lastIndex)
    val step = steps[safeIndex]
    val isLast = safeIndex == steps.lastIndex

    val density = LocalDensity.current
    val scrim = Color.Black.copy(alpha = 0.82f)
    val cardColor = MaterialTheme.colorScheme.surface
    val ringColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxHpx = with(density) { maxHeight.toPx() }
        val target = step.target?.let { anchors[it] }
        val gapPx = with(density) { 12.dp.toPx() }
        val arrowHalfPx = with(density) { 9.dp.toPx() }
        val arrowHPx = with(density) { 9.dp.toPx() }
        val cutoutPadPx = with(density) { 6.dp.toPx() }
        // Bottom-nav items and the FAB live in the lower half → caption goes above them; the top-bar
        // help action lives up top → caption goes below it.
        val placeAbove = target != null && target.center.y > maxHpx / 2f

        // Swallow every touch that isn't on the caption card so the app behind the scrim can't be
        // poked while the tour is up. Consuming each change blocks the scaffold underneath from also
        // reacting; the card (a later sibling, drawn on top) still receives its own button taps.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(color = scrim)
            if (target != null) {
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(target.left - cutoutPadPx, target.top - cutoutPadPx),
                    size = Size(target.width + cutoutPadPx * 2, target.height + cutoutPadPx * 2),
                    cornerRadius = CornerRadius(with(density) { 18.dp.toPx() }),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = ringColor,
                    topLeft = Offset(target.left - cutoutPadPx, target.top - cutoutPadPx),
                    size = Size(target.width + cutoutPadPx * 2, target.height + cutoutPadPx * 2),
                    cornerRadius = CornerRadius(with(density) { 18.dp.toPx() }),
                    style = Stroke(width = with(density) { 2.dp.toPx() }),
                )
                // Caption pointer: a small triangle from the card edge toward the target.
                val cx = target.center.x
                val path = Path().apply {
                    if (placeAbove) {
                        val tipY = target.top - cutoutPadPx - gapPx
                        moveTo(cx, tipY)
                        lineTo(cx - arrowHalfPx, tipY - arrowHPx)
                        lineTo(cx + arrowHalfPx, tipY - arrowHPx)
                    } else {
                        val tipY = target.bottom + cutoutPadPx + gapPx
                        moveTo(cx, tipY)
                        lineTo(cx - arrowHalfPx, tipY + arrowHPx)
                        lineTo(cx + arrowHalfPx, tipY + arrowHPx)
                    }
                    close()
                }
                drawPath(path, color = cardColor)
            }
        }

        // Vertical placement of the caption card relative to the spotlight (or centered when the
        // step has no target, e.g. the Welcome step).
        val cardAlign: Alignment
        val cardPadding: PaddingValues
        if (target == null) {
            cardAlign = Alignment.Center
            cardPadding = PaddingValues(horizontal = 24.dp)
        } else if (placeAbove) {
            val bottomInsetDp = with(density) { (maxHpx - (target.top - cutoutPadPx - gapPx - arrowHPx)).toDp() }
            cardAlign = Alignment.BottomCenter
            cardPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInsetDp.coerceAtLeast(0.dp))
        } else {
            val topInsetDp = with(density) { (target.bottom + cutoutPadPx + gapPx + arrowHPx).toDp() }
            cardAlign = Alignment.TopCenter
            cardPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInsetDp.coerceAtLeast(0.dp))
        }

        Surface(
            modifier = Modifier
                .align(cardAlign)
                .padding(cardPadding)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            color = cardColor,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = t(step.titleKey),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = t(step.bodyKey),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Progress dots.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        steps.indices.forEach { i ->
                            val on = i == safeIndex
                            Box(
                                modifier = Modifier
                                    .size(if (on) 9.dp else 7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (safeIndex > 0) {
                            TextButton(onClick = { index = safeIndex - 1 }) { Text(t("tour.back")) }
                        }
                        if (isLast) {
                            Button(onClick = onFinish) { Text(t("tour.done")) }
                        } else {
                            Button(onClick = { index = safeIndex + 1 }) { Text(t("tour.next")) }
                        }
                    }
                }
                if (!isLast) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            t("tour.skip"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
