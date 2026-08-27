package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.medvoicetrainer.ui.PracticeExperience

@Composable
fun PracticeExperienceScreen(onSelect: (PracticeExperience) -> Unit) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "What would you like to practice?",
                style = MaterialTheme.typography.headlineMedium,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Choose what appears in the app. Your choice can be changed anytime in Settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            ExperienceChoiceCard(
                title = t("Patient & everyday English"),
                badge = "ALL FEATURES",
                description = "Practice conversations with patients and caregivers, plus everyday speaking and listening. Includes every practice mode and feature.",
                supporting = "Recommended for medical students, IMGs, doctors, and other healthcare professionals.",
                icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
                prominent = true,
                onClick = { onSelect(PracticeExperience.ALL_FEATURES) }
            )
            Spacer(Modifier.height(14.dp))
            ExperienceChoiceCard(
                title = t("Everyday English only"),
                badge = null,
                description = "Focus on real-life conversations, listening, and speaking. Patient-communication practice and related tools will be hidden.",
                supporting = "Your learning history and saved reviews always remain available.",
                icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                prominent = false,
                onClick = { onSelect(PracticeExperience.EVERYDAY_ENGLISH) }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "This only changes which features are shown. It never deletes your progress.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PracticeExperiencePromptDialog(
    onSelect: (PracticeExperience) -> Unit,
    onNotNow: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    Dialog(onDismissRequest = onNotNow) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(t("Personalize your practice"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choose whether you want all patient-communication and everyday features, or a simpler everyday-English experience.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                ExperienceChoiceCard(
                    title = t("Patient & everyday English"),
                    badge = "ALL FEATURES",
                    description = "Patient conversations, everyday English, and every feature.",
                    supporting = "Best for healthcare learners and professionals.",
                    icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
                    prominent = true,
                    compact = true,
                    onClick = { onSelect(PracticeExperience.ALL_FEATURES) }
                )
                Spacer(Modifier.height(10.dp))
                ExperienceChoiceCard(
                    title = t("Everyday English only"),
                    badge = null,
                    description = "Real-life speaking and listening without patient-communication tools.",
                    supporting = "History and saved reviews stay visible.",
                    icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                    prominent = false,
                    compact = true,
                    onClick = { onSelect(PracticeExperience.EVERYDAY_ENGLISH) }
                )
                TextButton(onClick = onNotNow, modifier = Modifier.align(Alignment.End)) {
                    Text(t("Not now"))
                }
            }
        }
    }
}

@Composable
private fun ExperienceChoiceCard(
    title: String,
    badge: String?,
    description: String,
    supporting: String,
    icon: @Composable () -> Unit,
    prominent: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val border = if (prominent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = border,
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (prominent) 2.dp else 0.dp
    ) {
        Column(modifier = Modifier.padding(if (compact) 15.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (compact) 38.dp else 44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) { icon() }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    if (badge != null) {
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                badge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Icon(Icons.Default.ChevronRight, contentDescription = t("Choose") + " $title", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(if (compact) 9.dp else 14.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
