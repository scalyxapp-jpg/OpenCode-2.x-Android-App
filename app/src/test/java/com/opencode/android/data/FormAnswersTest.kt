package com.opencode.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Form answers are validated against the JSON TYPE, not just the value: the server
 * rejects an array for a `string` field ("Expected string for form field: …") and a
 * label instead of an option value ("Invalid option …"). Sending everything as an array
 * surfaced in the UI as "the question has expired" while the answer never arrived.
 */
class FormAnswersTest {
    @Test
    fun `string fields take a plain string`() {
        assertEquals(JsonPrimitive("hello"), formAnswerValue("string", listOf("hello")))
    }

    @Test
    fun `multiselect fields take an array of values`() {
        assertEquals(
            JsonArray(listOf(JsonPrimitive("v1"), JsonPrimitive("v2"))),
            formAnswerValue("multiselect", listOf("v1", "v2")),
        )
    }

    @Test
    fun `numeric and boolean fields take their JSON type`() {
        assertEquals(JsonPrimitive(5.0), formAnswerValue("number", listOf("5")))
        assertEquals(JsonPrimitive(7L), formAnswerValue("integer", listOf("7")))
        assertEquals(JsonPrimitive(true), formAnswerValue("boolean", listOf("true")))
    }

    @Test
    fun `unparsable input stays a string so the server can explain`() {
        assertEquals(JsonPrimitive("abc"), formAnswerValue("number", listOf("abc")))
        assertEquals(JsonPrimitive("maybe"), formAnswerValue("boolean", listOf("maybe")))
    }

    @Test
    fun `unknown or missing types are strings`() {
        assertEquals(JsonPrimitive("x"), formAnswerValue(null, listOf("x")))
        assertEquals(JsonPrimitive("x"), formAnswerValue("external", listOf("x")))
    }
}
