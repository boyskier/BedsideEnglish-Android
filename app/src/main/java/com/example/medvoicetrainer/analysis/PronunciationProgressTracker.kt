package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, local-only outcome log used for weekly pronunciation progress calculations. */
data class PronunciationProgressSummary(
    val attempts: Int,
    val passed: Int,
    val focusPattern: String?
) {
    val passRatePercent: Int
        get() = if (attempts == 0) 0 else ((passed * 100.0) / attempts).toInt()
}

object PronunciationProgressTracker {
    const val MAX_EVENTS = 200

    fun append(
        existingJson: String,
        timestamp: String,
        category: String,
        target: String,
        passed: Boolean
    ): String {
        val old = try { JSONArray(existingJson.ifBlank { "[]" }) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { JSONArray() }
        val all = mutableListOf<JSONObject>()
        for (i in 0 until old.length()) old.optJSONObject(i)?.let(all::add)
        all += JSONObject()
            .put("timestamp", timestamp)
            .put("pattern", PronunciationEvidencePolicy.pattern(category))
            .put("target", target.take(120))
            .put("passed", passed)
        return JSONArray(all.takeLast(MAX_EVENTS)).toString()
    }

    fun summarize(existingJson: String, sinceTimestamp: String): PronunciationProgressSummary {
        val events = try { JSONArray(existingJson.ifBlank { "[]" }) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { JSONArray() }
        var attempts = 0
        var passed = 0
        val failedByPattern = mutableMapOf<String, Int>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("timestamp") < sinceTimestamp) continue
            attempts += 1
            if (event.optBoolean("passed")) {
                passed += 1
            } else {
                val pattern = event.optString("pattern").ifBlank { "other" }
                failedByPattern[pattern] = failedByPattern.getOrDefault(pattern, 0) + 1
            }
        }
        val focus = failedByPattern.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )?.key
        return PronunciationProgressSummary(attempts, passed, focus)
    }
}
