package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/** One kept Say It recording: the learner's own voice saying one phrase, replayable later. */
data class SayItTake(
    /** Path relative to `filesDir`, resolved through `LearnerAudioStore.resolve`. */
    val path: String,
    val durationMs: Long = 0,
    val recordedAt: Long = 0,
    /** [IntelligibilityOutcome] name once an AI check graded this take; blank until then. */
    val outcome: String = "",
)

/**
 * Index of the learner's kept Say It recordings, persisted as one compact settings value next to
 * [SayItProgressTracker]'s phrase progress (the audio itself lives under `learner_audio/`).
 *
 * Bounded on purpose: a learner drilling the same phrase all week should be able to hear how they
 * sounded a few takes ago, not accumulate an unbounded voice archive on their phone. Both caps
 * evict oldest-first and hand the dropped entries back so their files can be deleted in the same
 * step — an entry that leaves this index must never leave its m4a behind.
 */
object SayItTakeLog {
    const val SETTING_KEY = "say_it_takes_v1"

    /** Enough to hear "first try vs. now" on one phrase without turning into an archive. */
    const val MAX_PER_PHRASE = 3

    /** Whole-feature ceiling (~2 MB of 32 kbps mono at typical phrase length). */
    const val MAX_TOTAL = 120

    /** A log update plus the entries the caps pushed out, whose audio files the caller deletes. */
    data class Update(
        val log: Map<String, List<SayItTake>>,
        val evicted: List<SayItTake>,
    )

    fun read(json: String): Map<String, List<SayItTake>> = try {
        val root = JSONObject(json.ifBlank { "{}" })
        root.keys().asSequence().mapNotNull { id ->
            val array = root.optJSONArray(id) ?: return@mapNotNull null
            val takes = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("path").trim()
                if (path.isEmpty()) return@mapNotNull null
                SayItTake(
                    path = path,
                    durationMs = item.optLong("durationMs", 0).coerceAtLeast(0),
                    recordedAt = item.optLong("recordedAt", 0).coerceAtLeast(0),
                    outcome = item.optString("outcome"),
                )
            }
            if (takes.isEmpty()) null else id to takes.sortedByDescending(SayItTake::recordedAt)
        }.toMap()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyMap()
    }

    fun write(log: Map<String, List<SayItTake>>): String {
        val root = JSONObject()
        log.forEach { (id, takes) ->
            if (takes.isEmpty()) return@forEach
            val array = JSONArray()
            takes.forEach { take ->
                array.put(
                    JSONObject()
                        .put("path", take.path)
                        .put("durationMs", take.durationMs)
                        .put("recordedAt", take.recordedAt)
                        .put("outcome", take.outcome)
                )
            }
            root.put(id, array)
        }
        return root.toString()
    }

    /** Add [take] as the newest recording for [phraseId], applying both caps. */
    fun add(
        log: Map<String, List<SayItTake>>,
        phraseId: String,
        take: SayItTake,
    ): Update {
        if (phraseId.isBlank() || take.path.isBlank()) return Update(log, emptyList())
        val existing = log[phraseId].orEmpty().filterNot { it.path == take.path }
        val kept = (listOf(take) + existing).take(MAX_PER_PHRASE)
        val evicted = existing.drop(MAX_PER_PHRASE - 1).toMutableList()
        var next = log + (phraseId to kept)

        // Global ceiling: the oldest recording anywhere goes first, so today's practice is never
        // the thing that gets dropped.
        var total = next.values.sumOf { it.size }
        while (total > MAX_TOTAL) {
            val oldest = next.entries
                .mapNotNull { (id, takes) -> takes.minByOrNull(SayItTake::recordedAt)?.let { id to it } }
                .minByOrNull { it.second.recordedAt } ?: break
            evicted += oldest.second
            next = removeTake(next, oldest.first, oldest.second.path)
            total -= 1
        }
        return Update(next, evicted)
    }

    /** Attach an AI verdict to the newest take of [phraseId] (no-op when nothing was kept). */
    fun tagLatest(
        log: Map<String, List<SayItTake>>,
        phraseId: String,
        outcome: String,
    ): Map<String, List<SayItTake>> {
        val takes = log[phraseId].orEmpty()
        if (takes.isEmpty()) return log
        val newest = takes.maxByOrNull(SayItTake::recordedAt) ?: return log
        return log + (phraseId to takes.map { if (it.path == newest.path) it.copy(outcome = outcome) else it })
    }

    /** Drop one recording the learner deleted; the caller deletes its file. */
    fun remove(
        log: Map<String, List<SayItTake>>,
        phraseId: String,
        path: String,
    ): Map<String, List<SayItTake>> = removeTake(log, phraseId, path)

    private fun removeTake(
        log: Map<String, List<SayItTake>>,
        phraseId: String,
        path: String,
    ): Map<String, List<SayItTake>> {
        val remaining = log[phraseId].orEmpty().filterNot { it.path == path }
        return if (remaining.isEmpty()) log - phraseId else log + (phraseId to remaining)
    }
}
