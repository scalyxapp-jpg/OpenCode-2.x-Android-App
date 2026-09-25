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
 * and some routes as {"message":"…"} or a plain string. Retrofit otherwise
 * surfaces only "HTTP 500 Internal Server Error", which tells the user nothing,
 * so we pull out the real message. A JSON body without a usable message falls
 * back to [fallback] (the raw blob would be noise); a plain-text body is kept.
 */
fun serverErrorMessage(raw: String?, fallback: String): String {
    val body = raw?.trim().orEmpty()
    if (body.isEmpty()) return fallback
    val message = runCatching {
        val root = errorJson.parseToJsonElement(body).jsonObject
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
        (data?.get("message") ?: root["message"] ?: root["error"])?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    if (message != null) return message
    return if (body.startsWith("{")) fallback else body.take(300)
}
