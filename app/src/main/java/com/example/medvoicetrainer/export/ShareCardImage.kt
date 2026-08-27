package com.example.medvoicetrainer.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.medvoicetrainer.analysis.ScoreDomains
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Ported from app/export/share_card_image.py — de-identified progress cards rendered as
 * shareable 1080x1080 PNG images. The Python original drew on a headless matplotlib Agg canvas;
 * this uses android.graphics.Canvas/Bitmap directly (no Compose dependency, so it can run off
 * the UI thread from a plain export/share action). Same privacy contract as [ShareReport]: only
 * counters and scores, no names, transcripts, or patient details.
 *
 * Coordinates below mirror the Python source's 0..1 axes fractions (origin bottom-left, y up);
 * [frac] converts a fraction to a pixel, flipping Y for Android's top-left/y-down Canvas.
 */
object ShareCardImage {

    private const val SIZE_PX = 1080

    // Mirrors app/brand.py PALETTE.
    private object P {
        val primary = Color.parseColor("#1e40af")
        val primaryDark = Color.parseColor("#1e3a8a")
        val primaryBright = Color.parseColor("#2563eb")
        val accent = Color.parseColor("#f59e0b")
        val onAccent = Color.parseColor("#1c1917")
        val success = Color.parseColor("#047857")
        val bg = Color.parseColor("#f9fafb")
        val surface = Color.parseColor("#ffffff")
        val text = Color.parseColor("#111827")
        val textMuted = Color.parseColor("#6b7280")
        val textSubtle = Color.parseColor("#9ca3af")
    }

    private fun px(frac: Float): Float = frac * SIZE_PX
    private fun pxY(frac: Float): Float = (1f - frac) * SIZE_PX

    private fun newCanvas(): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        return bitmap to canvas
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.textSize = size
        p.textAlign = align
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return p
    }

    /** Draw text vertically centered on [cy] (mirrors matplotlib's va="center"). */
    private fun drawVCentered(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun rounded(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, radiusFrac: Float = 0.02f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        val rect = RectF(px(x), pxY(y + h), px(x + w), pxY(y))
        canvas.drawRoundRect(rect, px(radiusFrac), px(radiusFrac), paint)
    }

    private fun background(canvas: Canvas, title: String, subtitle: String) {
        val bgPaint = Paint()
        bgPaint.color = P.primaryDark
        canvas.drawRect(0f, 0f, SIZE_PX.toFloat(), SIZE_PX.toFloat(), bgPaint)
        rounded(canvas, 0.05f, 0.05f, 0.90f, 0.90f, P.surface, 0.025f)

        canvas.drawText("Bedside English", px(0.10f), pxY(0.885f), textPaint(24f, P.primary, bold = true))
        canvas.drawText(title, px(0.10f), pxY(0.83f), textPaint(46f, P.text, bold = true))
        canvas.drawText(subtitle, px(0.10f), pxY(0.785f), textPaint(20f, P.textMuted))
    }

    /** dataviz stat-tile contract: value in ink (semibold), label muted, color never carries the number itself. */
    private fun statTile(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, label: String, accent: Boolean = false) {
        rounded(canvas, x, y, w, h, P.bg, 0.015f)
        val barColor = if (accent) P.accent else P.primaryBright
        val barPaint = Paint()
        barPaint.color = barColor
        canvas.drawRect(px(x + 0.015f), pxY(y + h - 0.006f), px(x + 0.065f), pxY(y + h - 0.012f), barPaint)

        drawVCentered(canvas, value, px(x + w / 2), pxY(y + h * 0.56f), textPaint(46f, P.text, bold = true, align = Paint.Align.CENTER))
        drawVCentered(canvas, label, px(x + w / 2), pxY(y + h * 0.22f), textPaint(18f, P.textMuted, align = Paint.Align.CENTER))
    }

    private fun footer(canvas: Canvas, note: String = "", context: String = "general") {
        if (note.isNotEmpty()) {
            canvas.drawText(note, px(0.10f), pxY(0.155f), textPaint(18f, P.success, bold = true))
        }
        val privacy = when (context) {
            "clinical" -> "No patient-identifying details or transcript included."
            "everyday" -> "No personal conversation details or transcript included."
            else -> "No transcript or identifying details included."
        }
        val product = when (context) {
            "clinical" -> "Free · private · AI clinical English practice"
            "everyday" -> "Free · private · AI real-life English practice"
            else -> "Free · private · AI speaking practice"
        }
        canvas.drawText(privacy, px(0.10f), pxY(0.115f), textPaint(15f, P.textSubtle))
        canvas.drawText(product, px(0.10f), pxY(0.085f), textPaint(17f, P.textMuted))
    }

    private fun fmt(value: Double?, suffix: String = ""): String {
        if (value == null) return "—"
        return if (value == Math.floor(value)) "${value.toInt()}$suffix" else "${"%.1f".format(value)}$suffix"
    }

    /** word-wrap matching Python's textwrap.fill(width=N): greedy fill by character count. */
    private fun wrapText(text: String, width: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (candidate.length > width && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return if (lines.isEmpty()) listOf("") else lines
    }

    /** Render the weekly confidence card. Takes the same inputs as ShareReport.buildWeeklyConfidenceCard. */
    fun renderWeeklyCard(
        sessionCount: Int,
        totalSpeakingMinutes: Double,
        avgWpm: Double?,
        streak: Int,
        avgFillerRate: Double?,
        mastered: Int,
        active: Int
    ): Bitmap {
        val (bitmap, canvas) = newCanvas()
        background(canvas, "My week of clinical English", "Week ending ${LocalDate.now()}")

        val tiles = listOf(
            Triple(fmt(sessionCount.toDouble()), "practice sessions", false),
            Triple(fmt(Math.round(totalSpeakingMinutes).toDouble()), "minutes speaking", false),
            Triple(fmt(avgWpm), "avg words / min", false),
            Triple(fmt(if (streak > 0) streak.toDouble() else null), "day streak", true)
        )
        val gx = 0.10f; val gy = 0.545f; val gw = 0.385f; val gh = 0.145f; val gap = 0.03f
        for ((i, tile) in tiles.withIndex()) {
            val (value, label, accent) = tile
            val col = i % 2; val row = i / 2
            statTile(canvas, gx + col * (gw + gap), gy - row * (gh + gap), gw, gh, value, label, accent)
        }

        val extra = mutableListOf<String>()
        if (avgFillerRate != null) extra.add("Hesitation (filler) rate: ${fmt(avgFillerRate, "%")}")
        if (mastered != 0) extra.add("Errors graduated: ${fmt(mastered.toDouble())}")
        if (active != 0) extra.add("Errors still training: ${fmt(active.toDouble())}")
        if (extra.isNotEmpty()) {
            canvas.drawText(extra.joinToString("   ·   "), px(0.10f), pxY(0.295f), textPaint(18f, P.textMuted))
        }

        val note = if (mastered != 0) "$mastered mistake${if (mastered != 1) "s" else ""} mastered this week!" else ""
        footer(canvas, note)
        return bitmap
    }

    /** Render a milestone-unlocked card. [label] is the already-localized milestone text. */
    fun renderMilestoneCard(label: String, sessions: Int?, minutes: Int?, streak: Int?, mastered: Int?): Bitmap {
        val (bitmap, canvas) = newCanvas()
        background(canvas, "Milestone unlocked", LocalDate.now().toString())

        rounded(canvas, 0.10f, 0.665f, 0.26f, 0.045f, P.accent, 0.012f)
        drawVCentered(canvas, "MILESTONE", px(0.23f), pxY(0.6875f), textPaint(18f, P.onAccent, bold = true, align = Paint.Align.CENTER))

        val wrapped = wrapText(label, 26)
        val labelPaint = textPaint(46f, P.primary, bold = true)
        var lineY = 0.60f
        for (line in wrapped) {
            canvas.drawText(line, px(0.10f), pxY(lineY), labelPaint)
            lineY -= 0.06f
        }

        val tiles = listOf(
            Triple(fmt(sessions?.toDouble()), "total sessions", false),
            Triple(fmt(minutes?.toDouble()), "minutes spoken", false),
            Triple(fmt(streak?.toDouble()), "day streak", true),
            Triple(fmt(mastered?.toDouble()), "mistakes mastered", false)
        )
        val gx = 0.10f; val gy = 0.245f; val gw = 0.185f; val gh = 0.13f; val gap = 0.02f
        for ((i, tile) in tiles.withIndex()) {
            val (value, tlabel, accent) = tile
            statTile(canvas, gx + i * (gw + gap), gy, gw, gh, value, tlabel, accent)
        }

        footer(canvas)
        return bitmap
    }

    private fun analysisOf(session: Map<String, Any?>): JSONObject {
        val raw = session["raw_claude_response"]
        return when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            else -> JSONObject()
        }
    }

    private fun avgScore(scores: JSONObject): Double? {
        val vals = mutableListOf<Double>()
        for (key in scores.keys()) {
            val v = scores.opt(key)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null) vals.add(d)
        }
        return if (vals.isNotEmpty()) Math.round(vals.sum() / vals.size * 10) / 10.0 else null
    }

    /** Render one session's de-identified result card. */
    fun renderSessionCard(session: Map<String, Any?>): Bitmap {
        val analysis = analysisOf(session)
        val scores = analysis.optJSONObject("overall_scores") ?: JSONObject()
        val avg = avgScore(scores)
        val fluency = analysis.optJSONObject("fluency_metrics") ?: JSONObject()
        val intel = analysis.optJSONObject("intelligibility") ?: JSONObject()
        val correctionsCount = analysis.optJSONArray("corrections")?.length() ?: 0

        val createdAtRaw = session["created_at"]?.toString() ?: ""
        val created = try {
            OffsetDateTime.parse(createdAtRaw).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            createdAtRaw.take(10).ifEmpty { LocalDate.now().toString() }
        }
        val mode = (session["mode"]?.toString() ?: "practice").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val (bitmap, canvas) = newCanvas()
        background(canvas, "$mode session", created)

        if (avg != null) {
            canvas.drawText("%.1f".format(avg), px(0.10f), pxY(0.63f), textPaint(118f, P.primary, bold = true))
            canvas.drawText("/ 10", px(0.335f), pxY(0.605f), textPaint(35f, P.textSubtle))
            canvas.drawText(
                "average practice score (AI-estimated feedback)",
                px(0.10f), pxY(0.545f), textPaint(18f, P.textMuted)
            )
        } else {
            canvas.drawText("Practice complete", px(0.10f), pxY(0.61f), textPaint(54f, P.primary, bold = true))
        }

        val wpm = if (fluency.has("wpm") && !fluency.isNull("wpm")) fluency.optDouble("wpm") else null
        val fillerDensity = if (fluency.has("filler_density") && !fluency.isNull("filler_density")) fluency.optDouble("filler_density") else null
        val grade = intel.optString("grade", "—").ifEmpty { "—" }

        val tiles = listOf(
            Triple(fmt(wpm), "words / min", false),
            Triple(fmt(fillerDensity, "%"), "filler rate", false),
            Triple(grade, "communication clarity", false),
            Triple(fmt(correctionsCount.toDouble()), "corrections saved", true)
        )
        val gx = 0.10f; val gy = 0.345f; val gw = 0.185f; val gh = 0.13f; val gap = 0.02f
        for ((i, tile) in tiles.withIndex()) {
            val (value, label, accent) = tile
            statTile(canvas, gx + i * (gw + gap), gy, gw, gh, value, label, accent)
        }

        footer(canvas, context = if (ScoreDomains.isEverydaySession(session)) "everyday" else "clinical")
        return bitmap
    }

    /** Save a rendered card to disk as PNG; returns the path. */
    fun saveBitmapAsPng(bitmap: Bitmap, path: String): String {
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return path
    }

    /** Subdirectory of [Context.getCacheDir] exposed to the FileProvider (see res/xml/file_paths.xml). */
    private const val SHARED_CARDS_DIR = "shared_cards"

    /**
     * Save a rendered card into the app's cache dir so it can be handed to another app via a
     * FileProvider content:// Uri (cacheDir is not directly readable by other apps). The file is
     * a regenerable export, so overwriting [name] on every call is fine — no cleanup needed.
     */
    fun saveBitmapForSharing(context: Context, bitmap: Bitmap, name: String = "milestone_card.png"): File {
        val dir = File(context.cacheDir, SHARED_CARDS_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
