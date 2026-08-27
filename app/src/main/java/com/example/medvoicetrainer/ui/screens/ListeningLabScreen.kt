package com.example.medvoicetrainer.ui.screens

import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.ListeningDrillEngine
import com.example.medvoicetrainer.analysis.PromptBuilder
import com.example.medvoicetrainer.analysis.toAnalysisMap
import com.example.medvoicetrainer.analysis.toDeepMap
import com.example.medvoicetrainer.ui.ActiveSessionState
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.voice.WsolaStretcher
import com.example.medvoicetrainer.voice.playAttemptPcm
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

private data class LabeledChoice(val key: String, val label: String)

private val GENDER_CHOICES = listOf(
    LabeledChoice("", "Random"),
    LabeledChoice("female", "Female"),
    LabeledChoice("male", "Male"),
)

/**
 * Mirrors app/ui/listening_lab_window.py's "hear, commit, verify" flow, now including accent/
 * gender variety and Gemini-TTS synthesis (via `MainViewModel.synthesizeListeningNarration`),
 * closing a scope trim this screen used to document explicitly:
 *  1. Accent/voice settings: when a Gemini API key is configured, "Play"/"Ask to repeat" fetch
 *     (or reuse a cached) cloud-narrated clip in the learner's chosen accent/gender via
 *     `ListeningAudioCache` — replays within the cache's few-minutes TTL never re-pay for
 *     synthesis. "Ask more slowly" doesn't re-synthesize at all: it time-stretches the cached
 *     clip locally with `WsolaStretcher`, matching the "same audio, just slower" mental model.
 *     Without a Gemini key (or on synthesis failure), this falls back to on-device Android
 *     `TextToSpeech` exactly as before, so the screen still works fully offline.
 *  2. Noise/environment effects are still not wired (there's no separate "clean" vs. "challenge"
 *     audio track) — `AudioEffects.applyEnvironment` exists and is a natural next step, not
 *     attempted here.
 *  3. No adaptive drill picker/"New adaptive drill" button — `ListeningDrillEngine.chooseAdaptiveDrill`
 *     exists and is ready to be wired, but drill selection currently happens up-front in
 *     `PracticeScreen`'s shared card-list picker (same convention every other practice mode uses),
 *     not inside this screen.
 * Everything else — detail-recall scoring, repair-strategy detection, replay/reveal assistance
 * logging, spaced-repetition persistence, and the progress summary — calls the real ported
 * engines (`ListeningDrillEngine`, `Repository.recordListeningAttempt`/`updateListeningAssistance`/
 * `updateListeningRatings`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningLabScreen(
    viewModel: MainViewModel,
    activeSession: ActiveSessionState,
    // Backing out of the lab is the learner ending it deliberately, so it is recorded as a
    // discard rather than leaving a row that looks interrupted to the Home recovery card.
    onBack: () -> Unit = {
        viewModel.cancelSession(com.example.medvoicetrainer.db.SessionEndReason.DISCARDED)
    },
) {
    val t = com.example.medvoicetrainer.ui.LocalTranslate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf(false) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    val geminiApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val cloudTtsAvailable = geminiApiKey.isNotBlank()

    DisposableEffect(context) {
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isPlaying = false
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isPlaying = false
            }
        })
        tts = textToSpeech
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
            runCatching { audioTrack?.stop(); audioTrack?.release() }
        }
    }

    val drillJson = remember(activeSession.caseJson) {
        try { JSONObject(activeSession.caseJson) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
    }
    val drillMap = remember(drillJson) { drillJson.toDeepMap() }
    val drillId = remember(drillJson) { drillJson.optString("id", "unknown") }
    val category = remember(drillJson) { drillJson.optString("category").ifBlank { null } }
    val difficulty = remember(drillJson) { drillJson.optInt("difficulty", 1) }
    val skillTags = remember(drillJson) {
        val arr = drillJson.optJSONArray("receptive_tags")
        if (arr != null) (0 until arr.length()).map { arr.optString(it) } else emptyList()
    }
    val drillContext = drillJson.optString("context", "Listen carefully.")
    val transcript = drillJson.optString("transcript", "")
    val detailsList = remember(drillJson) {
        val list = mutableListOf<JSONObject>()
        drillJson.optJSONArray("details")?.let { arr ->
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        }
        list
    }
    val labelsByKey = remember(detailsList) {
        detailsList.associate { d -> d.optString("key") to d.optString("label").ifBlank { d.optString("key") } }
    }
    // Accent choices for this drill: its own accent_candidates (data/listening_drills.json) when
    // present, otherwise every accent PromptBuilder knows how to voice — same taxonomy Survival/
    // Encounter's accent pickers use, so a key here always resolves to a real instruction.
    val accentChoices = remember(drillJson) {
        val candidates = drillJson.optJSONArray("accent_candidates")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        (candidates.ifEmpty { PromptBuilder.ACCENT_KEYS }).map { key ->
            LabeledChoice(key, PromptBuilder.accentLabel(key))
        }
    }
    var accentKey by remember(accentChoices) { mutableStateOf(accentChoices.firstOrNull()?.key ?: "us") }
    var genderKey by remember { mutableStateOf("") }
    var accentMenuExpanded by remember { mutableStateOf(false) }
    var genderMenuExpanded by remember { mutableStateOf(false) }
    // The last successfully fetched cloud clip for the CURRENT accent/gender choice, kept so
    // "Ask more slowly" can time-stretch it locally instead of paying for another synthesis call.
    // Scoped to (accentKey, genderKey) so switching either invalidates the stale clip automatically.
    var lastCloudClip by remember(accentKey, genderKey) { mutableStateOf<Pair<ByteArray, Int>?>(null) }
    var lastVoiceName by remember(accentKey, genderKey) { mutableStateOf<String?>(null) }

    val answers = remember { mutableStateMapOf<String, String>() }
    var hasSubmitted by remember { mutableStateOf(false) }
    var scoreResult by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var replayCount by remember { mutableStateOf(0) }
    var cleanReplayCount by remember { mutableStateOf(0) }
    var hasPlayedOnce by remember { mutableStateOf(false) }
    val explicitRepairs = remember { mutableStateListOf<String>() }
    var attemptId by remember { mutableStateOf<Int?>(null) }
    var statusText by remember { mutableStateOf("") }
    var authenticity by remember { mutableStateOf<Int?>(null) }
    var accentMatch by remember { mutableStateOf<Int?>(null) }
    var naturalness by remember { mutableStateOf<Int?>(null) }
    var ratingsSaved by remember { mutableStateOf(false) }

    val attempts by viewModel.listeningAttempts.collectAsStateWithLifecycle()
    val progress = remember(attempts) {
        ListeningDrillEngine.progressSummary(attempts.map { it.toAnalysisMap() })
    }

    fun syncAssistanceIfCommitted() {
        val id = attemptId ?: return
        viewModel.logListeningAssistance(id, replayCount, cleanReplayCount, 1, explicitRepairs.toList())
    }

    fun speakOnDevice(rate: Float) {
        if (!isTtsReady || transcript.isEmpty()) return
        tts?.setSpeechRate(rate)
        tts?.language = Locale.US
        isPlaying = true
        tts?.speak(transcript, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
    }

    fun playPcm(pcm: ByteArray, sampleRate: Int) {
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        isPlaying = true
        val track = playAttemptPcm(pcm, sampleRate) { isPlaying = false }
        audioTrack = track
        if (track == null) {
            isPlaying = false
            playbackError = true
        }
    }

    fun stretched(pcm: ByteArray, sampleRate: Int, rate: Float): ByteArray? = runCatching {
        val stretcher = WsolaStretcher(sampleRate)
        stretcher.setSpeed(rate)
        stretcher.process(pcm) + stretcher.finish()
    }.getOrNull()

    /** Plays [transcript] at [rate] (1.0 = normal, <1.0 = slower for "ask more slowly"). */
    fun speak(rate: Float) {
        if (transcript.isEmpty() || isPlaying || isSynthesizing) return
        if (!hasPlayedOnce) hasPlayedOnce = true else replayCount++
        syncAssistanceIfCommitted()
        playbackError = false

        if (!cloudTtsAvailable) {
            speakOnDevice(rate)
            return
        }

        val cached = lastCloudClip
        if (cached != null) {
            val (pcm, sampleRate) = cached
            if (rate == 1.0f) playPcm(pcm, sampleRate) else stretched(pcm, sampleRate, rate)?.let { playPcm(it, sampleRate) }
                ?: speakOnDevice(rate)
            return
        }

        isSynthesizing = true
        scope.launch {
            val narration = viewModel.synthesizeListeningNarration(drillId, transcript, accentKey, genderKey)
            isSynthesizing = false
            if (narration == null) {
                speakOnDevice(rate)
                return@launch
            }
            lastCloudClip = narration.pcm to narration.sampleRate
            lastVoiceName = narration.voiceName
            if (rate == 1.0f) {
                playPcm(narration.pcm, narration.sampleRate)
            } else {
                val slow = stretched(narration.pcm, narration.sampleRate, rate)
                playPcm(slow ?: narration.pcm, narration.sampleRate)
            }
        }
    }

    fun askToRepeat() {
        explicitRepairs.add("repeat")
        speak(1.0f)
    }

    fun askMoreSlowly() {
        explicitRepairs.add("slow")
        cleanReplayCount++
        speak(0.8f)
    }

    fun commit() {
        if (hasSubmitted) return
        val answerDetails = detailsList.associate { detail ->
            val key = detail.optString("key")
            key to (answers[key]?.trim() ?: "")
        }
        if (answerDetails.values.none { it.isNotBlank() }) {
            statusText = t("Type what you heard before checking the transcript.")
            return
        }
        val score = ListeningDrillEngine.scoreDetailRecall(drillMap, answerDetails)
        val answerText = answerDetails.filterValues { it.isNotBlank() }
            .entries.joinToString("; ") { (key, value) -> "${labelsByKey[key] ?: key}: $value" }
        val typedRepairs = ListeningDrillEngine.detectRepairStrategies(answerText)
        val repairs = (explicitRepairs + typedRepairs).filter { it.isNotBlank() }.distinct()
        explicitRepairs.clear()
        explicitRepairs.addAll(repairs)

        val correct = (score["details_correct"] as? Int) ?: 0
        val total = (score["details_total"] as? Int) ?: 0
        statusText = ""
        viewModel.saveListeningAttempt(
            drillId = drillId,
            category = category,
            difficulty = difficulty,
            skillTags = skillTags,
            score = correct,
            total = total,
            replayCount = replayCount,
            cleanReplayCount = cleanReplayCount,
            repairStrategies = repairs,
            accent = accentKey,
            voiceName = lastVoiceName,
            provider = if (lastCloudClip != null) "gemini" else null,
            audioSource = if (lastCloudClip != null) "generated_tts" else "device_tts",
            onSaved = { id ->
                attemptId = id
                hasSubmitted = true
                scoreResult = score
                // Mirrors listening_lab_window.py's _commit(): the result panel reveals the
                // transcript immediately, so this logs that reveal right away instead of waiting
                // on a separate button press.
                viewModel.logListeningAssistance(id, replayCount, cleanReplayCount, 1, repairs)
            }
        )
    }

    val detailResultsByKey = remember(scoreResult) {
        (scoreResult?.get("detail_results") as? List<Map<String, Any?>>)?.associateBy { it["key"].toString() } ?: emptyMap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // §9 header: "🎧 {category} · Level {N}", with a back arrow — previously a static
        // "Listening Lab" title with no way to leave before committing an answer.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = t("Back"))
            }
            Text(
                text = "🎧 ${category?.replaceFirstChar { it.uppercase() } ?: "Listening"} · Level $difficulty",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "Progress: ${progress["details_correct"]}/${progress["details_total"]} exact details · " +
                "${progress["first_pass_successes"]} first-pass clips · ${progress["repair_count"]} repairs · " +
                "next focus: ${progress["weakest_tag"] ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Context",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = drillContext,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (cloudTtsAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val selectedAccent = accentChoices.firstOrNull { it.key == accentKey } ?: accentChoices.first()
                ExposedDropdownMenuBox(
                    modifier = Modifier.weight(1f),
                    expanded = accentMenuExpanded,
                    onExpandedChange = { if (!hasSubmitted) accentMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedAccent.label,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !hasSubmitted,
                        label = { Text(t("Accent")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accentMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = accentMenuExpanded, onDismissRequest = { accentMenuExpanded = false }) {
                        accentChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = { accentKey = choice.key; accentMenuExpanded = false },
                            )
                        }
                    }
                }
                val selectedGender = GENDER_CHOICES.first { it.key == genderKey }
                ExposedDropdownMenuBox(
                    modifier = Modifier.weight(1f),
                    expanded = genderMenuExpanded,
                    onExpandedChange = { if (!hasSubmitted) genderMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedGender.label,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !hasSubmitted,
                        label = { Text(t("Voice")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genderMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = genderMenuExpanded, onDismissRequest = { genderMenuExpanded = false }) {
                        GENDER_CHOICES.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = { genderKey = choice.key; genderMenuExpanded = false },
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (isPlaying) {
                        tts?.stop()
                        runCatching { audioTrack?.stop(); audioTrack?.release() }
                        audioTrack = null
                        isPlaying = false
                    } else {
                        speak(1.0f)
                    }
                },
                enabled = (isTtsReady || cloudTtsAvailable) && !isSynthesizing,
                modifier = Modifier.weight(1f)
            ) {
                if (isSynthesizing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSynthesizing) t("Loading…") else if (isPlaying) t("Stop") else t("Play"))
            }
            OutlinedButton(
                onClick = ::askToRepeat,
                enabled = (isTtsReady || cloudTtsAvailable) && !isPlaying && !isSynthesizing,
                modifier = Modifier.weight(1f),
            ) {
                Text("↻ " + t("Ask to repeat"))
            }
            OutlinedButton(
                onClick = ::askMoreSlowly,
                enabled = (isTtsReady || cloudTtsAvailable) && !isPlaying && !isSynthesizing,
                modifier = Modifier.weight(1f),
            ) {
                Text("🐢 " + t("More slowly"))
            }
        }
        if (playbackError) {
            Text(
                "Couldn't play that clip — try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // §9: reframes repair strategies as a practiced skill, not a penalty — matches the
        // spec's inline hint, since "assisted" reps are scheduled deliberately, not marked down.
        Text(
            "ⓘ Using \"repeat\" or \"slower\" is a skill, not a penalty — it schedules an assisted rep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text(
            text = "Commit what you heard",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Type the exact names, numbers, times, prices, or directions you heard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        detailsList.forEach { detail ->
            val key = detail.optString("key")
            val label = labelsByKey[key] ?: key
            val result = detailResultsByKey[key]
            val isCorrect = result?.get("correct") == true

            OutlinedTextField(
                value = answers[key] ?: "",
                onValueChange = { if (!hasSubmitted) answers[key] = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !hasSubmitted,
                trailingIcon = {
                    if (hasSubmitted) {
                        if (isCorrect) {
                            Icon(Icons.Default.CheckCircle, contentDescription = t("Correct"), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.Cancel, contentDescription = t("Incorrect"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
            if (hasSubmitted && !isCorrect) {
                val accepted = (detail.optJSONArray("answers"))?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
                if (accepted.isNotEmpty()) {
                    Text(
                        text = "Correct answers: ${accepted.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (statusText.isNotBlank()) {
            Text(statusText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (!hasSubmitted) {
            Button(onClick = ::commit, modifier = Modifier.fillMaxWidth()) {
                Text("✓ " + t("Commit answer before revealing"))
            }
        } else {
            val correct = (scoreResult?.get("details_correct") as? Int) ?: 0
            val total = (scoreResult?.get("details_total") as? Int) ?: 0
            val missed = detailResultsByKey.values.filter { it["correct"] != true }.mapNotNull { it["label"]?.toString() }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Score: $correct / $total",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Replays: $replayCount · Repairs: ${explicitRepairs.joinToString(", ").ifBlank { "none" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (missed.isNotEmpty()) {
                        Text(
                            text = "Missed: ${missed.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Transcript:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Optional field validation (1–5)",
                        style = MaterialTheme.typography.labelMedium
                    )
                    RatingRow("Ordinary/real", authenticity) { authenticity = it }
                    RatingRow("Accent match", accentMatch) { accentMatch = it }
                    RatingRow("Naturalness", naturalness) { naturalness = it }
                    Button(
                        onClick = {
                            attemptId?.let { id ->
                                viewModel.saveListeningRatings(id, authenticity, accentMatch, naturalness)
                                ratingsSaved = true
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(if (ratingsSaved) t("Ratings saved") else t("Save ratings"))
                    }
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t("End Drill"))
            }
        }
    }
}

@Composable
private fun RatingRow(label: String, value: Int?, onChange: (Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { n ->
                FilterChip(
                    selected = value == n,
                    onClick = { onChange(if (value == n) null else n) },
                    label = { Text("$n") }
                )
            }
        }
    }
}
