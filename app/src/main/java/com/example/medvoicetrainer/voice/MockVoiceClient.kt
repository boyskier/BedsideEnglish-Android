package com.example.medvoicetrainer.voice

import com.example.medvoicetrainer.analysis.DemoTour
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Ported from app/voice/mock_client.py's scripted dialogue — was a 30-line stub that always
 * replied with one canned line regardless of case/mode. This app only ever drives the mock
 * client through Python's "interactive" typed-demo path (the user always types/speaks their own
 * turn, already appended to the transcript by MainViewModel.addLearnerTurn before sendText() is
 * called) — so Python's separate non-interactive "auto-played movie" path (--dev CLI / unit
 * tests only, no Android equivalent harness) and its pyttsx3 TTS narration (Windows desktop
 * SAPI5 voices) are both N/A here, matching this file's own header comment framing.
 */
class MockVoiceClient(
    private val mode: String = "encounter",
    private val caseId: String = "",
    private val caseJson: String = "{}"
) : VoiceClient {

    private var listener: VoiceClientListener? = null
    private var lines: List<String> = emptyList()
    private var running = false
    private var respIdx = 0

    // Survival "Advanced Beta" only — see SceneTransition.kt. Lets the whole propose → accept →
    // transition → save loop be walked offline, with no API key and no tokens spent.
    private var sceneTransitionsEnabled = false
    private var learnerTurnCount = 0
    private var scriptedProposalIndex = 0
    private var nextProposalId = 0

    override fun setListener(listener: VoiceClientListener) {
        this.listener = listener
    }

    override fun enableSceneTransitions(enabled: Boolean) {
        sceneTransitionsEnabled = enabled
    }

    override suspend fun connect(systemPrompt: String, model: String, voice: String) {
        running = true
        respIdx = 0
        learnerTurnCount = 0
        scriptedProposalIndex = 0
        lines = pickScript(mode, caseId, caseJson, systemPrompt)

        delay(500)
        listener?.onStatus("Connected to Mock Voice ($model)")
        emitNextAiLine()
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray) {
        // Mock does not process audio chunks in real time
    }

    /** Mirrors mock_client.py's submit_user_text(): voice the next scripted reply after a beat. */
    override suspend fun sendText(text: String) {
        delay(550)
        // A stage direction is the app talking to the scene, not the learner taking a turn: answer
        // it in character and don't advance the learner-turn counter that drives proposals.
        if (text.trimStart().startsWith(SceneTransitionProtocol.STAGE_PREFIX)) {
            listener?.onTranscript("patient", sceneChangeReply(text), true)
            listener?.onTurnComplete()
            return
        }
        learnerTurnCount += 1
        emitNextAiLine()
        listener?.onTurnComplete()
        maybeProposeSceneTransition()
    }

    /**
     * Nothing to send back to — the "provider" is this object. Accepting is followed by the
     * manager injecting the stage direction through [sendText], which is where the mock reacts.
     */
    override suspend fun respondToSceneTransition(
        id: String,
        outcome: SceneTransitionOutcome,
        note: String,
    ) = Unit

    override suspend fun close() {
        running = false
        listener?.onStatus("Mock session closed")
    }

    /** Fire the next scripted proposal once the learner has taken enough turns for it. */
    private fun maybeProposeSceneTransition() {
        if (!running || !sceneTransitionsEnabled) return
        val next = MOCK_SCENE_TRANSITIONS.getOrNull(scriptedProposalIndex) ?: return
        if (learnerTurnCount < next.first) return
        scriptedProposalIndex += 1
        nextProposalId += 1
        listener?.onSceneTransitionProposed(next.second.copy(id = "mock_fc_$nextProposalId"))
    }

    /**
     * Answer a stage direction in character. Keyed off the wording
     * [SceneTransitionProtocol.stageDirectionFor] produces for each type, so all five land on a
     * distinct reply — a character switch that answered with the move line ("here we are") was the
     * main reason the offline walkthrough of Stage 2 read as if nothing had happened.
     */
    private fun sceneChangeReply(stageDirection: String): String = when {
        stageDirection.contains("has just returned") ->
            "Oh, you're back — so what did they say? Tell me everything."
        stageDirection.contains("Time has passed") ->
            "Hey again! Feels like ages. So where were we?"
        stageDirection.contains("You are both there now") ->
            "Okay, here we are. Much better — so, you were saying?"
        stageDirection.contains("Continue the conversation naturally from here") ->
            "Hey, you're back with me — how did that go?"
        else -> "Hi there, I'll take it from here. What can I do for you?"
    }

    /**
     * Mirrors mock_client.py's _emit_next_ai_line(): emits the next scripted line, or — once the
     * script is exhausted — a status nudge to end the session, rather than looping silently.
     * Every transcript role is "patient" here (not "interviewer" for interview mode) to match how
     * GeminiLiveClient/OpenAIRealtimeClient already emit a single non-doctor role for every
     * backend; this app's UI has no separate "interviewer" bubble style.
     */
    private fun emitNextAiLine() {
        if (!running) return
        if (respIdx < lines.size) {
            val line = lines[respIdx]
            respIdx++
            listener?.onTranscript("patient", line, true)
        } else {
            val msg = if (mode == "survival" || mode == "lounge") {
                "The conversation script is complete — click ■ End & Analyze for your feedback."
            } else {
                "The patient has shared everything — click ■ End & Analyze for your feedback."
            }
            listener?.onStatus(msg)
        }
    }

    companion object {
        /**
         * Scripted Survival "Advanced Beta" proposals as (learner turns required, proposal), so an
         * offline session reaches a move, a solo errand (Stage 3's relay task), and a new character
         * (Stage 2) within a couple of minutes of typing. Ids are replaced per firing.
         */
        private val MOCK_SCENE_TRANSITIONS: List<Pair<Int, SceneTransitionProposal>> = listOf(
            2 to SceneTransitionProposal(
                id = "mock",
                type = SceneTransitionType.MOVE,
                title = "Walk to the information desk?",
                description = "You both walk over to the information desk and keep talking there.",
                place = "the information desk",
            ),
            4 to SceneTransitionProposal(
                id = "mock",
                type = SceneTransitionType.SOLO_ERRAND,
                title = "Go ask at the desk and come back?",
                description = "You go and ask at the desk on your own, then come back and report.",
                place = "the information desk",
            ),
            6 to SceneTransitionProposal(
                id = "mock",
                type = SceneTransitionType.NEW_CHARACTER,
                title = "Talk to the front desk staff?",
                description = "The front desk staff member takes over and deals with you directly.",
                newCharacterRole = "front desk staff",
            ),
        )

        // ── Warm-up conversation (app/analysis/daily_mission.py's "2-minute warm-up chat", the
        // cases/foundations/warmup_chat.json persona "Alex") — friendly peer, not a patient. ──
        private val MOCK_WARMUP_PARTNER_LINES = listOf(
            "Hey! Really glad you're here. I remember how nerve-wracking those first US rotations felt — the English wasn't the problem, getting the words *out* under pressure was. So let's just chat. What brought you into medicine in the first place?",
            "That's a great reason. I felt the same pull. Tell me — do you have a patient encounter that you still think about? Could be any kind, doesn't have to be dramatic.",
            "That's really meaningful. And in terms of speaking English clinically — what feels hardest right now? Like, is it starting sentences, or keeping up with fast speakers, or something else?",
            "Totally makes sense. That 'freeze' feeling is real. One thing that helped me: just talking more, even imperfectly. Like right now — you're already doing it. Why the US specifically for residency?"
        )

        // ── Chest Pain demo case (Mr. Johnson, 58M) ──
        private val MOCK_PATIENT_LINES = listOf(
            "Hello, doctor. I've been having this chest pain since this morning and it's really worrying me.",
            "It's in the middle of my chest, quite severe, maybe a 7 out of 10. It doesn't really go anywhere specific.",
            "It started while I was just sitting watching TV. Nothing special was happening.",
            "My dad actually died of a heart attack when he was 62, so I'm honestly quite scared this might be the same thing.",
            "I thought it might be heartburn at first, so I took some antacids but they didn't help at all.",
            "I do smoke, yes. About a pack a day for the last 20 years. I know, I know...",
            "I have high blood pressure and diabetes. I take metformin and lisinopril.",
            "Honestly, I just want to know if it's serious and if there's something you can give me for the pain.",
            "Thank you doctor. I appreciate you explaining everything to me."
        )

        // ── Korean CPX track: a short Korean scripted patient so the track runs offline too ──
        private val MOCK_KMLE_PATIENT_LINES = listOf(
            "안녕하세요.",
            "소변볼 때 아파서 왔어요.",
            "이틀 전부터요.",
            "화끈거리고 따가워요. 끝날 때 더 아파요.",
            "네, 자주 마려워요.",
            "아니요, 열은 없었어요.",
            "혹시 콩팥까지 번진 건 아닌가 걱정돼요.",
            "네, 알겠습니다. 감사합니다."
        )

        // ── Interview mode (residency match) ──
        private val MOCK_INTERVIEW_LINES = listOf(
            "Thank you for coming in today. Before we start formally, can I ask — how are you feeling?",
            "Interesting background. Can you elaborate on what specifically drew you to internal medicine over other specialties?",
            "That's a compelling reason. Tell me about a challenging clinical case you managed — walk me through your thinking.",
            "Good. And how did you communicate that uncertainty to the patient?",
            "What would your peers say is your biggest area for development as a clinician?",
            "Fair enough. Why this program specifically? What do you know about us?",
            "Any questions for me about the program?"
        )

        // Non-medical fallback replies for the keyless typed demo's Survival/Lounge modes — the
        // concrete opener/followups come from the session's own case JSON where available.
        private val MOCK_SURVIVAL_FALLBACK_RAPID = listOf(
            "Quick question: what are you doing right after this?",
            "What is the last thing you bought that was actually worth it?",
            "Coffee, tea, or neither today?"
        )
        private const val MOCK_SURVIVAL_FALLBACK_OPENER =
            "Hey, sorry to bother you for a second—could you help me with something?"
        private val MOCK_SURVIVAL_FALLBACK_FOLLOWUPS = listOf(
            "Oh, really? What happened after that?",
            "Wait, could you walk me through what you mean?",
            "Got it. So what do you want to do next?"
        )

        // ── Shortness of Breath demo case (Mrs. Chen, 45F) ──
        private val MOCK_SOB_PATIENT_LINES = listOf(
            "Doctor, I've been struggling to breathe for the past three days. It just keeps getting worse and I'm really worried.",
            "My breathing is fine when I'm sitting still, but as soon as I climb stairs or walk fast, I get very breathless. At night it's been hard to sleep lying flat.",
            "It started with a mild cough — I thought it was just a cold. But then the breathlessness came on and I've been coughing up some yellowish stuff.",
            "There is some chest tightness on both sides. It's not a stabbing pain, more like pressure, and it gets a bit worse when I take a deep breath.",
            "I take amlodipine for blood pressure — been on it for two years. No allergies that I know of.",
            "I was admitted with pneumonia about five years ago. Other than that, nothing major.",
            "I don't smoke now — I quit about ten years ago. But I did work in a textile factory for nearly fifteen years.",
            "My husband told me to come in sooner, but I didn't want to bother anyone. I'm just scared it's something with my heart again.",
            "Thank you, doctor. I really appreciate you explaining everything. When do you think the results will be back?"
        )

        // ── Abdominal Pain demo case (Mr. Patel, 35M) ──
        private val MOCK_ABD_PATIENT_LINES = listOf(
            "Doctor, I've got this terrible pain in my right side — it started last night and it's been getting worse. My wife made me come in.",
            "It started around my belly button and then moved down here to the right. Right now I'd say it's an eight out of ten.",
            "It's pretty constant — but it gets sharper when I move or cough. I've been walking hunched over because it hurts a bit less that way.",
            "Yes, I've been feeling sick since last night and I vomited twice this morning. And I've had a mild fever — my wife checked and it was 38.2.",
            "I haven't opened my bowels since yesterday, which is unusual for me. And I haven't passed gas since the pain started either.",
            "I've never had anything like this before. My grandfather had his appendix out — is that what I might have, doctor?",
            "I haven't eaten since yesterday lunchtime. I just can't face food right now.",
            "I'm just worried it's going to burst or something. I've heard bad stories. How quickly can you help me?",
            "That's a relief to hear. Thank you doctor. I'll do whatever you say — just please make it better."
        )

        /** Mirrors app/voice/mock_client.py's DEMO_CASES patient_lines, keyed by DemoTour's ids. */
        private val DEMO_PATIENT_LINES: Map<String, List<String>> = mapOf(
            "chest_pain" to MOCK_PATIENT_LINES,
            "dyspnea" to MOCK_SOB_PATIENT_LINES,
            "abdominal_pain" to MOCK_ABD_PATIENT_LINES
        )

        internal fun promptOpener(systemPrompt: String): String {
            val match = Regex("Open with:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(systemPrompt)
            return match?.groupValues?.get(1)?.trim() ?: ""
        }

        internal fun promptRapidQuestions(systemPrompt: String): List<String> {
            return Regex("^\\s*-\\s*\"([^\"]+)\"", RegexOption.MULTILINE).findAll(systemPrompt)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }

        private fun jsonStringList(json: JSONObject, key: String): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
        }

        /** Ported from mock_client.py's _survival_script (class method). */
        internal fun survivalScript(caseJson: String, systemPrompt: String): List<String> {
            val case = try { JSONObject(caseJson) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            val rapid = case.optString("scenario_type", "") == "rapid_fire"
            var samples = jsonStringList(case, "question_samples")
            if (rapid && samples.isEmpty()) samples = promptRapidQuestions(systemPrompt)
            return if (rapid) {
                samples.take(5).ifEmpty { MOCK_SURVIVAL_FALLBACK_RAPID }
            } else {
                val opener = case.optString("opener", "").trim().ifEmpty { promptOpener(systemPrompt) }
                val result = mutableListOf(opener.ifEmpty { MOCK_SURVIVAL_FALLBACK_OPENER })
                val followups = jsonStringList(case, "followups")
                result.addAll(followups.take(3).map { topic ->
                    if (topic.endsWith("?") || topic.endsWith(".") || topic.endsWith("!")) topic else "What about $topic?"
                })
                result.addAll(MOCK_SURVIVAL_FALLBACK_FOLLOWUPS)
                result
            }
        }

        /** Ported from mock_client.py's _lounge_script (static method). */
        internal fun loungeScript(caseJson: String): List<String> {
            val case = try { JSONObject(caseJson) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            return when (case.optString("scenario_type", "casual")) {
                // Free Talk's partner speaks one short line per turn; the offline demo keeps to
                // the same brief so the mode feels the same without a key.
                "free_talk" -> listOf(
                    "Hey! So, what's on your mind today?",
                    "Oh, nice. Tell me more?",
                    "Really? Why's that?",
                    "Ha, I see. And then what?",
                    "How did that feel?",
                    "Interesting. Anything else?"
                )
                "debate" -> listOf(
                    "All right, I’ll take the other side. What is your strongest reason for your view?",
                    "But does that still hold if the situation affects people very differently?",
                    "What evidence would actually make you change your mind?"
                )
                "article" -> listOf(
                    "So, what stood out to you most in what you just read or watched?",
                    "Was there any claim you found convincing—or hard to believe?",
                    "Why do you think that part matters in real life?"
                )
                "conflict" -> listOf(
                    "Can we talk for a minute? I’m frustrated about how that situation was handled.",
                    "I needed a clearer update earlier. What happened from your side?",
                    "Okay, what can we agree to do differently next time?"
                )
                else -> listOf(
                    "Hey, perfect timing—I needed a quick break. How’s your day been so far?",
                    "No way. What was the most unexpected part?",
                    "So what are you doing once you’re done here today?"
                )
            }
        }

        /**
         * Mirrors mock_client.py's __init__ + connect()'s warm-up override: encounter mode
         * detects the "Alex" warm-up persona from the resolved system prompt (matches Python's
         * "warmup"/"warm-up"/"alex"/"warm," substring checks on the lower-cased prompt); survival
         * and lounge re-derive their script from the live case JSON + system prompt; interview
         * and any other encounter case fall back to a fixed scripted patient/interviewer line set,
         * defaulting to the "chest_pain" demo case exactly like Python's `DEMO_CASES[0]` fallback
         * when [caseId] doesn't resolve to a known scripted demo case.
         */
        internal fun pickScript(mode: String, caseId: String, caseJson: String, systemPrompt: String): List<String> {
            val spLower = systemPrompt.lowercase()
            val isWarmup = mode == "encounter" && (
                spLower.contains("warmup") || spLower.contains("warm-up") ||
                    spLower.contains("alex") || spLower.contains("warm,")
                )
            return when {
                isWarmup -> MOCK_WARMUP_PARTNER_LINES
                mode == "interview" -> MOCK_INTERVIEW_LINES
                mode == "survival" -> survivalScript(caseJson, systemPrompt)
                mode == "lounge" -> loungeScript(caseJson)
                mode == com.example.medvoicetrainer.analysis.KmleCpx.SESSION_MODE -> MOCK_KMLE_PATIENT_LINES
                else -> {
                    val demoCaseId = DemoTour.demoCaseIdForRealCaseId(caseId) ?: "chest_pain"
                    DEMO_PATIENT_LINES[demoCaseId] ?: MOCK_PATIENT_LINES
                }
            }
        }
    }
}
