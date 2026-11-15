package com.naeblis11.mealplanner.desktop.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Meal Planner's MCP server (plan 2026-11-14, R1): JSON-RPC 2.0 as MCP's Streamable HTTP transport carries it, one
 * message per POST, answered with one JSON reply. `initialize`, `ping`, `tools/list` and `tools/call` are answered;
 * a notification (no id) or a client's response gets no reply. Transport-free: McpRoutes owns HTTP and who may call.
 * A tool that refuses ([ToolRefusal]) or fails is a result with isError, so the AI reads why; [log] gets the tool's
 * name and an exception class only, never its arguments.
 */
class McpServer(
    private val tools: McpTools,
    private val version: String,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    /** The reply to [body], or null when it needs none. */
    suspend fun handle(body: String): String? {
        val message = try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "That isn't JSON.")
        } catch (e: IllegalArgumentException) {
            return error(JsonNull, PARSE_ERROR, "That isn't JSON.")
        }
        val o = message as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Send one JSON-RPC message per request.")
        val id = o["id"]
        val method = (o["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // A response to something this server never asks, or a notification: nothing to say.
        if (method == null) return if (id == null) error(JsonNull, INVALID_REQUEST, "A request needs a method.") else null
        if (id == null) return null
        if (id !is JsonPrimitive || (id.isString.not() && id.content.toLongOrNull() == null)) {
            return error(JsonNull, INVALID_REQUEST, "A request's id must be a string or a number.")
        }
        val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, toolList())
            "tools/call" -> call(id, params)
            else -> error(id, METHOD_NOT_FOUND, "Meal Planner doesn't answer $method.")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", version)
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun toolList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (t in tools.tools) {
                add(
                    buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchema", t.inputSchema)
                    },
                )
            }
        }
    }

    private suspend fun call(id: JsonElement, params: JsonObject): String {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return error(id, INVALID_PARAMS, "tools/call needs a tool name.")
        val tool = tools.tool(name) ?: return error(id, INVALID_PARAMS, "There is no tool called $name.")
        val args = when (val a = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> return error(id, INVALID_PARAMS, "A tool's arguments are an object.")
        }
        val (text, isError) = try {
            tool.run(args).toString() to false
        } catch (e: ToolRefusal) {
            (e.message ?: "Meal Planner refused that.") to true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Meal Planner: the MCP tool $name failed: ${e.javaClass.name}")
            "Something went wrong in Meal Planner; nothing more is known here. Try again, or check the app." to true
        }
        return result(
            id,
            buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
                put("isError", isError)
            },
        )
    }

    private fun result(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    companion object {
        const val SERVER_NAME = "meal-planner"

        /** Newest first; an initialize asking for another gets the newest. */
        val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        const val INSTRUCTIONS =
            "Meal Planner is one household's recipe library, meal plan, pantry and shopping list, on this PC. Recipes are " +
                "Open Recipe Format YAML: read one with get_recipe before update_recipe, and send back its base_hash. " +
                "Meals go in a day's Breakfast, Lunch or Dinner. Dates are YYYY-MM-DD. Changes reach the household's " +
                "phones and other PCs at their next sync."
    }
}
