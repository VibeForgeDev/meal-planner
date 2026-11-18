package com.naeblis11.mealplanner.kroger

import com.naeblis11.mealplanner.app.MemorySettings
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerStock
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KrogerAccountTest {
    private val fake = FakeKroger()
    private val tokens = MemoryKrogerTokens()
    private val clients = MemoryKrogerClients()
    private val settings = MemorySettings()
    private var now = 1_000_000L
    private val authorizer = FakeAuthorizer { fake.consent(it) }

    private fun account(builtIn: KrogerClient? = fake.client) =
        KrogerAccount(fake.http, { fake.endpoints }, builtIn, clients, tokens, settings, authorizer, clock = { now })

    @After
    fun tearDown() = fake.close()

    private val store = KrogerStore("01400943", "Kroger - Main St", "1 Main St, Springfield, OH 45000")

    @Test
    fun aProductCarriesItsAisleAndItsFeaturedPictureFromKrogersOwnHost() {
        val a = account()
        a.chooseStore(store)
        fake.product("0001", "Kroger Ground Chuck", aisle = "AISLE 12")
        fake.product("0002", "Yellow Onions")
        val found = a.products(listOf("0001", "0002")).associateBy { it.productId }
        assertEquals("AISLE 12", found.getValue("0001").aisle)
        assertNull(found.getValue("0002").aisle)
        // The featured (front) picture, at medium.
        assertEquals("https://www.kroger.com/product/images/medium/front/0001", found.getValue("0001").imageUrl)
    }

    @Test
    fun anAisleWithoutADescriptionIsNamedByItsNumberAndAPictureElsewhereIsDropped() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"productId":"1","upc":"1","description":"Milk","aisleLocations":[{"number":"7"}],
                "images":[{"perspective":"front","sizes":[{"size":"small","url":"https://images.example.com/1.jpg"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        val p = KrogerApi.product(o)!!
        assertEquals("Aisle 7", p.aisle)
        assertNull(p.imageUrl)
        assertTrue(KrogerEndpoints.isImageUrl("https://www.kroger.com/product/images/small/front/1"))
        assertFalse(KrogerEndpoints.isImageUrl("http://www.kroger.com/product/images/small/front/1"))
        assertFalse(KrogerEndpoints.isImageUrl("https://www.kroger.com.example.com/1.jpg"))
        assertFalse(KrogerEndpoints.isImageUrl("https://notkroger.com/1.jpg"))
        assertFalse(KrogerEndpoints.isImageUrl("https://user@www.kroger.com/1.jpg"))
        assertFalse(KrogerEndpoints.isImageUrl("https://www.kroger.com:8443/1.jpg"))
    }

    @Test
    fun aPictureIsFetchedWithoutCredentialsAndOnlyAnImageIsTaken() {
        val a = account()
        val png = byteArrayOf(1, 2, 3)
        fake.images["a.png"] = "image/png" to png
        fake.images["page"] = "text/html" to "<html>".toByteArray()
        fake.images["huge"] = "image/jpeg" to ByteArray(KrogerHttp.MAX_IMAGE_BYTES + 1)
        val base = fake.endpoints.base.removeSuffix("/v1") + "/images/"
        assertTrue(png.contentEquals(a.image(base + "a.png")))
        assertNull(a.image(base + "page"))
        assertNull(a.image(base + "huge"))
        assertNull(a.image(base + "missing"))
        // Never anywhere but a picture host: nothing is sent.
        val before = fake.requests.size
        assertNull(KrogerAccount(KrogerHttp(), { fake.endpoints }, fake.client, clients, tokens, settings, authorizer).image(base + "a.png"))
        assertEquals(before, fake.requests.size)
    }

    @Test
    fun signingInKeepsOnlyTheRefreshTokenAndSaysSo() {
        val a = account()
        assertTrue(a.status.value.clientReady)
        assertFalse(a.status.value.signedIn)
        var shown: String? = null
        a.signIn { shown = it }
        assertTrue(shown!!.startsWith(fake.endpoints.authorize + "?"))
        assertTrue(shown!!.contains("code_challenge_method=S256"))
        assertTrue(shown!!.contains("scope=cart.basic%3Awrite%20product.compact"))
        assertEquals("refresh-1", tokens.token)
        assertTrue(a.status.value.signedIn)
        assertFalse(a.status.value.ready)
        a.chooseStore(store)
        assertTrue(a.status.value.ready)
        // A new account (the next start) reads the same.
        assertEquals(store, account().status.value.store)
    }

    @Test
    fun aSignInWhoseStateDoesntComeBackIsRefused() {
        val a = KrogerAccount(fake.http, { fake.endpoints }, fake.client, clients, tokens, settings, FakeAuthorizer { fake.consent(it, state = "other") })
        val e = assertThrows(KrogerException::class.java) { a.signIn {} }
        assertEquals(KrogerMessages.SIGN_IN_FAILED, e.message)
        assertNull(tokens.token)
    }

    @Test
    fun withoutAClientNothingIsAsked() {
        val a = account(builtIn = null)
        assertFalse(a.status.value.clientReady)
        assertEquals(KrogerMessages.NO_CLIENT, assertThrows(KrogerException::class.java) { a.signIn {} }.message)
        assertTrue(fake.requests.isEmpty())
        // A client set up by hand wins, and is shown.
        a.useClient("  test-client ", "test-secret")
        assertEquals("test-client", a.status.value.customClientId)
        assertTrue(a.status.value.clientReady)
        assertThrows(IllegalArgumentException::class.java) { a.useClient("two words", "x") }
        a.forgetClient()
        assertFalse(a.status.value.clientReady)
    }

    @Test
    fun storesSearchesAndLookupsUseTheAppsOwnTokenOnce() {
        val a = account()
        val found = a.stores(" 45000 ")
        assertEquals(listOf(store), found)
        a.chooseStore(found.single())
        fake.product("0001", "Kroger Ground Chuck", promo = 3.99, stock = "LOW")
        fake.product("0002", "Yellow Onions")
        val results = a.search("ground chuck")
        assertEquals(listOf("0001"), results.map { it.productId })
        val p = results.single()
        assertEquals(4.99, p.regular!!, 0.0)
        assertEquals(3.99, p.promo!!, 0.0)
        assertEquals(KrogerStock.LOW, p.stock)
        assertEquals("1 lb", p.size)
        assertTrue(p.curbside && p.delivery && p.instore)
        assertEquals(listOf("0002", "0001"), a.products(listOf("0002", "0001")).map { it.productId }.sorted().reversed())
        assertEquals(1, fake.appTokens)
        assertTrue(fake.requests.any { it.contains("filter.locationId=01400943") })
        assertThrows(IllegalArgumentException::class.java) { a.stores("4500") }
        assertThrows(IllegalArgumentException::class.java) { a.search("ab") }
    }

    @Test
    fun theAppsTokenIsMadeAgainWhenItEndsOrIsRefused() {
        val a = account()
        a.chooseStore(store)
        a.search("onion")
        now += 30 * 60_000L
        a.search("onion")
        assertEquals(2, fake.appTokens)
        fake.appToken = "app-2" // Kroger revoked the old one.
        a.search("onion")
        assertEquals(3, fake.appTokens)
    }

    @Test
    fun theCartGetsEveryItemAtOnceTheChosenWay() {
        val a = account()
        a.signIn {}
        a.chooseStore(store)
        a.setModality(KrogerModality.DELIVERY)
        a.addToCart(listOf(KrogerCartItem("0001", 2), KrogerCartItem("0002", 1)))
        assertEquals(listOf("0001", "0002"), fake.cartAdds.map { it.getValue("upc").toString().trim('"') })
        assertTrue(fake.cartAdds.all { it.getValue("modality").toString() == "\"DELIVERY\"" })
        assertEquals(KrogerModality.DELIVERY, account().status.value.modality)
    }

    @Test
    fun anEndedAccessTokenIsRefreshedAndTheNewRefreshTokenKept() {
        val a = account()
        a.signIn {}
        a.chooseStore(store)
        now += 31 * 60_000L
        a.addToCart(listOf(KrogerCartItem("0001", 1)))
        assertEquals(1, fake.refreshes)
        assertEquals("refresh-2", tokens.token)
        // Refused once (revoked early): one refresh, one more try.
        fake.rejectNextCartToken = true
        a.addToCart(listOf(KrogerCartItem("0002", 1)))
        assertEquals(2, fake.refreshes)
        assertEquals(2, fake.cartAdds.size)
    }

    @Test
    fun aRefusedRefreshSignsOut() {
        val a = account()
        a.signIn {}
        now += 31 * 60_000L
        fake.refuseRefresh = true
        assertThrows(KrogerSignInException::class.java) { a.addToCart(listOf(KrogerCartItem("0001", 1))) }
        assertNull(tokens.token)
        assertFalse(a.status.value.signedIn)
    }

    @Test
    fun krogersLimitAndOutagesAreSentences() {
        val a = account()
        a.chooseStore(store)
        fake.productsStatus = 429
        assertEquals(KrogerMessages.LIMIT, assertThrows(KrogerLimitException::class.java) { a.search("onion") }.message)
        fake.productsStatus = 503
        assertEquals(KrogerMessages.UNAVAILABLE, assertThrows(KrogerException::class.java) { a.search("onion") }.message)
        fake.close()
        assertEquals(KrogerMessages.OFFLINE, assertThrows(KrogerException::class.java) { a.search("onion again") }.message)
    }

    @Test
    fun aSignInThatCantBeReadHereCountsAsSignedOut() {
        tokens.token = "refresh-1"
        tokens.failRead = true
        assertFalse(account().status.value.signedIn)
    }

    @Test
    fun withoutAStoreSearchingSaysToChooseOne() {
        assertEquals(KrogerMessages.NO_STORE, assertThrows(KrogerException::class.java) { account().search("onion") }.message)
    }

    @Test
    fun onlyKrogersHostIsEverContacted() {
        val http = KrogerHttp()
        http.allowed("https://api.kroger.com/v1/products")
        for (bad in listOf("http://api.kroger.com/v1", "https://api.kroger.com.evil.test/v1", "https://www.kroger.com/", "https://api.kroger.com:8443/v1")) {
            assertThrows(bad, IOException::class.java) { http.allowed(bad) }
        }
        http.allowed("https://api-ce.kroger.com/v1/products")
        assertEquals("https://api.kroger.com/v1", KrogerEndpoints.KROGER.base)
        assertEquals("https://api-ce.kroger.com/v1", KrogerEndpoints.CERTIFICATION.base)
        assertEquals(setOf("api.kroger.com", "api-ce.kroger.com"), KrogerEndpoints.KROGER_HOSTS)
        assertEquals(KrogerEndpoints.KROGER, KrogerEndpoints.forClient(KrogerClient("a", "b")))
        assertEquals(KrogerEndpoints.CERTIFICATION, KrogerEndpoints.forClient(KrogerClient("a", "b", certification = true)))
    }

    @Test
    fun theBuiltInClientIsReadFromItsResourceOrNotUsed() {
        val logged = mutableListOf<String>()
        assertEquals(KrogerClient("id-1", "s-1"), KrogerClients.builtIn({ """{"client_id":"id-1","client_secret":"s-1"}""" }, { logged += it }))
        assertEquals(
            KrogerClient("id-1", "s-1", certification = true),
            KrogerClients.builtIn({ """{"client_id":"id-1","client_secret":"s-1","environment":"certification"}""" }, { logged += it }),
        )
        assertNull(KrogerClients.builtIn({ null }, { logged += it }))
        assertNull(KrogerClients.builtIn({ """{"client_id":"id 1","client_secret":"s-1"}""" }, { logged += it }))
        assertTrue(logged.single(), logged.single().contains("isn't a client id and secret"))
        assertFalse(KrogerClient("id-1", "s-1").toString().contains("s-1"))
    }

    @Test
    fun aTestClientFindsPricesButNeverSignsInOrAddsToTheCart() {
        val a = KrogerAccount(fake.http, { fake.endpoints }, null, MemoryKrogerClients(KrogerClient(fake.client.id, fake.client.secret, certification = true)), tokens, settings, authorizer)
        assertTrue(a.status.value.certification)
        a.chooseStore(store)
        fake.product("0002", "Yellow Onions")
        assertEquals(1, a.search("onion").size)
        assertEquals(KrogerMessages.CERTIFICATION_NO_CART, assertThrows(KrogerException::class.java) { a.signIn {} }.message)
        tokens.token = "refresh-1"
        assertEquals(KrogerMessages.CERTIFICATION_NO_CART, assertThrows(KrogerException::class.java) { a.addToCart(listOf(KrogerCartItem("0002", 1))) }.message)
        assertTrue(fake.cartAdds.isEmpty())
    }
}
