package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.LifetimeStatsEngine
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel

/** A deliberately separate home surface so the all-features dashboard remains pixel-stable. */
@Composable
fun EverydayDashboardScreen(
    viewModel: MainViewModel,
    onStartConversation: () -> Unit,
    onOpenPracticeMode: (String) -> Unit,
    onOpenPronunciation: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val t = LocalTranslate.current
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val voiceBackend by viewModel.voiceBackend.collectAsStateWithLifecycle()
    val completedSessions = remember(sessions) { sessions.filter { it.deletedAt == null } }
    val everydaySessions = remember(completedSessions) {
        completedSessions.count { it.mode in setOf("survival", "listening", "lounge") }
    }
    val streak = remember(completedSessions) { LifetimeStatsEngine.computeStreak(completedSessions) }
    val sayItProgress by viewModel.sayItProgress.collectAsStateWithLifecycle()
    val masteredPhrases = remember(sayItProgress) { sayItProgress.count { it.value.mastered } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Bottom room for the Home coach FAB, as on the clinical Home (DashboardScreen).
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                t("Everyday English"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t("Build confidence through real-life conversations, focused listening, and speaking practice."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("START HERE", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Have a real-life conversation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (voiceBackend == "demo")
                            t("Try a prepared Survival English conversation. No API key or microphone is required.")
                        else
                            t("Step into an everyday situation and respond naturally in your own words."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onStartConversation, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (voiceBackend == "demo") t("Start an everyday demo") else t("Start a conversation"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard(t("Everyday sessions"), everydaySessions.toString(), Icons.Default.Forum, Modifier.weight(1f))
                StatCard(t("Day streak"), streak.toString(), Icons.Default.LocalFireDepartment, Modifier.weight(1f))
                // Sessions and streak both measure showing up. This is the only tile that measures
                // something kept — without it a learner who practises daily has no evidence that
                // anything accumulated, which is the complaint everyday practice always attracts.
                StatCard(t("Expressions learned"), masteredPhrases.toString(), Icons.Default.RecordVoiceOver, Modifier.weight(1f))
            }
        }

        item {
            Text("Explore practice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.Explore,
                title = t("Survival English"),
                body = t("Longer, changing scenes that can move forward or introduce someone new."),
                onClick = { onOpenPracticeMode("survival") }
            )
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.Headphones,
                title = t("Listening Lab"),
                body = t("Train accents, numbers, times, and the details people say quickly."),
                onClick = { onOpenPracticeMode("listening") }
            )
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.Forum,
                title = t("tab.free_talk"),
                body = t("Talk about anything you like. Your partner keeps every reply to one short line, so you do most of the talking."),
                onClick = { onOpenPracticeMode("free_talk") }
            )
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.Coffee,
                title = t("Free English Lounge"),
                body = t("Keep a casual conversation going, discuss an article, or practise disagreement."),
                onClick = { onOpenPracticeMode("lounge") }
            )
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.RecordVoiceOver,
                title = t("Say It: Everyday"),
                body = t("Drill the expressions that keep a conversation going — and the ones you kept from your own feedback."),
                onClick = { onOpenPracticeMode("sayit_everyday") }
            )
        }
        item {
            EverydayFeatureCard(
                icon = Icons.Default.Mic,
                title = t("Pronunciation Lab"),
                body = t("Work on clarity and the sounds that most affect understanding."),
                onClick = onOpenPronunciation
            )
        }
        item {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Change practice experience")
            }
        }
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EverydayFeatureCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    val t = LocalTranslate.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = t("Open") + " $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
