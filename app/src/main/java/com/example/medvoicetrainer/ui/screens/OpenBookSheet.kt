package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medvoicetrainer.analysis.ClosingLevel
import com.example.medvoicetrainer.analysis.ClosingLine
import com.example.medvoicetrainer.analysis.ClosingMove
import com.example.medvoicetrainer.analysis.OpenBookAidsUsed
import com.example.medvoicetrainer.analysis.OpenBookAsk
import com.example.medvoicetrainer.analysis.OpenBookCard
import com.example.medvoicetrainer.analysis.OpenBookLevel
import com.example.medvoicetrainer.ui.ChecklistItem
import com.example.medvoicetrainer.ui.LocalTranslate

/**
 * Open Book — the staged answer sheet for an encounter (see
 * [com.example.medvoicetrainer.analysis.OpenBookEngine]).
 *
 * The learner this exists for is not short of English; they are short of clinical knowledge. Told
 * "chest pain radiating to the left arm" they have nothing to ask next, so the microphone is a wall
 * rather than an exercise. The sheet hands back the clinical half — the diagnosis, and what that
 * diagnosis obliges you to ask — so the only load left is saying it in English, which is the thing
 * they came here to practise.
 *
 * Everything about the presentation is built to keep it from becoming reading practice: one level
 * per deliberate tap, the checklist ticks itself off from the live transcript so the sheet reports
 * progress rather than restating itself, and the model sentences arrive last and default to "say it
 * yourself" rather than "paste it into the box".
 */

/** The entry point next to "Stuck? Question idea", showing help actually opened mid-visit. */
@Composable
fun OpenBookChip(level: OpenBookLevel, briefed: Boolean = false, onClick: () -> Unit) {
    val t = LocalTranslate.current
    val label = when {
        level != OpenBookLevel.HIDDEN -> t("openbook.chip") + " · Lv." + level.step
        briefed -> t("Case notes")
        else -> t("openbook.chip")
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        leadingIcon = {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.secondary),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBookSheet(
    card: OpenBookCard,
    level: OpenBookLevel,
    /** How far the independent closing ladder is open. */
    closingLevel: ClosingLevel,
    /** Live coverage for [OpenBookCard.mustAsk], keyed by objective. Empty before the first turn. */
    coverage: Map<String, ChecklistItem>,
    /** Deepest level this learner needed the last time they practised this specialty, if any. */
    previousLevel: Int?,
    /** True while the microphone is held muted for the duration of the sheet. */
    micHeld: Boolean,
    /** False when this session has no live microphone at all (typed/demo), so the mic row is moot. */
    micAvailable: Boolean,
    /** The learner chose English practice and reviewed diagnosis/checklist before the visit. */
    briefed: Boolean,
    ttsReady: Boolean,
    onReveal: (OpenBookLevel) -> Unit,
    onRevealClosing: (ClosingLevel) -> Unit,
    onReleaseMic: () -> Unit,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
    onDismiss: () -> Unit,
    initialPhrases: Boolean = false,
) {
    val t = LocalTranslate.current
    var showingPhrases by rememberSaveable(initialPhrases) { mutableStateOf(initialPhrases) }
    val scrollState = rememberScrollState()
    LaunchedEffect(showingPhrases) { scrollState.scrollTo(0) }
    // A pre-visit preview already revealed the case context. Keep that fact separate from the
    // optional in-visit phrase view, which only changes what this sheet displays.
    val visibleLevel = com.example.medvoicetrainer.analysis.OpenBookEngine.displayedLevel(level, briefed)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    t(if (showingPhrases) "Phrases" else if (briefed && level == OpenBookLevel.HIDDEN) "Case notes" else "openbook.title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (briefed && card.mustAsk.any { !it.say.isNullOrBlank() }) {
                    TextButton(onClick = { showingPhrases = !showingPhrases }) {
                        Text(t(if (showingPhrases) "Case notes" else "Open phrases"), fontSize = 11.sp)
                    }
                }
            }

            // A muted microphone during an otherwise full-duplex conversation is exactly the kind of
            // state that reads as a bug if it isn't named, so the time-out says so outright — and
            // offers the way out in place, because the alternative the learner found for themselves
            // was closing the sheet, un-muting, and reopening it for every single sentence.
            if (micHeld && micAvailable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.MicOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        t("openbook.timeout"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReleaseMic) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(t("openbook.unmute"), fontSize = 11.sp)
                    }
                }
            }

            if (!showingPhrases && visibleLevel == OpenBookLevel.HIDDEN) {
                Text(
                    t("openbook.intro"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The only thing that turns a scaffold into something a learner outgrows is being
                // told, at the moment of reaching for it, that they needed less of it before.
                if (previousLevel != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            t("openbook.previous").replace("%d", previousLevel.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            if (!showingPhrases && visibleLevel.step >= OpenBookLevel.SHORTLIST.step && visibleLevel.step < OpenBookLevel.DIAGNOSIS.step) {
                LevelHeader(1, t("openbook.level1.title"))
                Text(
                    t("openbook.level1.body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                card.shortlist.forEach { candidate ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "◇  $candidate",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            if (!showingPhrases && visibleLevel.step >= OpenBookLevel.DIAGNOSIS.step) {
                LevelHeader(2, t("openbook.level2.title"))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            card.diagnosis,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        card.oneLiner?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if ((showingPhrases || !briefed) && visibleLevel.step >= OpenBookLevel.CHECKLIST.step && card.mustAsk.isNotEmpty()) {
                if (showingPhrases) {
                    Text(
                        t("Useful wording for this conversation"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        t("Use these as a starting point. You can say them as written or make them your own."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    card.mustAsk.filter { !it.say.isNullOrBlank() }.forEach { ask ->
                        MustAskRow(
                            ask = ask,
                            met = coverage[ask.objective]?.isMet == true,
                            showScript = true,
                            ttsReady = ttsReady,
                            onListen = onListen,
                            onUsePhrase = onUsePhrase,
                            useLabel = t("openbook.use_phrase"),
                        )
                    }
                } else {
                LevelHeader(3, t("openbook.level3.title"))
                Text(
                    t("openbook.level3.body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                card.mustAsk.forEach { ask ->
                    MustAskRow(
                        ask = ask,
                        met = coverage[ask.objective]?.isMet == true,
                        showScript = visibleLevel.step >= OpenBookLevel.SCRIPT.step,
                        ttsReady = ttsReady,
                        onListen = onListen,
                        onUsePhrase = onUsePhrase,
                        useLabel = t("openbook.use_phrase"),
                    )
                }
                if (card.redFlags.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    t("openbook.red_flags"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            card.redFlags.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                }
            }

            // A card whose case shipped no must-ask list stops at the diagnosis. Saying so beats a
            // disabled button the learner keeps pressing, and beats an empty checklist that reads
            // as "you have already asked everything".
            if (!showingPhrases && !briefed && visibleLevel.step >= OpenBookLevel.DIAGNOSIS.step && card.mustAsk.isEmpty()) {
                Text(
                    t("openbook.no_checklist"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            val next = card.nextLevel(visibleLevel)
            if (!showingPhrases && !briefed && next != null) {
                Button(onClick = { onReveal(next) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (next) {
                            OpenBookLevel.SHORTLIST -> t("openbook.reveal.shortlist")
                            OpenBookLevel.DIAGNOSIS -> t("openbook.reveal.diagnosis")
                            OpenBookLevel.CHECKLIST -> t("openbook.reveal.checklist")
                            OpenBookLevel.SCRIPT -> t("openbook.reveal.script")
                            OpenBookLevel.HIDDEN -> ""
                        }
                    )
                }
            }

            if (!showingPhrases && !briefed) card.closing?.takeIf { it.lines.isNotEmpty() }?.let { closing ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ClosingSection(
                    closing = closing,
                    closingLevel = closingLevel,
                    ttsReady = ttsReady,
                    onListen = onListen,
                    onUsePhrase = onUsePhrase,
                )
                card.nextClosingLevel(closingLevel)?.let { nextClosing ->
                    Button(
                        onClick = { onRevealClosing(nextClosing) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when (nextClosing) {
                                ClosingLevel.FRAME -> t("openbook.closing.reveal.frame")
                                ClosingLevel.SCRIPT -> t("openbook.closing.reveal.script")
                                ClosingLevel.HIDDEN -> ""
                            }
                        )
                    }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(t("openbook.close"))
            }
            Text(
                t("openbook.disclaimer"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The closing half of the sheet: the four moves a consultation ending has to make.
 *
 * Presented as moves rather than as one script because the shape is the transferable part — the
 * learner meets a different disease every session and the same four moves every time — and because
 * a flat block of six sentences to read out is a performance, not practice. [ClosingLevel.FRAME]
 * gives the moves and what each has to achieve so the learner tries their own English first;
 * [ClosingLevel.SCRIPT] then supplies the model sentence to compare against.
 */
@Composable
private fun ClosingSection(
    closing: com.example.medvoicetrainer.analysis.OpenBookClosing,
    closingLevel: ClosingLevel,
    ttsReady: Boolean,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
) {
    val t = LocalTranslate.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🩺", fontSize = 16.sp)
        Text(
            t("openbook.closing.title"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Text(
        if (closingLevel == ClosingLevel.HIDDEN) t("openbook.closing.intro") else t("openbook.closing.body"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (closingLevel == ClosingLevel.HIDDEN) return

    closing.moves.forEachIndexed { index, move ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (index + 1).toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                    Text(
                        t("openbook.closing.move.${move.key}"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    t("openbook.closing.point.${move.key}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                closing.linesFor(move).forEach { line ->
                    ClosingLineRow(
                        line = line,
                        showScript = closingLevel.step >= ClosingLevel.SCRIPT.step,
                        ttsReady = ttsReady,
                        onListen = onListen,
                        onUsePhrase = onUsePhrase,
                    )
                }
            }
        }
    }
}

/**
 * One line of the closing script, and — where the case has one — the chart sentence it came from.
 *
 * The two are shown together on purpose. A case's own plan is written for a chart ("Aggressive IV
 * hydration with Normal Saline, check CK and urine pH") and saying that to a patient is the register
 * mistake this app's learners make most; seeing the two side by side, on a case they have just spent
 * ten minutes inside, is the whole lesson. The chart line is therefore always labelled as something
 * not to say, never offered to the typing box, and never spoken by the TTS.
 */
@Composable
private fun ClosingLineRow(
    line: ClosingLine,
    showScript: Boolean,
    ttsReady: Boolean,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
) {
    val t = LocalTranslate.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line.point?.let {
            Text(
                "→ $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        line.chartNote?.let { note ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        t("openbook.closing.chart_label"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showScript) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "“${line.say}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (ttsReady) {
                    IconButton(onClick = { onListen(line.say) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // A frame line still has a blank in it, so dropping it into the box would send "___"
            // to the patient. Only complete sentences are offered.
            if (!line.hasBlank) {
                TextButton(onClick = { onUsePhrase(line.say) }, modifier = Modifier.align(Alignment.End)) {
                    Text(t("openbook.use_phrase"), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LevelHeader(step: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondary,
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One "must ask" row. Ticked rows stay visible and struck through rather than disappearing — the
 * list doubles as the record of what the learner has covered, and a shrinking list would take that
 * away exactly when it is most reassuring.
 */
@Composable
private fun MustAskRow(
    ask: OpenBookAsk,
    met: Boolean,
    showScript: Boolean,
    ttsReady: Boolean,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
    useLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (met) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (met) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (met) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    ask.objective,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (met) TextDecoration.LineThrough else null,
                    color = if (met) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            val say = ask.say
            if (showScript && !say.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "“$say”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (ttsReady) {
                        IconButton(onClick = { onListen(say) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                // Deliberately secondary to reading it aloud: dropping the sentence into the typing
                // box gets the encounter moving, but it is not speaking practice, so it is a small
                // text button rather than the obvious action.
                TextButton(
                    onClick = { onUsePhrase(say) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(useLabel, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Post-session badge naming how much of the answer sheet was open. Not a penalty — the analysis
 * prompt is explicitly told not to deduct for it — but leaving it off the report would let a
 * heavily scaffolded run read as an unaided one.
 *
 * The three aids are named separately because they are not degrees of one thing: a briefed session
 * was never meant to be a diagnostic test, and needing the closing script says nothing about how the
 * history went.
 */
@Composable
fun OpenBookUsedBadge(aids: OpenBookAidsUsed, modifier: Modifier = Modifier) {
    val t = LocalTranslate.current
    if (!aids.any) return
    val parts = buildList {
        if (aids.briefed) add(t("openbook.badge.briefed"))
        // A briefing already hands over levels 1-3, so the sheet is only worth naming separately
        // when the learner went on to open more than the briefing had given them.
        val briefedLevel = if (aids.briefed) OpenBookLevel.CHECKLIST.step else 0
        if (aids.level > briefedLevel) add(t("openbook.badge").replace("%d", aids.level.toString()))
        if (aids.closingLevel > 0) add(t("openbook.badge.closing"))
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * The pinned one-line summary that stays above the composer once the sheet has been closed.
 *
 * The sheet is modal, and a modal answer sheet on a live conversation forces the learner to choose
 * between reading and talking — which they resolved by reopening and re-closing it for every
 * question. The strip is the part worth keeping visible: how many of the must-ask rows are done and
 * which one is next. It restates nothing; [coverage] ticks the rows off the live transcript, so the
 * strip is a progress readout rather than a second copy of the checklist.
 */
@Composable
fun OpenBookHudStrip(
    card: OpenBookCard,
    coverage: Map<String, ChecklistItem>,
    closingLevel: ClosingLevel,
    onOpen: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslate.current
    val met = card.mustAsk.count { coverage[it.objective]?.isMet == true }
    val nextAsk = card.mustAsk.firstOrNull { coverage[it.objective]?.isMet != true }
    val allDone = card.mustAsk.isNotEmpty() && nextAsk == null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (allDone) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            if (card.mustAsk.isNotEmpty()) {
                Text(
                    "$met/${card.mustAsk.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = when {
                    nextAsk != null -> t("openbook.hud.next").replace("%s", nextAsk.objective)
                    closingLevel != ClosingLevel.HIDDEN -> t("openbook.hud.closing_open")
                    // Every row ticked is the exact moment the learner used to run out of things to
                    // say, so the strip stops reporting the history and points at the closing.
                    card.mustAsk.isNotEmpty() -> t("openbook.hud.closing_next")
                    else -> t("openbook.chip")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = t("openbook.hud.unpin"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The pre-visit briefing: the diagnosis and its question checklist, before the microphone opens.
 *
 * This is the same content as levels 2 and 3 of the sheet, and deliberately so — what changes is
 * only *when* the learner gets it, which turns out to be the whole difference. Reached mid-encounter
 * the sheet is an admission of defeat and arrives after the learner has already frozen; handed over
 * at the door it is a handover, which is what every real ward round gives you. It separates the two
 * loads the app was always confusing: knowing the medicine, and saying it in English. Only the
 * second one is what this app grades.
 */
@Composable
fun OpenBookBriefingDialog(
    card: OpenBookCard,
    onStart: (reviewedPreview: Boolean) -> Unit,
) {
    val t = LocalTranslate.current
    var reviewingPreview by rememberSaveable { mutableStateOf(false) }
    var showingSampleQuestions by rememberSaveable { mutableStateOf(false) }
    val sampleQuestions = buildList {
        card.mustAsk.forEach { ask ->
            ask.say?.takeIf { it.isNotBlank() }?.let { add(ask.objective to it) }
        }
    }

    if (!reviewingPreview) {
        AlertDialog(
            // The learner decides whether to see the case context; neither choice is a learning mode.
            onDismissRequest = {},
            icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
            title = { Text(t("How would you like to begin?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        t("Choose how much context you want before the conversation starts."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column {
                        Text(t("Go in Cold"), fontWeight = FontWeight.Bold)
                        Text(
                            t("Start the conversation with no case preview."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        Text(t("Review First"), fontWeight = FontWeight.Bold)
                        Text(
                            t("Read the case notes before you begin."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { onStart(false) }) { Text(t("Go in Cold")) } },
            dismissButton = {
                TextButton(onClick = { reviewingPreview = true }) { Text(t("Review First")) }
            },
        )
        return
    }

    AlertDialog(
        // Starting a live session stays an explicit action: the microphone must not turn on while
        // someone is reading the preview.
        onDismissRequest = {},
        icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
        title = { Text(t(if (showingSampleQuestions) "Sample questions" else "Case notes")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (showingSampleQuestions) {
                    Text(
                        t("Use these as a starting point. You can say them as written or make them your own."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sampleQuestions.isEmpty()) {
                        Text(
                            t("This case has no sample questions yet. Start when you're ready."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sampleQuestions.forEach { (objective, question) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        objective,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "“$question”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                } else {
                Text(
                    t("Take a moment to read the note. Open sample questions if you want wording before you start."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            card.diagnosis,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        card.oneLiner?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (card.mustAsk.isNotEmpty()) {
                    Text(
                        t("openbook.brief.ask"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    card.mustAsk.forEach { ask ->
                        Text(
                            "• ${ask.objective}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                }
                Text(
                    t("openbook.disclaimer"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onStart(true) }) { Text(t("Start conversation")) } },
        dismissButton = {
            TextButton(onClick = { showingSampleQuestions = !showingSampleQuestions }) {
                Text(t(if (showingSampleQuestions) "Back to case notes" else "Open sample questions"))
            }
        },
    )
}

/** A direct route to model wording, kept beside the case-notes entry point during a visit. */
@Composable
fun OpenBookPhrasesChip(onClick: () -> Unit) {
    val t = LocalTranslate.current
    AssistChip(
        onClick = onClick,
        label = { Text(t("Phrases"), fontSize = 11.sp) },
        leadingIcon = {
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.secondary),
    )
}
