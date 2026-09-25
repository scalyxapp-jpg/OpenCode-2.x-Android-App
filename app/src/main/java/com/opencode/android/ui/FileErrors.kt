package com.opencode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a failed `/file` request into something a user can act on.
 *
 * The server wraps failures as
 *   `{"name":"UnknownError","data":{"message":"…","ref":"err_…"}}`
 * with an HTTP 500. Surfacing that message explains *why* a folder could not be
 * opened (most often "unreadable directory"); the bare status code does not.
 *
 * [serverMessage] is pure so it can be unit-tested; [friendly] adds the
 * retrofit-specific unwrapping.
 */
object FileErrors {
    private val json = Json { ignoreUnknownKeys = true }

    /** Extracts `data.message` from the server envelope, or null when absent. */
    fun serverMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val data = root["data"] as? JsonObject ?: return null
            (data["message"] as? JsonPrimitive)?.content?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun friendly(e: Exception): String {
        if (e is retrofit2.HttpException) {
            val raw = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            return serverMessage(raw) ?: "HTTP ${e.code()}"
        }
        return e.message ?: e::class.java.simpleName
    }
}
