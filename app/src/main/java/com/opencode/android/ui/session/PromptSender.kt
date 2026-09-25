package com.opencode.android.ui.session

import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.util.serverErrorMessage
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Candidate 1 strangler: the guarded `prompt_async` call of the send path.
 *
 * Extracted from `performSend`, which inlined the revision lookup, the
 * once-only 409 retry without a stale revision, the body read for a readable
 * error and the response close. The transport is a lambda so the whole state
 * machine is testable without Retrofit; the guard revision is read/cleared
 * through callbacks so this class never reaches into the ViewModel.
 */
sealed interface PromptSendResult {
    /** The turn was accepted (2xx). */
    data object Ok : PromptSendResult

    /** Still 409 after dropping the stale revision: another client changed the selection. */
    data object Conflict : PromptSendResult

    /** Non-2xx: [message] is the server's readable reason, or a fallback. */
    data class Failed(val message: String) : PromptSendResult
}

class PromptSender(
    private val sendPromptAsync: suspend (
        sessionId: String,
        body: PromptAsyncRequest,
        guardRevision: Long?,
    ) -> Response<ResponseBody>,
    private val guardRevision: (sessionId: String) -> Long?,
    private val clearGuardRevision: (sessionId: String) -> Unit,
) {
    suspend fun send(sessionId: String, body: PromptAsyncRequest): PromptSendResult {
        val revision = guardRevision(sessionId)
        var response = sendPromptAsync(sessionId, body, revision)
        if (response.code() == 409 && revision != null) {
            // Selection changed through another guarded client. Retry once
            // without the stale revision; the proxy rewrites the request to its
            // current authoritative selection.
            clearGuardRevision(sessionId)
            response = sendPromptAsync(sessionId, body, null)
        }
        if (response.code() == 409) return PromptSendResult.Conflict
        if (!response.isSuccessful) {
            // Keep the real reason ("insufficient balance…"): a bare HTTP code
            // tells the user nothing.
            val errorBody = try {
                response.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            return PromptSendResult.Failed(
                serverErrorMessage(errorBody, "prompt_async HTTP ${response.code()}"),
            )
        }
        response.body()?.close()
        return PromptSendResult.Ok
    }
}
