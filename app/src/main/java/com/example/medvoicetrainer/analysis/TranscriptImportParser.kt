package com.example.medvoicetrainer.analysis

/**
 * Which side of an imported conversation spoke first, for text that carries no speaker labels at
 * all. [LEARNER_FIRST] is the default because that is how a chat with an assistant starts — the
 * learner types (or says) the opening line. [OTHER_FIRST] is the escape hatch for a transcript
 * that opens with the assistant, e.g. a role-play where the patient greets first.
 */
enum class ImportSpeakerOrder { LEARNER_FIRST, OTHER_FIRST }

/**
 * Parses a conversation transcript the learner pasted in from an external app (ChatGPT/Gemini
 * live mode, a notes app, a share-sheet text dump, ...) into the same (role, text) turn shape
 * MainViewModel's live session pipeline produces, so an imported conversation can be run through
 * the exact same analysis/SRS pipeline as a real session. "doctor" is this app's existing
 * convention for the learner's own turns (see SessionMapper.kt); anything else is displayed and
 * scored as the other party.
 *
 * Three layers, tried in order, because real pasted text almost never arrives pre-labelled:
 *
 *  1. **Inline prefixes** — `You:` / `AI:` and friends, with the turn's text on the same line.
 *     This is what a learner produces when they hand-label a transcript.
 *  2. **Standalone speaker markers** — a line that is *only* `You said:` or `ChatGPT said:`, with
 *     the turn's text on the following lines. This is exactly what copying a conversation out of
 *     the ChatGPT web UI produces, and it is the single most common real-world paste. Layer 1
 *     alone silently mangled it: `"you said:"` does not start with `"you:"`, so every marker was
 *     swallowed into the previous turn as if the learner had said the words "You said".
 *  3. **Speaker-less alternation** — no labels of any kind, so turns are split on blank lines
 *     (falling back to one turn per line) and roles alternate from [ImportSpeakerOrder]. This one
 *     is a guess, which is why the caller exposes a toggle rather than deciding silently.
 *
 * Layers 1 and 2 share a pass; layer 3 runs only when that pass finds no labels whatsoever.
 */
object TranscriptImportParser {

    private const val LEARNER_ROLE = "doctor"
    private const val OTHER_ROLE = "patient"

    private val LEARNER_PREFIXES = listOf(
        "you:", "me:", "doctor:", "learner:", "student:", "candidate:", "user:"
    )
    private val OTHER_PREFIXES = listOf(
        "ai:", "assistant:", "patient:", "chatgpt:", "gpt:", "gemini:", "interviewer:", "bot:", "model:"
    )

    // Whole-line markers, matched after an optional trailing colon is removed: the ChatGPT web UI
    // copies "You said:", while some exports and accessibility dumps drop the colon and leave a
    // bare "ChatGPT". Only exact whole-line matches count, so an ordinary sentence that merely
    // begins with one of these words is still plain text.
    private val LEARNER_MARKERS = setOf("you said", "me", "you")
    private val OTHER_MARKERS = setOf(
        "chatgpt said", "gemini said", "claude said", "assistant said", "ai said",
        "chatgpt", "gemini", "claude", "assistant"
    )

    fun parse(
        raw: String,
        speakerOrder: ImportSpeakerOrder = ImportSpeakerOrder.LEARNER_FIRST
    ): List<Pair<String, String>> {
        val labelled = parseLabelled(raw)
        if (labelled.foundLabel) return labelled.turns
        return parseAlternating(raw, speakerOrder)
    }

    /**
     * Whether [raw] carries speaker labels of any kind. When false, [parse] had to guess who spoke
     * first and the caller should offer the [ImportSpeakerOrder] toggle instead of hiding it.
     */
    fun hasSpeakerLabels(raw: String): Boolean = parseLabelled(raw).foundLabel

    private data class LabelledResult(val turns: List<Pair<String, String>>, val foundLabel: Boolean)

    /**
     * Layers 1 + 2. Unlabelled lines are appended (with a space) onto whichever speaker's turn is
     * currently open, so multi-line and multi-paragraph turns survive. Lines before the first
     * recognized speaker are dropped (there is no speaker to attribute them to) — that is also
     * what strips the conversation title the web UI puts above the first "You said:".
     */
    private fun parseLabelled(raw: String): LabelledResult {
        val turns = mutableListOf<Pair<String, String>>()
        var currentRole: String? = null
        var foundLabel = false
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) {
                currentRole?.let { role -> turns.add(role to text) }
            }
            buffer.clear()
        }

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val lower = line.lowercase()
            val markerRole = standaloneMarkerRole(lower)
            val learnerPrefix = LEARNER_PREFIXES.firstOrNull { lower.startsWith(it) }
            val otherPrefix = OTHER_PREFIXES.firstOrNull { lower.startsWith(it) }
            when {
                // Checked before the prefixes: "You said:" is not a "you:" prefix, so without this
                // branch it would fall through to the continuation case and corrupt the turn.
                markerRole != null -> {
                    flush()
                    foundLabel = true
                    currentRole = markerRole
                }
                learnerPrefix != null -> {
                    flush()
                    foundLabel = true
                    currentRole = LEARNER_ROLE
                    buffer.append(line.substring(learnerPrefix.length).trim())
                }
                otherPrefix != null -> {
                    flush()
                    foundLabel = true
                    currentRole = OTHER_ROLE
                    buffer.append(line.substring(otherPrefix.length).trim())
                }
                currentRole != null -> {
                    if (buffer.isNotEmpty()) buffer.append(" ")
                    buffer.append(line)
                }
                else -> { /* no speaker recognized yet — drop preamble */ }
            }
        }
        flush()
        return LabelledResult(turns, foundLabel)
    }

    /** The role a whole-line speaker marker names, or null when the line is ordinary text. */
    private fun standaloneMarkerRole(lowerLine: String): String? {
        val stripped = lowerLine.removeSuffix(":").trim()
        if (stripped.isEmpty()) return null
        if (stripped in LEARNER_MARKERS) return LEARNER_ROLE
        if (stripped in OTHER_MARKERS) return OTHER_ROLE
        return null
    }

    /**
     * Layer 3. Blank-line-separated blocks are the turn boundary when the paste has any (the
     * common case — chat UIs put a blank line between messages); otherwise every non-blank line
     * becomes its own turn. Roles then alternate, which is right for a two-party conversation and
     * wrong for anything else, hence the caller-facing toggle.
     *
     * Fewer than two units is not a conversation — a single pasted paragraph carries no evidence
     * of who said it — so it yields nothing rather than being asserted to be the learner.
     */
    private fun parseAlternating(
        raw: String,
        speakerOrder: ImportSpeakerOrder
    ): List<Pair<String, String>> {
        val blocks = raw.split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { block -> block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }

        val units = if (blocks.size >= 2) {
            blocks
        } else {
            raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        if (units.size < 2) return emptyList()

        val firstRole = if (speakerOrder == ImportSpeakerOrder.LEARNER_FIRST) LEARNER_ROLE else OTHER_ROLE
        val secondRole = if (firstRole == LEARNER_ROLE) OTHER_ROLE else LEARNER_ROLE
        return units.mapIndexed { index, text ->
            (if (index % 2 == 0) firstRole else secondRole) to text
        }
    }

    /** True once [parse] found at least one non-blank learner turn — the minimum finishSession
     *  already requires to run analysis, so callers can surface a friendly error before that. */
    fun hasLearnerTurn(turns: List<Pair<String, String>>): Boolean =
        turns.any { it.first == LEARNER_ROLE && it.second.isNotBlank() }
}
