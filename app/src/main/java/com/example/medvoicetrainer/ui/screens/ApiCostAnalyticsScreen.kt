package com.example.medvoicetrainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.medvoicetrainer.analysis.CostTracker
import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.db.ApiUsageEventEntity
import com.example.medvoicetrainer.ui.LocalTranslate

@Composable
fun ApiCostAnalyticsScreen(
    sessions: List<SessionEntity>,
    apiUsageEvents: List<ApiUsageEventEntity> = emptyList(),
    onClose: () -> Unit
) {
    val t = LocalTranslate.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var showPricingReference by remember { mutableStateOf(false) }

    // Compute cost reports for all analyzed sessions
    val reports = remember(sessions) {
        sessions.filter {
            !it.summaryFeedback.isNullOrBlank() ||
                it.rawClaudeResponse?.isNotBlank() == true ||
                (it.voiceBackend.lowercase() in setOf("gemini", "openai") && it.durationSeconds > 0)
        }
            .map { CostTracker.buildReportFromSession(it) }
            .sortedByDescending { it.createdAt }
    }
    // buildReportFromSession only carries what SessionCostReport models (no voice model name) —
    // look the originating row back up by id for the couple of extra fields the per-session card
    // needs, instead of growing that shared report class for one screen's display purposes.
    val sessionsById = remember(sessions) { sessions.associateBy { it.id } }

    val otherApiCostUsd = remember(apiUsageEvents) { apiUsageEvents.sumOf { it.costUsd } }
    val totalCostUsd = remember(reports, otherApiCostUsd) {
        reports.sumOf { it.totalCostUsd } + otherApiCostUsd
    }
    val totalAnalysisCostUsd = remember(reports) { reports.sumOf { it.claudeCostUsd } }
    val totalVoiceCostUsd = remember(reports) { reports.sumOf { it.voiceCostUsd } }
    val totalInputTokens = remember(reports, apiUsageEvents) {
        reports.sumOf { it.claudeInputTokens + it.voiceEstimatedInputTokens } +
            apiUsageEvents.sumOf { it.inputTokens }
    }
    val totalOutputTokens = remember(reports, apiUsageEvents) {
        reports.sumOf { it.claudeOutputTokens + it.voiceEstimatedOutputTokens } +
            apiUsageEvents.sumOf { it.outputTokens }
    }
    val containsEstimates = remember(reports, sessionsById, apiUsageEvents) {
        reports.any { report -> sessionsById[report.sessionId]?.costEstimated != false } ||
            apiUsageEvents.any { it.estimated }
    }

    // The whole screen is one scrollable list, like the Session History tab: the header, KPI cards,
    // token card and "other API calls" summary used to be fixed above a weight(1f) LazyColumn, which
    // squeezed the session breakdown into a sliver barely one card tall. Putting every section in as
    // a list item lets the entire page scroll together.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Preferences hosts this screen outside the app Scaffold, while History hosts it
            // inside one. Consumed insets are zero in the latter case, so this is safe for both.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = t("cost.title"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = t("cost.subtitle"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.Close, contentDescription = t("common.close"))
                }
            }
        }

        // Top KPI Cards Row
        item {
            Row(
                // Without this, each KpiCard sizes to its own content and the two cards end up
                // visibly different heights whenever one title/subtitle wraps more than the other
                // (e.g. the "· Estimated" suffix below). IntrinsicSize.Max + fillMaxHeight on each
                // card makes both stretch to match the taller one.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KpiCard(
                    title = t("cost.total_cost_title") + if (containsEstimates) " · " + t("Estimated") else "",
                    value = String.format("$%.4f", totalCostUsd),
                    subtitle = t("cost.analysis_voice_subtitle_format")
                        .format(totalAnalysisCostUsd, totalVoiceCostUsd) +
                        if (otherApiCostUsd > 0.0) " / Other: $%.4f".format(otherApiCostUsd) else "",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = Icons.Default.MonetizationOn,
                    accentColor = MaterialTheme.colorScheme.primary
                )
                KpiCard(
                    title = t("cost.sessions_analyzed_title"),
                    value = t("cost.sessions_value_format").format(reports.size),
                    subtitle = t("cost.avg_per_session_format").format(if (reports.isNotEmpty()) totalCostUsd / reports.size else 0.0),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = Icons.Default.Assessment,
                    accentColor = MaterialTheme.colorScheme.secondary
                )
            }
        }

        // Token Consumption Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = t("cost.token_usage_title"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = t("cost.total_tokens_format").format(totalInputTokens + totalOutputTokens),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        TokenMetricItem(t("cost.input_token_label"), totalInputTokens, MaterialTheme.colorScheme.primary)
                        TokenMetricItem(t("cost.output_token_label"), totalOutputTokens, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        if (apiUsageEvents.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            t("Other API calls"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        apiUsageEvents.take(3).forEach { event ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${event.provider} · ${event.operation}" +
                                        if (event.estimated) " · " + t("Estimated") else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "$%.4f".format(event.costUsd),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // The actual "what did I use" history is the primary content of this screen; the static
        // provider price sheet is reference material, not usage data, so it lives one tap away
        // behind a plain text link instead of competing with real sessions for a top-level tab
        // (previously 4 equal-weight tabs, whose long labels also wrapped awkwardly here).
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = t("cost.tab_sessions"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // On a phone this used to force the title and two action labels into one row,
                // reducing the last label to a single-character-wide vertical column.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Everything on this screen is a local estimate derived from token counts;
                    // AI Studio's own usage page is the authoritative number, so link straight out
                    // to it rather than leaving the learner to guess whether the estimate is real.
                    TextButton(
                        onClick = { uriHandler.openUri(GOOGLE_AI_STUDIO_USAGE_URL) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            t("cost.ai_studio_link"),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { showPricingReference = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            t("cost.pricing_reference_link"),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (reports.isEmpty()) {
            item { EmptySessionCostState() }
        } else {
            items(reports, key = { it.sessionId ?: it.createdAt.hashCode() }) { report ->
                SessionCostCard(report, sessionsById[report.sessionId])
            }
        }

        // The bottom navigation bar and the floating mic button overlay this screen, so the last
        // card needs room to clear them when scrolled to the end.
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (showPricingReference) {
        PricingReferenceDialog(onDismiss = { showPricingReference = false })
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Without weight(1f), this Text and the icon are both measured at their own
                    // unconstrained width; the "· Estimated" suffix made the title long enough to
                    // get clipped by the card's rounded edge instead of wrapping.
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TokenMetricItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = String.format("%,d", count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun EmptySessionCostState() {
    val t = LocalTranslate.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HistoryToggleOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = t("cost.empty_sessions"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionCostCard(report: CostTracker.SessionCostReport, session: SessionEntity?) {
    val t = LocalTranslate.current
    // Saveable so an expanded card stays expanded after it scrolls out of the list and back in.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val usedAnalysisApi = report.analysisProvider != "demo"
    val usedVoiceApi = report.voiceCostUsd > 0.0 || report.voiceBackend.lowercase() in setOf("gemini", "openai")
    val estimated = session?.costEstimated != false
    // report.analysisProvider is "demo" both for a genuine mock/demo session AND for a real
    // (paid) voice session where analysis was skipped or unavailable (e.g. "Skip analysis (keep
    // transcript)" or no analysis API key configured) — those aren't the same thing, and labeling
    // the latter "Demo" falsely implies the whole session, including its real voice cost, was free.
    val badgeProvider = if (report.analysisProvider == "demo" && usedVoiceApi) "skipped" else report.analysisProvider

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProviderBadge(badgeProvider)
                    Text(
                        text = if (report.caseName.isNotBlank()) report.caseName else "Encounter #${report.sessionId ?: ""}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$%.4f".format(report.totalCostUsd),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (estimated) {
                        Text(
                            text = t("Estimated"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }

            Spacer(Modifier.height(8.dp))
            CostBreakdownRow(
                icon = "🧠",
                label = t("cost.section_analysis"),
                detail = if (usedAnalysisApi) {
                    t("cost.model_tokens_format").format(
                        report.analysisModel.ifBlank { "default" },
                        report.claudeInputTokens, report.claudeOutputTokens
                    )
                } else {
                    t("cost.no_api_used")
                },
                cost = report.claudeCostUsd,
                muted = !usedAnalysisApi
            )
            Spacer(Modifier.height(4.dp))
            CostBreakdownRow(
                icon = "🎙",
                label = t("cost.section_voice"),
                detail = if (usedVoiceApi) {
                    if (session?.voiceUsageExact == true) {
                        "${session.voiceModel?.takeIf { it.isNotBlank() } ?: report.voiceBackend} | " +
                            "In %,d / Out %,d tokens".format(
                                report.voiceEstimatedInputTokens,
                                report.voiceEstimatedOutputTokens,
                            )
                    } else {
                        t("cost.voice_model_seconds_format").format(
                            session?.voiceModel?.takeIf { it.isNotBlank() } ?: report.voiceBackend,
                            report.voiceAudioSeconds
                        ) + " · " + t("Estimated")
                    }
                } else {
                    t("cost.no_voice_cost")
                },
                cost = report.voiceCostUsd,
                muted = !usedVoiceApi
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("cost.duration_format").format(report.durationSeconds / 60, report.durationSeconds % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (usedAnalysisApi && report.claudeCachedInputTokens > 0) {
                        Text(
                            t("cost.cached_tokens_format").format(report.claudeCachedInputTokens),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    report.notes.forEach { note ->
                        Text(
                            "• $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CostBreakdownRow(icon: String, label: String, detail: String, cost: Double, muted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
                    // A single line cut the "In X / Out Y tokens" half off for longer model names.
                    // Two lines gives it room to wrap instead of getting ellipsized mid-number.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = if (muted) "$0.0000" else "$%.4f".format(cost),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ProviderBadge(provider: String) {
    val t = LocalTranslate.current
    val (color, label) = when (provider.lowercase()) {
        "claude" -> MaterialTheme.colorScheme.tertiary to "Claude"
        "gemini" -> MaterialTheme.colorScheme.primary to "Gemini"
        "openai" -> MaterialTheme.colorScheme.secondary to "OpenAI"
        "demo" -> MaterialTheme.colorScheme.onSurfaceVariant to "Demo"
        "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant to t("No Analysis")
        else -> MaterialTheme.colorScheme.onSurfaceVariant to provider.uppercase()
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Static per-1M-token reference pricing for all three providers — informational, not tied to this
 * learner's own usage, so it lives behind an explicit link/dialog rather than sharing top billing
 * with the real session history above.
 */
@Composable
private fun PricingReferenceDialog(onDismiss: () -> Unit) {
    val t = LocalTranslate.current
    var selectedProvider by remember { mutableIntStateOf(0) }
    val providers = listOf(
        Triple("claude", t("cost.tab_claude_pricing"), MaterialTheme.colorScheme.tertiary),
        Triple("gemini", t("cost.tab_gemini_pricing"), MaterialTheme.colorScheme.primary),
        Triple("openai", t("cost.tab_openai_pricing"), MaterialTheme.colorScheme.secondary)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t("cost.pricing_reference_title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = t("common.close"))
                    }
                }
                ScrollableTabRow(
                    selectedTabIndex = selectedProvider,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    providers.forEachIndexed { index, (_, label, _) ->
                        Tab(
                            selected = selectedProvider == index,
                            onClick = { selectedProvider = index },
                            text = { Text(label, maxLines = 1, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
                val (providerId, title, accentColor) = providers[selectedProvider]
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    ProviderPricingTable(providerId, title, accentColor)
                }
            }
        }
    }
}

@Composable
private fun ProviderPricingTable(provider: String, title: String, accentColor: Color) {
    val t = LocalTranslate.current
    val models = remember(provider) {
        CostTracker.ALL_MODEL_PRICING.filter { it.provider.equals(provider, ignoreCase = true) }
    }
    val voiceModels = remember(provider) {
        CostTracker.VOICE_MODEL_PRICING.filter { it.provider.equals(provider, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accentColor.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                    Text(
                        text = t("cost.pricing_table_header_format").format(title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }

        items(voiceModels) { pricing ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = pricing.aliases.firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = t("Realtime voice · standard paid-tier list price"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PricingRateCell(t("Text in"), "$%.2f / 1M".format(pricing.textInputPricePer1M))
                        PricingRateCell(t("Audio in"), "$%.2f / 1M".format(pricing.audioInputPricePer1M))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PricingRateCell(t("Text out"), "$%.2f / 1M".format(pricing.textOutputPricePer1M))
                        PricingRateCell(t("Audio out"), "$%.2f / 1M".format(pricing.audioOutputPricePer1M))
                    }
                }
            }
        }

        items(models) { pricing ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pricing.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (pricing.aliases.isNotEmpty()) {
                            Text(
                                text = pricing.aliases.first(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        PricingRateCell(t("cost.input_rate_label"), "$%.4f / 1M".format(pricing.inputPricePer1M))
                        PricingRateCell(t("cost.output_rate_label"), "$%.4f / 1M".format(pricing.outputPricePer1M))
                        PricingRateCell(
                            t("cost.cache_rate_label"),
                            pricing.cachePricePer1M?.let { "$%.4f / 1M".format(it) } ?: t("cost.rate_not_applicable")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PricingRateCell(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
