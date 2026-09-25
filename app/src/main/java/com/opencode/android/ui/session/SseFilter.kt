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
    val IGNORED_TYPES = setOf(
        "sync",
        "server.heartbeat",
        "server.connected",
        "tui.toast.show",
    )

    /** True when [event] should be delivered to the [sessionId] collector. */
    fun shouldDeliver(event: Event, sessionId: String): Boolean {
        if (event.type in IGNORED_TYPES) return false
        val evtSession = event.properties?.sessionId
        return evtSession == null || evtSession == sessionId
    }
}
