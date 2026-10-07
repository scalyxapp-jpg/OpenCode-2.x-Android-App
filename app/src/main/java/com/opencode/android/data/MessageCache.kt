package com.opencode.android.data
import android.content.Context
import com.opencode.android.domain.Message
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last-known message list per session, kept on disk.
 *
 * Why: the process is killed routinely (low memory, user swipe) and a cold
 * start otherwise shows an empty conversation until the network answers — on a
 * heavy session that is several seconds of blank screen. Painting the cached
 * tail immediately makes the app feel instant; the network load then replaces
 * it with the authoritative list.
 *
 * Bounded on purpose: only the newest [MAX_MESSAGES] are stored and oversized
 * payloads are skipped, so the cache can never grow into another memory/disk
 * problem of the kind that caused the OOM crashes.
 */
object MessageCache : MessageStore {
    private const val DIR = "session_cache"
    private const val MAX_MESSAGES = 80
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    // Parsed tails kept in memory: a repeat open must not touch the disk or
    // re-parse JSON. Bounded (see MessageMemoryCache) so it cannot grow.
    private val memory = MessageMemoryCache()

    // Hoisted: fileFor() runs on every read and write.
    private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9_-]")

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        // Only the application context is captured here. Resolving filesDir and
        // creating the directory both touch the disk, and init() runs on the
        // main thread from Application.onCreate — StrictMode flagged both.
        // fileFor() is only reached from Dispatchers.IO, so the work happens
        // there.
        appContext = context.applicationContext
    }

    private fun fileFor(sessionId: String): File? {
        val context = appContext ?: return null
        val base = dir ?: File(context.filesDir, DIR).also { dir = it }
        return File(base, "${sessionId.replace(UNSAFE_FILENAME_CHARS, "_")}.json")
    }

    /** Returns the cached tail, or null when there is nothing usable. */
    override suspend fun read(sessionId: String): List<Message>? {
        // Memory first: a repeat open returns without suspending or parsing.
        memory.get(sessionId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(sessionId) ?: return@withContext null
            if (!file.exists()) return@withContext null
            try {
                // A corrupt/hand-edited cache file larger than the write cap must
                // not be read into memory: the decode would spike the heap.
                if (file.length() > MAX_BYTES) {
                    AppLog.w(APP_LOG_TAG) { "MessageCache: skip oversized read (${file.length()} B)" }
                    return@withContext null
                }
                val text = file.readText()
                if (text.isBlank()) return@withContext null
                json
                    .decodeFromString<List<Message>>(text)
                    .takeIf { it.isNotEmpty() }
                    ?.also { memory.put(sessionId, it) }
            } catch (e: OutOfMemoryError) {
                // A best-effort cache must never take the process down.
                AppLog.e(APP_LOG_TAG, "MessageCache read OOM — dropping cache")
                null
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "MessageCache read failed: ${e.message}")
                null
            }
        }
    }

    /** Persists the newest slice of the conversation. Best-effort. */
    override suspend fun write(
        sessionId: String,
        messages: List<Message>,
    ) {
        if (messages.isEmpty()) return
        val tail =
            if (messages.size > MAX_MESSAGES) {
                messages.subList(messages.size - MAX_MESSAGES, messages.size).toList()
            } else {
                messages
            }
        // Warm the memory cache immediately, so the next open is instant even
        // when the disk write below is slow or fails.
        memory.put(sessionId, tail)
        withContext(Dispatchers.IO) {
            val file = fileFor(sessionId) ?: return@withContext
            try {
                // Created on demand, on the IO dispatcher (see init()).
                file.parentFile?.mkdirs()
                // Shrink the tail until its trimmed encoding fits the cap: a
                // heavy session is then still cached (its newest messages)
                // instead of being skipped and costing a cold load on every
                // open.
                val fitted =
                    CacheTrimming.newestFitting(tail, MAX_BYTES) { candidate ->
                        json
                            .encodeToString(
                                kotlinx.serialization.serializer(),
                                CacheTrimming.trim(candidate),
                            ).toByteArray(Charsets.UTF_8)
                            .size
                    }
                val text =
                    json.encodeToString(
                        kotlinx.serialization.serializer(),
                        CacheTrimming.trim(fitted),
                    )
                // Byte size, not String.length: a character count is not a size
                // and undercounts non-ASCII content.
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) {
                    AppLog.d(APP_LOG_TAG) { "MessageCache: skip write, ${bytes.size} bytes" }
                    return@withContext
                }
                // Atomic replace: a concurrent read (cold start while a refresh
                // is writing) could otherwise observe a truncated file and
                // silently discard the cache. renameTo is atomic within the
                // same directory. Unique temp name: two overlapping writes for
                // the same session sharing one ".tmp" path could interleave
                // delete/rename and lose the newest cache update.
                val tmp = File(file.parentFile, file.name + ".tmp-" + java.util.UUID.randomUUID())
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    // Some filesystems refuse rename over an existing file.
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        AppLog.e(APP_LOG_TAG, "MessageCache: rename failed")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "MessageCache write failed: ${e.message}")
            }
        }
    }

    /** Drops every cached conversation (e.g. when switching backend). */
    override suspend fun clearAll(): Unit =
        withContext(Dispatchers.IO) {
            memory.clear()
            try {
                (dir ?: appContext?.let { File(it.filesDir, DIR) })
                    ?.listFiles()
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                // Best-effort, but never silent: a real disk failure was
                // invisible before.
                AppLog.w(APP_LOG_TAG) { "MessageCache clearAll failed: ${e.message}" }
            }
        }

    suspend fun clear(sessionId: String) =
        withContext(Dispatchers.IO) {
            memory.remove(sessionId)
            try {
                fileFor(sessionId)?.delete()
            } catch (e: Exception) {
                AppLog.w(APP_LOG_TAG) { "MessageCache clear failed: ${e.message}" }
            }
        }

    /**
     * Session ids whose cached tail contains [query] (case-insensitive), so the
     * home search can surface conversations by message content and not only by
     * title. The server has no message-search endpoint, so this is deliberately
     * local: it only sees the cached tail (newest [MAX_MESSAGES]) and is best
     * effort. Requires at least two characters to avoid scanning on every key.
     */
    suspend fun search(query: String): List<String> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            val base = dir ?: appContext?.let { File(it.filesDir, DIR) } ?: return@withContext emptyList()
            try {
                base
                    .listFiles()
                    ?.mapNotNull { file ->
                        try {
                            // Same size guard as read(): never read a pathological
                            // file into memory during a search.
                            if (file.length() > MAX_BYTES) return@mapNotNull null
                            val messages = json.decodeFromString<List<Message>>(file.readText())
                            val hit =
                                messages.any { message ->
                                    message.parts.any { part ->
                                        part.text?.contains(q, ignoreCase = true) == true
                                    }
                                }
                            if (hit) file.nameWithoutExtension else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    ?: emptyList()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "MessageCache search failed: ${e.message}")
                emptyList()
            }
        }
}
