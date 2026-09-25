package com.opencode.android.util

/**
 * Keeps the optimistic user echo on screen until the server echoes it back.
 *
 * The refresh fired right after a send often lands before the message is
 * persisted, so a naive "replace the list with the server's" made the just-sent
 * bubble disappear and reappear a moment later — a flicker exactly when the
 * user is watching. Pure and Android-free so the matching rule is unit-tested.
 */
object MessageEcho {

    /** Id prefix of a locally echoed row. */
    const val LOCAL_PREFIX = "local_"

    /** Minimal view of a row: id, role and its first text part. */
    data class Row(val id: String?, val role: String?, val text: String)

    fun isLocal(row: Row): Boolean = row.id?.startsWith(LOCAL_PREFIX) == true

    /**
     * Local rows the server has NOT echoed yet. A row is considered echoed when
     * the server list already holds a `user` row with the same text; the id
     * differs (the server assigns its own), so text is the only join key.
     */
    fun pendingEchoes(local: List<Row>, server: List<Row>): List<Row> =
        local.filter { isLocal(it) && it.text.isNotBlank() }
            .filterNot { candidate ->
                server.any { it.role == "user" && it.text == candidate.text }
            }
}
