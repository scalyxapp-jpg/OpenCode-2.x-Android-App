package com.opencode.android.util

import java.io.IOException
import java.io.InputStream

/**
 * Thrown by [LimitedInputStream] when a body passes [LimitedInputStream]'s
 * byte ceiling. Distinct from a transport/decode failure so callers can treat
 * "too large" as "keep what we have" without swallowing real errors.
 */
class BodyTooLargeException(
    message: String,
) : IOException(message)

/**
 * Passes bytes through until [maxBytes] is exceeded, then throws.
 *
 * Used to abort decoding a pathological HTTP body before it exhausts the heap.
 * The OpenCode `GET /session/{id}/message` endpoint is not truly paginated:
 * `?limit=N` re-serves the whole newest-N tail, and older messages can carry
 * full tool outputs and `summary.diffs` patches — measured at ~77 MB for
 * `limit=60` and ~101 MB for `limit=120`. The streamed decoder would read all
 * of it, so the cap turns a fatal `OutOfMemoryError` into a clean failure that
 * keeps the already-loaded messages on screen.
 */
class LimitedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val what: String,
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) account(1)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) account(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = delegate.skip(n)
        if (skipped > 0) account(skipped)
        return skipped
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun account(n: Long) {
        count += n
        if (count > maxBytes) {
            throw BodyTooLargeException("$what exceeded $maxBytes bytes — aborted to avoid OOM")
        }
    }
}
