package com.opencode.android.data

import com.opencode.android.domain.Message

/**
 * One page of a session's messages plus the cursor for the next (older) page.
 *
 * [nextCursor] is the server's opaque `x-next-cursor` value; pass it back as the
 * `before` query parameter to fetch the page immediately older than this one.
 * `null` means there is nothing older to fetch.
 */
data class MessagePage(
    val messages: List<Message>,
    val nextCursor: String?,
    /**
     * The body exceeded the decode cap, so this page has no messages — but
     * [nextCursor] was still read from the response headers. The caller can use
     * it to SKIP the oversized page and continue further back, instead of
     * concluding that there is no older history.
     */
    val tooLarge: Boolean = false,
)
