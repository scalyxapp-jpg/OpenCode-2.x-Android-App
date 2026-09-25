package com.opencode.android.util

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide channel for user-facing error messages.
 *
 * Errors used to be written to logcat only, so a failed load, save or action
 * was invisible to the user — the screen simply stayed empty. Every error path
 * that affects a workflow now posts here; the root Scaffold in
 * [OpenCodeApp] shows a snackbar, so the message appears wherever it happened.
 *
 * Messages carry a string resource (with arguments) rather than a finished
 * string so they stay localisable.
 */
object UserMessages {
    sealed interface Event {
        data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : Event
        data class Raw(val text: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // A retry/reconnect loop can fail with the same message repeatedly; only the
    // first is worth showing, and not again for a while.
    private const val DEDUPE_WINDOW_MS = 10_000L
    private val lastShownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Coroutine cancellation ("Job was cancelled") is control flow, not a
    // failure: navigation and refresh races cancel in-flight loads all the
    // time. Showing it as "Could not load the session" cried wolf so often
    // the real errors stopped registering. Dropped centrally so every
    // present and future call site is covered.
    private val CANCELLATION = Regex("job was cancell?ed", RegexOption.IGNORE_CASE)

    private fun isCancellationNoise(text: String) = CANCELLATION.containsMatchIn(text)

    fun post(@StringRes id: Int, vararg args: Any) {
        if (args.any { it is String && isCancellationNoise(it) }) return
        emit(Event.Res(id, args.toList()), "$id:${args.joinToString("|")}")
    }

    fun post(text: String) {
        if (isCancellationNoise(text)) return
        emit(Event.Raw(text), text)
    }

    private fun emit(event: Event, key: String) {
        val now = System.currentTimeMillis()
        val previous = lastShownAt[key]
        if (previous != null && now - previous < DEDUPE_WINDOW_MS) return
        lastShownAt[key] = now
        _events.tryEmit(event)
    }
}
