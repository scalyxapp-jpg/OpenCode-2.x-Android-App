package com.opencode.android.ui.session

/**
 * When a poll tick must re-read a session's metadata.
 *
 * Why this exists: the server names a session from its first turn, but it does so **without
 * touching `time.updated`**. The poller only refreshed the meta on a changed timestamp, so the
 * generated title never arrived and the session kept its placeholder name ("new session") for the
 * rest of its life — reported by the user and reproduced against the running server: the title
 * appeared there within seconds, the app simply never looked again.
 *
 * A differing, non-blank server title is therefore its own trigger. Everything stays conservative:
 * a blank title from the server (still generating, or a build that does not name sessions) never
 * clears the one we show, and nothing is refreshed when nothing changed.
 *
 * Pure, so the rule is unit-tested.
 */
object SessionMetaPolicy {
    fun needsMetaRefresh(
        serverTitle: String?,
        currentTitle: String?,
        contentChanged: Boolean,
    ): Boolean {
        if (contentChanged) return true
        val fresh = serverTitle?.takeIf { it.isNotBlank() } ?: return false
        return fresh != currentTitle?.takeIf { it.isNotBlank() }
    }
}
