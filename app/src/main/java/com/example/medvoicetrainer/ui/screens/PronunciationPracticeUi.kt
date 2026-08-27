package com.example.medvoicetrainer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.medvoicetrainer.analysis.IntelligibilityOutcome
import com.example.medvoicetrainer.analysis.PhraseBlanks
import com.example.medvoicetrainer.analysis.PronunciationAudioQuality
import com.example.medvoicetrainer.analysis.PronunciationAudioQualityAnalyzer
import com.example.medvoicetrainer.analysis.SpeakingAttemptJudge
import com.example.medvoicetrainer.analysis.SpeakingJudgment
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.voice.AttemptRecorder
import com.example.medvoicetrainer.voice.AudioEffects
import com.example.medvoicetrainer.voice.playAttemptPcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The model voice currently loaded on screen, so anything that needs the room quiet — chiefly
 * [PronunciationAttemptPanel] starting a recording — can silence it without every intermediate
 * composable having to forward a stop callback.
 *
 * One screen owns the model voice at a time (each screen builds its engine in
 * [rememberEnglishTts] and unregisters it on dispose), which is exactly the lifetime this needs.
 * Without it, a learner practising on the phone's speaker who taps Record while the target phrase
 * is still playing records the app's own voice and gets graded on it.
 */
internal object ModelSpeechControl {
    @Volatile
    private var stopper: (() -> Unit)? = null

    fun register(stop: () -> Unit) {
        stopper = stop
    }

    fun unregister(stop: () -> Unit) {
        if (stopper === stop) stopper = null
    }

    fun stopSpeaking() {
        runCatching { stopper?.invoke() }
    }
}

/** Holds one screen-scoped Android TTS engine set to US English, released on dispose. */
internal class EnglishTtsHandle(
    private val ttsProvider: () -> TextToSpeech?,
    val readyProvider: () -> Boolean,
) {
    val ready: Boolean get() = readyProvider()

    /**
     * Speak one model sentence. A cloze phrase is spoken as its written-out parts with a real
     * silence where each blank is: `speak("Hello, my name is ___.")` must never hand the engine a
     * run of underscores, which voices read out as "underscore underscore underscore" (or drop the
     * whole utterance), leaving the learner copying audio that is not the phrase on screen.
     */
    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val engine = ttsProvider() ?: return
        val segments = PhraseBlanks.segments(text)
        if (segments.isEmpty()) return
        val utteranceId = "pron-${text.hashCode()}"
        segments.forEachIndexed { index, segment ->
            if (index == 0) {
                engine.speak(segment, TextToSpeech.QUEUE_FLUSH, null, "$utteranceId-0")
            } else {
                engine.playSilentUtterance(
                    PhraseBlanks.BLANK_PAUSE_MS,
                    TextToSpeech.QUEUE_ADD,
                    "$utteranceId-gap-$index",
                )
                engine.speak(segment, TextToSpeech.QUEUE_ADD, null, "$utteranceId-$index")
            }
        }
    }

    fun stop() {
        runCatching { ttsProvider()?.stop() }
    }
}

@Composable
internal fun rememberEnglishTts(): EnglishTtsHandle {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember(context.applicationContext) {
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
    }
    LaunchedEffect(ready) {
        if (ready) {
            tts.language = Locale.US
            // Media usage + speech content: the model phrase plays at media volume out of the
            // loudspeaker instead of being ducked or routed to the earpiece.
            runCatching {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
    }
    val handle = remember(tts) { EnglishTtsHandle({ tts }, { ready }) }
    DisposableEffect(handle) {
        val stop = handle::stop
        ModelSpeechControl.register(stop)
        onDispose {
            ModelSpeechControl.unregister(stop)
            tts.stop()
            tts.shutdown()
        }
    }
    return handle
}

/**
 * Perception-first discrimination quiz: the app speaks ONE word of a minimal pair (chosen at
 * random and hidden), the learner taps which of the two they heard. Three correct in a row unlocks
 * production — a learner who cannot yet hear the /r/–/l/ contrast cannot self-monitor it, and
 * repeating a sound they cannot distinguish just rehearses the error. Offline TTS only, no API cost.
 */
@Composable
internal fun PerceptionDiscriminationQuiz(
    pair: Pair<String, String>,
    ttsReady: Boolean,
    onSpeak: (String) -> Unit,
    onPassed: () -> Unit,
) {
    val t = LocalTranslate.current
    var spokenWord by remember(pair) { mutableStateOf<String?>(null) }
    var streak by remember(pair) { mutableStateOf(0) }
    var lastCorrect by remember(pair) { mutableStateOf<Boolean?>(null) }

    fun playRandom() {
        val word = if (kotlin.random.Random.nextBoolean()) pair.first else pair.second
        spokenWord = word
        lastCorrect = null
        onSpeak(word)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("pron.discern_title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        Text(
            t("pron.discern_prompt"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { playRandom() }, enabled = ttsReady) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(if (spokenWord == null) t("pron.discern_play") else t("pron.discern_replay"))
        }
        if (spokenWord != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(pair.first, pair.second).forEach { option ->
                    Button(
                        onClick = {
                            val correct = option.equals(spokenWord, ignoreCase = true)
                            lastCorrect = correct
                            if (correct) {
                                streak += 1
                                spokenWord = null
                                if (streak >= 3) onPassed()
                            } else {
                                streak = 0
                                spokenWord = null
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(option) }
                }
            }
        }
        lastCorrect?.let {
            Text(
                if (it) t("pron.discern_correct") else t("pron.discern_wrong"),
                style = MaterialTheme.typography.labelSmall,
                color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Text(
            t("pron.discern_progress").replace("{n}", streak.coerceAtMost(3).toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class AttemptPhase { IDLE, RECORDING, RECORDED, JUDGING }

/**
 * Peak mic level under which a recording in progress is treated as "nothing is reaching the app".
 * Well below normal speech on the shared [com.example.medvoicetrainer.voice.pcm16MicLevel] scale,
 * so ordinary quiet talkers do not trip it — only a genuinely dead input does.
 */
private const val QUIET_MIC_LEVEL = 0.08f

/** Localized, actionable line for a recording the offline gate refused. */
private fun micQualityHintKey(quality: PronunciationAudioQuality): String? =
    when (quality.reasonCode) {
        PronunciationAudioQualityAnalyzer.Reason.NO_AUDIO,
        PronunciationAudioQualityAnalyzer.Reason.TOO_SHORT -> "pron.quality_too_short"
        PronunciationAudioQualityAnalyzer.Reason.TOO_QUIET -> "pron.quality_too_quiet"
        PronunciationAudioQualityAnalyzer.Reason.CLIPPED -> "pron.quality_clipped"
        PronunciationAudioQualityAnalyzer.Reason.BROADBAND_NOISE,
        PronunciationAudioQualityAnalyzer.Reason.LOW_SNR -> "pron.quality_noisy"
        PronunciationAudioQualityAnalyzer.Reason.NO_SPEECH -> "pron.quality_no_speech"
        else -> null
    }

/**
 * Shorter than this and a recording holds no usable attempt, so callers that keep takes are not
 * handed a fraction of a second of room tone to store.
 */
private const val MIN_KEEPABLE_PCM_BYTES = 16_000 // 0.5 s of 16 kHz PCM16

/**
 * Listen → record → replay → (optionally) AI-check loop for one target sentence.
 *
 * The AI check is gated the same way as all pronunciation analysis: Gemini-only and explicitly
 * user-initiated ([judge] null = unavailable, panel degrades to self-comparison), and its verdict
 * is about intelligibility, never accent — the principle line is always rendered with a verdict.
 *
 * [onAttemptRecorded] receives each finished recording for callers that keep the learner's takes
 * (Say It). It is deliberately not the same thing as [onVerdict]: a take is worth keeping whether
 * or not the learner ever spends an AI check on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PronunciationAttemptPanel(
    targetText: String,
    ttsReady: Boolean,
    onSpeakTarget: () -> Unit,
    judge: (suspend (ByteArray) -> SpeakingJudgment?)?,
    onVerdict: ((SpeakingJudgment) -> Unit)? = null,
    onAttemptRecorded: ((ByteArray) -> Unit)? = null,
) {
    val t = LocalTranslate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember { AttemptRecorder() }
    var phase by remember(targetText) { mutableStateOf(AttemptPhase.IDLE) }
    var attempt by remember(targetText) { mutableStateOf<ByteArray?>(null) }
    var judgment by remember(targetText) { mutableStateOf<SpeakingJudgment?>(null) }
    var judgeFailed by remember(targetText) { mutableStateOf(false) }
    var judgeFailReason by remember(targetText) { mutableStateOf<String?>(null) }
    var micError by remember(targetText) { mutableStateOf<String?>(null) }
    var playbackTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var isPlayingAttempt by remember(targetText) { mutableStateOf(false) }
    // Written from the capture thread; snapshot state handles the cross-thread write, and the
    // meter is the only thing that reads it.
    var micLevel by remember(targetText) { mutableStateOf(0f) }
    var peakLevel by remember(targetText) { mutableStateOf(0f) }
    var quality by remember(targetText) { mutableStateOf<PronunciationAudioQuality?>(null) }

    fun stopAttemptPlayback() {
        playbackTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        playbackTrack = null
        isPlayingAttempt = false
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            stopAttemptPlayback()
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    fun startRecording() {
        stopAttemptPlayback()
        // Without earphones the model phrase plays out of the same loudspeaker the mic is next to,
        // so an attempt started while it is still talking captures the app, not the learner.
        ModelSpeechControl.stopSpeaking()
        judgment = null
        judgeFailed = false
        judgeFailReason = null
        micError = null
        quality = null
        micLevel = 0f
        peakLevel = 0f
        val started = recorder.start(
            onLevel = { level ->
                micLevel = level
                if (level > peakLevel) peakLevel = level
            },
            onError = { message -> micError = message },
        )
        phase = if (started) AttemptPhase.RECORDING else AttemptPhase.IDLE
        if (!started && micError == null) micError = t("pron.mic_failed")
    }

    fun finishRecording() {
        val pcm = recorder.stop()
        attempt = pcm
        micLevel = 0f
        phase = AttemptPhase.RECORDED
        if (pcm.size >= MIN_KEEPABLE_PCM_BYTES) onAttemptRecorded?.invoke(pcm)
        // Analysing 45 s of PCM allocates enough to be felt on the main thread; the verdict only
        // drives a hint line, so it can land a frame later.
        scope.launch {
            quality = withContext(Dispatchers.Default) {
                PronunciationAudioQualityAnalyzer.analyze(pcm, AttemptRecorder.SAMPLE_RATE)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (PhraseBlanks.hasBlank(targetText)) {
            // The model audio pauses where the blank is; say so, or a learner hearing a gap
            // assumes the phrase was cut off.
            Text(
                t("pron.blank_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSpeakTarget, enabled = ttsReady) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(t("pron.listen_target"))
            }
            when (phase) {
                AttemptPhase.RECORDING -> {
                    Button(onClick = { finishRecording() }) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(t("pron.stop"))
                    }
                }
                AttemptPhase.JUDGING -> Unit
                else -> {
                    OutlinedButton(onClick = {
                        if (hasMicPermission) {
                            startRecording()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(t(if (phase == AttemptPhase.RECORDED) "pron.re_record" else "pron.record"))
                    }
                }
            }
        }

        if (!hasMicPermission && phase == AttemptPhase.IDLE) {
            Text(
                t("pron.mic_permission"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        micError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (phase == AttemptPhase.RECORDING) {
            Text(
                t("pron.recording_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            // A live meter answers "is it even hearing me?" while there is still time to fix it —
            // the single most common reason a check "does not work" is a mic that captured nothing.
            LinearProgressIndicator(
                progress = { micLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (peakLevel < QUIET_MIC_LEVEL) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            if (peakLevel < QUIET_MIC_LEVEL) {
                Text(
                    t("pron.mic_silent"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        val recorded = attempt
        if (phase == AttemptPhase.RECORDED && recorded != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (isPlayingAttempt) {
                        stopAttemptPlayback()
                    } else {
                        stopAttemptPlayback()
                        playbackTrack = playAttemptPcm(recorded) { isPlayingAttempt = false }
                        isPlayingAttempt = playbackTrack != null
                    }
                }) {
                    Icon(
                        if (isPlayingAttempt) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(t("pron.play_attempt"))
                }
                if (judge != null) {
                    Button(onClick = {
                        phase = AttemptPhase.JUDGING
                        judgeFailed = false
                        judgeFailReason = null
                        scope.launch {
                            // A built-in mic held at speaking distance records far quieter than a
                            // headset; the listener is given the same attempt at a workable level.
                            val payload = withContext(Dispatchers.Default) {
                                AudioEffects.normalizeForSpeech(recorded, AttemptRecorder.SAMPLE_RATE)
                            }
                            val result = judge(payload)
                            judgment = result
                            judgeFailed = result == null
                            judgeFailReason = if (result == null) SpeakingAttemptJudge.lastFailureReason else null
                            phase = AttemptPhase.RECORDED
                            if (result != null) onVerdict?.invoke(result)
                        }
                    }) {
                        Text(t("pron.check_ai"))
                    }
                }
            }
            // Shown before the learner spends an AI check on audio that cannot carry one: what is
            // wrong with the recording and what to change about it. Suppressed once a failed check
            // is already reporting the same thing.
            quality?.takeIf { !it.accepted && !judgeFailed }?.let { verdict ->
                micQualityHintKey(verdict)?.let { key ->
                    Text(
                        t(key),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (judge == null) {
                Text(
                    t("pron.judge_unavailable"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (phase == AttemptPhase.JUDGING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(t("pron.judging"), style = MaterialTheme.typography.labelSmall)
        }
        if (judgeFailed) {
            // Map the raw provider error to a friendly, actionable line. A 429/quota failure is
            // the common free-tier "20 requests/day per model" case, so it gets its own message
            // pointing at what actually helps (wait / switch model or provider) instead of dumping
            // the raw HTTP 429 JSON blob at the learner. When the recording itself was the
            // problem, say that instead — "the check didn't come back" sends the learner looking
            // at their API key for a microphone issue.
            val quotaHit = com.example.medvoicetrainer.api.isGeminiQuotaError(judgeFailReason)
            val recordingHint = quality?.takeIf { !it.accepted }?.let(::micQualityHintKey)
            Text(
                when {
                    quotaHit -> t("pron.judge_failed_quota")
                    recordingHint != null -> t(recordingHint)
                    else -> t("pron.judge_failed")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        judgment?.let { verdict ->
            val outcomeLabel = when (verdict.outcome) {
                IntelligibilityOutcome.COMFORTABLE -> t("pron.outcome_comfortable")
                IntelligibilityOutcome.EFFORTFUL -> t("pron.outcome_effortful")
                IntelligibilityOutcome.CRITICAL_MISMATCH -> t("pron.outcome_critical")
                IntelligibilityOutcome.COULD_NOT_ASSESS -> t("pron.outcome_unassessable")
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = when (verdict.outcome) {
                    IntelligibilityOutcome.COMFORTABLE ->
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    IntelligibilityOutcome.EFFORTFUL,
                    IntelligibilityOutcome.COULD_NOT_ASSESS ->
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    IntelligibilityOutcome.CRITICAL_MISMATCH ->
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                }
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        if (verdict.pass) "✓ $outcomeLabel" else "↻ $outcomeLabel",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (verdict.intendedText.isNotBlank()) {
                        Text(t("pron.intended_label"), style = MaterialTheme.typography.labelSmall)
                        Text(verdict.intendedText, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (verdict.heardTranscript.isNotBlank()) {
                        Text(t("pron.heard_label"), style = MaterialTheme.typography.labelSmall)
                        Text(
                            "“${verdict.heardTranscript}”",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (verdict.criticalDifferences.isNotEmpty()) {
                        Text(
                            t("pron.critical_difference"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        verdict.criticalDifferences.forEach { difference ->
                            Text(
                                "• ${difference.expected} → " +
                                    (difference.heard?.takeIf { it.isNotBlank() }
                                        ?: t("pron.not_heard")),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (verdict.feedback.isNotBlank()) {
                        Text(verdict.feedback, style = MaterialTheme.typography.bodySmall)
                    }
                    if (verdict.focusWords.isNotEmpty()) {
                        Text(
                            "${t("pron.focus_words")} ${verdict.focusWords.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        t(
                            if (verdict.heardTranscript.isNotBlank()) {
                                "pron.blind_judge_principle"
                            } else {
                                "pron.judge_principle"
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
