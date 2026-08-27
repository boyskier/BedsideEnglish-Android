package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.api.DEFAULT_GEMINI_ANALYSIS_MODEL
import com.example.medvoicetrainer.api.AzurePronunciationEvidence
import com.example.medvoicetrainer.api.AzureSpeechService
import com.example.medvoicetrainer.api.GeminiService
import com.example.medvoicetrainer.api.withGeminiAudioFallbackTracked
import com.example.medvoicetrainer.api.withGeminiTextFallbackTracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PronunciationCorrection(
    val turnIndex: Int? = null,
    val original: String,
    val corrected: String,
    val explanation: String,
    val category: String = "pronunciation",
    val severity: String = "moderate",
    val confidence: Double = 1.0,
    val pattern: String = "other",
    val startMs: Int? = null,
    val endMs: Int? = null
)

data class BlindIntelligibilityObservation(
    val turnIndex: Int,
    val expectedTranscript: String,
    val heardTranscript: String,
    val uncertainWords: List<String>,
    val comprehensibility: Int,
    val confidence: Double,
    val criticalItemsClear: Boolean,
    val wordMatchRate: Double,
    val criticalDifferences: List<CriticalMeaningDifference>,
    val outcome: IntelligibilityOutcome
) {
    val understood: Boolean = outcome == IntelligibilityOutcome.COMFORTABLE
}

data class PronunciationAnalysisResult(
    val corrections: List<PronunciationCorrection>,
    val blindObservations: List<BlindIntelligibilityObservation>,
    val blindModelUsed: String = "",
    val diagnosticModelUsed: String = ""
) {
    fun acousticMetrics(): Map<String, Any> {
        if (blindObservations.isEmpty()) return emptyMap()
        val understood = blindObservations.count { it.understood }
        val overallOutcome = when {
            blindObservations.any { it.outcome == IntelligibilityOutcome.CRITICAL_MISMATCH } ->
                IntelligibilityOutcome.CRITICAL_MISMATCH
            blindObservations.any { it.outcome == IntelligibilityOutcome.COULD_NOT_ASSESS } ->
                IntelligibilityOutcome.COULD_NOT_ASSESS
            blindObservations.any { it.outcome == IntelligibilityOutcome.EFFORTFUL } ->
                IntelligibilityOutcome.EFFORTFUL
            else -> IntelligibilityOutcome.COMFORTABLE
        }
        val grade = when (overallOutcome) {
            IntelligibilityOutcome.COMFORTABLE ->
                "Comfortably understood by the blind AI listener"
            IntelligibilityOutcome.EFFORTFUL ->
                "Understood with some listener effort"
            IntelligibilityOutcome.CRITICAL_MISMATCH ->
                "A meaning-critical word sounded different"
            IntelligibilityOutcome.COULD_NOT_ASSESS ->
                "Some audio could not be assessed reliably"
        }
        return mapOf(
            "acoustic_measured" to true,
            "acoustic_outcome" to overallOutcome.wireName,
            "acoustic_grade" to grade,
            "blind_understood_turns" to understood,
            "blind_total_turns" to blindObservations.size,
            "blind_attention_turns" to (blindObservations.size - understood),
            "blind_observations" to blindObservations.sortedBy { it.turnIndex }.map { observation ->
                mapOf(
                    "turn_index" to observation.turnIndex,
                    "intended" to observation.expectedTranscript,
                    "heard" to observation.heardTranscript,
                    "outcome" to observation.outcome.wireName,
                    "uncertain_words" to observation.uncertainWords,
                    "critical_differences" to observation.criticalDifferences.map { difference ->
                        mapOf(
                            "type" to difference.type,
                            "expected" to difference.expected,
                            "heard" to (difference.heard ?: "")
                        )
                    }
                )
            },
            "blind_model_used" to blindModelUsed,
            "diagnostic_model_used" to diagnosticModelUsed,
            "acoustic_principle" to
                "The first AI listener wrote what it heard without seeing the script. " +
                    "Only then did the app compare intended meaning and diagnose likely blockers."
        )
    }
}

/**
 * Lazy source for one learner turn. Audio is kept on disk until its batch is sent so a long
 * session does not become an equally long in-memory PCM buffer.
 */
data class PronunciationAudioSource(
    val turnIndex: Int,
    val transcriptText: String,
    val byteSize: Int,
    val mimeType: String = "audio/wav",
    val loadAudio: () -> ByteArray
)

/**
 * Wall-clock guard for the audio judges.
 *
 * Pronunciation is a supplementary signal on a screen the learner is already waiting on, so it
 * runs under a fixed time budget. Wrapping the whole analysis in `withTimeoutOrNull` was the wrong
 * shape for that: a long session is split into several batches, and cancelling mid-run threw away
 * every batch that had already completed — the learner waited the full budget and got nothing.
 * This lets the engines check the budget *between* provider calls, stop cleanly, and return the
 * evidence they actually collected. A null budget means "no limit" (tests, focused speaking checks).
 */
internal class PronunciationTimeBudget(private val budgetMs: Long?) {
    private val startedAt = System.nanoTime()

    fun remainingMs(): Long =
        budgetMs?.let { it - (System.nanoTime() - startedAt) / 1_000_000 } ?: Long.MAX_VALUE

    /** True when too little time is left to be worth starting another provider round-trip. */
    fun spent(minimumMs: Long = MIN_PRONUNCIATION_CALL_BUDGET_MS): Boolean = remainingMs() < minimumMs
}

/** Below this there is no realistic chance of a multimodal round-trip returning in time. */
private const val MIN_PRONUNCIATION_CALL_BUDGET_MS = 5_000L

object PronunciationEngine {

    /**
     * Maximum raw audio bytes per request. This is a request-batch cap, not a session cap:
     * longer sessions are split into as many batches as needed.
     */
    const val MAX_AUDIO_BYTES = 6 * 1024 * 1024
    const val MIN_AUDIO_BYTES = 3_200
    const val MAX_CORRECTIONS = 5
    internal const val MIN_CONFIDENCE = 0.75
    internal val PATTERNS = setOf(
        "r_l", "f_p", "th", "final_consonant", "consonant_cluster",
        "word_stress", "sentence_stress", "rhythm", "intonation", "linking_reduction",
        "inserted_vowel", "vowel", "other"
    )

    private val SEVERITY_RANK = mapOf("major" to 2, "moderate" to 1)

    /**
     * One finding per target, worst first, capped at [MAX_CORRECTIONS].
     *
     * Was written out identically inside both engines; extracted so the two cannot drift apart
     * and so the cross-language golden harness can pin it (ledger LOG-04).
     */
    internal fun rankCorrections(findings: List<PronunciationCorrection>): List<PronunciationCorrection> =
        findings
            .groupBy { normalizeWords(it.corrected) }
            .mapNotNull { (_, sameTarget) ->
                sameTarget.maxWithOrNull(
                    compareBy<PronunciationCorrection> { SEVERITY_RANK[it.severity] ?: 0 }
                        .thenBy { it.confidence }
                )
            }
            .sortedWith(
                compareByDescending<PronunciationCorrection> { SEVERITY_RANK[it.severity] ?: 0 }
                    .thenByDescending { it.confidence }
            )
            .take(MAX_CORRECTIONS)

    internal fun blindResponseSchema(): JSONObject = JSONObject()
        .put("type", "ARRAY")
        .put("maxItems", 12)
        .put(
            "items",
            JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("turn_index", JSONObject().put("type", "INTEGER"))
                        .put("heard_transcript", JSONObject().put("type", "STRING"))
                        .put(
                            "uncertain_words",
                            JSONObject()
                                .put("type", "ARRAY")
                                .put("items", JSONObject().put("type", "STRING"))
                        )
                        .put(
                            "comprehensibility",
                            JSONObject().put("type", "INTEGER").put("minimum", 1).put("maximum", 5)
                        )
                        .put("critical_items_clear", JSONObject().put("type", "BOOLEAN"))
                        .put(
                            "confidence",
                            JSONObject().put("type", "NUMBER").put("minimum", 0).put("maximum", 1)
                        )
                )
                .put(
                    "required",
                    JSONArray(
                        listOf(
                            "turn_index", "heard_transcript", "uncertain_words",
                            "comprehensibility", "critical_items_clear", "confidence"
                        )
                    )
                )
        )

    internal fun responseSchema(): JSONObject = JSONObject()
        .put("type", "ARRAY")
        .put("maxItems", 3)
        .put(
            "items",
            JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("turn_index", JSONObject().put("type", "INTEGER"))
                        .put("target", JSONObject().put("type", "STRING"))
                        .put("heard_as", JSONObject().put("type", "STRING"))
                        .put("issue", JSONObject().put("type", "STRING"))
                        .put("coaching_tip", JSONObject().put("type", "STRING"))
                        .put("severity", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("moderate", "major"))))
                        .put("confidence", JSONObject().put("type", "NUMBER").put("minimum", 0).put("maximum", 1))
                        .put("pattern", JSONObject().put("type", "STRING").put("enum", JSONArray(PATTERNS.toList())))
                        .put("start_ms", JSONObject().put("type", "INTEGER").put("minimum", 0))
                        .put("end_ms", JSONObject().put("type", "INTEGER").put("minimum", 0))
                )
                .put(
                    "required",
                    JSONArray(
                        listOf(
                            "turn_index", "target", "heard_as", "issue", "coaching_tip",
                            "severity", "confidence", "pattern", "start_ms", "end_ms"
                        )
                    )
                )
        )

    internal fun l1Name(l1Lang: String): String =
        L1InterferenceCatalog.profile(l1Lang)?.name ?: "unknown"

    internal fun buildIndexedUserTranscript(transcript: List<Map<String, Any?>>): String =
        transcript.withIndex()
            .filter { (_, turn) -> turn["role"] == "user" }
            .joinToString("\n") { (index, turn) -> "[turn $index] ${turn["text"]?.toString().orEmpty()}" }

    internal fun parseTurnIndex(item: JSONObject, transcript: List<Map<String, Any?>>): Int? {
        if (!item.has("turn_index") || item.isNull("turn_index")) return null
        val index = item.optInt("turn_index", -1)
        if (index !in transcript.indices) return null
        return index.takeIf { transcript[it]["role"] == "user" }
    }

    /** Greedy ordered batches preserve all turns while bounding each inline request. */
    internal fun batchSources(
        sources: List<PronunciationAudioSource>,
        maxBytes: Int = MAX_AUDIO_BYTES
    ): List<List<PronunciationAudioSource>> {
        require(maxBytes > 0)
        val batches = mutableListOf<MutableList<PronunciationAudioSource>>()
        var current = mutableListOf<PronunciationAudioSource>()
        var currentBytes = 0L
        for (source in sources.sortedBy { it.turnIndex }) {
            if (source.byteSize < MIN_AUDIO_BYTES || source.transcriptText.isBlank()) continue
            if (current.isNotEmpty() && currentBytes + source.byteSize > maxBytes) {
                batches.add(current)
                current = mutableListOf()
                currentBytes = 0L
            }
            current.add(source)
            currentBytes += source.byteSize.toLong()
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    internal fun buildBlindPrompt(
        sources: List<PronunciationAudioSource>,
        domain: String
    ): String {
        val setting = if (domain.lowercase(Locale.ROOT) in setOf("everyday", "survival")) {
            "everyday conversation"
        } else {
            "medical or professional conversation"
        }
        val labels = sources.joinToString(", ") { "[turn ${it.turnIndex}]" }
        return """
            Act as a careful first-time listener in $setting. You do NOT have a reference
            transcript and must not guess from a presumed script. The attached audio clips are
            labelled $labels.

            For every clip:
            - Write exactly what you believe you heard in heard_transcript.
            - Put uncertain or guessed words in uncertain_words.
            - Rate comprehensibility from 1 (very hard) to 5 (effortless).
            - critical_items_clear is false if any medication, dose, number, negation,
              laterality, time, name, or other meaning-critical item is hard to hear. You do not
              know the intended wording, so judge clarity only; do not claim meaning was preserved.
            - confidence describes your confidence in this blind listening judgment.
            - Do not comment on accent and do not repair grammar.

            Return only the requested JSON array, one item per labelled turn.
        """.trimIndent()
    }

    internal fun parseBlindResponse(
        raw: String,
        sources: List<PronunciationAudioSource>
    ): List<BlindIntelligibilityObservation> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.split("```").getOrElse(1) { text }
            if (text.startsWith("json")) text = text.substring(4)
        }
        val items = try {
            JSONArray(text.trim())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            return emptyList()
        }
        val sourceByTurn = sources.associateBy { it.turnIndex }
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val turnIndex = item.optInt("turn_index", -1)
                val source = sourceByTurn[turnIndex] ?: continue
                val rawHeard = item.optString("heard_transcript").trim()
                val confidence = item.optDouble("confidence", 0.0)
                val comprehensibility = item.optInt("comprehensibility", 0)
                if (confidence !in 0.0..1.0 || comprehensibility !in 1..5) continue
                val heard = if (rawHeard.isBlank()) {
                    if (comprehensibility == 1) "[No reliable words heard]" else continue
                } else {
                    rawHeard
                }
                val uncertain = item.optJSONArray("uncertain_words")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).trim().takeIf { it.isNotBlank() }
                    }.take(12)
                }.orEmpty()
                val criticalItemsClear = when {
                    item.has("critical_items_clear") -> item.optBoolean("critical_items_clear", false)
                    // Backward-compatible parser for responses from an in-flight older prompt.
                    else -> item.optBoolean("critical_meaning_preserved", false)
                }
                val assessment = IntelligibilityComparator.assess(
                    expected = source.transcriptText,
                    heard = heard,
                    uncertainWords = uncertain,
                    comprehensibility = comprehensibility,
                    confidence = confidence,
                    criticalItemsClear = criticalItemsClear
                )
                add(
                    BlindIntelligibilityObservation(
                        turnIndex = turnIndex,
                        expectedTranscript = source.transcriptText.take(900),
                        heardTranscript = heard.take(900),
                        uncertainWords = uncertain,
                        comprehensibility = comprehensibility,
                        confidence = confidence,
                        criticalItemsClear = criticalItemsClear,
                        wordMatchRate = assessment.wordMatchRate,
                        criticalDifferences = assessment.criticalDifferences,
                        outcome = assessment.outcome
                    )
                )
            }
        }
    }

    /** One-line instruction to localise coaching prose (not the target word) into the learner's L1. */
    internal fun coachingLanguageNote(explanationLanguage: String): String =
        if (explanationLanguage.isBlank()) {
            ""
        } else {
            "\n- Write the issue and coaching_tip text in the learner's native language " +
                "($explanationLanguage). Keep target, heard_as, and pattern in English."
        }

    internal fun buildBatchPrompt(
        sources: List<PronunciationAudioSource>,
        l1Lang: String,
        domain: String,
        blindObservations: List<BlindIntelligibilityObservation> = emptyList(),
        explanationLanguage: String = ""
    ): String {
        val everyday = domain.lowercase(Locale.ROOT) in setOf("everyday", "survival")
        val setting = if (everyday) "practical everyday English" else "medical or professional English"
        val l1 = l1Name(l1Lang)
        val focus = L1InterferenceCatalog.profile(l1Lang)?.pronunciationFocus
            ?: "segmental clarity, word stress, and rhythm"
        val blindByTurn = blindObservations.associateBy { it.turnIndex }
        val indexed = sources.joinToString("\n") {
            val blind = blindByTurn[it.turnIndex]
            "[turn ${it.turnIndex}] expected: ${it.transcriptText.take(700)}\n" +
                "  blind listener heard: ${blind?.heardTranscript.orEmpty().take(700)}\n" +
                "  blind uncertainty: ${blind?.uncertainWords?.joinToString().orEmpty()}"
        }
        return """
            You are a conservative pronunciation and intelligibility coach for $setting.
            The learner's first language is $l1. Treat L1 patterns only as hypotheses, never as
            evidence by themselves. A separate first-pass listener transcribed each audio clip
            without seeing the expected words. Use that blind evidence before diagnosing cause.

            <transcript>
            $indexed
            </transcript>

            Listen to every labelled clip and report only acoustically clear issues
            that could impede comfortable understanding. Pay special attention to $focus, while
            allowing any harmless non-native accent. Do not infer an error from spelling or L1.

            Rules:
            - The target must be an exact word or short phrase present in that turn's transcript.
            - Set turn_index only to one of the supplied labels.
            - confidence is your confidence that the issue is audible, not confidence in the text.
            - Omit confidence below $MIN_CONFIDENCE and omit minor/accent-only differences.
            - The target must be missing, changed, or explicitly uncertain in the blind transcript,
              OR the blind comprehensibility score must be 3 or lower. Otherwise omit it.
            - heard_as is a short plain-English approximation of what made it confusing.
            - coaching_tip must be actionable: a target-specific mouth cue, stress cue, or rhythm cue.
            - pattern must be one of: ${PATTERNS.joinToString("|")}.
            - start_ms and end_ms bound the shortest useful replay window for the target in this
              clip. Include a little surrounding context; keep the window under 8 seconds.
            - Return at most 3 findings from this batch. Return [] when evidence is insufficient.${coachingLanguageNote(explanationLanguage)}

            Return ONLY a JSON array:
            [{
              "turn_index": 12,
              "target": "metoprolol",
              "heard_as": "met-pro-lol, with the middle syllable swallowed",
              "issue": "The unstressed middle syllable was not audible.",
              "coaching_tip": "Say meh-TOE-pro-lol; keep four syllables and stress TOE.",
              "severity": "moderate|major",
              "confidence": 0.0,
              "pattern": "word_stress",
              "start_ms": 1200,
              "end_ms": 2800
            }]
        """.trimIndent()
    }

    private fun normalizeWords(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun transcriptContainsTarget(transcript: String, target: String): Boolean {
        val haystack = normalizeWords(transcript)
        val needle = normalizeWords(target)
        return needle.isNotBlank() && " $needle " in " $haystack "
    }

    // internal, not private: the desktop port (ledger LOG-04) has no other way to pin this
    // threshold table, which sets the comprehensibility score for the whole Azure path.
    internal fun azureComprehensibility(confidence: Double, uncertainCount: Int): Int = when {
        confidence >= 0.88 && uncertainCount == 0 -> 5
        confidence >= 0.72 && uncertainCount <= 1 -> 4
        confidence >= 0.55 -> 3
        confidence > 0.0 -> 2
        else -> 1
    }

    private fun looselyMatches(left: String, right: String): Boolean {
        val a = normalizeWords(left)
        val b = normalizeWords(right)
        return a.isNotBlank() && b.isNotBlank() &&
            (a == b || a.contains(b) || b.contains(a))
    }

    internal fun correctionsFromAzureEvidence(
        source: PronunciationAudioSource,
        blind: BlindIntelligibilityObservation,
        evidence: AzurePronunciationEvidence
    ): List<PronunciationCorrection> {
        // A native-reference score is never enough to create a finding. Every returned item must
        // also be grounded in a word the blind listener missed, changed, or marked uncertain.
        if (blind.outcome == IntelligibilityOutcome.COMFORTABLE) return emptyList()
        val criticalTargets = blind.criticalDifferences
            .map { it.expected }
            .filterNot { it == "(none)" }
        val expectedTokens = normalizeWords(source.transcriptText).split(" ").filter { it.length >= 3 }
        val heardTokens = normalizeWords(blind.heardTranscript).split(" ").toSet()

        return evidence.words.mapNotNull { word ->
            val target = word.word.trim()
            if (!transcriptContainsTarget(source.transcriptText, target)) return@mapNotNull null
            val specialistFoundIssue =
                !word.errorType.equals("None", ignoreCase = true) ||
                    (word.accuracy != null && word.accuracy < 65.0)
            if (!specialistFoundIssue) return@mapNotNull null
            val groundedByBlind = blind.uncertainWords.any { looselyMatches(it, target) } ||
                criticalTargets.any { looselyMatches(it, target) } ||
                (
                    normalizeWords(target) in expectedTokens &&
                        normalizeWords(target) !in heardTokens &&
                        normalizeWords(target).length >= 4
                    )
            if (!groundedByBlind) return@mapNotNull null

            val weakestPhoneme = word.phonemes
                .filter { it.accuracy != null }
                .minByOrNull { it.accuracy ?: 100.0 }
                ?.phoneme
                ?.takeIf { it.isNotBlank() }
            val errorDescription = when (word.errorType.lowercase(Locale.ROOT)) {
                "omission" -> "The blind listener missed this word."
                "insertion" -> "An extra sound or word may have obscured the message."
                "mispronunciation" -> "The blind listener did not reliably identify this word."
                else -> "This word was not reliably caught by the blind listener."
            }
            val tip = if (weakestPhoneme != null) {
                "Keep the /$weakestPhoneme/ portion distinct, then say the whole word once at normal speed."
            } else {
                "Say the word once in a short phrase, keeping every syllable audible."
            }
            val replayWindow = if (
                word.startMs != null && word.endMs != null &&
                word.endMs > word.startMs && word.endMs - word.startMs <= 8_000
            ) {
                word.startMs to word.endMs
            } else {
                null
            }
            PronunciationCorrection(
                turnIndex = source.turnIndex,
                original = "Blind listener did not reliably catch: $target",
                corrected = target,
                explanation = "$errorDescription Tip: $tip",
                severity = if (
                    blind.outcome == IntelligibilityOutcome.CRITICAL_MISMATCH ||
                    word.errorType.equals("Omission", ignoreCase = true)
                ) "major" else "moderate",
                confidence = 0.80,
                pattern = "other",
                startMs = replayWindow?.first,
                endMs = replayWindow?.second
            )
        }.distinctBy { normalizeWords(it.corrected) }.take(3)
    }

    /** internal: prompt text is pinned by the cross-language golden harness (ledger LOG-04). */
    internal fun buildAzureTextCoachingPrompt(
        findings: List<PronunciationCorrection>,
        observations: List<BlindIntelligibilityObservation>,
        l1Lang: String,
        explanationLanguage: String = ""
    ): String {
        val blindByTurn = observations.associateBy { it.turnIndex }
        val evidence = findings.joinToString("\n") { finding ->
            val blind = blindByTurn[finding.turnIndex]
            JSONObject()
                .put("turn_index", finding.turnIndex)
                .put("target", finding.corrected)
                .put("blind_listener_heard", blind?.heardTranscript.orEmpty())
                .put("blind_outcome", blind?.outcome?.wireName.orEmpty())
                .put("specialist_evidence", finding.explanation)
                .put("start_ms", finding.startMs ?: 0)
                .put("end_ms", finding.endMs ?: 0)
                .toString()
        }
        return """
            You are an intelligibility coach. Audio has already been measured by blind speech
            recognition and a specialist pronunciation engine. You receive TEXT EVIDENCE ONLY.
            Do not decide pass/fail, add targets, or coach toward a native accent.

            Learner L1: ${l1Name(l1Lang)}
            Evidence, one JSON object per line:
            $evidence

            For each supplied target, give one concrete, brief action that can make that word
            easier for an ordinary listener to catch. Preserve turn_index, target, start_ms and
            end_ms exactly. Never mention a numeric pronunciation score. Use confidence 0.80.${coachingLanguageNote(explanationLanguage)}
            Return ONLY a JSON array with:
            turn_index, target, heard_as, issue, coaching_tip, severity (moderate|major),
            confidence, pattern, start_ms, end_ms.
        """.trimIndent()
    }

    suspend fun analyzePronunciationSegmentsWithAzureDetailed(
        azureKey: String,
        azureRegion: String,
        sources: List<PronunciationAudioSource>,
        l1Lang: String = "ko",
        domain: String = "clinical",
        geminiApiKey: String = "",
        geminiModel: String = DEFAULT_GEMINI_ANALYSIS_MODEL,
        explanationLanguage: String = "",
        budgetMs: Long? = null
    ): PronunciationAnalysisResult {
        if (azureKey.isBlank() || azureRegion.isBlank()) {
            return PronunciationAnalysisResult(emptyList(), emptyList())
        }
        val budget = PronunciationTimeBudget(budgetMs)
        val observations = mutableListOf<BlindIntelligibilityObservation>()
        val findings = mutableListOf<PronunciationCorrection>()

        for (source in sources.sortedBy { it.turnIndex }) {
            // Turns already measured stay measured; only the unstarted ones are given up.
            if (budget.spent()) break
            if (source.byteSize < MIN_AUDIO_BYTES || source.transcriptText.isBlank()) continue
            val bytes = withContext(Dispatchers.IO) {
                runCatching { source.loadAudio() }.getOrNull()
            }?.takeIf { it.size >= MIN_AUDIO_BYTES } ?: continue
            val blindResult = AzureSpeechService.transcribeBlind(
                subscriptionKey = azureKey,
                region = azureRegion,
                audioBytes = bytes,
                mimeType = source.mimeType
            )
            val comprehensibility = azureComprehensibility(
                blindResult.confidence,
                blindResult.uncertainWords.size
            )
            val assessment = IntelligibilityComparator.assess(
                expected = source.transcriptText,
                heard = blindResult.text,
                uncertainWords = blindResult.uncertainWords,
                comprehensibility = comprehensibility,
                confidence = blindResult.confidence,
                criticalItemsClear =
                    blindResult.confidence >= 0.72 && blindResult.uncertainWords.size <= 1
            )
            val observation = BlindIntelligibilityObservation(
                turnIndex = source.turnIndex,
                expectedTranscript = source.transcriptText.take(900),
                heardTranscript = blindResult.text.take(900),
                uncertainWords = blindResult.uncertainWords,
                comprehensibility = comprehensibility,
                confidence = blindResult.confidence,
                criticalItemsClear =
                    blindResult.confidence >= 0.72 && blindResult.uncertainWords.size <= 1,
                wordMatchRate = assessment.wordMatchRate,
                criticalDifferences = assessment.criticalDifferences,
                outcome = assessment.outcome
            )
            observations += observation
            if (
                assessment.outcome != IntelligibilityOutcome.COMFORTABLE &&
                assessment.outcome != IntelligibilityOutcome.COULD_NOT_ASSESS
            ) {
                val specialistEvidence = runCatching {
                    AzureSpeechService.assessPronunciation(
                        subscriptionKey = azureKey,
                        region = azureRegion,
                        audioBytes = bytes,
                        referenceText = source.transcriptText,
                        mimeType = source.mimeType
                    )
                }.getOrNull()
                if (specialistEvidence != null) {
                    findings += correctionsFromAzureEvidence(source, observation, specialistEvidence)
                }
            }
        }

        var diagnosticModel = if (findings.isEmpty()) "" else "azure-pronunciation-assessment"
        var finalFindings = findings
        // The coaching pass only rewrites tips onto findings Azure already produced, so skipping it
        // when the budget is gone costs polish, not evidence.
        if (findings.isNotEmpty() && geminiApiKey.isNotBlank() && !budget.spent()) {
            val refined = runCatching {
                val call = withGeminiTextFallbackTracked(geminiModel) { model ->
                    GeminiService.generateContent(
                        apiKey = geminiApiKey,
                        model = model,
                        prompt = buildAzureTextCoachingPrompt(findings, observations, l1Lang, explanationLanguage)
                    )
                }
                diagnosticModel += "+gemini-text:${call.modelUsed}"
                val sourceByTurn = sources.associateBy { it.turnIndex }
                val allowed = findings.associateBy {
                    "${it.turnIndex}:${normalizeWords(it.corrected)}"
                }
                parseBatchResponse(call.value, sources, observations).mapNotNull { coached ->
                    val original = allowed[
                        "${coached.turnIndex}:${normalizeWords(coached.corrected)}"
                    ] ?: return@mapNotNull null
                    coached.copy(
                        original = original.original,
                        startMs = original.startMs,
                        endMs = original.endMs
                    ).takeIf { sourceByTurn.containsKey(it.turnIndex) }
                }
            }.getOrNull().orEmpty()
            if (refined.isNotEmpty()) finalFindings = refined.toMutableList()
        }

        return PronunciationAnalysisResult(
            corrections = rankCorrections(finalFindings),
            blindObservations = observations.distinctBy { it.turnIndex },
            blindModelUsed = "azure-speech-stt",
            diagnosticModelUsed = diagnosticModel
        )
    }

    internal fun parseBatchResponse(
        raw: String,
        sources: List<PronunciationAudioSource>,
        blindObservations: List<BlindIntelligibilityObservation> = emptyList()
    ): List<PronunciationCorrection> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.split("```").getOrElse(1) { text }
            if (text.startsWith("json")) text = text.substring(4)
        }
        val sourceByTurn = sources.associateBy { it.turnIndex }
        val blindByTurn = blindObservations.associateBy { it.turnIndex }
        val items = try { JSONArray(text.trim()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return emptyList() }
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val turnIndex = item.optInt("turn_index", -1)
                val source = sourceByTurn[turnIndex] ?: continue
                val target = item.optString("target", item.optString("word", "")).trim()
                val heardAs = item.optString("heard_as", "").trim()
                val issue = item.optString("issue", "").trim()
                val tip = item.optString("coaching_tip", item.optString("correction", "")).trim()
                val severity = item.optString("severity", "").trim().lowercase(Locale.ROOT)
                val confidence = item.optDouble("confidence", 0.0)
                val pattern = item.optString("pattern", "other").trim().lowercase(Locale.ROOT)
                val startMs = item.optInt("start_ms", -1)
                val endMs = item.optInt("end_ms", -1)
                val normalizedTarget = normalizeWords(target)

                if (!transcriptContainsTarget(source.transcriptText, target)) continue
                if (severity !in setOf("moderate", "major") || confidence < MIN_CONFIDENCE) continue
                if (pattern !in PATTERNS) continue
                if (issue.isBlank() || tip.isBlank()) continue
                val replayWindow = if (
                    startMs >= 0 &&
                    endMs > startMs &&
                    endMs - startMs <= 8_000 &&
                    endMs <= 120_000
                ) {
                    startMs to endMs
                } else {
                    null
                }
                val blind = blindByTurn[turnIndex]
                if (blind != null) {
                    val targetHeardClearly = transcriptContainsTarget(blind.heardTranscript, target)
                    val targetMarkedUncertain = blind.uncertainWords.any {
                        normalizeWords(it).contains(normalizedTarget) ||
                            normalizedTarget.contains(normalizeWords(it))
                    }
                    if (
                        targetHeardClearly &&
                        !targetMarkedUncertain &&
                        blind.comprehensibility > 3 &&
                        blind.outcome == IntelligibilityOutcome.COMFORTABLE
                    ) {
                        continue
                    }
                }

                val original = if (
                    heardAs.isNotBlank() && normalizeWords(heardAs) != normalizedTarget
                ) {
                    "$target (sounded like: $heardAs)"
                } else {
                    "Unclear pronunciation of: $target"
                }
                val confidencePercent = (confidence.coerceIn(0.0, 1.0) * 100).toInt()
                add(
                    PronunciationCorrection(
                        turnIndex = turnIndex,
                        original = original,
                        // The practice target stays normal orthography so TTS and SRS ask the
                        // learner to say the word, not to read an explanatory phonetic sentence.
                        corrected = target,
                        explanation = "[Pronunciation - $severity - $confidencePercent% confidence] $issue Tip: $tip",
                        severity = severity,
                        confidence = confidence.coerceIn(0.0, 1.0),
                        pattern = pattern,
                        startMs = replayWindow?.first,
                        endMs = replayWindow?.second
                    )
                )
            }
        }
    }

    suspend fun analyzePronunciationSegments(
        apiKey: String,
        sources: List<PronunciationAudioSource>,
        l1Lang: String = "ko",
        domain: String = "clinical",
        model: String = DEFAULT_GEMINI_ANALYSIS_MODEL
    ): List<PronunciationCorrection> = analyzePronunciationSegmentsDetailed(
        apiKey = apiKey,
        sources = sources,
        l1Lang = l1Lang,
        domain = domain,
        model = model
    ).corrections

    suspend fun analyzePronunciationSegmentsDetailed(
        apiKey: String,
        sources: List<PronunciationAudioSource>,
        l1Lang: String = "ko",
        domain: String = "clinical",
        model: String = DEFAULT_GEMINI_ANALYSIS_MODEL,
        explanationLanguage: String = "",
        budgetMs: Long? = null
    ): PronunciationAnalysisResult {
        if (apiKey.isBlank()) return PronunciationAnalysisResult(emptyList(), emptyList())
        val budget = PronunciationTimeBudget(budgetMs)
        val findings = mutableListOf<PronunciationCorrection>()
        val observations = mutableListOf<BlindIntelligibilityObservation>()
        var blindModelUsed = ""
        var diagnosticModelUsed = ""
        for (batch in batchSources(sources)) {
            // Each batch costs two multimodal round-trips. Stop before starting one we cannot
            // finish, and keep every batch already folded into `findings`/`observations`.
            if (budget.spent()) break
            val loaded = withContext(Dispatchers.IO) {
                batch.mapNotNull { source ->
                    val bytes = runCatching { source.loadAudio() }.getOrNull()
                    bytes?.takeIf { it.size >= MIN_AUDIO_BYTES }?.let {
                        source to GeminiService.InlineAudioPart(
                            bytes = it,
                            mimeType = source.mimeType,
                            label = "Unscripted learner audio for [turn ${source.turnIndex}]"
                        )
                    }
                }
            }
            if (loaded.isEmpty()) continue
            val loadedSources = loaded.map { it.first }
            val parts = loaded.map { it.second }
            val blindCall = try {
                withGeminiAudioFallbackTracked(model) { m ->
                    GeminiService.generateContentWithAudioParts(
                        apiKey = apiKey,
                        model = m,
                        audioParts = parts,
                        prompt = buildBlindPrompt(loadedSources, domain),
                        responseSchema = blindResponseSchema(),
                        readTimeoutMs = budget.remainingMs()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                continue
            }
            blindModelUsed = blindCall.modelUsed
            val batchObservations = parseBlindResponse(blindCall.value, loadedSources)
            // A reference-aware judge must not run when the blind evidence is missing; otherwise
            // expected-text priming recreates the old false-confidence path.
            if (batchObservations.isEmpty()) continue
            observations += batchObservations
            // The blind listener already produced usable intelligibility evidence for this batch.
            // If the budget ran out on it, keep that and skip the diagnostic pass rather than
            // discarding the round-trip we just paid for.
            if (budget.spent()) break

            val diagnosticCall = try {
                withGeminiAudioFallbackTracked(blindCall.modelUsed) { m ->
                    GeminiService.generateContentWithAudioParts(
                        apiKey = apiKey,
                        model = m,
                        audioParts = parts,
                        prompt = buildBatchPrompt(
                            loadedSources,
                            l1Lang,
                            domain,
                            batchObservations,
                            explanationLanguage
                        ),
                        responseSchema = responseSchema(),
                        readTimeoutMs = budget.remainingMs()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                continue
            }
            diagnosticModelUsed = diagnosticCall.modelUsed
            findings += parseBatchResponse(
                diagnosticCall.value,
                loadedSources,
                batchObservations
            )
        }

        return PronunciationAnalysisResult(
            corrections = rankCorrections(findings),
            blindObservations = observations.distinctBy { it.turnIndex },
            blindModelUsed = blindModelUsed,
            diagnosticModelUsed = diagnosticModelUsed
        )
    }

    /** Backward-compatible single-buffer entry point used by focused speaking checks/tests. */
    suspend fun analyzePronunciation(
        apiKey: String,
        audioBytes: ByteArray,
        transcript: List<Map<String, Any?>>,
        l1Lang: String = "ko",
        sampleRate: Int = 16000,
        domain: String = "clinical",
        model: String = DEFAULT_GEMINI_ANALYSIS_MODEL
    ): List<PronunciationCorrection> {
        if (audioBytes.size < MIN_AUDIO_BYTES || apiKey.isBlank()) return emptyList()
        val userTurns = transcript.withIndex().filter { it.value["role"] == "user" }
        if (userTurns.isEmpty()) return emptyList()
        val bounded = if (audioBytes.size > MAX_AUDIO_BYTES) audioBytes.copyOf(MAX_AUDIO_BYTES) else audioBytes
        val combinedText = userTurns.joinToString(" ") { it.value["text"]?.toString().orEmpty() }
        val firstIndex = userTurns.first().index
        return analyzePronunciationSegments(
            apiKey = apiKey,
            sources = listOf(
                PronunciationAudioSource(
                    turnIndex = firstIndex,
                    transcriptText = combinedText,
                    byteSize = bounded.size,
                    mimeType = "audio/pcm;rate=$sampleRate",
                    loadAudio = { bounded }
                )
            ),
            l1Lang = l1Lang,
            domain = domain,
            model = model
        )
    }
}
