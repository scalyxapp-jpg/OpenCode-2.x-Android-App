package com.opencode.android.data
import com.opencode.android.util.AppLog
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.ReconnectPolicy

import com.opencode.android.domain.Event
import com.opencode.android.ui.session.EventSource as SessionEventSource
import com.opencode.android.ui.session.SseEventDecoder
import com.opencode.android.ui.session.SseFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streams SSE events from the OpenCode server's global event feed.
 *
 * Verified live against the server:
 *  - `/global/event` is the ONLY working SSE endpoint. The per-session
 *    `/api/session/{id}/event` hangs / returns 502, and `/session/{id}/event`
 *    serves the HTML SPA.
 *  - The wire shape is `data: {"payload":{"id","type","properties"}}`.
 *  - `/global/event` streams every session's events, so the collector filters
 *    by sessionID.
 *
 * The flow is self-healing: a closed/failed connection reconnects with
 * exponential backoff (0.5s → 30s), and synthetic `sse.connected` /
 * `sse.disconnected` events let the UI reflect connection health.
 */
object SseClient : SessionEventSource {

    /** [EventSource] adapter so consumers depend on the seam, not this object. */
    override fun stream(baseUrl: String, sessionId: String): Flow<Event> =
        events(baseUrl, sessionId)

    // One shared client: a client per reconnect leaks dispatcher threads and
    // connection pools (previously each connect built a fresh OkHttpClient).
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: never time out on read
            .retryOnConnectionFailure(true)
            .build()
    }

    fun events(baseUrl: String, sessionId: String): Flow<Event> = flow {
        var attempt = 0
        var reconnectLogs = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            try {
                connectOnce(baseUrl, sessionId).collect { emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "SseClient: stream error: ${e.message}")
            }
            if (!currentCoroutineContext().isActive) break
            // Reset the backoff only when the connection was actually STABLE.
            // Resetting on any clean close meant a server (or proxy) that
            // accepts and immediately closes produced a ~250 ms reconnect loop
            // forever — four requests per second plus constant radio wakeups.
            val livedMs = System.currentTimeMillis() - startedAt
            if (ReconnectPolicy.isStable(livedMs)) {
                attempt = 0
                reconnectLogs = 0
            }
            attempt++
            // Exponential ceiling + FULL JITTER (see RetryPolicy, which owns the
            // arithmetic so it can be unit-tested). A fixed delay makes every
            // client reconnect in the same instant after a server blip
            // ("thundering herd"); spreading each wait removes the spike.
            val delayMs = ReconnectPolicy.backoffMs(
                attempt = attempt,
                jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(),
            )
            // A flapping link must not flood logcat; log the first few only.
            if (ReconnectPolicy.shouldLogReconnect(reconnectLogs)) {
                AppLog.d(APP_LOG_TAG) {
                    "SseClient: reconnecting in ${delayMs}ms (attempt $attempt, lived ${livedMs}ms)"
                }
                reconnectLogs++
            }
            // Sleep out the backoff, but wake early when Android reports the
            // network is usable again: a cellular/wifi handoff must not cost up
            // to 30 s of dead stream. A network wake also resets the backoff.
            val wokeByNetwork = kotlinx.coroutines.withTimeoutOrNull(delayMs) {
                NetworkMonitor.available.first()
            } != null
            if (wokeByNetwork) {
                AppLog.d(APP_LOG_TAG) {
                    "SseClient: network available, reconnecting immediately"
                }
                attempt = 0
            }
        }
    }
        // Parse/transport work stays off the main thread; the collector still
        // resumes on its own dispatcher.
        .flowOn(Dispatchers.IO)
        // BOUNDED buffer with a drop-oldest safety valve.
        //
        // This used to be Channel.UNLIMITED with the comment "a tool storm
        // must not drop events". That was a silent process killer: the feed is
        // the GLOBAL event stream (every session, plus a high-frequency `sync`
        // flood), and the collector runs on Main and is busy rendering during
        // generation. Whenever it fell behind, the unbounded buffer grew
        // without limit until the app was OOM-killed — a crash with no dialog,
        // which is exactly what users saw after working in a session for a bit.
        //
        // Dropping the OLDEST event is safe here: `message.part.updated` sends
        // a full part snapshot at start and end, and the debounced
        // refreshMessages() reload reconciles the persisted history, so at
        // worst a few streamed characters are skipped — never data loss.
        .buffer(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun connectOnce(baseUrl: String, sessionId: String): Flow<Event> = callbackFlow {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/global/event")
            .build()

        AppLog.d(APP_LOG_TAG) { "SseClient: connecting ${request.url} (session=$sessionId)" }

        // Liveness watchdog. SSE requires readTimeout(0) (the stream is idle
        // between turns), which also means a half-open TCP connection — a
        // dropped cellular/wifi handoff, no FIN and no RST — is never
        // reported. The app would sit on a dead stream believing it is
        // "connected" and never see another update. The server emits
        // `server.heartbeat` regularly, so ANY inbound traffic counts as
        // alive and silence past the window forces a reconnect.
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val watchdog = launch {
            while (isActive) {
                delay(ReconnectPolicy.WATCHDOG_TICK_MS)
                val idle = System.currentTimeMillis() - lastActivity.get()
                if (ReconnectPolicy.watchdogExpired(idle)) {
                    AppLog.e(APP_LOG_TAG, "SseClient: watchdog — no events for ${idle}ms, reconnecting")
                    close(IOException("SSE watchdog: idle ${idle}ms"))
                    return@launch
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                AppLog.d(APP_LOG_TAG) { "SseClient: connection opened (${response.code})" }
                lastActivity.set(System.currentTimeMillis())
                trySend(Event(type = SSE_CONNECTED))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // Count liveness BEFORE filtering: server.heartbeat is dropped
                // below as noise but is exactly the signal the watchdog needs.
                lastActivity.set(System.currentTimeMillis())
                val event = SseEventDecoder.decode(data) ?: return
                if (!SseFilter.shouldDeliver(event, sessionId)) return
                trySend(event)
            }

            override fun onClosed(eventSource: EventSource) {
                AppLog.d(APP_LOG_TAG) { "SseClient: connection closed" }
                trySend(Event(type = SSE_DISCONNECTED))
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                AppLog.e(APP_LOG_TAG, "SseClient: ERROR ${t?.message} response=${response?.code}")
                trySend(Event(type = SSE_DISCONNECTED))
                // Always close with a throwable so the reconnect loop backs off.
                close(t ?: IOException("SSE failure response=${response?.code}"))
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            watchdog.cancel()
            eventSource.cancel()
        }
    }

    const val SSE_CONNECTED = "sse.connected"
    const val SSE_DISCONNECTED = "sse.disconnected"

    // Backoff/jitter, stability threshold, watchdog window and reconnect-log
    // cap now live in ReconnectPolicy (unit-tested).
}
