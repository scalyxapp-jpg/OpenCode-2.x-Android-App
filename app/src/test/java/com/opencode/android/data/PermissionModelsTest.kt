package com.opencode.android.data

import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PermissionRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The permission prompt is the one chat flow that cannot be exercised against
 * the live server here (no pending request), so pin the wire contract:
 *  - `GET /permission` item shape (extra unknown keys must not break parsing)
 *  - the three protocol reply values the web sends ("once"/"always"/"reject")
 */
class PermissionModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun parsesPermissionRequestFromServerArray() {
        val raw = """
            [
              {
                "id": "per_abc123",
                "sessionID": "ses_xyz",
                "permission": "bash",
                "title": "Run a shell command",
                "description": "rm -rf build/",
                "metadata": { "command": "rm -rf build/" },
                "time": { "created": 1700000000000 },
                "extraUnknownField": 42
              }
            ]
        """.trimIndent()

        val list = json.decodeFromString<List<PermissionRequest>>(raw)
        assertEquals(1, list.size)
        val p = list.single()
        assertEquals("per_abc123", p.id)
        assertEquals("ses_xyz", p.sessionId)
        assertEquals("bash", p.permission)
        assertEquals("Run a shell command", p.title)
        assertEquals("rm -rf build/", p.description)
        assertEquals(1700000000000L, p.time?.created)
    }

    @Test
    fun missingOptionalFieldsParseAsNull() {
        val p = json.decodeFromString<PermissionRequest>("""{"id":"per_1","sessionID":"ses_1"}""")
        assertNull(p.permission)
        assertNull(p.title)
        assertNull(p.description)
        assertNull(p.metadata)
    }

    @Test
    fun replySerializesWithProtocolValues() {
        assertEquals("""{"reply":"once"}""", json.encodeToString(PermissionReplyRequest("once")))
        assertEquals("""{"reply":"always"}""", json.encodeToString(PermissionReplyRequest("always")))
        assertEquals("""{"reply":"reject"}""", json.encodeToString(PermissionReplyRequest("reject")))
    }
}
