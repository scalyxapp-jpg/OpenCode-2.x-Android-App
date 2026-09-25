package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The most recently used model refs, most-recent first, so the model picker can
 * offer a short "Recent" section above the provider groups. Purely a UX
 * shortcut: the ref format is the same provider-qualified `provider/model`
 * string used by the selection (`sessionModelRef`).
 */
object RecentModelsStore {
    private const val PREFS = "opencode.recent_models"
    private const val KEY = "refs"
    private const val SEP = "\n"
    private const val MAX = 8

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun record(ref: String) {
        if (ref.isBlank()) return
        val current = recent().filterNot { it == ref }
        val updated = (listOf(ref) + current).take(MAX)
        prefs?.edit { putString(KEY, updated.joinToString(SEP)) }
    }

    fun recent(): List<String> =
        prefs?.getString(KEY, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
}
