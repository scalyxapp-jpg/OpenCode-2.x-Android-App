package com.opencode.android.ui

/**
 * Message-tail paging. The server exposes no cursor/offset pagination (verified:
 * `offset` is ignored, `before` errors), so older history can only be reached by
 * asking for a larger tail. These bounds keep the default memory footprint small
 * while still letting the user walk back.
 *
 * Pure so the boundary rule can be unit-tested.
 *
 * Sizes come from a measured heavy session (comment in ChatViewModel): the
 * endpoint returns messages in FULL — every tool output and diff — and
 * `limit=200` was ~14.4 MB of JSON, which inflated to >100 MB of live objects
 * and produced OutOfMemoryErrors. The old max of 400 could hold a few hundred
 * MB for a single session; 120 keeps one session bounded (~30-40 MB) while
 * still allowing several "load older" steps.
 */
const val MESSAGE_PAGE_STEP = 30
const val MESSAGE_LIMIT_MAX = 120

/**
 * True when the loaded page is full, which means the session very likely has
 * older messages still on the server (the endpoint returns the newest N, so
 * `size == limit` implies truncation) and the limit has not hit its cap.
 */
fun canLoadOlder(messageLimit: Int, loadedCount: Int): Boolean =
    messageLimit < MESSAGE_LIMIT_MAX && loadedCount >= messageLimit
