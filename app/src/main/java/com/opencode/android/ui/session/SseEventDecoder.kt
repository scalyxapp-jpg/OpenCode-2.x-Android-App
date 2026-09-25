package com.opencode.android.ui.session

import com.opencode.android.data.SseClient
import com.opencode.android.domain.Event
import com.opencode.android.domain.SseEnvelope
import kotlinx.serialization.json.Json

/**
 * Decodes an SSE `data:` frame into a domain [Event].
 *
 * The wire shape (verified against the live server) is
 * `data: {"payload":{"id","type","properties"}}`; a top-level `{type,data}`
 * shape never matched and left the stream dead. Kept pure so the decode rules
 * are unit-testable without a socket.
 */
object SseEventDecoder {
    /** Synthetic connection-state event types emitted by the SSE transport. */
    const val SSE_CONNECTED = SseClient.SSE_CONNECTED
    const val SSE_DISCONNECTED = SseClient.SSE_DISCONNECTED

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Null on malformed/unknown frames so one bad frame cannot kill the stream. */
    fun decode(data: String): Event? = try {
        json.decodeFromString<SseEnvelope>(data).payload
    } catch (_: Exception) {
        null
    }
}
