package com.example.medvoicetrainer.analysis
import com.example.medvoicetrainer.voice.AudioEffects

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Recursively converts a drill/attempt JSONObject into the nested Map/List<Any> shape
 * ListeningDrillEngine's functions expect (e.g. `drill["details"] as? List<Map<String, Any>>`).
 * `org.json.JSONObject.opt()` alone returns raw JSONObject/JSONArray instances, which don't cast
 * to Kotlin Map/List — this is the bridge, first needed when ListeningLabScreen.kt started
 * calling into this file's scoring/adaptive-selection logic instead of duplicating it inline.
 */
fun org.json.JSONObject.toDeepMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    for (key in keys()) {
        map[key] = deepJsonValue(opt(key))
    }
    return map
}

fun org.json.JSONArray.toDeepList(): List<Any> = (0 until length()).map { deepJsonValue(get(it)) }

private fun deepJsonValue(value: Any?): Any = when (value) {
    is org.json.JSONObject -> value.toDeepMap()
    is org.json.JSONArray -> value.toDeepList()
    null, org.json.JSONObject.NULL -> ""
    else -> value
}

/** Bridges Room's [com.example.medvoicetrainer.db.ListeningAttemptEntity] to the snake_case
 * Map<String, Any?> shape [ListeningDrillEngine]'s progress/adaptive-selection functions expect
 * (mirrors `SessionEntity.toAnalysisMap()`'s role for session-based engines). */
fun com.example.medvoicetrainer.db.ListeningAttemptEntity.toAnalysisMap(): Map<String, Any?> = mapOf(
    "drill_id" to drillId,
    "created_at" to createdAt,
    "category" to category,
    "difficulty" to difficulty,
    "skill_tags" to skillTags,
    "details_correct" to detailsCorrect,
    "details_total" to detailsTotal,
    "unaided_details_correct" to unaidedDetailsCorrect,
    "unaided_details_total" to unaidedDetailsTotal,
    "unaided_accuracy" to unaidedAccuracy,
    "assisted_details_correct" to assistedDetailsCorrect,
    "assisted_details_total" to assistedDetailsTotal,
    "assisted_accuracy" to assistedAccuracy,
    "first_pass_correct" to firstPassCorrect,
    "replay_count" to replayCount,
    "clean_replay_count" to cleanReplayCount,
    "reveal_count" to revealCount,
    "repair_count" to repairCount,
    "interval_days" to intervalDays,
    "next_due_at" to nextDueAt
)

object ListeningDrillEngine {

    val NOISE_PROFILES = AudioEffects.NOISE_PROFILES

    private val NEGATION_WORDS = setOf("not", "no", "never", "without", "neither", "wrong", "incorrect")
    private val UNCERTAINTY_WORDS = setOf("maybe", "perhaps", "possibly", "probably", "either", "unsure", "uncertain", "guess", "guessing")
    private val CONTRAST_BOUNDARIES = setOf("but", "rather", "instead", "actually", "correction")
    private const val CLAUSE_BOUNDARY = "<clause>"

    fun normalizeHeardText(text: String?): String {
        var t = (text ?: "").lowercase(Locale.US).replace("’", "'")
        t = t.replace("n't", " not")
        t = t.replace(Regex("[^a-z0-9]+"), " ")
        return t.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun tokenizeHeardText(text: String?): List<String> {
        var raw = (text ?: "").lowercase(Locale.US).replace("’", "'").replace("n't", " not")
        raw = raw.replace(Regex("(?<=\\d)[.,](?=\\d)"), " ")
        val chunks = raw.split(Regex("[.;,!?]+|\\n+"))
        val words = mutableListOf<String>()
        for (chunk in chunks) {
            val normalized = normalizeHeardText(chunk)
            if (normalized.isBlank()) continue
            if (words.isNotEmpty()) {
                words.add(CLAUSE_BOUNDARY)
            }
            words.addAll(normalized.split(" "))
        }
        return words
    }

    private fun phraseOccurrences(words: List<String>, phrase: String): List<Pair<Int, Int>> {
        val target = phrase.split(" ").filter { it.isNotBlank() }
        if (target.isEmpty() || target.size > words.size) return emptyList()
        val occurrences = mutableListOf<Pair<Int, Int>>()
        for (start in 0..words.size - target.size) {
            var match = true
            for (i in target.indices) {
                if (words[start + i] != target[i]) {
                    match = false
                    break
                }
            }
            if (match) {
                occurrences.add(Pair(start, start + target.size))
            }
        }
        return occurrences
    }

    private fun lastBoundary(words: List<String>, start: Int): Int {
        for (index in start - 1 downTo 0) {
            if (words[index] == CLAUSE_BOUNDARY || CONTRAST_BOUNDARIES.contains(words[index])) {
                return index
            }
        }
        return -1
    }

    private fun occurrenceRejectionReason(words: List<String>, start: Int, end: Int, target: List<String>, competingSurfaces: Set<String>? = null): String? {
        val boundary = lastBoundary(words, start)
        val prefixStart = max(boundary + 1, start - 7)
        val prefix = words.subList(max(0, prefixStart), max(0, start))
        
        var nextBoundary = words.size
        for (i in end until words.size) {
            if (words[i] == CLAUSE_BOUNDARY || CONTRAST_BOUNDARIES.contains(words[i])) {
                nextBoundary = i
                break
            }
        }
        val suffixEnd = min(nextBoundary, end + 4)
        val suffix = words.subList(min(words.size, end), min(words.size, suffixEnd))

        val targetStartsNegative = target.isNotEmpty() && NEGATION_WORDS.contains(target[0])
        val negativePositions = mutableListOf<Int>()
        for (i in prefix.indices) {
            if (NEGATION_WORDS.contains(prefix[i])) negativePositions.add(i)
        }

        val untilReleasesNegation = negativePositions.isNotEmpty() && prefix.contains("until") && prefix.indexOf("until") > negativePositions.last()

        if (!targetStartsNegative && negativePositions.isNotEmpty() && !untilReleasesNegation) {
            return "negated"
        }

        if (suffix.size >= 2) {
            val s2 = listOf(suffix[0], suffix[1])
            if (s2 == listOf("is", "wrong") || s2 == listOf("was", "wrong") ||
                s2 == listOf("is", "incorrect") || s2 == listOf("was", "incorrect") ||
                s2 == listOf("is", "not") || s2 == listOf("was", "not")) {
                return "negated"
            }
        }

        if (end < words.size && words[end] == CLAUSE_BOUNDARY) {
            var correctionEnd = words.size
            for (i in end + 1 until words.size) {
                if (words[i] == CLAUSE_BOUNDARY) {
                    correctionEnd = i
                    break
                }
            }
            val correction = words.subList(min(words.size, end + 1), min(words.size, correctionEnd))
            
            var replacementEnd = words.size
            for (i in correctionEnd + 1 until words.size) {
                if (words[i] == CLAUSE_BOUNDARY) {
                    replacementEnd = i
                    break
                }
            }
            val replacement = words.subList(min(words.size, correctionEnd + 1), min(words.size, replacementEnd))
            
            val exactMarker = correction == listOf("no") || correction == listOf("sorry") ||
                              correction == listOf("actually", "no") || correction == listOf("sorry", "no")
            
            val replacementStartsLikeDetail = replacement.take(2).any { token -> token.any { it.isDigit() } }
            val replacementIsCompetingRelation = competingSurfaces?.any { surface -> phraseOccurrences(replacement, surface).isNotEmpty() } ?: false
            
            if (exactMarker && (replacementStartsLikeDetail || replacementIsCompetingRelation)) {
                return "self_corrected"
            }
        }

        if (prefix.any { UNCERTAINTY_WORDS.contains(it) }) return "uncertain"
        if (prefix.contains("not") && prefix.contains("sure")) return "uncertain"
        if (suffix.take(2).any { UNCERTAINTY_WORDS.contains(it) }) return "uncertain"

        val nearbyStart = max(boundary + 1, start - 2)
        val nearbyEnd = min(nextBoundary, end + 3)
        val nearby = words.subList(max(0, nearbyStart), min(words.size, nearbyEnd))
        if (nearby.contains("or") || nearby.contains("either")) {
            return "alternative_list"
        }

        return null
    }

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "hundred", "thousand"
    )

    private fun valueShape(tokens: List<String>): String {
        val t = tokens.filter { it.isNotBlank() }
        if (t.isEmpty()) return "empty"
        if (t.any { token -> token.any { it.isLetter() } && token.any { it.isDigit() } }) return "code"
        if (t.size >= 2 && t[0].length == 1 && t[0][0].isLetter() && (t[1].all { it.isDigit() } || NUMBER_WORDS.contains(t[1]))) return "code"
        if (t.all { token -> token.all { it.isDigit() } || NUMBER_WORDS.contains(token) }) return "number"
        return "word"
    }

    private fun slashListsCompetingValue(drill: Map<String, Any>, detail: Map<String, Any>, answer: String, candidate: String): Boolean {
        if (!answer.contains("/")) return false
        val parts = answer.split("/")
        val targetTokens = normalizeHeardText(candidate).split(" ")
        val targetShape = valueShape(targetTokens)

        val acceptedByKey = mutableMapOf<String, Set<String>>()
        val detailsList = drill["details"] as? List<Map<String, Any>> ?: emptyList()
        for (item in detailsList) {
            val key = item["key"]?.toString() ?: ""
            val answersList = item["answers"] as? List<Any> ?: emptyList()
            acceptedByKey[key] = answersList.map { normalizeHeardText(it.toString()) }.filter { it.isNotBlank() }.toSet()
        }

        fun belongsToKnownDetail(tokens: List<String>): Boolean {
            for (acceptedValues in acceptedByKey.values) {
                for (accepted in acceptedValues) {
                    if (phraseOccurrences(tokens, accepted).isNotEmpty()) return true
                }
            }
            return false
        }

        for ((index, part) in parts.withIndex()) {
            val partTokens = tokenizeHeardText(part)
            if (phraseOccurrences(partTokens, targetTokens.joinToString(" ")).isEmpty()) continue
            
            val neighbours = mutableListOf<List<String>>()
            if (index > 0) {
                val left = tokenizeHeardText(parts[index - 1])
                neighbours.add(left.takeLast(2))
            }
            if (index + 1 < parts.size) {
                val right = tokenizeHeardText(parts[index + 1])
                val firstClause = if (right.contains(CLAUSE_BOUNDARY)) right.subList(0, right.indexOf(CLAUSE_BOUNDARY)) else right
                neighbours.add(firstClause.take(2))
            }

            for (tokens in neighbours) {
                if (tokens.isEmpty() || belongsToKnownDetail(tokens)) continue
                if (valueShape(tokens) == targetShape) return true
            }
        }
        return false
    }

    private fun spanDistance(first: Pair<Int, Int>, second: Pair<Int, Int>): Int {
        if (first.second <= second.first) return second.first - first.second
        if (second.second <= first.first) return first.first - second.second
        return 0
    }

    private fun relationMatches(drill: Map<String, Any>, detail: Map<String, Any>, words: List<String>, occurrence: Pair<Int, Int>): Boolean {
        val group = detail["relation_group"] as? String
        val ownCues = detail["relation_cues"] as? List<Any> ?: emptyList()
        if (group == null || ownCues.isEmpty()) return true

        val detailsList = drill["details"] as? List<Map<String, Any>> ?: emptyList()
        val grouped = detailsList.filter { it["relation_group"] == group }
        
        val distances = mutableListOf<Pair<Triple<Int, Int, Int>, String>>()
        
        for (item in grouped) {
            val cues = item["relation_cues"] as? List<Any> ?: emptyList()
            for (rawCue in cues) {
                val cue = normalizeHeardText(rawCue.toString())
                for (cueSpan in phraseOccurrences(words, cue)) {
                    val betweenStart = min(occurrence.second, cueSpan.second)
                    val betweenEnd = max(occurrence.first, cueSpan.first)
                    val bridge = words.subList(max(0, betweenStart), min(words.size, betweenEnd))
                    val followsValue = if (cueSpan.first >= occurrence.second) 1 else 0
                    val crossesClause = bridge.contains(CLAUSE_BOUNDARY)
                    val valuePrefix = words.subList(max(0, occurrence.first - 2), max(0, occurrence.first))

                    if (crossesClause && !(followsValue == 1 && valuePrefix.contains("for"))) continue

                    val rawDistance = spanDistance(occurrence, cueSpan)
                    val explicitPostLink = followsValue == 1 && bridge.any { setOf("for", "belongs", "goes", "means").contains(it) }
                    var adjustedDistance = rawDistance
                    if (explicitPostLink) adjustedDistance -= 1
                    else if (followsValue == 1) adjustedDistance += 1

                    distances.add(Pair(Triple(adjustedDistance, rawDistance, followsValue), item["key"].toString()))
                }
            }
        }

        if (distances.isEmpty()) return false
        val nearest = distances.minByOrNull { it.first.first }?.first?.first ?: return false
        val maxDistance = max(1, (detail["relation_max_distance"]?.toString()?.toIntOrNull() ?: 7))
        val nearestKeys = distances.filter { it.first.first == nearest }.map { it.second }.toSet()
        return nearest <= maxDistance && nearestKeys == setOf(detail["key"].toString())
    }

    private fun scoreOneDetail(drill: Map<String, Any>, detail: Map<String, Any>, answer: String, structured: Boolean): Pair<String?, String?> {
        val words = tokenizeHeardText(answer)
        var rejection: String? = null
        val relationGroup = detail["relation_group"] as? String
        
        val competingSurfaces = mutableSetOf<String>()
        val detailsList = drill["details"] as? List<Map<String, Any>> ?: emptyList()
        if (relationGroup != null) {
            for (other in detailsList) {
                if (other["relation_group"] == relationGroup && other["key"] != detail["key"]) {
                    val ans = other["answers"] as? List<Any> ?: emptyList()
                    for (a in ans) {
                        competingSurfaces.add(normalizeHeardText(a.toString()))
                    }
                }
            }
        }

        val answersList = detail["answers"] as? List<Any> ?: emptyList()
        val variants = answersList.map { it.toString() }.sortedByDescending { normalizeHeardText(it).split(" ").size }

        for (variant in variants) {
            val surfaces = mutableSetOf(normalizeHeardText(variant))
            if (structured) {
                val prefixes = mutableSetOf(
                    normalizeHeardText(detail["key"]?.toString() ?: ""),
                    normalizeHeardText(detail["label"]?.toString() ?: "")
                )
                val cues = detail["relation_cues"] as? List<Any> ?: emptyList()
                for (cue in cues) prefixes.add(normalizeHeardText(cue.toString()))
                
                val candidateWords = normalizeHeardText(variant).split(" ")
                for (prefix in prefixes) {
                    val prefixWords = prefix.split(" ").filter { it.isNotBlank() }
                    if (prefixWords.isNotEmpty() && candidateWords.take(prefixWords.size) == prefixWords) {
                        val remainder = candidateWords.drop(prefixWords.size).joinToString(" ")
                        if (remainder.isNotBlank()) {
                            surfaces.add(remainder)
                        }
                    }
                }
            }

            for (candidate in surfaces) {
                val target = candidate.split(" ").filter { it.isNotBlank() }
                for (occurrence in phraseOccurrences(words, candidate)) {
                    if (slashListsCompetingValue(drill, detail, answer, candidate)) {
                        rejection = rejection ?: "alternative_list"
                        continue
                    }
                    val reason = occurrenceRejectionReason(words, occurrence.first, occurrence.second, target, competingSurfaces)
                    if (reason != null) {
                        rejection = rejection ?: reason
                        continue
                    }
                    if (!structured && !relationMatches(drill, detail, words, occurrence)) {
                        rejection = rejection ?: "relation_mismatch"
                        continue
                    }
                    return Pair(variant, null)
                }
            }
        }
        return Pair(null, rejection ?: "not_found")
    }

    fun scoreDetailRecall(drill: Map<String, Any>, answer: Any): Map<String, Any> {
        val structured = answer is Map<*, *>
        val results = mutableListOf<Map<String, Any?>>()
        val detailsList = drill["details"] as? List<Map<String, Any>> ?: emptyList()
        
        for (detail in detailsList) {
            val key = detail["key"]?.toString() ?: ""
            var detailAnswer = if (structured) {
                (answer as Map<*, *>)[key]?.toString() ?: ""
            } else {
                answer.toString()
            }
            
            var scoreAsStructured = structured
            if (structured && detailAnswer.isBlank()) {
                val fallback = (answer as Map<*, *>)["__free_text__"]?.toString() ?: ""
                if (fallback.isNotBlank()) {
                    detailAnswer = fallback
                    scoreAsStructured = false
                }
            }
            
            val (matched, reason) = scoreOneDetail(drill, detail, detailAnswer, scoreAsStructured)
            results.add(mapOf(
                "key" to detail["key"],
                "label" to (detail["label"] ?: detail["key"]),
                "correct" to (matched != null),
                "matched" to matched,
                "reason" to if (matched != null) null else reason,
                "accepted_answers" to (detail["answers"] as? List<Any> ?: emptyList<Any>())
            ))
        }

        val correct = results.count { it["correct"] == true }
        val total = results.size
        
        return mapOf(
            "details_correct" to correct,
            "details_total" to total,
            "accuracy" to if (total > 0) correct.toDouble() / total else 0.0,
            "detail_results" to results
        )
    }

    private val REPAIR_PATTERNS = mapOf(
        "repeat" to listOf("say that again", "repeat", "what was that", "sorry"),
        "slow" to listOf("slow down", "more slowly", "too fast"),
        "chunk" to listOf("one at a time", "break that down", "chunk"),
        "confirm" to listOf("did you say", "was that", "let me confirm", "so that's")
    )

    fun detectRepairStrategies(text: String): List<String> {
        val normalized = normalizeHeardText(text)
        return REPAIR_PATTERNS.filter { (_, patterns) ->
            patterns.any { p -> normalized.contains(normalizeHeardText(p)) }
        }.map { it.key }
    }

    // Mirrors the exact "yyyy-MM-dd'T'HH:mm:ss" (no zone suffix, device-local time) format
    // Repository.recordListeningAttempt()/updateListeningAssistance() actually write for
    // createdAt/nextDueAt — a prior version of this parser required a literal trailing 'Z' that
    // those strings never have, so every parse silently failed and every drill read as "due".
    private fun parseDue(value: Any?): Date? {
        if (value == null) return null
        val str = value.toString()
        if (str.isEmpty()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(str)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    private fun unaidedEvidence(attempt: Map<String, Any?>): Triple<Int, Int, Double>? {
        if (attempt.containsKey("unaided_accuracy")) {
            val value = attempt["unaided_accuracy"] as? Double ?: return null
            val total = (attempt["unaided_details_total"] as? Number)?.toInt() ?: (attempt["details_total"] as? Number)?.toInt() ?: 0
            val correct = (attempt["unaided_details_correct"] as? Number)?.toInt() ?: 0
            return Triple(correct, total, value)
        }
        
        val assistedKeys = listOf("replay_count", "clean_replay_count", "reveal_count", "repair_count")
        val assisted = assistedKeys.any { ((attempt[it] as? Number)?.toInt() ?: 0) > 0 }
        if (assisted) return null
        
        val total = (attempt["details_total"] as? Number)?.toInt() ?: 0
        val correct = max(0, min(total, (attempt["details_correct"] as? Number)?.toInt() ?: 0))
        return Triple(correct, total, if (total > 0) correct.toDouble() / total else 0.0)
    }

    private fun assistedEvidence(attempt: Map<String, Any?>): Triple<Int, Int, Double>? {
        if (attempt.containsKey("assisted_accuracy")) {
            val value = attempt["assisted_accuracy"] as? Double ?: return null
            val total = (attempt["assisted_details_total"] as? Number)?.toInt() ?: (attempt["details_total"] as? Number)?.toInt() ?: 0
            val correct = (attempt["assisted_details_correct"] as? Number)?.toInt() ?: 0
            return Triple(correct, total, value)
        }
        
        if (unaidedEvidence(attempt) != null) return null
        
        val total = (attempt["details_total"] as? Number)?.toInt() ?: 0
        val correct = max(0, min(total, (attempt["details_correct"] as? Number)?.toInt() ?: 0))
        return Triple(correct, total, if (total > 0) correct.toDouble() / total else 0.0)
    }

    fun chooseAdaptiveDrill(
        drills: List<Map<String, Any>>,
        attempts: List<Map<String, Any?>> = emptyList(),
        maxDifficulty: Int? = null
    ): Map<String, Any>? {
        if (drills.isEmpty()) return null
        
        val now = Date()
        val byId = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        val tagScores = mutableMapOf<String, MutableList<Double>>()
        var evidenceAttempts = 0
        
        for (attempt in attempts) {
            val id = attempt["drill_id"]?.toString() ?: ""
            byId.getOrPut(id) { mutableListOf() }.add(attempt)
            
            val evidence = unaidedEvidence(attempt) ?: continue
            evidenceAttempts++
            
            val tagsRaw = attempt["skill_tags"]
            val tags = if (tagsRaw is String) {
                if (tagsRaw.isNotBlank()) {
                    try {
                        org.json.JSONArray(tagsRaw).let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                } else emptyList()
            } else if (tagsRaw is List<*>) {
                tagsRaw.map { it.toString() }
            } else emptyList()
            
            for (tag in tags) {
                tagScores.getOrPut(tag) { mutableListOf() }.add(evidence.third)
            }
        }
        
        val overall = tagScores.values.flatten()
        val meanAccuracy = if (overall.isNotEmpty()) overall.sum() / overall.size else 0.0
        
        var inferredCap = 1
        if (evidenceAttempts >= 3 && meanAccuracy >= 0.65) inferredCap = 2
        if (evidenceAttempts >= 8 && meanAccuracy >= 0.78) inferredCap = 3
        if (evidenceAttempts >= 15 && meanAccuracy >= 0.88) inferredCap = 4
        
        val difficultyCap = maxDifficulty ?: inferredCap
        val eligible = drills.filter { (it["difficulty"]?.toString()?.toIntOrNull() ?: 1) <= difficultyCap }.ifEmpty { drills }
        
        val ranked = mutableListOf<Pair<Double, Map<String, Any>>>()
        
        for (drill in eligible) {
            val id = drill["id"]?.toString() ?: ""
            val previous = byId[id] ?: emptyList()
            var due = false
            if (previous.isNotEmpty()) {
                val latest = previous.maxByOrNull { parseDue(it["created_at"])?.time ?: 0L }
                val nextDue = parseDue(latest?.get("next_due_at")) ?: now
                due = nextDue.time <= now.time
            }
            
            val practicePenalty = previous.size * 2.5
            var weakBonus = 0.0
            val rTags = drill["receptive_tags"] as? List<*> ?: emptyList<Any>()
            for (tag in rTags) {
                val scores = tagScores[tag.toString()] ?: emptyList()
                val mastery = if (scores.isNotEmpty()) scores.sum() / scores.size else 0.35
                weakBonus += (1.0 - mastery) * 2.0
            }
            
            val dueBonus = if (due && previous.isNotEmpty()) 8.0 else 0.0
            val noveltyBonus = if (previous.isEmpty()) 5.0 else 0.0
            val jitter = Random.nextDouble() * 0.5
            
            ranked.add(Pair(dueBonus + noveltyBonus + weakBonus - practicePenalty + jitter, drill))
        }
        
        val best = ranked.maxByOrNull { it.first }?.first ?: 0.0
        val pool = ranked.filter { it.first >= best - 0.25 }.map { it.second }
        return if (pool.isNotEmpty()) pool[Random.nextInt(pool.size)] else null
    }

    fun progressSummary(attempts: List<Map<String, Any?>>): Map<String, Any?> {
        val unaided = attempts.mapNotNull { unaidedEvidence(it) }
        val assisted = attempts.mapNotNull { assistedEvidence(it) }
        
        val totalDetails = unaided.sumOf { it.second }
        val correctDetails = unaided.sumOf { it.first }
        val assistedTotal = assisted.sumOf { it.second }
        val assistedCorrect = assisted.sumOf { it.first }
        
        val firstPass = attempts.count { 
            unaidedEvidence(it) != null && ((it["first_pass_correct"] as? Number)?.toInt() ?: 0) != 0 
        }
        val repairs = attempts.sumOf { (it["repair_count"] as? Number)?.toInt() ?: 0 }
        
        val tagValues = mutableMapOf<String, MutableList<Double>>()
        for (attempt in attempts) {
            val evidence = unaidedEvidence(attempt) ?: continue
            val tagsRaw = attempt["skill_tags"]
            val tags = if (tagsRaw is String) {
                if (tagsRaw.isNotBlank()) {
                    try {
                        org.json.JSONArray(tagsRaw).let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                } else emptyList()
            } else if (tagsRaw is List<*>) {
                tagsRaw.map { it.toString() }
            } else emptyList()
            
            for (tag in tags) {
                tagValues.getOrPut(tag) { mutableListOf() }.add(evidence.third)
            }
        }
        
        val mastery = tagValues.mapValues { it.value.sum() / it.value.size }
        val weakest = mastery.minByOrNull { it.value }?.key
        
        return mapOf(
            "attempts" to attempts.size,
            "unaided_attempts" to unaided.size,
            "assisted_attempts" to assisted.size,
            "details_correct" to correctDetails,
            "details_total" to totalDetails,
            "detail_accuracy" to if (totalDetails > 0) correctDetails.toDouble() / totalDetails else 0.0,
            "unaided_accuracy" to if (totalDetails > 0) correctDetails.toDouble() / totalDetails else 0.0,
            "assisted_details_correct" to assistedCorrect,
            "assisted_details_total" to assistedTotal,
            "assisted_accuracy" to if (assistedTotal > 0) assistedCorrect.toDouble() / assistedTotal else 0.0,
            "first_pass_successes" to firstPass,
            "repair_count" to repairs,
            "weakest_tag" to weakest,
            "tag_mastery" to mastery
        )
    }
}
