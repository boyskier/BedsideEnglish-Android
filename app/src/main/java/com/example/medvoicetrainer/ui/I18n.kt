package com.example.medvoicetrainer.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val LocalTranslate = staticCompositionLocalOf<(String) -> String> { { it } }

private val supportedLanguageCodes = setOf("en", "ko", "es", "zh", "ar", "hi", "pt", "tl", "fr", "ja", "de", "id", "vi", "ru", "bn", "it", "th", "tr")

/** Accept both persisted ISO codes and the legacy English display names. */
internal fun normalizeLanguageCode(language: String): String {
    val normalized = language.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
    if (normalized in supportedLanguageCodes) return normalized

    return when (normalized) {
        "english" -> "en"
        "korean" -> "ko"
        "spanish" -> "es"
        "chinese" -> "zh"
        "arabic" -> "ar"
        "hindi" -> "hi"
        "portuguese" -> "pt"
        "tagalog", "filipino" -> "tl"
        "french" -> "fr"
        "japanese" -> "ja"
        "german", "deutsch" -> "de"
        "indonesian", "bahasa" -> "id"
        "vietnamese" -> "vi"
        "russian" -> "ru"
        "bengali" -> "bn"
        "italian" -> "it"
        "thai" -> "th"
        "turkish" -> "tr"
        else -> "en"
    }
}

/**
 * Parse only usable translation entries. Locale metadata and non-string/blank values are not
 * translations; omitting them here lets the English dictionary provide the intended fallback.
 */
internal fun parseLocaleEntries(jsonString: String): Map<String, String> {
    val root = try {
        Json.parseToJsonElement(jsonString) as? JsonObject
    } catch (_: Exception) {
        null
    }
    if (root == null) {
        // Some legacy locale assets contain a malformed unrelated entry. Preserve the valid,
        // line-based translations instead of dropping the entire language (English remains the
        // fallback for anything that cannot be recovered).
        val entry = Regex("""(?m)^\s*"([^"]+)"\s*:\s*"((?:\\.|[^"\\])*)"\s*,?\s*$""")
        return buildMap {
            entry.findAll(jsonString).forEach { match ->
                val key = match.groupValues[1]
                if (key == "_meta") return@forEach
                val rawValue = match.groupValues[2]
                val value = try {
                    (Json.parseToJsonElement("\"$rawValue\"") as JsonPrimitive).content
                } catch (_: Exception) {
                    rawValue
                }
                if (value.isNotBlank()) put(key, value)
            }
        }
    }
    return buildMap {
        root.forEach { (key, value) ->
            if (key == "_meta") return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            if (!primitive.isString) return@forEach
            val text = primitive.content
            if (text.isNotBlank()) put(key, text)
        }
    }
}

internal fun resolveTranslation(
    key: String,
    language: String,
    dictionaries: Map<String, Map<String, String>>
): String = lookupTranslation(key, normalizeLanguageCode(language), dictionaries)

/** [resolveTranslation] for a caller that already holds the normalized language code. */
internal fun lookupTranslation(
    key: String,
    languageCode: String,
    dictionaries: Map<String, Map<String, String>>
): String = dictionaries[languageCode]?.get(key)
    ?: dictionaries["en"]?.get(key)
    ?: key

object I18n {
    // Published as one immutable snapshot through a @Volatile field. t() is called from the
    // Compose threads for effectively every visible string, so it must never observe the map
    // mid-population; replacing the reference wholesale gives readers a safe, lock-free view
    // instead of letting them read a mutable map another thread is still filling.
    @Volatile
    private var dictionaries: Map<String, Map<String, String>> = emptyMap()

    @Volatile
    private var isInitialized = false

    /** The last (language string -> resolved code) pair, cached as one object — see [languageCode]. */
    private class ResolvedLanguage(val language: String, val code: String)

    // Resolved code for the last language string passed to t(). The UI asks for the same language
    // on every one of those hundreds of calls per recomposition, and normalizeLanguageCode()
    // allocates several intermediate strings each time. Cached as a single immutable object behind
    // one @Volatile reference so a reader can never pair one call's language with another's code.
    @Volatile
    private var resolvedLanguage: ResolvedLanguage? = null

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return

        // The main app is intentionally English-only. Load only its fallback dictionary during
        // cold start; onboarding languages are loaded on demand by [ensureLanguage]. This avoids
        // parsing all 19 locale files before the first frame while preserving every onboarding
        // translation and English fallback for incomplete locales.
        dictionaries = mapOf("en" to loadDictionary(context, "en"))
        isInitialized = true
    }

    /** Load one onboarding locale off the main thread and publish it atomically. */
    suspend fun ensureLanguage(context: Context, language: String) {
        val code = normalizeLanguageCode(language)
        if (code == "en" || dictionaries.containsKey(code)) return
        val loaded = withContext(Dispatchers.IO) { loadDictionary(context.applicationContext, code) }
        if (loaded.isEmpty()) return
        synchronized(this) {
            if (!dictionaries.containsKey(code)) dictionaries = dictionaries + (code to loaded)
        }
    }

    private fun loadDictionary(context: Context, code: String): Map<String, String> = try {
        val jsonString = context.assets.open("i18n/locales/$code.json")
            .bufferedReader()
            .use { it.readText() }
        parseLocaleEntries(jsonString)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    fun t(key: String, lang: String): String =
        lookupTranslation(key, languageCode(lang), dictionaries)

    private fun languageCode(lang: String): String {
        val cached = resolvedLanguage
        if (cached != null && cached.language == lang) return cached.code
        val code = normalizeLanguageCode(lang)
        // Racing callers may each recompute once; whichever pair is published last is still an
        // internally consistent (language, code) pair, so no reader ever sees a mismatched code.
        resolvedLanguage = ResolvedLanguage(lang, code)
        return code
    }
}
