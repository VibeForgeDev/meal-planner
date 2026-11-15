package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.desktop.mcp.McpServer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URI

const val MCP_PATH = "/mcp"
const val MCP_MAX_BYTES = 1024 * 1024

const val MCP_NOT_LOOPBACK = "Meal Planner's MCP server answers programs on this PC only."
const val MCP_BAD_ORIGIN = "Meal Planner's MCP server doesn't answer web pages."
const val MCP_NOT_JSON = "Send MCP messages as application/json."
const val MCP_TOO_LARGE = "That MCP message is too large for Meal Planner."

/**
 * Whether a request's `Origin` may reach the MCP server (plan 2026-11-14, R2): none (MCP clients send none), or this
 * PC's own address over http, on any port. A page anywhere else (or a name rebound to 127.0.0.1) sends its own Origin.
 */
internal fun isLocalOrigin(origin: String?): Boolean {
    if (origin == null) return true
    val uri = try {
        URI(origin)
    } catch (e: Exception) {
        return false
    }
    return uri.scheme == "http" && (uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "[::1]")
}

/**
 * MCP's Streamable HTTP transport at [MCP_PATH] (R1, R2), for programs on this PC only: a loopback caller (else 403),
 * no Origin but this PC's ([isLocalOrigin], else 403), a JSON body ([isJsonContentType], else 415: a web page can't
 * send one without a CORS preflight, which this server never answers) of at most [maxBytes] (else 413). The reply is
 * application/json, or 202 with no body for a notification. GET (an SSE stream) and DELETE (a session's end) are 405:
 * this server keeps no session and sends nothing unasked.
 */
fun Route.mcpRoutes(
    server: McpServer,
    maxBytes: Int = MCP_MAX_BYTES,
    remoteAddress: (ApplicationCall) -> String? = { it.request.local.remoteAddress },
) {
    post(MCP_PATH) {
        if (!isLoopback(remoteAddress(call))) return@post call.reply(JsonReply.error(403, MCP_NOT_LOOPBACK))
        if (!isLocalOrigin(call.request.headers[HttpHeaders.Origin])) return@post call.reply(JsonReply.error(403, MCP_BAD_ORIGIN))
        if (!isJsonContentType(call.request.headers[HttpHeaders.ContentType])) return@post call.reply(JsonReply.error(415, MCP_NOT_JSON))
        val bytes = call.readBody(maxBytes) ?: return@post call.reply(JsonReply.error(413, MCP_TOO_LARGE))
        val reply = server.handle(bytes.toString(Charsets.UTF_8))
        if (reply == null) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            call.respondText(reply, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
    get(MCP_PATH) { notAllowed(call) }
    delete(MCP_PATH) { notAllowed(call) }
}

private suspend fun notAllowed(call: ApplicationCall) {
    call.response.header(HttpHeaders.Allow, "POST")
    call.respond(HttpStatusCode.MethodNotAllowed)
}
