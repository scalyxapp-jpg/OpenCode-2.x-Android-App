package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.util.AppLog
import com.opencode.android.util.APP_LOG_TAG
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
    private val json = Json {
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
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val header = authHeader
                val guardToken = guardTokenSource()
                val builder = chain.request().newBuilder()
                if (header != null) builder.header("Authorization", header)
                if (guardToken.isNotBlank()) {
                    builder.header("X-Session-Guard-Token", guardToken)
                }
                chain.proceed(builder.build())
            }
            .build()
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
    fun setBackend(url: String, username: String?, password: String?) = synchronized(configLock) {
        currentBaseUrl = url
        authHeader = basicAuth(username, password)
        _api = buildApi(url)
    }

    fun setBaseUrl(url: String) = synchronized(configLock) {
        currentBaseUrl = url
        // Clear the credentials of the previous backend: otherwise they would be
        // sent to the new host until setAuth() runs (or forever, if the new
        // backend needs no auth).
        authHeader = null
        _api = buildApi(url)
    }

    /** Sets (or clears) HTTP Basic credentials and rebuilds the API. */
    fun setAuth(username: String?, password: String?) = synchronized(configLock) {
        authHeader = basicAuth(username, password)
        _api = buildApi(currentBaseUrl)
    }

    fun hasAuth(): Boolean = authHeader != null

    private fun basicAuth(username: String?, password: String?): String? =
        if (!username.isNullOrBlank() && password != null) {
            val token = android.util.Base64.encodeToString(
                "$username:$password".toByteArray(),
                android.util.Base64.NO_WRAP,
            )
            "Basic $token"
        } else {
            null
        }

    private fun buildApi(baseUrl: String): OpenCodeApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(OpenCodeApi::class.java)
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
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun requiresAuth(url: String): Boolean? = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        try {
            val client = authProbeClient
            val req = okhttp3.Request.Builder()
                .url("${url.trimEnd('/')}/api/health")
                .build()
            client.newCall(req).execute().use { resp ->
                val needs = resp.code == 401 ||
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
     */
    suspend fun getMessagesStreamed(sessionId: String, limit: Int?): List<Message>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                api.getMessagesRaw(sessionId, limit).use { body ->
                    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                    json.decodeFromStream<List<Message>>(body.byteStream())
                }
            } catch (e: OutOfMemoryError) {
                AppLog.e(APP_LOG_TAG, "getMessagesStreamed OOM (limit=$limit) — safety net, keeping previous messages")
                null
            }
        }

    companion object {
        // Default server: Tailscale IP of the opencode serve host.
        // On a real Android device 127.0.0.1 points to the device itself, so we
        // must reach the server over the Tailscale network.
        const val DEFAULT_BASE_URL = "http://192.168.1.100:4096"

        @Volatile
        private var shared: BackendSession? = null

        /**
         * Process-wide instance shared by legacy static callers (the
         * `ApiClient` object) and Hilt injection, so both observe one
         * connection. New code should inject `BackendSession` instead.
         */
        fun shared(): BackendSession = shared ?: synchronized(this) {
            shared ?: BackendSession().also { shared = it }
        }
    }
}
