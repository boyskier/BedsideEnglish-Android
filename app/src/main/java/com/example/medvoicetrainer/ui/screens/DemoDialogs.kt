package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.medvoicetrainer.analysis.DemoCaseInfo
import com.example.medvoicetrainer.analysis.DemoTour

/**
 * Ported from app/ui/demo_intro_dialog.py's show_demo_intro_dialog: shown before every
 * typed-Demo session so the user picks one of the 3 scripted patients, with a "✓ Played" mark
 * for cases already completed and the "tour is short, then it's your own key" contract line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoIntroDialog(
    completedIds: Set<String>,
    defaultCaseId: String = DemoTour.DEMO_CASES.firstOrNull()?.id ?: "chest_pain",
    onCancel: () -> Unit,
    onStart: (caseId: String) -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    var selected by remember { mutableStateOf(defaultCaseId) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(20.dp, 16.dp)
                ) {
                    Text(
                        t("demo.intro.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            t("demo.intro.chip_type"),
                            t("demo.intro.chip_scripted"),
                            t("demo.intro.chip_analysis")
                        ).forEach { chip ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    chip,
                                    modifier = Modifier.padding(8.dp, 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp, 12.dp)
                        .weight(1f, fill = false)
                ) {
                    Text(t("demo.intro.pick_scenario"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("demo.intro_tour_contract").replace("{total}", DemoTour.DEMO_CASES.size.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    DemoTour.DEMO_CASES.forEach { case ->
                        DemoCaseCard(
                            case = localizedDemoCase(case, t),
                            played = case.id in completedIds,
                            isSelected = selected == case.id,
                            onClick = { selected = case.id }
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        t("demo.intro.unlock_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onCancel) { Text(t("demo.intro.cancel")) }
                    Button(onClick = { onStart(selected) }) { Text(t("demo.intro.start")) }
                }
            }
        }
    }
}

@Composable
private fun localizedDemoCase(
    case: DemoCaseInfo,
    t: (String) -> String
): DemoCaseInfo = case.copy(
    label = t("demo.case.${case.id}.label"),
    description = t("demo.case.${case.id}.description"),
    focus = t("demo.case.${case.id}.focus")
)

@Composable
private fun DemoCaseCard(case: DemoCaseInfo, played: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = isSelected, onClick = onClick)
                Text(
                    "${case.emoji}  ${case.label}" + if (played) "  ${t("demo.intro_played")}" else "",
                    fontWeight = FontWeight.Bold,
                    color = if (played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                case.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 40.dp)
            )
            Text(
                "${t("demo.intro.focus")}: ${case.focus}",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
    }
}

/**
 * Ported from app/ui/demo_next_dialog.py's show_demo_next_dialog: "Your demo results & what's
 * next" — shown after a typed-demo session's feedback. One screen, three ranked choices: unlock
 * now (free key), keep touring (next scripted patient), or decide later. See
 * ONBOARDING_POST_DEMO_FLOW.md §3.3. Records the choice via DemoTour.markDecisionShown so the
 * frequency guard (shouldAutoShowDecision) can suppress a same-day repeat after "decide later".
 */
@Composable
fun DemoNextDialog(
    tourComplete: Boolean,
    doneCount: Int,
    totalCount: Int,
    everyday: Boolean,
    onUnlockNow: () -> Unit,
    onNextDemoPatient: (() -> Unit)?,
    onDecideLater: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Dialog(onDismissRequest = onDecideLater) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(20.dp, 16.dp)
                ) {
                    Text(
                        if (everyday) t("Nice work — you completed an everyday conversation")
                        else if (tourComplete) t("demo_next.complete_title") else t("demo_next.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        if (everyday) t("Try another prepared situation, or connect Gemini for live, open-ended voice practice.")
                        else if (tourComplete) t("demo_next.complete_subtitle").replace("{total}", totalCount.toString())
                        else t("demo_next.subtitle_progress")
                            .replace("{done}", doneCount.toString())
                            .replace("{total}", totalCount.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp, 14.dp)
                        .weight(1f, fill = false)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = com.example.medvoicetrainer.ui.theme.SuccessGreen.copy(alpha = 0.15f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    t("demo_next.stats_header").removePrefix("✓ "),
                                    fontWeight = FontWeight.Bold,
                                    color = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t("demo_next.stat_fallback"),
                                color = com.example.medvoicetrainer.ui.theme.SuccessGreenStrong
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(if (everyday) t("Connect live voice to unlock") else t("demo_next.unlocks_header"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val locked = if (everyday) {
                        listOf(
                            t("Open-ended AI conversations"),
                            t("Detailed everyday-English feedback"),
                            t("Personal corrections and saved reviews"),
                            t("Pronunciation and shadowing practice")
                        )
                    } else {
                        listOf(
                            t("demo_next.unlock_voice"),
                            t("demo_next.unlock_scores"),
                            t("demo_next.unlock_corrections"),
                            t("demo_next.unlock_soap"),
                            t("demo_next.unlock_shadowing")
                        )
                    }
                    locked.forEach { item ->
                        Row {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(item, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onUnlockNow, modifier = Modifier.fillMaxWidth()) {
                        Text(if (everyday) t("Connect live voice") else t("demo_next.cta_unlock"))
                    }

                    if ((everyday || !tourComplete) && onNextDemoPatient != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onNextDemoPatient, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (everyday) t("Try another everyday scenario")
                                else t("demo_next.cta_next").replace("{n}", (totalCount - doneCount).toString())
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDecideLater, modifier = Modifier.fillMaxWidth()) {
                        Text(t("demo_next.cta_later"))
                    }
                }
            }
        }
    }
}
