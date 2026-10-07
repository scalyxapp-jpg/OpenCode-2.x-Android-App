package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Owns the active backend connection: base URL, credentials, the Retrofit
 * instance built for them, and the session-guard token source.
 *
 * One lock guards the (baseUrl, authHeader, api) triple. Without it, two
 * threads switching backend/credentials could interleave so that the Retrofit
 * instance was built for one host while the interceptor sent the other host's
 * credentials — and a request in flight during the swap could pick up either.
 * `@Volatile` alone only guarantees visibility, not atomicity.
 */
class BackendSession(
    guardTokenProvider: () -> String = { AppSettingsStore.state.value.guardToken },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    // HTTP Basic auth (opencode serve sets it via OPENCODE_SERVER_PASSWORD).
    // Observed 401: www-authenticate: Basic realm="Secure Area".
    @Volatile
    private var authHeader: String? = null

    @Volatile
    private var guardTokenSource: () -> String = guardTokenProvider

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                // Attach credentials and the guard token ONLY when the request
                // targets the currently configured host. A request built from
                // the previous backend's Retrofit instance still carries the old
                // host in its URL; without this check it would be sent the NEW
                // backend's Basic credentials (the TOCTOU the config lock alone
                // did not close). The guard token likewise only ever goes to the
                // configured backend, never to an arbitrary host.
                if (request.url.host == currentHost()) {
                    authHeader?.let { builder.header("Authorization", it) }
                    val guardToken = guardTokenSource()
                    if (guardToken.isNotBlank()) {
                        builder.header("X-Session-Guard-Token", guardToken)
                    }
                }
                chain.proceed(builder.build())
            }.build()
    }

    /** Host of the currently configured backend, or null when unconfigured. */
    private fun currentHost(): String? =
        try {
            java.net.URI(currentBaseUrl).host
        } catch (_: Exception) {
            null
        }

    private val configLock = Any()

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var _api: OpenCodeApi = buildApi(DEFAULT_BASE_URL)

    val api: OpenCodeApi
        get() = _api

    fun currentBaseUrl(): String = currentBaseUrl

    /** The active backend base URL. */
    val baseUrl: String get() = currentBaseUrl

    /** Replaces the source of the X-Session-Guard-Token request header. */
    fun setGuardTokenProvider(provider: () -> String) {
        guardTokenSource = provider
    }

    /**
     * Atomically points the session at [url] with the given Basic credentials.
     * A blank username or null password clears the auth header, matching the
     * old setBaseUrl+setAuth sequence without an observable half-swapped state.
     */
    fun setBackend(
        url: String,
        username: String?,
        password: String?,
    ) = synchronized(configLock) {
        currentBaseUrl = url
        authHeader = basicAuth(username, password)
        _api = buildApi(url)
    }

    fun setBaseUrl(url: String) =
        synchronized(configLock) {
            currentBaseUrl = url
            // Clear the credentials of the previous backend: otherwise they would be
            // sent to the new host until setAuth() runs (or forever, if the new
            // backend needs no auth).
            authHeader = null
            _api = buildApi(url)
        }

    /** Sets (or clears) HTTP Basic credentials and rebuilds the API. */
    fun setAuth(
        username: String?,
        password: String?,
    ) = synchronized(configLock) {
        authHeader = basicAuth(username, password)
        _api = buildApi(currentBaseUrl)
    }

    fun hasAuth(): Boolean = authHeader != null

    private fun basicAuth(
        username: String?,
        password: String?,
    ): String? =
        if (!username.isNullOrBlank() && password != null) {
            val token =
                android.util.Base64.encodeToString(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP,
                )
            "Basic $token"
        } else {
            null
        }

    private fun buildApi(baseUrl: String): OpenCodeApi {
        // Retrofit requires a syntactically valid base URL even before the user
        // has configured one. The stand-in is never dialed: on first launch the
        // backend picker is the start destination, and no request runs until a
        // real address is set.
        val safe = baseUrl.ifBlank { UNCONFIGURED_BASE_URL }
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(if (safe.endsWith("/")) safe else "$safe/")
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        // V2 wire surface is adapted to the app-facing OpenCodeApi so the rest
        // of the app keeps its domain model across the V1→V2 server move.
        return OpenCodeApiAdapter(retrofit.create(OpenCodeV2Api::class.java))
    }

    /**
     * Probes /api/health without credentials to detect a password-protected
     * server (401 + WWW-Authenticate: Basic).
     *
     * Returns true when auth is needed, false when the server is reachable and
     * open, and null when the probe itself failed (unreachable).
     */
    // Reused for health probes (no per-call client/thread-pool leak).
    private val authProbeClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun requiresAuth(url: String): Boolean? =
        kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO,
        ) {
            try {
                val client = authProbeClient
                val req =
                    okhttp3.Request
                        .Builder()
                        .url("${url.trimEnd('/')}/api/info")
                        .build()
                client.newCall(req).execute().use { resp ->
                    val needs =
                        resp.code == 401 ||
                            resp.header("www-authenticate")?.contains("Basic", true) == true
                    AppLog.d(APP_LOG_TAG) {
                        "requiresAuth($url): code=${resp.code} www=${resp.header("www-authenticate")} -> $needs"
                    }
                    needs
                }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "requiresAuth($url) ERROR: ${e.message}")
                null
            }
        }

    /**
     * Streams the session message list and decodes it straight from the
     * response stream, so the whole body is never held as a String as well as
     * as objects. The OOM catch is a last-resort safety net, not a control path.
     *
     * A hard byte ceiling is enforced BEFORE and DURING the decode: the
     * endpoint re-serves the whole newest-N tail (measured ~77 MB at
     * `limit=60`), and decoding that allocates hundreds of MB. Over the cap we
     * return null so the caller keeps the messages already on screen instead of
     * dying with `OutOfMemoryError`.
     */
    suspend fun getMessagesStreamed(
        sessionId: String,
        limit: Int?,
        before: String? = null,
    ): MessagePage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = api.getMessagesRaw(sessionId, limit, before)
                if (!response.isSuccessful) {
                    // Same contract as the old ResponseBody path (Retrofit threw
                    // HttpException): surface the error so the caller can log it
                    // and fall through to a smaller page, rather than silently
                    // treating it as an empty conversation.
                    throw java.io.IOException(
                        "message list HTTP ${response.code()} (limit=$limit before=${before != null})",
                    )
                }
                val body = response.body() ?: return@withContext MessagePage(emptyList(), null)
                // The cursor for the NEXT (older) page; V2 returns it in the body
                // (`cursor.previous` = the "older" direction).
                body.use {
                    val declared = body.contentLength()
                    if (declared > MAX_MESSAGE_BODY_BYTES) {
                        AppLog.e(
                            APP_LOG_TAG,
                            "getMessagesStreamed: body $declared B > cap $MAX_MESSAGE_BODY_BYTES B " +
                                "(limit=$limit) — keeping previous messages",
                        )
                        // Keep the cursor: a caller walking older pages can skip
                        // this oversized page instead of stopping here.
                        return@withContext MessagePage(emptyList(), null, tooLarge = true)
                    }
                    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                    val page =
                        json.decodeFromStream<com.opencode.android.domain.MessageListResponse>(
                            com.opencode.android.util.LimitedInputStream(
                                body.byteStream(),
                                MAX_MESSAGE_BODY_BYTES,
                                "message list",
                            ),
                        )
                    // V2 cursors are opaque; `previous` walks toward older pages.
                    MessagePage(page.data, page.cursor?.previous)
                }
            } catch (e: OutOfMemoryError) {
                AppLog.e(APP_LOG_TAG, "getMessagesStreamed OOM (limit=$limit) — safety net, keeping previous messages")
                null
            } catch (e: com.opencode.android.util.BodyTooLargeException) {
                // Pathological history: keep what is already on screen. Other
                // failures (server error, malformed body) still propagate so the
                // caller can surface them.
                AppLog.e(APP_LOG_TAG, "getMessagesStreamed too large (limit=$limit): ${e.message}")
                null
            }
        }

    @Volatile
    private var guardProbeUrl: String? = null

    @Volatile
    private var guardAvailable = false

    /**
     * Whether the configured backend exposes the OPTIONAL session-guard proxy.
     *
     * The guard is a local convenience (pins a session's model/agent, uploads
     * attachments to the server host, streams diagnostics). A plain OpenCode
     * server does not have it — and answers `/__session_guard__/health` with the
     * SPA's HTML (HTTP 200), so a status check alone is not enough: the body is
     * parsed, and a non-JSON body is treated as "no guard".
     *
     * Probed once per backend URL and cached, so guard-only features can fall
     * back to the portable path without a request per send.
     */
    suspend fun hasGuard(): Boolean {
        val url = currentBaseUrl()
        if (guardProbeUrl == url) return guardAvailable
        return try {
            val response = api.sessionGuardHealth()
            val ok = response.isSuccessful && response.body() != null
            guardAvailable = ok
            guardProbeUrl = url
            ok
        } catch (e: Exception) {
            guardAvailable = false
            guardProbeUrl = url
            false
        }
    }

    companion object {
        /**
         * Initial backend URL: empty on purpose. The server address is whatever
         * the user enters on first launch — the app starts on the backend
         * picker when no backend has been saved. A hard-coded address made a
         * fresh install probe the developer's private host.
         */
        const val DEFAULT_BASE_URL = ""

        /**
         * Syntactically valid stand-in used only to construct the Retrofit
         * instance while no backend is configured. Never dialed.
         */
        private const val UNCONFIGURED_BASE_URL = "http://127.0.0.1/"

        /**
         * Largest message-list body decoded. A tool-heavy session returns the
         * newest messages IN FULL (every tool output/diff); measured pages run
         * 9–15 MB. The old 4 MB cap rejected them and the session rendered
         * empty in a retry loop (observed in logcat). 32 MB decodes the page and
         * `PayloadCaps.capMessages` trims it right after. The device has ~3.5 GB
         * RAM, so this is safe; [LimitedInputStream] still bounds a pathological
         * body.
         */
        const val MAX_MESSAGE_BODY_BYTES = 32L * 1024 * 1024
    }
}
