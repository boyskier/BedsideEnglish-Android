package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorIdentity
import org.json.JSONArray
import org.json.JSONObject

/** One expression the learner kept from their own conversation. */
data class MyPhrase(
    /** The better way to say it — the sentence they will drill. */
    val en: String,
    /** What they actually said, kept as the reason this line is here. */
    val original: String = "",
    /** One line on why the replacement is better, from the correction it came from. */
    val note: String = "",
    /** Where it came from ("Everyday · At the lab"), for the drill list. */
    val source: String = "",
    val addedAt: Long = 0,
)

/**
 * The learner's own collected expressions — the half of the phrasebook the app does not write.
 *
 * Everything else here is a bank someone else authored. This is the return path: a session ends,
 * the feedback names a line the learner said awkwardly and a better way to say it, and that better
 * way lands here — where "Say It: Everyday" then drills it like any other phrase.
 *
 * That loop is the point. A phrasebook the learner only ever reads is scrollable content; a
 * phrasebook fed by their own mistakes is the thing they came back for. Kept as one compact
 * settings value next to [SayItProgressTracker]'s phrase progress, which already keys on
 * `category|phrase` — so a kept expression drills, records takes and schedules reviews with no
 * further plumbing, under the category [CATEGORY].
 */
object MyPhrasebook {
    const val SETTING_KEY = "my_phrasebook_v1"

    /** The Say It category kept expressions drill under. Part of every stored progress key. */
    const val CATEGORY = "My expressions"

    /**
     * Ceiling on kept expressions.
     *
     * A collection is only useful while the learner can still see the whole of it. Past this the
     * oldest untouched entries are dropped rather than growing an archive nobody drills — the same
     * bounded-on-purpose reasoning as [SayItTakeLog].
     */
    const val MAX_ENTRIES = 60

    fun read(json: String): List<MyPhrase> = try {
        val array = JSONArray(json.ifBlank { "[]" })
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val en = entry.optString("en").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MyPhrase(
                en = en,
                original = entry.optString("original").trim(),
                note = entry.optString("note").trim(),
                source = entry.optString("source").trim(),
                addedAt = entry.optLong("addedAt", 0).coerceAtLeast(0),
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        emptyList()
    }

    fun write(phrases: List<MyPhrase>): String {
        val array = JSONArray()
        phrases.forEach { phrase ->
            array.put(
                JSONObject()
                    .put("en", phrase.en)
                    .put("original", phrase.original)
                    .put("note", phrase.note)
                    .put("source", phrase.source)
                    .put("addedAt", phrase.addedAt)
            )
        }
        return array.toString()
    }

    /**
     * Add one expression, newest first.
     *
     * Re-adding a line the learner already kept updates it in place rather than duplicating it:
     * the same wording is worth correcting twice — that is what makes it worth drilling — but two
     * identical cards in the drill list only ever read as a bug. The existing entry keeps its
     * position so a phrase they are already working through does not jump to the top on every
     * repeat offence.
     */
    fun add(phrases: List<MyPhrase>, phrase: MyPhrase): List<MyPhrase> {
        if (phrase.en.isBlank()) return phrases
        val key = ErrorIdentity.normalize(phrase.en)
        val existingIndex = phrases.indexOfFirst { ErrorIdentity.normalize(it.en) == key }
        if (existingIndex >= 0) {
            return phrases.toMutableList().apply {
                val existing = this[existingIndex]
                this[existingIndex] = phrase.copy(addedAt = existing.addedAt.takeIf { it > 0 } ?: phrase.addedAt)
            }
        }
        return (listOf(phrase) + phrases).take(MAX_ENTRIES)
    }

    /** Forget one expression. Matching is normalized so the UI can pass the displayed text back. */
    fun remove(phrases: List<MyPhrase>, en: String): List<MyPhrase> {
        val key = ErrorIdentity.normalize(en)
        return phrases.filterNot { ErrorIdentity.normalize(it.en) == key }
    }

    fun contains(phrases: List<MyPhrase>, en: String): Boolean {
        val key = ErrorIdentity.normalize(en)
        return phrases.any { ErrorIdentity.normalize(it.en) == key }
    }

    /** The kept expressions as a phrasebook category, so the drill screen reads one shape. */
    fun asCategory(phrases: List<MyPhrase>): EverydayPhraseCategory? {
        if (phrases.isEmpty()) return null
        return EverydayPhraseCategory(
            id = "mine",
            name = CATEGORY,
            goal = null,
            phrases = phrases.map { phrase ->
                EverydayPhrase(
                    en = phrase.en,
                    function = phrase.source,
                    gloss = phrase.note.ifBlank { null },
                    register = null,
                    why = phrase.original.takeIf { it.isNotBlank() },
                    skeleton = EverydayPhrasebook.skeletonFor(phrase.en),
                    categoryId = "mine",
                    categoryName = CATEGORY,
                )
            },
        )
    }
}
