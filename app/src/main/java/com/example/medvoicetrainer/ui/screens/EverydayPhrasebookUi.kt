package com.example.medvoicetrainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medvoicetrainer.analysis.EverydayPhrase
import com.example.medvoicetrainer.analysis.PhrasebookAidsUsed
import com.example.medvoicetrainer.analysis.PhrasebookCard
import com.example.medvoicetrainer.analysis.PhrasebookLevel
import com.example.medvoicetrainer.analysis.PhraseRegister
import com.example.medvoicetrainer.ui.ChecklistItem
import com.example.medvoicetrainer.ui.LocalTranslate

/**
 * The Everyday Phrasebook's four surfaces — the pre-start card, the in-session sheet, the feedback
 * recap, and the "keep this" button that feeds the learner's own collection.
 *
 * They are one feature deliberately spread across the session, because a phrasebook that only
 * appears in one place fails in a predictable way at each of them: shown only before the start it
 * is read and forgotten, shown only mid-conversation it arrives after the learner has already
 * frozen, and shown only afterwards it is a list of things they could have said. Together they make
 * a loop — see it, reach for it, be told you used it, keep the ones you didn't.
 *
 * Everything here follows [com.example.medvoicetrainer.analysis.OpenBookSheet]'s rule about not
 * becoming reading practice: one level per deliberate tap, the function before the sentence, and
 * "say it yourself" ahead of "put it in the box".
 */

/** The in-session entry point, sitting next to "Stuck?" and showing how far the sheet is open. */
@Composable
fun PhrasebookChip(level: PhrasebookLevel, usedCount: Int, onClick: () -> Unit) {
    val t = LocalTranslate.current
    val label = buildString {
        append(t("phrasebook.chip"))
        if (level != PhrasebookLevel.HIDDEN) append(" · Lv.${level.step}")
        // The count is the only part of the chip that ever reports success rather than help taken,
        // so it earns its space the moment there is one.
        if (usedCount > 0) append("  ✓$usedCount")
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        leadingIcon = {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.tertiary),
    )
}

/**
 * The staged sheet.
 *
 * Level 1 shows only what each expression *does*, in the learner's own language where the content
 * has it, so the first stop is "I know the move, let me try it" rather than a sentence to copy.
 * Level 2 hands over the frame with the learner's own words blanked out. Level 3 is the complete
 * sentence, and only then does the "put it in the box" affordance appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhrasebookSheet(
    card: PhrasebookCard,
    level: PhrasebookLevel,
    /** Live usage for the offered expressions, keyed by the sentence. Empty before the first turn. */
    usage: Map<String, ChecklistItem>,
    /** True while the microphone is held muted for the duration of the sheet. */
    micHeld: Boolean,
    /** False when this session has no live microphone at all (typed/demo), so the mic row is moot. */
    micAvailable: Boolean,
    ttsReady: Boolean,
    /**
     * Whether an expression is already in the learner's own phrasebook, so "keep" cannot be pressed
     * twice. A predicate rather than a set because the store matches normalized text — an exact
     * string comparison here would silently offer a duplicate over a difference in punctuation.
     */
    kept: (String) -> Boolean,
    onReveal: (PhrasebookLevel) -> Unit,
    onReleaseMic: () -> Unit,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
    onKeep: (EverydayPhrase) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalTranslate.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    t("phrasebook.title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Same reasoning as Open Book's: a muted mic in a full-duplex conversation reads as a
            // bug unless it is named, and the way back is offered here rather than found by closing
            // and reopening the sheet for every sentence.
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
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("openbook.unmute"), fontSize = 11.sp)
                    }
                }
            }

            Text(
                when (level) {
                    PhrasebookLevel.HIDDEN -> t("phrasebook.intro")
                    PhrasebookLevel.FUNCTION -> t("phrasebook.level1.body")
                    PhrasebookLevel.SKELETON -> t("phrasebook.level2.body")
                    PhrasebookLevel.SENTENCE -> t("phrasebook.level3.body")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (level != PhrasebookLevel.HIDDEN) {
                val scene = card.sceneEntries
                val bank = card.functionEntries
                // Scene lines are labelled apart from the shared ones because a line written for
                // this conversation is worth more than a line that works in any conversation, and
                // the learner should be able to tell which they are being handed.
                if (scene.isNotEmpty()) {
                    SectionLabel(t("phrasebook.scene_label"))
                    scene.forEach { phrase ->
                        PhraseRow(
                            phrase = phrase,
                            level = level,
                            used = usage[phrase.en]?.isMet == true,
                            keptAlready = kept(phrase.en),
                            ttsReady = ttsReady,
                            onListen = onListen,
                            onUsePhrase = onUsePhrase,
                            onKeep = onKeep,
                        )
                    }
                }
                if (bank.isNotEmpty()) {
                    if (scene.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    SectionLabel(t("phrasebook.bank_label"))
                    bank.forEach { phrase ->
                        PhraseRow(
                            phrase = phrase,
                            level = level,
                            used = usage[phrase.en]?.isMet == true,
                            keptAlready = kept(phrase.en),
                            ttsReady = ttsReady,
                            onListen = onListen,
                            onUsePhrase = onUsePhrase,
                            onKeep = onKeep,
                        )
                    }
                }
            }

            card.nextLevel(level)?.let { next ->
                Button(onClick = { onReveal(next) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (next) {
                            PhrasebookLevel.FUNCTION -> t("phrasebook.reveal.function")
                            PhrasebookLevel.SKELETON -> t("phrasebook.reveal.skeleton")
                            PhrasebookLevel.SENTENCE -> t("phrasebook.reveal.sentence")
                            PhrasebookLevel.HIDDEN -> ""
                        }
                    )
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(t("openbook.close"))
            }
        }
    }
}

/**
 * One expression, showing exactly as much as the current level allows.
 *
 * A used line keeps its place and gains a tick rather than disappearing: the sheet doubles as the
 * record of what the learner managed to say, and a list that shrinks as they succeed takes that
 * away at the moment it is most worth having.
 */
@Composable
private fun PhraseRow(
    phrase: EverydayPhrase,
    level: PhrasebookLevel,
    used: Boolean,
    keptAlready: Boolean,
    ttsReady: Boolean,
    onListen: (String) -> Unit,
    onUsePhrase: (String) -> Unit,
    onKeep: (EverydayPhrase) -> Unit,
) {
    val t = LocalTranslate.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (used) 0.3f else 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (used) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (used) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        phrase.function.ifBlank { phrase.gloss.orEmpty().ifBlank { phrase.en } },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    phrase.gloss?.takeIf { phrase.function.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                phrase.register?.let { RegisterBadge(it) }
            }

            if (level.step >= PhrasebookLevel.SKELETON.step && level.step < PhrasebookLevel.SENTENCE.step) {
                Text(
                    "“${phrase.skeleton}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            if (level.step >= PhrasebookLevel.SENTENCE.step) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "“${phrase.en}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (ttsReady) {
                        IconButton(onClick = { onListen(phrase.en) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = t("phrasebook.listen"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                phrase.why?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    KeepExpressionButton(kept = keptAlready, onKeep = { onKeep(phrase) })
                    TextButton(onClick = { onUsePhrase(phrase.en) }) {
                        Text(t("openbook.use_phrase"), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * The pre-start card.
 *
 * Collapsed by default and capped at three expressions. Both are the design: a start screen that
 * opens onto ten model sentences is read once, remembered as none, and stands between the learner
 * and the thing they came to do. Expanding it shows only what each expression *does* — the English
 * still costs one more tap, so a learner who wants to try it cold can read the moves and go.
 */
@Composable
fun ScenePhrasesCard(
    phrases: List<EverydayPhrase>,
    ttsReady: Boolean,
    onListen: (String) -> Unit,
    /** Called whenever the card is opened, so the session can report the aid honestly. */
    onOpened: () -> Unit,
) {
    if (phrases.isEmpty()) return
    val t = LocalTranslate.current
    var expanded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<String>()) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded) onOpened()
                    },
            ) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        t("phrasebook.preview.title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        t("phrasebook.preview.body").replace("{n}", phrases.size.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = t(if (expanded) "phrasebook.preview.hide" else "phrasebook.preview.show"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        t("phrasebook.preview.tap"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    phrases.forEach { phrase ->
                        val open = phrase.en in revealed
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { revealed = if (open) revealed - phrase.en else revealed + phrase.en },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        phrase.function.ifBlank { phrase.en },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    phrase.register?.let { RegisterBadge(it) }
                                }
                                phrase.gloss?.takeIf { phrase.function.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (open) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "“${phrase.en}”",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (ttsReady) {
                                            IconButton(
                                                onClick = { onListen(phrase.en) },
                                                modifier = Modifier.size(30.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.VolumeUp,
                                                    contentDescription = t("phrasebook.listen"),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                    phrase.why?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The feedback recap: which of the offered expressions the learner actually produced.
 *
 * Deliberately reports the successes first and never scolds for the rest. The unused lines are
 * shown as the obvious next thing to drill, not as a miss — they are, after all, exactly the
 * sentences this learner did not yet have.
 */
@Composable
fun PhrasebookRecapCard(
    aids: PhrasebookAidsUsed,
    onOpenDrill: ((List<EverydayPhrase>) -> Unit)? = null,
) {
    if (aids.offered.isEmpty() && !aids.any) return
    val t = LocalTranslate.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t("phrasebook.recap.title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (aids.used.isEmpty()) {
                    t("phrasebook.recap.none")
                } else {
                    t("phrasebook.recap.used")
                        .replace("{n}", aids.used.size.toString())
                        .replace("{m}", aids.offered.size.toString())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            aids.used.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text("“$line”", style = MaterialTheme.typography.bodySmall)
                }
            }
            val unused = aids.unused
            if (onOpenDrill != null && unused.isNotEmpty()) {
                TextButton(onClick = { onOpenDrill(unused) }) {
                    Text(t("phrasebook.recap.drill"), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * "Keep this one" — the button that turns a correction or a model line into something the learner
 * will meet again in the drill.
 *
 * Shows its result in place once pressed rather than disappearing, so the learner can see that the
 * tap did something. That was the whole failure mode of the correction list this replaces: a screen
 * of better wordings with nowhere for any of it to go.
 */
@Composable
fun KeepExpressionButton(kept: Boolean, onKeep: () -> Unit) {
    val t = LocalTranslate.current
    if (kept) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                t("phrasebook.kept"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    TextButton(onClick = onKeep) {
        Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(t("phrasebook.keep"), fontSize = 11.sp)
    }
}

/**
 * How formal this line is, as a badge.
 *
 * Everyday English fails on register long before it fails on grammar — "I would be delighted to
 * accompany you" to a labmate is perfectly correct and completely wrong — so the label rides on
 * every expression rather than living in a lesson nobody reads.
 */
@Composable
private fun RegisterBadge(register: PhraseRegister) {
    val t = LocalTranslate.current
    val color = when (register) {
        PhraseRegister.CASUAL -> MaterialTheme.colorScheme.tertiary
        PhraseRegister.NEUTRAL -> MaterialTheme.colorScheme.secondary
        PhraseRegister.POLITE -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(
            t("phrasebook.register.${register.key}"),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.tertiary, CircleShape)
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
