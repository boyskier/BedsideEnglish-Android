package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.SessionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class SessionFeeling(val wireName: String) {
    STUCK("stuck"),
    EFFORTFUL("effortful"),
    COMFORTABLE("comfortable");

    companion object {
        fun fromWireName(value: String?): SessionFeeling? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

enum class ReflectionPromptReason(val wireName: String) {
    RETRY("retry"),
    NEW_MODE("new_mode"),
    PERFORMANCE_SHIFT("performance_shift");
}

data class SessionReflectionRecord(
    val sessionId: Int,
    val feeling: SessionFeeling,
    val date: String,
    val promptReason: String
)

/**
 * Makes the lightweight post-session reflection contextual instead of periodic. A meaningful
 * learning event chooses *whether* the UI opens itself; the time/session guards only prevent
 * otherwise-useful prompts from becoming survey fatigue.
 */
object SessionReflection {
    const val HISTORY_KEY = "session_reflection_history"
    const val LAST_AUTO_DATE_KEY = "session_reflection_last_auto_date"
    const val LAST_AUTO_SESSION_KEY = "session_reflection_last_auto_session"
    const val AUTO_COOLDOWN_DAYS = 7L
    const val MIN_SESSIONS_BETWEEN_AUTO_PROMPTS = 2
    const val PERFORMANCE_SHIFT_POINTS = 15.0

    fun automaticPromptReason(
        sessionsNewestFirst: List<SessionEntity>,
        currentSessionId: Int,
        lastAutoDateIso: String,
        lastAutoSessionId: Int,
        today: LocalDate = LocalDate.now()
    ): ReflectionPromptReason? {
        val analyzed = sessionsNewestFirst.filter(::isMeaningfulAnalyzedSession)
        val current = analyzed.firstOrNull { it.id == currentSessionId } ?: return null
        val previous = analyzed.filter { it.id != current.id }

        // Avoid adding another first-run demand, and never open more than once in seven days.
        if (lastAutoDateIso.isBlank()) {
            if (analyzed.size < 3) return null
        } else {
            val lastDate = runCatching { LocalDate.parse(lastAutoDateIso) }.getOrNull()
            if (lastDate != null && ChronoUnit.DAYS.between(lastDate, today) < AUTO_COOLDOWN_DAYS) {
                return null
            }
            if (lastAutoSessionId > 0 && analyzed.count { it.id > lastAutoSessionId } < MIN_SESSIONS_BETWEEN_AUTO_PROMPTS) {
                return null
            }
        }

        val caseId = current.caseId.orEmpty()
        if (caseId.isNotBlank() && previous.any { it.caseId == caseId }) {
            return ReflectionPromptReason.RETRY
        }

        // A mode earns a proactive check only after the learner already knows the basic feedback
        // flow; the third-session guard above keeps onboarding free of this card.
        if (previous.none { sameMode(it, current) }) {
            return ReflectionPromptReason.NEW_MODE
        }

        val comparison = previous.filter { it.analysisDomain == current.analysisDomain }.take(3)
        if (comparison.size >= 2) {
            val baseline = comparison.map(::overallScore).average()
            if (abs(overallScore(current) - baseline) >= PERFORMANCE_SHIFT_POINTS) {
                return ReflectionPromptReason.PERFORMANCE_SHIFT
            }
        }
        return null
    }

    fun parseHistory(json: String): List<SessionReflectionRecord> = try {
        val array = JSONArray(json.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val feeling = SessionFeeling.fromWireName(item.optString("feeling")) ?: continue
                add(
                    SessionReflectionRecord(
                        sessionId = item.optInt("session_id", 0),
                        feeling = feeling,
                        date = item.optString("date"),
                        promptReason = item.optString("prompt_reason", "manual")
                    )
                )
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyList()
    }

    fun record(
        historyJson: String,
        sessionId: Int,
        feeling: SessionFeeling,
        dateIso: String,
        promptReason: String
    ): String {
        val next = (parseHistory(historyJson).filterNot { it.sessionId == sessionId } +
            SessionReflectionRecord(sessionId, feeling, dateIso, promptReason)).takeLast(100)
        return JSONArray().apply {
            next.forEach { record ->
                put(
                    JSONObject()
                        .put("session_id", record.sessionId)
                        .put("feeling", record.feeling.wireName)
                        .put("date", record.date)
                        .put("prompt_reason", record.promptReason)
                )
            }
        }.toString()
    }

    private fun isMeaningfulAnalyzedSession(session: SessionEntity): Boolean =
        session.deletedAt == null &&
            !session.rawClaudeResponse.isNullOrBlank() &&
            session.learnerTurnCount > 0 &&
            session.voiceBackend !in setOf("demo", "imported")

    private fun sameMode(left: SessionEntity, right: SessionEntity): Boolean =
        left.mode.equals(right.mode, ignoreCase = true) && left.analysisDomain == right.analysisDomain

    private fun overallScore(session: SessionEntity): Double {
        val scores = if (session.analysisDomain == "everyday") {
            listOf(
                session.grammarScore,
                session.medicalAccuracyScore,
                session.clinicalReasoningScore,
                session.fluencyScore
            )
        } else {
            listOf(
                session.grammarScore,
                session.medicalAccuracyScore,
                session.clinicalReasoningScore,
                session.professionalismScore,
                session.fluencyScore
            )
        }
        return scores.average()
    }
}
