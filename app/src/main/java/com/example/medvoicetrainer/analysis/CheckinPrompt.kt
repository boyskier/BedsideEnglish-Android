package com.example.medvoicetrainer.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ported from app/ui/checkin_prompt.py — decision/persistence logic for the confidence
 * micro check-in shown every N sessions. The tkinter dialog itself (widget layout) is not
 * ported; a Compose dialog calling into this object is still pending (see PORTING_STATUS.md).
 *
 * Privacy contract: rating is a 1/2/3 integer stored locally only. Telemetry emits ONLY
 * {rating: int} — never transcript, text, or PII. Testimonials are stored locally, never
 * auto-transmitted.
 */

data class CheckinRecord(val rating: Int, val date: String)
data class Testimonial(val text: String, val date: String)

object CheckinPrompt {

    const val CHECKIN_INTERVAL = 5 // show after every 5th completed session

    /**
     * Whether the check-in dialog should be shown now. R2-4: a scripted-demo patient isn't
     * meaningful confidence data, and asking during the demo can steal the modal grab from the
     * post-tour decision screen — skip entirely while on the "demo" voice backend.
     */
    fun shouldShowCheckin(voiceBackend: String, totalSessionsAnalyzed: Int, lastCheckinSession: Int): Boolean {
        if (voiceBackend == "demo") return false
        if (totalSessionsAnalyzed < CHECKIN_INTERVAL) return false
        return totalSessionsAnalyzed - lastCheckinSession >= CHECKIN_INTERVAL
    }

    fun shouldAskTestimonial(rating: Int): Boolean = rating >= 2

    fun parseCheckinHistory(json: String): List<CheckinRecord> {
        return try {
            val arr = JSONArray(json.ifBlank { "[]" })
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CheckinRecord(rating = o.optInt("rating", 0), date = o.optString("date", ""))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    fun appendRating(historyJson: String, rating: Int, todayIso: String): String {
        val history = (parseCheckinHistory(historyJson) + CheckinRecord(rating, todayIso)).takeLast(50)
        val arr = JSONArray()
        for (r in history) {
            arr.put(JSONObject().put("rating", r.rating).put("date", r.date))
        }
        return arr.toString()
    }

    fun parseTestimonials(json: String): List<Testimonial> {
        return try {
            val arr = JSONArray(json.ifBlank { "[]" })
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Testimonial(text = o.optString("text", ""), date = o.optString("date", ""))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** Store testimonial locally only — never transmitted automatically. */
    fun appendTestimonial(itemsJson: String, text: String, todayIso: String): String {
        val items = (parseTestimonials(itemsJson) + Testimonial(text, todayIso)).takeLast(20)
        val arr = JSONArray()
        for (t in items) {
            arr.put(JSONObject().put("text", t.text).put("date", t.date))
        }
        return arr.toString()
    }
}
