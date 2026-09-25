package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import kotlinx.coroutines.flow.Flow

/**
 * The seam for session events. The SSE client is one adapter; a synthetic
 * event list (tests) or a future transport is another. Consumer code
 * ([SessionStreamer]) depends only on this, never on OkHttp or the global
 * `/global/event` feed shape.
 */
fun interface EventSource {
    /** A cold flow of events for one session at [baseUrl]. */
    fun stream(baseUrl: String, sessionId: String): Flow<Event>
}
