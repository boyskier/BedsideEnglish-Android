package com.example.medvoicetrainer.analysis

import org.json.JSONArray

/**
 * Shared post-session analysis prompt for GeminiService/ClaudeService/OpenAIService.evaluateSession.
 * Ported from app/analysis/analysis_providers.py's domain-aware system prompt
 * (analysis_system_prompt) plus a scoped version of app/analysis/prompt_builder.py's
 * build_analysis_prompt everyday/clinical branch: everyday (survival/lounge) sessions must never
 * be scored against the clinical rubric (medical accuracy, clinical reasoning, SOAP note) — they
 * get their own JSON schema (naturalness/interaction/comprehension_repair/fluency), matching
 * ScoreDomains.SURVIVAL_METRIC_KEYS. This intentionally does not replicate prompt_builder.py's
 * full eval_data/rubric-driven prompt (checklist sections, self-scores, commitments, accent/pace
 * notes, etc.) — the Kotlin evaluateSession call site only has transcript+caseJson+nativeLanguage,
 * not the Python eval_data rubric object, so this is a fixed two-schema branch, not a rubric
 * template engine. See PORTING_STATUS.md for the exact scope note.
 */
object EvalPromptBuilder {

    const val DOMAIN_CLINICAL = "clinical"
    const val DOMAIN_EVERYDAY = "everyday"
    const val PROMPT_VERSION = "gec-evidence-v5-multilingual-l1"

    private val CLINICAL_SYSTEM_HEADER = """
        You are an expert medical educator, communication evaluator, and senior attending physician.
        Evaluate the following transcript of a learner taking a medical history from a simulated patient.
        The target case is specified in the CASE DETAILS.
    """.trimIndent()

    private val EVERYDAY_SYSTEM_HEADER = """
        You are an expert second-language conversation coach assessing an adult learner's everyday
        spoken English. This was ordinary real-life conversation practice (survival English or a free
        English lounge chat) — NOT a clinical encounter or medical examination. Analyse only the
        transcript evidence and the everyday rubric below. Be rigorous, practical, and constructive;
        do not inflate grades. Never request clinical reasoning, medical vocabulary, patient
        counselling, SOAP structure, or clinical signposting — a short answer can be excellent when
        it completes the real-life task.
    """.trimIndent()

    private val CLINICAL_SCHEMA = """
        {
          "grammar_score": 0 to 100 integer,
          "medical_accuracy_score": 0 to 100 integer,
          "clinical_reasoning_score": 0 to 100 integer,
          "professionalism_score": 0 to 100 integer,
          "fluency_score": 0 to 100 integer,
          "summary_feedback": "Short constructive paragraph summarizing strengths and weaknesses",
          "history_completeness": 0.0 to 1.0 number,
          "ice_elicited": true or false,
          "shared_plan": {
            "next_step": "agreed next step, or empty string",
            "monitoring_interval": "agreed monitoring/follow-up timing, or empty string",
            "safety_net": "warning signs and escalation plan, or empty string",
            "understanding_check": "teach-back or other understanding check, or empty string"
          },
          "empathy_markers_found": ["short marker or transcript quote"],
          "soap_note": {
            "subjective": "what the learner actually elicited",
            "objective": "objective information supported by the case/transcript",
            "assessment": "assessment supported by the encounter",
            "plan": "plan supported by the encounter"
          },
          "misconception_review": [
            {
              "turn_index": 0,
              "learner_claim": "exact clinical claim copied from that learner turn",
              "classification": "incorrect_claim or faulty_reasoning or unsafe_recommendation or overgeneralization or terminology_confusion",
              "topic": "short stable clinical topic",
              "severity": "low or moderate or high or critical",
              "verdict": "incorrect_for_this_case or misleading or unsafe",
              "correct_concept": "brief corrected clinical concept",
              "why_in_this_patient": ["case-specific clue or reasoning step"],
              "reasoning_repair": "the safer reasoning sequence to use next time",
              "score_impact": ["medical_accuracy or clinical_reasoning"],
              "confidence": 0.0 to 1.0 number
            }
          ],
          "corrections": [
            {
              "turn_index": 0,
              "original": "exact erroneous word or phrase from that learner turn",
              "corrected": "the smallest replacement that fixes this one issue",
              "explanation": "brief rule-based explanation; distinguish an error from an optional style improvement",
              "category": "Must be exactly one of: articles, plurals, verb_tense, prepositions, word_order, word_choice, konglish, register, other",
              "pattern_id": "stable lowercase rule id such as articles.missing_indefinite, verb_tense.missing_auxiliary, prepositions.allergic_to, or konglish.hand_phone",
              "feedback_type": "error or style",
              "confidence": 0.0 to 1.0 number,
              "l1_hypothesis": "short probable L1 influence, or null when unsupported"
            }
          ],
          "error_opportunities": {
            "articles": 0,
            "plurals": 0,
            "verb_tense": 0,
            "prepositions": 0,
            "word_order": 0,
            "word_choice": 0,
            "konglish": 0,
            "register": 0
          },
          "checklist_results": [
            {
              "item": "name of checklist/rubric item from the case",
              "required": true or false,
              "passed": true or false,
              "status": "passed or failed or not_applicable",
              "evidence": "short direct transcript quote, or null"
            }
          ],
          "commitment_results": [
            {
              "id": 0,
              "commitment": "the supplied commitment text",
              "result": "kept or missed or not_applicable",
              "evidence": "short transcript quote or reason"
            }
          ]
        }
    """.trimIndent()

    // Keys match ScoreDomains.SURVIVAL_METRIC_KEYS ("naturalness", "interaction",
    // "comprehension_repair", "fluency") with a _score suffix for consistency with the clinical
    // schema's *_score keys; MainViewModel.parseEvaluationJson reads the _score-suffixed names.
    private val EVERYDAY_SCHEMA = """
        {
          "naturalness_score": 0 to 100 integer,
          "interaction_score": 0 to 100 integer,
          "comprehension_repair_score": 0 to 100 integer,
          "fluency_score": 0 to 100 integer,
          "summary_feedback": "Short constructive paragraph summarizing strengths and weaknesses, including one concrete 10-minute everyday speaking/listening drill",
          "corrections": [
            {
              "turn_index": 0,
              "original": "exact erroneous word or phrase from that learner turn",
              "corrected": "the smallest replacement that fixes this one issue",
              "explanation": "brief rule-based explanation; distinguish an error from an optional style improvement",
              "category": "Must be exactly one of: articles, plurals, verb_tense, prepositions, word_order, word_choice, konglish, register, other",
              "pattern_id": "stable lowercase rule id such as articles.missing_indefinite, verb_tense.missing_auxiliary, prepositions.allergic_to, or konglish.hand_phone",
              "feedback_type": "error or style",
              "confidence": 0.0 to 1.0 number,
              "l1_hypothesis": "short probable L1 influence, or null when unsupported"
            }
          ],
          "error_opportunities": {
            "articles": 0,
            "plurals": 0,
            "verb_tense": 0,
            "prepositions": 0,
            "word_order": 0,
            "word_choice": 0,
            "konglish": 0,
            "register": 0
          },
          "checklist_results": [
            {
              "item": "name of an everyday task/rubric item",
              "required": true or false,
              "passed": true or false,
              "evidence": "short direct transcript quote, or null"
            }
          ]
        }
    """.trimIndent()

    fun isEverydayDomain(domain: String?): Boolean =
        (domain ?: DOMAIN_CLINICAL).lowercase() == DOMAIN_EVERYDAY

    /**
     * Returns (systemPrompt, userPrompt), branched on [domain] ("clinical" or "everyday").
     *
     * [rubricContext] is the optional rubric/context block from [AnalysisPromptBuilder]
     * (scoring anchors, checklist, empathy markers, per-session fairness notes, self-assessment,
     * student SOAP, debrief commitments). When non-empty it's inserted before the final
     * instruction so the evaluator scores against the real per-case rubric instead of the caseJson
     * alone — this is the input-side of app/analysis/prompt_builder.py's build_analysis_prompt.
     */
    fun buildEvalPrompts(
        transcript: String,
        caseJson: String,
        nativeLanguage: String,
        domain: String = DOMAIN_CLINICAL,
        rubricContext: String = ""
    ): Pair<String, String> {
        require(domain == DOMAIN_CLINICAL || domain == DOMAIN_EVERYDAY) {
            "Invalid analysis domain: $domain"
        }
        val everyday = isEverydayDomain(domain)
        val header = if (everyday) EVERYDAY_SYSTEM_HEADER else CLINICAL_SYSTEM_HEADER
        val schema = if (everyday) EVERYDAY_SCHEMA else CLINICAL_SCHEMA
        val contextLabel = if (everyday) "EVERYDAY SESSION CONTEXT" else "CASE GROUND TRUTH"

        val systemPrompt = """
            $header

            You MUST analyze the encounter and return a structured JSON response matching this EXACT schema:
            $schema

            CORRECTION EVIDENCE RULES:
            - Corrections may target learner/user turns only. turn_index must match a supplied [turn N] label.
            - original must be copied verbatim from that turn. Never reconstruct or paraphrase it.
            - Make one atomic correction per item and use the smallest edit that fixes that one issue.
            - feedback_type="error" only when the original is unacceptable or meaningfully misleading.
              Use feedback_type="style" for an acceptable sentence that merely has a more natural alternative.
            - Before returning an item, run an adversarial second check: could the original be
              acceptable in this exact context, a transcript artifact, or merely less natural?
              Retain it as an error only when the detection and this audit independently agree.
            - Omit uncertain findings and any item with confidence below 0.70.
            - Never infer pronunciation from a transcript. Pronunciation is evaluated separately from audio.
            - L1 is a hypothesis, not evidence. Set l1_hypothesis to null unless the observed form supports it.
            - pattern_id identifies the reusable rule, not the sentence. Keep it stable across examples.
            - Count error_opportunities independently of errors: how many learner contexts genuinely
              required or attempted each structure. Use 0 when a category is not measurable.
            - Return no more than 12 validated corrections. The app chooses a smaller teaching set;
              do not hide repeated evidence merely to reduce cognitive load.

            CLINICAL MISCONCEPTION RULES (clinical encounters only):
            - misconception_review explains concrete medical-accuracy or clinical-reasoning deductions.
            - Include only a clinical claim the learner actually made. learner_claim must be an exact
              contiguous quote from the learner turn named by turn_index.
            - Silence, an omitted question, an omitted differential, uncertainty, or ending the encounter
              early is NOT proof of a misconception. Report omissions only through checklist_results.
            - Do not turn an acceptable alternative approach, local-practice variation, or optional detail
              into an error. Omit findings below 0.80 confidence.
            - Use the case's clinical_knowledge as the preferred ground truth when present. If absent,
              derive cautiously from the supplied case details, teaching data, reference SOAP and rubric;
              do not invent patient facts, guideline citations, doses, or unsupported exceptions.
            - Separate factual error from faulty reasoning. severity=critical only for advice that could
              plausibly cause immediate serious harm in this encounter.
            - why_in_this_patient must connect the correction to supplied patient clues, not provide a
              generic disease essay. score_impact may contain only medical_accuracy or clinical_reasoning.
            - Return no more than 5 high-value findings. An empty array is correct when no grounded
              misconception was spoken, even if the learner missed checklist items or received a low score.
        """.trimIndent()

        val normalizedTranscript = normalizeTranscript(transcript)
        val rubricBlock = if (rubricContext.isNotBlank()) "\n$rubricContext\n" else ""
        // Every selectable L1 gets an evidence-gated transfer watchlist. The watchlist may explain
        // an independently observed error; it is explicitly forbidden from creating one.
        val l1Note = L1InterferenceCatalog.promptNote(nativeLanguage)
        val l1Block = if (l1Note.isNotBlank()) "\n$l1Note\n" else ""
        val userPrompt = """
            NATIVE LANGUAGE OF LEARNER: $nativeLanguage
            $l1Block
            $contextLabel:
            $caseJson

            TRANSCRIPT OF ENCOUNTER:
            $normalizedTranscript
            $rubricBlock
            Evaluate this encounter and output the JSON feedback. Do not include any markdown format around the JSON, return ONLY a valid JSON string.
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    internal fun normalizeTranscript(raw: String): String {
        try {
            val array = JSONArray(raw)
            val indexed = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val turnText = item.optString("text").trim()
                    if (turnText.isEmpty()) continue
                    val rawRole = item.optString("role").trim().lowercase()
                    val role = when (rawRole) {
                        "user", "learner", "doctor" -> "learner"
                        "model", "ai", "patient" -> "partner"
                        else -> rawRole.ifBlank { "unknown" }
                    }
                    add("[turn $index] $role: $turnText")
                }
            }
            if (indexed.isNotEmpty()) return indexed.joinToString("\n")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            // Plain-text imports use the tolerant line parser below.
        }

        val normalized = mutableListOf<String>()
        for ((index, line) in raw.lines().withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            val role = when {
                lower.startsWith("user:") || lower.startsWith("learner:") || lower.startsWith("doctor:") -> "learner"
                lower.startsWith("model:") || lower.startsWith("ai:") || lower.startsWith("patient:") -> "partner"
                else -> "unknown"
            }
            val colonIndex = trimmed.indexOf(':')
            val turnText = if (colonIndex >= 0) trimmed.substring(colonIndex + 1).trim() else trimmed
            normalized.add("[turn $index] $role: $turnText")
        }
        return if (normalized.isNotEmpty()) normalized.joinToString("\n") else raw
    }
}
