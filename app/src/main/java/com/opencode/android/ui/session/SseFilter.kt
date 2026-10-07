package com.opencode.android.ui.session

import com.opencode.android.domain.Event

/**
 * Decides whether a raw event belongs to the active session.
 *
 * `/global/event` streams every session plus high-frequency broadcasts the app
 * never acts on. Filtering before the buffer (not just in the collector) keeps
 * unrelated traffic from costing buffer slots and CPU while the UI is busy.
 */
object SseFilter {
    /** High-frequency broadcasts the app never acts on. */
    val IGNORED_TYPES =
        setOf(
            "sync",
            "server.heartbeat",
            "server.connected",
            "tui.toast.show",
        )

    /**
     * Events that legitimately carry no session id but still concern the active
     * session. Every OTHER null-session event is a global broadcast that may
     * belong to a different session, so delivering it to whichever session is
     * open caused spurious cross-session reloads/VCS fetches.
     */
    val GLOBAL_BROADCAST_TYPES =
        setOf(
            // Synthetic connection events emitted by SseClient itself: they
            // carry no session id but the streamer must see them to resync.
            "sse.connected",
            "sse.disconnected",
            "permission.asked",
            "permission.replied",
            "question.asked",
            "question.v2.asked",
            "question.replied",
            "question.rejected",
        )

    /**
     * Running-state events the global (home list) consumer needs for EVERY
     * session. Without this a global collector would also be flooded with every
     * other session's message traffic, whose bursts can drop the status events
     * from the bounded buffer.
     */
    val STATUS_TYPES = setOf("session.status", "session.idle")

    /** True when [event] should be delivered to the [sessionId] collector. */
    fun shouldDeliver(
        event: Event,
        sessionId: String,
    ): Boolean {
        if (event.type in IGNORED_TYPES) return false
        val evtSession = event.properties?.sessionId
        // An empty sessionId marks a GLOBAL consumer (the home list tracking
        // running badges). It wants every session's status, not the message
        // traffic of sessions it is not showing.
        if (sessionId.isEmpty()) {
            return (evtSession != null && event.type in STATUS_TYPES) ||
                event.type in GLOBAL_BROADCAST_TYPES
        }
        return if (evtSession != null) {
            evtSession == sessionId
        } else {
            // Only genuinely global broadcasts; a session-less `session.updated`
            // / `session.diff` belongs to some other session and must not drive
            // the active one.
            event.type in GLOBAL_BROADCAST_TYPES
        }
    }
}
