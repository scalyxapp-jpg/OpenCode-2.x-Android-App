package com.opencode.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON value the OpenCode server accepts as a form answer for [type].
 *
 * Verified against a live server (one request per shape):
 *  - `string` / `external` → a plain STRING ("Expected string for form field: …" if an
 *    array arrives)
 *  - `multiselect`         → an ARRAY of option VALUES ("Expected string array …"; a
 *    label instead of a value gives "Invalid option for form field: …")
 *  - `number` / `integer`  → a number (a quoted number is rejected)
 *  - `boolean`             → a boolean
 *
 * Sending everything as an array — what the client used to do — made the server reject
 * the answer, and the UI reported the question as expired while nothing was sent.
 * Non-parsable input stays a string on purpose, so the server's own message ("Expected
 * number for form field: …") reaches the user instead of a silent failure.
 */
internal fun formAnswerValue(
    type: String?,
    values: List<String>,
): JsonElement {
    val first = values.firstOrNull() ?: ""
    return when (type?.trim()?.lowercase()) {
        "multiselect" -> JsonArray(values.map { JsonPrimitive(it) })
        "number" -> first.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(first)
        "integer" -> first.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(first)
        "boolean" -> first.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(first)
        else -> JsonPrimitive(first)
    }
}
