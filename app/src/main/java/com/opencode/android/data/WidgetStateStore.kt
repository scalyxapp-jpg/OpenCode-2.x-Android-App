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

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
