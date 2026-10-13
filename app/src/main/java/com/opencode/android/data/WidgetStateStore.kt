package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tiny persisted snapshot the home-screen widget renders. Kept separate from
 * [LastSessionStore] because the widget needs a short human-readable status
 * ("Working…" / "Idle") and the active model, which are live conversation
 * facts rather than "where was I".
 *
 * This is a data-layer store with no Glance dependency: the UI widget refreshes
 * itself through `SessionWidget.refresh(context)` after a write.
 */
object WidgetStateStore {
    private const val PREFS = "opencode.widget"
    private const val KEY_STATUS = "status"
    private const val KEY_MODEL = "model"
    private const val KEY_GENERATING = "generating"
    private const val KEY_GLANCE = "glance"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Mirrors the last session's running state and re-renders the widget.
     *
     * The chat screen used to be the only writer, so leaving the chat mid-turn left
     * `generating = true` persisted and the widget showed "Working…" indefinitely.
     * The home list knows the state of every session, so it keeps the widget honest.
     * No-op when nothing changed (the periodic caller runs every few seconds).
     */
    fun updateGenerating(generating: Boolean) {
        val current = snapshot()
        if (current.generating == generating) return
        save(
            status = if (generating) null else current.status,
            model = current.model,
            generating = generating,
        )
        appContext?.let { com.opencode.android.ui.widget.refreshSessionWidget(it) }
    }

    fun save(
        status: String?,
        model: String?,
        generating: Boolean,
    ) {
        prefs?.edit {
            putString(KEY_STATUS, status)
            putString(KEY_MODEL, model)
            putBoolean(KEY_GENERATING, generating)
        }
    }

    /**
     * Persists the ambient facts (widget/notification/tile read them from another
     * entry point) — the glance line is derived, never stored pre-rendered.
     */
    fun saveGlance(facts: GlanceFacts) {
        prefs?.edit {
            putString(
                KEY_GLANCE,
                kotlinx.serialization.json.Json.encodeToString(
                    GlanceFacts.serializer(),
                    facts,
                ),
            )
        }
    }

    fun glance(): GlanceFacts {
        val raw = prefs?.getString(KEY_GLANCE, null) ?: return GlanceFacts()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(GlanceFacts.serializer(), raw)
        }.getOrDefault(GlanceFacts())
    }

    data class Snapshot(
        val status: String?,
        val model: String?,
        val generating: Boolean,
    )

    fun snapshot(): Snapshot =
        Snapshot(
            status = prefs?.getString(KEY_STATUS, null),
            model = prefs?.getString(KEY_MODEL, null),
            generating = prefs?.getBoolean(KEY_GENERATING, false) == true,
        )
}
