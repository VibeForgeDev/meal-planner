package com.naeblis11.mealplanner.kroger

import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import com.naeblis11.mealplanner.domain.KrogerStock
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A Kroger developer application (developer.kroger.com): the one built into the app, or one set up by hand. */
data class KrogerClient(val id: String, val secret: String, val certification: Boolean = false) {
    // A data class would print the secret.
    override fun toString(): String = "KrogerClient(id=$id, certification=$certification)"
}

/** A store, as Kroger's Locations API returned it: its id, its name and its address, shown as given. */
data class KrogerStore(val locationId: String, val name: String, val address: String)

/** One line of an add to the cart: the product's UPC and how many. */
data class KrogerCartItem(val upc: String, val quantity: Int)

/** A sign-in's tokens: the access token, how long it lasts, and the refresh token when Kroger gave a new one. */
class KrogerTokens(val access: String, val expiresInSeconds: Long, val refresh: String?) {
    override fun toString(): String = "KrogerTokens(expiresIn=$expiresInSeconds)"
}

/** A Kroger failure, in a sentence for the user; never a token, a code or the client secret. */
open class KrogerException(message: String) : IOException(message)

/** Kroger refused the sign-in (revoked, expired): only signing in again helps. */
class KrogerSignInException(message: String = KrogerMessages.SIGN_IN_AGAIN) : KrogerException(message)

/** Kroger's daily limit for this application is used up. */
class KrogerLimitException : KrogerException(KrogerMessages.LIMIT)

/**
 * Kroger's public API (plan 2026-11-17): the application's own token (client credentials, `product.compact`), kept
 * in memory until a minute before it expires; stores near a ZIP code; product search and lookup at a store; the
 * customer's sign-in (authorization code with PKCE) and its refresh; and adding to the customer's cart. Blocking: call
 * it off the UI thread. What Products returns is handed back as it came and never kept here.
 */
class KrogerApi(
    private val http: KrogerHttp,
    private val endpointsFor: (KrogerClient) -> KrogerEndpoints,
    private val client: () -> KrogerClient?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var appToken: String? = null
    private var appTokenFor: String? = null
    private var appTokenUntil = 0L

    private fun clientOrThrow(): KrogerClient = client() ?: throw KrogerException(KrogerMessages.NO_CLIENT)

    // The client's environment; a sign-in or a cart needs production, as certification has no customer accounts.
    private val endpoints: KrogerEndpoints get() = endpointsFor(clientOrThrow())

    private fun customerEndpoints(): KrogerEndpoints {
        val c = clientOrThrow()
        if (c.certification) throw KrogerException(KrogerMessages.CERTIFICATION_NO_CART)
        return endpointsFor(c)
    }

    /** The application's token, for Locations and Products. */
    @Synchronized
    fun appToken(): String {
        val c = clientOrThrow()
        appToken?.let { if (appTokenFor == c.id && clock() < appTokenUntil) return it }
        val reply = http.form(endpoints.token, KrogerHttp.basic(c), mapOf("grant_type" to "client_credentials", "scope" to PRODUCT_SCOPE))
        if (reply.status == 400 || reply.status == 401) throw KrogerException(KrogerMessages.CLIENT_REFUSED)
        check(reply)
        val tokens = tokens(reply.json(), wantRefresh = false)
        appToken = tokens.access
        appTokenFor = c.id
        appTokenUntil = clock() + tokens.expiresInSeconds * 1000 - 60_000
        return tokens.access
    }

    /** The stores near [zip] (five digits), nearest first, at most [limit]. */
    fun stores(zip: String, limit: Int = 10): List<KrogerStore> {
        require(zip.length == 5 && zip.all { it in '0'..'9' }) { KrogerMessages.BAD_ZIP }
        val url = endpoints.locations + "?" + KrogerHttp.query(mapOf("filter.zipCode.near" to zip, "filter.limit" to limit.toString()))
        return data(withAppToken { http.get(url, it) }).mapNotNull(::store)
    }

    /**
     * Kroger's search for [term] at [locationId], [limit] from [start]. Kroger wants at least three letters and at most
     * eight words; a shorter term is a sentence, a longer one is cut to its first eight words.
     */
    fun search(term: String, locationId: String, start: Int = 0, limit: Int = PAGE): List<KrogerProduct> {
        val words = term.split(' ').filter { it.isNotEmpty() }.take(MAX_TERM_WORDS)
        val cleaned = words.joinToString(" ")
        require(cleaned.length >= 3) { KrogerMessages.SHORT_TERM }
        val url = endpoints.products + "?" + KrogerHttp.query(
            mapOf("filter.term" to cleaned, "filter.locationId" to locationId, "filter.start" to start.toString(), "filter.limit" to limit.toString()),
        )
        return data(withAppToken { http.get(url, it) }).mapNotNull(::product)
    }

    /** The products [ids] at [locationId], as they are there now (remembered matches), in lookups of at most 50. */
    fun products(ids: Collection<String>, locationId: String): List<KrogerProduct> =
        ids.distinct().chunked(MAX_IDS).flatMap { chunk ->
            val url = endpoints.products + "?" + KrogerHttp.query(
                mapOf("filter.productId" to chunk.joinToString(","), "filter.locationId" to locationId, "filter.limit" to MAX_IDS.toString()),
            )
            data(withAppToken { http.get(url, it) }).mapNotNull(::product)
        }

    /** Kroger's consent page for the customer's sign-in. */
    fun authorizeUrl(redirectUri: String, challenge: String, state: String): String =
        customerEndpoints().authorize + "?" + KrogerHttp.query(
            mapOf(
                "scope" to USER_SCOPES, "response_type" to "code", "client_id" to clientOrThrow().id, "redirect_uri" to redirectUri,
                "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state,
            ),
        )

    /** The sign-in's code swapped for the customer's tokens. */
    fun exchange(code: String, redirectUri: String, verifier: String): KrogerTokens {
        val reply = http.form(
            customerEndpoints().token, KrogerHttp.basic(clientOrThrow()),
            mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri, "code_verifier" to verifier),
        )
        if (reply.status == 400 || reply.status == 401) throw KrogerException(KrogerMessages.SIGN_IN_FAILED)
        check(reply)
        return tokens(reply.json(), wantRefresh = true)
    }

    /** A new access token for the customer; Kroger may give a new refresh token too, which then replaces the old. */
    fun refresh(refreshToken: String): KrogerTokens {
        val reply = http.form(customerEndpoints().token, KrogerHttp.basic(clientOrThrow()), mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
        if (reply.status == 400 || reply.status == 401) throw KrogerSignInException()
        check(reply)
        return tokens(reply.json(), wantRefresh = false)
    }

    /**
     * Adds [items] to the signed-in customer's cart, for [modality], in one call. False when Kroger refused the access
     * token (401): the caller refreshes it and tries once more.
     */
    fun addToCart(accessToken: String, items: List<KrogerCartItem>, modality: KrogerModality): Boolean {
        require(items.isNotEmpty() && items.all { it.upc.isNotEmpty() && it.quantity in 1..MAX_QUANTITY }) { "Nothing to add." }
        val body = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    items.forEach { i -> add(buildJsonObject { put("upc", i.upc); put("quantity", i.quantity); put("modality", modality.wire) }) }
                },
            )
        }
        val reply = http.putJson(customerEndpoints().cartAdd, accessToken, body.toString())
        if (reply.status == 401) return false
        if (reply.status == 403) throw KrogerSignInException(KrogerMessages.CART_NOT_ALLOWED)
        check(reply)
        return true
    }

    // The application's token, made again once when Kroger refuses it (it may have been revoked or expired early).
    private fun withAppToken(call: (String) -> KrogerHttp.Reply): KrogerHttp.Reply {
        val first = call(appToken())
        if (first.status != 401) return first.also(::check)
        synchronized(this) { appToken = null }
        return call(appToken()).also(::check)
    }

    private fun check(reply: KrogerHttp.Reply) {
        when {
            reply.status in 200..299 -> Unit
            reply.status == 429 -> throw KrogerLimitException()
            reply.status >= 500 -> throw KrogerException(KrogerMessages.UNAVAILABLE)
            else -> throw KrogerException("Kroger refused the request (${reply.status}).")
        }
    }

    private fun tokens(o: JsonObject, wantRefresh: Boolean): KrogerTokens {
        val access = o.text("access_token") ?: throw KrogerException(KrogerMessages.NO_TOKEN)
        val refresh = o.text("refresh_token")
        if (wantRefresh && refresh == null) throw KrogerException(KrogerMessages.NO_TOKEN)
        val seconds = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 1800L
        return KrogerTokens(access, seconds.coerceIn(60L, 86_400L), refresh)
    }

    private fun data(reply: KrogerHttp.Reply): List<JsonObject> =
        (reply.json()["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    companion object {
        const val PRODUCT_SCOPE = "product.compact"
        const val USER_SCOPES = "cart.basic:write product.compact"
        const val PAGE = 10
        const val MAX_IDS = 50
        const val MAX_TERM_WORDS = 8
        const val MAX_QUANTITY = 99

        /** A store from Locations' `data`, or null when it has no id. */
        fun store(o: JsonObject): KrogerStore? {
            val id = o.text("locationId")?.takeIf { it.isNotEmpty() } ?: return null
            val a = o["address"] as? JsonObject
            val address = listOfNotNull(
                a?.text("addressLine1"),
                listOfNotNull(a?.text("city"), listOfNotNull(a?.text("state"), a?.text("zipCode")).joinToString(" ").ifEmpty { null })
                    .joinToString(", ").ifEmpty { null },
            ).joinToString(", ")
            return KrogerStore(id, o.text("name") ?: o.text("chain") ?: id, address)
        }

        /** The picture sizes tried, in order: medium is sharp in a 56 dp square on a phone and still small. */
        val IMAGE_SIZES = listOf("medium", "small", "thumbnail", "large")

        /**
         * A product's picture: the featured one (else the front, else the first), at the first of [IMAGE_SIZES] Kroger
         * has; null when there is none or it isn't on a kroger.com host (KrogerEndpoints.isImageUrl).
         */
        fun imageUrl(o: JsonObject): String? {
            val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val chosen = images.firstOrNull { it.flag("featured") == true }
                ?: images.firstOrNull { it.text("perspective") == "front" }
                ?: images.firstOrNull()
                ?: return null
            val sizes = (chosen["sizes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val url = IMAGE_SIZES.firstNotNullOfOrNull { size -> sizes.firstOrNull { it.text("size") == size }?.text("url") }
                ?: sizes.firstNotNullOfOrNull { it.text("url") }
            return url?.takeIf(KrogerEndpoints::isImageUrl)
        }

        /**
         * The product's page on kroger.com: Products' `productPageURI` (a path on www.kroger.com, or a whole kroger.com
         * address), else kroger.com's page for its product id; null when neither is an https kroger.com address.
         */
        fun pageUrl(o: JsonObject, productId: String): String? {
            o.text("productPageURI")?.trim()?.takeIf { it.isNotEmpty() }?.let { given ->
                val full = if (given.startsWith("/")) "$KROGER_SITE$given" else given
                if (KrogerEndpoints.isPageUrl(full)) return full
            }
            return if (PRODUCT_ID.matches(productId)) "$KROGER_SITE/p/item/$productId" else null
        }

        /** Where the store shelves it, from its first aisle location: Kroger's description ("AISLE 12"), else "Aisle 12". */
        fun aisle(o: JsonObject): String? {
            val first = (o["aisleLocations"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            first.text("description")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return first.text("number")?.trim()?.takeIf { it.isNotEmpty() && it != "0" }?.let { "Aisle $it" }
        }

        /** A product from Products' `data`, its first item's size, price, stock and fulfillment; null without an id or UPC. */
        fun product(o: JsonObject): KrogerProduct? {
            val id = o.text("productId")?.takeIf { it.isNotEmpty() } ?: return null
            val upc = o.text("upc")?.takeIf { it.isNotEmpty() } ?: return null
            val item = (o["items"] as? JsonArray)?.firstOrNull() as? JsonObject
            val price = item?.get("price") as? JsonObject
            val fulfillment = item?.get("fulfillment") as? JsonObject
            return KrogerProduct(
                productId = id,
                upc = upc,
                description = o.text("description") ?: id,
                brand = o.text("brand")?.takeIf { it.isNotEmpty() },
                size = item?.text("size")?.takeIf { it.isNotEmpty() },
                regular = price?.number("regular")?.takeIf { it > 0.0 },
                promo = price?.number("promo")?.takeIf { it > 0.0 },
                stock = KrogerStock.fromWire(((item?.get("inventory") as? JsonObject))?.text("stockLevel")),
                instore = fulfillment?.flag("instore") == true,
                curbside = fulfillment?.flag("curbside") == true,
                delivery = fulfillment?.flag("delivery") == true,
                aisle = aisle(o),
                imageUrl = imageUrl(o),
                pageUrl = pageUrl(o, id),
            )
        }

        private const val KROGER_SITE = "https://www.kroger.com"
        private val PRODUCT_ID = Regex("[A-Za-z0-9]{1,32}")

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

        private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
}
