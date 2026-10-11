package com.opencode.android.ui

/**
 * LEGACY helper: kept only because its unit test pins the boundary rule; it has NO
 * production caller any more.
 *
 * The sentence below used to claim "the server exposes no cursor pagination" — that is
 * outdated. The real "load older" path walks the opaque `cursor.previous` value
 * (see [com.opencode.android.data.MessagePage] and `util/MessageCursor`). Do not build
 * new paging on this file.
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
