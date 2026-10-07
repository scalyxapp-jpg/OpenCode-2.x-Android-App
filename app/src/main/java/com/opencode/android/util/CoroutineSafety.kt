package com.opencode.android.util

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Last-resort handler for long-lived fire-and-forget scopes.
 *
 * A coroutine launched on a scope without a handler routes an uncaught
 * exception to the thread's default handler, which on Android kills the
 * process. These scopes do optional background work (log upload, provider
 * refresh, notification replies); a single unexpected throwable there must not
 * take the whole app down. Adding this handler degrades one feature instead.
 *
 * Never used for work whose failure the caller must observe — that stays a
 * normal `try/catch` around the awaited call.
 */
val LogAndSwallow: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        AppLog.e(
            APP_LOG_TAG,
            "uncaught coroutine: ${throwable::class.java.simpleName}: ${throwable.message}",
            throwable,
        )
    }
