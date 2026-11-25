package com.naeblis11.mealplanner.kroger

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Where Kroger's public API is. Tests point it at FakeKroger. */
data class KrogerEndpoints(val base: String) {
    val authorize: String get() = "$base/connect/oauth2/authorize"
    val token: String get() = "$base/connect/oauth2/token"
    val locations: String get() = "$base/locations"
    val products: String get() = "$base/products"
    val cartAdd: String get() = "$base/cart/add"

    companion object {
        val KROGER = KrogerEndpoints("https://api.kroger.com/v1")

        /**
         * Kroger's certification environment, for a client registered for testing (developer.kroger.com, "API Basics"):
         * Products and Locations only, since it has no customer accounts, so no sign-in and no cart.
         */
        val CERTIFICATION = KrogerEndpoints("https://api-ce.kroger.com/v1")

        /** The only hosts Meal Planner ever sends a Kroger request to. */
        val KROGER_HOSTS: Set<String> = setOf("api.kroger.com", "api-ce.kroger.com")

        /**
         * Whether [host] may serve a product picture: Kroger's own kroger.com and its subdomains (www.kroger.com today),
         * the only other place Meal Planner fetches from for Kroger, and only a picture, with no credentials.
         */
        fun isImageHost(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT)
            return h == "kroger.com" || (h.endsWith(".kroger.com") && h.length > ".kroger.com".length)
        }

        /** Whether [url] is a product page Meal Planner may open: the same rule as a picture's. */
        fun isPageUrl(url: String): Boolean = isImageUrl(url)

        /** Whether [url] is a picture Meal Planner may show: https on an [isImageHost] host, on the usual port. */
        fun isImageUrl(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) && uri.host?.let(::isImageHost) == true
        } catch (e: URISyntaxException) {
            false
        }

        /** Where [client] works: a client is locked to the environment it was registered for. */
        fun forClient(client: KrogerClient): KrogerEndpoints = if (client.certification) CERTIFICATION else KROGER
    }
}

/**
 * Every request to Kroger goes through here (plan 2026-11-17, "Network rules"): https only, to [hosts] only, checked
 * before anything is sent; no redirects followed; short timeouts; a reply over [MAX_REPLY_BYTES] refused.
 * HttpURLConnection, because Android has no java.net.http. Nothing here logs; a reply's toString shows only its status,
 * and a failure's message names a host at most. Tests pass hosts = 127.0.0.1 and httpsOnly = false, for FakeKroger.
 */
class KrogerHttp(
    private val hosts: Set<String> = KrogerEndpoints.KROGER_HOSTS,
    private val httpsOnly: Boolean = true,
    private val connectTimeoutMillis: Int = TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = TIMEOUT_MILLIS,
    private val imageHost: (String) -> Boolean = KrogerEndpoints::isImageHost,
) {
    class Reply(val status: Int, val body: String) {
        /** The body as a JSON object; anything else (an HTML error page, nothing) is an empty one. */
        fun json(): JsonObject = try {
            Json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }

        override fun toString(): String = "Reply($status)"
    }

    /** [url] as a URI, or an IOException unless it is https (or, in tests, http) on one of [hosts]. Sends nothing. */
    fun allowed(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IOException("That isn't an address Meal Planner can use.")
        }
        val scheme = uri.scheme
        val schemeOk = scheme == "https" || (!httpsOnly && scheme == "http")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (!schemeOk || host == null || host !in hosts) {
            throw IOException("Meal Planner only talks to Kroger's API; it won't contact $scheme://${uri.host}.")
        }
        if (httpsOnly && uri.port != -1 && uri.port != 443) throw IOException("Meal Planner only talks to Kroger on its usual port.")
        return uri
    }

    /**
     * A product's picture (KrogerProduct.imageUrl): its bytes, or null when Kroger has none there (not 200, not an
     * image, over [MAX_IMAGE_BYTES]). https to an [imageHost] only, no credentials, no redirects, never cached on disk.
     */
    fun image(url: String): ByteArray? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IOException("That isn't an address Meal Planner can use.")
        }
        val schemeOk = uri.scheme == "https" || (!httpsOnly && uri.scheme == "http")
        val host = uri.host
        if (!schemeOk || host == null || uri.rawUserInfo != null || !imageHost(host)) {
            throw IOException("Meal Planner only shows pictures from Kroger.")
        }
        if (httpsOnly && uri.port != -1 && uri.port != 443) throw IOException("Meal Planner only talks to Kroger on its usual port.")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) return null
            val type = connection.contentType?.lowercase(Locale.ROOT) ?: return null
            if (!type.startsWith("image/")) return null
            return try {
                connection.inputStream.use { readBytesAtMost(it, MAX_IMAGE_BYTES) }
            } catch (e: TooLarge) {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: String, bearer: String): Reply = send("GET", url, "Bearer $bearer", null, null)

    /** A form POST (the token endpoint), the client named by [basic] (base64 of id:secret). */
    fun form(url: String, basic: String, fields: Map<String, String>): Reply =
        send("POST", url, "Basic $basic", "application/x-www-form-urlencoded", query(fields))

    fun putJson(url: String, bearer: String, json: String): Reply = send("PUT", url, "Bearer $bearer", "application/json", json)

    private fun send(method: String, url: String, authorization: String, contentType: String?, body: String?): Reply {
        val connection = allowed(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readAtMost(it) }.orEmpty()
            return Reply(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private class TooLarge : IOException("Kroger's reply was too large.")

    private fun readAtMost(input: InputStream): String = String(readBytesAtMost(input, MAX_REPLY_BYTES), Charsets.UTF_8)

    // By hand: InputStream.readNBytes needs Android 13, and the phone runs from Android 8.
    private fun readBytesAtMost(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw TooLarge()
        }
        return out.toByteArray()
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MAX_REPLY_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "MealPlanner"

        /** application/x-www-form-urlencoded (and a URL's query): each name and value encoded as UTF-8, spaces as %20. */
        fun query(fields: Map<String, String>): String = fields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        // The String overload: the Charset one needs Android 13.
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** The Basic credentials for [client]: base64 of id:secret. */
        fun basic(client: KrogerClient): String =
            Base64.getEncoder().encodeToString("${client.id}:${client.secret}".toByteArray(Charsets.UTF_8))
    }
}
