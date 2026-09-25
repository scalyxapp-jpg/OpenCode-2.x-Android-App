package com.opencode.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlighter must never change the code (colour only) and must pick the
 * GitHub palette the web's Shiki theme uses.
 */
class CodeHighlighterTest {

    private val light = codePalette(isLight = true)
    private val dark = codePalette(isLight = false)

    private fun colorAt(text: AnnotatedString, index: Int): Color? =
        text.spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color

    private fun colorOf(text: AnnotatedString, needle: String): Color? {
        val at = text.text.indexOf(needle)
        if (at < 0) return null
        return colorAt(text, at)
    }

    @Test
    fun `never alters the source text`() {
        val code = """
            // a comment
            fun main() {
                val s = "hello ${'$'}name"
                println(s)   /* inline */
            }
        """.trimIndent()
        assertEquals(code, highlightCode(code, "kotlin", light).text)
    }

    @Test
    fun `kotlin keyword string and comment use the theme colours`() {
        val code = "fun f() { val s = \"x\" } // note"
        val out = highlightCode(code, "kotlin", light)
        assertEquals(light[CodeToken.Keyword], colorOf(out, "fun"))
        assertEquals(light[CodeToken.String], colorOf(out, "\"x\""))
        assertEquals(light[CodeToken.Comment], colorOf(out, "// note"))
    }

    @Test
    fun `python hash comment is recognised`() {
        val out = highlightCode("# note\nx = 1", "python", light)
        assertEquals(light[CodeToken.Comment], colorOf(out, "# note"))
        assertEquals(light[CodeToken.Number], colorOf(out, "1"))
    }

    @Test
    fun `sql double dash comment is recognised`() {
        val out = highlightCode("-- note\nselect 1", "sql", light)
        assertEquals(light[CodeToken.Comment], colorOf(out, "-- note"))
    }

    @Test
    fun `xml tags and attributes are coloured`() {
        val out = highlightCode("<a href=\"x\">t</a>", "html", light)
        assertEquals(light[CodeToken.Tag], colorOf(out, "<a"))
        assertEquals(light[CodeToken.Attribute], colorOf(out, "href"))
        assertEquals(light[CodeToken.String], colorOf(out, "\"x\""))
    }

    @Test
    fun `a comment body is not re-tokenised`() {
        // The word "fun" inside a comment must stay comment-coloured.
        val out = highlightCode("// fun val", "kotlin", light)
        assertEquals(light[CodeToken.Comment], colorOf(out, "fun"))
    }

    @Test
    fun `unknown language still highlights strings and numbers`() {
        val out = highlightCode("plain \"s\" 42", "whatever", light)
        assertEquals(light[CodeToken.String], colorOf(out, "\"s\""))
        assertEquals(light[CodeToken.Number], colorOf(out, "42"))
    }

    @Test
    fun `diff lines are coloured by their prefix`() {
        val diff = "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n context"
        val out = highlightCode(diff, "diff", light)
        assertEquals(light[CodeToken.Hunk], colorOf(out, "--- a"))
        assertEquals(light[CodeToken.Hunk], colorOf(out, "@@"))
        assertEquals(light[CodeToken.Removed], colorOf(out, "-old"))
        assertEquals(light[CodeToken.Added], colorOf(out, "+new"))
        assertEquals(light[CodeToken.Plain], colorOf(out, "context"))
    }

    @Test
    fun `dark palette differs from light`() {
        assertNotEquals(light[CodeToken.Keyword], dark[CodeToken.Keyword])
        assertNotEquals(light[CodeToken.String], dark[CodeToken.String])
    }

    @Test
    fun `empty code is handled`() {
        assertTrue(highlightCode("", "kotlin", light).text.isEmpty())
    }

    @Test
    fun `language tag with parameters is normalised`() {
        val out = highlightCode("fun f() {}", "kotlin title=file", light)
        assertEquals(light[CodeToken.Keyword], colorOf(out, "fun"))
    }
}
