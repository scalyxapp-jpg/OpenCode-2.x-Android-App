package com.opencode.android.ui.session

import com.opencode.android.domain.PromptAsyncRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class PromptSenderTest {

    private val body: PromptAsyncRequest = buildPromptAsyncRequest(
        messageId = "msg_1",
        partId = "prt_1",
        agent = "build",
        providerId = "deepseek",
        modelId = "deepseek-v4-flash",
        variant = "default",
        finalText = "hi",
    )

    private fun ok() = Response.success("ok".toResponseBody(null))

    private fun conflict() = Response.error<okhttp3.ResponseBody>(
        409,
        """{"data":{"message":"guard conflict"}}""".toResponseBody("application/json".toMediaType()),
    )

    private fun error(code: Int, json: String) = Response.error<okhttp3.ResponseBody>(
        code,
        json.toResponseBody("application/json".toMediaType()),
    )

    @Test
    fun `2xx returns Ok`() = kotlinx.coroutines.runBlocking {
        var cleared = false
        val sender = PromptSender(
            sendPromptAsync = { _, _, _ -> ok() },
            guardRevision = { 7L },
            clearGuardRevision = { cleared = true },
        )
        assertEquals(PromptSendResult.Ok, sender.send("ses_1", body))
        assertTrue(!cleared)
    }

    @Test
    fun `409 with a revision retries once without it`() = kotlinx.coroutines.runBlocking {
        val revisions = mutableListOf<Long?>()
        var cleared = false
        val sender = PromptSender(
            sendPromptAsync = { _, _, revision ->
                revisions += revision
                if (revisions.size == 1) conflict() else ok()
            },
            guardRevision = { 7L },
            clearGuardRevision = { cleared = true },
        )
        assertEquals(PromptSendResult.Ok, sender.send("ses_1", body))
        assertEquals(listOf(7L, null), revisions)
        assertTrue(cleared)
    }

    @Test
    fun `409 twice returns Conflict`() = kotlinx.coroutines.runBlocking {
        val sender = PromptSender(
            sendPromptAsync = { _, _, _ -> conflict() },
            guardRevision = { 7L },
            clearGuardRevision = { },
        )
        assertEquals(PromptSendResult.Conflict, sender.send("ses_1", body))
    }

    @Test
    fun `409 without a revision is Conflict and does not retry`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val sender = PromptSender(
            sendPromptAsync = { _, _, _ -> calls++; conflict() },
            guardRevision = { null },
            clearGuardRevision = { },
        )
        assertEquals(PromptSendResult.Conflict, sender.send("ses_1", body))
        assertEquals(1, calls)
    }

    @Test
    fun `non-2xx returns the server's readable reason`() = kotlinx.coroutines.runBlocking {
        val sender = PromptSender(
            sendPromptAsync = { _, _, _ ->
                error(500, """{"data":{"message":"insufficient balance"}}""")
            },
            guardRevision = { null },
            clearGuardRevision = { },
        )
        val result = sender.send("ses_1", body)
        assertTrue(result is PromptSendResult.Failed)
        assertTrue((result as PromptSendResult.Failed).message.contains("insufficient balance"))
    }
}
