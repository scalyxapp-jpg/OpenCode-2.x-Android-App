package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server reports folder failures as an HTTP 500 with a JSON envelope; the
 * useful part is `data.message`. Surfacing it (instead of a bare status code)
 * is what makes the browser's error state actionable.
 */
class FileErrorsTest {

    @Test
    fun `extracts the message from the server envelope`() {
        val raw = """{"name":"UnknownError","data":{"message":"Unexpected server error","ref":"err_1"}}"""
        assertEquals("Unexpected server error", FileErrors.serverMessage(raw))
    }

    @Test
    fun `ignores unknown fields around the message`() {
        val raw = """{"extra":1,"data":{"other":"x","message":"readable?"}}"""
        assertEquals("readable?", FileErrors.serverMessage(raw))
    }

    @Test
    fun `returns null for malformed or unexpected payloads`() {
        assertNull(FileErrors.serverMessage(null))
        assertNull(FileErrors.serverMessage(""))
        assertNull(FileErrors.serverMessage("   "))
        assertNull(FileErrors.serverMessage("not json"))
        assertNull(FileErrors.serverMessage("""{"data":{}}"""))
        assertNull(FileErrors.serverMessage("""{"name":"UnknownError"}"""))
        assertNull(FileErrors.serverMessage("""[1,2,3]"""))
        assertNull(FileErrors.serverMessage("""{"data":{"message":"   "}}"""))
    }

    @Test
    fun `non-http exceptions keep their own message`() {
        assertEquals("boom", FileErrors.friendly(IllegalStateException("boom")))
    }

    @Test
    fun `exception without a message falls back to the class name`() {
        assertEquals("IllegalStateException", FileErrors.friendly(IllegalStateException()))
    }
}
