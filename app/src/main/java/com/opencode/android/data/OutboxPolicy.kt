package com.opencode.android.data

/**
 * Rules for the **offline outbox**: prompts that could not be sent because the server
 * was unreachable are kept and delivered automatically once it is reachable again.
 *
 * Why: on a phone the connection is frequently gone for a moment — VPN handshake, lift,
 * tunnel, server restart. The old behaviour restored the text into the composer and
 * showed an error, so the user had to notice and retype/send later. Now the prompt
 * survives, in order, and the app reports when it goes out.
 *
 * Kept pure so the decisions are unit-tested: what may be queued (only connectivity
 * failures, never a real rejection), how long to wait between attempts, and how many
 * items are kept.
 */
object OutboxPolicy {
    /** Items kept per session; the oldest is dropped when the cap is hit. */
    const val MAX_ITEMS_PER_SESSION = 10

    /** Attempts before an item is parked as failed and only sent manually. */
    const val MAX_ATTEMPTS = 5

    /** First retry delay; doubles per attempt, capped. */
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L

    /**
     * Whether a failed send is a CONNECTIVITY problem (queue it and retry) or a real
     * rejection (report it, never queue it).
     *
     * An explicit `HTTP 4xx` is always a rejection — queueing it would replay the same
     * error forever. Explicit `5xx`/`429`, connectivity wording and the usual socket
     * exception classes are retryable.
     */
    fun isRetryable(
        errorClass: String?,
        message: String?,
    ): Boolean {
        val text = message?.lowercase().orEmpty()
        // The only hard veto: the server understood us and refused.
        if (HTTP_4XX.containsMatchIn(text) && !HTTP_429.containsMatchIn(text)) return false
        if (HTTP_5XX.containsMatchIn(text) || HTTP_429.containsMatchIn(text)) return true
        if (CONNECTIVITY_WORDS.any { it in text }) return true
        return errorClass in CONNECTIVITY_CLASSES
    }

    /**
     * Delay before the next attempt; `attempts` counts FAILED tries so far.
     *
     * The first retry waits [BASE_BACKOFF_MS], the second double that, and so on up to
     * [MAX_BACKOFF_MS] — doubling (attempts - 1) times. (The earlier form doubled
     * `attempts` times, so the very first retry already waited twice as long; the unit
     * test caught it.)
     */
    fun backoffMs(attempts: Int): Long {
        if (attempts <= 1) return BASE_BACKOFF_MS.coerceAtMost(MAX_BACKOFF_MS)
        var delay = BASE_BACKOFF_MS
        repeat(attempts - 1) {
            delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return delay.coerceAtMost(MAX_BACKOFF_MS)
    }

    /** True when the item may be tried now (respects the per-item backoff). */
    fun shouldAttempt(
        item: OutboxItem,
        nowMs: Long,
    ): Boolean {
        if (item.attempts >= MAX_ATTEMPTS) return false
        if (item.attempts == 0) return true
        return nowMs - item.lastAttemptAt >= backoffMs(item.attempts)
    }

    /** Trims a session's queue to the cap, dropping the oldest entries. */
    fun trim(items: List<OutboxItem>): List<OutboxItem> =
        if (items.size <= MAX_ITEMS_PER_SESSION) {
            items
        } else {
            items.takeLast(MAX_ITEMS_PER_SESSION)
        }

    private val HTTP_4XX = Regex("http 4\\d\\d")
    private val HTTP_429 = Regex("http 429")
    private val HTTP_5XX = Regex("http 5\\d\\d")

    private val CONNECTIVITY_WORDS =
        listOf(
            "unknownhost",
            "unable to resolve host",
            "no address associated",
            "connectexception",
            "connection refused",
            "failed to connect",
            "connection reset",
            "network is unreachable",
            "no route to host",
            "timeout",
            "timed out",
            "broken pipe",
            "software caused connection abort",
            "connection closed",
            "unexpected end of stream",
            "failed to connect to",
        )

    private val CONNECTIVITY_CLASSES =
        setOf(
            "java.net.UnknownHostException",
            "java.net.ConnectException",
            "java.net.SocketTimeoutException",
            "java.net.SocketException",
            "java.net.NoRouteToHostException",
            "java.io.IOException",
            "java.io.InterruptedIOException",
            "javax.net.ssl.SSLException",
            "okhttp3.internal.http2.StreamResetException",
        )
}
