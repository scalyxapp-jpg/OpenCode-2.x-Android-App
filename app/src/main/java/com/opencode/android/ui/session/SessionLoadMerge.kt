package com.opencode.android.ui.session

import com.opencode.android.domain.Message

/**
 * Decides which messages survive a completed [loadSession] network fetch.
 *
 * Pure so the "never blank a populated conversation" rule is unit-tested.
 * Getting it wrong is user-visible and hard to reproduce: the server can
 * transiently return `[]` while it rebuilds a session, an oversized/failed page
 * returns empty, and the load often races a session switch. A naive "the load
 * result replaces the list" then wiped the cached tail that had just been
 * painted and left the session permanently empty.
 */
object SessionLoadMerge {
    fun resolve(
        current: List<Message>,
        loaded: List<Message>,
        sameSession: Boolean,
        merge: (List<Message>, List<Message>) -> List<Message>,
    ): List<Message> =
        when {
            // Transiently empty load: keep what is already on screen (the cached
            // tail or a concurrent refresh) instead of blanking it.
            loaded.isEmpty() && current.isNotEmpty() -> current

            // Nothing shown yet: take the page, even if it is empty.
            current.isEmpty() -> loaded

            // Same session: keep older pages the user paged in; newest wins.
            sameSession -> merge(current, loaded)

            // Different session with a real page: replace.
            else -> loaded
        }
}
