package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KrogerTest {
    private fun product(
        id: String,
        description: String,
        brand: String? = "Kroger",
        stock: KrogerStock? = KrogerStock.HIGH,
        curbside: Boolean = true,
        delivery: Boolean = true,
        regular: Double? = 4.99,
        promo: Double? = null,
    ) = KrogerProduct(id, "000$id", description, brand, "1 lb", regular, promo, stock, instore = true, curbside = curbside, delivery = delivery)

    @Test
    fun theTermKeepsWhatAStoreNamesAProduct() {
        assertEquals("ground chuck", KrogerQuery.term("ground chuck"))
        assertEquals("onion", KrogerQuery.term("chopped onion"))
        assertEquals("onion", KrogerQuery.term("onion (diced)"))
        assertEquals("boneless chicken breasts", KrogerQuery.term("boneless chicken breasts, cut in strips"))
        assertEquals("venison sausage", KrogerQuery.term("venison sausage or ground beef"))
        assertEquals("ricotta", KrogerQuery.term("ricotta Or mozzarella"))
        assertEquals("orange", KrogerQuery.term("orange"))
        assertEquals("Tomatoes", KrogerQuery.term("Tomatoes [canned"))
        // Nothing left but preparation words: the name as it is.
        assertEquals("large", KrogerQuery.term("large"))
        assertEquals("shredded cheddar cheese", KrogerQuery.term("  shredded   cheddar cheese "))
    }

    @Test
    fun theNameKeyFoldsOnlyAsciiCaseAndSpaces() {
        assertEquals("ground chuck", KrogerQuery.nameKey("  Ground   CHUCK "))
        assertEquals("cr\u00C8me fra\u00EEche", KrogerQuery.nameKey("Cr\u00C8me Fra\u00EEche"))
    }

    @Test
    fun onlyWhatTheStoreOffersThatWayIsShownUnlessAsked() {
        val products = listOf(
            product("1", "Kroger Ground Chuck", curbside = false),
            product("2", "Kroger Ground Chuck 80/20", stock = KrogerStock.OUT),
            product("3", "Ground Chuck Patties"),
            product("4", "Ground Chuck", delivery = false),
        )
        assertEquals(listOf("4", "3"), KrogerRanking.rank("ground chuck", products, KrogerModality.PICKUP).map { it.productId })
        assertEquals(listOf("1", "3"), KrogerRanking.rank("ground chuck", products, KrogerModality.DELIVERY).map { it.productId })
        // Shown on request, after every available one.
        val all = KrogerRanking.rank("ground chuck", products, KrogerModality.PICKUP, showUnavailable = true).map { it.productId }
        assertEquals(listOf("4", "3"), all.take(2))
        assertEquals(setOf("1", "2"), all.drop(2).toSet())
    }

    @Test
    fun theClosestComeFirstThenStockThenKrogersOrder() {
        val products = listOf(
            product("a", "Simple Truth Organic Chicken Broth"),
            product("b", "Kroger Boneless Skinless Chicken Breasts", stock = KrogerStock.LOW),
            product("c", "Boneless Skinless Chicken Breast Tenderloins"),
            product("d", "Kroger Boneless Skinless Chicken Breasts"),
        )
        val ranked = KrogerRanking.rank("boneless skinless chicken breasts", products, KrogerModality.PICKUP).map { it.productId }
        // b and d match alike; d is in stock.
        assertEquals(listOf("d", "b", "c", "a"), ranked)
        // A plural on either side still counts.
        assertEquals(1.0, KrogerRanking.overlap(setOf("onion"), product("x", "Yellow Onions")), 0.0)
        assertEquals(1.0, KrogerRanking.overlap(setOf("berries"), product("y", "Blueberry Berry Mix")), 0.0)
    }

    @Test
    fun aPromoBelowTheRegularPriceIsASale() {
        assertTrue(product("1", "x", promo = 3.99).onSale)
        assertFalse(product("1", "x", promo = 0.0).onSale)
        assertFalse(product("1", "x", promo = 4.99).onSale)
        assertFalse(product("1", "x", regular = null, promo = 1.0).onSale)
        assertEquals("$4.99", krogerPrice(4.99))
        assertEquals("$10.00", krogerPrice(10.0))
    }

    @Test
    fun modalitiesAndStockReadKrogersNames() {
        assertEquals(KrogerModality.DELIVERY, KrogerModality.fromWire("DELIVERY"))
        assertEquals(KrogerModality.PICKUP, KrogerModality.fromWire(null))
        assertEquals(KrogerStock.OUT, KrogerStock.fromWire("TEMPORARILY_OUT_OF_STOCK"))
        assertEquals(null, KrogerStock.fromWire("SOMETHING_NEW"))
    }

    @Test
    fun theShoppingListShowsTheAislesOfThisDevicesStoreOrAllWithoutOne() {
        val matches = listOf(
            KrogerMatch("ground chuck", "1", "1", 1, "AISLE 12", "store-a"),
            KrogerMatch("onion", "2", "2", 1, "Produce", "store-b"),
            KrogerMatch("milk", "3", "3", 1),
        )
        assertEquals(mapOf("ground chuck" to "AISLE 12"), krogerAisles(matches, "store-a"))
        assertEquals(mapOf("ground chuck" to "AISLE 12", "onion" to "Produce"), krogerAisles(matches, null))
        assertEquals(emptyMap<String, String>(), krogerAisles(matches, "store-c"))
    }
}
