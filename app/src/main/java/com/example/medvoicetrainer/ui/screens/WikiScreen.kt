package com.example.medvoicetrainer.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Compose port of the Python desktop app's Help Wiki viewer (`app/ui/help_window.py`'s
 * `HelpWikiWindow`). Loads the bundled `USER_GUIDE_<LANG>.md` asset for the current app
 * language, parses it with [parseMarkdownGuide] (see `MarkdownGuideParser.kt`), and
 * renders it natively (headings, bold/italic/code spans, links, lists, blockquotes,
 * tables, code blocks, horizontal rules) with a table-of-contents panel, in-guide search
 * with next/previous navigation, and a manual guide-language override — no WebView.
 */

private val GUIDE_LANG_NAMES = mapOf(
    "en" to "English (en)",
    "ko" to "한국어 (ko)",
    "es" to "Español (es)",
    "zh" to "中文 (zh)",
    "ar" to "العربية (ar)",
    "hi" to "हिन्दी (hi)",
    "pt" to "Português (pt)",
    "tl" to "Tagalog (tl)",
    "fr" to "Français (fr)",
    "ja" to "日本語 (ja)",
    "de" to "Deutsch (de)",
    "id" to "Bahasa Indonesia (id)",
    "vi" to "Tiếng Việt (vi)",
    "ru" to "Русский (ru)"
)

/** Scans `assets/guides/` for `USER_GUIDE_<CODE>.md` files. Mirrors
 * `_find_available_guide_languages` in help_window.py. */
private fun findAvailableGuideLanguages(context: Context): List<String> {
    val codes = mutableSetOf<String>()
    try {
        context.assets.list("guides")?.forEach { name ->
            val match = Regex("^USER_GUIDE_([A-Za-z]+)\\.md$", RegexOption.IGNORE_CASE).find(name)
            if (match != null) {
                codes.add(match.groupValues[1].lowercase())
            }
        }
    } catch (e: IOException) {
        // Ignore: assets/guides missing entirely falls back to "en" below.
    }
    if (codes.isEmpty()) codes.add("en")
    return codes.sorted()
}

/**
 * Body accent colors for the rendered guide. The Markdown renderer used to hardcode the light
 * theme's ink values (a near-navy link blue, a dark red for `code`, a bright yellow search
 * highlight); on the app's dark theme those land as good-as-black text on a near-black page.
 * Each pair below is picked per theme so the guide stays readable either way.
 */
private data class GuidePalette(
    val link: Color,
    val code: Color,
    val searchHighlight: Color,
    val flash: Color,
)

@Composable
private fun rememberGuidePalette(): GuidePalette {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            GuidePalette(
                link = Color(0xFF93B4FF),
                code = Color(0xFFFCA5A5),
                searchHighlight = Color(0xFF6B5B12),
                flash = Color(0xFFF59E0B).copy(alpha = 0.28f),
            )
        } else {
            GuidePalette(
                link = Color(0xFF1D4ED8),
                code = Color(0xFFB91C1C),
                searchHighlight = Color(0xFFFEF08A),
                flash = Color(0xFFFEF08A).copy(alpha = 0.55f),
            )
        }
    }
}

/** Loads a guide's raw Markdown text, falling back to English if the requested language
 * isn't bundled. Mirrors `_resolve_guide_path` + `_load_and_render_guide`. */
private fun loadGuideText(context: Context, langCode: String): String {
    val candidates = linkedSetOf(
        "guides/USER_GUIDE_${langCode.uppercase()}.md",
        "guides/USER_GUIDE_EN.md"
    )
    for (path in candidates) {
        try {
            return context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            // Try the next candidate.
        }
    }
    return "# Error\n\nUser guide file not found."
}

@Composable
fun WikiScreen(viewModel: MainViewModel, onNavigateBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val t = LocalTranslate.current
    val uiLang by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()

    val palette = rememberGuidePalette()

    val availableLangs = remember { findAvailableGuideLanguages(context) }
    var manualLangOverride by remember { mutableStateOf<String?>(null) }
    val effectiveLang = manualLangOverride
        ?: uiLang.takeIf { availableLangs.contains(it) }
        ?: "en"

    // Reading the ~33 KB guide and parsing it into blocks both used to happen with the parse on
    // the main thread (`remember(guideText) { parseMarkdownGuide(...) }`), which janked the frame
    // the screen opened on. Both halves now run on a worker and only the finished block list is
    // handed back to Compose, so opening the guide costs one spinner instead of a stall.
    var blocks by remember { mutableStateOf<List<MdBlock>?>(null) }
    LaunchedEffect(effectiveLang) {
        blocks = null
        blocks = withContext(Dispatchers.IO) { parseMarkdownGuide(loadGuideText(context, effectiveLang)) }
    }
    val loadedBlocks = blocks ?: emptyList()
    val headings = remember(loadedBlocks) {
        loadedBlocks.withIndex()
            .filter { it.value is MdBlock.Heading }
            .map { (idx, block) -> idx to (block as MdBlock.Heading) }
    }

    var searchQuery by remember { mutableStateOf("") }
    // Scanning every block for the query used to run synchronously inside composition on every
    // keystroke — the other half of the "typing in the guide search stutters" report. Debounced
    // and moved to a background dispatcher.
    var matches by remember { mutableStateOf<List<SearchMatch>>(emptyList()) }
    LaunchedEffect(loadedBlocks, searchQuery) {
        if (searchQuery.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        matches = withContext(Dispatchers.Default) { findSearchMatches(loadedBlocks, searchQuery) }
    }
    // Per-block lookup so each rendered row can grab its own highlights in O(1) instead of the
    // whole list re-filtering `matches` (and rebuilding its AnnotatedString) for every item.
    val matchesByBlock = remember(matches) { matches.groupBy { it.blockIndex } }
    var currentMatchIdx by remember { mutableStateOf(-1) }
    LaunchedEffect(matches) {
        currentMatchIdx = if (matches.isNotEmpty()) 0 else -1
    }

    var showToc by remember { mutableStateOf(false) }
    var flashedBlockIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(flashedBlockIndex) {
        if (flashedBlockIndex >= 0) {
            delay(500)
            flashedBlockIndex = -1
        }
    }

    val listState = rememberLazyListState()

    fun jumpToBlock(index: Int) {
        if (index < 0) return
        scope.launch {
            if (reducedMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
        }
        flashedBlockIndex = index
    }

    fun openLink(url: String) {
        if (url.startsWith("#")) {
            val target = slugify(url.removePrefix("#"))
            val idx = resolveAnchorBlockIndex(loadedBlocks, target)
            if (idx != null) jumpToBlock(idx)
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // No app can handle the link; silently ignore, matching the Python
                // original's try/except around webbrowser.open().
            }
        }
    }

    // Two things this outer Surface fixes, both visible in dark mode:
    //  * the guide opens as a full-screen overlay outside the app Scaffold, so nothing was
    //    providing a background/LocalContentColor — body Text fell back to near-black ink on the
    //    dark page (the "글자가 이상함" report);
    //  * the activity is edge-to-edge, so without a safeDrawing inset the toolbar row rendered
    //    underneath the status bar and its title/icons were clipped.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- Search / toolbar row ---
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (onNavigateBack != null) {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = t("common.back"))
                                }
                            }
                            Text(
                                text = t("help_wiki.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showToc = !showToc }) {
                                Icon(imageVector = Icons.Default.MenuBook, contentDescription = t("help_wiki.contents"))
                            }
                            // Ported from app/ui/coach.py's how_it_works() — a re-openable
                            // "How it works" tour, previously not ported at all.
                            var showHowItWorks by remember { mutableStateOf(false) }
                            IconButton(onClick = { showHowItWorks = true }) {
                                Icon(imageVector = Icons.Default.Info, contentDescription = t("tour.title"))
                            }
                            if (showHowItWorks) {
                                HowItWorksDialog(onDismiss = { showHowItWorks = false })
                            }
                            GuideLanguageMenu(
                                availableLangs = availableLangs,
                                selectedLang = effectiveLang,
                                label = t("help_wiki.language"),
                                onSelect = { code -> manualLangOverride = code }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(t("help_wiki.search_placeholder")) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = t("common.close"))
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                enabled = matches.isNotEmpty(),
                                onClick = {
                                    if (matches.isEmpty()) return@IconButton
                                    currentMatchIdx = (currentMatchIdx - 1 + matches.size) % matches.size
                                    jumpToBlock(matches[currentMatchIdx].blockIndex)
                                }
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = t("help_wiki.prev"))
                            }
                            IconButton(
                                enabled = matches.isNotEmpty(),
                                onClick = {
                                    if (matches.isEmpty()) return@IconButton
                                    currentMatchIdx = (currentMatchIdx + 1) % matches.size
                                    jumpToBlock(matches[currentMatchIdx].blockIndex)
                                }
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = t("help_wiki.next"))
                            }
                        }

                        if (searchQuery.isNotEmpty()) {
                            Text(
                                text = if (matches.isEmpty()) {
                                    t("help_wiki.no_results")
                                } else {
                                    t("help_wiki.results_count")
                                        .replace("{current}", (currentMatchIdx + 1).toString())
                                        .replace("{total}", matches.size.toString())
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (matches.isEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                    }
                }

                // --- Guide content ---
                Box(modifier = Modifier.fillMaxSize()) {
                    if (blocks == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(t("help_wiki.loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        val activeMatch = matches.getOrNull(currentMatchIdx)
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(loadedBlocks, key = { idx, _ -> idx }) { idx, block ->
                                val blockMatches = matchesByBlock[idx].orEmpty()
                                val activeRange = if (activeMatch?.blockIndex == idx) {
                                    activeMatch.start until (activeMatch.start + activeMatch.length)
                                } else null
                                GuideBlockView(
                                    block = block,
                                    palette = palette,
                                    flashed = flashedBlockIndex == idx,
                                    highlights = remember(blockMatches) {
                                        blockMatches.map { it.start until (it.start + it.length) }
                                    },
                                    activeHighlight = activeRange,
                                    onLinkClick = ::openLink
                                )
                            }
                            item { Spacer(modifier = Modifier.height(48.dp)) }
                        }
                    }
                }
            }

            // --- Table of contents overlay panel ---
            // §14 "Motion": Compose doesn't read the system's "Remove animations" setting on its
            // own, so this slide-in becomes an instant cut when it's on (reducedMotion declared
            // above, shared with jumpToBlock's autoscroll).
            AnimatedVisibility(
                visible = showToc,
                enter = fadeIn(if (reducedMotion) snap() else tween(150)),
                exit = fadeOut(if (reducedMotion) snap() else tween(150)),
                modifier = Modifier.fillMaxSize().zIndex(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { showToc = false }
                )
            }
            AnimatedVisibility(
                visible = showToc,
                enter = slideInHorizontally(if (reducedMotion) snap() else tween(220)) { -it },
                exit = slideOutHorizontally(if (reducedMotion) snap() else tween(220)) { -it },
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(0.85f)
                    .zIndex(2f)
            ) {
                Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = t("help_wiki.contents"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showToc = false }) {
                                Icon(Icons.Default.Close, contentDescription = t("common.close"))
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(headings, key = { it.first }) { (blockIndex, heading) ->
                                TocRow(heading = heading) {
                                    jumpToBlock(blockIndex)
                                    showToc = false
                                }
                            }
                            item { Spacer(modifier = Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocRow(heading: MdBlock.Heading, onClick: () -> Unit) {
    val startPadding = (16 + (heading.level - 1) * 16).dp
    Text(
        text = heading.text,
        style = when (heading.level) {
            1 -> MaterialTheme.typography.titleSmall
            2 -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.bodySmall
        },
        fontWeight = if (heading.level == 1) FontWeight.Bold else FontWeight.Normal,
        color = if (heading.level == 1) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = startPadding, end = 16.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun GuideLanguageMenu(
    availableLangs: List<String>,
    selectedLang: String,
    label: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(GUIDE_LANG_NAMES[selectedLang] ?: selectedLang.uppercase())
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = label
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableLangs.forEach { code ->
                DropdownMenuItem(
                    text = { Text(GUIDE_LANG_NAMES[code] ?: code.uppercase()) },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    }
                )
            }
        }
    }
}

@Composable
private fun GuideBlockView(
    block: MdBlock,
    palette: GuidePalette,
    flashed: Boolean,
    highlights: List<IntRange>,
    activeHighlight: IntRange?,
    onLinkClick: (String) -> Unit
) {
    // §14 "Motion": the search-match flash is a color fade like any other animation — snap
    // straight to/from the target color under reduced motion instead of tweening.
    val reducedMotion = com.example.medvoicetrainer.ui.rememberReducedMotion()
    val flashTarget = if (flashed) palette.flash else Color.Transparent
    val flashBg by animateColorAsState(
        targetValue = flashTarget,
        animationSpec = if (reducedMotion) snap() else tween(if (flashed) 80 else 500),
        label = "flashHighlight"
    )
    val rowModifier = Modifier
        .fillMaxWidth()
        .background(flashBg)

    when (block) {
        is MdBlock.Heading -> {
            Column(modifier = rowModifier.padding(top = if (block.level == 1) 18.dp else 12.dp, bottom = 4.dp)) {
                Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                    // Level 2 used to take colorScheme.secondary, which on the dark theme is the
                    // deep navy PrimaryBlue — effectively invisible against the dark page. The
                    // size/weight step already carries the hierarchy, so both top levels share the
                    // one brand color that is contrast-checked for both themes.
                    color = when (block.level) {
                        1, 2 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (block.level == 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }
        }
        is MdBlock.Paragraph -> {
            if (block.spans.isNotEmpty()) {
                MarkdownInlineText(
                    spans = block.spans,
                    palette = palette,
                    highlights = highlights,
                    activeHighlight = activeHighlight,
                    baseStyle = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLinkClick,
                    modifier = rowModifier.padding(vertical = 2.dp)
                )
            }
        }
        is MdBlock.BulletItem -> {
            Row(
                modifier = rowModifier.padding(
                    start = 16.dp + block.indent.dp,
                    top = 2.dp,
                    bottom = 2.dp,
                    end = 8.dp
                )
            ) {
                Text(
                    "•  ",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                MarkdownInlineText(
                    spans = block.spans,
                    palette = palette,
                    highlights = highlights,
                    activeHighlight = activeHighlight,
                    baseStyle = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLinkClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MdBlock.NumberedItem -> {
            Row(
                modifier = rowModifier.padding(
                    start = 16.dp + block.indent.dp,
                    top = 2.dp,
                    bottom = 2.dp,
                    end = 8.dp
                )
            ) {
                Text(
                    "${block.number}.  ",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                MarkdownInlineText(
                    spans = block.spans,
                    palette = palette,
                    highlights = highlights,
                    activeHighlight = activeHighlight,
                    baseStyle = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLinkClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MdBlock.Quote -> {
            Row(
                modifier = rowModifier
                    .padding(vertical = 2.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(10.dp))
                MarkdownInlineText(
                    spans = block.spans,
                    palette = palette,
                    highlights = highlights,
                    activeHighlight = activeHighlight,
                    baseStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    onLinkClick = onLinkClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MdBlock.CodeBlock -> {
            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = rowModifier
                    .padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            )
        }
        MdBlock.HorizontalRule -> {
            HorizontalDivider(modifier = rowModifier.padding(vertical = 10.dp))
        }
        is MdBlock.Table -> {
            GuideTableView(
                block = block,
                palette = palette,
                onLinkClick = onLinkClick,
                modifier = rowModifier.padding(vertical = 8.dp)
            )
        }
        MdBlock.Blank -> {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun GuideTableView(
    block: MdBlock.Table,
    palette: GuidePalette,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        TableRowView(
            cells = block.headers,
            alignments = block.alignments,
            isHeader = true,
            backgroundColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onPrimary,
            linkColor = MaterialTheme.colorScheme.onPrimary,
            onLinkClick = onLinkClick
        )
        block.rows.forEachIndexed { rowIdx, row ->
            TableRowView(
                cells = row,
                alignments = block.alignments,
                isHeader = false,
                backgroundColor = if (rowIdx % 2 == 0) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                textColor = MaterialTheme.colorScheme.onSurface,
                linkColor = palette.link,
                onLinkClick = onLinkClick
            )
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<TableCell>,
    alignments: List<TableAlign>,
    isHeader: Boolean,
    backgroundColor: Color,
    textColor: Color,
    linkColor: Color,
    onLinkClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 1.dp)) {
        cells.forEachIndexed { colIdx, cell ->
            val align = alignments.getOrNull(colIdx) ?: TableAlign.LEFT
            val isLink = cell.linkUrl != null
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(backgroundColor)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .then(if (isLink) Modifier.clickable { onLinkClick(cell.linkUrl!!) } else Modifier),
                contentAlignment = when (align) {
                    TableAlign.LEFT -> Alignment.CenterStart
                    TableAlign.CENTER -> Alignment.Center
                    TableAlign.RIGHT -> Alignment.CenterEnd
                }
            ) {
                Text(
                    text = cell.text,
                    color = if (isLink) linkColor else textColor,
                    fontWeight = if (isHeader || cell.bold) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (isLink) TextDecoration.Underline else null,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Renders a list of inline [InlineSpan]s as a single [Text] using [AnnotatedString],
 * applying bold/italic/code/link styling plus search-highlight backgrounds, and
 * dispatching taps that land on a link span to [onLinkClick]. This replaces the tagged
 * `tk.Text` ranges + `tag_bind` click handlers used in help_window.py's
 * `_insert_inline_formatted`.
 */
@Composable
private fun MarkdownInlineText(
    spans: List<InlineSpan>,
    palette: GuidePalette,
    highlights: List<IntRange>,
    activeHighlight: IntRange?,
    baseStyle: TextStyle,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val linkColor = palette.link
    val codeColor = palette.code
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val highlightBg = palette.searchHighlight
    val activeBg = MaterialTheme.colorScheme.tertiaryContainer

    val annotated: AnnotatedString = remember(spans, highlights, activeHighlight, palette, codeBg, activeBg) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span.kind) {
                    SpanKind.LINK -> {
                        pushStringAnnotation(tag = "URL", annotation = span.url ?: "")
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(span.text)
                        }
                        pop()
                    }
                    SpanKind.CODE -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBg)
                    ) { append(span.text) }
                    SpanKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                    SpanKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                    SpanKind.BOLD_ITALIC -> withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    ) { append(span.text) }
                    SpanKind.NORMAL -> append(span.text)
                }
            }
            val total = spans.sumOf { it.text.length }
            highlights.forEach { range ->
                val start = range.first.coerceIn(0, total)
                val end = (range.last + 1).coerceIn(0, total)
                if (start < end) addStyle(SpanStyle(background = highlightBg), start, end)
            }
            activeHighlight?.let { range ->
                val start = range.first.coerceIn(0, total)
                val end = (range.last + 1).coerceIn(0, total)
                if (start < end) addStyle(SpanStyle(background = activeBg), start, end)
            }
        }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = baseStyle,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { offsetPosition ->
                val layout = layoutResult ?: return@detectTapGestures
                val charOffset = layout.getOffsetForPosition(offsetPosition)
                annotated.getStringAnnotations("URL", charOffset, charOffset)
                    .firstOrNull()
                    ?.let { onLinkClick(it.item) }
            }
        },
        onTextLayout = { layoutResult = it }
    )
}
