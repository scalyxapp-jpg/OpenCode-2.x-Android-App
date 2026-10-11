package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    //
    // (baseUrl, authHeader, host) are published as ONE immutable snapshot so the
    // interceptor can never pair one host with another host's credentials. With
    // separate @Volatile fields a setBackend() landing between the interceptor's
    // host test and its authHeader read sent the new backend's request the old
    // backend's credentials.
    private class BackendConfig(
        val baseUrl: String,
        val authHeader: String?,
        val host: String?,
    )

    @Volatile
    private var config = BackendConfig(DEFAULT_BASE_URL, null, hostOf(DEFAULT_BASE_URL))

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
                // backend's Basic credentials. One atomic snapshot read closes
                // the TOCTOU the config lock alone did not.
                val current = config
                if (current.host != null && request.url.host == current.host) {
                    current.authHeader?.let { builder.header("Authorization", it) }
                    val guardToken = guardTokenSource()
                    if (guardToken.isNotBlank()) {
                        builder.header("X-Session-Guard-Token", guardToken)
                    }
                }
                chain.proceed(builder.build())
            }.build()
    }

    private val configLock = Any()

    @Volatile
    private var _api: OpenCodeApi = buildApi(DEFAULT_BASE_URL)

    val api: OpenCodeApi
        get() = _api

    fun currentBaseUrl(): String = config.baseUrl

    /** The active backend base URL. */
    val baseUrl: String get() = config.baseUrl

    /** Replaces the source of the X-Session-Guard-Token request header. */
    fun setGuardTokenProvider(provider: () -> String) {
        guardTokenSource = provider
    }

    private fun hostOf(url: String): String? =
        try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
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
        if (!isUsableUrl(url)) {
            AppLog.e(APP_LOG_TAG, "setBackend: ignoring unusable url")
            return@synchronized
        }
        config = BackendConfig(url, basicAuth(username, password), hostOf(url))
        _api = buildApi(url)
    }

    /**
     * True when [url] is a syntactically usable HTTP(S) base URL.
     *
     * Retrofit's `baseUrl()` throws `IllegalArgumentException` on a malformed
     * address, and this is reached from `Application`/receiver code with whatever
     * is in the store — a single corrupt entry used to crash the app before the
     * first frame (and on every start, because the entry stayed).
     */
    private fun isUsableUrl(url: String): Boolean =
        url.isNotBlank() && (url.trimEnd('/') + "/").toHttpUrlOrNull() != null

    fun setBaseUrl(url: String) =
        synchronized(configLock) {
            if (!isUsableUrl(url)) {
                AppLog.e(APP_LOG_TAG, "setBaseUrl: ignoring unusable url")
                return@synchronized
            }
            // Clear the credentials of the previous backend: otherwise they would be
            // sent to the new host until setAuth() runs (or forever, if the new
            // backend needs no auth).
            config = BackendConfig(url, null, hostOf(url))
            _api = buildApi(url)
        }

    /** Sets (or clears) HTTP Basic credentials and rebuilds the API. */
    fun setAuth(
        username: String?,
        password: String?,
    ) = synchronized(configLock) {
        val current = config
        config = BackendConfig(current.baseUrl, basicAuth(username, password), current.host)
        _api = buildApi(current.baseUrl)
    }

    fun hasAuth(): Boolean = config.authHeader != null

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
        // real address is set. Anything still malformed falls back here instead of
        // throwing through Application.onCreate.
        val safe =
            baseUrl
                .takeIf { isUsableUrl(it) }
                ?: UNCONFIGURED_BASE_URL.takeIf { isUsableUrl(baseUrl) } ?: UNCONFIGURED_BASE_URL
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
                    // treating it as an empty conversation. Close the error body
                    // first — the caller retries this up to 3× per page size, and
                    // each leaked stream held a pooled connection.
                    val code = response.code()
                    response.errorBody()?.close()
                    throw java.io.IOException(
                        "message list HTTP $code (limit=$limit before=${before != null})",
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
                        // The body was never decoded (that is what the cap
                        // protects against), so no cursor is available: the
                        // caller must stop here rather than skip this page.
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Structured concurrency: a cancelled caller must stay cancelled.
            throw e
        } catch (e: Exception) {
            // A transient failure (timeout, radio wake-up, server restart) must
            // NOT be cached: it used to disable guard-backed features for the
            // whole lifetime of this backend URL, silently downgrading uploads
            // to the data-URL fallback. Leaving guardProbeUrl unset makes the
            // next call probe again.
            AppLog.d(APP_LOG_TAG) { "hasGuard probe failed, not caching: ${e.message}" }
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
