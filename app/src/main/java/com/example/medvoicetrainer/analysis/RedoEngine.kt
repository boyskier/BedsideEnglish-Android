package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorIdentity

/**
 * Selects which of a session's corrections deserve an immediate SPOKEN redo on the feedback
 * screen ("say it right now, out loud"). Reading a correction is recognition; the learning
 * moment is producing the fixed form with your own mouth while the mistake is still fresh —
 * the "Redo" half of the redo→transfer loop the roadmap left open.
 *
 * Selection is deterministic and conservative: it keeps the analysis' own importance order,
 * drops targets that can't work as a one-breath spoken attempt (near-identical pairs, empty
 * sides, paragraph-length rewrites), and dedupes near-duplicates so three redo slots never
 * burn on the same phrase twice.
 */
object RedoEngine {

    const val DEFAULT_MAX_TARGETS = 3

    /** A corrected sentence longer than this many words is a rewrite, not a redo target. */
    const val MAX_TARGET_WORDS = 24

    data class RedoTarget(
        val original: String,
        val corrected: String,
        val explanation: String,
        val category: String
    )

    fun selectRedoTargets(
        candidates: List<RedoTarget>,
        maxTargets: Int = DEFAULT_MAX_TARGETS
    ): List<RedoTarget> {
        val out = mutableListOf<RedoTarget>()
        val seenKeys = mutableSetOf<String>()
        for (c in candidates) {
            if (out.size >= maxTargets) break
            val original = c.original.trim()
            val corrected = c.corrected.trim()
            if (original.isEmpty() || corrected.isEmpty()) continue

            val key = ErrorIdentity.normalize(corrected)
            if (key.isEmpty() || key in seenKeys) continue
            // The redo must change something audible: identical normalized forms are no-ops.
            if (key == ErrorIdentity.normalize(original)) continue
            if (key.split(" ").size > MAX_TARGET_WORDS) continue

            seenKeys.add(key)
            out.add(c.copy(original = original, corrected = corrected))
        }
        return out
    }
}
