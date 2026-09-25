package com.opencode.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ApiClient replaces its Retrofit instance when the backend URL changes. The
 * repository must resolve the api per call; a captured instance kept pointing
 * at the previous server after a backend switch.
 */
class ChatRepositoryApiProviderTest {

    @Test
    fun `api is resolved on every call, not captured once`() = runBlocking {
        var resolutions = 0
        val repo = ChatRepository(
            apiProvider = {
                resolutions++
                throw IllegalStateException("no api configured for this test")
            },
        )

        repeat(2) { runCatching { repo.session("ses_1") } }

        assertEquals(2, resolutions)
    }
}
