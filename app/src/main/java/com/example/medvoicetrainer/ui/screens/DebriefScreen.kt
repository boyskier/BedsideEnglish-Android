package com.example.medvoicetrainer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.medvoicetrainer.analysis.LearnerProfile
import com.example.medvoicetrainer.analysis.TutorPromptBuilder
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.voice.AttemptRecorder
import com.example.medvoicetrainer.voice.Dictation

/**
 * Post-session English tutor. This is the "spoken debrief tutor" — a turn-based Chat+STT+TTS coach
 * whose whole value is correction quality (see [TutorPromptBuilder]): it works one concrete English
 * fix at a time, models the natural phrasing, and asks the learner to say it back. It is grounded
 * in the learner's own distilled [LearnerProfile] (recurring mistakes from the SRS error_items) and
 * in the just-finished session transcript, so its examples come from what the learner actually said.
 *
 * Speaking is the default: the mic is the one control on the composer, backed by a live waveform
 * that mirrors the encounter's mic bar so the learner can see the app hearing them. The text field
 * stays folded away behind "⌨ Type instead" (same affordance as an encounter) so the keyboard only
 * appears when the learner asks for it. The tutor's replies are read aloud with the device's
 * built-in TTS, which needs no API key and works offline; the speaker toggle mutes them for a
 * text-only session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebriefScreen(
    viewModel: MainViewModel,
    apiKey: String,
    model: String,
    soapNote: String,
    everyday: Boolean = false,
    sessionSummary: String = "",
    // When true the coach was opened on its own from the home screen (not right after a session):
    // it grounds itself purely on the learner's saved-mistake profile, pulls in no session
    // transcript/summary/SOAP, and its chat is intentionally ephemeral (not attached to a session).
    standalone: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val t = LocalTranslate.current
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var isTutorLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()

    // Grounding material: the learner's recurring-mistake profile and the session they just did.
    val errorItems by viewModel.errorItems.collectAsStateWithLifecycle()
    val lastTranscript by viewModel.lastCompletedTranscript.collectAsStateWithLifecycle()
    // A standalone coach isn't tied to a just-finished run, so it must NOT drag in the previous
    // session's transcript (which would be stale and confusing) — it leans on the profile instead.
    val transcript = if (standalone) emptyList() else lastTranscript
    val openAiApiKey by viewModel.openAiApiKey.collectAsStateWithLifecycle()
    val geminiApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val learnerProfile = remember(errorItems) { LearnerProfile.distill(errorItems) }

    // --- Text-to-speech for the tutor's spoken replies (device built-in; no key, offline) ---
    // The shared engine, so this screen speaks blanks as pauses and can be silenced the moment
    // the learner starts talking back (see rememberEnglishTts / ModelSpeechControl).
    val ttsHandle = rememberEnglishTts()
    var voiceReplyEnabled by remember { mutableStateOf(true) }
    val speak: (String) -> Unit = { text ->
        if (voiceReplyEnabled) ttsHandle.speak(text)
    }

    // --- Push-to-talk speech-to-text for the learner's spoken answers ---
    val recorder = remember { AttemptRecorder() }
    var isRecording by remember { mutableStateOf(false) }
    var sttBusy by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    // 0f..1f loudness of what the mic is capturing right now, driving the composer's waveform.
    // Written from the capture thread; Compose snapshot state writes are safe off the main thread.
    var micLevel by remember { mutableFloatStateOf(0f) }
    val sttAvailable = openAiApiKey.isNotBlank() || geminiApiKey.isNotBlank()
    // The typed composer is opt-in: speaking is the point of this screen, and an always-present
    // text field invites the keyboard to cover the conversation. Mirrors the encounter's
    // showTypedInput toggle. Forced open when there's no STT key, since typing is then the only way in.
    var showTypedInput by remember { mutableStateOf(false) }
    val typedInputVisible = showTypedInput || !sttAvailable
    val textFieldFocus = remember { FocusRequester() }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            voiceStatus = null
            micLevel = 0f
            // Same reason as the push-to-talk path below: on a phone speaker the tutor's voice
            // lands in the learner's own recording.
            ttsHandle.stop()
            if (recorder.start(onLevel = { micLevel = it }) { m -> voiceStatus = m }) isRecording = true
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val opener = remember(everyday, standalone) {
        if (standalone) TutorPromptBuilder.standaloneOpener() else TutorPromptBuilder.opener(everyday)
    }

    // Initial greeting — spoken aloud if voice replies are on.
    LaunchedEffect(Unit) {
        chatHistory = listOf("tutor" to opener)
        speak(opener)
    }

    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            if (reducedMotion) listState.scrollToItem(chatHistory.size - 1) else listState.animateScrollToItem(chatHistory.size - 1)
        }
    }

    // Asking to type should actually raise the keyboard — otherwise the toggle only reveals a field
    // the learner has to tap a second time. Guarded because the requester isn't attached on the
    // no-STT path's first composition.
    LaunchedEffect(showTypedInput) {
        if (showTypedInput) runCatching { textFieldFocus.requestFocus() }
    }

    fun sendTurn(userMessage: String) {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty() || isTutorLoading) return
        val historyWithUser = chatHistory + ("user" to trimmed)
        chatHistory = historyWithUser
        textInput = ""
        isTutorLoading = true
        coroutineScope.launch {
            val prompt = TutorPromptBuilder.turnPrompt(
                everyday = everyday,
                learnerProfile = learnerProfile,
                sessionSummary = sessionSummary,
                soapNote = soapNote,
                transcript = transcript,
                chatHistory = historyWithUser,
            )
            try {
                val response = GeminiService.generateContent(
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    systemInstruction = TutorPromptBuilder.systemInstruction(everyday)
                )
                chatHistory = chatHistory + ("tutor" to response)
                speak(response)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                chatHistory = chatHistory + (
                    "tutor" to "Sorry, I couldn't reply right now. " +
                        com.example.medvoicetrainer.api.ApiError.userMessage(e)
                )
            } finally {
                isTutorLoading = false
            }
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            // Stop → transcribe → auto-send as the learner's spoken turn.
            val pcm = recorder.stop()
            isRecording = false
            micLevel = 0f
            sttBusy = true
            voiceStatus = null
            coroutineScope.launch {
                val text = try {
                    withContext(Dispatchers.IO) {
                        Dictation.transcribePcm16(
                            pcm = pcm,
                            sampleRate = AttemptRecorder.SAMPLE_RATE,
                            openAiApiKey = openAiApiKey,
                            geminiApiKey = geminiApiKey,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    voiceStatus = com.example.medvoicetrainer.api.ApiError.userMessage(
                        e,
                        "Couldn't understand that — try again."
                    )
                    ""
                } finally {
                    sttBusy = false
                }
                if (text.isNotBlank()) sendTurn(text)
                else if (voiceStatus == null) voiceStatus = "Didn't catch that — hold the mic and speak."
            }
        } else {
            ttsHandle.stop()
            if (hasMicPermission) {
                voiceStatus = null
                micLevel = 0f
                if (recorder.start(onLevel = { micLevel = it }) { m -> voiceStatus = m }) isRecording = true
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Persist the conversation before leaving (both the back button and the system back gesture).
    fun leaveDebrief() {
        recorder.cancel()
        isRecording = false
        micLevel = 0f
        ttsHandle.stop()
        // Post-session debrief writes the chat onto the just-finished session's row AND mines it
        // for corrections. A standalone coach has no session row to attach to, so it takes the
        // session-less path: no transcript persistence (which would corrupt an unrelated older
        // session), but the conversation is still analyzed and its corrections/commitments flow
        // into the same SRS + daily-mission pipeline.
        if (standalone) viewModel.finalizeStandaloneCoach(chatHistory)
        else viewModel.finalizeDebrief(chatHistory, kickoffMessage = "")
        onNavigateBack()
    }
    androidx.activity.compose.BackHandler { leaveDebrief() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (everyday || standalone) t("English Coach") else t("Speaking Tutor")) },
                navigationIcon = {
                    IconButton(onClick = { leaveDebrief() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = t("Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { voiceReplyEnabled = !voiceReplyEnabled; if (!voiceReplyEnabled) ttsHandle.stop() }) {
                        Icon(
                            if (voiceReplyEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (voiceReplyEnabled) t("Mute tutor voice") else t("Unmute tutor voice")
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scaffold's default content insets are system bars only, and the app is
                // edge-to-edge (so manifest adjustResize doesn't shrink the Compose view). Without
                // this the keyboard the "Type instead" toggle now raises would sit on top of the
                // composer it's meant to open.
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(chatHistory) { (role, text) ->
                    val isTutor = role == "tutor"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isTutor) Arrangement.Start else Arrangement.End
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isTutor) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isTutor) (if (everyday) t("ENGLISH COACH") else t("SPEAKING TUTOR")) else t("YOU"),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTutor) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isTutor) MaterialTheme.colorScheme.onSurface else Color.White
                                )
                            }
                        }
                    }
                }

                if (isTutorLoading) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    t("Tutor is thinking..."),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Status line for the voice path. "Transcribing" now lives in the composer's own helper
            // line (below), so this is just the recording instruction and any mic/STT error.
            val statusText = if (isRecording) t("Listening — tap the mic again when you're done.") else voiceStatus
            statusText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Input Controls
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Speaking row: the mic, whatever it's currently doing, and the typing escape hatch.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (sttAvailable) {
                            // Push-to-talk: this is the primary way to use the tutor (spoken practice).
                            Surface(
                                shape = CircleShape,
                                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                IconButton(onClick = { toggleRecording() }, enabled = !isTutorLoading && !sttBusy) {
                                    Icon(
                                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecording) t("Stop recording") else t("Speak your answer"),
                                        tint = if (isRecording) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        // While recording, the live waveform takes the middle of the bar — the same
                        // "the app is hearing me" signal the encounter's mic bar gives, and a flat
                        // centerline is the cue that nothing is reaching the mic.
                        if (isRecording) {
                            VoiceWaveform(
                                level = micLevel,
                                active = true,
                                reducedMotion = reducedMotion,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                            )
                        } else {
                            Text(
                                text = when {
                                    sttBusy -> t("Transcribing your answer…")
                                    sttAvailable -> t("Tap the mic and say it out loud.")
                                    else -> t("Type your answer below.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (sttAvailable) {
                            TextButton(
                                onClick = { showTypedInput = !showTypedInput },
                                enabled = !isRecording
                            ) {
                                Text(if (showTypedInput) t("Hide typing") else "⌨ " + t("Type instead"))
                            }
                        }
                    }

                    // Typed composer — revealed by "⌨ Type instead" (or always, with no STT key).
                    if (typedInputVisible) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                placeholder = { Text("Say it back, or type…") },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(textFieldFocus),
                                singleLine = false,
                                maxLines = 3,
                                enabled = !isRecording
                            )

                            FloatingActionButton(
                                onClick = { sendTurn(textInput) },
                                containerColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = t("Send"), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
