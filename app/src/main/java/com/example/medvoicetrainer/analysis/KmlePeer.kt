package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Korean CPX peer mode (친구와 역할극): two students practise the way Korean students actually
 * prepare — one plays the doctor, the other the standardized patient from a script — while one
 * phone records the room. Afterwards the recording is transcribed with speaker labels and graded
 * by the same checklist grader as an AI-patient station, so a pair gets an examiner's feedback in
 * seconds instead of relying on the partner's memory.
 *
 * No live voice model runs, so a station costs one transcription and one grading call. Pure logic
 * only (prompts, parsing, the partner's role card); recording and network calls live elsewhere.
 */
object KmlePeer {

    /** Marker in the composed case's `kmle_cpx` block for a session graded from a peer recording. */
    const val PEER_KEY = "peer"
    const val AUDIO_MIME = "audio/aac"

    fun markPeer(caseJson: String): String {
        val case = runCatching { JSONObject(caseJson) }.getOrNull() ?: return caseJson
        case.optJSONObject(KmleCpx.CASE_KEY)?.put(PEER_KEY, true) ?: return caseJson
        return case.toString()
    }

    fun isPeer(caseJson: String): Boolean =
        runCatching { JSONObject(caseJson).optJSONObject(KmleCpx.CASE_KEY)?.optBoolean(PEER_KEY, false) == true }
            .getOrDefault(false)

    // ── Transcription ──────────────────────────────────────────────────────────────────────

    fun transcriptionPrompt(session: KmleCpx.SessionCase): String {
        val other = if (session.usesGuardian) "보호자(환자 역할을 맡은 학생이 보호자를 연기)" else "환자(표준화 환자 역할을 맡은 학생)"
        return """
이 녹음은 한국 의과대학생 두 명이 CPX(진료수행시험)를 연습하는 역할극입니다. 한 명은 학생의사, 다른 한 명은 $other 역할입니다.
상황: ${session.doorNote}

할 일: 녹음 전체를 처음부터 끝까지 빠짐없이 한국어로 받아 적고, 말한 사람을 구분하세요.
- speaker는 "doctor"(학생의사: 인사하고, 질문하고, 진찰을 말로 하고, 설명하는 사람) 또는 "patient"(대답하고 증상을 말하는 사람) 중 하나입니다.
- 말한 순서대로, 말이 바뀔 때마다 새 항목을 만듭니다. 요약하거나 고치지 말고 들리는 대로 적습니다. 의학용어도 들린 그대로 적습니다.
- 알아들을 수 없는 부분은 "(잘 안 들림)"으로 적습니다. 역할극 밖의 잡담(시작 전 준비, 웃음, "다시 할게" 등)은 speaker를 "other"로 적습니다.
- 녹음이 비어 있거나 대화가 없으면 turns를 빈 배열로 둡니다.

JSON 객체 하나만 출력하세요: {"turns": [{"speaker": "doctor | patient | other", "text": "..."}]}
""".trim()
    }

    /** Structured-output schema for the transcription call (Gemini `responseSchema`). */
    fun transcriptionSchema(): JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put(
                "turns",
                JSONObject()
                    .put("type", "ARRAY")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "OBJECT")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("speaker", JSONObject().put("type", "STRING").put("enum", JSONArray().put("doctor").put("patient").put("other")))
                                    .put("text", JSONObject().put("type", "STRING")),
                            )
                            .put("required", JSONArray().put("speaker").put("text")),
                    ),
            ),
        )
        .put("required", JSONArray().put("turns"))

    /**
     * The transcript as (role, text) turns in the app's usual roles ("doctor" / "patient").
     * Off-script chatter is dropped; consecutive lines by the same speaker are merged.
     */
    fun parseTranscript(raw: String?): List<Pair<String, String>> {
        val root = KmleCpxScorecard.parseModelJson(raw) ?: return emptyList()
        val turns = root.optJSONArray("turns") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until turns.length()) {
            val o = turns.optJSONObject(i) ?: continue
            val role = when (o.optString("speaker").trim().lowercase()) {
                "doctor", "student", "학생의사" -> "doctor"
                "patient", "guardian", "환자", "보호자" -> "patient"
                else -> continue
            }
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.first == role) out[out.lastIndex] = role to (last.second + " " + text)
            else out += role to text
        }
        return out
    }

    // ── The partner's role card ────────────────────────────────────────────────────────────

    data class RoleCard(
        val who: String,
        val complaint: String,
        val speakerNote: String,
        val ideas: String,
        val concerns: String,
        val expectations: String,
        val facts: List<SpScript.Fact>,
        /** Korean maneuver name → how to react when the "doctor" performs it. */
        val examReactions: List<Pair<String, Boolean>>,
        val rules: List<String>,
    )

    fun roleCard(session: KmleCpx.SessionCase): RoleCard {
        val case = session.case
        val name = session.patientName.ifBlank { "환자" }
        val who = "${KmleCpx.patientLabel(session.age, session.gender)} $name"
        val speakerNote = when {
            KmleCpx.isChild(session.age) -> "당신은 아이의 엄마 또는 아빠입니다. 아이의 증상을 보호자가 본 대로 말하세요."
            session.usesGuardian -> "환자가 직접 말하기 어려운 상태입니다. 당신은 함께 온 가족입니다."
            else -> "당신이 환자 본인입니다."
        }
        val script = session.script
        // With a script, its answers already carry the patient's thoughts in lay words; the raw
        // `ideas` field of older cases often names the diagnosis ("thinks this is related to …"),
        // which would hand the answer to the student sitting opposite.
        val hasScript = script != null && script.history.isNotEmpty()
        return RoleCard(
            who = who,
            complaint = session.doorComplaint.ifBlank { session.presentation.doorComplaint }.removeSuffix("고").trim(),
            speakerNote = speakerNote,
            ideas = if (hasScript) "" else case.optString("ideas").trim(),
            concerns = if (hasScript) "" else case.optString("concerns").trim(),
            expectations = if (hasScript) "" else case.optString("expectations").trim(),
            facts = script?.history.orEmpty(),
            examReactions = script?.exam.orEmpty().map { session.maneuverKo(it.maneuver) to it.painful },
            rules = listOf(
                "학생의사가 물어본 것에만 짧게 대답하세요. 먼저 줄줄 말하지 마세요.",
                "\"어디가 불편해서 오셨어요?\"에는 주호소 한 가지만 말하세요.",
                "대본에 없는 것을 물으면 \"아니요\"나 \"잘 모르겠어요\"로 답하세요.",
                "생각·걱정·기대는 학생의사가 물을 때 말하고, 설명이 끝났는데도 걱정을 묻지 않으면 한 번 물어보세요.",
                "진찰할 때는 아래 반응표대로만 아파하세요.",
            ),
        )
    }
}
