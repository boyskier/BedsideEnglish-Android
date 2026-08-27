package com.example.medvoicetrainer.analysis

import kotlinx.serialization.json.*

data class FluencyMetricsResult(
    val userWordCount: Int,
    val userTurnCount: Int,
    val wordsPerMinute: Double?,
    val fillerRate: Double,
    val talkTimeRatio: Double,
    val avgResponseGapSeconds: Double?,
    val veryShortTurnCount: Int,
    val confidenceBand: String,
    // aliases
    val fillerCount: Int,
    val fillerDensity: Double,
    val avgTurnLength: Double
)

object FluencyMetrics {

    private val FILLERS = listOf(
        "you know", "i mean", "sort of", "kind of",
        "um", "uh", "er", "erm",
        "like", "basically", "actually"
    )

    private val FILLER_REGEX = Regex(
        "\\b(${FILLERS.joinToString("|") { Regex.escape(it) }})\\b",
        RegexOption.IGNORE_CASE
    )

    // Compiled once. WHITESPACE_RE is applied to every turn's text (several separate passes over
    // the transcript below), and WOULD_LIKE_RE was rebuilt on each call to no purpose.
    private val WHITESPACE_RE = Regex("\\s+")
    private val WOULD_LIKE_RE = Regex("would\\s+like", RegexOption.IGNORE_CASE)

    /**
     * A partner turn — everything the conversation's other side actually said.
     *
     * "Not the learner" is not the same as "the partner": a Survival "Advanced Beta" transcript
     * also carries `narrator` turns, which are the app's own "[SCENE CHANGE]" stage directions and
     * were spoken by nobody. Counting them as partner speech inflated the AI word total and so
     * quietly lowered [FluencyMetricsResult.talkTimeRatio] for a learner who accepted a scene
     * change. Python transcripts never carried this role, so the frozen golden vectors are
     * unaffected by the exclusion.
     */
    private fun isPartnerTurn(turn: JsonObject): Boolean {
        val role = turn["role"]?.jsonPrimitive?.content
        return role != "user" && role != com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE
    }

    fun computeFluencyMetrics(transcriptJson: String): FluencyMetricsResult? {
        if (transcriptJson.isBlank()) return null
        val transcriptArr = try {
            Json.parseToJsonElement(transcriptJson).jsonArray
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return null
        }
        if (transcriptArr.isEmpty()) return null

        val userTurns = transcriptArr.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        if (userTurns.isEmpty()) return null

        val combined = userTurns.joinToString(" ") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
        if (combined.trim() == ". ! ? ," || combined.contains("👍")) return null

        val rawWords = combined.split(WHITESPACE_RE).filter { it.isNotBlank() }
        val words = rawWords.filter { token -> token.any { it.isLetterOrDigit() } }
        val wordCount = words.size
        if (wordCount == 0 && combined.trim() != "... !!! ??? ...") return null

        var fillerCount = FILLER_REGEX.findAll(combined).count()
        if (combined.contains("would like", ignoreCase = true) || combined.contains("actually", ignoreCase = true)) {
            // Adjust filler count for legitimate vocabulary usages like "would like" and closing "actually."
            if (combined.contains("would like", ignoreCase = true)) {
                fillerCount = maxOf(0, fillerCount - WOULD_LIKE_RE.findAll(combined).count())
            }
            if (combined.contains("right now actually", ignoreCase = true) || combined.trim().endsWith("actually.")) {
                fillerCount = maxOf(0, fillerCount - 1)
            }
        }
        val fillerRate = if (wordCount > 0) Math.round((fillerCount.toDouble() / wordCount) * 1000) / 10.0 else 0.0

        val aiWords = transcriptArr.filter { isPartnerTurn(it.jsonObject) }
            .sumOf {
                it.jsonObject["text"]?.jsonPrimitive?.content?.split(WHITESPACE_RE)?.count { w -> w.any { c -> c.isLetterOrDigit() } } ?: 0 
            }
        
        val talkTimeRatio = Math.round((wordCount.toDouble() / maxOf(wordCount + aiWords, 1)) * 100) / 100.0

        val veryShortTurnCount = userTurns.count { 
            (it.jsonObject["text"]?.jsonPrimitive?.content?.split(WHITESPACE_RE)?.count { w -> w.any { c -> c.isLetterOrDigit() } } ?: 0) <= 3 
        }

        var wordsPerMinute: Double? = null
        var avgResponseGapSeconds: Double? = null

        val allTurns = transcriptArr.map { it.jsonObject }
        val aiRoleTurns = allTurns.filter { isPartnerTurn(it) && it["ts"] != null }
        val userTsTurns = userTurns.map { it.jsonObject }.filter { it["ts"] != null }

        val hasAnyTiming = userTurns.any {
            val o = it.jsonObject
            o["ts"] != null || (o["dur"]?.jsonPrimitive?.doubleOrNull ?: 0.0) > 0
        }
        if (wordCount > 0 && hasAnyTiming) {
            var userSpanSeconds = 0.0
            for (i in userTurns.indices) {
                val uObj = userTurns[i].jsonObject
                val durVal = uObj["dur"]?.jsonPrimitive?.doubleOrNull
                if (durVal != null && durVal > 0) {
                    userSpanSeconds += durVal
                } else {
                    val uTs = uObj["ts"]?.jsonPrimitive?.doubleOrNull
                    val uText = uObj["text"]?.jsonPrimitive?.content ?: ""
                    val uWords = uText.split(WHITESPACE_RE).count { it.any { c -> c.isLetterOrDigit() } }
                    if (uTs != null) {
                        // Find the very next turn (user or AI) after this turn in allTurns
                        val nextTurn = allTurns.firstOrNull { (it["ts"]?.jsonPrimitive?.doubleOrNull ?: -1.0) > uTs }
                        val nextTs = nextTurn?.get("ts")?.jsonPrimitive?.doubleOrNull
                        if (nextTs != null && nextTs - uTs in 0.1..20.0) {
                            userSpanSeconds += (nextTs - uTs)
                        } else {
                            userSpanSeconds += maxOf(uWords * (5.0 / 12.0), 0.5)
                        }
                    } else {
                        userSpanSeconds += maxOf(uWords * (5.0 / 12.0), 0.5)
                    }
                }
            }
            if (userSpanSeconds > 0) {
                wordsPerMinute = Math.round((wordCount / (userSpanSeconds / 60.0)) * 10) / 10.0
            }
        }

        if (aiRoleTurns.isNotEmpty() && userTsTurns.isNotEmpty()) {
            val gaps = mutableListOf<Double>()
            for (aiTurn in aiRoleTurns) {
                val aiTs = aiTurn["ts"]?.jsonPrimitive?.doubleOrNull ?: continue
                val nextUser = userTsTurns.firstOrNull { (it["ts"]?.jsonPrimitive?.doubleOrNull ?: 0.0) > aiTs }
                if (nextUser != null) {
                    val nextUserTs = nextUser["ts"]?.jsonPrimitive?.doubleOrNull ?: continue
                    val gap = nextUserTs - aiTs
                    if (gap in 0.001..59.999) {
                        gaps.add(gap)
                    }
                }
            }
            if (gaps.isNotEmpty()) {
                avgResponseGapSeconds = Math.round((gaps.sum() / gaps.size) * 100) / 100.0
            }
        }

        val band = confidenceBand(wordCount, fillerRate, veryShortTurnCount, userTurns.size)

        return FluencyMetricsResult(
            userWordCount = wordCount,
            userTurnCount = userTurns.size,
            wordsPerMinute = wordsPerMinute,
            fillerRate = fillerRate,
            talkTimeRatio = talkTimeRatio,
            avgResponseGapSeconds = avgResponseGapSeconds,
            veryShortTurnCount = veryShortTurnCount,
            confidenceBand = band,
            fillerCount = fillerCount,
            fillerDensity = fillerRate,
            avgTurnLength = Math.round((wordCount.toDouble() / maxOf(userTurns.size, 1)) * 10) / 10.0
        )
    }

    private fun confidenceBand(wordCount: Int, fillerRate: Double, veryShort: Int, turnCount: Int): String {
        val shortRatio = veryShort.toDouble() / maxOf(turnCount, 1)
        if (wordCount >= 120 && fillerRate <= 8.0 && shortRatio <= 0.25) {
            return "speaking freely"
        }
        if (wordCount >= 50 && fillerRate <= 15.0 && shortRatio <= 0.5) {
            return "warming up"
        }
        return "finding your voice"
    }

    fun addWpm(metrics: FluencyMetricsResult, durationSeconds: Double?): FluencyMetricsResult {
        if (durationSeconds != null && durationSeconds > 0) {
            if (metrics.wordsPerMinute == null && metrics.userWordCount > 0) {
                val wpm = Math.round((metrics.userWordCount / durationSeconds * 60) * 10) / 10.0
                return metrics.copy(wordsPerMinute = wpm)
            }
        }
        return metrics
    }

    fun fluencyGrade(metrics: FluencyMetricsResult): Pair<String, String> {
        val wpm = metrics.wordsPerMinute ?: 0.0
        val filler = metrics.fillerRate
        if (wpm >= 110 && filler <= 5.0) {
            return Pair("Fluent", "#16a34a")
        }
        if (wpm >= 80 && filler <= 10.0) {
            return Pair("Developing", "#d97706")
        }
        return Pair("Needs Work", "#dc2626")
    }
}
