package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The nursing half of a nursing session's Overview: the verdict, the OET estimate or rubric
 * criteria, the brief's task list ticked off, safety flags, deterministic checks, and stronger
 * versions of the learner's weakest lines. Everything here was computed by
 * analysis/NursingScorecard.kt; this file only renders it.
 */
@Composable
internal fun NursingScorecardContent(scorecardJson: String) {
    val t = LocalTranslate.current
    val card = remember(scorecardJson) { runCatching { JSONObject(scorecardJson) }.getOrNull() } ?: return
    if (!card.has("framework")) return
    val oet = card.optJSONObject("oet")
    val verdict = card.optJSONObject("verdict")
    val criteria = card.optJSONArray("criteria").objects()
    val tasks = card.optJSONArray("task_items").objects()
    val flags = card.optJSONArray("safety_flags").objects()
    val checks = card.optJSONArray("checks").objects()
    val upgrades = card.optJSONArray("line_upgrades").objects()
    val jargon = card.optJSONArray("jargon").objects()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VerdictBanner(verdict, oet)

        if (flags.isNotEmpty()) {
            SectionTitle(t("Safety"))
            flags.forEach { flag ->
                val critical = flag.optString("severity") == "critical"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (critical) Icons.Default.Error else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (critical) MaterialTheme.colorScheme.error else Color(0xFFB26A00),
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(flag.optString("issue"), fontWeight = FontWeight.SemiBold)
                        flag.optString("quote").takeIf(String::isNotBlank)?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                        }
                    }
                }
            }
        }

        if (criteria.any { !it.isNull("score") }) {
            SectionTitle(if (oet != null || card.optString("framework") == "oet") t("OET criteria") else t("Nursing rubric"))
            if (card.optString("framework") == "oet") {
                val linguistic = criteria.filter { it.optString("group") == "linguistic" }
                val clinical = criteria.filter { it.optString("group") == "clinical" }
                SubTitle(t("Linguistic (0–6)"))
                linguistic.forEach { CriterionRow(it) }
                SubTitle(t("Clinical communication (0–3)"))
                clinical.forEach { CriterionRow(it) }
            } else {
                criteria.forEach { CriterionRow(it) }
            }
        }

        if (tasks.isNotEmpty()) {
            val completion = card.optDouble("task_completion", Double.NaN)
            SectionTitle(
                t("Your brief's tasks") +
                    if (completion.isFinite()) " · ${(completion * 100).roundToInt()}%" else ""
            )
            tasks.forEach { task ->
                val status = task.optString("status")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusIcon(status)
                    Column(Modifier.weight(1f)) {
                        Text(task.optString("item"), style = MaterialTheme.typography.bodyMedium)
                        task.optString("evidence").takeIf { it.isNotBlank() && status != "done" }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (checks.isNotEmpty()) {
            val passed = checks.count { it.optBoolean("passed") }
            SectionTitle(t("Nurse communication checks") + " · $passed/${checks.size}")
            Text(
                t("Detected from your words alone — evidence the behaviour happened, not a grade."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            checks.forEach { CheckRow(it) }
        }

        if (jargon.isNotEmpty()) {
            SectionTitle(t("Plain-language swaps"))
            jargon.forEach { hit ->
                Text(
                    "“${hit.optString("term")}” → “${hit.optString("plain")}”" +
                        (hit.optInt("count", 1).takeIf { it > 1 }?.let { "  ×$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (upgrades.isNotEmpty()) {
            SectionTitle(t("Say it better"))
            upgrades.forEach { upgrade ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        upgrade.optString("original").takeIf(String::isNotBlank)?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("→ ${upgrade.optString("better")}", fontWeight = FontWeight.SemiBold)
                        upgrade.optString("why").takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (card.optString("framework") == "oet") {
            Text(
                t("OET estimates are practice guidance from this app's own conversion, not an official OET result. Most regulators and CGFNS VisaScreen ask for Grade B (350) in Speaking — check your target body's current requirement."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun VerdictBanner(verdict: JSONObject?, oet: JSONObject?) {
    val t = LocalTranslate.current
    val level = verdict?.optString("level").orEmpty()
    val (container, content) = when (level) {
        "ready" -> Color(0xFFE3F4E8) to Color(0xFF1B5E20)
        "close" -> Color(0xFFFFF4DC) to Color(0xFF7A4F00)
        "not_yet" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (oet != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        t("OET grade") + " " + oet.optString("grade"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                    Text(
                        "≈ ${oet.optInt("scaled")} / 500",
                        style = MaterialTheme.typography.titleMedium,
                        color = content,
                    )
                }
                Text(
                    t("Criteria total") + " ${oet.optInt("raw")}/${oet.optInt("raw_max")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                )
            } else {
                Text(
                    when (level) {
                        "ready" -> t("Ready")
                        "close" -> t("Nearly there")
                        "not_yet" -> t("Not yet")
                        "too_short" -> t("Too short to judge")
                        else -> t("Nursing feedback")
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
            }
            verdict?.optString("headline")?.takeIf(String::isNotBlank)?.let {
                Text(t(it), color = content)
            }
            oet?.optJSONArray("weakest")?.let { arr ->
                val names = (0 until arr.length()).map { t(arr.optString(it)) }.filter(String::isNotBlank)
                if (names.isNotEmpty() && level != "ready") {
                    Text(t("Focus next:") + " " + names.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = content)
                }
            }
        }
    }
}

@Composable
private fun CriterionRow(criterion: JSONObject) {
    val t = LocalTranslate.current
    val max = criterion.optInt("max", 100).coerceAtLeast(1)
    val score = criterion.optDouble("score", Double.NaN)
    var showEvidence by rememberSaveable(criterion.optString("key")) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t(criterion.optString("label")), modifier = Modifier.weight(1f))
            Text(
                if (score.isFinite()) "${score.roundToInt()}/$max" else "—",
                fontWeight = FontWeight.Bold,
            )
        }
        LinearProgressIndicator(
            progress = { if (score.isFinite()) (score / max).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        val evidence = criterion.optString("evidence")
        if (evidence.isNotBlank()) {
            if (showEvidence) {
                Text(evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = { showEvidence = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(t("Why?"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: JSONObject) {
    val t = LocalTranslate.current
    val passed = check.optBoolean("passed")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusIcon(if (passed) "done" else "missed")
        Column(Modifier.weight(1f)) {
            Text(t(check.optString("label")), fontWeight = FontWeight.SemiBold)
            check.optString("detail").takeIf(String::isNotBlank)?.let {
                Text(t(it), style = MaterialTheme.typography.bodySmall)
            }
            if (!passed) {
                check.optString("tip").takeIf(String::isNotBlank)?.let {
                    Text(
                        t("Try:") + " " + it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: String) {
    val (icon, tint) = when (status) {
        "done" -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
        "partial" -> Icons.Default.RemoveCircle to Color(0xFFB26A00)
        "missed" -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> Icons.Default.RemoveCircle to MaterialTheme.colorScheme.outline
    }
    Icon(icon, contentDescription = status, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
