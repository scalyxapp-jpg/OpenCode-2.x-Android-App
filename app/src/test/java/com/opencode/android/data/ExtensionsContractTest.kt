package com.opencode.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings → Extensions (MCPs / Plugins / Skills) against the real contract.
 *
 * The endpoints were read out of the web bundle and verified against the running
 * server — toggling an MCP server issues exactly one call:
 *
 * ```
 * switch ON  -> POST /api/experimental/mcp/{name}/connect      (204)
 * switch OFF -> POST /api/experimental/mcp/{name}/disconnect   (204)
 * list       -> GET  /api/mcp                                  [{name, status:{status}}]
 * plugins    -> GET  /api/plugin                               [{id, source, features, state}]
 * skills     -> GET  /api/skill                                [{id, name, description}]
 * resources  -> GET  /api/mcp/resource                         {data:{resources:[…]}}
 * ```
 */
class ExtensionsContractTest {
    private lateinit var server: MockWebServer
    private lateinit var session: BackendSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = BackendSession()
        session.setBaseUrl(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `switching an MCP server on calls connect once`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            session.api.connectMcp("gitnexus").close()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/mcp/gitnexus/connect", request.path)
        }

    @Test
    fun `switching an MCP server off calls disconnect once`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            session.api.disconnectMcp("gitnexus").close()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/mcp/gitnexus/disconnect", request.path)
        }

    @Test
    fun `the MCP list carries the status the switch renders`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":[{"name":"semble","status":{"status":"connected"}},
                           {"name":"godot-game","status":{"status":"disabled"}}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val servers = session.api.getMcpServers()

            assertEquals("/api/mcp", server.takeRequest().path)
            assertEquals("connected", servers["semble"]?.status)
            assertEquals("disabled", servers["godot-game"]?.status)
        }

    @Test
    fun `plugins are parsed with source, state and features`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":[{"id":"opencode.tool.input.repair",
                           "source":{"type":"builtin"},
                           "features":{"server":true},
                           "state":{"status":"active"}}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val plugins = session.api.getPlugins()

            assertEquals("/api/plugin", server.takeRequest().path)
            assertEquals(1, plugins.size)
            assertEquals("opencode.tool.input.repair", plugins.first().id)
            assertEquals("builtin", plugins.first().sourceType)
            assertEquals("active", plugins.first().status)
            assertTrue(plugins.first().features.contains("server"))
        }

    @Test
    fun `skills are parsed with name and description`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":[{"id":"agent-browser","name":"agent-browser",
                           "description":"Browser automation CLI for AI agents."}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val skills = session.api.getSkills()

            assertEquals("/api/skill", server.takeRequest().path)
            assertEquals(1, skills.size)
            assertEquals("agent-browser", skills.first().id)
            assertEquals("Browser automation CLI for AI agents.", skills.first().description)
        }

    @Test
    fun `MCP resources are grouped by server for the row detail`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":{"resources":[
                           {"server":"exa-web-search","name":"tools_list","uri":"exa://tools/list"},
                           {"server":"gdb-mcp","name":"list_sessions","uri":"debugger://sessions"}]}}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val resources = session.api.getMcpResources()

            assertEquals("/api/mcp/resource", server.takeRequest().path)
            assertEquals(2, resources.count { it.server.isNotBlank() })
            assertEquals(1, resources.count { it.server == "gdb-mcp" })
        }
}
