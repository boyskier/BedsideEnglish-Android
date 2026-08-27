package com.example.medvoicetrainer.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Reader for `data/survival_transitions.json` — the per-category menu of scene changes the
 * "Survival English — Advanced Beta" prompt is allowed to propose from, plus the errand fact
 * templates behind Stage 3's relay card.
 *
 * Only the beta path touches this: an ordinary Survival session never calls anything here, and
 * the file is new, so no existing scenario data or schema changed. The parsing entry points take a
 * JSON **string** (rather than a Context) so the whole thing is exercised by plain JVM unit tests;
 * [load] is the only Android-aware function.
 */
object SceneTransitionCatalog {

    const val ASSET_NAME = "survival_transitions.json"

    data class Option(
        val id: String,
        val title: String,
        val description: String,
        val place: String = "",
        val role: String = "",
        val factTemplates: List<String> = emptyList(),
    )

    data class CategoryMenu(
        val moves: List<Option> = emptyList(),
        val timeSkips: List<Option> = emptyList(),
        val newCharacters: List<Option> = emptyList(),
        val errands: List<Option> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = moves.isEmpty() && timeSkips.isEmpty() &&
                newCharacters.isEmpty() && errands.isEmpty()
    }

    class Catalog internal constructor(
        private val defaults: CategoryMenu,
        private val byCategory: Map<String, CategoryMenu>,
        private val factSlots: Map<String, List<String>>,
    ) {
        /** The menu for [category], falling back to the shared defaults for an unlisted one. */
        fun menuFor(category: String?): CategoryMenu {
            val specific = byCategory[category?.trim().orEmpty()] ?: return defaults
            return CategoryMenu(
                moves = specific.moves.ifEmpty { defaults.moves },
                timeSkips = specific.timeSkips.ifEmpty { defaults.timeSkips },
                newCharacters = specific.newCharacters.ifEmpty { defaults.newCharacters },
                errands = specific.errands.ifEmpty { defaults.errands },
            )
        }

        /**
         * The 2–3 concrete "facts" the learner is handed when they accept a solo errand, built by
         * filling an errand's templates from [factSlots]. Deliberately template composition rather
         * than an LLM call: this runs mid-session, must be instant and free, and the grader has to
         * be able to compare the learner's report against an exact known list.
         */
        fun errandFacts(
            category: String?,
            place: String,
            count: Int = 3,
            random: Random = Random.Default,
        ): List<String> {
            val errands = menuFor(category).errands
            if (errands.isEmpty()) return emptyList()
            val normalizedPlace = place.trim().lowercase()
            val errand = errands.firstOrNull { option ->
                val optionPlace = option.place.lowercase()
                // Both sides must be non-empty: `"anything".contains("")` is always true, so an
                // errand entered without a `place` would otherwise match every proposal and win
                // ahead of the one that genuinely describes where the learner was sent.
                normalizedPlace.isNotEmpty() && optionPlace.isNotEmpty() &&
                    (optionPlace.contains(normalizedPlace) || normalizedPlace.contains(optionPlace))
            } ?: errands[random.nextInt(errands.size)]
            val templates = errand.factTemplates.ifEmpty { return emptyList() }
            return templates.shuffled(random)
                .take(count.coerceAtLeast(1))
                .map { fillSlots(it, random) }
        }

        internal fun fillSlots(template: String, random: Random): String =
            SLOT_PATTERN.replace(template) { match ->
                val values = factSlots[match.groupValues[1]]
                if (values.isNullOrEmpty()) match.value else values[random.nextInt(values.size)]
            }

        /**
         * The "only propose from this list" block injected into the beta system prompt. Empty when
         * the category has nothing to offer, in which case the prompt simply omits the section and
         * the tool is never worth calling.
         */
        fun promptBlockFor(category: String?): String {
            val menu = menuFor(category)
            if (menu.isEmpty) return ""
            val lines = mutableListOf<String>()
            fun section(header: String, type: String, options: List<Option>) {
                if (options.isEmpty()) return
                lines.add(header)
                options.forEach { option ->
                    val extras = buildList {
                        if (option.place.isNotEmpty()) add("place=\"${option.place}\"")
                        if (option.role.isNotEmpty()) add("new_character_role=\"${option.role}\"")
                    }.joinToString(", ")
                    val suffix = if (extras.isEmpty()) "" else " [$extras]"
                    lines.add("  - type=\"$type\", title=\"${option.title}\": ${option.description}$suffix")
                }
            }
            section("MOVE TO A NEARBY PLACE:", "move", menu.moves)
            section("SKIP AHEAD IN TIME:", "time_skip", menu.timeSkips)
            section("A DIFFERENT PERSON TAKES OVER:", "new_character", menu.newCharacters)
            section("SEND THE USER OFF ALONE, THEN HAVE THEM REPORT BACK:", "solo_errand", menu.errands)
            return lines.joinToString("\n")
        }
    }

    // Both braces escaped: Android's ICU regex rejects a bare `}` that the desktop JVM accepts,
    // and an exception here poisons the whole object (failed <clinit> → NoClassDefFoundError on
    // every later touch of the class).
    private val SLOT_PATTERN = Regex("\\{([a-z_]+)\\}")

    private val EMPTY = Catalog(CategoryMenu(), emptyMap(), emptyMap())

    @Volatile
    private var cached: Catalog? = null

    /** Parsed catalog from the bundled asset; empty (and harmless) if the asset is unreadable. */
    fun load(context: Context): Catalog {
        cached?.let { return it }
        val parsed = try {
            parse(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately NOT cached. A read that failed once (a transient asset-manager error,
            // an interrupted open) would otherwise leave every later solo errand in this process
            // factless, and a factless errand is the one outcome the relay task must never
            // produce — the partner is told the learner is back with news they were never given.
            // A genuinely malformed asset still short-circuits cheaply, because parse() returns
            // EMPTY without throwing and that result *is* cached below.
            return EMPTY
        }
        cached = parsed
        return parsed
    }

    fun parse(json: String): Catalog {
        val root = try {
            JSONObject(json)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return EMPTY
        }
        val slots = LinkedHashMap<String, List<String>>()
        root.optJSONObject("fact_slots")?.let { obj ->
            for (key in obj.keys()) slots[key] = stringList(obj.optJSONArray(key))
        }
        val defaults = parseMenu(root.optJSONObject("defaults"))
        val categories = LinkedHashMap<String, CategoryMenu>()
        root.optJSONObject("categories")?.let { obj ->
            for (key in obj.keys()) categories[key] = parseMenu(obj.optJSONObject(key))
        }
        return Catalog(defaults, categories, slots)
    }

    private fun parseMenu(obj: JSONObject?): CategoryMenu {
        if (obj == null) return CategoryMenu()
        return CategoryMenu(
            moves = parseOptions(obj.optJSONArray("moves")),
            timeSkips = parseOptions(obj.optJSONArray("time_skips")),
            newCharacters = parseOptions(obj.optJSONArray("new_characters")),
            errands = parseOptions(obj.optJSONArray("errands")),
        )
    }

    private fun parseOptions(array: JSONArray?): List<Option> {
        if (array == null) return emptyList()
        val out = mutableListOf<Option>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            out += Option(
                id = item.optString("id").trim().ifEmpty { "option_$index" },
                title = title,
                description = item.optString("description").trim(),
                place = item.optString("place").trim(),
                role = item.optString("role").trim(),
                factTemplates = stringList(item.optJSONArray("fact_templates")),
            )
        }
        return out
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
