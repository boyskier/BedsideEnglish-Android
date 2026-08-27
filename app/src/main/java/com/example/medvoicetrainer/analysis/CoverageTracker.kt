package com.example.medvoicetrainer.analysis

import android.content.Context
import org.json.JSONObject

/**
 * Ported from app/analysis/coverage.py — rule-based history-coverage tracking, no API calls.
 * Drives the encounter navigation aids: the open-book panel's live "what to ask" checklist, the
 * unified "Help me continue" button, the five-stage interview progress bar, and the guided-script
 * fallback when a case ships no script of its own.
 *
 * Matching uses the shared flexible intent rules in [InterviewRules] rather than exact model
 * sentences. Patient-answer corroboration supplies a deterministic ASR safety net: if the
 * learner question is garbled but the patient clearly says "I take metformin", medications are
 * treated as resolved without pretending the learner transcript itself was recognised.
 */

data class HistoryDomain(
    val key: String,
    val phase: Int?,
    val core: Boolean = true,
    val label: String = "",
    val keywords: List<String> = emptyList(),
    val followCues: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val chiefTerms: List<String> = emptyList()
)

data class CoverageHint(
    val domain: HistoryDomain,
    val questions: List<String>,
    val reason: String,
    val phase: Int
)

private val PHASE_BY_KEY = mapOf(
    "opening" to 0,
    "onset_duration" to 1,
    "location" to 1,
    "character" to 1,
    "severity" to 1,
    "radiation" to 1,
    "aggravating" to 1,
    "relieving" to 1,
    "aggravating_relieving" to 1,
    "progression" to 1,
    "associated_symptoms" to 1,
    "pmh" to 2,
    "medications" to 3,
    "allergies" to 3,
    "smoking" to 4,
    "alcohol" to 4,
    "substances" to 4,
    "living_work" to 4,
    "family_hx" to 4,
    "social_hx" to 4,
    "ideas" to 4,
    "concerns" to 4,
    "expectations" to 4,
    "summary_closing" to 4
)

/** Return the five-stage UI phase for one coverage-domain definition. */
fun domainPhase(domain: HistoryDomain): Int {
    val explicit = domain.phase
    if (explicit != null) return explicit.coerceIn(0, 4)
    return PHASE_BY_KEY[domain.key] ?: 1
}

/**
 * History domains only make sense for standard patient encounters, not free-form skill
 * drills / handovers / personalised review personas.
 */
fun supportsHistoryCoverage(case: Map<String, Any?>?): Boolean {
    return case != null && case["persona_override"].let { it == null || it == "" }
}

object HistoryDomains {
    // Known placeholder strings in history_domains.json questions -> replacement templates.
    private val SYMPTOM_SUBS = listOf(
        "symptoms" to "{chief}",
        "How long have you had this?" to "How long have you had {chief}?",
        "Can you describe what it feels like?" to "Can you describe what {chief} feels like?",
        "Where exactly do you feel it?" to "Where exactly do you feel {chief}?"
    )

    fun loadDomains(context: Context): List<HistoryDomain> {
        return parseDomains {
            context.assets.open("history_domains.json").bufferedReader().use { it.readText() }
        }
    }

    /**
     * The parsing half, separated from the asset lookup so the domains can come from anywhere
     * (ledger LOG-41). Reading `history_domains.json` needs a [Context]; parsing what it says
     * does not, and requiring one is what kept every consumer of this out of plain JVM tests.
     */
    fun parseDomains(read: () -> String): List<HistoryDomain> {
        return try {
            val text = read()
            val root = JSONObject(text)
            val arr = root.optJSONArray("domains") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                HistoryDomain(
                    key = d.optString("key", ""),
                    phase = if (d.has("phase")) d.optInt("phase") else null,
                    core = d.optBoolean("core", true),
                    label = d.optString("label", ""),
                    keywords = jsonStringList(d.optJSONArray("keywords")),
                    followCues = jsonStringList(d.optJSONArray("follow_cues")),
                    questions = jsonStringList(d.optJSONArray("questions")),
                    chiefTerms = jsonStringList(d.optJSONArray("chief_terms"))
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    private fun jsonStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    fun contextualize(question: String, chief: String): String {
        if (chief.isEmpty()) return question
        var result = question
        for ((placeholder, template) in SYMPTOM_SUBS) {
            if (placeholder in result) {
                result = result.replace(placeholder, template.replace("{chief}", chief))
            }
        }
        return result
    }
}

/**
 * Tracks which history domains the student's turns have touched, and which domains the
 * patient's recent answers make a natural next question.
 */
class CoverageTracker(
    val domains: List<HistoryDomain>,
    case: Map<String, Any?>? = null
) {
    val covered: MutableSet<String> = mutableSetOf()
    val volunteered: MutableSet<String> = mutableSetOf()
    private val suggested: MutableSet<String> = mutableSetOf()
    private var chief: String = ""
    var furthestPhase: Int = 0
    var lastPhase: Int = 0

    /**
     * Per-domain justification for what is ticked: which transcript line matched, and how strongly.
     * The UI reads this to show the learner *why* something is ticked rather than asking them to
     * trust the matcher, which is the only honest way to present rules that will sometimes miss.
     */
    private val evidenceByDomain: MutableMap<String, CoverageEvidence> = mutableMapOf()

    /**
     * Rows the learner ticked or cleared by hand. Once set, the learner's judgement is final for
     * that domain: `true` keeps it ticked even if no rule ever fires, `false` keeps it clear even
     * if one does. This is what makes an occasional wrong match a one-tap correction instead of a
     * reason to distrust the whole panel.
     */
    private val manualOverrides: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Learner turns seen so far. Unlike every other signal here this one cannot fail to be
     * observed, which is exactly why the opening question is retired on it — see
     * [CoverageSignals.OPENING_STALE_AFTER_LEARNER_TURNS].
     */
    var learnerTurns: Int = 0
        private set

    // InterviewRules.normalizeTranscript runs NFKC normalization plus several regex passes. The
    // chief complaint and each domain's chief_terms/follow_cues are fixed for the whole encounter,
    // but isApplicable() is called for every domain on every pass (update, notePatientTurn,
    // nextHint, phaseItems, cuedUncovered) — so their normalized forms are derived once and reused
    // instead of being recomputed dozens of times per turn.
    private var normalizedChief: String = ""
    private val normalizedChiefTerms = HashMap<String, List<String>>()
    private val normalizedFollowCues = HashMap<String, List<String>>()

    private fun normalizedChiefTermsFor(domain: HistoryDomain): List<String> =
        normalizedChiefTerms.getOrPut(domain.key) {
            domain.chiefTerms.map { InterviewRules.normalizeTranscript(it) }
        }

    private fun normalizedFollowCuesFor(domain: HistoryDomain): List<String> =
        normalizedFollowCues.getOrPut(domain.key) {
            domain.followCues.map { InterviewRules.normalizeTranscript(it) }
        }

    // Context bias: how strongly each domain feels like the natural NEXT question, based on
    // cues the patient has recently volunteered. Decays every patient turn so a stale cue
    // can't dominate the whole encounter.
    private val cueScore: MutableMap<String, Double> = mutableMapOf()

    companion object {
        const val CUE_HIT = 1.0
        const val CUE_DECAY = 0.5
        const val CUE_MIN = 0.75
    }

    init {
        if (case != null) setChief(case)
    }

    private fun setChief(case: Map<String, Any?>) {
        val raw = case["chief_complaint"]?.toString() ?: ""
        chief = if (raw.isNotEmpty()) raw.split(" for ")[0].split(" since ")[0].trim() else ""
        normalizedChief = InterviewRules.normalizeTranscript(chief)
    }

    fun reset(case: Map<String, Any?>? = null) {
        if (case != null) setChief(case)
        // The domain definitions themselves don't change across a reset, so the normalized
        // chief_terms/follow_cues caches stay valid and are deliberately kept.
        covered.clear()
        volunteered.clear()
        suggested.clear()
        cueScore.clear()
        evidenceByDomain.clear()
        manualOverrides.clear()
        learnerTurns = 0
        furthestPhase = 0
        lastPhase = 0
    }

    /**
     * Information already obtained, whether asked or volunteered.
     *
     * This getter builds a fresh merged set on every read, so callers that need it per domain
     * inside a loop should snapshot it once (or use [isResolved]) rather than reading it per item.
     */
    val resolved: Set<String>
        get() {
            val out = (covered + volunteered).filterTo(mutableSetOf()) { manualOverrides[it] != false }
            for ((key, manuallyCovered) in manualOverrides) if (manuallyCovered) out.add(key)
            return out
        }

    /** Allocation-free equivalent of `key in resolved`, for use inside per-domain loops. */
    private fun isResolved(key: String): Boolean =
        manualOverrides[key] ?: (key in covered || key in volunteered)

    /**
     * How this domain came to be ticked — the learner asking it outright, a bare keyword, the
     * patient volunteering it, or the learner ticking the row themselves.
     */
    fun evidenceFor(key: String): CoverageEvidence {
        manualOverrides[key]?.let { manual ->
            return if (manual) {
                CoverageEvidence(source = CoverageSource.MANUAL)
            } else {
                CoverageEvidence(source = CoverageSource.NONE)
            }
        }
        evidenceByDomain[key]?.let { return it }
        // Fallback for keys pushed directly into the sets rather than observed through a turn.
        return when {
            key in covered -> CoverageEvidence(source = CoverageSource.LEARNER_STRONG)
            key in volunteered -> CoverageEvidence(source = CoverageSource.PATIENT_VOLUNTEERED)
            else -> CoverageEvidence()
        }
    }

    /**
     * Record the learner's own verdict for a domain: `true` ticked, `false` cleared, `null` back to
     * whatever the rules decide.
     */
    fun setManualOverride(key: String, covered: Boolean?) {
        if (covered == null) manualOverrides.remove(key) else manualOverrides[key] = covered
    }

    fun manualOverrideFor(key: String): Boolean? = manualOverrides[key]

    /**
     * True once the opening question has been overtaken by the encounter — either substantive
     * history is already underway, or enough learner turns have gone by that suggesting an opener
     * would be absurd regardless of what the rules managed to recognise.
     */
    private fun openingIsBygone(): Boolean =
        learnerTurns >= CoverageSignals.OPENING_STALE_AFTER_LEARNER_TURNS ||
            furthestPhase >= 1 ||
            domains.any { domainPhase(it) == 1 && isResolved(it.key) }

    /** Domains that still make sense to suggest as the *next* thing to ask. */
    private fun suggestableUncovered(): List<HistoryDomain> {
        val bygone = openingIsBygone()
        return domains.filter {
            isApplicable(it) && !isResolved(it.key) && !(bygone && domainPhase(it) == 0)
        }
    }

    fun isApplicable(domain: HistoryDomain): Boolean {
        val chiefTerms = domain.chiefTerms
        if (chiefTerms.isEmpty() || chief.isEmpty() || chief == "the symptoms" || chief == "symptoms") return true
        return normalizedChiefTermsFor(domain).any { it in normalizedChief }
    }

    fun phaseItems(phase: Int, resolvedOnly: Boolean = false): Set<String> {
        val keys = domains.filter { isApplicable(it) && domainPhase(it) == phase }.map { it.key }.toSet()
        return if (resolvedOnly) keys intersect resolved else keys
    }

    private fun contextualize(question: String): String = HistoryDomains.contextualize(question, chief)

    /**
     * Mark domains matched by this student turn; returns newly covered keys.
     *
     * [turnIndex] is the position of this turn in the session transcript, so a tick can point back
     * at the sentence that produced it. Pass -1 when there is no transcript to point into.
     *
     * Already-covered domains are re-checked, but only so a later unambiguous question can upgrade
     * a tick that was first made on a bare keyword. They are never re-reported as newly covered.
     */
    fun update(userText: String, turnIndex: Int = -1): Set<String> {
        val text = InterviewRules.normalizeTranscript(userText)
        learnerTurns += 1
        if (text.isEmpty()) return emptySet()
        val quote = CoverageSignals.shortQuote(userText)
        val new = mutableSetOf<String>()
        for (d in domains) {
            if (!isApplicable(d)) continue
            val alreadyCovered = d.key in covered
            // Nothing to gain from re-matching a domain that is already ticked at full strength.
            if (alreadyCovered && evidenceFor(d.key).source >= CoverageSource.LEARNER_STRONG) continue
            val source = CoverageSignals.learnerSource(d.key, text, d.keywords)
            if (source == CoverageSource.NONE) continue
            val observed = CoverageEvidence(source = source, turnIndex = turnIndex, quote = quote)
            evidenceByDomain[d.key] = evidenceByDomain[d.key]?.mergedWith(observed) ?: observed
            if (!alreadyCovered) {
                covered.add(d.key)
                new.add(d.key)
            }
        }
        if (new.isNotEmpty()) {
            val phases = domains.filter { it.key in new }.map { domainPhase(it) }
            lastPhase = phases.max()
            furthestPhase = maxOf(furthestPhase, lastPhase)
        }
        return new
    }

    /**
     * Read the patient's answer and bias the next hint toward whatever the patient just
     * volunteered. Strong domain-specific evidence also resolves information after a garbled
     * learner transcript. No API calls.
     */
    fun notePatientTurn(patientText: String, turnIndex: Int = -1): Set<String> {
        val text = InterviewRules.normalizeTranscript(patientText)
        // Fade prior cues first; the freshest turn should weigh most.
        val keys = cueScore.keys.toList()
        for (k in keys) {
            val decayed = cueScore.getValue(k) * CUE_DECAY
            if (decayed < 0.01) cueScore.remove(k) else cueScore[k] = decayed
        }
        if (text.isBlank()) return emptySet()

        val quote = CoverageSignals.shortQuote(patientText)
        val newlyResolved = mutableSetOf<String>()
        // A patient narrating their complaint is only evidence that the consultation got opened
        // while the consultation is *still* opening. Once it has moved on, the same phrasing is
        // just the patient elaborating, and letting it resolve the opening retroactively would
        // re-introduce the over-ticking this whole layer exists to remove.
        val openingBygone = openingIsBygone()
        for (d in domains) {
            val key = d.key
            if (key.isEmpty() || !isApplicable(d)) continue
            if (openingBygone && domainPhase(d) == 0) continue
            if (!isResolved(key) && CoverageSignals.patientEvidence(key, text)) {
                volunteered.add(key)
                newlyResolved.add(key)
                val observed = CoverageEvidence(
                    source = CoverageSource.PATIENT_VOLUNTEERED,
                    turnIndex = turnIndex,
                    quote = quote,
                )
                evidenceByDomain[key] = evidenceByDomain[key]?.mergedWith(observed) ?: observed
            }
            if (isResolved(key)) continue
            if (normalizedFollowCuesFor(d).any { InterviewRules.kwHit(text, it) }) {
                cueScore[key] = (cueScore[key] ?: 0.0) + CUE_HIT
            }
        }
        if (newlyResolved.isNotEmpty()) {
            val phases = domains.filter { it.key in newlyResolved }.map { domainPhase(it) }
            lastPhase = phases.max()
            furthestPhase = maxOf(furthestPhase, lastPhase)
        }
        return newlyResolved
    }

    /**
     * Uncovered domains the patient's recent answers point to, strongest first. Empty when no
     * cue has cleared CUE_MIN (then fall back to order).
     */
    private fun cuedUncovered(): List<HistoryDomain> {
        return domains
            .withIndex()
            .filter { (_, d) -> isApplicable(d) && !isResolved(d.key) && (cueScore[d.key] ?: 0.0) >= CUE_MIN }
            .sortedWith(compareByDescending<IndexedValue<HistoryDomain>> { cueScore[it.value.key] ?: 0.0 }.thenBy { it.index })
            .map { it.value }
    }

    /**
     * Return a context-first continuation hint without early-stage lock-in.
     *
     * Ranking is: fresh patient cue, unresolved area in the current/future phase, then an
     * earlier important omission. Once substantive history is underway, an ASR-missed opening
     * is obsolete as a *next* question and is left visible only in the detailed checklist.
     */
    fun nextHint(): CoverageHint? {
        val uncovered = suggestableUncovered()
        if (uncovered.isEmpty()) return null

        // 1) Context-aware: surface the strongest patient-cued domain we haven't just
        //    suggested. (A cued domain is worth repeating eventually, so we only skip it
        //    while a fresher un-suggested cue exists.)
        val cued = cuedUncovered()
        for (d in cued) {
            if (d in uncovered && d.key !in suggested) {
                suggested.add(d.key)
                return withHintReason(d, "follow_up")
            }
        }

        // 2) Continue from the furthest confidently observed phase. Earlier transcript gaps
        //    cannot monopolise assistance after a phase jump.
        val forward = uncovered.filter { domainPhase(it) >= furthestPhase }
        val earlier = uncovered.filter { domainPhase(it) < furthestPhase }
        val ordered = forward + earlier
        for (d in ordered) {
            if (d.key !in suggested) {
                suggested.add(d.key)
                val reason = if (d in forward) "continue" else "catch_up"
                return withHintReason(d, reason)
            }
        }

        // 3) Everything suggested already — reset and start over, preferring a still-relevant
        //    cued domain if one exists.
        suggested.clear()
        val nxt = cued.firstOrNull { it in uncovered } ?: ordered[0]
        suggested.add(nxt.key)
        val reason = if (cued.any { it.key == nxt.key }) "follow_up" else if (nxt in forward) "continue" else "catch_up"
        return withHintReason(nxt, reason)
    }

    private fun withHintReason(domain: HistoryDomain, reason: String): CoverageHint {
        val questions = withContextualizedQuestions(domain)
        return CoverageHint(domain = domain, questions = questions, reason = reason, phase = domainPhase(domain))
    }

    /**
     * The top few things worth asking next, best first, for a picker rather than a single guess.
     *
     * Ranking a whole list instead of committing to one answer is the point: the rules can be
     * confident about *which handful* of areas are still open long before they can be confident
     * about which single one the learner most wants, so offering a short menu turns a wrong first
     * guess from a dead end into a second row on the same sheet. The mix is capped per reason so
     * the learner always sees a genuine choice — a patient follow-up, the next area in order, and
     * one earlier gap — instead of three variations of the same suggestion.
     *
     * Unlike [nextHint] this is read-only: it never consumes the [suggested] rotation, so opening
     * the picker twice without saying anything shows the same options both times.
     */
    fun hintOptions(limit: Int = 3): List<CoverageHint> {
        if (limit <= 0) return emptyList()
        val uncovered = suggestableUncovered()
        if (uncovered.isEmpty()) return emptyList()
        val uncoveredKeys = uncovered.mapTo(mutableSetOf()) { it.key }

        val picked = LinkedHashMap<String, CoverageHint>()
        fun take(candidates: List<HistoryDomain>, reason: String, max: Int) {
            var used = 0
            for (d in candidates) {
                if (used >= max || picked.size >= limit) return
                if (d.key in picked) continue
                picked[d.key] = withHintReason(d, reason)
                used += 1
            }
        }

        take(cuedUncovered().filter { it.key in uncoveredKeys }, "follow_up", max = 1)
        take(uncovered.filter { domainPhase(it) >= furthestPhase }, "continue", max = 2)
        take(uncovered.filter { domainPhase(it) < furthestPhase }, "catch_up", max = 1)
        // Whatever is left over, in domain order, if the caps left room.
        for (d in uncovered) {
            if (picked.size >= limit) break
            if (d.key in picked) continue
            picked[d.key] = withHintReason(d, if (domainPhase(d) < furthestPhase) "catch_up" else "continue")
        }
        return picked.values.toList()
    }

    private fun withContextualizedQuestions(d: HistoryDomain): List<String> {
        if (chief.isEmpty() || d.questions.isEmpty()) return d.questions
        val firstQ = contextualize(d.questions[0])
        if (firstQ == d.questions[0]) return d.questions
        return listOf(firstQ) + d.questions.drop(1)
    }
}

data class GuidedScriptStep(val domain: String, val label: String, val question: String)

/**
 * Ordered list of guided-script steps. A case may ship its own `guided_script` (list of
 * strings or of {question, domain, label} maps); otherwise one model question per generic
 * history domain is used.
 */
fun buildGuidedScript(case: Map<String, Any?>, allDomains: List<HistoryDomain>): List<GuidedScriptStep> {
    @Suppress("UNCHECKED_CAST")
    val custom = case["guided_script"] as? List<Any?>
    if (!custom.isNullOrEmpty()) {
        return custom.mapIndexed { i, entry ->
            when (entry) {
                is String -> GuidedScriptStep(domain = "step_$i", label = "Step ${i + 1}", question = entry)
                is Map<*, *> -> GuidedScriptStep(
                    domain = entry["domain"]?.toString() ?: "step_$i",
                    label = entry["label"]?.toString() ?: "Step ${i + 1}",
                    question = entry["question"]?.toString() ?: ""
                )
                else -> GuidedScriptStep(domain = "step_$i", label = "Step ${i + 1}", question = "")
            }
        }
    }
    return allDomains.filter { it.questions.isNotEmpty() }.map {
        GuidedScriptStep(domain = it.key, label = it.label.ifEmpty { it.key }, question = it.questions[0])
    }
}
