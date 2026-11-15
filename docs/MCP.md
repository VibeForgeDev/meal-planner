# AI assistants (MCP)

The Windows app has an MCP server, so an AI assistant on the same PC (Claude Code, Claude Desktop, or any client that
speaks the Model Context Protocol) can work with your Meal Planner. It is part of the running app, so **Meal Planner
must be running** (it can sit in the tray). Everything it changes is saved the way the app saves it: recipes as YAML
files in `Documents\Meal Planner\recipes`, and phones and other PCs get the changes at their next sync.

## What the assistant can do

| Area | Tools |
|---|---|
| Recipes | `list_recipes` (search by name, category, cookbook or ingredient), `get_recipe` (ingredients, steps, notes and the whole recipe as Open Recipe Format YAML), `add_recipe`, `update_recipe` |
| Meal plan | `get_meal_plan` (up to 31 days from a date), `plan_meals` (one or many meals at once; a slot already planned is replaced), `remove_planned_meal` |
| Pantry | `list_pantry`, `add_pantry_item`, `update_pantry_item` (in stock or run out, aisle), `remove_pantry_item` |
| Shopping list | `list_shopping`, `add_shopping_item`, `add_recipe_to_shopping`, `add_week_to_shopping`, `check_shopping_item`, `remove_shopping_item` |

It can't delete a recipe (that would also delete its planned meals) or clear the whole shopping list; do those in the
app. A new recipe whose name is already in the library is refused. An edit is refused if the recipe changed since the
assistant read it, so it never saves over an edit made in the app or by hand. Amounts it can't read are kept as written
and listed back.

## Connecting

The server's address is `http://127.0.0.1:5000/mcp` (the preview window's is `http://127.0.0.1:5055/mcp`).

**Claude Code**, in any terminal:

    claude mcp add --transport http meal-planner http://127.0.0.1:5000/mcp

**Claude Desktop** takes a local command in its configuration (Settings, Developer, Edit config), so it reaches the
server through `mcp-remote`, which needs Node.js (already in the README's requirements):

    {
      "mcpServers": {
        "meal-planner": {
          "command": "npx",
          "args": ["-y", "mcp-remote", "http://127.0.0.1:5000/mcp"]
        }
      }
    }

Restart Claude Desktop after saving it.

## Who can reach it

Only programs on this PC: the server refuses any other computer, even while Alexa has the app's server open to your
home network. It also refuses web pages, so a site open in your browser can't use it. There is no password: anything
that can run as your Windows user can already change your recipe files. Don't connect an assistant you wouldn't let
edit your recipes.
