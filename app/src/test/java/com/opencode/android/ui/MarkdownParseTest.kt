package com.opencode.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block parser and the autolink scanner are pure, so the formatting
 * contract with the web renderer is pinned here.
 */
class MarkdownParseTest {
    @Test
    fun `headings keep their level`() {
        val blocks = parseMarkdown("# One\n### Three")
        assertEquals(
            listOf(MdBlock.Heading(1, "One"), MdBlock.Heading(3, "Three")),
            blocks,
        )
    }

    @Test
    fun `bullets and nesting levels`() {
        val blocks = parseMarkdown("- a\n  - b\n    - c")
        assertEquals(
            listOf(
                MdBlock.Bullet("a", 0),
                MdBlock.Bullet("b", 1),
                MdBlock.Bullet("c", 2),
            ),
            blocks,
        )
    }

    @Test
    fun `numbered list keeps the number`() {
        val blocks = parseMarkdown("1. first\n2) second")
        assertEquals(
            listOf(
                MdBlock.Numbered("1", "first", 0),
                MdBlock.Numbered("2", "second", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `task items are parsed before plain bullets`() {
        val blocks = parseMarkdown("- [ ] todo\n- [x] done\n- plain")
        assertEquals(
            listOf(
                MdBlock.Task("todo", false, 0),
                MdBlock.Task("done", true, 0),
                MdBlock.Bullet("plain", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `consecutive quote lines form a single block`() {
        val blocks = parseMarkdown("> one\n> two\n\nafter")
        assertEquals(MdBlock.Quote("one\ntwo"), blocks.first())
        assertEquals(2, blocks.size)
    }

    @Test
    fun `fenced code keeps the language and body`() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```")
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks.single())
    }

    @Test
    fun `table parses alignment row`() {
        val blocks = parseMarkdown("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |")
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf(TableAlign.Start, TableAlign.Center, TableAlign.End), table.aligns)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table.rows)
    }

    @Test
    fun `horizontal rule`() {
        assertEquals(MdBlock.Rule, parseMarkdown("---").single())
    }

    @Test
    fun `paragraph lines stay together`() {
        val blocks = parseMarkdown("line one\nline two")
        assertEquals(MdBlock.Paragraph("line one\nline two"), blocks.single())
    }

    @Test
    fun `parseAligns defaults to start`() {
        assertEquals(
            listOf(TableAlign.Start, TableAlign.Start),
            parseAligns("| --- | --- |"),
        )
    }

    @Test
    fun `autolink finds a bare url`() {
        val text = "see https://opencode.ai/docs now"
        val range = autolinkAt(text, 4)
        assertEquals("https://opencode.ai/docs", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `autolink excludes trailing sentence punctuation`() {
        val text = "go to https://opencode.ai."
        val range = autolinkAt(text, 6)!!
        assertEquals("https://opencode.ai", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `autolink drops an unmatched closing paren`() {
        val text = "(see https://opencode.ai/docs)"
        val range = autolinkAt(text, 5)!!
        assertEquals("https://opencode.ai/docs", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `autolink keeps a balanced closing paren`() {
        val text = "https://en.wikipedia.org/wiki/Foo_(bar)"
        val range = autolinkAt(text, 0)!!
        assertEquals(text, text.substring(range.first, range.last + 1))
    }

    @Test
    fun `autolink accepts www hosts`() {
        val text = "www.example.com/path"
        val range = autolinkAt(text, 0)!!
        assertEquals(text, text.substring(range.first, range.last + 1))
    }

    @Test
    fun `autolink ignores ordinary text`() {
        assertNull(autolinkAt("just words here", 0))
        assertNull(autolinkAt("ftp://nope", 0))
    }

    @Test
    fun `lone markers terminate and stay literal`() {
        // A stray marker used to make the inline loop stop advancing, which
        // hung the main thread (ANR) on any message containing one.
        val style = InlineStyle(Color(0xFF0000FF), Color(0x22000000), Color.White)
        val input = "a * b _ c ~ d [ e"
        assertEquals(input, inline(input, style).text)
    }

    @Test
    fun `unclosed bold stays literal`() {
        val style = InlineStyle(Color(0xFF0000FF), Color(0x22000000), Color.White)
        assertEquals("**oops", inline("**oops", style).text)
    }

    @Test
    fun `link label and url survive`() {
        val style = InlineStyle(Color(0xFF0000FF), Color(0x22000000), Color.White)
        val out = inline("[OpenCode](https://github.com/sst/opencode)", style)
        assertEquals("OpenCode", out.text)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals(
            "https://github.com/sst/opencode",
            (links.first().item as LinkAnnotation.Url).url,
        )
    }

    @Test
    fun `bare url becomes a link`() {
        val style = InlineStyle(Color(0xFF0000FF), Color(0x22000000), Color.White)
        val out = inline("see https://opencode.ai/docs now", style)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals("https://opencode.ai/docs", (links.first().item as LinkAnnotation.Url).url)
    }

    @Test
    fun `underscore inside a url is not an emphasis marker`() {
        // The scanner must treat the whole URL as one run; otherwise the link
        // would be cut at the underscore and the tail rendered as italics.
        val text = "https://x.dev/a_b more"
        val range = autolinkAt(text, 0)!!
        assertTrue(text.substring(range.first, range.last + 1).endsWith("a_b"))
    }

    // --- streaming split (progressive live rendering) ----------------------

    private fun assertSplitEquivalent(text: String) {
        val split = markdownStreamSplit(text)
        assertEquals(
            parseMarkdown(text),
            parseMarkdown(split.stable) + parseMarkdown(split.tail),
        )
    }

    @Test
    fun `stream split equals a full parse across many shapes`() {
        assertSplitEquivalent("")
        assertSplitEquivalent("no blank line yet")
        assertSplitEquivalent("first paragraph\n\nsecond paragraph")
        assertSplitEquivalent("# Heading\n\nbody text")
        assertSplitEquivalent("- a\n- b\n\n1. c\n2. d")
        assertSplitEquivalent("para\n\n```kotlin\nval x = 1\n```\n\nafter")
        // A blank line INSIDE a fenced code block must not be a boundary.
        assertSplitEquivalent("```\nline\n\nmore\n```\n\nafter")
        assertSplitEquivalent("| a | b |\n|--|--|\n| 1 | 2 |\n\npara")
        assertSplitEquivalent("trailing blank\n\n")
    }

    @Test
    fun `stream split keeps an unclosed fence in the tail`() {
        val text = "intro\n\n```kotlin\nval x = 1\n\nval y = 2"
        val split = markdownStreamSplit(text)
        // The tail starts at the fence, not inside it, so the prefix never
        // contains half of a code block.
        assertTrue(split.tail.startsWith("```kotlin"))
        assertSplitEquivalent(text)
    }

    @Test
    fun `stream split keeps car returns unsplit`() {
        // \r\n would make the offset arithmetic disagree with the normalised
        // parse, so such text simply stays in the tail (still correct, just not
        // incremental).
        val text = "a\r\n\r\nb"
        val split = markdownStreamSplit(text)
        assertEquals("", split.stable)
        assertEquals(text, split.tail)
    }
}
