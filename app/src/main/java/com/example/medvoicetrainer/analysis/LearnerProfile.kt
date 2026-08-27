package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorItemEntity

/**
 * Distills the learner's saved corrections ([ErrorItemEntity] / the SRS `error_items` table) into
 * a compact, prompt-ready English profile — a few hundred characters at most, regardless of how
 * many sessions of history exist. This is the piece that lets the debrief tutor (and, later, any
 * personalized analysis prompt) know *this specific learner's* recurring English weaknesses
 * without dumping the whole raw history into context (which would grow unbounded with usage).
 *
 * It deliberately reuses [MistakeGenomeEngine] — the same category weighting and "stubborn item"
 * ranking the Dashboard's mistake genome already shows — so the tutor is grounded in exactly the
 * pattern the learner sees elsewhere in the app, not a second divergent computation.
 */
object LearnerProfile {

    private const val MAX_RECURRING_CORRECTIONS = 6

    /**
     * Build the profile block. Returns a short human-readable string suitable for pasting into an
     * LLM prompt. When there is no confirmed correction history yet, returns an "early learner"
     * note so the caller can still frame the tutor sensibly instead of pretending to know weak
     * spots that haven't been observed.
     */
    fun distill(
        errorItems: List<ErrorItemEntity>,
        maxRecurring: Int = MAX_RECURRING_CORRECTIONS,
    ): String {
        val genome = MistakeGenomeEngine.buildMistakeGenome(errorItems)
        if (genome.totalItems == 0) {
            return "LEARNER ENGLISH PROFILE: No saved corrections yet — this is an early session. " +
                "Focus on encouraging fluent output and modeling natural phrasing; don't invent weaknesses."
        }

        val lines = StringBuilder("LEARNER ENGLISH PROFILE (from their own saved corrections):\n")

        val topCategories = genome.topCategories.take(4).joinToString(", ") { it.label }
        if (topCategories.isNotBlank()) {
            lines.append("- Recurring error types: ").append(topCategories).append('\n')
        }

        // Concrete "keeps saying X, should say Y" pairs the tutor can drill by name. Stubborn
        // items (repeated lapses / high seen count) come first; if there aren't enough, top up
        // with any other still-active corrections so an early learner still gets specifics.
        val recurring = (genome.stubbornItems + errorItems.filter { it.state != "mastered" })
            .distinctBy { it.key }
            .filter { it.original.isNotBlank() && it.corrected.isNotBlank() }
            .take(maxRecurring)
        if (recurring.isNotEmpty()) {
            lines.append("- Corrections that keep recurring:\n")
            for (item in recurring) {
                lines.append("    \"").append(item.original.trim()).append("\" → \"")
                    .append(item.corrected.trim()).append('"')
                if (item.lapses > 0) lines.append("  (relapsed ${item.lapses}×)")
                lines.append('\n')
            }
        }

        if (genome.masteredItems > 0) {
            lines.append("- Already improving / mastered: ").append(genome.masteredItems)
                .append(" corrections — acknowledge progress, don't re-drill these.\n")
        }

        lines.append(
            "- This session: prioritize the recurring items above. When the learner repeats one " +
                "out loud, model the natural phrasing and have them say it again correctly — one fix at a time."
        )
        return lines.toString().trimEnd()
    }
}
