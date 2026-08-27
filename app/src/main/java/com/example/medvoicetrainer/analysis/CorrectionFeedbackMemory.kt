package com.example.medvoicetrainer.analysis

import org.json.JSONObject

/**
 * Local active-learning memory from learner decisions. It does not silently suppress findings;
 * it tells the next evaluator which rule families have produced false alarms and therefore need
 * stronger evidence.
 */
object CorrectionFeedbackMemory {
    private val decisions = setOf("accepted", "not_error", "stt_error", "style_only")

    fun record(existingJson: String, patternId: String, decision: String): String {
        val pattern = patternId.trim().take(120)
        val normalizedDecision = decision.trim().lowercase()
        if (pattern.isBlank() || normalizedDecision !in decisions) return existingJson
        val root = try {
            JSONObject(existingJson.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            JSONObject()
        }
        val counts = root.optJSONObject(pattern) ?: JSONObject()
        counts.put(
            normalizedDecision,
            counts.optInt(normalizedDecision, 0).coerceAtLeast(0) + 1
        )
        root.put(pattern, counts)
        return root.toString()
    }

    fun promptNote(existingJson: String): String {
        val root = try {
            JSONObject(existingJson.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            return ""
        }
        val entries = buildList {
            for (pattern in root.keys()) {
                val counts = root.optJSONObject(pattern) ?: continue
                val accepted = counts.optInt("accepted", 0).coerceAtLeast(0)
                val rejected = counts.optInt("not_error", 0).coerceAtLeast(0) +
                    counts.optInt("stt_error", 0).coerceAtLeast(0)
                val style = counts.optInt("style_only", 0).coerceAtLeast(0)
                val total = accepted + rejected + style
                if (total >= 2) add(Triple(pattern, accepted, rejected + style))
            }
        }.sortedByDescending { it.second + it.third }.take(8)
        if (entries.isEmpty()) return ""
        val rows = entries.joinToString("\n") { (pattern, accepted, challenged) ->
            "- $pattern: learner accepted $accepted; challenged or marked style $challenged"
        }
        return """
            LEARNER FEEDBACK MEMORY:
            $rows
            Treat this only as calibration evidence. For frequently challenged patterns, require
            especially clear verbatim evidence and prefer omission over another speculative flag.
            Never suppress a genuine meaning-changing error solely because of past feedback.
        """.trimIndent()
    }
}
