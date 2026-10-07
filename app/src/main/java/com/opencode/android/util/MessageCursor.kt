package com.opencode.android.util

import com.opencode.android.domain.Message
import okio.ByteString.Companion.encodeUtf8

/**
 * Builds the server's `before` cursor for a message.
 *
 * The server returns its cursor in the `x-next-cursor` response header as
 * base64url of `{"id":<id>,"time":<created>}`. That exact value can also be
 * constructed locally from the oldest loaded message, which the server accepts
 * identically (verified). Used as a fallback so "load older" cannot silently
 * disappear when a proxy or an intermediate drops the header.
 *
 * Pure and Android-free so the encoding is unit-tested against a real cursor.
 */
object MessageCursor {
    fun forMessage(message: Message): String? {
        val id = message.id ?: message.info?.id ?: return null
        val time = message.time?.created ?: message.info?.time?.created ?: return null
        return """{"id":"$id","time":$time}""".encodeUtf8().base64Url()
    }
}
