package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

object PresentationBuilder {

    /** Session-owned notes exposed to the learner during the live presentation. */
    const val AI_SOAP_FIELD = "presentation_ai_soap"
    const val STUDENT_SOAP_FIELD = "presentation_student_soap"

    data class Launch(
        val id: String,
        val title: String,
        val caseJson: String,
        val sourceSessionId: Int?,
        val plannedContinuation: Boolean,
    )

    private const val MAX_TRANSCRIPT_CHARS = 6000
    private const val PRESENTATION_EVAL_TEMPLATE = "case_presentation"

    fun isPresentable(session: Map<String, Any?>): Boolean {
        if (session["mode"] != "encounter") return false
        val caseJsonStr = session["raw_case_json"]?.toString() ?: return false
        if (caseJsonStr.isBlank()) return false
        
        try {
            val caseObj = JSONObject(caseJsonStr)
            if (caseObj.length() == 0) return false
            if (caseObj.optString("persona_override").isNotBlank()) return false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return false
        }

        val transcriptJsonStr = session["raw_transcript"]?.toString() ?: return false
        return transcriptJsonStr.isNotBlank() && transcriptJsonStr != "[]"
    }

    private fun caseSummary(caseObj: JSONObject): String {
        val patientInfo = listOfNotNull(
            caseObj.optString("patient_name").takeIf { it.isNotBlank() },
            caseObj.optString("age").takeIf { it.isNotBlank() }?.let { "$it y" },
            caseObj.optString("gender").takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        val fields = listOf(
            "Patient" to patientInfo,
            "Chief complaint" to caseObj.optString("chief_complaint"),
            "HPI" to caseObj.optString("hpi_details"),
            "PMH" to caseObj.optString("pmh"),
            "Medications" to caseObj.optString("medications"),
            "Social history" to caseObj.optString("social_hx")
        )

        val parts = fields.filter { it.second.isNotBlank() }
            .map { "${it.first}: ${it.second}" }
            .toMutableList()

        val soapObj = caseObj.optJSONObject("reference_soap")
        if (soapObj != null && soapObj.length() > 0) {
            val soapParts = soapObj.keys().asSequence().map { k -> "$k: ${soapObj.optString(k)}" }.joinToString("; ")
            parts.add("Reference assessment/plan: $soapParts")
        }

        return parts.joinToString("\n")
    }

    private fun transcriptBlock(transcriptJson: String): String {
        try {
            val arr = JSONArray(transcriptJson)
            val lines = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val turn = arr.optJSONObject(i) ?: continue
                val role = turn.optString("role", "?").uppercase()
                val text = turn.optString("text", "")
                if (text.isNotBlank()) {
                    lines.add("$role: $text")
                }
            }
            var block = lines.joinToString("\n")
            if (block.length > MAX_TRANSCRIPT_CHARS) {
                block = "…(earlier turns omitted)\n" + block.takeLast(MAX_TRANSCRIPT_CHARS)
            }
            return block
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ""
        }
    }

    private fun buildPersona(caseObj: JSONObject, transcriptJson: String): String {
        return """
You are Dr. Park, a supportive but rigorous attending physician on ward rounds at a US teaching hospital. A medical student is about to give you an ORAL CASE PRESENTATION of a patient they interviewed.

CASE GROUND TRUTH (what the patient really has — the student may not have elicited all of it):
${caseSummary(caseObj)}

WHAT THE STUDENT ACTUALLY ELICITED (their original interview transcript):
${transcriptBlock(transcriptJson)}

How to run the round:
- Listen to the full presentation first. Only interject if the student stalls for a long time — then nudge with the standard structure (one-liner, HPI, relevant positives/negatives, PMH/meds/social, assessment, plan).
- Then ask 2-4 probing questions, prioritising (a) clinically important information the student elicited but left out of the presentation, and (b) gaps they never asked the patient about — for those, ask what they would still want to know and why.
- Pimp gently: one question at a time, give them time to think, and give a short teaching point after each answer.
- Stay in character as an attending. Do not grade numerically or lecture about grammar; the post-session review handles that.
- Close the round with one strength and one thing to sharpen next time. The session ends when the student says "I'd like to end the session."
        """.trimIndent()
    }

    fun buildPresentationCase(session: Map<String, Any?>): Map<String, Any>? {
        if (!isPresentable(session)) return null

        val caseJsonStr = session["raw_case_json"]?.toString() ?: "{}"
        val caseObj = try { JSONObject(caseJsonStr) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
        val transcriptJsonStr = session["raw_transcript"]?.toString() ?: "[]"

        val sessionId = session["id"]?.toString() ?: "unknown"
        val caseId = caseObj.optString("id", sessionId)
        val caseName = session["case_name"]?.toString() ?: caseObj.optString("patient_name", "case")
        val chiefComplaint = caseObj.optString("chief_complaint", "past encounter")

        return buildMap {
            putAll(mapOf(
            "id" to "present_$caseId",
            "system" to "presentation",
            "difficulty" to caseObj.optString("difficulty", "intermediate"),
            "eval_template" to PRESENTATION_EVAL_TEMPLATE,
            "patient_name" to "Present: $caseName",
            "chief_complaint" to "Oral case presentation — $chiefComplaint",
            "persona_override" to buildPersona(caseObj, transcriptJsonStr),
            "kickoff_text" to "(The student has joined you on ward rounds.) Greet them briefly and ask them to present the patient.",
            "source_session_id" to sessionId,
            "suggested_questions" to emptyList<String>(),
            "phrase_categories" to listOf(
                mapOf(
                    "name" to "Presentation skeleton",
                    "phrases" to listOf(
                        "This is a [age]-year-old [man/woman] presenting with…",
                        "The pain started…, is … in character, and radiates to…",
                        "Relevant negatives include…",
                        "My leading diagnosis is…, because…",
                        "For the plan, I would like to…"
                    )
                )
            )
            ))

            // These are snapshots of the notes produced from this exact encounter. They belong in
            // the presentation payload rather than being reloaded by id so every entry point
            // (History, Feedback, Home, Practice) behaves identically and a deleted source row
            // cannot strand an already-created launch. The reference/model-answer SOAP is
            // intentionally excluded: this aid is a memory support, not an answer key.
            session["soap_note"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                put(AI_SOAP_FIELD, it)
            }
            session["student_soap_note"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                put(STUDENT_SOAP_FIELD, it)
            }
        }
    }

    /** A UI-ready launch payload shared by Feedback, Home, Practice, and History entry points. */
    fun buildLaunch(session: Map<String, Any?>): Launch? {
        val case = buildPresentationCase(session) ?: return null
        val id = case["id"]?.toString().orEmpty().ifBlank { "attending_presentation" }
        val title = case["patient_name"]?.toString().orEmpty().ifBlank { "Present to the attending" }
        val sourceSessionId = (session["id"] as? Number)?.toInt()
            ?: session["id"]?.toString()?.toIntOrNull()
        val planned = runCatching {
            JSONObject(session["raw_case_json"]?.toString().orEmpty())
                .optBoolean("continue_to_attending", false)
        }.getOrDefault(false)
        return Launch(
            id = id,
            title = title,
            caseJson = JSONObject(case).toString(),
            sourceSessionId = sourceSessionId,
            plannedContinuation = planned,
        )
    }
}
