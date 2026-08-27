package com.example.medvoicetrainer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.example.medvoicetrainer.analysis.DemoTour
import com.example.medvoicetrainer.analysis.UnlockSheet
import com.example.medvoicetrainer.api.ProviderStatus
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.ui.PracticeExperience
import com.example.medvoicetrainer.ui.components.InlineDisclosure
import java.util.concurrent.atomic.AtomicBoolean

internal const val GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * Google AI Studio's own usage/quota dashboard — the authoritative view of what a Gemini key has
 * actually spent, next to which this app's Cost Analytics screen is only a local estimate. Linked
 * from Preferences (beside the Gemini key) and from the Cost Analytics header so a learner
 * worried about burning quota can check the real number without hunting for the URL.
 */
internal const val GOOGLE_AI_STUDIO_USAGE_URL = "https://aistudio.google.com/usage"
private const val TOTAL_STEPS = 3

private data class OnboardingLanguage(val code: String, val label: String)

private val onboardingLanguages = listOf(
    OnboardingLanguage("ko", "한국어"),
    OnboardingLanguage("en", "English"),
    OnboardingLanguage("es", "Español"),
    OnboardingLanguage("zh", "中文"),
    OnboardingLanguage("ar", "العربية"),
    OnboardingLanguage("hi", "हिन्दी"),
    OnboardingLanguage("pt", "Português"),
    OnboardingLanguage("tl", "Filipino"),
    OnboardingLanguage("id", "Bahasa Indonesia"),
    OnboardingLanguage("ja", "日本語"),
    OnboardingLanguage("vi", "Tiếng Việt")
)

// Every bundled locale has translations for the welcome screen, so expose each one here. Later
// onboarding steps retain English fallbacks where a locale is intentionally incomplete.
private val onboardingDisplayLanguages = listOf(
    OnboardingLanguage("ko", "\uD55C\uAD6D\uC5B4"),
    OnboardingLanguage("en", "English"),
    OnboardingLanguage("es", "Espa\u00F1ol"),
    OnboardingLanguage("zh", "\u4E2D\u6587"),
    OnboardingLanguage("ar", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
    OnboardingLanguage("hi", "\u0939\u093F\u0928\u094D\u0926\u0940"),
    OnboardingLanguage("pt", "Portugu\u00EAs"),
    OnboardingLanguage("tl", "Filipino"),
    OnboardingLanguage("id", "Bahasa Indonesia"),
    OnboardingLanguage("ja", "日本語"),
    OnboardingLanguage("vi", "Tiếng Việt")
)

/**
 * First-run journey for a learner who may never have heard the term "API key".
 *
 * 1. Explain the outcome in the learner's language.
 * 2. Preview the speak → patient → feedback loop and offer a real keyless patient.
 * 3. Reuse the same guided Gemini connection screen both here and after the demo.
 *
 * API-key drafts intentionally use [remember], never rememberSaveable: secrets must not be
 * copied into Android's saved-instance-state Bundle. The verified key is persisted only when the
 * learner explicitly finishes, into Repository's EncryptedSharedPreferences.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onComplete: () -> Unit,
    startAtConnect: Boolean = false,
    onDismiss: () -> Unit = onComplete
) {
    val currentL1 by viewModel.nativeLanguage.collectAsStateWithLifecycle()
    val currentGeminiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val savedOnboardingStep by viewModel.onboardingStep.collectAsStateWithLifecycle()
    val verificationMap by viewModel.providerStatusMap.collectAsStateWithLifecycle()
    val practiceExperience by viewModel.practiceExperience.collectAsStateWithLifecycle()
    val everydayOnly = practiceExperience == PracticeExperience.EVERYDAY_ENGLISH

    var step by rememberSaveable(startAtConnect, currentGeminiKey) {
        mutableStateOf(
            when {
                startAtConnect -> 2
                currentGeminiKey.isNotBlank() && savedOnboardingStep == 1 -> 2
                else -> savedOnboardingStep
            }
        )
    }
    var tempL1 by rememberSaveable { mutableStateOf(currentL1) }
    // This value belongs only to the wizard.  The main app is intentionally English-only.
    var tempUiLanguage by rememberSaveable { mutableStateOf("en") }
    var translationRevision by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val t: (String) -> String = { key ->
        // Reading the revision makes the wizard recompose after an asynchronously loaded locale
        // is published; until then the existing English fallback remains visible.
        translationRevision
        com.example.medvoicetrainer.ui.I18n.t(key, tempUiLanguage)
    }
    var tempGeminiKey by remember(currentGeminiKey) { mutableStateOf(currentGeminiKey) }
    var submittedKey by remember { mutableStateOf<String?>(null) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showFormatError by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf(false) }
    var isFinishing by remember { mutableStateOf(false) }
    val completionGate = remember { AtomicBoolean(false) }

    val normalizedKey = remember(tempGeminiKey) {
        UnlockSheet.normalizePastedGeminiKey(tempGeminiKey)
    }
    val verification = verificationMap["gemini"].takeIf { submittedKey == normalizedKey }
    val isVerifying = verification?.status == ProviderStatus.VERIFYING
    val isVerified = verification?.status == ProviderStatus.VERIFIED
    val showSampleScenario = currentGeminiKey.isBlank() && normalizedKey.isBlank()
    val pageTitle = when (step) {
        0 -> if (everydayOnly) "Speak more confidently in real life" else t("onboarding.v2.welcome_title")
        1 -> if (everydayOnly) "Try an everyday conversation" else t("onboarding.v2.preview_title")
        else -> if (everydayOnly) "Connect live AI conversations" else t("onboarding.v2.connect_title")
    }
    val hostView = LocalView.current
    val layoutDirection = if (tempUiLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(tempUiLanguage) {
        com.example.medvoicetrainer.ui.I18n.ensureLanguage(context, tempUiLanguage)
        translationRevision++
    }

    LaunchedEffect(step, startAtConnect) {
        if (!startAtConnect) viewModel.updateOnboardingStep(step)
    }

    // AnimatedContent replaces the page below a fixed header/footer. Explicitly announce the
    // new pane title so TalkBack users do not remain stranded on the button they just pressed.
    LaunchedEffect(step, pageTitle) {
        hostView.announceForAccessibility(pageTitle)
    }

    fun editKey(value: String) {
        tempGeminiKey = value
        submittedKey = null
        showFormatError = false
        activationError = false
        viewModel.clearProviderVerification("gemini")
    }

    fun verifyDraft() {
        if (completionGate.get()) return
        val clean = UnlockSheet.normalizePastedGeminiKey(tempGeminiKey)
        tempGeminiKey = clean
        activationError = false
        if (!UnlockSheet.looksLikeGeminiKey(clean)) {
            submittedKey = null
            showFormatError = true
            viewModel.clearProviderVerification("gemini")
            return
        }
        showFormatError = false
        submittedKey = clean
        viewModel.verifyProviderKey("gemini", clean)
    }

    fun claimCompletion(): Boolean {
        if (!completionGate.compareAndSet(false, true)) return false
        isFinishing = true
        return true
    }

    fun releaseCompletion() {
        completionGate.set(false)
        isFinishing = false
    }

    fun finishWithDemo() {
        if (!claimCompletion()) return
        try {
            viewModel.completeOnboardingWithDemo(tempL1)
            if (everydayOnly) {
                viewModel.startEverydayDemoSession()
            } else {
                val completed = viewModel.getCompletedDemoCaseIds()
                val nextDemoId = DemoTour.getFirstUnseenDemoCaseId(completed)
                viewModel.startDemoSession(nextDemoId)
            }
            onComplete()
        } catch (throwable: Throwable) {
            releaseCompletion()
            throw throwable
        }
    }

    fun finishWithVerifiedKey() {
        if (!isVerified || submittedKey != normalizedKey) return
        if (!claimCompletion()) return
        try {
            viewModel.completeOnboardingWithGeminiKey(normalizedKey, tempL1)
            onComplete()
        } catch (_: IllegalArgumentException) {
            // A stale/racing result should recover in place rather than crash or save a key that
            // was not the exact verified draft.
            activationError = true
            submittedKey = null
            viewModel.clearProviderVerification("gemini")
            releaseCompletion()
        }
    }

    val goBack: () -> Unit = {
        if (!completionGate.get()) {
            when {
                step == 2 && startAtConnect -> onDismiss()
                step > 0 -> step -= 1
                else -> Unit
            }
        }
    }
    BackHandler(enabled = step > 0) { goBack() }

    CompositionLocalProvider(
        LocalTranslate provides t,
        LocalLayoutDirection provides layoutDirection
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            OnboardingHeader(
                step = step,
                canGoBack = step > 0,
                onBack = goBack
            )

            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics { paneTitle = pageTitle },
                transitionSpec = {
                    val forward = targetState > initialState
                    val readingDirection = if (layoutDirection == LayoutDirection.Rtl) -1 else 1
                    val direction = (if (forward) 1 else -1) * readingDirection
                    (slideInHorizontally(tween(260)) { it / 5 * direction } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { -it / 5 * direction } + fadeOut(tween(150))
                        )
                },
                label = "onboarding_step"
            ) { page ->
                when (page) {
                    0 -> WelcomeStep(
                        everydayOnly = everydayOnly,
                        selectedLanguage = tempL1,
                        onSelectLanguage = { tempL1 = it },
                        selectedDisplayLanguage = tempUiLanguage,
                        onSelectDisplayLanguage = {
                            tempUiLanguage = it
                        }
                    )
                    1 -> ProductPreviewStep(everydayOnly = everydayOnly)
                    else -> ConnectGeminiStep(
                        everydayOnly = everydayOnly,
                        apiKey = tempGeminiKey,
                        showKey = showKey,
                        showFormatError = showFormatError,
                        activationError = activationError,
                        verificationStatus = verification?.status,
                        onKeyChange = ::editKey,
                        onToggleKeyVisibility = { showKey = !showKey },
                        onSubmit = ::verifyDraft
                    )
                }
            }

            when (step) {
                0 -> OnboardingFooter(
                    primaryLabel = if (currentGeminiKey.isNotBlank()) {
                        t("onboarding.v2.continue_gemini")
                    } else {
                        t("onboarding.v2.see_how")
                    },
                    primaryIcon = if (currentGeminiKey.isNotBlank()) Icons.Default.Key else Icons.Default.PlayArrow,
                    onPrimary = {
                        if (!completionGate.get()) {
                            step = if (currentGeminiKey.isNotBlank()) 2 else 1
                        }
                    },
                    secondaryLabel = t("onboarding.v2.have_key").takeIf { currentGeminiKey.isBlank() },
                    onSecondary = { if (!completionGate.get()) step = 2 },
                    primaryEnabled = !isFinishing,
                    secondaryEnabled = !isFinishing,
                    helper = if (everydayOnly) "Try a prepared everyday conversation, or connect Gemini for live voice practice" else t("onboarding.v2.welcome_helper")
                )
                1 -> OnboardingFooter(
                    primaryLabel = if (everydayOnly) "Try an everyday demo · about 3 min" else t("onboarding.v2.start_demo"),
                    primaryIcon = Icons.Default.PlayArrow,
                    onPrimary = ::finishWithDemo,
                    secondaryLabel = t("onboarding.v2.skip_demo"),
                    onSecondary = { if (!completionGate.get()) step = 2 },
                    primaryEnabled = !isFinishing,
                    secondaryEnabled = !isFinishing,
                    helper = if (everydayOnly) "Prepared everyday conversation · not AI · no microphone required" else t("onboarding.v2.demo_helper")
                )
                else -> OnboardingFooter(
                    primaryLabel = when {
                        isVerifying -> t("onboarding.v2.verifying")
                        isVerified -> t("onboarding.v2.finish_live")
                        verification?.status == ProviderStatus.INVALID_KEY -> t("onboarding.v2.retry")
                        verification?.status == ProviderStatus.ERROR -> t("onboarding.v2.retry")
                        else -> t("onboarding.v2.verify")
                    },
                    primaryIcon = when {
                        isVerifying -> null
                        isVerified -> Icons.Default.CheckCircle
                        verification?.status in setOf(ProviderStatus.INVALID_KEY, ProviderStatus.ERROR) -> Icons.Default.Refresh
                        else -> Icons.Default.Key
                    },
                    showProgress = isVerifying,
                    primaryEnabled = !isFinishing && !isVerifying && (isVerified || normalizedKey.isNotBlank()),
                    onPrimary = { if (isVerified) finishWithVerifiedKey() else verifyDraft() },
                    secondaryLabel = t("onboarding.v2.use_demo").takeIf { showSampleScenario },
                    onSecondary = ::finishWithDemo,
                    secondaryEnabled = !isFinishing
                )
            }
            }
        }
    }
}

@Composable
private fun OnboardingHeader(
    step: Int,
    canGoBack: Boolean,
    onBack: () -> Unit
) {
    val t = LocalTranslate.current


    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("onboarding.v2.back")
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Hearing,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = t("onboarding.v2.progress")
                    .replace("{step}", (step + 1).toString())
                    .replace("{total}", TOTAL_STEPS.toString()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )

        }
        LinearProgressIndicator(
            progress = { (step + 1) / TOTAL_STEPS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.primaryContainer
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeStep(
    everydayOnly: Boolean,
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    selectedDisplayLanguage: String,
    onSelectDisplayLanguage: (String) -> Unit
) {
    val t = LocalTranslate.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (everydayOnly) "EVERYDAY ENGLISH, PRACTICED OUT LOUD" else t("onboarding.v2.eyebrow"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (everydayOnly) "Speak more confidently in real-life conversations" else t("onboarding.v2.welcome_title"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            lineHeight = 35.sp,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (everydayOnly) {
                "Practise everyday situations with a conversation partner, then improve the English you used."
            } else t("onboarding.v2.welcome_body"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Front-load the trust answer a first-time user is silently weighing on screen 1
        // ("does this cost money / do I need to sign up?"). The same reassurance otherwise only
        // appears on the API-key step, after the moment most people decide whether to bounce.
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = t("onboarding.v2.welcome_trust"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val chips = if (everydayOnly) {
                listOf("Real-life conversation", "Listening", "Speaking confidence", "Pronunciation")
            } else {
                listOf("OSCE", "OET", t("onboarding.v2.residency_chip"), t("onboarding.v2.clinical_work"))
            }
            chips.forEach { label ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // The three value rows lead with their headline only. Each body is a full sentence, and
        // three of them stacked under three headlines is most of what made this screen a wall of
        // text — one shared toggle keeps the promises scannable and the reasoning available.
        Spacer(Modifier.height(26.dp))
        var valueDetailsExpanded by rememberSaveable { mutableStateOf(false) }
        ValueRow(
            icon = Icons.Default.RecordVoiceOver,
            title = if (everydayOnly) t("A conversation partner who responds") else t("onboarding.v2.value_voice_title"),
            body = if (everydayOnly) "Speak naturally and follow the reply, like a real conversation." else t("onboarding.v2.value_voice_body"),
            showBody = valueDetailsExpanded
        )
        Spacer(Modifier.height(if (valueDetailsExpanded) 14.dp else 10.dp))
        ValueRow(
            icon = Icons.Default.FactCheck,
            title = if (everydayOnly) t("Clear, practical language feedback") else t("onboarding.v2.value_feedback_title"),
            body = if (everydayOnly) "See fluency signals and a clearer, more natural way to express each idea." else t("onboarding.v2.value_feedback_body"),
            showBody = valueDetailsExpanded
        )
        Spacer(Modifier.height(if (valueDetailsExpanded) 14.dp else 10.dp))
        ValueRow(
            icon = Icons.Default.School,
            title = t("onboarding.v2.value_repeat_title"),
            body = t("onboarding.v2.value_repeat_body"),
            showBody = valueDetailsExpanded
        )
        TextButton(
            onClick = { valueDetailsExpanded = !valueDetailsExpanded },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(
                if (valueDetailsExpanded) t("onboarding.v2.less_detail") else t("onboarding.v2.more_detail"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                if (valueDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        if (!everydayOnly) WhyNotChatGptCard()

        Spacer(Modifier.height(24.dp))
        DisplayLanguageCard(
            selectedLanguage = selectedDisplayLanguage,
            onSelectLanguage = onSelectDisplayLanguage
        )

        Spacer(Modifier.height(12.dp))
        NativeLanguageCard(
            selectedLanguage = selectedLanguage,
            onSelectLanguage = onSelectLanguage
        )
    }
}

@Composable
private fun DisplayLanguageCard(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit
) {
    val t = LocalTranslate.current
    var expanded by remember { mutableStateOf(false) }
    val selected = onboardingDisplayLanguages.firstOrNull { it.code == selectedLanguage }
        ?: onboardingDisplayLanguages.first { it.code == "en" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(t("onboarding.v2.display_language_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                t("onboarding.v2.display_language_body"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    onboardingDisplayLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.label) },
                            leadingIcon = if (language.code == selectedLanguage) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelectLanguage(language.code)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Answers the most common skeptical question head-on, right where a new user first weighs the
 * app against a free alternative — see docs/DIFFERENTIATION_ANSWERS.md Q1. Collapsed by default
 * so it doesn't compete with the primary onboarding flow.
 */
@Composable
private fun WhyNotChatGptCard() {
    val t = LocalTranslate.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = t("onboarding.v2.why_not_chatgpt_title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = t("onboarding.v2.why_not_chatgpt_body"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        "onboarding.v2.why_not_chatgpt_point1",
                        "onboarding.v2.why_not_chatgpt_point2",
                        "onboarding.v2.why_not_chatgpt_point3"
                    ).forEach { key ->
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Text("•  ", fontWeight = FontWeight.Bold)
                            Text(
                                text = t(key),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeLanguageCard(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit
) {
    val t = LocalTranslate.current
    var expanded by remember { mutableStateOf(false) }
    val selected = onboardingLanguages.firstOrNull { it.code == selectedLanguage }
        ?: onboardingLanguages.first { it.code == "en" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        t("onboarding.v2.native_title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        t("onboarding.v2.native_body"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selected.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    onboardingLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.label) },
                            leadingIcon = if (language.code == selectedLanguage) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelectLanguage(language.code)
                            }
                        )
                    }
                }
            }

            // Discoverability fix (Q2 in docs/DIFFERENTIATION_ANSWERS.md): pronunciation analysis
            // already exists, is on by default, and is L1-specific, but was buried in Preferences —
            // most learners never found out it exists. Surface it right where their L1 is chosen.
            if (com.example.medvoicetrainer.analysis.PronunciationEngine.l1Name(selectedLanguage) != "unknown") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        t("onboarding.v2.pronunciation_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductPreviewStep(everydayOnly: Boolean) {
    val t = LocalTranslate.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        Text(
            text = if (everydayOnly) "Try an everyday conversation" else t("onboarding.v2.preview_title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (everydayOnly) {
                "Follow a prepared real-life exchange to see the learning flow. This sample is not AI and does not use your microphone."
            } else t("onboarding.v2.preview_body"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // The three loop steps and the sample card below say the same thing twice — the card just
        // says it by showing it. So the steps carry their headline only, and the card is the hero.
        Spacer(Modifier.height(20.dp))
        var loopDetailsExpanded by rememberSaveable { mutableStateOf(false) }
        PracticeLoopRow(
            number = "1",
            icon = Icons.Default.GraphicEq,
            title = t("onboarding.v2.loop_speak_title"),
            body = if (everydayOnly) "Respond naturally in a travel, service, social, or daily-life situation." else t("onboarding.v2.loop_speak_body"),
            showBody = loopDetailsExpanded
        )
        PracticeLoopRow(
            number = "2",
            icon = Icons.Default.RecordVoiceOver,
            title = if (everydayOnly) t("Your conversation partner responds") else t("onboarding.v2.loop_patient_title"),
            body = if (everydayOnly) "With live voice, the other person responds to what you actually say." else t("onboarding.v2.loop_patient_body"),
            showBody = loopDetailsExpanded
        )
        PracticeLoopRow(
            number = "3",
            icon = Icons.Default.EditNote,
            title = t("onboarding.v2.loop_improve_title"),
            body = if (everydayOnly) "Review one useful language improvement while the conversation is still fresh." else t("onboarding.v2.loop_improve_body"),
            showBody = loopDetailsExpanded,
            showConnector = false
        )
        TextButton(onClick = { loopDetailsExpanded = !loopDetailsExpanded }) {
            Text(
                if (loopDetailsExpanded) t("onboarding.v2.less_detail") else t("onboarding.v2.more_detail"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                if (loopDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        FeedbackPreviewCard(everydayOnly = everydayOnly)

        // Safety/scope note. It has to stay visible, but as a footnote rather than a third card.
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (everydayOnly) {
                    "Practice simulation only. Avoid entering private or identifying information."
                } else t("onboarding.v2.simulation_note"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PracticeLoopRow(
    number: String,
    icon: ImageVector,
    title: String,
    body: String,
    showBody: Boolean = true,
    showConnector: Boolean = true
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .size(width = 2.dp, height = if (showBody) 28.dp else 14.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (showConnector) 10.dp else 0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.size(7.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            AnimatedVisibility(visible = showBody) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackPreviewCard(everydayOnly: Boolean) {
    val t = LocalTranslate.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                t("onboarding.v2.preview_card_title"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            SpeechBubble(
                label = if (everydayOnly) t("CONVERSATION PARTNER") else t("onboarding.v2.patient_label"),
                text = if (everydayOnly) "The next train leaves from platform six in about ten minutes." else t("onboarding.v2.patient_sample"),
                learner = false
            )
            Spacer(Modifier.height(8.dp))
            SpeechBubble(
                label = t("onboarding.v2.you_label"),
                text = if (everydayOnly) "Where platform six?" else t("onboarding.v2.you_sample"),
                learner = true
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(t("onboarding.v2.coach_label"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (everydayOnly) "Try: “Could you tell me where platform six is?”" else t("onboarding.v2.coach_sample"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewMetric(if (everydayOnly) "Listening & response" else t("onboarding.v2.metric_clinical"))
                PreviewMetric(t("onboarding.v2.metric_fluency"))
                PreviewMetric(t("onboarding.v2.metric_corrections"))
            }
        }
    }
}

@Composable
private fun SpeechBubble(label: String, text: String, learner: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (learner) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (learner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (learner) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PreviewMetric(label: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectGeminiStep(
    everydayOnly: Boolean,
    apiKey: String,
    showKey: Boolean,
    showFormatError: Boolean,
    activationError: Boolean,
    verificationStatus: ProviderStatus?,
    onKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onSubmit: () -> Unit
) {
    val t = LocalTranslate.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        Text(
            text = t("onboarding.v2.recommended"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (everydayOnly) "Connect live AI conversations" else t("onboarding.v2.connect_title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (everydayOnly) {
                "No coding is needed. Get one Gemini API key—your personal connection code—from Google, paste it here, and we'll check it for you."
            } else t("onboarding.v2.connect_body"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // "What is an API key?" used to be a full card above the instructions, so everyone read a
        // definition before reaching the thing to do. It is still one tap away for whoever needs it.
        Spacer(Modifier.height(6.dp))
        InlineDisclosure(title = t("onboarding.v2.api_key_title"), dense = true) {
            Text(t("onboarding.v2.api_key_body"), style = MaterialTheme.typography.bodySmall)
            Text(
                t("onboarding.v2.not_password"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(t("onboarding.v2.setup_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SetupInstruction(number = "1", text = t("onboarding.v2.setup_sign_in"))
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { uriHandler.openUri(GEMINI_API_KEY_URL) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
            Text(t("onboarding.v2.open_ai_studio"), fontWeight = FontWeight.Bold)
        }
        Text(
            t("onboarding.v2.external_note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp, start = 4.dp)
        )

        Spacer(Modifier.height(14.dp))
        SetupInstruction(number = "2", text = t("onboarding.v2.setup_copy"))
        Spacer(Modifier.height(14.dp))
        SetupInstruction(number = "3", text = t("onboarding.v2.setup_paste"))
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onKeyChange,
            label = { Text(t("onboarding.v2.key_label")) },
            placeholder = { Text(t("onboarding.v2.key_placeholder")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showFormatError || verificationStatus == ProviderStatus.INVALID_KEY || activationError,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = {
                IconButton(onClick = onToggleKeyVisibility) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) t("onboarding.v2.hide_key") else t("onboarding.v2.show_key")
                    )
                }
            },
            supportingText = if (showFormatError) {
                { Text(t("onboarding.v2.format_error")) }
            } else null
        )

        TextButton(
            onClick = {
                val pasted = UnlockSheet.normalizePastedGeminiKey(clipboard.getText()?.text.orEmpty())
                onKeyChange(pasted)
                // Returning from AI Studio with a freshly copied key, a paste is a clear intent to
                // connect — verify it right away instead of making the learner hunt for a second
                // button at the funnel's highest-drop-off step. Typing already auto-verifies on IME
                // Done; this closes the same gap for the paste path.
                if (UnlockSheet.looksLikeGeminiKey(pasted)) onSubmit()
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(t("onboarding.v2.paste"))
        }

        VerificationStatusCard(verificationStatus = verificationStatus, activationError = activationError)

        // Three trust rows, a free-tier card, and a "why my own key?" expander used to stack up
        // below the field — four separate blocks of reassurance answering one worry. The worry is
        // real, so the answer stays: one visible line, with the full version behind it.
        Spacer(Modifier.height(14.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        t("onboarding.v2.trust_summary"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InlineDisclosure(title = t("onboarding.v2.trust_more"), dense = true) {
                    TrustRow(Icons.Default.CloudOff, t("onboarding.v2.no_collection"))
                    TrustRow(Icons.Default.Lock, t("onboarding.v2.encrypted"))
                    TrustRow(Icons.Default.FactCheck, t("onboarding.v2.billing"))
                    // §2 (early-UX): concrete free-tier reassurance. Live voice is the priciest
                    // modality, and a vague "costs follow your project settings" line reads as a
                    // warning — this says plainly that most learners never leave the free tier.
                    TrustRow(Icons.Default.AutoAwesome, t("onboarding.v2.free_tier_reassure"))
                    Text(
                        t("onboarding.v2.why_key"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        t("onboarding.v2.why_key_body"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupInstruction(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VerificationStatusCard(
    verificationStatus: ProviderStatus?,
    activationError: Boolean
) {
    val t = LocalTranslate.current
    val status = if (activationError) ProviderStatus.ERROR else verificationStatus
    AnimatedVisibility(visible = status != null && status != ProviderStatus.NOT_SET) {
        val (container, content, icon, title, body) = when (status) {
            ProviderStatus.VERIFYING -> StatusCardData(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                null,
                t("onboarding.v2.verifying"),
                t("onboarding.v2.verifying_body")
            )
            ProviderStatus.VERIFIED -> StatusCardData(
                Color(0xFFE8F5E9),
                Color(0xFF14532D),
                Icons.Default.CheckCircle,
                t("onboarding.v2.verified_title"),
                t("onboarding.v2.verified_body")
            )
            ProviderStatus.INVALID_KEY -> StatusCardData(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                Icons.Default.Info,
                t("onboarding.v2.invalid_title"),
                t("onboarding.v2.invalid_body")
            )
            ProviderStatus.ERROR -> StatusCardData(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                Icons.Default.Info,
                t("onboarding.v2.network_title"),
                t("onboarding.v2.network_body")
            )
            else -> return@AnimatedVisibility
        }
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status == ProviderStatus.VERIFYING) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = content)
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class StatusCardData(
    val container: Color,
    val content: Color,
    val icon: ImageVector?,
    val title: String,
    val body: String
)

@Composable
private fun TrustRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, body: String, showBody: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            AnimatedVisibility(visible = showBody) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OnboardingFooter(
    primaryLabel: String,
    primaryIcon: ImageVector?,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
    showProgress: Boolean = false,
    helper: String? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (showProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else if (primaryIcon != null) {
                    Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                if (showProgress || primaryIcon != null) Spacer(Modifier.size(8.dp))
                Text(primaryLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            if (helper != null) {
                Text(
                    helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                )
            }
            if (secondaryLabel != null) {
                TextButton(
                    onClick = onSecondary,
                    enabled = secondaryEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(secondaryLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
