package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted list of opencode serve backends.
 *
 * Verified auth behaviour (opencode-ai 1.18.29, observed against a real
 * password-protected server):
 *  - HTTP Basic auth, username defaults to "opencode"
 *    (OPENCODE_SERVER_USERNAME, CLI --username), password is
 *    OPENCODE_SERVER_PASSWORD (CLI --password).
 *  - Unauthenticated request -> 401 with
 *      www-authenticate: Basic realm="Secure Area"
 *      {"_tag":"UnauthorizedError","message":"Authentication required"}
 */
object BackendStore {
    private const val PREFS = "opencode.backends"
    private const val KEY_LIST = "list"

    const val DEFAULT_USERNAME = "opencode"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    data class Backend(
        val url: String,
        // Free-form display label only — no DNS/hostname semantics.
        val name: String? = null,
        val username: String = DEFAULT_USERNAME,
        val password: String? = null,
        val lastUsed: Long = 0L,
    )

    private fun p(): SharedPreferences? = prefs

    fun backends(): List<Backend> {
        val raw = p()?.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url", "")
                if (url.isBlank()) return@mapNotNull null
                Backend(
                    url = url,
                    name = o.optString("name", "").ifBlank { null },
                    username = o.optString("username", DEFAULT_USERNAME)
                        .ifBlank { DEFAULT_USERNAME },
                    // Stored encrypted (SecretBox); a legacy plaintext value is
                    // returned unchanged and re-encrypted on the next save.
                    password = o.optString("password", "")
                        .ifBlank { null }
                        ?.let { SecretBox.decrypt(it) }
                        ?.ifBlank { null },
                    lastUsed = o.optLong("lastUsed", 0L),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The backend to reconnect to on launch: the most recently used one, or the
     * only/first saved entry. Returns null when nothing has ever been added.
     */
    fun mostRecent(): Backend? {
        val list = backends()
        return list.maxByOrNull { it.lastUsed } ?: list.firstOrNull()
    }

    private fun write(list: List<Backend>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("url", b.url)
                    .put("name", b.name ?: "")
                    .put("username", b.username)
                    // Never persist the Basic-auth password as plaintext.
                    .put("password", b.password?.let { SecretBox.encrypt(it) } ?: "")
                    .put("lastUsed", b.lastUsed),
            )
        }
        p()?.edit { putString(KEY_LIST, arr.toString()) }
    }

    /** Adds (or replaces) a backend URL, keeping its credentials if known. */
    fun add(url: String, name: String? = null): Backend {
        val normalized = normalize(url)
        val existing = backends().firstOrNull { it.url == normalized }
        val backend = existing?.copy(
            name = name?.trim()?.ifBlank { null } ?: existing.name,
        ) ?: Backend(url = normalized, name = name?.trim()?.ifBlank { null })
        val list = backends().filterNot { it.url == normalized } + backend
        write(list)
        return backend
    }

    /** Sets (or clears) the free-form display label of a backend. */
    fun rename(url: String, name: String?) {
        val normalized = normalize(url)
        write(
            backends().map { b ->
                if (b.url == normalized) b.copy(name = name?.trim()?.ifBlank { null }) else b
            },
        )
    }

    fun remove(url: String) {
        write(backends().filterNot { it.url == normalize(url) })
    }

    /** Stores credentials after a successful authenticated connect. */
    fun saveCredentials(url: String, username: String, password: String?) {
        val normalized = normalize(url)
        val list = backends().map { b ->
            if (b.url == normalized) {
                b.copy(
                    username = username.ifBlank { DEFAULT_USERNAME },
                    password = password?.ifBlank { null },
                    lastUsed = System.currentTimeMillis(),
                )
            } else {
                b
            }
        }
        write(list)
    }

    fun markUsed(url: String) {
        val normalized = normalize(url)
        write(backends().map { if (it.url == normalized) it.copy(lastUsed = System.currentTimeMillis()) else it })
    }

    fun find(url: String): Backend? = backends().firstOrNull { it.url == normalize(url) }

    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
