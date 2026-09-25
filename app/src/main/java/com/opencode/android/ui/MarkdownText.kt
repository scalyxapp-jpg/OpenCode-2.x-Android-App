package com.opencode.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration



import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.ui.theme.codeFont
import com.opencode.android.ui.theme.spacing

/**
 * Lightweight Markdown renderer mirroring the web's message formatting.
 *
 * Supported (GFM, same set the web renderer accepts): headings h1-h6,
 * bullet/numbered/nested lists, task lists, fenced code blocks, blockquotes,
 * tables with alignment, horizontal rules, and inline bold / italic /
 * bold-italic / strikethrough / code / links.
 *
 * Links are real [LinkAnnotation]s, so tapping one opens the browser — they used
 * to be coloured text with the URL thrown away. Bare URLs are auto-linked too,
 * which is the form chat answers actually use.
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    // Never parse/highlight an enormous message. A single giant paste, log dump
    // or verbose tool output would otherwise build a huge block list and
    // AnnotatedString on every composition — a heap spike on top of the
    // streaming path. Plain Text still shows the full content.
    if (text.length > MARKDOWN_MAX_CHARS) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = modifier,
        )
        return
    }
    val blocks = remember(text) { parseMarkdown(text) }
    // Inline styles come from the theme (no hardcoded colours).
    val linkColor = Color(0xFF00BFFF)  // explizit sichtbar, theme-unabhängig
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineStyle = InlineStyle(
        link = linkColor,
        code = codeBackground,
        codeText = MaterialTheme.colorScheme.onSurface,
        codeFont = codeFont(),
    )
    // Picks the light or dark syntax palette, matching the surface the code
    // block sits on.
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = rememberInline(block.text, inlineStyle),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        4 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                    color = color,
                )

                is MdBlock.Bullet -> ListRow(
                    marker = "•",
                    text = block.text,
                    indentLevel = block.level,
                    style = inlineStyle,
                    color = color,
                )

                is MdBlock.Numbered -> ListRow(
                    marker = "${block.number}.",
                    text = block.text,
                    indentLevel = block.level,
                    style = inlineStyle,
                    color = color,
                )

                is MdBlock.Task -> ListRow(
                    marker = if (block.checked) "☑" else "☐",
                    text = block.text,
                    indentLevel = block.level,
                    style = inlineStyle,
                    color = color,
                    // A completed task reads as done, like the web's checked item.
                    strikethrough = block.checked,
                )

                is MdBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    // Wine-red rule: the quote bar is one of the few places the
                    // accent can carry structure without shouting.
                    Column(
                        modifier = Modifier
                            .width(3.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.extraSmall,
                            ),
                    ) { }
                    Text(
                        text = rememberInline(block.text, inlineStyle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Code -> {
                    // Syntax colours are theme-matched: the web highlights with
                    // Shiki (github-light / github-dark) and these palettes are
                    // taken from that theme, so a snippet reads the same here.
                    val palette = remember(isLightTheme) { codePalette(isLightTheme) }
                    val highlighted = remember(block.code, block.lang, palette) {
                        highlightCode(block.code, block.lang, palette)
                    }
                    // TUI-style line gutter: every code line gets its number
                    // on the left. The gutter stays fixed while long lines
                    // scroll horizontally underneath.
                    val codeLines = remember(block.code) { block.code.split('\n') }
                    val gutter = remember(codeLines) {
                        codeLines.indices.joinToString("\n") { "${it + 1}" }
                    }
                    val gutterWidth = remember(codeLines) {
                        (codeLines.size.toString().length * 8 + 12).dp
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.small,
                            )
                            .padding(MaterialTheme.spacing.small),
                    ) {
                        val context = LocalContext.current
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = block.lang.ifBlank { "code" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("code", block.code)
                                    )
                                    toast(context, context.getString(R.string.copied))
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copied),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        // Long lines scroll instead of wrapping, so indentation
                        // and alignment inside the snippet survive.
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = gutter,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = codeFont(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.End,
                                softWrap = false,
                                maxLines = codeLines.size.coerceAtLeast(1),
                                modifier = Modifier.width(gutterWidth),
                            )
                            Text(
                                text = highlighted,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = codeFont(),
                                softWrap = false,
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = MaterialTheme.spacing.small),
                            )
                        }
                    }
                }

                is MdBlock.Table -> Column(modifier = Modifier.fillMaxWidth()) {
                    block.rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == 0) {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                        ) {
                            row.forEachIndexed { cellIndex, cell ->
                                Text(
                                    text = rememberInline(cell, inlineStyle),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = color,
                                    textAlign = when (block.aligns.getOrNull(cellIndex)) {
                                        TableAlign.Center -> TextAlign.Center
                                        TableAlign.End -> TextAlign.End
                                        else -> TextAlign.Start
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = MaterialTheme.spacing.extraSmall, vertical = MaterialTheme.spacing.extraSmall),
                                )
                            }
                        }
                    }
                }

                // A real divider instead of a run of box-drawing characters:
                // it stretches to the bubble width and uses the theme outline.
                MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                is MdBlock.Paragraph -> Text(
                    text = rememberInline(block.text, inlineStyle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
        }
    }
}

/** Above this length the markdown parser/highlighter is skipped (plain text). */
private const val MARKDOWN_MAX_CHARS = 40_000

/** Theme colours the inline pass needs; bundled so `remember` keys stay simple. */
internal data class InlineStyle(
    val link: Color,
    val code: Color,
    val codeText: Color,
    val codeFont: FontFamily = FontFamily.Monospace,
)

@Composable
private fun ListRow(
    marker: String,
    text: String,
    indentLevel: Int,
    style: InlineStyle,
    color: Color,
    strikethrough: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indentLevel * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        // The marker sits one step back in the hierarchy from the item text.
        val markerColor = if (color == Color.Unspecified) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            color
        }
        Text(marker, color = markerColor, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = rememberInline(text, style, strikethrough),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

// Compiled once (Regex("…") in a hot loop recompiles on every call).
private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val RE_NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val RE_TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
// A separator cell is one or more dashes with optional alignment colons
// (GFM allows `:-:`, not just `:---:`).
private val RE_TABLE_SEP = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")
private val RE_BLOCKQUOTE = Regex("^\\s*>\\s?(.*)$")
private val RE_FENCE = Regex("^\\s*```\\s*(.*)$")

/**
 * Bare URLs and www hostnames. Trailing punctuation is trimmed afterwards, so
 * "see https://x.dev." links the URL and keeps the full stop outside.
 */
private val RE_AUTOLINK = Regex("""(https?://[^\s<>\[\]"']+|www\.[^\s<>\[\]"']+)""")

internal enum class TableAlign { Start, Center, End }

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val level: Int) : MdBlock
    data class Numbered(val number: String, val text: String, val level: Int) : MdBlock
    data class Task(val text: String, val checked: Boolean, val level: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val rows: List<List<String>>, val aligns: List<TableAlign>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Two spaces (or one tab) of indentation per nesting level, as in CommonMark. */
private fun indentLevel(prefix: String): Int {
    var spaces = 0
    for (ch in prefix) spaces += if (ch == '\t') 4 else 1
    return (spaces / 2).coerceAtMost(3)
}

internal fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val fence = RE_FENCE.find(line)
        when {
            // Fenced code block
            fence != null -> {
                flushParagraph()
                val lang = fence.groupValues[1].trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && RE_FENCE.find(lines[i]) == null) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(lang, code.toString()))
                i++ // skip closing fence
            }
            // Horizontal rule
            line.trim() in setOf("---", "***", "___") -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
                i++
            }
            // Heading
            RE_HEADING.find(line) != null -> {
                flushParagraph()
                val m = RE_HEADING.find(line) ?: continue
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
                i++
            }
            // Table (header row + separator row)
            line.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(RE_TABLE_SEP) -> {
                flushParagraph()
                val rows = mutableListOf<List<String>>()
                rows.add(splitRow(line))
                val aligns = parseAligns(lines[i + 1])
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.Table(rows, aligns))
            }
            // Task list item (must be checked before the plain bullet rule)
            RE_TASK.find(line) != null -> {
                flushParagraph()
                val m = RE_TASK.find(line) ?: continue
                blocks.add(
                    MdBlock.Task(
                        text = m.groupValues[3],
                        checked = m.groupValues[2].equals("x", ignoreCase = true),
                        level = indentLevel(m.groupValues[1]),
                    ),
                )
                i++
            }
            // Bullet list
            RE_BULLET.find(line) != null -> {
                flushParagraph()
                val m = RE_BULLET.find(line) ?: continue
                blocks.add(MdBlock.Bullet(m.groupValues[2], indentLevel(m.groupValues[1])))
                i++
            }
            // Numbered list
            RE_NUMBERED.find(line) != null -> {
                flushParagraph()
                val m = RE_NUMBERED.find(line) ?: continue
                blocks.add(
                    MdBlock.Numbered(
                        number = m.groupValues[2],
                        text = m.groupValues[3],
                        level = indentLevel(m.groupValues[1]),
                    ),
                )
                i++
            }
            // Blockquote — consecutive ">" lines form one quote
            RE_BLOCKQUOTE.find(line) != null -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && RE_BLOCKQUOTE.find(lines[i]) != null) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(RE_BLOCKQUOTE.find(lines[i])?.groupValues?.get(1) ?: "")
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
            }
            // Blank line
            line.isBlank() -> {
                flushParagraph()
                i++
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/** Column alignment from the `:---`, `:---:`, `---:` separator cells. */
internal fun parseAligns(separator: String): List<TableAlign> =
    separator.trim().trim('|').split('|').map { cell ->
        val c = cell.trim()
        when {
            c.startsWith(":") && c.endsWith(":") -> TableAlign.Center
            c.endsWith(":") -> TableAlign.End
            else -> TableAlign.Start
        }
    }

/**
 * Finds a bare URL starting at [index], or null. Trailing sentence punctuation
 * and an unbalanced closing paren are excluded so the link is the URL only.
 */
internal fun autolinkAt(text: String, index: Int): IntRange? {
    val m = RE_AUTOLINK.matchAt(text, index) ?: return null
    var end = m.range.last
    while (end > m.range.first && text[end] in ".,;:!?") end--
    // Drop a ')' that closes nothing inside the URL (e.g. "(see https://x.dev)").
    if (end > m.range.first && text[end] == ')') {
        val inner = text.substring(m.range.first, end + 1)
        if (inner.count { it == '(' } < inner.count { it == ')' }) end--
    }
    return if (end < m.range.first) null else m.range.first..end
}

// Memoised inline parsing: building the AnnotatedString runs a scan, so without
// remember() it re-runs on every recomposition of a message bubble.
@Composable
private fun rememberInline(
    text: String,
    style: InlineStyle,
    strikethrough: Boolean = false,
): AnnotatedString = remember(text, style, strikethrough) {
    inline(text, style, strikethrough)
}

private val RE_CODE_SPAN = Regex("`([^`]+)`")
private val RE_BOLD_ITALIC = Regex("\\*\\*\\*([^*]+)\\*\\*\\*")
private val RE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
// Word-boundary guarded so snake_case identifiers are left alone.
private val RE_BOLD_UNDER = Regex("(?<![\\w_])__([^_]+)__(?![\\w_])")
private val RE_STRIKE = Regex("~~([^~]+)~~")
private val RE_ITALIC_STAR = Regex("\\*([^*]+)\\*")
private val RE_ITALIC_UNDER = Regex("(?<![\\w_])_([^_]+)_(?![\\w_])")
private val RE_MD_LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")

/**
 * Inline pass. Recursive: the first marker found is consumed and its content is
 * parsed again with the accumulated style, so `**bold with `code`**` composes
 * instead of being flattened (the old flat regex could not nest).
 */
internal fun inline(
    text: String,
    style: InlineStyle,
    strikethrough: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    val root = if (strikethrough) {
        SpanStyle(textDecoration = TextDecoration.LineThrough)
    } else {
        SpanStyle()
    }

    // Declared before `emit` because Kotlin local functions cannot be used
    // before their declaration.
    fun emitPlain(plain: String, base: SpanStyle) {
        var i = 0
        while (i < plain.length) {
            // Search FORWARD for the next URL: checking only the current index
            // meant any text before a bare URL made the whole run plain.
            val m = RE_AUTOLINK.find(plain, i)
            if (m == null) {
                withStyle(base) { append(plain.substring(i)) }
                return
            }
            val range = autolinkAt(plain, m.range.first)
            if (range == null) {
                // Defensive: never spin — emit the raw match and advance.
                if (m.range.first > i) {
                    withStyle(base) { append(plain.substring(i, m.range.first)) }
                }
                withStyle(base) { append(plain.substring(m.range.first, m.range.last + 1)) }
                i = m.range.last + 1
            } else {
                if (range.first > i) withStyle(base) { append(plain.substring(i, range.first)) }
                val url = plain.substring(range.first, range.last + 1)
                val href = if (url.startsWith("http")) url else "https://$url"
                withLink(LinkAnnotation.Url(href, linkStyles(style))) { append(url) }
                i = range.last + 1
            }
        }
    }

    fun emit(source: String, base: SpanStyle) {
        var i = 0
        while (i < source.length) {
            val rest = source.substring(i)
            val match = when {
                rest.startsWith("`") -> RE_CODE_SPAN.find(source, i)
                rest.startsWith("***") -> RE_BOLD_ITALIC.find(source, i)
                rest.startsWith("**") -> RE_BOLD.find(source, i)
                rest.startsWith("__") -> RE_BOLD_UNDER.find(source, i)
                rest.startsWith("~~") -> RE_STRIKE.find(source, i)
                rest.startsWith("*") -> RE_ITALIC_STAR.find(source, i)
                rest.startsWith("_") -> RE_ITALIC_UNDER.find(source, i)
                rest.startsWith("[") -> RE_MD_LINK.find(source, i)
                else -> null
            }
            if (match == null) {
                val next = nextMarkerIndex(source, i)
                if (next <= i) {
                    // The current character is a marker char with no valid pair
                    // (a lone '*', '_', '~' or '['). Emit it literally and move
                    // on — not advancing here spun this loop forever and ANR'd
                    // the app on any message containing a stray marker.
                    emitPlain(source.substring(i, i + 1), base)
                    i++
                } else {
                    emitPlain(source.substring(i, next), base)
                    i = next
                }
                continue
            }
            if (match.range.first > i) {
                emitPlain(source.substring(i, match.range.first), base)
            }
            val inner = match.groupValues[1]
            when {
                match.value.startsWith("`") -> withStyle(
                    base + SpanStyle(
                        fontFamily = style.codeFont,
                        background = style.code,
                        color = style.codeText,
                    ),
                ) { append(inner) }
                match.value.startsWith("***") -> emit(
                    inner,
                    base + SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                )
                match.value.startsWith("**") || match.value.startsWith("__") ->
                    emit(inner, base + SpanStyle(fontWeight = FontWeight.Bold))
                match.value.startsWith("~~") -> emit(
                    inner,
                    base + SpanStyle(textDecoration = TextDecoration.LineThrough),
                )
                match.value.startsWith("*") || match.value.startsWith("_") ->
                    emit(inner, base + SpanStyle(fontStyle = FontStyle.Italic))
                else -> {
                    // [label](url)
                    val url = match.groupValues[2]
                    withLink(LinkAnnotation.Url(url, linkStyles(style))) {
                        emit(inner, base + SpanStyle(color = style.link))
                    }
                }
            }
            i = match.range.last + 1
        }
    }

    emit(text, root)
}

/** Underline + theme link colour, the treatment the web uses for anchors. */
private fun linkStyles(style: InlineStyle) = TextLinkStyles(
    style = SpanStyle(color = style.link, textDecoration = TextDecoration.Underline),
)

/**
 * Index of the next inline marker at or after [from], or the string length.
 * URLs are skipped wholesale so an underscore or asterisk inside them is not
 * mistaken for emphasis (e.g. `https://x.dev/a_b` must stay one link).
 */
private fun nextMarkerIndex(source: String, from: Int): Int {
    var i = from
    while (i < source.length) {
        when (source[i]) {
            '`', '*', '_', '~', '[' -> return i
            else -> {
                val m = RE_AUTOLINK.find(source, i)
                i = if (m != null && m.range.first == i) m.range.last + 1 else i + 1
            }
        }
    }
    return source.length
}
