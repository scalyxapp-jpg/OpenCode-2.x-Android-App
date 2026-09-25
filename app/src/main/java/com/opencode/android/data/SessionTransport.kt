package com.opencode.android.data

import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.util.SelectionGuard
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * The transport seam a conversation workflow uses for the session-level HTTP
 * operations it owns (interrupt/abort, guarded prompt). It exists so
 * `SessionConversation` can run a workflow without reaching into the
 * ViewModel's `OpenCodeApi` or the process-wide `SelectionGuard`: the transport
 * is injected, and a test supplies a fake.
 *
 * [BackendSessionTransport] is the production adapter over [BackendSession]
 * (which resolves the current Retrofit instance per call, so a backend switch
 * is picked up) and the session guard revisions.
 */
interface SessionTransport {
    suspend fun promptAsync(
        sessionId: String,
        body: PromptAsyncRequest,
        guardRevision: Long?,
    ): Response<ResponseBody>

    suspend fun abort(sessionId: String): Response<ResponseBody>

    /** Fallback for servers without the abort endpoint. */
    suspend fun interrupt(sessionId: String)

    fun guardRevision(sessionId: String): Long?

    fun clearGuardRevision(sessionId: String)
}

class BackendSessionTransport(
    private val session: BackendSession,
    private val guard: SelectionGuard,
) : SessionTransport {
    override suspend fun promptAsync(
        sessionId: String,
        body: PromptAsyncRequest,
        guardRevision: Long?,
    ): Response<ResponseBody> = session.api.sendPromptAsync(sessionId, body, guardRevision)

    override suspend fun abort(sessionId: String): Response<ResponseBody> =
        session.api.abort(sessionId)

    override suspend fun interrupt(sessionId: String) {
        session.api.interrupt(sessionId)
    }

    override fun guardRevision(sessionId: String): Long? = guard.guardRevision(sessionId)

    override fun clearGuardRevision(sessionId: String) {
        guard.clearGuardRevision(sessionId)
    }
}
