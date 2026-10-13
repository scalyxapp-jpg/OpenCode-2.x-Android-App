package com.opencode.android.ui.race

import com.opencode.android.data.BackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The race fan-out against a mock server: one session per member (created WITH its
 * model, so there is no window where a member runs on the wrong model), one prompt per
 * member, read-back of answer/cost and an interrupt for every member on stop.
 */
class RaceControllerTest {
    private lateinit var server: MockWebServer
    private lateinit var session: BackendSession
    private val created = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val ids = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    return when {
                        request.method == "POST" && path.startsWith("/api/session") &&
                            !path.contains("/prompt") && !path.contains("/interrupt") -> {
                            val id = "ses_${ids.incrementAndGet()}"
                            created += id
                            json("""{"data":{"id":"$id","title":"Race","status":{"type":"idle"}}}""")
                        }

                        path.contains("/prompt") -> {
                            json("""{"data":{"id":"msg_x"}}""")
                        }

                        path.contains("/interrupt") -> {
                            MockResponse().setResponseCode(204)
                        }

                        path.contains("/message") -> {
                            json(
                                """{"data":[{"id":"m1","type":"assistant",
                                   "content":[{"type":"text","text":"answer for ${path.substringAfter(
                                    "/api/session/",
                                ).substringBefore("/")}"}]}]}""",
                            )
                        }

                        path.startsWith("/api/session/") -> {
                            json(
                                """{"data":{"id":"ses","cost":0.0123,
                                   "tokens":{"input":10,"output":5},"status":{"type":"idle"}}}""",
                            )
                        }

                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
        server.start()
        session = BackendSession()
        session.setBaseUrl(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private val members =
        listOf(
            RaceMember("A", "deepseek/a", "deepseek", "a"),
            RaceMember("B", "deepseek/b", "deepseek", "b"),
        )

    @Test
    fun `a race creates one session per member, prompts each and reports their answers`() {
        runBlocking {
            var latest: RaceState? = null
            val controller =
                RaceController(
                    api = session.api,
                    scope = CoroutineScope(Dispatchers.IO),
                    onState = { latest = it },
                )
            controller.pollIntervalMs = 10

            controller.start("compare this", "/tmp/project", "build", members)

            // Wait until both members reported an answer (or fail loudly).
            withTimeoutOrNull(8_000) {
                while (latest?.finished != true) delay(20)
            }

            val state = latest
            assertTrue("race never finished: $state", state?.finished == true)
            assertEquals(2, state?.members?.size)
            val distinct = state?.members?.mapNotNull { it.sessionId }?.distinct().orEmpty()
            assertEquals("one session per member (traffic: $requests)", 2, distinct.size)
            state?.members?.forEach { member ->
                assertTrue("member without session: $member", member.sessionId != null)
                assertTrue("member without answer: $member", member.answer.startsWith("answer for"))
                assertEquals(0.0123, member.cost ?: 0.0, 0.000001)
                assertEquals(15L, member.tokens)
                assertTrue("member still running", !member.running)
            }
            // Two prompts, one per member session.
            assertEquals(2, requests.count { it.contains("POST") && it.contains("/prompt") })
        }
    }

    @Test
    fun `stopping a race interrupts every member session`() {
        runBlocking {
            var latest: RaceState? = null
            val controller =
                RaceController(
                    api = session.api,
                    scope = CoroutineScope(Dispatchers.IO),
                    onState = { latest = it },
                )
            controller.pollIntervalMs = 10
            controller.start("compare this", null, "build", members)
            // Wait until BOTH members have their session, then stop.
            withTimeoutOrNull(8_000) {
                while (latest?.members?.count { it.sessionId != null } != 2) delay(20)
            }
            controller.stop()
            withTimeoutOrNull(8_000) {
                while (requests.count { it.contains("/interrupt") } < 2) delay(20)
            }
            assertEquals("interrupts (traffic: $requests)", 2, requests.count { it.contains("/interrupt") })
            assertTrue("all members must be marked done", latest?.members?.all { !it.running } == true)
        }
    }
}
