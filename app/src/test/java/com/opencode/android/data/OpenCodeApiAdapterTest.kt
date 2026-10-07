package com.opencode.android.data

import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PromptAsyncModel
import com.opencode.android.domain.PromptAsyncPart
import com.opencode.android.domain.PromptAsyncRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Pins the V1→V2 request/response mapping in [OpenCodeApiAdapter].
 *
 * The adapter is the single place the app talks 2.x, so these assertions guard
 * the paths, the `x-opencode-directory` header, the prompt body shape and the
 * response mapping that the UI depends on.
 */
class OpenCodeApiAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OpenCodeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
        val v2 =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenCodeV2Api::class.java)
        api = OpenCodeApiAdapter(v2)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getProjectSessions hits api session and resolves location directory`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":[{"id":"ses_1","title":"T","location":{"directory":"/home/u/p"}}],"cursor":{}}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val sessions = api.getProjectSessions("/home/u/p", roots = true, limit = 20)

            assertEquals(1, sessions.size)
            assertEquals("/home/u/p", sessions.first().directory)
            // Web parity: global fetch (limit=5000, parentID=null), grouped
            // client-side by location.directory — no directory scoping header.
            val request = server.takeRequest()
            assertEquals("/api/session?limit=5000&order=desc&parentID=null", request.path)
            assertEquals(null, request.getHeader("x-opencode-directory"))
        }

    @Test
    fun `getSessionStatuses maps running to busy`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody("""{"data":{"ses_1":{"type":"running"}}}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val statuses = api.getSessionStatuses("/home/u/p")

            assertEquals("busy", statuses["ses_1"]?.type)
            assertEquals("/api/session/active", server.takeRequest().path)
        }

    @Test
    fun `sendPromptAsync selects the model then sends text and files`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204).setHeader("X-Session-Guard-Revision", "7"))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("X-Session-Guard-Revision", "8"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{}}""").setHeader("Content-Type", "application/json"))

            api.sendPromptAsync(
                sessionId = "ses_1",
                request =
                    PromptAsyncRequest(
                        messageID = "msg_1",
                        agent = "build",
                        model = PromptAsyncModel(modelID = "m", providerID = "p"),
                        variant = "low",
                        parts =
                            listOf(
                                PromptAsyncPart(id = "p1", type = "text", text = "hello"),
                                PromptAsyncPart(id = "p2", type = "file", url = "file:///x.png", filename = "x.png"),
                            ),
                    ),
                guardRevision = 6,
            )

            val modelReq = server.takeRequest()
            assertEquals("POST", modelReq.method)
            assertEquals("/api/session/ses_1/model", modelReq.path)
            assertTrue(modelReq.body.readUtf8().contains("\"providerID\":\"p\""))

            val agentReq = server.takeRequest()
            assertEquals("/api/session/ses_1/agent", agentReq.path)
            assertTrue(agentReq.body.readUtf8().contains("\"agent\":\"build\""))

            val promptReq = server.takeRequest()
            assertEquals("/api/session/ses_1/prompt", promptReq.path)
            val body = promptReq.body.readUtf8()
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("file:///x.png"))
            // The guard revision from the model write must be carried onto the
            // prompt, otherwise our own selection write looks like drift (409).
            assertEquals("8", promptReq.getHeader("X-Session-Guard-Revision"))
        }

    @Test
    fun `renameSession tolerates the empty 204 patch body and re-reads the session`() =
        runBlocking {
            // PATCH answers 204 with no body; the adapter must not decode it.
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse()
                    .setBody("""{"data":{"id":"ses_1","title":"new name","location":{"directory":"/p"}}}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val session = api.renameSession("ses_1", mapOf("title" to "new name"))

            assertEquals("new name", session.title)
            assertEquals("/p", session.directory)

            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/session/ses_1", patch.path)
            assertTrue(patch.body.readUtf8().contains("new name"))
            assertEquals("/api/session/ses_1", server.takeRequest().path)
        }

    @Test
    fun `replySessionPermission sends a decision`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true").setHeader("Content-Type", "application/json"))

            api.replySessionPermission("ses_1", "per_1", PermissionReplyRequest(reply = "always"))

            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/permission/per_1/reply", request.path)
            assertEquals("""{"decision":"always"}""", request.body.readUtf8())
        }

    @Test
    fun `getVcsDiff maps the legacy git mode to working`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"location":{},"data":[]}""").setHeader("Content-Type", "application/json"))

            api.getVcsDiff(directory = "/base")

            val request = server.takeRequest()
            assertEquals("/api/vcs/diff?mode=working", request.path)
            assertEquals("/base", request.getHeader("x-opencode-directory"))
        }

    @Test
    fun `getFiles resolves v2 relative paths against the location`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"location":{"directory":"/base"},"data":[{"path":"sub/","type":"directory"},{"path":"a.txt","type":"file"}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val files = api.getFiles(path = ".", directory = "/base")

            assertEquals("/base/sub", files[0].absolute)
            assertEquals("sub", files[0].name)
            assertEquals("directory", files[0].type)
            assertEquals("/base/a.txt", files[1].absolute)
            assertEquals("file", files[1].type)
            assertEquals("/base", server.takeRequest().getHeader("x-opencode-directory"))
        }

    @Test
    fun `exportSession returns the exported messages`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":{"info":{"id":"ses_1"},"messages":[{"id":"m1","type":"assistant","content":[{"type":"text","text":"hi"}]}]}}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val messages = api.exportSession("ses_1")

            assertEquals(1, messages.size)
            assertEquals(
                "hi",
                messages
                    .first()
                    .content
                    .first()
                    .text,
            )
            assertEquals("/api/experimental/session/ses_1/export", server.takeRequest().path)
        }

    @Test
    fun `postLog forwards to the guard client-log route`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"ok":true}""").setHeader("Content-Type", "application/json"))

            api.postLog(
                kotlinx.serialization.json.buildJsonObject {
                    put("service", "opencode-android")
                    put("level", "warn")
                    put("message", "boom")
                },
            )

            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/__session_guard__/client-log"))
            assertTrue(request.path!!.contains("name=android-client.log"))
            assertTrue(request.path!!.contains("append=1"))
            assertTrue(request.body.readUtf8().contains("boom"))
        }

    @Test
    fun `getProviderAuth maps key and oauth methods including form prompts`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"location":{},"data":[{"id":"openai","name":"OpenAI","methods":[
                          {"type":"key","label":"API key"},
                          {"id":"chatgpt-browser","type":"oauth","label":"Sign in","form":[{"key":"server","title":"Server","type":"string"}]},
                          {"type":"env","names":["OPENAI_API_KEY"]}
                        ]}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val auth = api.getProviderAuth()

            val methods = auth["openai"].orEmpty()
            assertEquals(2, methods.size)
            assertEquals("api", methods[0].type)
            assertEquals("oauth", methods[1].type)
            assertEquals("server", methods[1].prompts.first().key)
        }
}
