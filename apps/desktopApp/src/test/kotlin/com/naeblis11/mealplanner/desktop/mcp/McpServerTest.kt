package com.naeblis11.mealplanner.desktop.mcp

import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 2026-11-14: the MCP server end to end over a real (temp) desktop library: the protocol, then each tool through
 * tools/call, with recipes written as YAML files by the app's own repository.
 */
class McpServerTest {
    private val dir: File = Files.createTempDirectory("mp-mcp").toFile()
    private val logs = mutableListOf<String>()
    private val app = DesktopApp(dir, moveToTrash = { it.delete() }, settingsFactory = { MapSettings() }).also { runBlocking { it.start().join() } }
    private val server = McpServer(McpTools(app.container), "test", log = { logs += it })
    private var nextId = 0

    @After
    fun tearDown() {
        assertTrue(app.close())
        dir.deleteRecursively()
    }

    private fun send(text: String): JsonObject? = runBlocking { server.handle(text) }?.let { Json.parseToJsonElement(it).jsonObject }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", ++nextId); put("method", method); put("params", params) }.toString())!!

    // A tool's answer: (its text, isError).
    private fun call(tool: String, vararg args: Pair<String, Any?>): Pair<String, Boolean> {
        val arguments = JsonObject(
            args.associate { (k, v) ->
                k to when (v) {
                    null -> kotlinx.serialization.json.JsonNull
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    is kotlinx.serialization.json.JsonElement -> v
                    else -> JsonPrimitive(v.toString())
                }
            },
        )
        val reply = request("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) })
        val result = reply["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        return text to result["isError"]!!.jsonPrimitive.boolean
    }

    private fun ok(tool: String, vararg args: Pair<String, Any?>): JsonObject {
        val (text, isError) = call(tool, *args)
        assertFalse("$tool refused: $text", isError)
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun refused(tool: String, vararg args: Pair<String, Any?>): String {
        val (text, isError) = call(tool, *args)
        assertTrue("$tool should refuse: $text", isError)
        return text
    }

    private val pancakes = """
        recipe_name: Pancakes
        ingredients:
        - flour:
            amounts:
            - amount: 2
              unit: cups
        - milk:
            amounts:
            - amount: 250
              unit: ml
        - salt:
            amounts:
            - amount: a pinch
              unit: ''
        steps:
        - step: Mix.
        - step: Fry.
    """.trimIndent()

    private fun addPancakes(): Long = ok("add_recipe", "yaml" to pancakes)["recipe_id"]!!.jsonPrimitive.long

    @Test
    fun itInitializesAndListsItsTools() {
        val init = request("initialize", buildJsonObject { put("protocolVersion", "2025-03-26"); putJsonObject("capabilities") {} })["result"]!!.jsonObject
        assertEquals("2025-03-26", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(McpServer.SERVER_NAME, init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(init["capabilities"]!!.jsonObject.containsKey("tools"))
        val newest = request("initialize", buildJsonObject { put("protocolVersion", "1999-01-01") })["result"]!!.jsonObject
        assertEquals(McpServer.PROTOCOL_VERSIONS.first(), newest["protocolVersion"]!!.jsonPrimitive.content)
        val names = request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "list_recipes", "get_recipe", "add_recipe", "update_recipe", "get_meal_plan", "plan_meals", "remove_planned_meal",
                "list_pantry", "add_pantry_item", "update_pantry_item", "remove_pantry_item", "list_shopping", "add_shopping_item",
                "add_recipe_to_shopping", "add_week_to_shopping", "check_shopping_item", "remove_shopping_item",
            ),
            names,
        )
        // No tool deletes a recipe or clears the whole list.
        assertFalse(names.any { it.contains("delete") || it.contains("clear") })
        assertEquals(JsonObject(emptyMap()), request("ping")["result"])
    }

    @Test
    fun theProtocolRefusesWhatItCantAnswer() {
        assertEquals(McpServer.PARSE_ERROR, send("not json")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(McpServer.INVALID_REQUEST, send("[1, 2]")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(McpServer.METHOD_NOT_FOUND, request("resources/list")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(
            McpServer.INVALID_PARAMS,
            request("tools/call", buildJsonObject { put("name", "delete_everything") })["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )
        // A notification, and a client's response, need no reply.
        assertNull(send("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(send("""{"jsonrpc":"2.0","id":7,"result":{}}"""))
    }

    @Test
    fun aRecipeIsAddedAsAYamlFileReadBackAndListed() {
        val added = ok("add_recipe", "yaml" to pancakes)
        val id = added["recipe_id"]!!.jsonPrimitive.long
        // The amount it can't read is listed back, never guessed.
        assertEquals(listOf("salt a pinch"), added["unreadable_amounts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(app.recipesDir.listFiles()!!.any { it.readText().contains("recipe_name: Pancakes") })
        val recipe = ok("get_recipe", "recipe_id" to id)
        assertEquals("Pancakes", recipe["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("flour", "milk", "salt"), recipe["ingredients"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("Mix.", "Fry."), recipe["steps"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(recipe["yaml"]!!.jsonPrimitive.content.contains("recipe_uuid"))
        // Metric is shown in US units, as an import does.
        val milk = recipe["ingredients"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "milk" }
        assertFalse(milk["unit"]!!.jsonPrimitive.content == "ml")
        val listed = ok("list_recipes", "query" to "pancake")
        assertEquals(listOf("Pancakes"), listed["recipes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun aRecipeThatIsADuplicateOrUnreadableIsRefused() {
        addPancakes()
        assertTrue(refused("add_recipe", "yaml" to pancakes.replace("Pancakes", "pancakes")).contains("already in the library"))
        assertTrue(refused("add_recipe", "yaml" to "recipe_name: [unclosed").contains("YAML"))
        assertTrue(refused("add_recipe", "yaml" to "- just a list").contains("mapping"))
        assertTrue(refused("add_recipe", "yaml" to "ingredients: []\nsteps: []").isNotEmpty())
        assertEquals(1, ok("list_recipes")["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun aRecipeIsUpdatedFromWhatWasReadAndAStaleReadIsRefused() {
        val id = addPancakes()
        val read = ok("get_recipe", "recipe_id" to id)
        val uuid = read["recipe_uuid"]!!.jsonPrimitive.content
        val hash = read["base_hash"]!!.jsonPrimitive.content
        val edited = read["yaml"]!!.jsonPrimitive.content.replace("Fry.", "Fry until golden.")
        ok("update_recipe", "recipe_id" to id, "yaml" to edited, "base_hash" to hash)
        val again = ok("get_recipe", "recipe_id" to id)
        assertEquals(listOf("Mix.", "Fry until golden."), again["steps"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Its identity stays, whatever the edit says.
        assertEquals(uuid, again["recipe_uuid"]!!.jsonPrimitive.content)
        // The first read is stale now: the file changed since.
        assertTrue(refused("update_recipe", "recipe_id" to id, "yaml" to edited, "base_hash" to hash).contains("changed since"))
        assertTrue(refused("update_recipe", "recipe_id" to 999, "yaml" to edited, "base_hash" to hash).contains("no recipe"))
    }

    @Test
    fun mealsArePlannedTogetherOrNotAtAll() {
        val id = addPancakes()
        val monday = LocalDate.of(2026, 11, 16)
        val meals = buildJsonArray {
            add(buildJsonObject { put("date", monday.toString()); put("slot", "breakfast"); put("recipe_id", id); put("servings", "6") })
            add(buildJsonObject { put("date", monday.plusDays(1).toString()); put("slot", "Dinner"); put("recipe_id", id) })
        }
        assertEquals(2, ok("plan_meals", "meals" to meals)["planned"]!!.jsonPrimitive.int)
        val plan = ok("get_meal_plan", "start_date" to monday.toString())["meals"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Breakfast", "Dinner"), plan.map { it["slot"]!!.jsonPrimitive.content })
        assertEquals("6", plan[0]["servings"]!!.jsonPrimitive.content)
        // One bad meal (no such recipe) plans none of them.
        val bad = buildJsonArray {
            add(buildJsonObject { put("date", monday.plusDays(2).toString()); put("slot", "Lunch"); put("recipe_id", id) })
            add(buildJsonObject { put("date", monday.plusDays(3).toString()); put("slot", "Lunch"); put("recipe_id", 999) })
        }
        assertTrue(refused("plan_meals", "meals" to bad).contains("no recipe 999"))
        assertTrue(refused("plan_meals", "meals" to buildJsonArray { add(buildJsonObject { put("date", "16/11/2026"); put("slot", "Lunch"); put("recipe_id", id) }) }).contains("YYYY-MM-DD"))
        assertTrue(refused("plan_meals", "meals" to buildJsonArray { add(buildJsonObject { put("date", monday.toString()); put("slot", "Brunch"); put("recipe_id", id) }) }).contains("Breakfast, Lunch or Dinner"))
        assertEquals(2, ok("get_meal_plan", "start_date" to monday.toString())["meals"]!!.jsonArray.size)
        ok("remove_planned_meal", "date" to monday.toString(), "slot" to "Breakfast")
        assertEquals(1, ok("get_meal_plan", "start_date" to monday.toString())["meals"]!!.jsonArray.size)
        assertTrue(refused("get_meal_plan", "start_date" to monday.toString(), "days" to 40).contains("1 to 31"))
    }

    @Test
    fun thePantryIsAddedToChangedAndRemoved() {
        ok("add_pantry_item", "name" to "olive oil", "aisle" to "Oils")
        val item = ok("list_pantry")["items"]!!.jsonArray.single().jsonObject
        val id = item["id"]!!.jsonPrimitive.long
        assertTrue(item["in_stock"]!!.jsonPrimitive.boolean)
        ok("update_pantry_item", "item_id" to id, "in_stock" to false)
        assertFalse(ok("list_pantry")["items"]!!.jsonArray.single().jsonObject["in_stock"]!!.jsonPrimitive.boolean)
        // Adding it again puts it back in stock.
        assertTrue(ok("add_pantry_item", "name" to "Olive Oil")["message"]!!.jsonPrimitive.content.contains("in stock again"))
        assertTrue(ok("list_pantry")["items"]!!.jsonArray.single().jsonObject["in_stock"]!!.jsonPrimitive.boolean)
        assertTrue(refused("update_pantry_item", "item_id" to id).contains("Give in_stock"))
        ok("remove_pantry_item", "item_id" to id)
        assertEquals(0, ok("list_pantry")["count"]!!.jsonPrimitive.int)
        assertTrue(refused("remove_pantry_item", "item_id" to id).contains("no pantry item"))
    }

    @Test
    fun theShoppingListIsAddedToCheckedOffAndRemovedFrom() {
        val id = addPancakes()
        val eggs = ok("add_shopping_item", "name" to "eggs", "amount" to "6")["item_id"]!!.jsonPrimitive.long
        ok("add_recipe_to_shopping", "recipe_id" to id)
        val names = ok("list_shopping")["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("eggs", "flour", "milk")))
        ok("check_shopping_item", "item_id" to eggs, "checked" to true)
        val checked = ok("list_shopping")["items"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.long == eggs }
        assertTrue(checked["checked"]!!.jsonPrimitive.boolean)
        ok("remove_shopping_item", "item_id" to eggs)
        assertFalse(ok("list_shopping")["items"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.long == eggs })
        assertTrue(refused("check_shopping_item", "item_id" to eggs, "checked" to true).contains("no shopping list item"))
        // A week's planned meals, from any day in it.
        val wednesday = LocalDate.of(2026, 11, 18)
        ok("plan_meals", "meals" to buildJsonArray { add(buildJsonObject { put("date", wednesday.toString()); put("slot", "Lunch"); put("recipe_id", id) }) })
        assertTrue(ok("add_week_to_shopping", "week_of" to wednesday.toString())["message"]!!.jsonPrimitive.content.contains("week of 2026-11-16"))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aMissingOrWrongArgumentIsARefusalNotAFailure() {
        assertTrue(refused("get_recipe").contains("recipe_id is missing"))
        assertTrue(refused("get_recipe", "recipe_id" to "seven").contains("whole number"))
        assertTrue(refused("check_shopping_item", "item_id" to 1, "checked" to "yes").contains("true or false"))
        assertTrue(refused("add_shopping_item", "name" to JsonArray(emptyList())).contains("single value"))
        assertTrue(logs.isEmpty())
    }
}
