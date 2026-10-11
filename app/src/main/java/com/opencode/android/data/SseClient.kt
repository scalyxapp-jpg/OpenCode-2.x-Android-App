package com.opencode.android.data
import com.opencode.android.domain.Event
import com.opencode.android.ui.session.SseEventDecoder
import com.opencode.android.ui.session.SseFilter
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.ReconnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.opencode.android.ui.session.EventSource as SessionEventSource

/**
 * Streams SSE events from the OpenCode server's global event feed.
 *
 * Verified live against the server:
 *  - `/api/event` is the working SSE endpoint this client dials (the V2 name of
 *    the former `/global/event` feed). The per-session routes do NOT work: the
 *    per-session event route hangs / returns 502 and the web route serves the SPA.
 *  - The wire shape is `data: {"payload":{"id","type","properties"}}`.
 *  - `/global/event` streams every session's events, so the collector filters
 *    by sessionID.
 *
 * The flow is self-healing: a closed/failed connection reconnects with
 * exponential backoff (see `RetryPolicy`: 0.25 s → 8 s, full jitter), and
 * synthetic `sse.connected` / `sse.disconnected` events let the UI reflect
 * connection health.
 */
object SseClient : SessionEventSource {
    /** [EventSource] adapter so consumers depend on the seam, not this object. */
    override fun stream(
        baseUrl: String,
        sessionId: String,
    ): Flow<Event> = events(baseUrl, sessionId)

    // One shared client: a client per reconnect leaks dispatcher threads and
    // connection pools (previously each connect built a fresh OkHttpClient).
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: never time out on read
            .retryOnConnectionFailure(true)
            .build()
    }

    fun events(
        baseUrl: String,
        sessionId: String,
    ): Flow<Event> {
        // Connection up/down events travel on their OWN channel. Sharing the data
        // buffer meant a tool storm (or any burst of large
        // `message.part.updated` frames) evicted the OLDEST entry — which at a
        // connection boundary IS `sse.connected`. Losing it skipped the resync and
        // the status re-read that recover a `session.idle` dropped during the
        // reconnect gap, so the UI could sit on "generating" for good.
        val control =
            MutableSharedFlow<Event>(
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        return merge(dataEvents(baseUrl, sessionId, control), control)
            // Parse/transport work stays off the main thread; the collector still
            // resumes on its own dispatcher.
            .flowOn(Dispatchers.IO)
    }

    private fun dataEvents(
        baseUrl: String,
        sessionId: String,
        control: MutableSharedFlow<Event>,
    ): Flow<Event> =
        flow {
            var attempt = 0
            var reconnectLogs = 0
            while (currentCoroutineContext().isActive) {
                val startedAt = System.currentTimeMillis()
                try {
                    connectOnce(baseUrl, sessionId, control).collect { emit(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Throttled: during an outage this fires once per reconnect and
                    // would otherwise flood the uploaded ring buffer.
                    val t = System.currentTimeMillis()
                    if (t - lastErrorLogAt.get() >= CONNECT_LOG_INTERVAL_MS) {
                        lastErrorLogAt.set(t)
                        AppLog.e(APP_LOG_TAG, "SseClient: stream error: ${e.message}")
                    }
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
                val delayMs =
                    ReconnectPolicy.backoffMs(
                        attempt = attempt,
                        jitter =
                            java.util.concurrent.ThreadLocalRandom
                                .current()
                                .nextLong(),
                    )
                // A flapping link must not flood logcat; log the first few only.
                if (ReconnectPolicy.shouldLogReconnect(reconnectLogs)) {
                    AppLog.d(APP_LOG_TAG) {
                        "SseClient: reconnecting in ${delayMs}ms (attempt $attempt, lived ${livedMs}ms)"
                    }
                    reconnectLogs++
                }
                // Sleep out the backoff, but wake early when Android reports the
                // network is usable again: a cellular/wifi handoff must not cost the
                // full backoff ceiling of dead stream. A network wake also resets the backoff.
                val wokeByNetwork =
                    kotlinx.coroutines.withTimeoutOrNull(delayMs) {
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
            // Dropping the OLDEST DATA event is safe here: `message.part.updated`
            // sends a full part snapshot at start and end, and the debounced
            // refreshMessages() reload reconciles the persisted history, so at
            // worst a few streamed characters are skipped — never data loss.
            // Control events are NOT in this buffer (see [events]).
            //
            // Capacity is deliberately small (256, down from 1024): a
            // `message.part.updated` carries the FULL part, so a tool storm can put
            // multi-megabyte snapshots in the buffer. 1024 slots of those is itself
            // hundreds of MB; 256 still absorbs a normal burst and drops the tail
            // under a pathological one.
            .buffer(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun connectOnce(
        baseUrl: String,
        sessionId: String,
        control: MutableSharedFlow<Event>,
    ): Flow<Event> =
        callbackFlow {
            val request =
                Request
                    .Builder()
                    .url("${baseUrl.trimEnd('/')}/api/event")
                    .build()

            // The per-attempt "connecting" line is a debug aid, but during a
            // long outage the reconnect loop would emit one per attempt and the
            // ring buffer is uploaded to the host — thousands of identical lines
            // (seen in the device log). Log at most once per window.
            val now = System.currentTimeMillis()
            if (now - lastConnectLogAt.get() >= CONNECT_LOG_INTERVAL_MS) {
                lastConnectLogAt.set(now)
                AppLog.d(APP_LOG_TAG) { "SseClient: connecting ${request.url} (session=$sessionId)" }
            }

            // Liveness watchdog. SSE requires readTimeout(0) (the stream is idle
            // between turns), which also means a half-open TCP connection — a
            // dropped cellular/wifi handoff, no FIN and no RST — is never
            // reported. The app would sit on a dead stream believing it is
            // "connected" and never see another update. OpenCode 2.x sends a
            // `: heartbeat` COMMENT on an idle stream (not a `server.heartbeat`
            // event), so liveness is reset by ANY inbound LINE — the stock
            // okhttp-sse listener drops comments, which would have made the
            // watchdog reconnect every window on an idle session.
            val lastActivity =
                java.util.concurrent.atomic
                    .AtomicLong(System.currentTimeMillis())

            val call = client.newCall(request)
            // Everything after execute() must be able to close the response: an
            // exception while deriving source/watchdog (or before pump's finally)
            // otherwise leaked the connection.
            var response: okhttp3.Response? = null
            try {
                val executed = call.execute()
                response = executed
                if (!executed.isSuccessful) {
                    throw IOException("SSE connect failed: HTTP ${executed.code}")
                }
                val responseBody =
                    executed.body ?: throw IOException("SSE connect: empty body")
                val source = responseBody.source().buffer()

                val watchdog =
                    launch {
                        while (isActive) {
                            delay(ReconnectPolicy.WATCHDOG_TICK_MS)
                            val idle = System.currentTimeMillis() - lastActivity.get()
                            if (ReconnectPolicy.watchdogExpired(idle)) {
                                AppLog.e(APP_LOG_TAG, "SseClient: watchdog — no traffic for ${idle}ms, reconnecting")
                                close(IOException("SSE watchdog: idle ${idle}ms"))
                                return@launch
                            }
                        }
                    }

                // The blocking line read must not run on the collector's thread.
                val pump =
                    launch(Dispatchers.IO) {
                        try {
                            // Control events go on their own channel so a data storm
                            // cannot evict them from the bounded buffer.
                            control.tryEmit(Event(type = SSE_CONNECTED))
                            while (isActive) {
                                val line = source.readUtf8Line() ?: break
                                // Any line (including `: heartbeat`) proves liveness.
                                lastActivity.set(System.currentTimeMillis())
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                // A part snapshot can carry a multi-megabyte tool
                                // output; decoding it allocates a second full copy.
                                // Drop the oversized frame — the capped persisted
                                // message reloads it via reconcile, so nothing is lost.
                                if (data.length > MAX_SSE_FRAME_CHARS) {
                                    AppLog.w(APP_LOG_TAG) {
                                        "SseClient: dropping oversized frame (${data.length} chars)"
                                    }
                                    continue
                                }
                                val event = SseEventDecoder.decode(data) ?: continue
                                if (!SseFilter.shouldDeliver(event, sessionId)) continue
                                trySend(event)
                            }
                            control.tryEmit(Event(type = SSE_DISCONNECTED))
                        } catch (t: Throwable) {
                            control.tryEmit(Event(type = SSE_DISCONNECTED))
                            close(t)
                        } finally {
                            response?.close()
                        }
                        close()
                    }

                awaitClose {
                    watchdog.cancel()
                    pump.cancel()
                    call.cancel()
                }
            } catch (t: Throwable) {
                // Close the response for ANY failure before the pump took over
                // (non-2xx, missing body, a throw deriving the source): previously
                // the connection stayed open until the GC/pool timeout.
                response?.close()
                throw t
            }
        }

    const val SSE_CONNECTED = "sse.connected"
    const val SSE_DISCONNECTED = "sse.disconnected"

    /**
     * Largest SSE `data:` frame (in chars) that is decoded. ~1.5 M chars is a
     * ~3 MB JSON payload; anything larger is a pathological tool-output
     * snapshot whose decode would spike the heap. Such a frame is dropped and
     * recovered from the (capped) persisted message on the next reconcile.
     */
    const val MAX_SSE_FRAME_CHARS = 1_500_000

    /** Min gap between repeated "connecting"/"stream error" log lines (ms). */
    private const val CONNECT_LOG_INTERVAL_MS = 60_000L

    private val lastConnectLogAt =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    private val lastErrorLogAt =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    // Backoff/jitter, stability threshold, watchdog window and reconnect-log
    // cap now live in ReconnectPolicy (unit-tested).
}
