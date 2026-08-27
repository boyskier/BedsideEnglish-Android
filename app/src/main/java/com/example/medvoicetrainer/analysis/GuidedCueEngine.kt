package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/**
 * Beginner "spoon-feeding" scaffold for Foundations / `coaching_mode` encounters.
 *
 * A total-beginner learner freezes in front of an open mic because three loads hit at once — what
 * to ask, how to say it, and listening in real time. This engine removes the middle load: given the
 * case's learning objectives, the live coverage map, and how many times the learner has already
 * *produced* each objective before, it decides the single next thing to prompt and how much to
 * reveal.
 *
 * The reveal fades on purpose (gradual release, "I do → we do → you do") so the cue never becomes a
 * permanent crutch:
 *  - [GuidedFadeStage.FULL]  — the whole model sentence ("When did it start?")
 *  - [GuidedFadeStage.CLOZE] — a blanked sentence the learner completes ("When did _____?")
 *  - [GuidedFadeStage.OBJECTIVE] — only the English communicative goal ("Ask about onset")
 *  - [GuidedFadeStage.NONE]  — no cue; the learner is expected to stand on their own
 *
 * Pure logic (no Android / no network), so the whole fade + selection ladder is unit-testable. The
 * caller supplies the persisted per-skill success count via [successCountFor]; this object never
 * touches storage.
 */
enum class GuidedFadeStage { FULL, CLOZE, OBJECTIVE, NONE }

data class GuidedCue(
    /** The exact learning-objective string this cue scaffolds (also the coverage-map key). */
    val objective: String,
    val stage: GuidedFadeStage,
    /** The full model sentence the learner should aim to say — always populated for TTS playback. */
    val modelPhrase: String,
    /** What to render for [stage]: the full sentence, a cloze blank, or the English objective. */
    val displayPhrase: String,
)

object GuidedCueEngine {

    /**
     * Successes needed to leave each stage. Two reps at FULL cements the chunk; two more at CLOZE
     * forces recall of the content words; two more with only the communicative goal forces full production;
     * after that the scaffold is gone. Deliberately gentle — this is the beginner on-ramp, not a test.
     */
    fun stageForSuccessCount(count: Int): GuidedFadeStage = when {
        count <= 1 -> GuidedFadeStage.FULL
        count <= 3 -> GuidedFadeStage.CLOZE
        count <= 5 -> GuidedFadeStage.OBJECTIVE
        else -> GuidedFadeStage.NONE
    }

    /**
     * Stable per-skill key so the fade counter follows a communicative skill across different
     * cases (greeting in found_001 and found_004 share one counter). Normalizes the objective text.
     */
    fun skillKeyFor(objective: String): String =
        objective.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(80)

    /** True when a case opts into guided coaching (the Foundations authoring flag). */
    fun isCoachingCase(caseJson: String): Boolean = try {
        JSONObject(caseJson).optBoolean("coaching_mode", false)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        false
    }

    /**
     * Turn a model sentence into a cloze the learner completes: keep the leading (function) words,
     * blank the trailing content, preserve terminal punctuation. "When did it start?" → "When did
     * _____?". Deterministic so the golden/unit tests can pin it.
     */
    fun clozeOf(phrase: String): String {
        val trimmed = phrase.trim()
        val trailing = trimmed.takeLastWhile { it == '?' || it == '.' || it == '!' }
        val core = trimmed.dropLast(trailing.length).trim()
        val words = core.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= 1) return "_____$trailing"
        val keep = (words.size / 2).coerceAtLeast(1)
        return words.take(keep).joinToString(" ") + " _____" + trailing
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    private fun objectivesInOrder(root: JSONObject, coverage: Map<String, Boolean>): List<String> {
        val arr = root.optJSONArray("learning_objectives")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { arr.optString(it, null)?.takeIf { s -> s.isNotBlank() } }
        }
        return coverage.keys.toList()
    }

    /**
     * Resolve the model phrase for [objective]. Authoring order of preference:
     *  1. an explicit `objective_cues` entry (`{objective, say}`) — authoritative, matched by
     *     exact text or normalized skill key;
     *  2. the `suggested_questions` list — the question whose words best overlap the objective,
     *     falling back to the index-aligned question when nothing overlaps.
     * Returns null when the case gives us nothing usable (the objective then gets no cue).
     */
    private fun phraseFor(
        root: JSONObject,
        objective: String,
        objectiveIndex: Int,
    ): String? {
        val cues = root.optJSONArray("objective_cues")
        if (cues != null) {
            val wantedKey = skillKeyFor(objective)
            for (i in 0 until cues.length()) {
                val c = cues.optJSONObject(i) ?: continue
                val cueObj = c.optString("objective", "")
                if (cueObj == objective || skillKeyFor(cueObj) == wantedKey) {
                    val say = c.optString("say", "").trim()
                    if (say.isNotEmpty()) {
                        return say
                    }
                }
            }
        }

        val sq = root.optJSONArray("suggested_questions") ?: return null
        val questions = (0 until sq.length()).mapNotNull {
            sq.optString(it, null)?.trim()?.takeIf { s -> s.isNotEmpty() }
        }
        if (questions.isEmpty()) return null

        val objTokens = tokens(objective)
        val best = questions.maxByOrNull { tokens(it).intersect(objTokens).size }
        val bestScore = best?.let { tokens(it).intersect(objTokens).size } ?: 0
        val phrase = if (bestScore > 0) best else questions.getOrNull(objectiveIndex.coerceIn(0, questions.lastIndex))
        return phrase
    }

    /**
     * The single cue to show right now, or null when there is nothing to scaffold — every objective
     * covered, the next uncovered objective has no authored/derivable phrase, or the learner has
     * graduated that objective (stage [GuidedFadeStage.NONE]). Callers render at most one card.
     */
    fun nextCue(
        caseJson: String,
        coverage: Map<String, Boolean>,
        successCountFor: (objective: String) -> Int,
    ): GuidedCue? {
        val root = try {
            JSONObject(caseJson)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return null
        }
        val objectives = objectivesInOrder(root, coverage)
        if (objectives.isEmpty()) return null

        val nextIndex = objectives.indexOfFirst { coverage[it] != true }
        if (nextIndex < 0) return null // everything covered
        val objective = objectives[nextIndex]

        val stage = stageForSuccessCount(successCountFor(objective))
        if (stage == GuidedFadeStage.NONE) return null // learner has graduated this skill

        val modelPhrase = phraseFor(root, objective, nextIndex) ?: return null

        val display = when (stage) {
            GuidedFadeStage.FULL -> modelPhrase
            GuidedFadeStage.CLOZE -> clozeOf(modelPhrase)
            GuidedFadeStage.OBJECTIVE -> objective
            GuidedFadeStage.NONE -> modelPhrase // unreachable (guarded above)
        }

        return GuidedCue(
            objective = objective,
            stage = stage,
            modelPhrase = modelPhrase,
            displayPhrase = display,
        )
    }
}
