package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.FluencyMetrics
import com.example.medvoicetrainer.analysis.FluencyMetricsResult
import com.example.medvoicetrainer.ui.CorrectionDecision
import com.example.medvoicetrainer.ui.SrsCorrection
import com.example.medvoicetrainer.ui.isBulkAcceptableCorrection
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The post-session report used to be eleven flat chips, eight of them hidden behind a single "＋"
 * overflow menu — so most of what the analysis pipeline computed was, in practice, never read.
 * It is now six *groups*, all present on one scrollable page as collapsed accordion cards whose
 * headers carry that group's headline numbers. Nothing was deleted: collapsing moved detail one
 * tap away while the conclusion stays visible without any tap at all.
 *
 * English-first ordering is unchanged and still load-bearing: the language groups (Overview,
 * Fix & practice, Delivery) lead, and the clinical-realism group sits at the tail so the report
 * never reads as "the app graded my medicine". Declaration order IS display order.
 */
internal enum class FeedbackSection(val labelKey: String) {
    /** Summary + overall score + "★ fix this first" + the per-dimension score bars. */
    OVERVIEW("feedback.group_overview"),

    /** Corrections triage + the spoken redo loop + shadowing — everything you *do* about errors. */
    FIX("feedback.group_fix"),

    /** Fluency measurements + communication-clarity / blind-audio intelligibility. */
    DELIVERY("feedback.group_delivery"),

    /** Next-practice plan + self-assessment: opt-in, never blocking. */
    REFLECT("feedback.group_reflect"),

    /** The saved transcript and learner audio clips. */
    TRANSCRIPT("feedback.transcript_tab"),

    /** Checklist coverage + SOAP note. Clinical encounters only. */
    CLINICAL("feedback.group_clinical")
}

internal fun feedbackSections(everyday: Boolean): List<FeedbackSection> =
    FeedbackSection.entries.filter { section ->
        !everyday || section != FeedbackSection.CLINICAL
    }

/** Only the conclusion opens itself; every other group is one tap away. */
internal fun defaultExpandedSections(): Set<FeedbackSection> = setOf(FeedbackSection.OVERVIEW)

/**
 * Which one-time banner (if any) may occupy the top of the feedback screen right now. Previously
 * all three could stack on a learner's first live session, pushing the score they came to see
 * below the fold; they are now a strict priority queue showing at most one, so the rest surface on
 * later sessions instead of competing for the same screen.
 */
internal enum class FeedbackBanner { EXPLAINER, COST_PRIMER, REMINDER_OPTIN }

internal fun activeFeedbackBanner(
    explainerPending: Boolean,
    costPrimerPending: Boolean,
    reminderPending: Boolean
): FeedbackBanner? = when {
    explainerPending -> FeedbackBanner.EXPLAINER
    costPrimerPending -> FeedbackBanner.COST_PRIMER
    reminderPending -> FeedbackBanner.REMINDER_OPTIN
    else -> null
}

/** How many corrections the triage list shows before "show N more". */
internal const val CORRECTION_PREVIEW_COUNT = 3

/**
 * Highest-impact corrections first: real errors ahead of optional style notes, then by model
 * confidence, then original order. A long tail of low-confidence style suggestions is exactly what
 * makes the triage list feel like homework, so the preview cut has to take the top of *this*
 * order, not the first three the model happened to emit.
 */
internal fun orderCorrectionsByImpact(corrections: List<SrsCorrection>): List<SrsCorrection> =
    corrections.withIndex()
        .sortedWith(
            compareBy<IndexedValue<SrsCorrection>> { if (it.value.feedbackType == "style") 1 else 0 }
                .thenByDescending { it.value.confidence }
                .thenBy { it.index }
        )
        .map { it.value }

internal data class CorrectionTriageStats(
    val total: Int,
    val saved: Int,
    val reviewed: Int,
    val pendingBulkAcceptable: Int
) {
    val pending: Int get() = total - reviewed
}

internal fun correctionTriageStats(
    corrections: List<SrsCorrection>,
    decisions: Map<String, CorrectionDecision>
): CorrectionTriageStats {
    fun decisionOf(correction: SrsCorrection) =
        decisions[correction.decisionKey()] ?: CorrectionDecision.PENDING
    return CorrectionTriageStats(
        total = corrections.size,
        saved = corrections.count { decisionOf(it) == CorrectionDecision.ACCEPTED },
        reviewed = corrections.count { decisionOf(it) != CorrectionDecision.PENDING },
        pendingBulkAcceptable = corrections.count {
            isBulkAcceptableCorrection(it) && decisionOf(it) == CorrectionDecision.PENDING
        }
    )
}

/**
 * The Delivery group's collapsed headline — grade, speaking rate and filler density, i.e. the
 * three numbers a learner would otherwise have had to open two separate tabs to read. Falls back
 * to the transcript-clarity grade when no fluency metrics were computed, and to an empty string
 * when the session produced neither.
 */
internal fun deliveryHeadline(
    fluency: FluencyMetricsResult?,
    intelligibility: Map<String, Any>?
): String {
    val parts = mutableListOf<String>()
    if (fluency != null) {
        parts += FluencyMetrics.fluencyGrade(fluency).first
        fluency.wordsPerMinute?.let { parts += "${it.roundToInt()} WPM" }
        parts += String.format(Locale.US, "%.1f%% fillers", fluency.fillerDensity)
    }
    if (parts.isEmpty()) {
        val grade = intelligibility?.get("grade")?.toString()?.takeIf { it.isNotBlank() }
        val score = intelligibility?.get("score")
        if (grade != null) parts += if (score != null) "$grade ($score/10)" else grade
    }
    return parts.joinToString(" · ")
}

/**
 * A collapsible group card. The header is always readable — title plus that group's headline
 * numbers — so a learner who never expands anything still leaves with every group's conclusion.
 */
@Composable
internal fun FeedbackAccordionCard(
    title: String,
    headline: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandLabel: String,
    collapseLabel: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!headline.isNullOrBlank()) {
                        Text(
                            headline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) collapseLabel else expandLabel
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

/** The same disclosure pattern one level down, inside a group (lighter, no card elevation). */
@Composable
internal fun FeedbackSubBlock(
    title: String,
    headline: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandLabel: String,
    collapseLabel: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (!headline.isNullOrBlank()) {
                        Text(
                            headline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) collapseLabel else expandLabel
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Which groups are open, survivable across configuration changes. Backed by a single
 * comma-separated string because that is what `rememberSaveable`'s default saver can store
 * without a custom `Saver` for a set.
 */
@Stable
internal class FeedbackExpansionState(private val encoded: MutableState<String>) {
    private val expanded: Set<String>
        get() = encoded.value.split(',').filterNot { it.isBlank() }.toSet()

    fun isExpanded(section: FeedbackSection): Boolean = section.name in expanded

    fun toggle(section: FeedbackSection) {
        val next = expanded.toMutableSet()
        if (!next.add(section.name)) next.remove(section.name)
        encoded.value = next.joinToString(",")
    }

    fun expand(section: FeedbackSection) {
        val next = expanded.toMutableSet()
        next.add(section.name)
        encoded.value = next.joinToString(",")
    }
}

@Composable
internal fun rememberFeedbackExpansion(key: Any): FeedbackExpansionState {
    val encoded = rememberSaveable(key) {
        mutableStateOf(defaultExpandedSections().joinToString(",") { it.name })
    }
    return remember(encoded) { FeedbackExpansionState(encoded) }
}
