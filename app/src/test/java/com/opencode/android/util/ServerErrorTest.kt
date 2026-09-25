package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the friendly server-error extraction used by /compact. */
class ServerErrorTest {

    @Test
    fun `extracts the nested data message`() {
        val raw = """{"name":"UnknownError","data":{"message":"Unexpected server error. Check server logs for details.","ref":"err_1"}}"""
        assertEquals(
            "Unexpected server error. Check server logs for details.",
            serverErrorMessage(raw, "fallback"),
        )
    }

    @Test
    fun `extracts a top level message`() {
        assertEquals("bad request", serverErrorMessage("""{"message":"bad request"}""", "fallback"))
    }

    @Test
    fun `keeps a plain text body`() {
        assertEquals("boom", serverErrorMessage("boom", "fallback"))
    }

    @Test
    fun `falls back when the body is blank`() {
        assertEquals("fallback", serverErrorMessage(null, "fallback"))
        assertEquals("fallback", serverErrorMessage("   ", "fallback"))
    }

    @Test
    fun `falls back for a json body without a message`() {
        assertEquals("fallback", serverErrorMessage("""{"data":{"ref":"x"}}""", "fallback"))
    }
}
