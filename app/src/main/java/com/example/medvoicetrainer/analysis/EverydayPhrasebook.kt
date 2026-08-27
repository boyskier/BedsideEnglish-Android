package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorIdentity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Everyday Phrasebook — Open Book's counterpart for the everyday modes.
 *
 * The two scaffolds answer two different kinds of stuck. [OpenBookEngine] exists for the learner
 * whose *clinical* knowledge runs out: told "chest pain radiating to the left arm" they have
 * nothing to ask next. In Survival, Lounge and Listening there is no diagnosis to hand over — what
 * runs out is the *sentence*. The learner knows exactly what they want to do ("ask them to say
 * that again", "turn the invitation down without sounding cold") and has no English for it, so the
 * conversation stops on a move they could have made in their own language without thinking.
 *
 * So the unit here is a conversational **function**, not a place. A phrasebook organised by
 * situation ("at the cafe", "at the bank") is a travel guide; the block a learner actually hits is
 * the same handful of moves in every situation, which is what makes them worth drilling.
 *
 * The reveal is staged for the same reason Open Book's is — a screen of model sentences turns a
 * speaking exercise into reading practice:
 *  - [PhrasebookLevel.FUNCTION] — what this move *does*, and nothing else, so the learner tries
 *    their own English first
 *  - [PhrasebookLevel.SKELETON] — the frame with a blank where their own words go
 *  - [PhrasebookLevel.SENTENCE] — the complete sentence, last, and offered as something to say
 *    aloud rather than to paste into the box
 *
 * Pure logic: no Android, no network, no LLM. A card is JSON parsing and costs nothing.
 */
enum class PhrasebookLevel(val step: Int) {
    HIDDEN(0),
    FUNCTION(1),
    SKELETON(2),
    SENTENCE(3);

    companion object {
        fun of(step: Int): PhrasebookLevel = entries.lastOrNull { it.step <= step } ?: HIDDEN
    }
}

/**
 * How formal a line is.
 *
 * Carried per phrase because register, not grammar, is what everyday English fails on: the learner
 * who says "I would be delighted to accompany you" to a labmate and "give me that" to a professor
 * has made no grammatical error at all. Shown as a badge so the choice is visible at a glance.
 */
enum class PhraseRegister(val key: String) {
    CASUAL("casual"),
    NEUTRAL("neutral"),
    POLITE("polite");

    companion object {
        fun of(key: String?): PhraseRegister? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * One model expression.
 *
 * [function] is the level-1 reveal and is deliberately the only thing shown there. [gloss] is the
 * same idea in the learner's own language, so level 1 is usable by someone who cannot yet read the
 * English label. [skeleton] is the level-2 frame — authored when the phrase ships one, derived by
 * [EverydayPhrasebook.skeletonFor] otherwise.
 */
data class EverydayPhrase(
    val en: String,
    val function: String,
    val gloss: String?,
    val register: PhraseRegister?,
    val why: String?,
    val skeleton: String,
    /** Words that count as having used this line, for the live tick. Never graded. */
    val keywords: List<String> = emptyList(),
    val categoryId: String = "",
    val categoryName: String = "",
    /** True when the scene authored this line itself, rather than it coming from the function bank. */
    val sceneSpecific: Boolean = false,
) {
    /** Stable identity for progress/usage keys — the sentence itself, normalized. */
    val id: String get() = ErrorIdentity.normalize(en)
}

/** One function of the shared bank ("When you didn't catch it"). */
data class EverydayPhraseCategory(
    val id: String,
    val name: String,
    val goal: String?,
    val phrases: List<EverydayPhrase>,
)

/**
 * Everything the phrasebook can show for one everyday session.
 *
 * Scene lines come first and are marked as such: a line the scenario author wrote for *this*
 * conversation is worth more than a generic one, and the learner should be able to tell which is
 * which.
 */
data class PhrasebookCard(
    val title: String,
    val entries: List<EverydayPhrase>,
) {
    val sceneEntries: List<EverydayPhrase> get() = entries.filter { it.sceneSpecific }
    val functionEntries: List<EverydayPhrase> get() = entries.filterNot { it.sceneSpecific }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The next level up, or null once the sheet is fully open. */
    fun nextLevel(current: PhrasebookLevel): PhrasebookLevel? =
        PhrasebookLevel.entries.firstOrNull { it.step > current.step }
}

/**
 * How much phrase help a finished everyday session actually had, for the feedback report.
 *
 * [previewed] and [level] are separate numbers because they describe two different learners: one
 * read three expressions on the start screen and then closed the card, the other opened the sheet
 * mid-conversation because they were stuck. Collapsing them would report both as "used help".
 */
data class PhrasebookAidsUsed(
    val level: Int = 0,
    val previewed: Boolean = false,
    /** Full immutable snapshots, so feedback/history can drill the exact lines that were offered. */
    val offered: List<EverydayPhrase> = emptyList(),
    /** Expressions the learner actually produced, out of [offered]. */
    val used: List<String> = emptyList(),
) {
    val any: Boolean get() = level > 0 || previewed
    val unused: List<EverydayPhrase>
        get() {
            val usedIds = used.mapTo(HashSet()) { ErrorIdentity.normalize(it) }
            return offered.filterNot { it.id in usedIds }
        }
}

/** Durable, language-resolved snapshot stored inside a session's rawCaseJson. */
data class PhrasebookSessionSnapshot(
    val title: String,
    val offered: List<EverydayPhrase>,
    val usedIds: Set<String>,
) {
    val used: List<String> get() = offered.filter { it.id in usedIds }.map { it.en }
    val unused: List<EverydayPhrase> get() = offered.filterNot { it.id in usedIds }
}

object EverydayPhrasebook {

    /** The shared function bank, merged into the APK's assets from `data/`. */
    const val ASSET = "everyday_phrases.json"

    /** A scene's own lines, in `survival_situations.json` or the lounge-case JSON files. */
    const val SCENE_FIELD = "key_expressions"

    /** Session-owned payload. Kept in rawCaseJson to avoid a parallel persistence lifecycle. */
    const val SESSION_FIELD = "phrasebook_session"

    /**
     * How many expressions the pre-start card offers.
     *
     * Three, and the cap is the feature. A start screen listing ten model sentences is read once,
     * remembered as none, and delays the thing the learner came to do; three can actually be
     * carried into the conversation.
     */
    const val PREVIEW_MAX = 3

    /** How many the in-session sheet holds. Past this it stops being a glance. */
    const val CARD_MAX = 6

    /** The blank a skeleton leaves for the learner — the same run [PhraseBlanks] already handles. */
    const val BLANK = "___"

    /* Superseded malformed pattern from the conflicted change.

    private val TRAILING_PUNCTUATION = Regex("[.!?…]+$")

    */
    private val TRAILING_PUNCTUATION = Regex("[.!?,;:]+$")

    /**
     * The phrasebook answers "how do I say this in English", which is the whole difficulty of the
     * everyday modes and none of the difficulty of an encounter — a clinical case has Open Book,
     * and stacking both would put two competing answer sheets behind one conversation.
     */
    fun isSupportedMode(mode: String): Boolean = mode in setOf("survival", "lounge", "listening")

    // ---- parsing ----

    /**
     * Parse the shared function bank. [language] selects the gloss; English is the fallback, so a
     * bank that has not been translated yet still yields usable entries rather than none.
     */
    fun parseBank(json: String, language: String = "en"): List<EverydayPhraseCategory> {
        val root = try {
            JSONObject(json.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val categories = root.optJSONArray("categories") ?: return emptyList()
        return (0 until categories.length()).mapNotNull { index ->
            val entry = categories.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val id = entry.optString("id").trim().ifEmpty { name }
            val phrases = phrasesFrom(
                array = entry.optJSONArray("phrases"),
                language = language,
                categoryId = id,
                categoryName = name,
                sceneSpecific = false,
            )
            if (phrases.isEmpty()) null else EverydayPhraseCategory(
                id = id,
                name = name,
                goal = entry.optString("goal").trim().ifEmpty { null },
                phrases = phrases,
            )
        }
    }

    /** The scene's own lines, if its JSON authored any. */
    fun sceneExpressions(caseJson: String, language: String = "en"): List<EverydayPhrase> {
        val root = try {
            JSONObject(caseJson.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return phrasesFrom(
            array = root.optJSONArray(SCENE_FIELD),
            language = language,
            categoryId = "scene",
            categoryName = root.optString("case_name").trim()
                .ifEmpty { root.optString("patient_name").trim() },
            sceneSpecific = true,
        )
    }

    /**
     * Read a phrase array, accepting both the rich object form and a bare string.
     *
     * The string form is what `foundations_phrases.json` has always used, and the everyday drill
     * shares that loader — so accepting both here is what lets one screen read either bank without
     * a schema migration of content that is also used outside Android.
     */
    private fun phrasesFrom(
        array: JSONArray?,
        language: String,
        categoryId: String,
        categoryName: String,
        sceneSpecific: Boolean,
    ): List<EverydayPhrase> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val raw = array.opt(index)) {
                is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { text ->
                    EverydayPhrase(
                        en = text,
                        function = "",
                        gloss = null,
                        register = null,
                        why = null,
                        skeleton = skeletonFor(text),
                        categoryId = categoryId,
                        categoryName = categoryName,
                        sceneSpecific = sceneSpecific,
                    )
                }

                is JSONObject -> {
                    val en = raw.optString("en").trim().takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val authoredSkeleton = raw.optString("skeleton").trim()
                    EverydayPhrase(
                        en = en,
                        function = raw.optString("function").trim(),
                        gloss = glossFor(raw.opt("gloss"), language),
                        register = PhraseRegister.of(raw.optString("register")),
                        why = raw.optString("why").trim().ifEmpty { null },
                        skeleton = authoredSkeleton.ifEmpty { skeletonFor(en) },
                        keywords = stringList(raw.optJSONArray("keywords")),
                        categoryId = categoryId,
                        categoryName = categoryName,
                        sceneSpecific = sceneSpecific,
                    )
                }

                else -> null
            }
        }
    }

    /**
     * Resolve a gloss for [language]. Accepts a plain string (one language, as the desktop-era
     * content writes it) or a map keyed by language code.
     */
    fun glossFor(raw: Any?, language: String): String? = when (raw) {
        is String -> raw.trim().ifEmpty { null }
        is JSONObject -> {
            val code = language.trim().lowercase().substringBefore('-')
            (raw.optString(code).trim().ifEmpty { raw.optString("en").trim() }).ifEmpty { null }
        }

        else -> null
    }

    // ---- the card ----

    /**
     * Build the card for a running session: the scene's own lines first, then enough of the
     * function bank to fill [CARD_MAX].
     *
     * The bank contribution is spread across categories rather than taken in file order, because
     * three ways of asking someone to repeat themselves is one move, not three, and a learner who
     * opens the sheet mid-conversation needs breadth far more than depth.
     */
    fun cardFor(
        caseJson: String,
        bank: List<EverydayPhraseCategory>,
        language: String = "en",
        title: String = "",
        limit: Int = CARD_MAX,
    ): PhrasebookCard? {
        val scene = sceneExpressions(caseJson, language)
        val seed = seedOf(caseJson)
        val filler = spreadAcrossCategories(bank, seed, limit)
        val entries = (scene + filler)
            .distinctBy { it.id }
            .take(limit)
        if (entries.isEmpty()) return null
        return PhrasebookCard(
            title = title.ifBlank { scene.firstOrNull()?.categoryName.orEmpty() },
            entries = entries,
        )
    }

    /**
     * The pre-start card: [PREVIEW_MAX] expressions, chosen the same deterministic way, so what the
     * learner reads on the start screen is what the sheet opens on once they are inside.
     */
    fun preview(
        bank: List<EverydayPhraseCategory>,
        caseJson: String = "",
        language: String = "en",
        limit: Int = PREVIEW_MAX,
    ): List<EverydayPhrase> {
        val scene = sceneExpressions(caseJson, language)
        val seed = seedOf(caseJson)
        return (scene + spreadAcrossCategories(bank, seed, limit))
            .distinctBy { it.id }
            .take(limit)
    }

    /** Mark the concrete case that will actually start; no global pending flag can leak to another case. */
    fun markPreviewed(caseJson: String): String = mutateRoot(caseJson) { root ->
        val aids = root.optJSONObject("practice_aids") ?: JSONObject()
        root.put("practice_aids", aids.put("phrasebook_previewed", true))
    }

    /** Persist the exact resolved card at session creation, including rich display/drill metadata. */
    fun withSessionSnapshot(caseJson: String, card: PhrasebookCard?): String {
        if (card == null) return caseJson
        return mutateRoot(caseJson) { root ->
            root.put(
                SESSION_FIELD,
                JSONObject()
                    .put("version", 1)
                    .put("title", card.title)
                    .put("offered", JSONArray(card.entries.map(::phraseToJson)))
                    .put("used_ids", JSONArray()),
            )
        }
    }

    /** Update only usage while retaining the immutable offered snapshots. */
    fun withSessionUsage(caseJson: String, usedEnglish: Collection<String>): String {
        val hasSession = runCatching {
            JSONObject(caseJson.ifBlank { "{}" }).optJSONObject(SESSION_FIELD) != null
        }.getOrDefault(false)
        if (!hasSession) return caseJson
        return mutateRoot(caseJson) { root ->
            val session = root.optJSONObject(SESSION_FIELD) ?: return@mutateRoot
            val ids = usedEnglish.map { ErrorIdentity.normalize(it) }.filter { it.isNotBlank() }.distinct()
            session.put("used_ids", JSONArray(ids))
            root.put(SESSION_FIELD, session)
        }
    }

    /** Rehydrate feedback/history without consulting a mutable asset bank. */
    fun sessionSnapshot(caseJson: String): PhrasebookSessionSnapshot? {
        val root = runCatching { JSONObject(caseJson.ifBlank { "{}" }) }.getOrNull() ?: return null
        val session = root.optJSONObject(SESSION_FIELD) ?: return null
        val offeredArray = session.optJSONArray("offered") ?: return null
        val offered = phrasesFrom(
            array = offeredArray,
            language = "en",
            categoryId = "",
            categoryName = "",
            sceneSpecific = false,
        ).mapIndexed { index, parsed ->
            val raw = offeredArray.optJSONObject(index)
            if (raw == null) parsed else parsed.copy(
                categoryId = raw.optString("category_id"),
                categoryName = raw.optString("category_name"),
                sceneSpecific = raw.optBoolean("scene_specific", false),
            )
        }
        if (offered.isEmpty()) return null
        return PhrasebookSessionSnapshot(
            title = session.optString("title"),
            offered = offered,
            usedIds = stringList(session.optJSONArray("used_ids")).toSet(),
        )
    }

    fun aidsFromCaseJson(caseJson: String): PhrasebookAidsUsed {
        val snapshot = sessionSnapshot(caseJson)
        val root = runCatching { JSONObject(caseJson.ifBlank { "{}" }) }.getOrNull()
        val aids = root?.optJSONObject("practice_aids")
        return PhrasebookAidsUsed(
            level = aids?.optInt("phrasebook_revealed", 0) ?: 0,
            previewed = aids?.optBoolean("phrasebook_previewed", false) == true,
            offered = snapshot?.offered.orEmpty(),
            used = snapshot?.used.orEmpty(),
        )
    }

    private fun phraseToJson(phrase: EverydayPhrase): JSONObject = JSONObject()
        .put("en", phrase.en)
        .put("function", phrase.function)
        .put("gloss", phrase.gloss ?: JSONObject.NULL)
        .put("register", phrase.register?.key ?: JSONObject.NULL)
        .put("why", phrase.why ?: JSONObject.NULL)
        .put("skeleton", phrase.skeleton)
        .put("keywords", JSONArray(phrase.keywords))
        .put("category_id", phrase.categoryId)
        .put("category_name", phrase.categoryName)
        .put("scene_specific", phrase.sceneSpecific)

    private inline fun mutateRoot(caseJson: String, mutate: (JSONObject) -> Unit): String {
        val root = runCatching { JSONObject(caseJson.ifBlank { "{}" }) }.getOrElse { return caseJson }
        mutate(root)
        return root.toString()
    }

    /**
     * One phrase from each category in turn, cycling until [limit] is reached.
     *
     * Deterministic in [seed] rather than random: the same scene must offer the same expressions on
     * every recomposition and after a process death, or the sheet a learner half-read would come
     * back different. Seeding by the case keeps different scenes from all showing the same first
     * three lines.
     */
    fun spreadAcrossCategories(
        bank: List<EverydayPhraseCategory>,
        seed: Int,
        limit: Int,
    ): List<EverydayPhrase> {
        if (bank.isEmpty() || limit <= 0) return emptyList()
        val picked = mutableListOf<EverydayPhrase>()
        val rounds = bank.maxOf { it.phrases.size }
        for (round in 0 until rounds) {
            for (categoryIndex in bank.indices) {
                if (picked.size >= limit) return picked
                // Rotate the category order as well as the phrase within it. Without this,
                // categories after CARD_MAX could never reach a preview/card, no matter which
                // concrete scene seeded it.
                val category = bank[Math.floorMod(seed + categoryIndex, bank.size)]
                if (round >= category.phrases.size) continue
                val offset = Math.floorMod(seed + categoryIndex, category.phrases.size)
                val phrase = category.phrases[(offset + round) % category.phrases.size]
                if (picked.none { it.id == phrase.id }) picked += phrase
            }
        }
        return picked
    }

    /** Stable per-case seed, so the pre-start card and the in-session sheet agree. */
    fun seedOf(caseJson: String): Int {
        val id = try {
            JSONObject(caseJson.ifBlank { "{}" }).let { root ->
                root.optString("id").ifBlank { root.optString("case_name") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        var hash = 17
        for (char in id) hash = hash * 31 + char.code
        return hash
    }

    // ---- level 2 ----

    /**
     * The frame for [sentence]: the opening kept, the tail replaced by a blank.
     *
     * Front-loaded on purpose. English carries the *move* in the opening words ("Sorry, did you
     * say…", "I'd love to, but…") and the situation-specific content in the tail, which is exactly
     * the split level 2 wants — the learner is handed the part they cannot invent and asked to
     * supply the part they can.
     */
    fun skeletonFor(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return ""
        if (PhraseBlanks.hasBlank(trimmed)) return trimmed
        val punctuation = TRAILING_PUNCTUATION.find(trimmed)?.value.orEmpty()
        val body = trimmed.removeSuffix(punctuation).trim()
        val words = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return trimmed
        val keep = maxOf(2, (words.size + 1) / 2).coerceAtMost(words.size - 1)
        val head = words.take(keep).joinToString(" ").trimEnd(',', ';', ':', '—', '-')
        return "$head $BLANK$punctuation"
    }

    // ---- the live tick ----

    /**
     * Compile the card into [CoverageEngine] rules, so a line the learner actually says ticks
     * itself off instead of sitting on the sheet as something they read once.
     *
     * This is the half that keeps the phrasebook from becoming scrollable content: help that is
     * never observed being used is indistinguishable from help that was never useful. The ticks are
     * advisory and are never graded — a missed one costs the learner nothing.
     */
    fun rulesFor(card: PhrasebookCard): List<ObjectiveRule> = card.entries.map { phrase ->
        if (phrase.keywords.isEmpty()) {
            CoverageEngine.ruleFor(phrase.en)
        } else {
            CoverageEngine.ruleFor(
                phrase.en,
                JSONObject().put("keywords", JSONArray(phrase.keywords)),
            )
        }
    }

    /**
     * Whether the sheet should be holding the microphone muted right now.
     *
     * Shares [OpenBookMicPolicy] with Open Book rather than adding a second setting: the learner's
     * answer to "may a scaffold mute me while I read it" is one preference, not one per scaffold.
     * Under the default policy the ladder splits the same way — the function and the frame are
     * read, while the finished sentence is there to be said out loud off the screen, so holding the
     * mic there would force a toggle per sentence.
     */
    fun shouldHoldMic(policy: OpenBookMicPolicy, level: PhrasebookLevel): Boolean = when (policy) {
        OpenBookMicPolicy.NEVER -> false
        OpenBookMicPolicy.ALWAYS -> true
        OpenBookMicPolicy.READING -> level.step < PhrasebookLevel.SENTENCE.step
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it, null)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}
