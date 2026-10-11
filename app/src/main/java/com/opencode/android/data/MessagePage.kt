package com.opencode.android.data

import com.opencode.android.domain.Message

/**
 * One page of a session's messages plus the cursor for the next (older) page.
 *
 * [nextCursor] is the server's OPAQUE older-page cursor (V2 returns it in the
 * body as `cursor.previous`), NOT the `x-next-cursor` header: pass it back as
 * the `before` query parameter to fetch the page immediately older than this
 * one. `null` means there is nothing older to fetch.
 */
data class MessagePage(
    val messages: List<Message>,
    val nextCursor: String?,
    /**
     * The body exceeded the decode cap, so this page has no messages AND no
     * cursor: the body was never decoded (that is what the cap protects against),
     * so an older-page walk cannot skip past it and must stop here.
     */
    val tooLarge: Boolean = false,
)
