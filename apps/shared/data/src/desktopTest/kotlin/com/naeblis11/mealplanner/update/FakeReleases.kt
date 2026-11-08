package com.naeblis11.mealplanner.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * GitHub Releases on 127.0.0.1, on a port the system picks; never GitHub (spec "Updates", tests). As GitHub does,
 * `<REPO>/releases/latest/download/<name>` redirects to `<REPO>/releases/download/<tag>/<name>`, which redirects to the
 * asset host, here `http://<assetHost>:<port>/assets/<tag>/<name>`. Every request's path is kept in [requests], so a
 * test can show that nothing was asked.
 */
class FakeReleases : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val files = ConcurrentHashMap<String, ByteArray>()

    val requests = CopyOnWriteArrayList<String>()

    /** The release `latest` points at. */
    @Volatile
    var latestTag = "release-2"

    /** The host the second redirect names: 127.0.0.1 is on the tests' list, "localhost" isn't. */
    @Volatile
    var assetHost = "127.0.0.1"

    /** Each reply waits this long first, for the timeout cases. */
    @Volatile
    var delayMillis = 0L

    /** Files are sent without a Content-Length (chunked), as a reply whose length isn't known up front. */
    @Volatile
    var chunked = false

    /** A file is sent a byte at a time with this wait between, for the slow-drip case. */
    @Volatile
    var dripMillis = 0L

    /** Called with each request's path as it arrives, before the reply: a test can change the release in between. */
    @Volatile
    var onRequest: (String) -> Unit = {}

    val port: Int get() = server.address.port

    val endpoints: ReleaseEndpoints get() = ReleaseEndpoints("http://127.0.0.1:$port$REPO/releases")

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    /** The update client as the tests use it: 127.0.0.1 only, plain http, short timeouts. */
    fun http(readTimeoutMillis: Int = 2_000, overallTimeoutMillis: Long = 60_000, downloadTimeoutMillis: Long = 60_000): ReleaseHttp =
        ReleaseHttp(
            hosts = setOf("127.0.0.1"),
            httpsOnly = false,
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = readTimeoutMillis,
            overallTimeoutMillis = overallTimeoutMillis,
            downloadTimeoutMillis = downloadTimeoutMillis,
        )

    fun publish(tag: String, name: String, body: ByteArray) {
        files["$tag/$name"] = body
    }

    fun unpublish(tag: String, name: String) {
        files.remove("$tag/$name")
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requests += path
        onRequest(path)
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val latest = "$REPO/releases/latest/download/"
        val download = "$REPO/releases/download/"
        when {
            path.startsWith(latest) -> redirect(exchange, "$download$latestTag/${path.removePrefix(latest)}")
            path == "/nolocation" -> exchange.sendResponseHeaders(302, -1)
            path == "/relative" -> redirect(exchange, "/assets/release-2/rel")
            path == "/loop" -> redirect(exchange, "/loop")
            path.startsWith(download) -> {
                val key = path.removePrefix(download)
                if (files.containsKey(key)) redirect(exchange, "http://$assetHost:$port/assets/$key") else exchange.sendResponseHeaders(404, -1)
            }
            path.startsWith("/assets/") -> {
                val body = files[path.removePrefix("/assets/")]
                if (body == null) exchange.sendResponseHeaders(404, -1) else send(exchange, body)
            }
            else -> exchange.sendResponseHeaders(404, -1)
        }
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
    }

    private fun send(exchange: HttpExchange, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(200, if (chunked) 0 else body.size.toLong())
        exchange.responseBody.use { out ->
            if (dripMillis > 0) {
                for (b in body) {
                    out.write(b.toInt())
                    out.flush()
                    Thread.sleep(dripMillis)
                }
            } else {
                out.write(body)
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val REPO = "/VibeForgeDev/meal-planner"
    }
}
