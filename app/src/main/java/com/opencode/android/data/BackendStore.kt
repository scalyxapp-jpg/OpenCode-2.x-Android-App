package com.opencode.android.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    // Every mutation is read-modify-write on the single `list` blob. Without a
    // lock two concurrent calls (e.g. saveCredentials + markUsed while
    // connecting) lose one of the updates and a backend can disappear.
    private val lock = Any()

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

    fun backends(): List<Backend> =
        synchronized(lock) {
            val raw = p()?.getString(KEY_LIST, null) ?: return@synchronized emptyList()
            try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url", "")
                    if (url.isBlank()) return@mapNotNull null
                    Backend(
                        url = url,
                        name = o.optString("name", "").ifBlank { null },
                        username =
                            o
                                .optString("username", DEFAULT_USERNAME)
                                .ifBlank { DEFAULT_USERNAME },
                        // Stored encrypted (SecretBox); a legacy plaintext value is
                        // returned unchanged and re-encrypted on the next save.
                        password =
                            o
                                .optString("password", "")
                                .ifBlank { null }
                                ?.let { SecretBox.decrypt(it) }
                                ?.ifBlank { null },
                        lastUsed = o.optLong("lastUsed", 0L),
                    )
                }
            } catch (e: Exception) {
                // A malformed blob used to vanish the whole backend list with no
                // trace. Log it so a corrupt store is diagnosable instead of
                // looking like the user's backends were deleted.
                com.opencode.android.util.AppLog.e(
                    com.opencode.android.util.APP_LOG_TAG,
                    "BackendStore: could not parse saved backends: ${e.message}",
                )
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
        synchronized(lock) {
            // Ciphertext already on disk, so a keystore hiccup cannot erase a
            // password: when encrypt() fails (fail-closed null) we keep the
            // previously stored value instead of writing "" and losing the
            // credential permanently.
            val previous = storedCipherByUrl()
            val arr = JSONArray()
            list.forEach { b ->
                val encrypted = b.password?.let { SecretBox.encrypt(it) }
                if (b.password != null && encrypted == null) {
                    com.opencode.android.util.AppLog.w(com.opencode.android.util.APP_LOG_TAG) {
                        "BackendStore: encrypt failed for ${b.url}; keeping stored password"
                    }
                }
                arr.put(
                    JSONObject()
                        .put("url", b.url)
                        .put("name", b.name ?: "")
                        .put("username", b.username)
                        // Never persist the Basic-auth password as plaintext.
                        // Only fall back to the stored ciphertext when encryption
                        // FAILED (fail-closed null). An intentionally cleared password
                        // (b.password == null) must stay cleared, otherwise the old
                        // secret came back and was sent as a stale Authorization header.
                        .put("password", if (b.password == null) "" else encrypted ?: previous[b.url] ?: "")
                        .put("lastUsed", b.lastUsed),
                )
            }
            p()?.edit { putString(KEY_LIST, arr.toString()) }
        }
    }

    /** `url -> stored ciphertext` from the current blob, for [write]'s fallback. */
    private fun storedCipherByUrl(): Map<String, String> {
        val raw = p()?.getString(KEY_LIST, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url", "")
                    val cipher = o.optString("password", "")
                    if (url.isBlank() || cipher.isBlank()) null else url to cipher
                }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Adds (or replaces) a backend URL, keeping its credentials if known. */
    fun add(
        url: String,
        name: String? = null,
    ): Backend =
        synchronized(lock) {
            val normalized = normalize(url)
            // Never persist an unusable address: it would be re-applied on every
            // start (and on every notification reply) and crash the process.
            if ((normalized.trimEnd('/') + "/").toHttpUrlOrNull() == null) {
                com.opencode.android.util.AppLog.e(
                    com.opencode.android.util.APP_LOG_TAG,
                    "BackendStore.add: refusing malformed url",
                )
                return@synchronized Backend(url = normalized)
            }
            val existing = backends().firstOrNull { it.url == normalized }
            val backend =
                existing?.copy(
                    name = name?.trim()?.ifBlank { null } ?: existing.name,
                ) ?: Backend(url = normalized, name = name?.trim()?.ifBlank { null })
            val list = backends().filterNot { it.url == normalized } + backend
            write(list)
            backend
        }

    /** Sets (or clears) the free-form display label of a backend. */
    fun rename(
        url: String,
        name: String?,
    ) {
        synchronized(lock) {
            val normalized = normalize(url)
            write(
                backends().map { b ->
                    if (b.url == normalized) b.copy(name = name?.trim()?.ifBlank { null }) else b
                },
            )
        }
    }

    fun remove(url: String) {
        synchronized(lock) {
            write(backends().filterNot { it.url == normalize(url) })
        }
    }

    /** Stores credentials after a successful authenticated connect. */
    fun saveCredentials(
        url: String,
        username: String,
        password: String?,
    ) {
        synchronized(lock) {
            val normalized = normalize(url)
            val list =
                backends().map { b ->
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
    }

    fun markUsed(url: String) {
        synchronized(lock) {
            val normalized = normalize(url)
            write(backends().map { if (it.url == normalized) it.copy(lastUsed = System.currentTimeMillis()) else it })
        }
    }

    fun find(url: String): Backend? = synchronized(lock) { backends().firstOrNull { it.url == normalize(url) } }

    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
