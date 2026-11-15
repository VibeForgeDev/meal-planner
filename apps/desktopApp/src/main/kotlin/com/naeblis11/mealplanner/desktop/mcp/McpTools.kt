package com.naeblis11.mealplanner.desktop.mcp

import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.data.AddStatus
import com.naeblis11.mealplanner.data.FILE_HOLDS_ANOTHER_RECIPE
import com.naeblis11.mealplanner.data.PantryAdd
import com.naeblis11.mealplanner.data.RECIPE_HELD
import com.naeblis11.mealplanner.data.RecipeChangedException
import com.naeblis11.mealplanner.domain.Books
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A refusal the AI is told about in one sentence (a tool result with isError), never a protocol error. */
class ToolRefusal(message: String) : Exception(message)

/** One tool: its MCP [name], [description] and JSON Schema [inputSchema], and what it does with its arguments. */
class McpTool(val name: String, val description: String, val inputSchema: JsonObject, val run: suspend (JsonObject) -> JsonElement)

/**
 * Meal Planner's MCP tools (plan 2026-11-14, R3, R4), over the app's own repositories, so every write is the app's own:
 * a recipe's YAML file first, the removal-safety holds, the sync's change records. Answers are JSON. Nothing here logs.
 */
class McpTools(private val container: AppContainer) {
    private val recipes get() = container.recipes
    private val db get() = container.database

    val tools: List<McpTool> = listOf(
        McpTool(
            "list_recipes",
            "Lists the recipes in the library: id, name, category, subcategory, star rating and cookbook. Optionally " +
                "searched (name, category, cookbook or an ingredient) or limited to one category.",
            schema(
                "query" to prop("string", "Words to search for; leave out for every recipe."),
                "category" to prop("string", "Only recipes in this category (as list_recipes shows it)."),
            ),
        ) { args -> listRecipes(optText(args, "query"), optText(args, "category")) },
        McpTool(
            "get_recipe",
            "One recipe: its yields, ingredients (with their sections, amounts and units), steps and notes, plus the whole " +
                "recipe as Open Recipe Format YAML and its base_hash, which update_recipe needs.",
            schema("recipe_id" to prop("integer", "The recipe's id from list_recipes."), required = listOf("recipe_id")),
        ) { args -> getRecipe(id(args, "recipe_id")) },
        McpTool(
            "add_recipe",
            "Adds a new recipe from Open Recipe Format YAML (recipe_name, yields, ingredients with amounts and units, steps). " +
                "A name already in the library is refused. Metric amounts are shown in US units; amounts Meal Planner can't " +
                "read are kept as written and listed back.",
            schema("yaml" to prop("string", "The whole recipe as Open Recipe Format YAML."), required = listOf("yaml")),
        ) { args -> addRecipe(text(args, "yaml")) },
        McpTool(
            "update_recipe",
            "Replaces a recipe with edited Open Recipe Format YAML: get_recipe first, edit its yaml, and send it back with the " +
                "base_hash get_recipe gave. Refused if the recipe changed since. Its recipe_uuid and photo are kept.",
            schema(
                "recipe_id" to prop("integer", "The recipe's id."),
                "yaml" to prop("string", "The whole edited recipe as Open Recipe Format YAML."),
                "base_hash" to prop("string", "The base_hash get_recipe gave."),
                required = listOf("recipe_id", "yaml", "base_hash"),
            ),
        ) { args -> updateRecipe(id(args, "recipe_id"), text(args, "yaml"), text(args, "base_hash", allowEmpty = true)) },
        McpTool(
            "get_meal_plan",
            "The planned meals from a date: each day's Breakfast, Lunch and Dinner with the recipe and servings.",
            schema(
                "start_date" to prop("string", "The first day, as YYYY-MM-DD."),
                "days" to prop("integer", "How many days, 1 to 31; 7 if left out."),
                required = listOf("start_date"),
            ),
        ) { args -> mealPlan(date(args, "start_date"), optLong(args, "days")?.toInt() ?: 7) },
        McpTool(
            "plan_meals",
            "Plans one or more meals: each a date, a slot (Breakfast, Lunch or Dinner), a recipe and optionally the servings. " +
                "A slot already planned is replaced. All are checked before any is planned.",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        putJsonObject("meals") {
                            put("type", "array")
                            put("description", "Up to $MAX_MEALS meals.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    put("date", prop("string", "YYYY-MM-DD"))
                                    put("slot", prop("string", "Breakfast, Lunch or Dinner"))
                                    put("recipe_id", prop("integer", "The recipe's id."))
                                    put("servings", prop("string", "Servings, such as 4; leave out for the recipe's own."))
                                }
                                putJsonArray("required") { add(JsonPrimitive("date")); add(JsonPrimitive("slot")); add(JsonPrimitive("recipe_id")) }
                            }
                        }
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("meals"))),
                ),
            ),
        ) { args -> planMeals(args["meals"]) },
        McpTool(
            "remove_planned_meal",
            "Takes the meal off one day's slot (the recipe stays in the library).",
            schema(
                "date" to prop("string", "YYYY-MM-DD"),
                "slot" to prop("string", "Breakfast, Lunch or Dinner"),
                required = listOf("date", "slot"),
            ),
        ) { args -> removeMeal(date(args, "date"), slot(args, "slot")) },
        McpTool(
            "list_pantry",
            "What is in the pantry: each item's id, name, whether it is in stock (false means it ran out), aisle and date added.",
            schema(),
        ) { listPantry() },
        McpTool(
            "add_pantry_item",
            "Adds an item to the pantry, or marks one that ran out as in stock again.",
            schema("name" to prop("string", "The item, such as olive oil."), "aisle" to prop("string", "Its aisle, optionally."), required = listOf("name")),
        ) { args -> addPantry(text(args, "name"), optText(args, "aisle")) },
        McpTool(
            "update_pantry_item",
            "Changes a pantry item: in stock or run out, and its aisle.",
            schema(
                "item_id" to prop("integer", "The item's id from list_pantry."),
                "in_stock" to prop("boolean", "True when there is some, false when it ran out."),
                "aisle" to prop("string", "Its aisle; an empty string clears it."),
                required = listOf("item_id"),
            ),
        ) { args -> updatePantry(id(args, "item_id"), optBoolean(args, "in_stock"), args["aisle"]?.let { optText(args, "aisle") ?: "" }) },
        McpTool(
            "remove_pantry_item",
            "Removes an item from the pantry altogether.",
            schema("item_id" to prop("integer", "The item's id from list_pantry."), required = listOf("item_id")),
        ) { args -> removePantry(id(args, "item_id")) },
        McpTool(
            "list_shopping",
            "The shopping list: each item's id, name, amount, unit, aisle, whether it is checked off, and whether the pantry has it.",
            schema(),
        ) { listShopping() },
        McpTool(
            "add_shopping_item",
            "Adds an item to the shopping list; an item already there with a unit that combines gets the amount added.",
            schema(
                "name" to prop("string", "The item."),
                "amount" to prop("string", "How much, such as 2 or 1/2; optional."),
                "unit" to prop("string", "Its unit, such as cups; optional."),
                "aisle" to prop("string", "Its aisle; optional."),
                required = listOf("name"),
            ),
        ) { args -> addShopping(text(args, "name"), optText(args, "amount"), optText(args, "unit"), optText(args, "aisle")) },
        McpTool(
            "add_recipe_to_shopping",
            "Adds one recipe's ingredients to the shopping list (merged with what is there; pantry items are marked).",
            schema(
                "recipe_id" to prop("integer", "The recipe's id."),
                "servings" to prop("string", "Servings to shop for; leave out for the recipe's own."),
                required = listOf("recipe_id"),
            ),
        ) { args -> addRecipeToShopping(id(args, "recipe_id"), optText(args, "servings")) },
        McpTool(
            "add_week_to_shopping",
            "Adds the ingredients of every meal planned in a week (Monday to Sunday) to the shopping list.",
            schema("week_of" to prop("string", "Any day of the week, as YYYY-MM-DD."), required = listOf("week_of")),
        ) { args -> addWeek(date(args, "week_of")) },
        McpTool(
            "check_shopping_item",
            "Checks an item off the shopping list, or un-checks it.",
            schema(
                "item_id" to prop("integer", "The item's id from list_shopping."),
                "checked" to prop("boolean", "True to check it off."),
                required = listOf("item_id", "checked"),
            ),
        ) { args -> checkShopping(id(args, "item_id"), optBoolean(args, "checked") ?: throw ToolRefusal("checked must be true or false.")) },
        McpTool(
            "remove_shopping_item",
            "Removes one item from the shopping list.",
            schema("item_id" to prop("integer", "The item's id from list_shopping."), required = listOf("item_id")),
        ) { args -> removeShopping(id(args, "item_id")) },
    )

    private val byName = tools.associateBy { it.name }

    fun tool(name: String): McpTool? = byName[name]

    // Recipes.

    private suspend fun listRecipes(query: String?, category: String?): JsonElement {
        val found = recipes.summaries(query ?: "").first()
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .distinctBy { it.id }
        return buildJsonObject {
            put("count", found.size)
            putJsonArray("recipes") {
                for (r in found) {
                    add(
                        buildJsonObject {
                            put("id", r.id)
                            put("name", r.name)
                            put("category", r.category)
                            put("subcategory", r.subcategory)
                            put("rating", r.rating)
                            put("cookbook", r.book)
                        },
                    )
                }
            }
        }
    }

    private suspend fun getRecipe(id: Long): JsonElement {
        val detail = recipes.observeDetail(id).first() ?: throw ToolRefusal("There is no recipe $id.")
        val row = db.recipeDao().recipe(id) ?: throw ToolRefusal("There is no recipe $id.")
        val r = detail.recipe
        return buildJsonObject {
            put("id", r.id)
            put("recipe_uuid", r.recipeUuid)
            put("name", r.name)
            put("category", r.category)
            put("subcategory", r.subcategory)
            put("rating", r.rating)
            put("cookbook", Books.name(r.sourceBookJson))
            put("yields", r.yieldsJson)
            put("notes", r.notesJson)
            putJsonArray("ingredients") {
                for (i in detail.ingredients.sortedBy { it.orderNum }) {
                    add(
                        buildJsonObject {
                            put("section", i.section)
                            put("name", i.name)
                            put("amount", i.amount)
                            put("unit", i.unit)
                        },
                    )
                }
            }
            putJsonArray("steps") { for (s in detail.steps.sortedBy { it.orderNum }) add(JsonPrimitive(s.stepText)) }
            put("yaml", row.rawYaml)
            put("base_hash", row.fileHash ?: "")
        }
    }

    private suspend fun addRecipe(yaml: String): JsonElement {
        val doc = readDoc(yaml)
        val name = recipeName(doc)
        if (name.lowercase() in recipes.lowerCaseNames()) throw ToolRefusal("A recipe called \"$name\" is already in the library; give this one another name.")
        val uuid = doc["recipe_uuid"]?.toString()?.takeIf { it.isNotBlank() }
        if (uuid != null && recipes.idForUuid(uuid) != null) throw ToolRefusal("A recipe with recipe_uuid $uuid is already in the library; leave recipe_uuid out of a new recipe.")
        val id = saving { recipes.save(doc) }
        return saved(id, doc)
    }

    private suspend fun updateRecipe(id: Long, yaml: String, baseHash: String): JsonElement {
        val row = db.recipeDao().recipe(id) ?: throw ToolRefusal("There is no recipe $id.")
        val doc = readDoc(yaml)
        val name = recipeName(doc)
        if (name.lowercase() in recipes.lowerCaseNamesExcept(id)) throw ToolRefusal("Another recipe is already called \"$name\".")
        // The recipe stays itself (its identity is its uuid), and keeps its photo when the edit left the field out.
        doc["recipe_uuid"] = row.recipeUuid
        if (!doc.containsKey("image")) {
            @Suppress("UNCHECKED_CAST")
            (RecipeYaml.load(row.rawYaml) as? Map<Any?, Any?>)?.get("image")?.let { doc["image"] = it }
        }
        val saved = saving { recipes.save(doc, baseHash.ifEmpty { null }) }
        return saved(saved, doc)
    }

    private suspend fun saving(block: suspend () -> Long): Long = try {
        block()
    } catch (e: RecipeChangedException) {
        throw ToolRefusal("The recipe changed since it was read; call get_recipe again and edit that.")
    } catch (e: RecipeFormatException) {
        throw ToolRefusal("That isn't a recipe Meal Planner can read: ${e.message}")
    } catch (e: IOException) {
        // The library's own sentences (Windows refusing Documents, a held recipe, a file holding another recipe).
        val known = e.message?.takeIf { it == FILE_HOLDS_ANOTHER_RECIPE || it == RECIPE_HELD }
        throw ToolRefusal(recipes.libraryWriteMessage(e) ?: known ?: "Meal Planner couldn't save the recipe.")
    }

    private fun saved(id: Long, doc: YamlMap): JsonElement {
        val unreadable = OrfEditing.findUnparseableAmountSlots(doc)
        return buildJsonObject {
            put("recipe_id", id)
            put("name", recipeName(doc))
            putJsonArray("unreadable_amounts") {
                for (issue in unreadable) add(JsonPrimitive(listOfNotNull(issue.ingredientName, issue.amount, issue.unit).map { it.toString().trim() }.filter { it.isNotEmpty() }.joinToString(" ")))
            }
        }
    }

    private fun readDoc(yaml: String): YamlMap {
        if (yaml.length > MAX_YAML_CHARS) throw ToolRefusal("That recipe is longer than Meal Planner takes ($MAX_YAML_CHARS characters).")
        val loaded = try {
            RecipeYaml.load(yaml)
        } catch (e: Exception) {
            throw ToolRefusal("That isn't valid YAML: ${e.message?.lineSequence()?.firstOrNull() ?: "it can't be read"}")
        }
        @Suppress("UNCHECKED_CAST")
        val doc = (loaded as? Map<Any?, Any?>)?.let { LinkedHashMap(it) as YamlMap }
            ?: throw ToolRefusal("A recipe is a YAML mapping with recipe_name, ingredients and steps.")
        // Checked now, so a bad recipe is refused with the parser's reason before anything is written.
        try {
            Orf.parse(RecipeYaml.dump(doc))
        } catch (e: RecipeFormatException) {
            throw ToolRefusal("That isn't a recipe Meal Planner can read: ${e.message}")
        }
        return doc
    }

    private fun recipeName(doc: Map<Any?, Any?>): String =
        doc["recipe_name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolRefusal("A recipe needs a recipe_name.")

    // The meal plan.

    private suspend fun mealPlan(start: LocalDate, days: Int): JsonElement {
        if (days !in 1..MAX_DAYS) throw ToolRefusal("days must be 1 to $MAX_DAYS.")
        val last = start.plusDays(days - 1L)
        val rows = db.mealPlanDao().range(start.toString(), last.toString())
        return buildJsonObject {
            put("from", start.toString())
            put("to", last.toString())
            putJsonArray("meals") {
                for (m in rows) {
                    add(
                        buildJsonObject {
                            put("date", m.date)
                            put("slot", m.slot)
                            put("recipe_id", m.recipeId)
                            put("recipe_name", m.recipeName)
                            put("servings", m.servings)
                        },
                    )
                }
            }
        }
    }

    private suspend fun planMeals(value: JsonElement?): JsonElement {
        val list = value as? JsonArray ?: throw ToolRefusal("meals must be a list.")
        if (list.isEmpty() || list.size > MAX_MEALS) throw ToolRefusal("Plan 1 to $MAX_MEALS meals at a time.")
        // Every meal checked before any is planned, so a mistake plans nothing.
        val meals = list.mapIndexed { n, e ->
            val m = e as? JsonObject ?: throw ToolRefusal("Meal ${n + 1} isn't an object.")
            val recipeId = id(m, "recipe_id")
            val name = db.recipeDao().recipe(recipeId)?.name ?: throw ToolRefusal("Meal ${n + 1}: there is no recipe $recipeId.")
            Planned(date(m, "date"), slot(m, "slot"), recipeId, name, optText(m, "servings"))
        }
        for (m in meals) container.plans.assign(m.date, m.slot, m.recipeId, m.servings)
        return buildJsonObject {
            put("planned", meals.size)
            putJsonArray("meals") {
                for (m in meals) {
                    add(
                        buildJsonObject {
                            put("date", m.date.toString())
                            put("slot", m.slot)
                            put("recipe_id", m.recipeId)
                            put("recipe_name", m.name)
                            put("servings", m.servings)
                        },
                    )
                }
            }
        }
    }

    private class Planned(val date: LocalDate, val slot: String, val recipeId: Long, val name: String, val servings: String?)

    private suspend fun removeMeal(date: LocalDate, slot: String): JsonElement {
        val was = container.plans.assignment(date, slot) ?: return result("Nothing was planned for $slot on $date.")
        container.plans.unassign(date, slot)
        return result("Took ${was.recipeName} off $slot on $date.")
    }

    // The pantry.

    private suspend fun pantryItems() = container.pantry.observe().first()

    private suspend fun listPantry(): JsonElement {
        val items = pantryItems()
        return buildJsonObject {
            put("count", items.size)
            putJsonArray("items") {
                for (p in items) {
                    add(
                        buildJsonObject {
                            put("id", p.id)
                            put("name", p.name)
                            put("in_stock", p.active)
                            put("aisle", p.aisle)
                            put("added_on", p.addedOn)
                        },
                    )
                }
            }
        }
    }

    private suspend fun addPantry(name: String, aisle: String?): JsonElement {
        if (container.pantry.add(name, aisle) is PantryAdd.Added) return result("Added $name to the pantry.")
        val existing = db.pantryDao().byName(name) ?: return result("$name is already in the pantry.")
        if (existing.active) return result("${existing.name} is already in the pantry.")
        container.pantry.setActive(existing.id, true)
        return result("${existing.name} is in stock again.")
    }

    private suspend fun updatePantry(id: Long, inStock: Boolean?, aisle: String?): JsonElement {
        val item = pantryItems().firstOrNull { it.id == id } ?: throw ToolRefusal("There is no pantry item $id.")
        if (inStock == null && aisle == null) throw ToolRefusal("Give in_stock, aisle, or both.")
        inStock?.let { container.pantry.setActive(id, it) }
        aisle?.let { container.pantry.setAisle(id, it.trim().ifEmpty { null }) }
        return result("Updated ${item.name}.")
    }

    private suspend fun removePantry(id: Long): JsonElement {
        val item = pantryItems().firstOrNull { it.id == id } ?: throw ToolRefusal("There is no pantry item $id.")
        container.pantry.delete(id)
        return result("Removed ${item.name} from the pantry.")
    }

    // The shopping list.

    private suspend fun listShopping(): JsonElement {
        val items = container.shopping.observe().first()
        return buildJsonObject {
            put("count", items.size)
            putJsonArray("items") {
                for (s in items) {
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("name", s.name)
                            put("amount", s.amount)
                            put("unit", s.unit)
                            put("aisle", s.aisle)
                            put("checked", s.checked)
                            put("in_pantry", s.inPantry)
                        },
                    )
                }
            }
        }
    }

    private suspend fun addShopping(name: String, amount: String?, unit: String?, aisle: String?): JsonElement {
        val added = container.shopping.addItem(name, amount, unit, aisle)
        val row = db.shoppingDao().item(added.id)
        val said = when (added.status) {
            AddStatus.ADDED -> "Added"
            AddStatus.MERGED -> "Added to the line already there:"
            AddStatus.DUPLICATE -> "Already on the list:"
        }
        return buildJsonObject {
            put("message", "$said ${row?.name ?: name}.")
            put("item_id", added.id)
            put("amount", row?.amount)
            put("unit", row?.unit)
        }
    }

    private suspend fun addRecipeToShopping(recipeId: Long, servings: String?): JsonElement {
        val name = db.recipeDao().recipe(recipeId)?.name ?: throw ToolRefusal("There is no recipe $recipeId.")
        val added = container.shopping.addRecipe(recipeId, servings)
        return result(if (added) "Added the ingredients of $name to the shopping list." else "$name has no ingredients to add.")
    }

    private suspend fun addWeek(day: LocalDate): JsonElement {
        val start = Week.start(day)
        val meals = container.shopping.addWeek(start)
        return result(
            if (meals == 0) {
                "Nothing is planned in the week of $start."
            } else {
                "Added the ingredients of $meals planned meal${if (meals == 1) "" else "s"} in the week of $start."
            },
        )
    }

    private suspend fun checkShopping(id: Long, checked: Boolean): JsonElement {
        val row = db.shoppingDao().item(id) ?: throw ToolRefusal("There is no shopping list item $id.")
        container.shopping.setChecked(id, checked)
        return result(if (checked) "Checked off ${row.name}." else "${row.name} is no longer checked off.")
    }

    private suspend fun removeShopping(id: Long): JsonElement {
        val row = db.shoppingDao().item(id) ?: throw ToolRefusal("There is no shopping list item $id.")
        container.shopping.remove(id)
        return result("Removed ${row.name} from the shopping list.")
    }

    companion object {
        const val MAX_DAYS = 31
        const val MAX_MEALS = 50
        const val MAX_YAML_CHARS = 200_000

        private fun result(message: String): JsonElement = buildJsonObject { put("message", message) }

        private fun prop(type: String, description: String): JsonObject = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(props.toMap()),
                    "required" to JsonArray(required.map(::JsonPrimitive)),
                ),
            )

        private fun primitive(args: JsonObject, key: String): JsonPrimitive? = when (val e = args[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> e
            else -> throw ToolRefusal("$key must be a single value.")
        }

        private fun optText(args: JsonObject, key: String): String? =
            primitive(args, key)?.content?.trim()?.takeIf { it.isNotEmpty() }

        private fun text(args: JsonObject, key: String, allowEmpty: Boolean = false): String {
            val value = primitive(args, key)?.content ?: throw ToolRefusal("$key is missing.")
            if (!allowEmpty && value.isBlank()) throw ToolRefusal("$key is empty.")
            return value
        }

        private fun optLong(args: JsonObject, key: String): Long? {
            val p = primitive(args, key) ?: return null
            return p.longOrNull ?: p.content.trim().toLongOrNull() ?: throw ToolRefusal("$key must be a whole number.")
        }

        private fun id(args: JsonObject, key: String): Long = optLong(args, key) ?: throw ToolRefusal("$key is missing.")

        private fun optBoolean(args: JsonObject, key: String): Boolean? {
            val p = primitive(args, key) ?: return null
            return p.booleanOrNull ?: throw ToolRefusal("$key must be true or false.")
        }

        private fun date(args: JsonObject, key: String): LocalDate = try {
            LocalDate.parse(text(args, key).trim())
        } catch (e: DateTimeParseException) {
            throw ToolRefusal("$key must be a date as YYYY-MM-DD.")
        }

        private fun slot(args: JsonObject, key: String): String {
            val given = text(args, key).trim()
            return Week.SLOTS.firstOrNull { it.equals(given, ignoreCase = true) } ?: throw ToolRefusal("$key must be Breakfast, Lunch or Dinner.")
        }
    }
}
