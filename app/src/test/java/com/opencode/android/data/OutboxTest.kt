package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline outbox must only swallow CONNECTIVITY failures. A real rejection
 * (HTTP 4xx) has to surface, otherwise the app would replay the same error forever and
 * pretend the prompt was merely waiting.
 */
class OutboxPolicyTest {
    @Test
    fun `a dead connection is retryable`() {
        assertTrue(OutboxPolicy.isRetryable("java.net.UnknownHostException", "Unable to resolve host \"phone\""))
        assertTrue(OutboxPolicy.isRetryable("java.net.ConnectException", "Failed to connect to /10.0.0.5:4096"))
        assertTrue(OutboxPolicy.isRetryable("java.net.SocketTimeoutException", "timeout"))
        assertTrue(OutboxPolicy.isRetryable(null, "prompt_async HTTP 502"))
    }

    @Test
    fun `a rejected prompt is not retryable`() {
        assertFalse(OutboxPolicy.isRetryable(null, "prompt_async HTTP 400: text is required"))
        assertFalse(OutboxPolicy.isRetryable("java.io.IOException", "HTTP 404 not found"))
        assertFalse(OutboxPolicy.isRetryable(null, "Model deepseek/nope not available on server"))
    }

    @Test
    fun `rate limiting counts as retryable`() {
        assertTrue(OutboxPolicy.isRetryable(null, "prompt_async HTTP 429"))
    }

    @Test
    fun `backoff doubles per failed try and is capped`() {
        // attempts = failed tries so far: the FIRST retry waits the base delay.
        assertEquals(OutboxPolicy.BASE_BACKOFF_MS, OutboxPolicy.backoffMs(0))
        assertEquals(OutboxPolicy.BASE_BACKOFF_MS, OutboxPolicy.backoffMs(1))
        assertEquals(OutboxPolicy.BASE_BACKOFF_MS * 2, OutboxPolicy.backoffMs(2))
        assertEquals(OutboxPolicy.BASE_BACKOFF_MS * 4, OutboxPolicy.backoffMs(3))
        assertEquals(OutboxPolicy.MAX_BACKOFF_MS, OutboxPolicy.backoffMs(10))
    }

    @Test
    fun `an item is attempted immediately, then after its backoff`() {
        val fresh = OutboxItem(id = "1", sessionId = "s", text = "hi")
        assertTrue(OutboxPolicy.shouldAttempt(fresh, 1_000L))

        val failedOnce = fresh.copy(attempts = 1, lastAttemptAt = 1_000L)
        assertFalse(OutboxPolicy.shouldAttempt(failedOnce, 1_000L + OutboxPolicy.BASE_BACKOFF_MS - 1))
        assertTrue(OutboxPolicy.shouldAttempt(failedOnce, 1_000L + OutboxPolicy.BASE_BACKOFF_MS))
    }

    @Test
    fun `an item is parked after the attempt cap`() {
        val parked = OutboxItem(id = "1", sessionId = "s", text = "hi", attempts = OutboxPolicy.MAX_ATTEMPTS)
        assertFalse(OutboxPolicy.shouldAttempt(parked, Long.MAX_VALUE))
    }

    @Test
    fun `the per session cap keeps the newest items`() {
        val many = (1..(OutboxPolicy.MAX_ITEMS_PER_SESSION + 5)).map {
            OutboxItem(id = "$it", sessionId = "s", text = "text $it")
        }
        val trimmed = OutboxPolicy.trim(many)
        assertEquals(OutboxPolicy.MAX_ITEMS_PER_SESSION, trimmed.size)
        assertEquals("text 15", trimmed.last().text)
    }
}

/** The persistence format is a plain JSON list; a corrupt entry must not brick it. */
class OutboxCodecTest {
    @Test
    fun `round trips items including the selection`() {
        val items =
            listOf(
                OutboxItem(
                    id = "msg_1",
                    sessionId = "ses_1",
                    text = "do the thing",
                    agent = "build",
                    providerId = "deepseek",
                    modelId = "deepseek-v4-flash",
                    variant = "high",
                    createdAt = 42L,
                    attempts = 2,
                    lastAttemptAt = 99L,
                    lastError = "timeout",
                ),
            )
        val decoded = OutboxCodec.decode(OutboxCodec.encode(items))
        assertEquals(items, decoded)
    }

    @Test
    fun `garbage decodes to an empty outbox instead of throwing`() {
        assertEquals(emptyList<OutboxItem>(), OutboxCodec.decode("{not json"))
        assertEquals(emptyList<OutboxItem>(), OutboxCodec.decode(null))
        assertEquals(emptyList<OutboxItem>(), OutboxCodec.decode(""))
    }
}
