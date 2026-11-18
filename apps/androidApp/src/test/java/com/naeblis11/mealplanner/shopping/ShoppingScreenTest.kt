package com.naeblis11.mealplanner.shopping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class ShoppingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val flour = ShoppingItemEntity(id = 1, name = "Flour", amount = "2", unit = "cup", aisle = "Dry Goods & Pasta")
    private val bread = ShoppingItemEntity(id = 3, name = "Bread", aisle = "Bakery", checked = true)
    private val salt = ShoppingItemEntity(id = 2, name = "Salt", amount = "1", unit = "tsp", aisle = "Spices & Baking", inPantry = true)
    private val full = ShoppingState(listOf(AisleGroup("Bakery", listOf(bread)), AisleGroup("Dry Goods & Pasta", listOf(flour))), listOf(salt))

    private var checked: Pair<ShoppingItemEntity, Boolean>? = null
    private var addedWeek = 0
    private var added: List<String>? = null
    private var aisle: Pair<ShoppingItemEntity, String>? = null
    private var removed: ShoppingItemEntity? = null
    private var cleared = 0

    private fun show(state: ShoppingState, krogerAisles: Map<String, String> = emptyMap()) = compose.setContent {
        MealPlannerTheme {
            ShoppingScreen(
                state = state, message = null, error = null, adding = false, onMessageShown = {},
                onCheck = { item, on -> checked = item to on }, onAddThisWeek = { addedWeek++ },
                onAddItem = { n, a, u, ai -> added = listOf(n, a, u, ai) }, onSetAisle = { item, a -> aisle = item to a },
                onRemove = { removed = it }, onClear = { cleared++ }, krogerAisles = krogerAisles,
            )
        }
    }

    @Test
    fun anItemWithARememberedKrogerProductShowsItsAisle() {
        show(full, krogerAisles = mapOf("flour" to "AISLE 7"))
        compose.onNodeWithText(krogerAisleText("AISLE 7")).assertIsDisplayed()
        // The row is still one check box.
        compose.onNode(hasText("2 cup Flour") and isToggleable()).performClick()
        assertEquals(flour to true, checked)
        compose.onAllNodesWithText("Kroger:", substring = true).assertCountEquals(1)
    }

    @Test
    fun groupsByAisleAndKeepsTheKitchenApart() {
        show(full)
        compose.onNodeWithText("Need to buy").assertIsDisplayed()
        compose.onNodeWithText("Dry Goods & Pasta").assertIsDisplayed()
        compose.onNode(hasText("Bread") and isToggleable()).assertIsOn()
        compose.onNodeWithText("Already in My Kitchen").assertIsDisplayed()
        compose.onNode(hasText("Salt") and isToggleable()).assertIsDisplayed()
        compose.onNode(hasText("2 cup Flour") and isToggleable()).assertIsOff().performClick()
        assertEquals(flour to true, checked)
    }

    @Test
    fun anEmptyListSaysHowToStart() {
        show(ShoppingState(emptyList(), emptyList()))
        compose.onNodeWithText("No shopping list yet \u2014 add this week's meals, or add an item.").assertIsDisplayed()
        compose.onNodeWithText("Add this week's meals").performClick()
        assertEquals(1, addedWeek)
    }

    @Test
    fun clearingAsksFirst() {
        show(full)
        compose.onNodeWithText("Clear shopping list").performClick()
        compose.onNodeWithText("Clear shopping list?").assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithText("Clear shopping list").performClick()
        compose.onNodeWithText("Yes, clear it").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun addsAnItemByHand() {
        show(full)
        compose.onNodeWithText("Add item").performClick()
        compose.onNodeWithText("Item").performTextInput("Milk")
        compose.onNodeWithText("Amount").performTextInput("1")
        compose.onNodeWithText("Unit").performTextInput("gal")
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf("Milk", "1", "gal", ""), added)
    }

    @Test
    fun changesAnAisleOrRemovesAnItem() {
        show(full)
        compose.onNodeWithContentDescription("Edit Flour").performClick()
        compose.onNodeWithText("Aisle").performTextReplacement("Baking aisle")
        compose.onNodeWithText("Save aisle").performClick()
        assertEquals(flour to "Baking aisle", aisle)

        compose.onNodeWithContentDescription("Edit Flour").performClick()
        compose.onNodeWithText("Remove from list").performClick()
        assertEquals(flour, removed)
    }

    @Test
    fun aBadAmountKeepsTheAddDialogOpenWithWhatWasTyped() {
        show(full)
        compose.onNodeWithText("Add item").performClick()
        compose.onNodeWithText("Item").performTextInput("Milk")
        compose.onNodeWithText("Amount").performTextInput("lots")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Couldn't read the amount 'lots' -- try a number like 2, 1/2 or 1.5.").assertIsDisplayed()
        compose.onNodeWithText("Milk").assertIsDisplayed()
        assertEquals(null, added)

        compose.onNodeWithText("Amount").performTextReplacement("2")
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf("Milk", "2", "", ""), added)
    }
}