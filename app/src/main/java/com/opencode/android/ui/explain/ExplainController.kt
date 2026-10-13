package com.opencode.android.ui.explain

import com.opencode.android.data.OpenCodeApi
import com.opencode.android.domain.Message
import com.opencode.android.domain.PromptAsyncModel
import com.opencode.android.domain.PromptAsyncPart
import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.domain.SessionCreateRequest
import com.opencode.android.domain.SessionLocation
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.MessageEcho
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A side question about one artifact, rendered as a card in the running chat. */
data class ExplainState(
    val kind: ExplainPolicy.Kind,
    val label: String?,
    val snippet: String,
    val sessionId: String? = null,
    val running: Boolean = true,
    val failed: Boolean = false,
    val answer: String = "",
)

/**
 * Answers a side question about ONE artifact (message, diff, tool output) in its own
 * session with the `ask`/`docs` agent, so the working conversation stays untouched.
 *
 * Flow (all existing endpoints): create a session in the same project with the dedicated
 * agent and the CURRENT model (`POST /api/session`), send the scoped prompt
 * (`POST /prompt`), then read the answer (`GET /message`). The temp session is kept, so
 * "open in a session" works — it costs the same turn either way.
 */
class ExplainController(
    private val api: OpenCodeApi,
    private val scope: CoroutineScope,
    private val onState: (ExplainState?) -> Unit,
) {
    var pollIntervalMs: Long = 1_500L

    /** A side question is short; after this the card reports failure instead of spinning. */
    var maxDurationMs: Long = 3 * 60_000L

    @Volatile
    private var state: ExplainState? = null

    @Volatile
    private var generation = 0

    fun start(
        kind: ExplainPolicy.Kind,
        snippet: String,
        label: String?,
        directory: String?,
        providerId: String?,
        modelId: String?,
    ) {
        if (snippet.isBlank()) return
        val run = ++generation
        state = ExplainState(kind = kind, label = label, snippet = snippet)
        publish()
        scope.launch {
            val agent = ExplainPolicy.agentFor(kind)
            try {
                val created =
                    api
                        .createSessionIn(
                            SessionCreateRequest(
                                location = SessionLocation(directory = directory),
                                agent = agent,
                                model =
                                    if (!modelId.isNullOrBlank() && !providerId.isNullOrBlank()) {
                                        com.opencode.android.domain.ModelRef(
                                            id = modelId,
                                            providerId = providerId,
                                        )
                                    } else {
                                        null
                                    },
                            ),
                        ).data
                val sessionId = created.id
                update(run) { it.copy(sessionId = sessionId) }
                val body =
                    PromptAsyncRequest(
                        messageID = "",
                        agent = agent,
                        model =
                            if (!modelId.isNullOrBlank() && !providerId.isNullOrBlank()) {
                                PromptAsyncModel(modelID = modelId, providerID = providerId)
                            } else {
                                null
                            },
                        parts =
                            listOf(
                                PromptAsyncPart(
                                    id = "prt_expl_${System.nanoTime()}",
                                    type = "text",
                                    text = ExplainPolicy.prompt(kind, snippet, label),
                                ),
                            ),
                    )
                val response = api.sendPromptAsync(sessionId, body, null)
                response.body()?.close()
                response.errorBody()?.close()
                if (!response.isSuccessful) {
                    update(run) { it.copy(running = false, failed = true) }
                    return@launch
                }
                val deadline = System.currentTimeMillis() + maxDurationMs
                while (isCurrent(run)) {
                    delay(pollIntervalMs)
                    if (!isCurrent(run)) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        AppLog.w(APP_LOG_TAG) { "explain: deadline reached" }
                        update(run) { it.copy(running = false, failed = true) }
                        return@launch
                    }
                    val messages = runCatching { api.getMessages(sessionId, TAIL) }.getOrNull()
                    if (messages == null) continue
                    val answer = answerOf(messages)
                    if (answer.isNotBlank()) {
                        update(run) { it.copy(answer = answer, running = false) }
                        return@launch
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "explain failed: ${e.message}")
                update(run) { it.copy(running = false, failed = true) }
            }
        }
    }

    fun dismiss() {
        generation++
        state = null
        publish()
    }

    fun current(): ExplainState? = state

    private fun isCurrent(run: Int): Boolean = generation == run

    private fun publish() = onState(state)

    private fun update(
        run: Int,
        transform: (ExplainState) -> ExplainState,
    ) {
        if (!isCurrent(run)) return
        val current = state ?: return
        state = transform(current)
        publish()
    }

    /** Newest assistant answer, tolerant of both message schemas. */
    private fun answerOf(messages: List<Message>): String {
        val assistant =
            messages.lastOrNull { (it.role ?: it.info?.role ?: it.type) == "assistant" } ?: return ""
        return MessageEcho.firstText(assistant).trim()
    }

    private companion object {
        const val TAIL = 3
    }
}
