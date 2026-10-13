package com.opencode.android.data

/**
 * Decides WHEN a running turn deserves a background notification and WHAT it says.
 *
 * The requirement: while the app is not in focus and a session is running, notify
 * about intermediate steps *occasionally* and send a summary when the steps are
 * done. So:
 *
 *  - a step alert needs at least [INTERVAL_MS] since the last one for that session
 *    (a 20-step turn must not produce 20 alerts) and at most [MAX_PER_TURN] per
 *    turn,
 *  - a step that produced no tool at all is skipped once something was already
 *    reported (there would be nothing new to say),
 *  - the turn-end summary always goes out — that is the notification the user is
 *    waiting for.
 *
 * Pure and Android-free so the throttling is unit-tested.
 */
internal object SessionAlertPolicy {
    /** Minimum distance between two step alerts of the same session. */
    const val INTERVAL_MS = 45_000L

    /** Hard cap of step alerts per turn; the summary is not counted. */
    const val MAX_PER_TURN = 4

    /** Tool names kept for one step's text. */
    const val MAX_TOOL_LABELS = 4

    data class State(
        val lastNotifiedAt: Map<String, Long> = emptyMap(),
        val notifiedPerTurn: Map<String, Int> = emptyMap(),
        val pendingTools: Map<String, List<String>> = emptyMap(),
    )

    /**
     * Remembers that [label] ran during the current step. Consecutive duplicates
     * collapse (the same tool retried twice reads as one entry).
     */
    fun toolSeen(
        state: State,
        sessionId: String,
        label: String,
    ): State {
        val clean = label.trim()
        if (clean.isEmpty()) return state
        val current = state.pendingTools[sessionId].orEmpty()
        if (current.lastOrNull() == clean) return state
        return state.copy(
            pendingTools =
                state.pendingTools +
                    (sessionId to (current + clean).takeLast(MAX_TOOL_LABELS)),
        )
    }

    /**
     * A step finished. Returns the state plus the work of that step when an alert
     * is due, or `null` when it is throttled away.
     */
    fun stepEnded(
        state: State,
        sessionId: String,
        now: Long,
    ): Pair<State, List<String>?> {
        val reported = state.notifiedPerTurn[sessionId] ?: 0
        val last = state.lastNotifiedAt[sessionId] ?: 0L
        val tools = state.pendingTools[sessionId].orEmpty()
        // The step's tools are consumed either way: the next alert reports the
        // work of ITS step, not the accumulated history.
        val cleared = state.copy(pendingTools = state.pendingTools - sessionId)
        if (reported >= MAX_PER_TURN) return cleared to null
        if (last != 0L && now - last < INTERVAL_MS) return cleared to null
        if (tools.isEmpty() && reported > 0) return cleared to null
        return cleared.copy(
            lastNotifiedAt = cleared.lastNotifiedAt + (sessionId to now),
            notifiedPerTurn = cleared.notifiedPerTurn + (sessionId to (reported + 1)),
        ) to tools
    }

    /**
     * The turn is over: forget the counters so the NEXT turn notifies immediately
     * and the maps stay bounded by the number of running sessions.
     */
    fun turnEnded(
        state: State,
        sessionId: String,
    ): State =
        state.copy(
            pendingTools = state.pendingTools - sessionId,
            notifiedPerTurn = state.notifiedPerTurn - sessionId,
            lastNotifiedAt = state.lastNotifiedAt - sessionId,
        )
}
