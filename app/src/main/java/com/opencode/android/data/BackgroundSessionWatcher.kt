package com.opencode.android.data

import android.content.Context
import com.opencode.android.R
import com.opencode.android.domain.Event
import com.opencode.android.ui.session.SessionStatusReducer
import com.opencode.android.ui.session.SseFilter
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.LogAndSwallow
import com.opencode.android.util.MessageEcho
import com.opencode.android.util.ToolText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Posts the background notifications for sessions that keep running while the app
 * is not in focus:
 *
 *  - **step progress, occasionally** — after a finished step, throttled by
 *    [SessionAlertPolicy], naming the tools of that step ("Shell · ls -la"),
 *  - **the finished summary** — when the turn ends, with the answer itself
 *    (truncated), so the notification already says what happened.
 *
 * Both carry the session id and the message id, so a tap lands in that session at
 * that message.
 *
 * Lifecycle: collects only between "app went to the background" and "app came
 * back". A stream nobody needs while the user is watching would keep the process
 * busy for nothing, and the notifications are suppressed then anyway. [Notifier]
 * also refuses to post while the app is visible (belt and braces for the callers
 * that post from a foreground chat).
 */
object BackgroundSessionWatcher {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + LogAndSwallow)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var api: OpenCodeApi? = null

    @Volatile
    private var baseUrlProvider: () -> String = { "" }

    private var job: Job? = null
    private var policy = SessionAlertPolicy.State()

    // Ambient glance state while the app is in the BACKGROUND (the widget/notification
    // are the only UI then): steps of the newest turn, failures, elapsed time.
    private var glanceSession: String? = null
    private var glanceStep = 0
    private var glanceFailures = 0
    private var glanceStartedAt = 0L
    private var pendingDecisionIds = mutableSetOf<String>()
    private val running = mutableSetOf<String>()
    private val retrying = mutableSetOf<String>()

    /**
     * The message a tap should open: for a step alert the message the tool ran in,
     * for the summary the newest assistant answer. A failure here only costs the
     * precise scroll position, never the notification.
     */
    @Volatile
    private var lastMessageId: String? = null

    fun init(
        context: Context,
        api: OpenCodeApi,
        baseUrl: () -> String,
    ) {
        this.context = context.applicationContext
        this.api = api
        this.baseUrlProvider = baseUrl
        // Start/stop with the visibility transitions instead of polling the state.
        AppVisibility.onVisibilityChanged = { visible -> if (visible) stop() else start() }
        if (AppVisibility.isBackground) start()
    }

    fun start() {
        if (job?.isActive == true) return
        // Setting: nothing at all runs in the background when monitoring is off.
        if (!AppSettingsStore.state.value.backgroundMonitoring) return
        val url = baseUrlProvider()
        if (url.isBlank()) return
        // Cost of the day for the glance surfaces: one call when the watcher starts (the
        // app then keeps it fresh by re-entering the foreground), never a timer.
        scope.launch {
            runCatching {
                val start =
                    java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                api?.getStats(fromMs = start)?.cost?.let { GlanceStore.update(costTodayUsd = it) }
            }
        }
        AppLog.d(APP_LOG_TAG) { "BackgroundSessionWatcher: collect $url" }
        job =
            scope.launch {
                try {
                    SseClient.events(url, "").collect { event -> onEvent(event) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(APP_LOG_TAG) { "BackgroundSessionWatcher: stream failed: ${e.message}" }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun onEvent(event: Event) {
        // Nothing to collect for: a disabled master switch means no alerts at all.
        if (!AppSettingsStore.state.value.notificationsEnabled) return
        if (!SseFilter.shouldDeliver(event, "")) return
        val eventSession = event.properties?.sessionId
        if (eventSession != null) {
            val next = SessionStatusReducer.reduce(running, retrying, event)
            if (next != null) {
                val (nextRunning, nextRetrying) = next
                val finished = eventSession in running && eventSession !in nextRunning
                running.clear()
                running.addAll(nextRunning)
                retrying.clear()
                retrying.addAll(nextRetrying)
                if (finished) onTurnEnded(eventSession, turnFailed = event.type == "session.error")
                // A pure status frame carries nothing else.
                if (event.type in STATUS_ONLY) return
            }
            if (eventSession !in running) return
        } else {
            return
        }

        when (event.type) {
            "message.part.updated" -> {
                val part = event.properties?.part ?: return
                if (part.type.trim() != "tool") return
                val status = (part.state as? JsonObject)?.get("status").asText()
                if (status != "completed" && status != "error") return
                part.messageId?.let { lastMessageId = it }
                val input = (part.state as? JsonObject)?.get("input") as? JsonObject
                val detail =
                    input?.get("command").asText()
                        ?: input?.get("filePath").asText()
                        ?: input?.get("path").asText()
                        ?: input?.get("description").asText()
                if (status == "error") {
                    glanceFailures += 1
                    GlanceStore.update(failures = glanceFailures, running = true)
                }
                val label = ToolText.notificationLabel(part.tool ?: part.name, detail)
                policy = SessionAlertPolicy.toolSeen(policy, eventSession, label)
            }

            "session.next.step.ended" -> {
                val (nextPolicy, tools) =
                    SessionAlertPolicy.stepEnded(policy, eventSession, System.currentTimeMillis())
                policy = nextPolicy
                // Glance: this is the "Step 7 · 42s" the surfaces show.
                glanceStep += 1
                if (glanceSession != eventSession) {
                    glanceSession = eventSession
                    glanceStep = 1
                    glanceFailures = 0
                    glanceStartedAt = System.currentTimeMillis()
                }
                GlanceStore.update(
                    sessionId = eventSession,
                    running = true,
                    step = glanceStep,
                    elapsedMs = System.currentTimeMillis() - glanceStartedAt,
                    failures = glanceFailures,
                )
                if (tools != null) onStepDone(eventSession, tools)
            }

            "permission.asked", "permission.v2.asked", "question.asked", "question.v2.asked" -> {
                // Ambient "waiting for you": counted from the event stream, so the
                // surfaces are right without polling the request list.
                (event.properties?.requestId ?: event.properties?.part?.id)?.let { id ->
                    pendingDecisionIds += id
                    GlanceStore.update(pendingDecisions = pendingDecisionIds.size)
                }
            }

            "permission.replied", "permission.v2.replied", "question.replied", "question.rejected",
            "question.v2.replied", "question.v2.rejected",
            -> {
                (event.properties?.requestId ?: event.properties?.part?.id)?.let { id ->
                    pendingDecisionIds -= id
                    GlanceStore.update(pendingDecisions = pendingDecisionIds.size)
                }
            }
        }
    }

    private suspend fun onStepDone(
        sessionId: String,
        tools: List<String>,
    ) {
        val context = context ?: return
        val title = sessionTitle(sessionId) ?: context.getString(R.string.app_name)
        val step = policy.notifiedPerTurn[sessionId] ?: 1
        val body =
            if (tools.isEmpty()) {
                context.getString(R.string.notif_progress_step_only, step)
            } else {
                context.getString(R.string.notif_progress_text, step, tools.joinToString(", "))
            }
        Notifier.agentProgress(
            sessionId = sessionId,
            title = title,
            text = body,
            messageId = lastMessageId,
        )
    }

    private suspend fun onTurnEnded(
        sessionId: String,
        turnFailed: Boolean,
    ) {
        policy = SessionAlertPolicy.turnEnded(policy, sessionId)
        // Glance: the turn is over — clear the running line so a finished turn cannot
        // look stuck on the widget.
        if (glanceSession == sessionId || glanceSession == null) {
            glanceSession = null
            glanceStep = 0
            glanceFailures = 0
            GlanceStore.update(sessionId = sessionId, clearRunning = true)
        }
        val context = context ?: return
        val title = sessionTitle(sessionId) ?: context.getString(R.string.app_name)
        val answer = lastAssistantAnswer(sessionId)
        val base =
            when {
                turnFailed -> context.getString(R.string.notif_failed_text)
                answer.isNotBlank() -> answer
                else -> context.getString(R.string.notif_finished_text)
            }
        // A prompt may still be waiting in the queue; saying so turns the summary
        // into an actionable heads-up ("2 queued") instead of a dead end.
        val queued = queuedCount(sessionId)
        val body =
            if (queued > 0) {
                base + " · " + context.getString(R.string.notif_finished_queued, queued)
            } else {
                base
            }
        Notifier.agentFinished(
            sessionId = sessionId,
            title =
                context.getString(
                    if (turnFailed) R.string.notif_failed_title else R.string.notif_finished_title,
                    title,
                ),
            text = body,
            messageId = lastMessageId,
            // One notification per turn: the turn end can arrive twice (the status
            // frame and session.idle) and the foreground chat publishes its own.
            turnKey = "$sessionId:$lastMessageId",
        )
    }

    /** How many user prompts are still waiting in the queue (best effort). */
    private suspend fun queuedCount(sessionId: String): Int =
        try {
            api?.getInbox(sessionId)?.count { it.type == "user" } ?: 0
        } catch (e: Exception) {
            0
        }

    /** Heading of the notification: the session's own title, never the raw id. */
    private suspend fun sessionTitle(sessionId: String): String? =
        try {
            api?.getSessionFull(sessionId)?.title?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            AppLog.w(APP_LOG_TAG) { "BackgroundSessionWatcher: title fetch failed: ${e.message}" }
            null
        }

    /**
     * The answer itself (first line, capped) so the summary notification is
     * informative. An empty string only costs the wording, never the alert.
     */
    private suspend fun lastAssistantAnswer(sessionId: String): String =
        try {
            val messages = api?.getMessages(sessionId, TAIL_MESSAGES).orEmpty()
            val last = messages.lastOrNull { it.role == "assistant" }
            if (last == null) {
                ""
            } else {
                lastMessageId = last.id ?: lastMessageId
                MessageEcho
                    .firstText(last)
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .take(MAX_ANSWER_CHARS)
            }
        } catch (e: Exception) {
            AppLog.w(APP_LOG_TAG) { "BackgroundSessionWatcher: tail fetch failed: ${e.message}" }
            ""
        }

    private const val TAIL_MESSAGES = 4
    private const val MAX_ANSWER_CHARS = 180

    private val STATUS_ONLY =
        setOf(
            "session.status",
            "session.idle",
            "session.aborted",
            "session.error",
        )
}

/** Null-safe read of a JSON string field. */
private fun kotlinx.serialization.json.JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
