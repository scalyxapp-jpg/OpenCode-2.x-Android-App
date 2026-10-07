package com.opencode.android.data

import android.content.Context
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last-known home screen, kept on disk.
 *
 * Why: a cold start (or a slow/flaky link) showed an empty project list and a
 * skeleton until `GET /project` + `GET /session` answered — several seconds of
 * "nothing". Painting the cached projects and sessions immediately makes the
 * home screen feel instant; the network load then replaces it.
 *
 * Bounded on purpose: one file, at most [HomeSnapshot.MAX_CACHED_SESSIONS]
 * sessions and a hard byte cap, so it can never become another memory/disk
 * problem of the kind that caused the message OOM crashes.
 */
object HomeCache : HomeStore {
    private const val FILE_NAME = "home_cache.json"
    private const val MAX_BYTES = 2 * 1024 * 1024

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var file: File? = null

    // Parsed snapshot kept in memory so a repeat read (or the startup warm-up)
    // does not touch the disk. Invalidated by clearAll().
    @Volatile
    private var memory: HomeSnapshot? = null

    fun init(context: Context) {
        // Only the application context is captured; resolving filesDir touches
        // the disk and init() runs on the main thread from AppStartup.
        appContext = context.applicationContext
    }

    private fun fileFor(): File? {
        val context = appContext ?: return null
        return file ?: File(context.filesDir, FILE_NAME).also { file = it }
    }

    override suspend fun read(): HomeSnapshot? {
        memory?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = fileFor() ?: return@withContext null
            if (!f.exists()) return@withContext null
            try {
                // A corrupt/hand-edited file larger than the cap must not be
                // read into memory.
                if (f.length() > MAX_BYTES) {
                    AppLog.w(APP_LOG_TAG) { "HomeCache: skip oversized read (${f.length()} B)" }
                    return@withContext null
                }
                val text = f.readText()
                if (text.isBlank()) return@withContext null
                json.decodeFromString<HomeSnapshot>(text).also { memory = it }
            } catch (e: OutOfMemoryError) {
                AppLog.e(APP_LOG_TAG, "HomeCache read OOM — dropping cache")
                null
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "HomeCache read failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun write(snapshot: HomeSnapshot) {
        val bounded = snapshot.bounded()
        memory = bounded
        withContext(Dispatchers.IO) {
            val f = fileFor() ?: return@withContext
            try {
                f.parentFile?.mkdirs()
                val bytes =
                    json
                        .encodeToString(HomeSnapshot.serializer(), bounded)
                        .toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) {
                    AppLog.d(APP_LOG_TAG) { "HomeCache: skip write, ${bytes.size} bytes" }
                    return@withContext
                }
                // Atomic replace: a concurrent read (cold start while a refresh
                // writes) must not observe a truncated file. Unique temp name so
                // two overlapping writes cannot interleave.
                val tmp = File(f.parentFile, f.name + ".tmp-" + java.util.UUID.randomUUID())
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                        AppLog.e(APP_LOG_TAG, "HomeCache: rename failed")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "HomeCache write failed: ${e.message}")
            }
        }
    }

    override suspend fun clearAll(): Unit =
        withContext(Dispatchers.IO) {
            memory = null
            try {
                fileFor()?.delete()
            } catch (e: Exception) {
                AppLog.w(APP_LOG_TAG) { "HomeCache clearAll failed: ${e.message}" }
            }
        }
}
