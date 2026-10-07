package com.opencode.android.util

import com.opencode.android.domain.Message

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
    data class Row(
        val id: String?,
        val role: String?,
        val text: String,
    )

    fun isLocal(row: Row): Boolean = row.id?.startsWith(LOCAL_PREFIX) == true

    /**
     * Local rows the server has NOT echoed yet. A row is considered echoed when
     * the server list already holds a `user` row with the same text; the id
     * differs (the server assigns its own), so text is the only join key.
     */
    fun pendingEchoes(
        local: List<Row>,
        server: List<Row>,
    ): List<Row> =
        local
            .filter { isLocal(it) && it.text.isNotBlank() }
            .filterNot { candidate ->
                server.any { it.role == "user" && it.text == candidate.text }
            }

    /**
     * [messages] with EVERY optimistic echo removed. The refresh merges the
     * server page onto this base and re-adds the still-pending echoes from
     * [pendingEchoes]. Leaving the local rows in the base as well made the
     * prompt show twice — once from the base, once from the re-added pending
     * row — and that duplicate grew on every refresh.
     */
    fun withoutLocalEchoes(messages: List<Message>): List<Message> = messages.filterNot { it.id?.startsWith(LOCAL_PREFIX) == true }

    /**
     * First visible text of a message across BOTH server schemas: classic/web
     * carries `parts[].text`, the legacy `/api/session/{id}/message` schema
     * carries `content[].text` (some rows a top-level `text`). Reading only
     * `parts` made every legacy user message compare as blank, so the
     * optimistic echo never matched it and the prompt stayed on screen twice.
     */
    fun firstText(message: Message): String =
        (
            message.parts.firstOrNull { it.type.trim() == "text" }?.text
                ?: message.content.firstOrNull { it.type?.trim() == "text" }?.text
                ?: message.text
        )?.trim()
            .orEmpty()
}
