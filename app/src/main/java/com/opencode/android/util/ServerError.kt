package com.opencode.android.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Extracts the human-readable message from a server error body.
 *
 * The server answers failures as:
 *   {"name":"UnknownError","data":{"message":"Unexpected server error…"}}
 * and some routes as {"message":"…"}, {"error":{"message":"…"}} or a plain
 * string. Retrofit otherwise surfaces only "HTTP 500 Internal Server Error",
 * which tells the user nothing, so we pull out the real message. A JSON body
 * without a usable message falls back to [fallback] (the raw blob would be
 * noise); a plain-text body is kept.
 */
fun serverErrorMessage(
    raw: String?,
    fallback: String,
): String {
    val body = raw?.trim().orEmpty()
    if (body.isEmpty()) return fallback
    val message =
        runCatching {
            val root = errorJson.parseToJsonElement(body).jsonObject
            val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
            // {"error":{"message":…}} is a common shape; only then is `error`
            // itself a string.
            val errorObject = root["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
            // Candidates are tried IN ORDER and each is validated on its own: the
            // old chained `?:` picked `data["message"]` merely because the key was
            // PRESENT, so a present-but-unusable value (null, "", an object) hid
            // the valid message sitting next to it.
            sequenceOf(
                data?.get("message"),
                errorObject?.get("message"),
                root["message"],
                root["error"],
            // `JsonNull.jsonPrimitive.content` returns the STRING "null", so a
            // present-but-null field was reported as the message "null" unless it
            // is skipped explicitly.
            ).firstNotNullOfOrNull { candidate ->
                candidate
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    if (message != null) return message
    return if (body.startsWith("{")) fallback else body.take(300)
}

/**
 * Whether a failure means "this thing is GONE" rather than "it failed".
 *
 * The server answers 404 for a session, form or file that no longer exists (verified live: a
 * deleted session's pending forms still show up in `GET /api/form`, and every request for
 * that session answers 404). Callers use this to stop offering what can never open and to
 * explain it instead of printing a bare status code. 410 is included: "gone" by definition.
 */
fun isGoneError(e: Throwable): Boolean {
    val code = (e as? retrofit2.HttpException)?.code() ?: return false
    return code == 404 || code == 410
}
