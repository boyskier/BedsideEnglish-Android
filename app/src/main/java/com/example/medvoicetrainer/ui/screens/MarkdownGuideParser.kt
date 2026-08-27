package com.example.medvoicetrainer.ui.screens

/**
 * Pure-Kotlin Markdown parser for the bundled USER_GUIDE_<LANG>.md files.
 *
 * This is a line-oriented port of the hand-rolled parser in the original Python
 * desktop app (`app/ui/help_window.py`, see `_render_markdown` / `_insert_inline_formatted`
 * / `_slugify` / `_render_table_widget`). It intentionally supports the exact subset of
 * Markdown the original parser understood (headers #/##/###, bold/italic/bold-italic,
 * inline code, `[text](url)` links, blockquotes, bullet/numbered lists, tables with
 * alignment, fenced code blocks, and `---`/`***` horizontal rules) rather than being a
 * general-purpose CommonMark implementation.
 *
 * Deliberately contains no `android.*`/`org.json` references so it can be exercised by
 * plain local JVM unit tests (see PORTING_STATUS.md for why that matters in this repo).
 */

enum class SpanKind { NORMAL, BOLD, ITALIC, BOLD_ITALIC, CODE, LINK }

data class InlineSpan(
    val text: String,
    val kind: SpanKind = SpanKind.NORMAL,
    val url: String? = null
)

enum class TableAlign { LEFT, CENTER, RIGHT }

data class TableCell(
    val text: String,
    val bold: Boolean = false,
    val linkUrl: String? = null
)

sealed class MdBlock {
    data class Heading(val level: Int, val text: String, val slug: String) : MdBlock()
    data class Paragraph(val spans: List<InlineSpan>) : MdBlock()
    data class BulletItem(val spans: List<InlineSpan>, val indent: Int) : MdBlock()
    data class NumberedItem(val number: String, val spans: List<InlineSpan>, val indent: Int) : MdBlock()
    data class Quote(val spans: List<InlineSpan>) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Table(
        val headers: List<TableCell>,
        val alignments: List<TableAlign>,
        val rows: List<List<TableCell>>
    ) : MdBlock()
    data object Blank : MdBlock()
}

data class SearchMatch(val blockIndex: Int, val start: Int, val length: Int)

private val INLINE_REGEX = Regex(
    "(?<code>`[^`]+`)" +
        "|(?<boldItalic>\\*\\*\\*[^*]+\\*\\*\\*)" +
        "|(?<bold>\\*\\*[^*]+\\*\\*)" +
        "|(?<italic>\\*[^*]+\\*)" +
        "|(?<link>\\[[^\\]]+\\]\\([^)]+\\))"
)

private val LINK_REGEX = Regex("^\\[(?<txt>[^\\]]+)\\]\\((?<url>[^)]+)\\)$")

private val TABLE_SEPARATOR_REGEX = Regex("^\\|?\\s*(:?-+:?\\s*\\|?\\s*)+$")

/**
 * Parses inline formatting (bold, italic, bold-italic, inline code, links) within a
 * single line of text. Mirrors `_insert_inline_formatted` in help_window.py, including
 * its alternation priority order (code > bold-italic > bold > italic > link).
 */
fun parseInline(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    var lastIdx = 0
    for (match in INLINE_REGEX.findAll(text)) {
        val range = match.range
        if (range.first > lastIdx) {
            spans.add(InlineSpan(text.substring(lastIdx, range.first), SpanKind.NORMAL))
        }
        val raw = match.value
        when {
            match.groups["code"] != null ->
                spans.add(InlineSpan(raw.substring(1, raw.length - 1), SpanKind.CODE))
            match.groups["boldItalic"] != null ->
                spans.add(InlineSpan(raw.substring(3, raw.length - 3), SpanKind.BOLD_ITALIC))
            match.groups["bold"] != null ->
                spans.add(InlineSpan(raw.substring(2, raw.length - 2), SpanKind.BOLD))
            match.groups["italic"] != null ->
                spans.add(InlineSpan(raw.substring(1, raw.length - 1), SpanKind.ITALIC))
            match.groups["link"] != null -> {
                val linkMatch = LINK_REGEX.find(raw)
                if (linkMatch != null) {
                    spans.add(
                        InlineSpan(
                            linkMatch.groups["txt"]!!.value,
                            SpanKind.LINK,
                            linkMatch.groups["url"]!!.value
                        )
                    )
                } else {
                    spans.add(InlineSpan(raw, SpanKind.NORMAL))
                }
            }
        }
        lastIdx = range.last + 1
    }
    if (lastIdx < text.length) {
        spans.add(InlineSpan(text.substring(lastIdx), SpanKind.NORMAL))
    }
    return spans
}

/**
 * Standardizes heading text into an anchor slug matching the Markdown links used inside
 * USER_GUIDE_*.md (e.g. "1. API Key Setup" -> "1-api-key-setup"). Direct port of
 * `HelpWikiWindow._slugify`.
 */
fun slugify(text: String): String {
    val lower = text.lowercase()
    val cleaned = StringBuilder()
    for (ch in lower) {
        if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_') {
            cleaned.append(ch)
        } else if (ch == '—') { // em-dash
            cleaned.append('-')
        }
    }
    var slug = cleaned.toString().replace(' ', '-')
    while (slug.contains("--")) {
        slug = slug.replace("--", "-")
    }
    return slug.trim('-')
}

private fun splitTableRow(rowText: String): List<String> {
    var t = rowText.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|")) t = t.substring(0, t.length - 1)
    return t.split("|").map { it.trim() }
}

private fun parseTableCell(raw: String): TableCell {
    var text = raw.trim()
    var bold = false
    if (text.length >= 4 && text.startsWith("**") && text.endsWith("**")) {
        text = text.substring(2, text.length - 2).trim()
        bold = true
    }
    val linkMatch = LINK_REGEX.find(text)
    return if (linkMatch != null) {
        TableCell(linkMatch.groups["txt"]!!.value, bold = false, linkUrl = linkMatch.groups["url"]!!.value)
    } else {
        TableCell(text, bold = bold)
    }
}

private fun parseTable(tableLines: List<String>): MdBlock.Table {
    val headerCells = splitTableRow(tableLines[0])
    val alignCells = if (tableLines.size > 1) splitTableRow(tableLines[1]) else emptyList()
    val numCols = headerCells.size

    val alignments = alignCells.map { cell ->
        when {
            cell.startsWith(":") && cell.endsWith(":") -> TableAlign.CENTER
            cell.endsWith(":") -> TableAlign.RIGHT
            else -> TableAlign.LEFT
        }
    }

    val headers = headerCells.map { parseTableCell(it) }
    val rows = tableLines.drop(2).map { rowLine ->
        val cells = splitTableRow(rowLine).toMutableList()
        while (cells.size < numCols) cells.add("")
        val trimmed = if (cells.size > numCols) cells.subList(0, numCols) else cells
        trimmed.map { parseTableCell(it) }
    }
    return MdBlock.Table(headers, alignments, rows)
}

/**
 * Splits text into lines the way Python's `str.splitlines()` does: unlike Kotlin's
 * `String.split("\n")`, a trailing newline does not produce a phantom empty trailing
 * line. E.g. `"a\nb\n"` -> `["a", "b"]`, not `["a", "b", ""]`.
 */
private fun splitIntoLines(text: String): List<String> {
    val raw = text.split("\n").map { it.removeSuffix("\r") }
    return if (raw.isNotEmpty() && raw.last().isEmpty()) raw.dropLast(1) else raw
}

/**
 * Parses a whole Markdown guide document into a flat list of [MdBlock]s. Direct port of
 * the line-scanning loop in `HelpWikiWindow._render_markdown`.
 */
fun parseMarkdownGuide(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = splitIntoLines(markdown)
    val n = lines.size
    var i = 0
    var inCodeBlock = false
    val codeBuffer = StringBuilder()

    while (i < n) {
        val line = lines[i]
        val trimmedLine = line.trim()

        // Fenced code block toggle.
        if (trimmedLine.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MdBlock.CodeBlock(codeBuffer.toString()))
                codeBuffer.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            i++
            continue
        }
        if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
            i++
            continue
        }

        // Markdown tables: a line containing "|" whose next line is a separator row.
        if (line.contains("|")) {
            var isTable = false
            if (i + 1 < n && lines[i + 1].contains("|")) {
                val sepLine = lines[i + 1].trim()
                if (TABLE_SEPARATOR_REGEX.matches(sepLine)) {
                    isTable = true
                }
            }
            if (isTable) {
                val tableLines = mutableListOf<String>()
                while (i < n && lines[i].contains("|")) {
                    tableLines.add(lines[i])
                    i++
                }
                blocks.add(parseTable(tableLines))
                continue
            }
        }

        when {
            line.startsWith("# ") -> {
                val title = line.substring(2).trim()
                blocks.add(MdBlock.Heading(1, title, slugify(title)))
            }
            line.startsWith("## ") -> {
                val title = line.substring(3).trim()
                blocks.add(MdBlock.Heading(2, title, slugify(title)))
            }
            line.startsWith("### ") -> {
                val title = line.substring(4).trim()
                blocks.add(MdBlock.Heading(3, title, slugify(title)))
            }
            line.startsWith("> ") -> {
                val content = line.substring(2).trim()
                blocks.add(MdBlock.Quote(parseInline(content)))
            }
            trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                val indent = line.length - line.trimStart().length
                val content = line.trimStart().substring(2)
                blocks.add(MdBlock.BulletItem(parseInline(content), indent))
            }
            trimmedLine.isNotEmpty() && trimmedLine[0].isDigit() && trimmedLine.take(4).contains(". ") -> {
                val indent = line.length - line.trimStart().length
                val stripped = line.trimStart()
                val dotIdx = stripped.indexOf(". ")
                val num = stripped.substring(0, dotIdx)
                val content = stripped.substring(dotIdx + 2)
                blocks.add(MdBlock.NumberedItem(num, parseInline(content), indent))
            }
            trimmedLine == "---" || trimmedLine == "***" -> {
                blocks.add(MdBlock.HorizontalRule)
            }
            trimmedLine.isEmpty() -> {
                blocks.add(MdBlock.Blank)
            }
            else -> {
                blocks.add(MdBlock.Paragraph(parseInline(line)))
            }
        }
        i++
    }

    // Unterminated fenced code block at EOF: flush what we have rather than dropping it.
    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        blocks.add(MdBlock.CodeBlock(codeBuffer.toString()))
    }

    return blocks
}

/** Flattened plain-text content of a block, used for search. Table cells are intentionally
 * excluded — the Python original renders tables as embedded Tk widgets that its
 * `tk.Text.search` call cannot see either, so this mirrors that behavior rather than
 * being a regression. */
fun blockSearchText(block: MdBlock): String = when (block) {
    is MdBlock.Heading -> block.text
    is MdBlock.Paragraph -> block.spans.joinToString("") { it.text }
    is MdBlock.BulletItem -> block.spans.joinToString("") { it.text }
    is MdBlock.NumberedItem -> block.spans.joinToString("") { it.text }
    is MdBlock.Quote -> block.spans.joinToString("") { it.text }
    is MdBlock.CodeBlock -> block.code
    MdBlock.HorizontalRule, MdBlock.Blank, is MdBlock.Table -> ""
}

/**
 * Finds every case-insensitive occurrence of [query] across all blocks, in document
 * order. Mirrors `_on_search_changed`'s use of `tk.Text.search` walking forward from the
 * end of the previous match.
 */
fun findSearchMatches(blocks: List<MdBlock>, query: String): List<SearchMatch> {
    if (query.isBlank()) return emptyList()
    val q = query.lowercase()
    val results = mutableListOf<SearchMatch>()
    blocks.forEachIndexed { idx, block ->
        val text = blockSearchText(block)
        if (text.isEmpty()) return@forEachIndexed
        val lower = text.lowercase()
        var start = 0
        while (start <= lower.length - q.length) {
            val pos = lower.indexOf(q, start)
            if (pos < 0) break
            results.add(SearchMatch(idx, pos, q.length))
            start = pos + q.length
        }
    }
    return results
}

/**
 * Resolves an internal `#anchor` link target to the index of the matching heading block.
 * Mirrors `_on_link_click`'s exact-then-fuzzy slug lookup.
 */
fun resolveAnchorBlockIndex(blocks: List<MdBlock>, targetSlug: String): Int? {
    val slug = targetSlug.trim()
    val headings = blocks.withIndex().filter { it.value is MdBlock.Heading }
    val exact = headings.lastOrNull { (it.value as MdBlock.Heading).slug == slug }
    if (exact != null) return exact.index
    val fuzzy = headings.firstOrNull {
        val hSlug = (it.value as MdBlock.Heading).slug
        slug.contains(hSlug) || hSlug.contains(slug)
    }
    return fuzzy?.index
}
