package com.naeblis11.mealplanner.kroger

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.shopping.SEND_TO_KROGER
import com.naeblis11.mealplanner.shopping.ShoppingScreen
import com.naeblis11.mealplanner.shopping.ShoppingState
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class KrogerPhoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theSignInOpensTheBrowserAndTakesOnlyItsOwnAnswer() {
        val authorizer = PhoneKrogerAuthorizer(context, timeoutMillis = 5_000)
        assertEquals("mealplanner://kroger", authorizer.redirectUri)
        val url = "https://api.kroger.com/v1/connect/oauth2/authorize?client_id=x&state=s-1"
        val pool = Executors.newSingleThreadExecutor()
        try {
            val answer = pool.submit<Map<String, String>> { authorizer.authorize(url) {} }
            val until = System.currentTimeMillis() + 5000
            var opened: Intent? = null
            while (opened == null && System.currentTimeMillis() < until) {
                opened = shadowOf(context as android.app.Application).nextStartedActivity
                Thread.sleep(10)
            }
            assertEquals(Intent.ACTION_VIEW, opened!!.action)
            assertEquals(url, opened.dataString)
            assertFalse(PhoneKrogerAuthorizer.deliver(Uri.parse("mealplanner://kroger?code=c&state=other")))
            assertFalse(PhoneKrogerAuthorizer.deliver(Uri.parse("https://evil.test/kroger?code=c&state=s-1")))
            assertTrue(PhoneKrogerAuthorizer.deliver(Uri.parse("mealplanner://kroger?code=c-1&state=s-1")))
            assertEquals(mapOf("code" to "c-1", "state" to "s-1"), answer.get(5, TimeUnit.SECONDS))
            // Nothing waits now.
            assertFalse(PhoneKrogerAuthorizer.deliver(Uri.parse("mealplanner://kroger?code=c-1&state=s-1")))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun shopping(send: (() -> Unit)?) = compose.setContent {
        MealPlannerTheme {
            ShoppingScreen(
                state = ShoppingState(emptyList(), emptyList()), message = null, error = null, adding = false, onMessageShown = {},
                onCheck = { _, _ -> }, onAddThisWeek = {}, onAddItem = { _, _, _, _ -> }, onSetAisle = { _, _ -> }, onRemove = {},
                onClear = {}, onSendToKroger = send,
            )
        }
    }

    @Test
    fun theShoppingListHasNoKrogerButtonWithoutKroger() {
        shopping(null)
        compose.onNodeWithText("Add item").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(SEND_TO_KROGER)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theShoppingListsKrogerButtonOpensTheSend() {
        var opened = 0
        shopping { opened++ }
        compose.onNodeWithText(SEND_TO_KROGER).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theReviewShowsKrogersProductAsReturnedAndPicksIt() {
        val kroger = FakeKrogerControls()
        val product = kroger.product("1", "Kroger Ground Chuck 80% Lean")
        val item = SendItem("ground chuck", "Ground chuck", "1 lb Ground chuck", listOf(1L), "Ground chuck", results = listOf(product.copy(promo = 3.49)), searched = true)
        var picked: String? = null
        compose.setContent {
            MealPlannerTheme {
                KrogerSendScreen(
                    SendState.Reviewing(listOf(item), 0, KrogerModality.PICKUP, includePantry = false, pantryCount = 0),
                    SendActions(pick = { picked = it.productId }),
                )
            }
        }
        compose.onNodeWithText("1 lb Ground chuck").assertIsDisplayed()
        compose.onNodeWithText("Kroger Ground Chuck 80% Lean").assertIsDisplayed()
        compose.onNodeWithText("$3.49 on sale (regular $4.99)").assertIsDisplayed()
        compose.onNodeWithText("Kroger Ground Chuck 80% Lean").performClick()
        assertEquals("1", picked)
        compose.onNodeWithText("Review").assertIsDisplayed()
    }

    @Test
    fun eachProductLinksToItsPageOnKrogerComWithoutPickingIt() {
        val kroger = FakeKrogerControls()
        val chuck = kroger.product("1", "Kroger Ground Chuck").copy(pageUrl = "https://www.kroger.com/p/item/1")
        val other = kroger.product("2", "Simple Truth Ground Chuck").copy(pageUrl = "https://www.kroger.com/p/item/2")
        val item = SendItem("ground chuck", "Ground chuck", "Ground chuck", listOf(1L), "Ground chuck", results = listOf(chuck, other), searched = true, pick = chuck, quantity = 1)
        var state: SendState by mutableStateOf(SendState.Reviewing(listOf(item), 0, KrogerModality.PICKUP, includePantry = false, pantryCount = 0))
        val opened = mutableListOf<String>()
        var picked: String? = null
        val uris = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        compose.setContent {
            MealPlannerTheme {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides uris) {
                    KrogerSendScreen(state, SendActions(pick = { picked = it.productId }))
                }
            }
        }
        compose.onNodeWithContentDescription("$KROGER_PAGE_LINK: Simple Truth Ground Chuck").performClick()
        assertEquals(listOf("https://www.kroger.com/p/item/2"), opened)
        assertEquals(null, picked)
        // The summary links the picked product too.
        state = SendState.Summary(listOf(item), KrogerModality.PICKUP)
        compose.onNodeWithContentDescription("$KROGER_PAGE_LINK: Kroger Ground Chuck").performClick()
        assertEquals("https://www.kroger.com/p/item/1", opened.last())
    }

    @Test
    fun howManyIsInThePickedProductsCardAndOnTheSummary() {
        val kroger = FakeKrogerControls()
        val chuck = kroger.product("1", "Kroger Ground Chuck")
        val other = kroger.product("2", "Simple Truth Ground Chuck")
        val item = SendItem("ground chuck", "Ground chuck", "Ground chuck", listOf(1L), "Ground chuck", results = listOf(chuck, other), searched = true, pick = chuck, quantity = 2)
        var state: SendState by mutableStateOf(SendState.Reviewing(listOf(item), 0, KrogerModality.PICKUP, includePantry = false, pantryCount = 0))
        var set: Int? = null
        var setFor: Pair<String, Int>? = null
        compose.setContent {
            MealPlannerTheme {
                KrogerSendScreen(state, SendActions(setQuantity = { set = it }, setQuantityFor = { k, q -> setFor = k to q }))
            }
        }
        // One stepper, the picked product's.
        compose.onAllNodesWithContentDescription("One more").assertCountEquals(1)
        compose.onNodeWithContentDescription("Quantity 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("One more").performClick()
        assertEquals(3, set)
        state = SendState.Summary(listOf(item), KrogerModality.PICKUP)
        compose.onNodeWithContentDescription("One fewer").performClick()
        assertEquals("ground chuck" to 1, setFor)
    }

    @Test
    fun theSummarysOneButtonNamesHowManyGo() {
        val kroger = FakeKrogerControls()
        val p = kroger.product("1", "Kroger Ground Chuck")
        val items = listOf(
            SendItem("ground chuck", "Ground chuck", "Ground chuck", listOf(1L), "Ground chuck", pick = p, quantity = 2),
            SendItem("onion", "Onion", "Onion", listOf(2L), "Onion", skipped = true),
        )
        var added = 0
        compose.setContent {
            MealPlannerTheme { KrogerSendScreen(SendState.Summary(items, KrogerModality.DELIVERY), SendActions(addToCart = { added++ })) }
        }
        compose.onNodeWithText("2 \u00D7 Kroger Ground Chuck").assertIsDisplayed()
        compose.onNodeWithText("Not sent").assertIsDisplayed()
        compose.onNodeWithText(addLabel(1)).performClick()
        assertEquals(1, added)
    }
}
