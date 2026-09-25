package com.opencode.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Small syntax highlighter for fenced code blocks.
 *
 * The web renders code with Shiki (themes `github-light` / `github-dark`), which
 * is a JS library and cannot run here. The colours below are lifted verbatim
 * from that theme's `tokenColors`, so a snippet looks the same on both clients
 * without shipping a highlighter dependency.
 *
 * It is a pragmatic scanner, not a parser: comments and strings are matched
 * first (so their contents are never re-tokenised), then keywords, numbers,
 * types, calls and punctuation. Good enough for reading code in a chat, and it
 * degrades to plain text for unknown languages.
 */
internal enum class CodeToken {
    Plain, Comment, Keyword, String, Number, Type, Function, Tag, Attribute, Variable, Operator, Added, Removed, Hunk,
}

/** GitHub Light, straight from the Shiki theme the web loads. */
/** Above this, highlighting is skipped (see `highlightCode`). */
private const val MAX_HIGHLIGHT_CHARS = 20_000

private val GITHUB_LIGHT = mapOf(
    CodeToken.Plain to Color(0xFF24292E),
    CodeToken.Comment to Color(0xFF6A737D),
    CodeToken.Keyword to Color(0xFFD73A49),
    CodeToken.String to Color(0xFF032F62),
    CodeToken.Number to Color(0xFF005CC5),
    CodeToken.Type to Color(0xFF6F42C1),
    CodeToken.Function to Color(0xFF6F42C1),
    CodeToken.Tag to Color(0xFF22863A),
    CodeToken.Attribute to Color(0xFF6F42C1),
    CodeToken.Variable to Color(0xFFE36209),
    CodeToken.Operator to Color(0xFFD73A49),
    CodeToken.Added to Color(0xFF22863A),
    CodeToken.Removed to Color(0xFFB31D28),
    CodeToken.Hunk to Color(0xFF005CC5),
)

/** GitHub Dark, the dark counterpart of the same theme family. */
private val GITHUB_DARK = mapOf(
    CodeToken.Plain to Color(0xFFC9D1D9),
    CodeToken.Comment to Color(0xFF8B949E),
    CodeToken.Keyword to Color(0xFFFF7B72),
    CodeToken.String to Color(0xFFA5D6FF),
    CodeToken.Number to Color(0xFF79C0FF),
    CodeToken.Type to Color(0xFFD2A8FF),
    CodeToken.Function to Color(0xFFD2A8FF),
    CodeToken.Tag to Color(0xFF7EE787),
    CodeToken.Attribute to Color(0xFFD2A8FF),
    CodeToken.Variable to Color(0xFFFFA657),
    CodeToken.Operator to Color(0xFFFF7B72),
    CodeToken.Added to Color(0xFF7EE787),
    CodeToken.Removed to Color(0xFFFFA198),
    CodeToken.Hunk to Color(0xFF79C0FF),
)

internal fun codePalette(isLight: Boolean): Map<CodeToken, Color> =
    if (isLight) GITHUB_LIGHT else GITHUB_DARK

// --- language families -----------------------------------------------------

private val SLASH_COMMENT_LANGS = setOf(
    "kotlin", "kt", "kts", "java", "javascript", "js", "jsx", "typescript", "ts", "tsx",
    "c", "cpp", "c++", "csharp", "cs", "go", "rust", "rs", "swift", "scala", "dart",
    "groovy", "php", "json5", "protobuf", "proto",
)
private val HASH_COMMENT_LANGS = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "console", "yaml", "yml", "toml",
    "ruby", "rb", "perl", "r", "makefile", "dockerfile", "ini", "conf", "properties",
)
private val DIFF_LANGS = setOf("diff", "patch")

private val KEYWORDS = setOf(
    // shared C-family
    "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
    "const", "continue", "data", "def", "default", "do", "elif", "else", "enum",
    "except", "export", "extends", "external", "false", "final", "finally", "fn",
    "for", "from", "fun", "function", "global", "goto", "if", "implements", "import",
    "in", "inline", "instanceof", "interface", "internal", "is", "lambda", "let",
    "match", "module", "mutable", "namespace", "new", "nil", "none", "null", "object",
    "open", "operator", "override", "package", "pass", "private", "protected", "public",
    "raise", "return", "sealed", "static", "struct", "super", "suspend", "switch",
    "synchronized", "this", "throw", "throws", "trait", "transient", "true", "try",
    "type", "typeof", "val", "var", "void", "volatile", "when", "where", "while",
    "with", "yield", "echo", "fi", "then", "done", "esac", "local", "readonly",
    "select", "create", "table", "insert", "into", "update", "delete", "where",
    "join", "group", "order", "by", "having", "limit", "offset", "primary", "key",
    "foreign", "references", "index", "alter", "drop", "and", "or", "not", "xor",
)

private fun isHashComment(lang: String) = lang in HASH_COMMENT_LANGS
private fun isSlashComment(lang: String) = lang in SLASH_COMMENT_LANGS
private fun isDiff(lang: String) = lang in DIFF_LANGS
private fun isMarkup(lang: String) =
    lang in setOf("html", "xml", "svg", "vue", "svelte", "htm")

private fun isSql(lang: String) = lang in setOf("sql", "postgres", "postgresql", "mysql", "sqlite")

// --- regexes (compiled once) ----------------------------------------------

private val RE_BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
private val RE_LINE_COMMENT_SLASH = Regex("//[^\\n]*")
private val RE_LINE_COMMENT_HASH = Regex("#[^\\n]*")
private val RE_LINE_COMMENT_SQL = Regex("--[^\\n]*")
private val RE_XML_COMMENT = Regex("<!--[\\s\\S]*?-->")

private val RE_STRING = Regex(
    "\"\"\"[\\s\\S]*?\"\"\"" +          // Kotlin/Python raw
        "|\"[^\"\\n]*\"" +              // double quoted
        "|'[^'\\n]*'" +                 // single quoted
        "|`[^`]*`",                     // backtick (JS/Go)
)
private val RE_NUMBER = Regex(
    "\\b0[xX][0-9a-fA-F_]+\\b" +
        "|\\b\\d[\\d_]*(\\.[\\d_]+)?([eE][+-]?\\d+)?[fFdDlLuU]?\\b",
)
private val RE_ANNOTATION = Regex("@[A-Za-z_]\\w*")
private val RE_KEYWORD = Regex("\\b[A-Za-z_]\\w*\\b")
private val RE_FUNCTION = Regex("[A-Za-z_]\\w*(?=\\s*\\()")
private val RE_TYPE = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")
private val RE_PROPERTY = Regex("(?<=\\.)[A-Za-z_]\\w*")
private val RE_OPERATOR = Regex("[{}()\\[\\];,.<>=+\\-*/%!&|^~?:]+")
private val RE_TAG = Regex("</?[A-Za-z][\\w:.-]*")
private val RE_ATTR = Regex("[A-Za-z_:][\\w:.-]*(?==)")

/**
 * Colours [code] for [lang]. Falls back to plain text for languages that are
 * not recognised, and handles unified diffs line-wise.
 */
internal fun highlightCode(
    code: String,
    lang: String,
    palette: Map<CodeToken, Color>,
): AnnotatedString {
    val language = lang.trim().lowercase().substringBefore(' ').substringBefore(':')

    // Scanning is O(chars x rules); colouring a huge paste costs real frame time
    // for no reader benefit, so very large blocks stay plain monospace.
    if (code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)

    if (isDiff(language)) return highlightDiff(code, palette)

    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val hit = matchAt(code, i, language)
            if (hit == null) {
                append(code[i])
                i++
                continue
            }
            withStyle(styleFor(hit.first, palette)) { append(hit.second.value) }
            i = hit.second.range.last + 1
        }
    }
}

private fun styleFor(token: CodeToken, palette: Map<CodeToken, Color>) = SpanStyle(
    color = palette[token] ?: palette[CodeToken.Plain] ?: androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight = when (token) {
        CodeToken.Type, CodeToken.Function, CodeToken.Hunk -> FontWeight.Medium
        else -> null
    },
)

/** First rule that matches exactly at [index]; order encodes priority. */
private fun matchAt(code: String, index: Int, lang: String): Pair<CodeToken, MatchResult>? {
    fun tryRule(token: CodeToken, regex: Regex): Pair<CodeToken, MatchResult>? {
        val m = regex.find(code, index) ?: return null
        return if (m.range.first == index) token to m else null
    }

    if (isMarkup(lang)) {
        tryRule(CodeToken.Comment, RE_XML_COMMENT)?.let { return it }
        tryRule(CodeToken.Tag, RE_TAG)?.let { return it }
        tryRule(CodeToken.Attribute, RE_ATTR)?.let { return it }
        tryRule(CodeToken.String, RE_STRING)?.let { return it }
        return tryRule(CodeToken.Operator, RE_OPERATOR)
    }

    when {
        isHashComment(lang) -> tryRule(CodeToken.Comment, RE_LINE_COMMENT_HASH)?.let { return it }
        isSql(lang) -> {
            tryRule(CodeToken.Comment, RE_LINE_COMMENT_SQL)?.let { return it }
            tryRule(CodeToken.Comment, RE_BLOCK_COMMENT)?.let { return it }
        }
        else -> {
            // Slash-comment languages, plus a safe default: an unknown language
            // still benefits from string/number/keyword colouring.
            tryRule(CodeToken.Comment, RE_BLOCK_COMMENT)?.let { return it }
            tryRule(CodeToken.Comment, RE_LINE_COMMENT_SLASH)?.let { return it }
        }
    }
    tryRule(CodeToken.String, RE_STRING)?.let { return it }
    tryRule(CodeToken.Number, RE_NUMBER)?.let { return it }
    tryRule(CodeToken.Attribute, RE_ANNOTATION)?.let { return it }

    val word = tryRule(CodeToken.Plain, RE_KEYWORD)
    if (word != null) {
        val text = word.second.value
        return when {
            text.lowercase() in KEYWORDS -> CodeToken.Keyword to word.second
            text[0].isUpperCase() -> CodeToken.Type to word.second
            else -> tryRule(CodeToken.Function, RE_FUNCTION)
                ?: tryRule(CodeToken.Variable, RE_PROPERTY)
                ?: CodeToken.Plain to word.second
        }
    }
    return tryRule(CodeToken.Operator, RE_OPERATOR)
}

/** Unified diff: whole-line colouring, exactly what a reviewer wants to scan. */
private fun highlightDiff(code: String, palette: Map<CodeToken, Color>): AnnotatedString =
    buildAnnotatedString {
        code.split("\n").forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val token = when {
                line.startsWith("+++") || line.startsWith("---") -> CodeToken.Hunk
                line.startsWith("@@") -> CodeToken.Hunk
                line.startsWith("+") -> CodeToken.Added
                line.startsWith("-") -> CodeToken.Removed
                else -> CodeToken.Plain
            }
            withStyle(styleFor(token, palette)) { append(line) }
        }
    }
