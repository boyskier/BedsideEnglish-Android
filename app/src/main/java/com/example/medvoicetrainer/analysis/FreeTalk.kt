package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import java.util.Locale

/**
 * Free Talk: an open chat about whatever the learner wants to talk about today.
 *
 * It rides on the Lounge plumbing (case `lounge_free_talk` in `data/cases/lounge/`, so mode
 * "lounge", the everyday score domain, History's Lounge filter and the mock script all apply
 * unchanged) and differs in one thing: the partner says as little as possible. A learner who says
 * one sentence and gets four back is being lectured, not practising, so the brevity rules live
 * here in code — appended to whatever persona the case JSON authors — rather than in editable
 * content where they could be dropped.
 *
 * Scoring uses `data/eval/free_talk.json` (the same four everyday metric keys as every other
 * everyday rubric, re-anchored on carrying a conversation) plus [analysisNote], which tells the
 * evaluator the partner was muted on purpose and hands it a deterministic talk-share measurement.
 */
object FreeTalk {
    const val CASE_ID = "lounge_free_talk"
    const val SCENARIO_TYPE = "free_talk"
    const val EVAL_TEMPLATE = "free_talk"

    /** Where the chosen topic is kept on the session's case snapshot. */
    const val TOPIC_FIELD = "free_talk_topic"

    /** A partner turn longer than this broke the brief; reported, never held against the learner. */
    const val LONG_PARTNER_TURN_WORDS = 16

    internal const val PARTNER_RULES_MARKER = "YOUR MOST IMPORTANT RULE"

    internal val PARTNER_RULES = """
        $PARTNER_RULES_MARKER — SAY AS LITTLE AS POSSIBLE:
        The user is practising speaking. Every second you talk is a second they don't. Your only job is to keep them talking.
        - Each reply is ONE short sentence, ideally under 12 words. Never two full sentences. Never three.
        - The usual shape is a tiny reaction plus one short, open question: "Oh nice — what happened next?", "Really? Why?", "How did that feel?"
        - Sometimes a reaction alone is enough ("Ha, no way.", "Mm, I see.") — then stop and let them continue.
        - Never give your own story, long opinion, explanation, list, advice, or summary.
        - If they ask what you think, answer in a few words and hand it straight back: "I'd pick the beach — you?"
        - Never repeat or paraphrase what they just said back to them.
        - Never correct their English, never teach, and never comment on their English. Just chat like a friend.
        - If they give a very short answer, ask one simple, concrete question — do not fill the silence with talk of your own.
        - If they ask you to repeat or explain a word, do it in one short sentence, then stop.
        - If they ask how to say something in English, give just the phrase, then let them use it.
        - If they switch to another language, answer in English with one short line nudging them to try it in English.
        - When they say goodbye, reply with a short goodbye only.
        TOO LONG: "That sounds amazing! I love hiking too. The mountains are so peaceful and the fresh air really clears your head. Which trail did you take?"
        RIGHT: "Nice! Which trail?"
    """.trimIndent()

    fun isFreeTalk(caseData: Map<String, Any?>?): Boolean {
        val c = caseData ?: return false
        return c["scenario_type"]?.toString()?.trim() == SCENARIO_TYPE ||
            c["id"]?.toString()?.trim() == CASE_ID
    }

    /**
     * The full system prompt: the case's authored persona with the topic substituted, then the
     * brevity rules last, where the live models weigh them most.
     */
    fun buildPrompt(scenario: Map<String, Any?>, topic: String): String {
        val template = (scenario["prompt_template"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "You are a relaxed, friendly conversation partner having an ordinary chat in English with the user.\n\n{context_text}"
        val cleanTopic = topic.trim()
        val topicLine = if (cleanTopic.isNotEmpty()) {
            "TODAY'S TOPIC (chosen by the user): $cleanTopic\nStay with this topic, but follow the user if they drift somewhere else."
        } else {
            "No topic was chosen. Ask the user what they'd like to talk about, or ask one short question about their day, and follow their lead."
        }
        return template.replace("{context_text}", topicLine).trimEnd() + "\n\n" + PARTNER_RULES
    }

    /** Words and turns on each side of a conversation. Deterministic; drives scoring context only. */
    data class TalkShare(
        val learnerWords: Int,
        val learnerTurns: Int,
        val partnerWords: Int,
        val partnerTurns: Int,
        val longestLearnerTurnWords: Int,
        val longPartnerTurns: Int,
    ) {
        /** The learner's fraction of all spoken words, 0.0–1.0. */
        val learnerShare: Double
            get() = if (learnerWords + partnerWords == 0) 0.0
            else learnerWords.toDouble() / (learnerWords + partnerWords)

        val avgLearnerWordsPerTurn: Double
            get() = if (learnerTurns == 0) 0.0 else learnerWords.toDouble() / learnerTurns

        val avgPartnerWordsPerTurn: Double
            get() = if (partnerTurns == 0) 0.0 else partnerWords.toDouble() / partnerTurns
    }

    /**
     * Mid-session correction for a partner that drifts long anyway.
     *
     * The brevity rules are only a prompt, and live voice models relax them as a chat warms up.
     * After every partner turn over [maxWords], [onPartnerTurn] returns a stage note that the voice
     * layer seeds into the provider's context as inert history (see VoiceClient.seedHistory): it
     * triggers no reply, never reaches the app transcript, and so is neither shown to the learner
     * nor graded. Capped per session so a model that ignores it cannot flood its own context.
     */
    class BrevityGuard(
        private val maxWords: Int = LONG_PARTNER_TURN_WORDS,
        private val maxNudges: Int = MAX_BREVITY_NUDGES,
    ) {
        private var nudges = 0

        val nudgesSent: Int
            @Synchronized get() = nudges

        @Synchronized
        fun onPartnerTurn(text: String): String? {
            val words = wordCount(text)
            if (words <= maxWords || nudges >= maxNudges) return null
            nudges++
            return brevityNote(words)
        }
    }

    const val MAX_BREVITY_NUDGES = 8

    internal fun brevityNote(words: Int): String =
        "[STAGE NOTE — not said by the user. Never reply to it, never mention it.] Your last reply " +
            "was $words words: far too long. Every reply must be ONE short sentence under 12 words " +
            "— a tiny reaction and at most one short question — then stop and let the user talk."

    /** A guard for a Free Talk session, or null for any other case. */
    fun brevityGuardFor(caseData: Map<String, Any?>?): BrevityGuard? =
        if (isFreeTalk(caseData)) BrevityGuard() else null

    private val WHITESPACE = Regex("\\s+")

    private fun wordCount(text: String): Int =
        text.split(WHITESPACE).count { token -> token.any { it.isLetterOrDigit() } }

    /**
     * Measures a transcript in the "user"/"model" role convention (see SessionMapper's
     * toPythonRoleTranscriptJson). App-authored narrator lines are nobody's speech and are skipped,
     * matching [FluencyMetrics].
     */
    fun computeTalkShare(transcriptJson: String): TalkShare? {
        val array = try {
            JSONArray(transcriptJson.ifBlank { return null })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        var learnerWords = 0
        var learnerTurns = 0
        var partnerWords = 0
        var partnerTurns = 0
        var longestLearner = 0
        var longPartner = 0
        for (i in 0 until array.length()) {
            val turn = array.optJSONObject(i) ?: continue
            val words = wordCount(turn.optString("text"))
            if (words == 0) continue
            when (turn.optString("role").trim().lowercase()) {
                "user", "learner", "doctor" -> {
                    learnerWords += words
                    learnerTurns++
                    longestLearner = maxOf(longestLearner, words)
                }
                com.example.medvoicetrainer.voice.SceneTransitionProtocol.NARRATOR_ROLE -> Unit
                else -> {
                    partnerWords += words
                    partnerTurns++
                    if (words > LONG_PARTNER_TURN_WORDS) longPartner++
                }
            }
        }
        if (learnerTurns == 0 && partnerTurns == 0) return null
        return TalkShare(learnerWords, learnerTurns, partnerWords, partnerTurns, longestLearner, longPartner)
    }

    /**
     * Evaluator context for a Free Talk session; blank for every other session so callers can add
     * it unconditionally.
     */
    fun analysisNote(caseData: Map<String, Any?>?, transcriptJson: String): String {
        if (!isFreeTalk(caseData)) return ""
        val topic = caseData?.get(TOPIC_FIELD)?.toString()?.trim().orEmpty()
        val parts = mutableListOf(
            "FREE TALK NOTE: This was an open conversation" +
                (if (topic.isNotEmpty()) " on a topic the learner chose (\"$topic\")" else "") +
                ". The partner was deliberately instructed to say as little as possible — one short " +
                "sentence per turn — so that the learner carries the conversation. Never penalize the " +
                "learner for the partner's short turns and never credit the partner's lines to the " +
                "learner. Judge how well the learner used the space they were given: developing " +
                "answers with reasons, examples and small stories; asking questions back; moving the " +
                "topic forward unprompted; and sustaining connected multi-sentence turns. Bare " +
                "one-to-three-word answers to open questions are weak evidence for 'interaction' and " +
                "'fluency' even when they are grammatical. Prefer corrections and anki_cards that " +
                "would help the learner say MORE (linkers, opinion frames, follow-up questions, ways " +
                "to hold the turn), and name the single habit that would most lengthen their turns. " +
                "This was NOT a transactional exchange: where the everyday note above says a concise " +
                "answer can complete the task, that does not apply here — talking at length was the task."
        )
        computeTalkShare(transcriptJson)?.let { share ->
            parts.add(
                "TALK SHARE (deterministic, counted from the transcript — measurements, not scores): " +
                    "learner ${share.learnerWords} words in ${share.learnerTurns} turn(s), " +
                    "avg ${fmt(share.avgLearnerWordsPerTurn)} words/turn, longest ${share.longestLearnerTurnWords}; " +
                    "partner ${share.partnerWords} words in ${share.partnerTurns} turn(s), " +
                    "avg ${fmt(share.avgPartnerWordsPerTurn)} words/turn. " +
                    "Learner share of all words: ${Math.round(share.learnerShare * 100)}%."
            )
            if (share.longPartnerTurns > 0) {
                parts.add(
                    "The partner exceeded its brief in ${share.longPartnerTurns} turn(s) " +
                        "(more than $LONG_PARTNER_TURN_WORDS words). That cost the learner speaking " +
                        "time; do not lower the learner's scores for it."
                )
            }
        }
        return parts.joinToString("\n")
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
}
