package com.opencode.android.data
import com.opencode.android.util.AppLog
import com.opencode.android.util.APP_LOG_TAG

import android.content.Context
import com.opencode.android.domain.Message
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

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
    override suspend fun read(sessionId: String): List<Message>? = withContext(Dispatchers.IO) {
        val file = fileFor(sessionId) ?: return@withContext null
        if (!file.exists()) return@withContext null
        try {
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            json.decodeFromString<List<Message>>(text).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "MessageCache read failed: ${e.message}")
            null
        }
    }

    /** Persists the newest slice of the conversation. Best-effort. */
    override suspend fun write(sessionId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
        val file = fileFor(sessionId) ?: return@withContext
        if (messages.isEmpty()) return@withContext
        try {
            // Created on demand, on the IO dispatcher (see init()).
            file.parentFile?.mkdirs()
            val tail = if (messages.size > MAX_MESSAGES) {
                messages.subList(messages.size - MAX_MESSAGES, messages.size)
            } else {
                messages
            }
            val text = json.encodeToString(
                kotlinx.serialization.serializer(),
                CacheTrimming.trim(tail),
            )
            // Byte size, not String.length: a character count is not a size and
            // undercounts non-ASCII content.
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) {
                AppLog.d(APP_LOG_TAG) { "MessageCache: skip write, ${bytes.size} bytes" }
                return@withContext
            }
            // Atomic replace: a concurrent read (cold start while a refresh is
            // writing) could otherwise observe a truncated file and silently
            // discard the cache. renameTo is atomic within the same directory.
            val tmp = File(file.parentFile, file.name + ".tmp")
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

    /** Drops every cached conversation (e.g. when switching backend). */
    override suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        try {
            (dir ?: appContext?.let { File(it.filesDir, DIR) })
                ?.listFiles()
                ?.forEach { it.delete() }
        } catch (_: Exception) {
            // best-effort
        }
    }

    suspend fun clear(sessionId: String) = withContext(Dispatchers.IO) {
        try {
            fileFor(sessionId)?.delete()
        } catch (_: Exception) {
            // best-effort
        }
    }
}
