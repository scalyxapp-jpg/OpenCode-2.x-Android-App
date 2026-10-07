package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** The body cap must pass small bodies and abort oversized ones. */
class LimitedInputStreamTest {
    @Test
    fun `passes a body under the cap`() {
        val payload = ByteArray(1000) { it.toByte() }
        val stream = LimitedInputStream(ByteArrayInputStream(payload), maxBytes = 2000, what = "test")
        val read = stream.readBytes()
        assertEquals(1000, read.size)
    }

    @Test
    fun `aborts when the body exceeds the cap`() {
        val payload = ByteArray(5000)
        val stream = LimitedInputStream(ByteArrayInputStream(payload), maxBytes = 1000, what = "test")
        assertThrows(IOException::class.java) { stream.readBytes() }
    }

    @Test
    fun `aborts exactly past the boundary`() {
        val payload = ByteArray(1001)
        val stream = LimitedInputStream(ByteArrayInputStream(payload), maxBytes = 1000, what = "test")
        assertThrows(IOException::class.java) { stream.readBytes() }
    }
}
