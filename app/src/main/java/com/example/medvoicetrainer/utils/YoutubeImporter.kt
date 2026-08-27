package com.example.medvoicetrainer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ported from app/utils/youtube.py — fetches a public video's caption track and flattens it to
 * plain text for Teach-back's "paste a lecture" flow. The Python original used the
 * youtube-transcript-api package; there is no Kotlin equivalent, so this replicates its
 * underlying technique directly: read the caption track list embedded in the watch page, then
 * fetch and flatten the chosen track's timedtext XML.
 */
object YoutubeImporter {

    private val ID_PATTERNS = listOf(
        Regex("(?:v=)([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("shorts/([A-Za-z0-9_-]{11})"),
        Regex("embed/([A-Za-z0-9_-]{11})"),
        Regex("live/([A-Za-z0-9_-]{11})")
    )
    private val RAW_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        try {
            trimmed = java.net.URLDecoder.decode(trimmed, "UTF-8")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // keep trimmed
        }
        for (pattern in ID_PATTERNS) {
            pattern.find(trimmed)?.let { return it.groupValues[1] }
        }
        if (RAW_ID_PATTERN.matches(trimmed)) return trimmed
        return null
    }

    private data class CaptionTrack(val baseUrl: String, val languageCode: String, val isGenerated: Boolean)

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MedVoiceTrainer")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} fetching $urlString")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Extract the `"captionTracks":[...]` JSON array embedded in the watch page HTML, if present. */
    private fun extractCaptionTracksJson(html: String): String? {
        val marker = "\"captionTracks\":["
        val start = html.indexOf(marker)
        if (start == -1) return null
        val arrayStart = start + marker.length - 1 // index of the opening '['
        var depth = 0
        var i = arrayStart
        while (i < html.length) {
            when (html[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return html.substring(arrayStart, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun listCaptionTracks(videoId: String): List<CaptionTrack> {
        val html = httpGet("https://www.youtube.com/watch?v=$videoId")
        val json = extractCaptionTracksJson(html) ?: return emptyList()
        val arr = JSONArray(json)
        val tracks = mutableListOf<CaptionTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val baseUrl = obj.optString("baseUrl", "")
            if (baseUrl.isEmpty()) continue
            tracks.add(
                CaptionTrack(
                    baseUrl = baseUrl.replace("\\u0026", "&"),
                    languageCode = obj.optString("languageCode", ""),
                    isGenerated = obj.optString("kind", "") == "asr"
                )
            )
        }
        return tracks
    }

    private fun flattenTimedText(xml: String): String {
        // <text start="..." dur="...">escaped caption text</text>
        val texts = Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val parts = texts.map { unescapeHtml(it.groupValues[1]).replace('\n', ' ').trim() }.filter { it.isNotEmpty() }
        return parts.joinToString(" ")
    }

    private fun unescapeHtml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    /**
     * Fetch and flatten a transcript to plain text.
     * @param langMode "ko", "en", or "auto" (manual captions preferred over auto-generated;
     *   among candidates, English is preferred, then Korean, then whatever is first).
     */
    suspend fun fetchTranscript(videoId: String, langMode: String = "auto"): String = withContext(Dispatchers.IO) {
        val tracks = listCaptionTracks(videoId)
        if (tracks.isEmpty()) {
            throw Exception("No transcript found for video $videoId")
        }

        val chosen = when (langMode) {
            "ko" -> tracks.firstOrNull { it.languageCode == "ko" }
                ?: throw Exception("No Korean transcript found for video $videoId")
            "en" -> tracks.firstOrNull { it.languageCode == "en" }
                ?: throw Exception("No English transcript found for video $videoId")
            else -> {
                val manual = tracks.filter { !it.isGenerated }
                val generated = tracks.filter { it.isGenerated }
                val candidates = manual + generated
                candidates.firstOrNull { it.languageCode == "en" }
                    ?: candidates.firstOrNull { it.languageCode == "ko" }
                    ?: candidates.firstOrNull()
                    ?: throw Exception("No transcript found for video $videoId")
            }
        }

        val xml = httpGet(chosen.baseUrl)
        flattenTimedText(xml)
    }
}
