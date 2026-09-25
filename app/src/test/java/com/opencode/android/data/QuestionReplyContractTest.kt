package com.opencode.android.data

import com.opencode.android.domain.QuestionReplyRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The question reply protocol is `answers: string[][]` — one answer list per
 * question. The app used to send a single list, which mismatched multi-question
 * requests.
 */
class QuestionReplyContractTest {

    @Test
    fun `answers serialize as one list per question`() {
        val body = Json.encodeToString(
            QuestionReplyRequest.serializer(),
            QuestionReplyRequest(answers = listOf(listOf("a"), listOf("b", "c"))),
        )
        assertEquals("""{"answers":[["a"],["b","c"]]}""", body)
    }
}
