package com.opencode.android.data

import java.util.concurrent.atomic.AtomicReference

/**
 * One-slot hand-off from a share-target intent ([Intent.ACTION_SEND]) to the
 * chat composer. [MainActivity] fills it when the app is opened via the Android
 * share sheet; the first [ChatScreen] that renders consumes it (text is dropped
 * into the composer, images become attachments).
 *
 * A single slot is enough: a share opens exactly one chat, and the value is
 * consumed on first read.
 */
object PendingShare {
    data class Shared(
        val text: String?,
        val imageUris: List<String>,
    )

    private val slot = AtomicReference<Shared?>(null)

    /**
     * Stores the incoming share. When a share is already pending (two rapid
     * share-target intents, or a share from the widget flow) the values are
     * MERGED instead of overwritten — the old `set` silently dropped the first
     * one.
     */
    fun set(
        text: String?,
        imageUris: List<String>,
    ) {
        val cleanText = text?.takeIf { it.isNotBlank() }
        if (cleanText == null && imageUris.isEmpty()) return
        slot.updateAndGet { previous ->
            if (previous == null) {
                Shared(cleanText, imageUris)
            } else {
                Shared(
                    text =
                        listOfNotNull(previous.text, cleanText)
                            .joinToString("\n")
                            .ifBlank { null },
                    imageUris = previous.imageUris + imageUris,
                )
            }
        }
    }

    /** Returns the pending share once, then clears it. */
    fun consume(): Shared? = slot.getAndSet(null)
}
