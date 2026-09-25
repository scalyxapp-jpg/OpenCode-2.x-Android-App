package com.opencode.android.util

/**
 * Pure decision for the client-side guard auto-adopt. Kept out of the
 * ViewModel so the "once per revision, only on drift, only when enabled" rule
 * is testable without Android or network.
 */
object AutoAdoptPolicy {
    fun shouldAdopt(
        mismatch: Boolean,
        enabled: Boolean,
        revision: Long,
        alreadyAdoptedRevisions: Set<Long>,
    ): Boolean = mismatch && enabled && revision !in alreadyAdoptedRevisions
}
