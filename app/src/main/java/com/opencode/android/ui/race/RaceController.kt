package com.opencode.android.ui.race

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

/**
 * Runs one prompt against several models at once and reports their results.
 *
 * Why this exists: picking a model is guesswork — the app can show price and context
 * size, but not how a model handles *your* repository. A race answers that: the same
 * prompt goes to 2–3 models in parallel, each in its own session in the same project,
 * and the app shows answer, wall-clock time and cost side by side, with one tap to
 * continue in the winner.
 *
 * Verified building blocks (live server, 2.0.26):
 * `POST /api/session` accepts `{location, agent, model}` — so a member session is
 * created *with* its model in a single call (no second request, no window where the
 * session runs on the wrong model). Each member then gets one
 * `POST /api/session/{id}/prompt` with its own client-generated message id, and the
 * results are read back with `GET /api/session/{id}` (cost/tokens/status) and
 * `GET /api/session/{id}/message` (the answer). `POST /api/session/{id}/interrupt`
 * stops a race.
 *
 * Costs: every member is a full turn on the user's own key, which is why
 * [RacePlan.MAX_MODELS] is capped and the picker says so.
 */
class RaceController(
    private val api: OpenCodeApi,
    private val scope: CoroutineScope,
    private val onState: (RaceState?) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    /** Poll interval while at least one member is still running. */
    var pollIntervalMs: Long = 2_500L

    @Volatile
    private var state: RaceState? = null

    @Volatile
    private var generation = 0

    /**
     * Creates one session per member (same directory, same agent) and sends the prompt
     * to each. Returns immediately; progress arrives through [onState].
     */
    fun start(
        prompt: String,
        directory: String?,
        agent: String,
        members: List<RaceMember>,
    ) {
        if (members.isEmpty()) return
        val run = ++generation
        val started = System.currentTimeMillis()
        state =
            RaceState(
                prompt = prompt,
                directory = directory,
                startedAt = started,
                members = members.map { RaceMemberState(member = it) },
            )
        publish()
        scope.launch {
            // Fan out: one session per member. Members are independent, so a failure
            // marks only that member instead of aborting the whole race.
            members.forEach { member ->
                try {
                    val created =
                        api
                            .createSessionIn(
                                SessionCreateRequest(
                                    location = SessionLocation(directory = directory),
                                    agent = agent.ifBlank { null },
                                    model =
                                        com.opencode.android.domain.ModelRef(
                                            id = member.modelId,
                                            providerId = member.providerId,
                                        ),
                                ),
                            ).data
                    val sessionId = created.id
                    updateMember(run, member.modelRef) { it.copy(sessionId = sessionId) }
                    val body =
                        PromptAsyncRequest(
                            messageID = "",
                            agent = agent.ifBlank { null },
                            model = PromptAsyncModel(modelID = member.modelId, providerID = member.providerId),
                            parts =
                                listOf(
                                    PromptAsyncPart(
                                        id = "prt_race_${System.nanoTime()}",
                                        type = "text",
                                        text = prompt,
                                    ),
                                ),
                        )
                    val response = api.sendPromptAsync(sessionId, body, null)
                    response.body()?.close()
                    response.errorBody()?.close()
                    if (!response.isSuccessful) {
                        updateMember(run, member.modelRef) {
                            it.copy(
                                running = false,
                                failed = true,
                                elapsedMs = System.currentTimeMillis() - started,
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "race: ${member.label} failed to start: ${e.message}")
                    updateMember(run, member.modelRef) {
                        it.copy(
                            running = false,
                            failed = true,
                            elapsedMs = System.currentTimeMillis() - started,
                        )
                    }
                }
            }
            // Poll until every member is done (or the race was stopped/replaced).
            while (isCurrent(run) && state?.finished == false) {
                delay(pollIntervalMs)
                if (!isCurrent(run)) return@launch
                refresh()
            }
        }
    }

    /** Re-reads every member: status, answer, cost and elapsed time. */
    fun refresh() {
        val run = generation
        val members = state?.members ?: return
        scope.launch {
            members.forEach { member ->
                val sessionId = member.sessionId ?: return@forEach
                if (!isCurrent(run)) return@launch
                try {
                    val session = api.getSessionFull(sessionId)
                    val messages = api.getMessages(sessionId, TAIL)
                    val answer = answerOf(messages)
                    val running = session.status?.type == "busy" || session.status?.type == "retry"
                    updateMember(run, member.member.modelRef) {
                        it.copy(
                            running = running && answer.isBlank(),
                            answer = answer.ifBlank { it.answer },
                            cost = session.cost ?: it.cost,
                            tokens = session.tokens?.let { t -> (t.input ?: 0L) + (t.output ?: 0L) } ?: it.tokens,
                            elapsedMs = System.currentTimeMillis() - (state?.startedAt ?: 0L),
                        )
                    }
                    // A member with an answer and no running turn is done even if the
                    // status frame has not arrived yet.
                    if (answer.isNotBlank() && !running) {
                        updateMember(run, member.member.modelRef) { it.copy(running = false) }
                    }
                } catch (e: Exception) {
                    AppLog.w(APP_LOG_TAG) { "race: refresh $sessionId failed: ${e.message}" }
                }
            }
        }
    }

    /** Stops every member that is still running. */
    fun stop() {
        val members = state?.members ?: return
        scope.launch {
            members.forEach { member ->
                val sessionId = member.sessionId ?: return@forEach
                try {
                    api.interrupt(sessionId)
                } catch (e: Exception) {
                    AppLog.w(APP_LOG_TAG) { "race: interrupt $sessionId failed: ${e.message}" }
                }
                updateMember(generation, member.member.modelRef) { it.copy(running = false) }
            }
        }
    }

    /** Forgets the race (the sessions stay; they are real sessions on the server). */
    fun dismiss() {
        generation++
        state = null
        publish()
    }

    fun current(): RaceState? = state

    private fun isCurrent(run: Int): Boolean = generation == run

    private fun publish() {
        onState(state)
    }

    private fun updateMember(
        run: Int,
        modelRef: String,
        transform: (RaceMemberState) -> RaceMemberState,
    ) {
        if (!isCurrent(run)) return
        val current = state ?: return
        state =
            current.copy(
                members =
                    current.members.map { member ->
                        if (member.member.modelRef == modelRef) transform(member) else member
                    },
            )
        publish()
    }

    /**
     * First visible text of the newest assistant message.
     *
     * Both message schemas exist: the classic one carries `role`/`info.role`, the V2
     * one carries `type`. Checking only `role` made every raced answer look empty
     * (caught by [RaceControllerTest] against a mock server).
     */
    private fun answerOf(messages: List<Message>): String {
        val assistant =
            messages.lastOrNull {
                (it.role ?: it.info?.role ?: it.type) == "assistant"
            } ?: return ""
        return MessageEcho.firstText(assistant).trim()
    }

    private companion object {
        /** Member sessions hold full tool output; the answer only needs the tail. */
        const val TAIL = 4
    }
}
