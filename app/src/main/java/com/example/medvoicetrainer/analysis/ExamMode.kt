package com.example.medvoicetrainer.analysis

object ExamMode {

    val EXAM_SCENARIOS = listOf(
        mapOf(
            "id" to "diagnostic_english_baseline",
            "kind" to "diagnostic",
            "title" to "10-minute Clinical English Diagnostic",
            "eval_template" to "diagnostic_clinical_english",
            "station_minutes" to 10,
            "exam_mode" to true,
            "patient_name" to "Diagnostic Examiner",
            "kickoff_text" to "Start the diagnostic. Greet the learner briefly, then give the first task.",
            "persona_override" to "You are a clinical-English examiner running a 10-minute baseline diagnostic for an international medical student or IMG. Run four short tasks, one at a time: 1) ask the learner to explain a common diagnosis in patient-friendly English; 2) play a patient with a simple symptom and ask two follow-up questions; 3) ask for a 45-second SBAR handover from a short case you provide; 4) ask one residency-style question about learning from feedback. Keep your turns short. Do not grade during the live session. The post-session analysis will score it.",
            "learning_objective" to "Baseline grammar, fluency, patient-friendly wording, structure, and repair strategies."
        ),
        mapOf(
            "id" to "oet_speaking_hypertension",
            "kind" to "oet",
            "title" to "OET-style Speaking: New Hypertension",
            "eval_template" to "oet_speaking",
            "station_minutes" to 5,
            "exam_mode" to true,
            "patient_name" to "OET Role-play Patient",
            "kickoff_text" to "Begin as the patient. Say you are worried about your new diagnosis.",
            "persona_override" to "You are a 52-year-old patient in a primary care clinic. You were just told your blood pressure is high and you are worried about taking medication forever. The learner is the doctor. Ask practical questions about what hypertension means, side effects, lifestyle changes, follow-up, and warning signs. Reveal concerns only when asked. Stay natural and a little anxious. This is OET-style speaking practice, but do not mention scores during the role play.",
            "learning_objective" to "OET-style relationship building, information gathering, and information giving."
        ),
        mapOf(
            "id" to "osce_history_chest_pain",
            "kind" to "osce",
            "title" to "OSCE History: Chest Pain",
            "eval_template" to "history_taking",
            "station_minutes" to 8,
            "exam_mode" to true,
            "patient_name" to "Mr. Hayes",
            "age" to 58,
            "gender" to "male",
            "chief_complaint" to "central chest pain",
            "hpi_details" to "Central pressure-like chest pain for two hours, severity 7/10, started at rest, mild nausea, no relief with antacid.",
            "ideas" to "Maybe it is indigestion.",
            "concerns" to "Father died of a heart attack.",
            "expectations" to "Wants to know if it is serious and what happens next.",
            "pmh" to "Hypertension and type 2 diabetes.",
            "medications" to "Metformin and lisinopril.",
            "social_hx" to "Smokes one pack per day, occasional alcohol, taxi driver.",
            "learning_objective" to "Focused OSCE history with ICE, red flags, empathy, and closure."
        ),
        mapOf(
            "id" to "residency_interview_growth",
            "kind" to "residency",
            "title" to "Residency Interview: Growth From Feedback",
            "eval_template" to "residency_interview",
            "station_minutes" to 10,
            "exam_mode" to true,
            "patient_name" to "Program Director",
            "kickoff_text" to "Greet the applicant and ask the opening question.",
            "persona_override" to "You are a US internal medicine Program Director interviewing an IMG applicant. Opening question: Tell me about a time you received difficult feedback and how you changed your behavior. Probe for STAR structure, specificity, humility, and mature reflection. Ask follow-up questions if the answer is vague. Do not score live.",
            "learning_objective" to "Specific, mature, structured residency interview answers."
        ),
        mapOf(
            "id" to "ward_round_presentation_pneumonia",
            "kind" to "ward_round",
            "title" to "Ward Round: Present Pneumonia Case",
            "eval_template" to "case_presentation",
            "station_minutes" to 5,
            "exam_mode" to true,
            "patient_name" to "Attending",
            "kickoff_text" to "Ask the learner to present the patient from room 12.",
            "persona_override" to "You are an attending physician on morning rounds. Ask the learner to present: Room 12 is a 67-year-old woman admitted overnight with community-acquired pneumonia. Provide details only if the learner asks for the chart: fever 38.6 C, productive cough, right lower-zone crackles, WBC 15, CXR right lower-lobe infiltrate, started ceftriaxone and azithromycin, oxygen 2 L nasal cannula. After the presentation, ask one concise question about assessment and one about plan. Do not grade live.",
            "learning_objective" to "Concise oral case presentation with assessment and plan."
        )
    )

    val KIND_LABELS = mapOf(
        "diagnostic" to "10-min Diagnostic",
        "oet" to "OET Speaking",
        "osce" to "OSCE",
        "residency" to "Residency Interview",
        "ward_round" to "Ward Round"
    )

    fun listExamScenarios(kind: String? = null): List<Map<String, Any>> {
        if (kind == null) return EXAM_SCENARIOS
        return EXAM_SCENARIOS.filter { it["kind"] == kind }
    }

    fun buildExamPrompt(scenario: Map<String, Any>): String {
        var prompt = (scenario["persona_override"] as? String)?.trim() ?: ""
        if (prompt.isEmpty()) {
            prompt = PromptBuilder.buildPatientPrompt(scenario)
        }
        return prompt + """

EXAM PRACTICE RULES:
- Stay in role throughout the live session.
- Ask one prompt or question at a time, then wait.
- Do not reveal the rubric or give numeric scores during the session.
- Keep pressure realistic but educational.
- The learner ends the session by saying they would like to end."""
    }
}
