package com.opencode.android.util

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Client-generated ids in the server's format (`msg_…`, `ses_…`, `prt_…`).
 *
 * The server accepts and echoes these, which is what makes optimistic UI possible: a queued
 * prompt, a compaction request or an injected decision can be matched to the message that
 * comes back. Shared instead of copied per call site, so the shape stays identical everywhere.
 *
 * The counter makes ids unique inside the same millisecond (two actions in one frame used to
 * be able to collide), and the whole thing is thread-safe because actions run from both the
 * main thread and IO coroutines.
 */
object OpenCodeIds {
    private val counter = AtomicLong(0L)

    /** `msg_ab12cd34ef01` — the shape the server validates against `^msg_`. */
    fun msg(): String = generate("msg")

    /** `prt_…` for message parts. */
    fun part(): String = generate("prt")

    fun generate(prefix: String): String {
        val stamp = java.lang.Long.toString(System.currentTimeMillis(), 16)
        val seq =
            java.lang.Long
                .toString(counter.incrementAndGet() and 0xFFFF, 16)
                .padStart(4, '0')
        val nonce = java.lang.Long.toString((Math.random() * 0xFFFFFFFFL).toLong(), 16)
        return String.format(Locale.ENGLISH, "%s_%s%s%s", prefix, stamp, seq, nonce)
    }
}
