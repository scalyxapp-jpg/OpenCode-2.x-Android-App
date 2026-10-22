package com.opencode.android.ui.media

import com.opencode.android.domain.Message
import com.opencode.android.domain.Part
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a media reference is, decided from the mime type first and the extension second. */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

/**
 * Where the bytes live.
 *
 * Everything the server can hand out is reachable, because a generated artifact can
 * arrive in three completely different shapes: an inline base64 payload (images in
 * tool results), an absolute path on the serve host (screenshots, generated video,
 * audio files) or an http(s) URL.
 */
sealed interface MediaSource {
    /** `data:image/png;base64,...` — carried inside the message itself. */
    data class Inline(
        val dataUri: String,
    ) : MediaSource

    /** An http(s) URL, usually a file server or a provider link. */
    data class Remote(
        val url: String,
    ) : MediaSource

    /** A file on the serve host: absolute, or relative to the server's directory. */
    data class ServerFile(
        val path: String,
    ) : MediaSource
}

/**
 * One thumbnailable/playable item of a message.
 *
 * [key] is the cache identity: it depends only on where the bytes come from, so
 * the same screenshot referenced twice (tool content + the path in its output)
 * resolves to a single download and a single thumbnail.
 */
data class MediaRef(
    val source: MediaSource,
    val kind: MediaKind,
    val name: String? = null,
    val mime: String? = null,
) {
    val key: String
        get() =
            when (source) {
                is MediaSource.Inline -> "inline:${source.dataUri}"
                is MediaSource.Remote -> "remote:${source.url}"
                is MediaSource.ServerFile -> "server:${source.path}"
            }

    /** File name for the UI: the declared name, else the last path segment. */
    val displayName: String?
        get() =
            name?.takeIf { it.isNotBlank() }
                ?: when (source) {
                    is MediaSource.ServerFile -> {
                        source.path.substringAfterLast('/').ifBlank { null }
                    }

                    is MediaSource.Remote -> {
                        source.url
                            .substringAfterLast('/')
                            .substringBefore('?')
                            .ifBlank { null }
                    }

                    is MediaSource.Inline -> {
                        null
                    }
                }
}

/**
 * Finds every piece of media a message contains or produced.
 *
 * Pure Kotlin on purpose: the rules are the risky part (three wire shapes, two
 * markdown flavours, absolute and relative paths), so they are unit-tested instead
 * of being discovered on a device.
 */
object MediaRefs {
    /** Chrome refuses more than a handful per message anyway; a wall of images is noise. */
    const val MAX_PER_MESSAGE = 12

    /** Larger text blobs are not scanned for paths (see the text branch in collectPart). */
    private const val MAX_SCAN_CHARS = 500_000

    private val IMAGE_EXTENSIONS =
        setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv", "3gp", "avi")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus")

    private const val ALL_EXTENSIONS =
        "png|jpg|jpeg|webp|gif|bmp|heic|heif|avif|mp4|webm|mov|m4v|mkv|3gp|avi|mp3|wav|ogg|oga|m4a|flac|aac|opus"

    /** `data:` URIs are scanned first and removed from the text before anything else runs. */
    private val DATA_URI =
        Regex("""data:(image|video|audio)/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+""")

    /** `![alt](target)` / `[label](target)` — markdown, the shape agents write most. */
    private val MARKDOWN_TARGET = Regex("""!?\[[^\]]*]\(\s*<?([^)\s>]+)>?\s*\)""")

    /** `<img src="...">` and friends. */
    private val SRC_ATTRIBUTE = Regex("""\b(?:src|href)\s*=\s*["']([^"']+)["']""")

    private val HTTP_URL = Regex("""https?://[^\s"'<>)\]]+""")

    /** Absolute POSIX path, Windows drive path, `file://` URL or a relative path with a slash. */
    private val FILE_PATH =
        Regex(
            """(?:file://)?(?:[A-Za-z]:[\\/]|/|(?:\.{0,2}/)?[A-Za-z0-9_.@+-]+/)""" +
                """[^\s"'`()\[\]<>]*?\.(?:$ALL_EXTENSIONS)(?![A-Za-z0-9])""",
        )

    /**
     * Media of one message, in the order it appears.
     *
     * User turns contribute their attachments; assistant turns contribute file parts,
     * markdown images/links in their text and everything a tool result carried (a
     * `content` item of type `file`, or a path printed in its output/metadata).
     */
    fun forMessage(message: Message): List<MediaRef> {
        val out = LinkedHashMap<String, MediaRef>()

        fun add(ref: MediaRef?) {
            if (ref != null && out.size < MAX_PER_MESSAGE) out.putIfAbsent(ref.key, ref)
        }

        message.files.forEach { file ->
            add(fromAttachment(file.name, file.mime, file.data, file.source?.uri))
        }

        message.parts.forEach { part -> collectPart(part, ::add) }
        // Legacy endpoint (`content[]`) — same semantics, different envelope.
        message.content.forEach { content ->
            collectPart(
                Part(
                    id = content.id,
                    type = content.type ?: "",
                    text = content.text,
                    name = content.name,
                    tool = content.name,
                    state = content.state,
                ),
                ::add,
            )
        }
        return out.values.toList()
    }

    private fun collectPart(
        part: Part,
        add: (MediaRef?) -> Unit,
    ) {
        when (part.type.trim().lowercase()) {
            "file" -> {
                add(refFromUri(part.url ?: part.uri, part.mime, part.filename, part.name))
            }

            "tool" -> {
                part.state?.let { state -> collectFromJson(state, add, depth = 0) }
                // Tool outputs are plain text; a generated-image tool answers with a
                // path, a browser tool with a markdown screenshot link.
                part.state?.let { scanJsonStrings(it, add) }
            }

            "text" -> {
                // Streaming text grows with every tick; a scan ceiling keeps a giant
                // paste from costing a regex pass over megabytes.
                part.text
                    ?.takeIf { it.length <= MAX_SCAN_CHARS }
                    ?.let { fromText(it).forEach(add) }
            }
        }
    }

    /** `content: [{type:"file", uri, mime, name}]` is the canonical tool-result media. */
    private fun collectFromJson(
        element: JsonElement,
        add: (MediaRef?) -> Unit,
        depth: Int,
    ) {
        if (depth > 6) return
        when (element) {
            is JsonArray -> {
                element.forEach { collectFromJson(it, add, depth + 1) }
            }

            is JsonObject -> {
                val type = element["type"].asString()
                if (type == "file") {
                    add(
                        refFromUri(
                            element["uri"].asString() ?: element["url"].asString(),
                            element["mime"].asString(),
                            element["name"].asString() ?: element["filename"].asString(),
                        ),
                    )
                    return
                }
                // `input` holds what the agent ASKED for (often a path it merely
                // mentions); output/content/metadata hold what actually came back.
                element.forEach { (key, value) ->
                    if (key == "input") return@forEach
                    collectFromJson(value, add, depth + 1)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun scanJsonStrings(
        element: JsonElement,
        add: (MediaRef?) -> Unit,
    ) {
        when (element) {
            is JsonObject -> {
                // A file content item's `name` IS a path and its `uri` a data URL.
                // Scanning them as text produced a SECOND tile for the same picture
                // (seen on the device: one inline image plus a "file" tile of its own
                // name). Those items are handled by collectFromJson.
                if (element["type"].asString() == "file") return
                element.forEach { (key, value) -> if (key != "input") scanJsonStrings(value, add) }
            }

            is JsonArray -> {
                element.forEach { scanJsonStrings(it, add) }
            }

            is JsonPrimitive -> {
                if (!element.isString) return
                val text = element.content
                // Huge strings are base64 payloads: nothing to find with a path regex,
                // and running one over megabytes would be waste.
                if (text.length in 1..20_000) fromText(text).forEach(add)
            }
        }
    }

    /**
     * Media references inside plain text: inline data URIs, markdown images, html
     * `src`, bare URLs and file paths.
     */
    fun fromText(text: String): List<MediaRef> {
        val out = LinkedHashMap<String, MediaRef>()

        fun add(ref: MediaRef?) {
            if (ref != null && out.size < MAX_PER_MESSAGE) out.putIfAbsent(ref.key, ref)
        }

        var rest = text
        DATA_URI.findAll(text).forEach { match ->
            val kind = kindOfDataUri(match.value) ?: MediaKind.IMAGE
            add(MediaRef(MediaSource.Inline(match.value), kind))
        }
        if (out.isNotEmpty()) rest = DATA_URI.replace(text, " ")

        (MARKDOWN_TARGET.findAll(rest) + SRC_ATTRIBUTE.findAll(rest)).forEach { match ->
            add(refFromUri(match.groupValues[1]))
        }

        // URL spans first: a path-shaped tail of a URL (`https://host/demo.mp4`) must
        // not be read again as a relative server file — that showed the same clip
        // twice, once playable and once as a broken local file.
        val urls = HTTP_URL.findAll(rest).toList()
        val urlRanges = urls.map { it.range }
        urls.forEach { match ->
            val url = match.value.trimEnd('.', ',', ';', ':')
            if (looksLikeMedia(url)) add(MediaRef(MediaSource.Remote(url), kindOf(null, url) ?: MediaKind.IMAGE))
        }

        FILE_PATH.findAll(rest).forEach { match ->
            if (urlRanges.any { range -> match.range.first in range }) return@forEach
            val path = match.value.trimEnd('.', ',', ';', ':')
            // Belt and braces: the regex can start mid-URL on a scheme it did not see.
            if (path.startsWith("http://") || path.startsWith("https://")) return@forEach
            val kind = kindOf(null, path) ?: return@forEach
            add(MediaRef(MediaSource.ServerFile(normalisePath(path)), kind))
        }
        return out.values.toList()
    }

    /** Builds a reference from any URI-ish string (tool content, attachment source). */
    fun refFromUri(
        uri: String?,
        mime: String? = null,
        name: String? = null,
        fallbackName: String? = null,
    ): MediaRef? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = kindOf(mime, name) ?: kindOf(null, raw) ?: return null
        val source =
            when {
                raw.startsWith("data:", ignoreCase = true) -> MediaSource.Inline(raw)

                raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) -> MediaSource.Remote(raw)

                else -> MediaSource.ServerFile(normalisePath(raw))
            }
        return MediaRef(source, kind, name ?: fallbackName, mime)
    }

    /** A user attachment: inline base64 when the server kept it, else its source URI. */
    fun fromAttachment(
        name: String?,
        mime: String?,
        base64: String?,
        sourceUri: String?,
    ): MediaRef? {
        val kind = kindOf(mime, name) ?: return null
        val payload = base64?.takeIf { it.isNotBlank() }
        if (payload != null) {
            val type = mime?.substringBefore(';')?.trim()?.takeIf { it.contains('/') } ?: defaultMime(kind)
            return MediaRef(MediaSource.Inline("data:$type;base64,$payload"), kind, name, mime)
        }
        return refFromUri(sourceUri, mime, name)
    }

    /** Mime prefix wins; the extension of [nameOrPath] is the fallback. */
    fun kindOf(
        mime: String?,
        nameOrPath: String?,
    ): MediaKind? {
        val type =
            mime
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
        when {
            type.startsWith("image/") -> return MediaKind.IMAGE
            type.startsWith("video/") -> return MediaKind.VIDEO
            type.startsWith("audio/") -> return MediaKind.AUDIO
        }
        val extension = extensionOf(nameOrPath) ?: return null
        return when (extension) {
            in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            else -> null
        }
    }

    /** Extension for a name/path/URL, lowercased, query and fragment stripped. */
    fun extensionOf(nameOrPath: String?): String? {
        val raw = nameOrPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutQuery = raw.substringBefore('?').substringBefore('#')
        val last = withoutQuery.substringAfterLast('/').substringAfterLast('\\')
        val extension = last.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    }

    private fun kindOfDataUri(uri: String): MediaKind? =
        when {
            uri.startsWith("data:image/", ignoreCase = true) -> MediaKind.IMAGE
            uri.startsWith("data:video/", ignoreCase = true) -> MediaKind.VIDEO
            uri.startsWith("data:audio/", ignoreCase = true) -> MediaKind.AUDIO
            else -> null
        }

    private fun defaultMime(kind: MediaKind): String =
        when (kind) {
            MediaKind.IMAGE -> "image/png"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
        }

    private fun looksLikeMedia(url: String): Boolean = kindOf(null, url) != null

    /**
     * `file://`, `sandbox:` and `~/` are normalised to what the server's fs API
     * understands. Agents write `sandbox:/mnt/data/plot.png` when they run in a
     * sandboxed environment; the file is still right there under that path.
     */
    private fun normalisePath(raw: String): String =
        raw
            .removePrefix("file://")
            .removePrefix("sandbox:")
            .let { if (it.startsWith("~/")) it.removePrefix("~") else it }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
