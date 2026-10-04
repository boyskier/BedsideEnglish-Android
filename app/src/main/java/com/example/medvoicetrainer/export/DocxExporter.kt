package com.example.medvoicetrainer.export

import com.example.medvoicetrainer.analysis.ScoreDomains
import com.example.medvoicetrainer.ui.formatSoapSubjective
import com.example.medvoicetrainer.ui.notElicitedRanges
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ported from app/export/docx_exporter.py — generate a .docx session report.
 *
 * The Python original used `python-docx`. There is no Kotlin equivalent on the classpath, and
 * adding one (e.g. Apache POI) means a new Gradle dependency this environment can't fetch/verify
 * against a real build. A .docx is simply a ZIP of well-documented, stable OOXML parts, so this
 * writes a minimal valid one directly ([Content_Types].xml + _rels/.rels + word/document.xml) —
 * no external dependency, and it opens correctly in Word/Google Docs/LibreOffice. Table borders
 * use a hand-rolled `<w:tbl>` rather than the "Table Grid" *named style* the Python source
 * referenced, since that style lives in styles.xml which this minimal writer omits.
 */
object DocxExporter {

    internal fun esc(s: String): String = s
        .filter { it >= ' ' || it == '\n' || it == '\t' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun run(text: String, bold: Boolean = false, sizeHalfPoints: Int? = null, colorHex: String? = null): String {
        val props = StringBuilder()
        if (bold) props.append("<w:b/>")
        if (sizeHalfPoints != null) props.append("<w:sz w:val=\"$sizeHalfPoints\"/>")
        if (colorHex != null) props.append("<w:color w:val=\"$colorHex\"/>")
        val rPr = if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else ""
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }

    private fun paragraph(runsXml: String, center: Boolean = false): String {
        val pPr = if (center) "<w:pPr><w:jc w:val=\"center\"/></w:pPr>" else ""
        return "<w:p>$pPr$runsXml</w:p>"
    }

    private fun paragraphText(text: String, bold: Boolean = false, sizeHalfPoints: Int? = null, center: Boolean = false): String {
        return paragraph(run(text, bold, sizeHalfPoints), center)
    }

    private fun heading(text: String, level: Int): String {
        val size = when (level) {
            0 -> 44
            2 -> 30
            else -> 26
        }
        return paragraphText(text, bold = true, sizeHalfPoints = size, center = level == 0)
    }

    private const val TBL_BORDERS =
        "<w:tblBorders>" +
            "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "</w:tblBorders>"

    /** rows[0] is treated as the header row (bold). */
    private fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val cols = rows[0].size
        val grid = StringBuilder("<w:tblGrid>")
        repeat(cols) { grid.append("<w:gridCol/>") }
        grid.append("</w:tblGrid>")

        val body = StringBuilder()
        for ((rowIndex, row) in rows.withIndex()) {
            body.append("<w:tr>")
            for (cell in row) {
                body.append("<w:tc><w:tcPr/>")
                body.append(paragraph(run(cell, bold = rowIndex == 0)))
                body.append("</w:tc>")
            }
            body.append("</w:tr>")
        }

        return "<w:tbl><w:tblPr>$TBL_BORDERS</w:tblPr>$grid$body</w:tbl>"
    }

    private fun coloredRun(text: String, colorHex: String) = run(text, colorHex = colorHex)

    /** Runs for [text] with every "Not elicited" marker in red — see SoapNoteText for the UI twin. */
    private fun notElicitedAwareRuns(text: String): String {
        val runs = StringBuilder()
        var cursor = 0
        for (range in notElicitedRanges(text)) {
            if (range.first < cursor) continue
            if (range.first > cursor) runs.append(run(text.substring(cursor, range.first)))
            runs.append(coloredRun(text.substring(range.first, range.last + 1), "CC0000"))
            cursor = range.last + 1
        }
        if (cursor < text.length) runs.append(run(text.substring(cursor)))
        return runs.toString()
    }

    private fun fmtScore(value: Double?, plusSign: Boolean = false): String {
        if (value == null) return "N/A"
        return if (plusSign) "%+.1f".format(value) else "%.1f".format(value)
    }

    private fun toDoubleOrNull(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /** Generate a .docx report from session data and save to outputPath. Returns outputPath. */
    fun generateReport(session: Map<String, Any?>, outputPath: String): String {
        val everyday = ScoreDomains.isEverydaySession(session)
        val body = StringBuilder()

        body.append(heading("Bedside English Session Report", 0))

        // Session info table
        body.append(heading("Session Information", 2))
        val createdAtRaw = session["created_at"]?.toString() ?: ""
        val dateStr = try {
            OffsetDateTime.parse(createdAtRaw).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            createdAtRaw
        }
        val duration = (session["duration_seconds"] as? Number)?.toInt() ?: 0
        val infoRows = listOf(
            listOf("Field", "Value"),
            listOf("Date", dateStr),
            listOf("Mode", (session["mode"]?.toString() ?: "").replaceFirstChar { it.uppercase() }),
            listOf("Case / Scenario", session["case_name"]?.toString() ?: ""),
            listOf("Duration", "${duration / 60}m ${duration % 60}s"),
            listOf("Voice Backend", (session["voice_backend"]?.toString() ?: "").replaceFirstChar { it.uppercase() }),
            listOf("Eval Template", session["eval_template"]?.toString() ?: "N/A")
        )
        body.append(table(infoRows))
        body.append(paragraphText(""))

        val rawResponse = session["raw_claude_response"]
        val analysis: JSONObject = when (rawResponse) {
            is JSONObject -> rawResponse
            is String -> try { JSONObject(rawResponse) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { JSONObject() }
            else -> JSONObject()
        }

        val scores = analysis.optJSONObject("overall_scores") ?: JSONObject()
        val selfScores = mutableMapOf(
            "grammar" to toDoubleOrNull(session["self_grammar"]),
            "medical_accuracy" to toDoubleOrNull(session["self_medical_accuracy"]),
            "clinical_reasoning" to toDoubleOrNull(session["self_clinical_reasoning"]),
            "professionalism" to toDoubleOrNull(session["self_professionalism"]),
            "communication_fluency" to toDoubleOrNull(session["self_fluency"]),
            "fluency" to toDoubleOrNull(session["self_fluency"])
        )
        val rawSelfScores = session["self_scores_json"] as? String
        if (!rawSelfScores.isNullOrEmpty()) {
            try {
                val saved = JSONObject(rawSelfScores)
                for (key in saved.keys()) {
                    val v = toDoubleOrNull(saved.opt(key))
                    if (v != null) selfScores[key] = v
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // ignore malformed self scores
            }
        }
        val delta = analysis.optJSONObject("self_assessment_delta") ?: JSONObject()

        if (scores.length() > 0) {
            body.append(heading("Score Summary", 2))
            val scoreRows = mutableListOf(listOf("Metric", "AI Score (/10)", "Self Score (/10)", "Delta"))
            for (metric in scores.keys()) {
                val score = toDoubleOrNull(scores.opt(metric))
                val selfScore = selfScores[metric]
                var deltaValue = toDoubleOrNull(delta.opt(metric))
                if (selfScore != null && score != null) {
                    deltaValue = Math.round((score - selfScore) * 10) / 10.0
                }
                scoreRows.add(
                    listOf(
                        metric.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                        fmtScore(score),
                        fmtScore(selfScore),
                        fmtScore(deltaValue, plusSign = true)
                    )
                )
            }
            body.append(table(scoreRows))
            body.append(paragraphText(""))
        }

        // Checklist
        val checklist = if (everyday) null else analysis.optJSONArray("checklist_results")
        if (checklist != null && checklist.length() > 0) {
            body.append(heading("Checklist", 2))
            val clRows = mutableListOf(listOf("Item", "Required", "Result", "Evidence"))
            for (i in 0 until checklist.length()) {
                val item = checklist.optJSONObject(i) ?: continue
                clRows.add(
                    listOf(
                        item.optString("item", ""),
                        if (item.optBoolean("required", false)) "Yes" else "No",
                        if (item.optBoolean("passed", false)) "✓ Pass" else "✗ Fail",
                        item.optString("evidence", "")
                    )
                )
            }
            body.append(table(clRows))
            body.append(paragraphText(""))
        }

        // SOAP Note
        val soap = if (everyday) null else analysis.optJSONObject("soap_note")
        if (soap != null) {
            body.append(heading("SOAP Note", 2))
            val sections = listOf("subjective" to "S — Subjective", "objective" to "O — Objective", "assessment" to "A — Assessment", "plan" to "P — Plan")
            for ((key, label) in sections) {
                body.append(heading(label, 3))
                if (key == "subjective") {
                    // The Subjective section carries the six history-taking subsections; the
                    // "Not elicited" marker is coloured here rather than in the model output so
                    // an unasked question stays visually distinct from a patient's denial.
                    body.append(paragraph(notElicitedAwareRuns(formatSoapSubjective(soap.opt(key)))))
                } else {
                    body.append(paragraphText(soap.optString(key, "")))
                }
            }

            var refSoapStr = session["reference_soap"] as? String
            if (refSoapStr.isNullOrEmpty()) {
                val rawCase = session["raw_case_json"] as? String
                if (!rawCase.isNullOrEmpty()) {
                    try {
                        val caseData = JSONObject(rawCase)
                        refSoapStr = (caseData.optJSONObject("reference_soap") ?: JSONObject()).toString()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            if (!refSoapStr.isNullOrEmpty()) {
                try {
                    val refSoap = JSONObject(refSoapStr)
                    body.append(heading("Reference SOAP (Model Answer)", 3))
                    val runs = StringBuilder()
                    for (key in listOf("subjective", "objective", "assessment", "plan")) {
                        runs.append(coloredRun("${key.uppercase()}: ${refSoap.optString(key, "")}\n", "606060"))
                    }
                    body.append(paragraph(runs.toString()))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    // ignore malformed reference SOAP
                }
            }
            body.append(paragraphText(""))
        }

        // Corrections
        val corrections = analysis.optJSONArray("corrections")
        if (corrections != null && corrections.length() > 0) {
            body.append(heading("Language Corrections", 2))
            for (i in 0 until corrections.length()) {
                val corr = corrections.optJSONObject(i) ?: continue
                val turnIndex = if (corr.has("turn_index") && !corr.isNull("turn_index")) corr.get("turn_index").toString() else "?"
                val runs = StringBuilder()
                runs.append(run("${i + 1}. [Turn $turnIndex] "))
                runs.append(coloredRun(corr.optString("original", ""), "CC0000"))
                runs.append(run(" → "))
                runs.append(coloredRun(corr.optString("corrected", ""), "008000"))
                body.append(paragraph(runs.toString()))
                body.append(paragraphText("   Explanation: ${corr.optString("explanation", "")}"))
            }
            body.append(paragraphText(""))
        }

        // Summary
        val summary = analysis.optString("summary_feedback", "")
        if (summary.isNotEmpty()) {
            body.append(heading("Summary Feedback", 2))
            body.append(paragraphText(summary))
            body.append(paragraphText(""))
        }

        // Anki cards preview (first 5)
        val ankiCards = analysis.optJSONArray("anki_cards")
        if (ankiCards != null && ankiCards.length() > 0) {
            body.append(heading("Anki Cards Preview (first 5)", 2))
            val ankiRows = mutableListOf(listOf("Front", "Back"))
            for (i in 0 until minOf(5, ankiCards.length())) {
                val card = ankiCards.optJSONObject(i) ?: continue
                ankiRows.add(listOf(card.optString("front", ""), card.optString("back", "")))
            }
            body.append(table(ankiRows))
        }

        writeDocx(body.toString(), outputPath)
        return outputPath
    }

    private fun writeDocx(bodyXml: String, outputPath: String) {
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$bodyXml<w:sectPr/></w:body>
</w:document>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val file = File(outputPath)
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun writeEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            writeEntry("[Content_Types].xml", contentTypes)
            writeEntry("_rels/.rels", rels)
            writeEntry("word/document.xml", document)
        }
    }

    fun suggestFilename(session: Map<String, Any?>): String {
        val createdAtRaw = session["created_at"]?.toString() ?: ""
        val dateStr = try {
            OffsetDateTime.parse(createdAtRaw).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            "unknown"
        }
        val mode = session["mode"]?.toString() ?: "session"
        var caseName = (session["case_name"]?.toString() ?: "case").replace(" ", "_")
        caseName = caseName.replace(Regex("[<>:\"/\\\\|?*]"), "-")
        return "${dateStr}_${mode}_${caseName}.docx"
    }
}
