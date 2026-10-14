package com.opencode.android.ui.centre

/**
 * How urgent an insight is. Ordering matters: the Centre shows the list top-down, and the
 * user reads the first line first.
 */
enum class Severity {
    /** Something is burning (a blocked or wedged turn). */
    Critical,

    /** Something will bite soon (retry loop, context nearly full, unsent prompts). */
    Warning,

    /** Worth knowing, not urgent. */
    Notice,

    /** Context: everything is fine, or plain information. */
    Info,
}

/** What a tap on an insight does. `None` = the insight is pure information. */
enum class InsightAction {
    None,
    OpenSession,
    StopSession,
    SteerQueue,
    CompactSession,
    OpenChanges,
}

/**
 * Whether the shown action can actually run.
 *
 * The screen renders the button only when this is true: a visible "Open" that does nothing (the
 * dirty-projects insight used to have exactly that) reads as a broken app.
 */
val Insight.canRun: Boolean
    get() =
        when (action) {
            InsightAction.None -> false
            InsightAction.OpenSession,
            InsightAction.OpenChanges,
            InsightAction.StopSession,
            InsightAction.SteerQueue,
            InsightAction.CompactSession,
            -> !sessionId.isNullOrBlank()
        }

/** One derived statement about the current state. Never a raw server value. */
data class Insight(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val sessionId: String? = null,
    val action: InsightAction = InsightAction.None,
)

/**
 * A project with uncommitted work.
 *
 * [targetSessionId] is the newest session of that project: "open changes" has to land somewhere,
 * and the Changes tab lives inside a session. Without a session the insight stays informational -
 * a button that cannot act must not be rendered.
 */
data class DirtyProject(
    val label: String,
    val targetSessionId: String? = null,
)

/** One running (or retrying) turn, as far as the rules need it. */
data class RunningSignal(
    val sessionId: String,
    val title: String,
    /** Unix ms of the last observed activity (message/tool update). */
    val lastActivityMs: Long = 0L,
    val retrying: Boolean = false,
    val retryAttempt: Int? = null,
    val retryMessage: String? = null,
    /** Prompts waiting behind this turn in the SERVER queue. */
    val queueDepth: Int = 0,
    /** Tokens occupying the context window (0 = unknown). */
    val tokens: Long = 0L,
    /** The model's context window (0 = unknown). */
    val tokenLimit: Long = 0L,
    val cost: Double? = null,
)

/**
 * Everything the rules read, already gathered by the caller. Keeping the rules pure means the
 * interesting part - what the app NOTICES - is unit-tested instead of only visible on a phone.
 */
data class CentreSignals(
    val running: List<RunningSignal> = emptyList(),
    /** Sessions with waiting work (permissions/questions/forms/queues). */
    val needsYou: Int = 0,
    /** Pending requests whose session no longer exists (hidden, but worth naming). */
    val orphaned: Int = 0,
    /** Prompts stuck in the local offline outbox. */
    val outboxWaiting: Int = 0,
    val costToday: Double? = null,
    /** Average cost per day over the previous window (the baseline for "expensive today"). */
    val costBaselinePerDay: Double? = null,
    val mcpTotal: Int = 0,
    val mcpConnected: Int = 0,
    /** Projects with uncommitted changes, labelled with branch and counts. */
    val dirtyProjects: List<DirtyProject> = emptyList(),
)

/**
 * The Central Intelligence: turns a pile of server values into statements a human can act on.
 *
 * Every rule is intentionally conservative. A false "stuck" alarm on a turn that is simply
 * thinking hard destroys trust in the whole panel, so the thresholds are high and each rule
 * needs positive evidence; anything unknown (no token limit, no baseline) produces no insight
 * rather than a guess.
 */
object CentreInsights {
    /** No activity for this long while running = worth a look (not a failure, hence Warning). */
    const val STUCK_AFTER_MS = 10 * 60_000L

    /** Retry #2 and up is a loop, not a hiccup. */
    const val RETRY_LOOP_ATTEMPTS = 2

    /** Above this fill level a compaction is cheaper than the next tool round. */
    const val CONTEXT_PRESSURE_PERCENT = 85

    /** Today must exceed BOTH the factor and the floor to count as a spike. */
    const val COST_SPIKE_FACTOR = 2.0

    /** Floor in USD: 2× of almost nothing is still almost nothing. */
    const val COST_SPIKE_FLOOR = 1.0

    fun evaluate(signals: CentreSignals): List<Insight> {
        val out = ArrayList<Insight>()

        signals.running.forEach { run ->
            if (run.retrying) {
                val attempt = run.retryAttempt ?: 0
                if (attempt >= RETRY_LOOP_ATTEMPTS) {
                    out +=
                        Insight(
                            id = "retry:${run.sessionId}",
                            severity = Severity.Warning,
                            title = "Retrying (attempt $attempt)",
                            detail =
                                listOfNotNull(
                                    run.title,
                                    run.retryMessage?.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                            sessionId = run.sessionId,
                            action = InsightAction.OpenSession,
                        )
                }
            } else {
                val idleFor = now_() - run.lastActivityMs
                if (run.lastActivityMs > 0L && idleFor > STUCK_AFTER_MS) {
                    out +=
                        Insight(
                            id = "stuck:${run.sessionId}",
                            severity = Severity.Warning,
                            title = "No progress for ${minutes(idleFor)} min",
                            detail = run.title,
                            sessionId = run.sessionId,
                            action = InsightAction.StopSession,
                        )
                }
            }

            if (run.queueDepth > 0) {
                out +=
                    Insight(
                        id = "queued:${run.sessionId}",
                        severity = Severity.Notice,
                        title =
                            "${run.queueDepth} prompt" + (if (run.queueDepth == 1) "" else "s") +
                                " waiting behind this turn",
                        detail = run.title,
                        sessionId = run.sessionId,
                        action = InsightAction.SteerQueue,
                    )
            }

            if (run.tokens > 0L && run.tokenLimit > 0L) {
                val percent = ((run.tokens.toDouble() * 100.0) / run.tokenLimit).toInt()
                if (percent >= CONTEXT_PRESSURE_PERCENT) {
                    out +=
                        Insight(
                            id = "context:${run.sessionId}",
                            severity = Severity.Warning,
                            title = "Context $percent% full",
                            detail = "${run.title} · ${CentrePolicy.formatTokens(
                                run.tokens,
                            )} of ${CentrePolicy.formatTokens(run.tokenLimit)} — /compact frees room",
                            sessionId = run.sessionId,
                            action = InsightAction.CompactSession,
                        )
                }
            }
        }

        if (signals.outboxWaiting > 0) {
            out +=
                Insight(
                    id = "outbox",
                    severity = Severity.Warning,
                    title =
                        "${signals.outboxWaiting} prompt" + (if (signals.outboxWaiting == 1) "" else "s") +
                            " could not be sent",
                    detail = "Waiting in the offline outbox — open the session to send them.",
                )
        }

        if (signals.costToday != null && signals.costBaselinePerDay != null && signals.costBaselinePerDay > 0.0) {
            val factor = signals.costToday / signals.costBaselinePerDay
            if (factor >= COST_SPIKE_FACTOR && signals.costToday >= COST_SPIKE_FLOOR) {
                out +=
                    Insight(
                        id = "cost",
                        severity = Severity.Notice,
                        title = "Today costs ${CentrePolicy.costLabel(signals.costToday)}",
                        detail = "${oneDecimal(
                            factor,
                        )}× the previous days' average of ${CentrePolicy.costLabel(signals.costBaselinePerDay)}",
                    )
            }
        }

        if (signals.needsYou > 0) {
            out +=
                Insight(
                    id = "needs",
                    severity = Severity.Notice,
                    title = "${signals.needsYou} session" + (if (signals.needsYou == 1) "" else "s") + " waiting for you",
                    detail = "Permissions, questions or queued prompts — see “Needs you” below.",
                )
        }

        if (signals.orphaned > 0) {
            out +=
                Insight(
                    id = "orphaned",
                    severity = Severity.Info,
                    title = "${signals.orphaned} request" + (if (signals.orphaned == 1) "" else "s") + " from deleted sessions",
                    detail = "Hidden — their session no longer exists on the server.",
                )
        }

        if (signals.mcpTotal > 0 && signals.mcpConnected < signals.mcpTotal) {
            val down = signals.mcpTotal - signals.mcpConnected
            out +=
                Insight(
                    id = "mcp",
                    severity = Severity.Notice,
                    title = "$down MCP server" + (if (down == 1) "" else "s") + " not connected",
                    detail = "$signals.mcpConnected of $signals.mcpTotal connected — Extensions → MCP.",
                )
        }

        if (signals.dirtyProjects.isNotEmpty()) {
            val targets = signals.dirtyProjects.mapNotNull { it.targetSessionId }.distinct()
            out +=
                Insight(
                    id = "dirty",
                    severity = Severity.Info,
                    title =
                        "${signals.dirtyProjects.size} project" + (if (signals.dirtyProjects.size == 1) "" else "s") +
                            " with uncommitted changes",
                    detail = signals.dirtyProjects.joinToString(", ") { it.label },
                    sessionId = targets.firstOrNull(),
                    // Only offer the action when there is a session to land in.
                    action = if (targets.isEmpty()) InsightAction.None else InsightAction.OpenChanges,
                )
        }

        if (out.isEmpty()) {
            out +=
                Insight(
                    id = "clear",
                    severity = Severity.Info,
                    title = "All clear",
                    detail =
                        if (signals.running.isEmpty()) {
                            "Nothing is running and nothing waits for you."
                        } else {
                            "Everything is running smoothly."
                        },
                )
        }

        // Severity first (the dashboard reads top-down), then anything with an action (that is
        // what the user can do something about), then by id for a stable order.
        return out.sortedWith(
            compareBy(
                { it.severity.ordinal },
                { if (it.action == InsightAction.None) 1 else 0 },
                { it.id },
            ),
        )
    }

    /** Injected into the rules so tests can pin "stuck" without waiting. */
    private var clock: () -> Long = { System.currentTimeMillis() }

    /** Test seam: evaluate as if it were [at]. */
    fun evaluationClockAt(at: Long) {
        clock = { at }
    }

    fun resetClock() {
        clock = { System.currentTimeMillis() }
    }

    private fun now_(): Long = clock.invoke()

    private fun minutes(ms: Long): Long = ms / 60_000L

    private fun oneDecimal(value: Double): String = String.format(java.util.Locale.ENGLISH, "%.1f", value)
}
