package com.naeblis11.mealplanner.kroger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Kroger's public API on a 127.0.0.1 ephemeral port, for tests only (never the network): tokens (client credentials,
 * authorization code with PKCE, refresh, rotating the refresh token), Locations, Products (term and productId), and
 * the cart's add. It checks the client's Basic credentials and the bearer token of every call.
 */
class FakeKroger(val client: KrogerClient = KrogerClient("test-client", "test-secret")) : Closeable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val endpoints = KrogerEndpoints("http://127.0.0.1:${server.address.port}/v1")
    val http = KrogerHttp(hosts = setOf("127.0.0.1"), httpsOnly = false, imageHost = { it == "127.0.0.1" })

    /** The products the store has, as Products' `data` objects. */
    val products = CopyOnWriteArrayList<JsonObject>()

    /** Every cart add's `items`, as received. */
    val cartAdds = CopyOnWriteArrayList<JsonObject>()

    /** Every request's path and query, in order. */
    val requests = CopyOnWriteArrayList<String>()

    var appTokens = 0
    var refreshes = 0
    var appToken = "app-1"
    var userAccess = "user-1"
    var refreshToken = "refresh-1"

    /** The code the next consent gives, and the PKCE challenge and redirect it was asked for. */
    var code = "code-1"
    var challenge: String? = null
    var redirect: String? = null

    /** The next calls' answers when set: a status for every Products call, a refusal of refresh, one cart 401. */
    var productsStatus: Int? = null
    var refuseRefresh = false
    var rejectNextCartToken = false

    init {
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                reply(ex, 500, "{}")
            }
        }
        server.start()
    }

    /** The pictures served under /images/NAME, with their content type; anything else there is a 404. */
    val images = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ByteArray>>()

    private fun handle(ex: HttpExchange) {
        if (ex.requestURI.path.startsWith("/images/")) {
            requests += ex.requestURI.toString()
            val image = images[ex.requestURI.path.removePrefix("/images/")]
            if (image == null || ex.requestHeaders.getFirst("Authorization") != null) return reply(ex, 404, "{}")
            ex.responseHeaders.add("Content-Type", image.first)
            ex.sendResponseHeaders(200, image.second.size.toLong())
            ex.responseBody.use { it.write(image.second) }
            return
        }
        val path = ex.requestURI.path.removePrefix("/v1")
        val query = parse(ex.requestURI.rawQuery.orEmpty())
        requests += ex.requestURI.toString()
        val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
        when {
            path == "/connect/oauth2/token" && ex.requestMethod == "POST" -> {
                val basic = "Basic " + Base64.getEncoder().encodeToString("${client.id}:${client.secret}".toByteArray())
                if (auth != basic) return reply(ex, 401, """{"error":"invalid_client"}""")
                val form = parse(String(ex.requestBody.readBytes(), Charsets.UTF_8))
                when (form["grant_type"]) {
                    "client_credentials" -> {
                        appTokens++
                        reply(ex, 200, """{"access_token":"$appToken","expires_in":1800,"token_type":"bearer"}""")
                    }
                    "authorization_code" -> {
                        val verifier = form["code_verifier"].orEmpty()
                        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
                        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
                        if (form["code"] != code || expected != challenge || form["redirect_uri"] != redirect) {
                            return reply(ex, 400, """{"error":"invalid_grant"}""")
                        }
                        reply(ex, 200, """{"access_token":"$userAccess","expires_in":1800,"refresh_token":"$refreshToken"}""")
                    }
                    "refresh_token" -> {
                        if (refuseRefresh || form["refresh_token"] != refreshToken) return reply(ex, 400, """{"error":"invalid_grant"}""")
                        refreshes++
                        userAccess = "user-${refreshes + 1}"
                        refreshToken = "refresh-${refreshes + 1}"
                        reply(ex, 200, """{"access_token":"$userAccess","expires_in":1800,"refresh_token":"$refreshToken"}""")
                    }
                    else -> reply(ex, 400, "{}")
                }
            }
            path == "/locations" -> {
                if (auth != "Bearer $appToken") return reply(ex, 401, "{}")
                reply(
                    ex, 200,
                    """{"data":[{"locationId":"01400943","chain":"KROGER","name":"Kroger - Main St","address":{"addressLine1":"1 Main St","city":"Springfield","state":"OH","zipCode":"${query["filter.zipCode.near"]}"}}]}""",
                )
            }
            path == "/products" -> {
                if (auth != "Bearer $appToken") return reply(ex, 401, "{}")
                productsStatus?.let { return reply(ex, it, "{}") }
                if (query["filter.locationId"].isNullOrEmpty()) return reply(ex, 400, "{}")
                val ids = query["filter.productId"]?.split(',')
                val term = query["filter.term"]?.lowercase()
                val found = products.filter { p ->
                    val id = p.getValue("productId").toString().trim('"')
                    if (ids != null) id in ids else term != null && term.split(' ').any { p.toString().lowercase().contains(it) }
                }
                val start = query["filter.start"]?.toInt() ?: 0
                val limit = query["filter.limit"]?.toInt() ?: 10
                reply(ex, 200, """{"data":[${found.drop(start).take(limit).joinToString(",")}]}""")
            }
            path == "/cart/add" && ex.requestMethod == "PUT" -> {
                if (rejectNextCartToken) { rejectNextCartToken = false; return reply(ex, 401, "{}") }
                if (auth != "Bearer $userAccess") return reply(ex, 401, "{}")
                val body = Json.parseToJsonElement(String(ex.requestBody.readBytes(), Charsets.UTF_8)).jsonObject
                body.getValue("items").jsonArray.forEach { cartAdds += it.jsonObject }
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
            else -> reply(ex, 404, "{}")
        }
    }

    /** What a browser does on Kroger's consent page: remembers the challenge and redirect, answers with [code]. */
    fun consent(url: String, state: String? = null): Map<String, String> {
        val q = parse(java.net.URI(url).rawQuery)
        challenge = q["code_challenge"]
        redirect = q["redirect_uri"]
        return mapOf("code" to code, "state" to (state ?: q.getValue("state")))
    }

    fun product(
        id: String,
        description: String,
        regular: Double = 4.99,
        promo: Double = 0.0,
        stock: String = "HIGH",
        curbside: Boolean = true,
        delivery: Boolean = true,
        aisle: String? = null,
    ): JsonObject = Json.parseToJsonElement(
        """{"productId":"$id","upc":"$id","brand":"Kroger","description":"$description","items":[{"itemId":"$id","size":"1 lb",
            "price":{"regular":$regular,"promo":$promo},"inventory":{"stockLevel":"$stock"},
            "fulfillment":{"curbside":$curbside,"delivery":$delivery,"instore":true,"shiptohome":false}}]""" +
            (aisle?.let { ""","aisleLocations":[{"description":"$it","number":"12","side":"L"}]""" } ?: "") +
            ""","images":[{"perspective":"back","featured":false,"sizes":[{"size":"medium","url":"https://www.kroger.com/product/images/medium/back/$id"}]},
            {"perspective":"front","featured":true,"sizes":[{"size":"thumbnail","url":"https://www.kroger.com/product/images/thumbnail/front/$id"},
            {"size":"medium","url":"https://www.kroger.com/product/images/medium/front/$id"}]}]}""",
    ).jsonObject.also { products += it }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parse(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        val k = if (i < 0) it else it.substring(0, i)
        val v = if (i < 0) "" else it.substring(i + 1)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    override fun close() = server.stop(0)
}

/** A token store in memory; [failRead] stands for a file sealed by another Windows user. */
class MemoryKrogerTokens(var token: String? = null) : KrogerTokenStore {
    var failRead = false

    override fun load(): String? = if (failRead) throw java.io.IOException("sealed elsewhere") else token

    override fun save(refreshToken: String) {
        token = refreshToken
    }

    override fun delete() {
        token = null
    }
}

class MemoryKrogerClients(var client: KrogerClient? = null) : KrogerClientStore {
    override fun load(): KrogerClient? = client

    override fun save(client: KrogerClient) {
        this.client = client
    }

    override fun delete() {
        client = null
    }
}

/** A browser that consents at once through [fake], or answers what [answer] makes of the consent page's address. */
class FakeAuthorizer(private val answer: (String) -> Map<String, String>) : KrogerAuthorizer {
    override val redirectUri = "http://localhost:5070/kroger"
    var cancelled = false

    override fun authorize(url: String, onUrl: (String) -> Unit): Map<String, String> {
        onUrl(url)
        return answer(url)
    }

    override fun cancel() {
        cancelled = true
    }
}
