package com.opencode.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A URL in the user's own prompt (and in tool output) is rendered as plain text, so it
 * used to be untappable. [autoLinkedText] turns those URLs into real links.
 */
class LinkTextTest {
    private val url = "https://example.com/docs"

    private fun links(text: String): List<LinkAnnotation.Url> {
        val annotated = autoLinkedText(text, Color.Blue)
        return annotated
            .getLinkAnnotations(0, annotated.length)
            .mapNotNull { it.item as? LinkAnnotation.Url }
    }

    @Test
    fun `a bare http url becomes a link`() {
        val found = links("see $url for details")
        assertEquals(1, found.size)
        assertEquals(url, found.first().url)
    }

    @Test
    fun `a www url becomes an https link and keeps surrounding text`() {
        val annotated = autoLinkedText("go to www.example.org now", Color.Blue)
        val found =
            annotated.getLinkAnnotations(0, annotated.length).mapNotNull { it.item as? LinkAnnotation.Url }
        assertEquals(1, found.size)
        assertEquals("https://www.example.org", found.first().url)
        assertTrue(annotated.text.contains("go to "))
        assertTrue(annotated.text.contains(" now"))
    }

    @Test
    fun `a trailing dot stays outside the link`() {
        val found = links("read https://example.com/a.")
        assertEquals(1, found.size)
        assertEquals("https://example.com/a", found.first().url)
    }

    @Test
    fun `multiple urls are all linked`() {
        assertEquals(2, links("https://a.dev and https://b.dev").size)
    }

    @Test
    fun `plain text stays link-free`() {
        assertTrue(links("no urls here, just code: val x = 1").isEmpty())
    }
}
