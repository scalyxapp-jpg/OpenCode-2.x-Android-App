package com.opencode.android.ui.session

import com.opencode.android.data.SessionTransport
import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import com.opencode.android.domain.Model
import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.domain.SessionStatus
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Candidate 1 — SessionConversation command surface + projection, with fakes. */
class SessionConversationTest {

    private class FakePort : ConversationPort {
        var sends = 0
        var retries = 0
        var reconciles = 0
        var generatingSets = 0
        var refreshes = 0
        override fun send() { sends++ }
        override fun retry() { retries++ }
        override fun reconcile(includeMeta: Boolean) { reconciles++ }
        override fun loadPendingQuestions() {}
        override fun loadPermissions() {}
        override fun notifyPermission() {}
        override fun notifyDone() {}
        override fun notifyError(message: String?) {}
        override fun notifyInterruptFailed(message: String?) {}
        override fun refreshMessages(sessionId: String) { refreshes++ }
        override fun refreshSessionModel() {}
        override fun loadVcsDiff() {}
        override fun setGenerating(value: Boolean) { generatingSets++ }
        override fun setPendingPersist(value: Boolean) {}
        override fun setCompacting(value: Boolean) {}
        override fun setStatusError(value: String?) {}
        override fun setSseConnected(value: Boolean) {}
        override fun setSelectedModel(value: String) {}
    }

    private class FakeTransport : SessionTransport {
        var aborts = 0
        var interrupts = 0
        override suspend fun promptAsync(
            sessionId: String,
            body: PromptAsyncRequest,
            guardRevision: Long?,
        ): Response<okhttp3.ResponseBody> = Response.success("ok".toResponseBody(null))
        override suspend fun abort(sessionId: String): Response<okhttp3.ResponseBody> {
            aborts++
            return Response.success("true".toResponseBody(null))
        }
        override suspend fun interrupt(sessionId: String) { interrupts++ }
        override fun guardRevision(sessionId: String): Long? = null
        override fun clearGuardRevision(sessionId: String) {}
    }

    private val noError: (JsonElement?) -> String? = { null }
    private val noModels: () -> List<Model> = { emptyList() }

    @Test
    fun `dispatch routes send and retry to the port and interrupt to the transport`() = runTest {
        val port = FakePort()
        val transport = FakeTransport()
        val conversation = SessionConversation(
            EventSource { _, _ -> emptyFlow() }, this, noModels, noError, transport, port,
        )
        conversation.dispatch(SessionCommand.Load("s1", "http://x"))
        conversation.dispatch(SessionCommand.Send)
        conversation.dispatch(SessionCommand.Interrupt)
        conversation.dispatch(SessionCommand.Retry)
        advanceUntilIdle()
        assertEquals(1, port.sends)
        assertEquals(1, port.retries)
        assertEquals(1, transport.aborts)
        assertEquals(1, port.refreshes)
    }

    @Test
    fun `load sets the session and stream events project into state`() = runTest {
        val port = FakePort()
        val transport = FakeTransport()
        val source = EventSource { _, _ ->
            flowOf(
                Event(
                    type = "session.status",
                    properties = EventProperties(status = SessionStatus(type = "busy")),
                ),
            )
        }
        val conversation = SessionConversation(source, this, noModels, noError, transport, port)
        conversation.dispatch(SessionCommand.Load("s1", "http://x"))
        advanceUntilIdle()
        assertEquals("s1", conversation.state.value.sessionId)
        assertTrue(conversation.state.value.isGenerating)
        assertTrue(port.generatingSets > 0)
    }
}
