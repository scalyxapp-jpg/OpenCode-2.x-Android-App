package com.opencode.android.util

/**
 * Share of the context window in use, clamped to 0..100. Null when the limit is
 * unknown (0/blank) — the caller then shows a neutral placeholder instead of a
 * misleading "0%".
 */
fun contextPercent(totalTokens: Long, limit: Long?): Int? =
    limit?.takeIf { it > 0 }?.let { ((totalTokens * 100) / it).coerceIn(0, 100).toInt() }
