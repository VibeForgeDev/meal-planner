package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.desktop.mcp.McpServer
import com.naeblis11.mealplanner.desktop.mcp.McpTools
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 2026-11-14, R2: the MCP route answers programs on this PC only, never a web page, and only JSON. */
class McpRoutesTest {
    private val dir: File = Files.createTempDirectory("mp-mcp-routes").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val server = McpServer(McpTools(app.container), "test", log = {})
    private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun ApplicationTestBuilder.serve(remote: String?, maxBytes: Int = MCP_MAX_BYTES) = application {
        ServerRoutes(listOf<Route.() -> Unit>({ mcpRoutes(server, maxBytes = maxBytes, remoteAddress = { remote }) }), log = {}).install(this)
    }

    private suspend fun ApplicationTestBuilder.send(body: String, origin: String? = null, json: Boolean = true): HttpResponse = client.post(MCP_PATH) {
        if (json) contentType(ContentType.Application.Json) else contentType(ContentType.Text.Plain)
        if (origin != null) header(HttpHeaders.Origin, origin)
        setBody(body)
    }

    @Test
    fun aProgramOnThisPcIsAnswered() = testApplication {
        serve("127.0.0.1")
        val reply = send(ping)
        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue(reply.contentType()!!.match(ContentType.Application.Json))
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", reply.bodyAsText())
        // From this PC's own pages too (an MCP inspector on localhost).
        assertEquals(HttpStatusCode.OK, send(ping, origin = "http://localhost:6274").status)
        assertEquals(HttpStatusCode.OK, send(ping, origin = "http://127.0.0.1:6274").status)
    }

    @Test
    fun anyoneElseIsRefused() = testApplication {
        serve("192.0.2.30")
        val reply = send(ping)
        assertEquals(HttpStatusCode.Forbidden, reply.status)
        assertTrue(reply.bodyAsText().contains(MCP_NOT_LOOPBACK))
    }

    @Test
    fun aWebPageIsRefusedWhateverItsAddress() = testApplication {
        serve("127.0.0.1")
        for (origin in listOf("https://example.com", "http://localhost.example.com", "https://localhost", "null", "chrome-extension://abc")) {
            assertEquals(origin, HttpStatusCode.Forbidden, send(ping, origin = origin).status)
        }
        // A form or text body (all a page can send without a preflight) is refused before it is read.
        assertEquals(HttpStatusCode.UnsupportedMediaType, send(ping, json = false).status)
    }

    @Test
    fun aNotificationIsAcceptedWithNoBodyAndTheRestIs405() = testApplication {
        serve("127.0.0.1")
        val reply = send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(HttpStatusCode.Accepted, reply.status)
        assertEquals("", reply.bodyAsText())
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get(MCP_PATH).status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.delete(MCP_PATH).status)
    }

    @Test
    fun aMessageOverTheCapIsRefused() = testApplication {
        serve("127.0.0.1", maxBytes = 64)
        assertEquals(HttpStatusCode.PayloadTooLarge, send("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"${"x".repeat(100)}"}}""").status)
    }

    @Test
    fun originsAreJudgedByTheirHost() {
        assertTrue(isLocalOrigin(null))
        assertTrue(isLocalOrigin("http://localhost"))
        assertTrue(isLocalOrigin("http://[::1]:3000"))
        assertFalse(isLocalOrigin("http://127.0.0.1.example.com"))
        assertFalse(isLocalOrigin("not a uri at all %%"))
    }
}
