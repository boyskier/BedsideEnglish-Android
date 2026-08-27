package com.example.medvoicetrainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import com.example.medvoicetrainer.analysis.SayItPhraseProgress
import com.example.medvoicetrainer.analysis.EverydayPhrase
import com.example.medvoicetrainer.analysis.SayItProgressTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One theme of high-frequency chunks (from `foundations_phrases.json` / `everyday_phrases.json`). */
private data class PhraseCategory(
    val id: String,
    val name: String,
    val phrases: List<EverydayPhrase>,
)

/**
 * Which bank the drill is showing.
 *
 * Three sources rather than three screens: the record-listen-judge loop, the phrase progress, the
 * kept takes and the "two clear takes finishes it" rule are identical whether the sentence is
 * "What brings you in today?" or "Sorry, could you say that again?". Only the content differs, and
 * [SayItProgressTracker.phraseId] already keys on `category|phrase`, so the three cannot collide.
 */
private enum class PhraseSource(val labelKey: String) {
    CLINICAL("sayit.source.clinical"),
    EVERYDAY("sayit.source.everyday"),
    SESSION("sayit.source.session"),

    /**
     * The learner's own kept expressions — the ones the feedback screen offered as a better way to
     * say something they actually said. This is the return path that makes the whole loop close:
     * without it a correction is read once and never met again.
     */
    MINE("sayit.source.mine"),
}

private fun loadPhraseCategories(
    viewModel: MainViewModel,
    source: PhraseSource,
    sessionPhrases: List<EverydayPhrase>,
): List<PhraseCategory> {
    return try {
        when (source) {
            PhraseSource.CLINICAL -> parseStringBank(viewModel.loadAsset("foundations_phrases.json"))
            PhraseSource.EVERYDAY -> viewModel.everydayPhraseBank().map { category ->
                PhraseCategory(category.id, category.name, category.phrases)
            }

            PhraseSource.SESSION -> sessionPhraseCategories(sessionPhrases)

            PhraseSource.MINE -> com.example.medvoicetrainer.analysis.MyPhrasebook
                .asCategory(viewModel.myPhrasebook.value)
                ?.let { listOf(PhraseCategory(it.id, it.name, it.phrases)) }
                .orEmpty()
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        emptyList()
    }
}

private fun sessionPhraseCategories(phrases: List<EverydayPhrase>): List<PhraseCategory> =
    phrases.groupBy { phrase -> phrase.categoryId.ifBlank { phrase.categoryName.ifBlank { "session" } } }
        .map { (id, entries) ->
            PhraseCategory(
                id = id,
                name = entries.first().categoryName.ifBlank { "This session" },
                phrases = entries,
            )
        }

/** The desktop-era `{categories: [{name, phrases: ["…"]}]}` shape. */
private fun parseStringBank(json: String): List<PhraseCategory> {
    val root = JSONObject(json)
    val cats = root.optJSONArray("categories") ?: return emptyList()
    return (0 until cats.length()).mapNotNull { i ->
        val obj = cats.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name", "").trim()
        val arr = obj.optJSONArray("phrases")
        val phrases = if (arr == null) emptyList() else {
            (0 until arr.length()).mapNotNull { j ->
                arr.optString(j, null)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        if (name.isEmpty() || phrases.isEmpty()) null else PhraseCategory(
            id = name,
            name = name,
            phrases = phrases.map { text ->
                EverydayPhrase(
                    en = text,
                    function = "",
                    gloss = null,
                    register = null,
                    why = null,
                    skeleton = com.example.medvoicetrainer.analysis.EverydayPhrasebook.skeletonFor(text),
                    categoryId = name,
                    categoryName = name,
                )
            },
        )
    }
}

/**
 * What the learner's saved progress on one phrase actually says, in words.
 *
 * The three states have to look different: a phrase that passed once is one clear take from being
 * finished, while a phrase that came out unclear needs another try now. Rendering both as a bare
 * "↻" — which is what a pass with no focus words used to produce — told a learner who had just been
 * told "comfortably understood" that something had gone wrong, and hid the fact that the phrase was
 * already halfway to done.
 */
@Composable
private fun PhraseProgressLine(progress: SayItPhraseProgress?) {
    if (progress == null || progress.attempts == 0) return
    val t = LocalTranslate.current
    val text: String
    val color: Color
    when {
        progress.mastered -> {
            text = "✓ ${t("sayit.status_done")}"
            color = MaterialTheme.colorScheme.primary
        }

        progress.consecutivePasses > 0 -> {
            val streak = "${progress.consecutivePasses}/${SayItProgressTracker.PASS_TO_MASTER}"
            text = "✓ $streak  ·  ${t("sayit.status_one_more")}"
            color = MaterialTheme.colorScheme.primary
        }

        else -> {
            val focus = progress.focusWords.takeIf { it.isNotEmpty() }?.joinToString(", ")
            text = "↻ ${t("sayit.status_retry")}" + (focus?.let { "  ·  $it" } ?: "")
            color = MaterialTheme.colorScheme.secondary
        }
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * "Say It" — a listen-and-repeat drill for the true beginner (the language-production problem, as
 * opposed to the clinical-schema problem the encounter loops train). Each high-frequency clinical
 * chunk is modelled by TTS, the learner records themselves, and Gemini judges intelligibility —
 * decoupled from any live patient roleplay so a total beginner can drill pronunciation and memorise
 * the phrases without the cognitive load of a real-time conversation.
 *
 * Deliberately reuses [PronunciationAttemptPanel] / [rememberEnglishTts] / judgeSpeakingAttempt so
 * the model-audio-needs-a-human-instructor problem never arises: the "native model" is TTS, and the
 * "grading" is the same intelligibility judge the SRS and feedback screens already use.
 */
@Composable
fun ListenAndRepeatScreen(
    viewModel: MainViewModel,
    initialEveryday: Boolean = false,
    initialSessionPhrases: List<EverydayPhrase> = emptyList(),
    // Lets the host (PracticeScreen) drop its own header/tab strip once a topic is open, the same
    // way the Listening Lab's drill goes full-screen — a topic already has its own back arrow, so
    // the outer chrome above it only duplicates that and eats vertical space.
    onTopicOpenChanged: (Boolean) -> Unit = {},
) {
    val t = LocalTranslate.current
    var source by rememberSaveable {
        mutableStateOf(
            when {
                initialSessionPhrases.isNotEmpty() -> PhraseSource.SESSION
                initialEveryday -> PhraseSource.EVERYDAY
                else -> PhraseSource.CLINICAL
            }
        )
    }
    var categories by remember { mutableStateOf<List<PhraseCategory>?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val progress by viewModel.sayItProgress.collectAsStateWithLifecycle()
    val takes by viewModel.sayItTakes.collectAsStateWithLifecycle()
    val mine by viewModel.myPhrasebook.collectAsStateWithLifecycle()

    // Keyed on `mine` as well as the source so an expression kept from the feedback screen appears
    // here without the learner having to leave the tab and come back.
    LaunchedEffect(source, mine, initialSessionPhrases) {
        // An index belongs to the bank it was taken from, so switching banks always returns to the
        // topic list. The previous bank's list stays on screen until the new one is ready rather
        // than being replaced by a spinner — blanking it would take the source chips away for the
        // length of the load, exactly when the learner is using them.
        selectedIndex = null
        categories = withContext(Dispatchers.IO) {
            loadPhraseCategories(viewModel, source, initialSessionPhrases)
        }
    }

    val ttsHandle = rememberEnglishTts()
    val takePlayer = rememberSavedTakePlayer()
    val judgeAvailable = remember { viewModel.isSpeakingJudgeAvailable() }

    val loaded = categories
    if (loaded == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val selected = selectedIndex?.let { loaded.getOrNull(it) }
    androidx.compose.runtime.DisposableEffect(selected != null) {
        onTopicOpenChanged(selected != null)
        onDispose { onTopicOpenChanged(false) }
    }
    if (selected == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        t("sayit.title"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (source == PhraseSource.CLINICAL) t("sayit.intro") else t("sayit.intro_everyday"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!judgeAvailable) {
                        Text(
                            t("sayit.no_judge"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // One drill, three banks. A segmented row rather than three entry points scattered
            // across the app: the learner who has just kept an expression from their feedback needs
            // to find it next to the phrases they were already drilling, not in a fourth place.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhraseSource.entries
                        .filter { it != PhraseSource.SESSION || initialSessionPhrases.isNotEmpty() }
                        .forEach { option ->
                        FilterChip(
                            selected = source == option,
                            onClick = { source = option },
                            label = { Text(t(option.labelKey)) },
                        )
                    }
                }
            }
            if (loaded.isEmpty()) {
                item {
                    Text(
                        if (source == PhraseSource.MINE) t("sayit.mine_empty") else t("sayit.bank_empty"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(loaded) { index, category ->
                val states = category.phrases.map { phrase ->
                    progress[SayItProgressTracker.phraseId(category.name, phrase.en)]
                }
                val completed = states.count { it?.mastered == true }
                // A phrase needs two clear takes to count as done, so without this line a learner
                // who just passed one sees the same "0/n ✓" as before they started and reasonably
                // concludes the check did nothing.
                val started = states.count { it != null && it.attempts > 0 && !it.mastered }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedIndex = index },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Hearing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                category.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                buildString {
                                    append(t("sayit.phrase_count").replace("{n}", category.phrases.size.toString()))
                                    append("  ·  $completed/${category.phrases.size} ✓")
                                    if (started > 0) {
                                        append("  ·  ")
                                        append(t("sayit.in_progress").replace("{n}", started.toString()))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }

    // Keep the full catalogue visible, but make the next useful repetition the first thing the
    // learner sees: due/weak → not-yet-attempted → scheduled → mastered confidence checks.
    //
    // Ordered once per visit to this topic, on purpose: recomputing it live would re-sort the list
    // the instant a verdict lands, sliding the card the learner is working in out from under their
    // finger. The new order is what they get when they come back.
    val orderedPhrases = remember(selected.name, selected.phrases) {
        selected.phrases.withIndex()
            .sortedWith(
                compareBy<IndexedValue<EverydayPhrase>> {
                    SayItProgressTracker.priority(
                        progress[SayItProgressTracker.phraseId(selected.name, it.value.en)]
                    )
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            TextButton(onClick = { selectedIndex = null }) {
                Icon(Icons.Default.ArrowBack, contentDescription = t("sayit.back"))
                Spacer(Modifier.width(6.dp))
                Text(t("sayit.back"))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    selected.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(orderedPhrases, key = { phrase -> SayItProgressTracker.phraseId(selected.name, phrase.en) }) { phrase ->
                val phraseId = SayItProgressTracker.phraseId(selected.name, phrase.en)
                val phraseProgress = progress[phraseId]
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "\"${phrase.en}\"",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (phrase.function.isNotBlank() || !phrase.gloss.isNullOrBlank()) {
                            Text(
                                listOfNotNull(
                                    phrase.function.takeIf { it.isNotBlank() },
                                    phrase.gloss?.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            phrase.register?.let { register ->
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(t("phrasebook.register.${register.key}")) },
                                )
                            }
                            phrase.skeleton.takeIf { it.isNotBlank() && it != phrase.en }?.let { skeleton ->
                                Text(
                                    skeleton,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        phrase.why?.takeIf { it.isNotBlank() }?.let { why ->
                            Text(
                                why,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PhraseProgressLine(phraseProgress)
                        PronunciationAttemptPanel(
                            targetText = phrase.en,
                            ttsReady = ttsHandle.ready,
                            onSpeakTarget = { ttsHandle.speak(phrase.en) },
                            judge = if (judgeAvailable) {
                                { pcm -> viewModel.judgeSpeakingAttempt(targetText = phrase.en, audioPcm = pcm) }
                            } else {
                                null
                            },
                            onVerdict = { judgment ->
                                viewModel.recordSayItVerdict(selected.name, phrase.en, judgment)
                            },
                            onAttemptRecorded = { pcm ->
                                viewModel.saveSayItTake(selected.name, phrase.en, pcm)
                            },
                        )
                        SavedTakesSection(
                            takes = takes[phraseId].orEmpty(),
                            resolve = viewModel::resolveLearnerAudio,
                            player = takePlayer,
                            onDelete = { take ->
                                viewModel.deleteSayItTake(selected.name, phrase.en, take.path)
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}
