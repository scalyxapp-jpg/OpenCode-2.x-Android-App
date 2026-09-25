package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side model visibility + selection state.
 *
 * Mirrors the web's localStorage entries (verified via Playwright):
 *  - "opencode.global.dat:model" -> { user: [ {modelID, providerID, visibility} ], recent: [...], variant: {} }
 *  - "opencode.workspace.<dir>.dat:workspace:model-selection" -> { session: { <id>: {agent, model:{modelID, providerID}, variant} } }
 *
 * The server is NOT involved: switching a model and managing visibility are
 * purely client-side; the chosen model is only sent with the next prompt.
 */
object ModelVisibilityStore {
    private const val PREFS = "opencode.global.dat"
    private const val KEY_MODEL = "model"
    private const val KEY_SELECTION = "model-selection"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        // Eager: AppStartup runs this on a background dispatcher, so the disk
        // load is off the main thread and later reads are in-memory.
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences? = prefs

    /**
     * Canonical visibility key. The catalog can hand us either a bare model id
     * ("deepseek-v4-flash") or a provider-qualified one
     * ("deepseek/deepseek-v4-flash"); both must resolve to the SAME key or a
     * toggle in one screen has no effect in another. Always normalize to
     * "<provider>/<bare-model>".
     */
    fun visibilityKey(providerId: String, modelId: String): String =
        "$providerId/${bareModelId(modelId)}"

    private fun bareModelId(modelId: String): String = modelId.substringAfter('/')

    private fun readModel(): JSONObject {
        val raw = p()?.getString(KEY_MODEL, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeModel(obj: JSONObject) {
        p()?.edit { putString(KEY_MODEL, obj.toString()) }
    }

    /** Explicit visibility map: "provider/model" -> true (show) / false (hide). */
    fun visibilityMap(): Map<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        val user = readModel().optJSONArray("user") ?: return out
        for (i in 0 until user.length()) {
            val e = user.optJSONObject(i) ?: continue
            val modelId = e.optString("modelID", "")
            val providerId = e.optString("providerID", "")
            if (modelId.isEmpty() || providerId.isEmpty()) continue
            val vis = e.optString("visibility", "show")
            out[visibilityKey(providerId, modelId)] = vis != "hide"
        }
        return out
    }

    /** Models are visible unless explicitly hidden. */
    fun isVisible(providerId: String, modelId: String): Boolean =
        visibilityMap()[visibilityKey(providerId, modelId)] ?: true

    fun setVisibility(providerId: String, modelId: String, show: Boolean) {
        val bareId = bareModelId(modelId)
        val obj = readModel()
        val user = obj.optJSONArray("user") ?: JSONArray()
        val newUser = JSONArray()
        var replaced = false
        for (i in 0 until user.length()) {
            val e = user.optJSONObject(i) ?: continue
            if (e.optString("modelID") == bareId && e.optString("providerID") == providerId) {
                val updated = JSONObject()
                    .put("modelID", bareId)
                    .put("providerID", providerId)
                    .put("visibility", if (show) "show" else "hide")
                newUser.put(updated)
                replaced = true
            } else {
                newUser.put(e)
            }
        }
        if (!replaced) {
            newUser.put(
                JSONObject()
                    .put("modelID", bareId)
                    .put("providerID", providerId)
                    .put("visibility", if (show) "show" else "hide"),
            )
        }
        obj.put("user", newUser)
        writeModel(obj)
    }

    fun setProviderVisibility(providerId: String, modelIds: List<String>, show: Boolean) {
        modelIds.forEach { setVisibility(providerId, it, show) }
    }

    fun recent(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val arr = readModel().optJSONArray("recent") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val modelId = e.optString("modelID", "")
            val providerId = e.optString("providerID", "")
            if (modelId.isNotEmpty() && providerId.isNotEmpty()) out.add(providerId to modelId)
        }
        return out
    }

    /**
     * Remember the last used selection per session.
     * Web shape (localStorage "…:workspace:model-selection"):
     *   { session: { <sid>: { agent, model:{providerID, modelID}, variant } } }
     */
    fun saveSelection(
        sessionId: String,
        agent: String?,
        providerId: String?,
        modelId: String?,
        variant: String? = null,
    ) {
        val obj = try {
            JSONObject(p()?.getString(KEY_SELECTION, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val sessions = obj.optJSONObject("session") ?: JSONObject()
        val existing = sessions.optJSONObject(sessionId)
        val entry = JSONObject()
        val effectiveAgent = agent ?: existing?.optStringOrNull("agent")
        if (!effectiveAgent.isNullOrBlank()) entry.put("agent", effectiveAgent)
        if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
            entry.put(
                "model",
                JSONObject().put("modelID", modelId).put("providerID", providerId),
            )
        } else {
            existing?.optJSONObject("model")?.let { entry.put("model", it) }
        }
        val effectiveVariant = variant ?: existing?.optStringOrNull("variant")
        if (!effectiveVariant.isNullOrBlank()) entry.put("variant", effectiveVariant)
        sessions.put(sessionId, entry)
        obj.put("session", sessions)
        p()?.edit { putString(KEY_SELECTION, obj.toString()) }
    }

    data class SessionSelection(
        val agent: String?,
        val providerId: String?,
        val modelId: String?,
        val variant: String?,
    )

    fun selection(sessionId: String): SessionSelection? {
        val obj = try {
            JSONObject(p()?.getString(KEY_SELECTION, null) ?: "{}")
        } catch (_: Exception) {
            return null
        }
        val entry = obj.optJSONObject("session")?.optJSONObject(sessionId) ?: return null
        val model = entry.optJSONObject("model")
        return SessionSelection(
            agent = entry.optStringOrNull("agent"),
            providerId = model?.optStringOrNull("providerID"),
            modelId = model?.optStringOrNull("modelID"),
            variant = entry.optStringOrNull("variant"),
        )
    }
}

/**
 * `JSONObject.optString(key, null)` is ambiguous against the Android SDK's
 * platform-typed overload (it inferred `Nothing?`), so read the value only when
 * the key is present and non-null. Absent/null → null, never "".
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
