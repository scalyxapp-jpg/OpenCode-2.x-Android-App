package com.opencode.android.ui.session

import com.opencode.android.domain.Event

/**
 * Pure fold of the global `/global/event` feed into the session-list running
 * indicators.
 *
 * The server broadcasts `session.status` (`busy`/`retry`/`idle`) and
 * `session.idle` for EVERY session, no matter which client (this app, the web,
 * the TUI) started the turn. Consuming those events is therefore the only way
 * to show a session as running when it was started elsewhere, and it flips the
 * badge the instant the turn starts or ends instead of waiting for the next
 * poll. Transport-free so it is unit-testable.
 *
 * Returns `null` when the event does not affect the running sets, so the caller
 * can skip a redundant state write (and the recomposition it would cause).
 */
object SessionStatusReducer {
    fun reduce(
        running: Set<String>,
        retrying: Set<String>,
        event: Event,
    ): Pair<Set<String>, Set<String>>? {
        val sessionId = event.properties?.sessionId ?: return null
        return when (event.type) {
            "session.idle" -> {
                (running - sessionId) to (retrying - sessionId)
            }

            "session.status" -> {
                when (event.properties?.status?.type) {
                    "busy" -> (running + sessionId) to (retrying - sessionId)

                    // A retry is a running turn that is being re-attempted; the
                    // row renders "retrying", so it must not stay in `running`.
                    "retry" -> (running - sessionId) to (retrying + sessionId)

                    "idle" -> (running - sessionId) to (retrying - sessionId)

                    else -> null
                }
            }

            else -> {
                null
            }
        }
    }
}
