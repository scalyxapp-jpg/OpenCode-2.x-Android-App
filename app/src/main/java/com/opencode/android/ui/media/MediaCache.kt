package com.opencode.android.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches the bytes of a [MediaRef].
 *
 * Media never lives in the message model long enough to be decoded on demand: a
 * screenshot is an inline base64 blob or a path on the serve host, and a video has to
 * be a real file before the player can read it. So every reference is materialised
 * once into `cacheDir/media`, and both the thumbnail and the full-screen viewer work
 * from that file.
 */
object MediaCache {
    /** Larger than the text limit on purpose (videos); bounded so memory survives. */
    private const val MAX_DOWNLOAD_BYTES = 96L * 1024 * 1024

    /** Disk budget for thumbnails and full files ($MAX_DOWNLOAD_BYTES is per file). */
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024

    @Volatile
    private var root: File? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A generated video can be tens of MB over a phone uplink.
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "media")
        dir.mkdirs()
        root = dir
    }

    /** The cached file for [ref], only if it is already there (no I/O beyond a stat). */
    fun cached(ref: MediaRef): File? = targetFor(ref)?.takeIf { it.isFile && it.length() > 0 }

    /**
     * Returns a local file holding the bytes of [ref], downloading them if needed.
     *
     * [api] is the current backend session's API (server-hosted files); [baseUrl]
     * resolves server-relative URLs. Throws [IOException] with a readable message
     * when the bytes cannot be had — the UI shows that instead of an empty tile.
     */
    suspend fun ensure(
        ref: MediaRef,
        api: OpenCodeApi,
        baseUrl: String,
    ): File =
        withContext(Dispatchers.IO) {
            cached(ref)?.let { return@withContext it }
            val target = targetFor(ref) ?: throw IOException("media cache not initialised")
            val bytes =
                when (val source = ref.source) {
                    is MediaSource.Inline -> {
                        decodeInline(source.dataUri)
                    }

                    is MediaSource.Remote -> {
                        val url =
                            if (source.url.startsWith("/")) {
                                baseUrl.trimEnd('/') + source.url
                            } else {
                                source.url
                            }
                        download(url)
                    }

                    is MediaSource.ServerFile -> readServerFile(api, source.path)
                }
            if (bytes.isEmpty()) throw IOException("empty media file")
            // Write-then-rename: a half-written file must never be mistaken for a
            // cached one (the viewer would show a broken image forever).
            val temp = File(target.parentFile, target.name + ".part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("could not store media")
            }
            AppLog.d(APP_LOG_TAG) { "media cached: ${ref.displayName ?: ref.kind} ${bytes.size} B" }
            trimToBudget()
            target
        }

    /**
     * Keeps the media cache bounded (videos are the reason this matters). Oldest files
     * go first; a tile whose file vanished simply downloads again.
     */
    private fun trimToBudget(maxBytes: Long = MAX_CACHE_BYTES) {
        val dir = root ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    /** Drops every downloaded medium (Settings → clear cache). */
    fun clear() {
        val dir = root ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        MediaThumbnails.clear()
    }

    /** Bytes used on disk, for the settings row. */
    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    /**
     * Reads a file from the serve host.
     *
     * `fs/read` is scoped to the `x-opencode-directory` header, so an absolute path
     * gets its own folder as the scope (a `/tmp` screenshot is "not found" otherwise —
     * verified against the live server). A session-relative path may resolve against
     * the wrong folder when the server's working directory is not the session's; that
     * case retries once with the active location — one request, and only on failure.
     */
    private suspend fun readServerFile(
        api: OpenCodeApi,
        path: String,
    ): ByteArray {
        val directory = parentDirectoryOf(path)
        if (directory != null) return api.readFileBytes(path, directory)
        return try {
            api.readFileBytes(path, null)
        } catch (e: IOException) {
            val active = runCatching { api.getPathInfo().directory }.getOrNull()
            if (active.isNullOrBlank()) throw e else api.readFileBytes(path, active)
        }
    }

    /**
     * The folder to scope an absolute path to, or null for a session-relative path
     * (then the server's default directory applies).
     */
    private fun parentDirectoryOf(path: String): String? {
        if (!path.startsWith("/")) return null
        return path.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
    }

    private fun decodeInline(dataUri: String): ByteArray {
        val comma = dataUri.indexOf(',')
        if (comma < 0) throw IOException("malformed data URI")
        val meta = dataUri.substring(0, comma)
        val payload = dataUri.substring(comma + 1)
        return if (meta.contains(";base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            // A percent-encoded (non-base64) data URI is legal but exotic; handle it
            // rather than showing a broken tile.
            java.net.URLDecoder
                .decode(payload, "UTF-8")
                .toByteArray()
        }
    }

    private fun download(url: String): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("media HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                throw IOException("media too large (${body.contentLength() / (1024 * 1024)} MB)")
            }
            return body.byteStream().use { stream ->
                com.opencode.android.util
                    .LimitedInputStream(stream, MAX_DOWNLOAD_BYTES, "media download")
                    .use { it.readBytes() }
            }
        }
    }

    /**
     * Deterministic cache location. The extension matters: the Android players sniff
     * containers by name, and BitmapFactory prefers a real suffix.
     */
    private fun targetFor(ref: MediaRef): File? {
        val dir = root ?: return null
        val extension =
            MediaRefs.extensionOf(ref.displayName)
                ?: MediaRefs.extensionOf(
                    when (val source = ref.source) {
                        is MediaSource.ServerFile -> source.path
                        is MediaSource.Remote -> source.url
                        is MediaSource.Inline -> null
                    },
                )
                ?: extensionForMime(ref.mime)
        return File(dir, digest(ref.key) + "." + extension)
    }

    private fun extensionForMime(mime: String?): String =
        when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/flac" -> "flac"
            else -> "bin"
        }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Thumbnails for the chat strip: downscaled images and the first frame of a video.
 *
 * Everything is decoded off the main thread and kept in a small memory cache keyed by
 * the media identity, so scrolling back and forth does not re-decode or re-read.
 */
object MediaThumbnails {
    /** ~24 tiles; each is already downscaled, so this is a few MB at most. */
    private val cache = LruCache<String, Bitmap>(24)

    suspend fun load(
        ref: MediaRef,
        file: File,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(ref.key)?.let { return@withContext it }
            val bitmap =
                runCatching {
                    when (ref.kind) {
                        MediaKind.IMAGE -> decodeImage(file, MAX_EDGE)
                        MediaKind.VIDEO -> videoFrame(file, MAX_EDGE)
                        MediaKind.AUDIO -> null
                    }
                }.getOrNull()
            bitmap?.let { cache.put(ref.key, it) }
            bitmap
        }

    fun clear() = cache.evictAll()

    /**
     * Full-size decode for the viewer, bounded to [maxEdge]: a 50 MP panorama would
     * otherwise allocate ~200 MB and kill the process right when it is opened.
     */
    suspend fun fullImage(
        file: File,
        maxEdge: Int = 4096,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching { decodeImage(file, maxEdge) }.getOrNull()
        }

    private const val MAX_EDGE = 512

    private fun decodeImage(
        file: File,
        maxEdge: Int,
    ): Bitmap? {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, this)
            }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge * 2 || bounds.outHeight / sample > maxEdge * 2) {
            sample *= 2
        }
        val decoded =
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ) ?: return null
        return scaleToFit(decoded, maxEdge)
    }

    private fun videoFrame(
        file: File,
        maxEdge: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame =
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                    ?: return null
            scaleToFit(frame, maxEdge)
        } catch (e: Exception) {
            AppLog.d(APP_LOG_TAG) { "video thumbnail failed: ${e.message}" }
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleToFit(
        bitmap: Bitmap,
        maxEdge: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
