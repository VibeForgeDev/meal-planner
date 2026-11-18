package com.naeblis11.mealplanner.desktop.kroger

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.desktop.google.GoogleClients
import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.kroger.KrogerAccount
import com.naeblis11.mealplanner.kroger.KrogerAuthorizer
import com.naeblis11.mealplanner.kroger.KrogerClient
import com.naeblis11.mealplanner.kroger.KrogerClientStore
import com.naeblis11.mealplanner.kroger.KrogerClients
import com.naeblis11.mealplanner.kroger.KrogerEndpoints
import com.naeblis11.mealplanner.kroger.KrogerException
import com.naeblis11.mealplanner.kroger.KrogerHttp
import com.naeblis11.mealplanner.kroger.KrogerMessages
import com.naeblis11.mealplanner.kroger.KrogerTokenStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Kroger on the PC (plan 2026-11-17): the client from the secrets file ([SecretsKrogerClients]), else the one built
 * into the app; the customer's refresh token sealed with DPAPI beside the secrets file ([SealedKrogerTokens]); and the
 * sign-in answered on this PC ([LoopbackKrogerAuthorizer]) at the registered address, port 5070 (5071 for a preview).
 */
object DesktopKroger {
    const val TOKEN_FILE = "kroger-token.dat"

    /** The registered answer ports: the installed app's (whose server is on 5000), and any preview's. */
    const val INSTALLED_PORT = 5070
    const val PREVIEW_PORT = 5071

    fun signInPort(serverPort: Int): Int = if (serverPort == 5000) INSTALLED_PORT else PREVIEW_PORT

    fun create(secrets: SecretsFile, protector: SecretProtector, settings: SettingsStore, serverPort: Int, browse: (URI) -> Unit): KrogerAccount =
        KrogerAccount(
            http = KrogerHttp(),
            endpointsFor = KrogerEndpoints::forClient,
            builtIn = KrogerClients.builtIn(),
            clients = SecretsKrogerClients(secrets),
            tokens = SealedKrogerTokens(File(secrets.file.absoluteFile.parentFile, TOKEN_FILE), protector),
            settings = settings,
            authorizer = LoopbackKrogerAuthorizer(signInPort(serverPort), browse),
        )
}

/**
 * A Kroger client set up by hand, in the secrets file: [ID_KEY], [SECRET_KEY] and [ENVIRONMENT_KEY] ("certification"
 * for a test client). Edited by hand or by Settings; forgetting it empties the values, as both halves are needed.
 */
class SecretsKrogerClients(private val secrets: SecretsFile) : KrogerClientStore {
    override fun load(): KrogerClient? {
        val values = secrets.read()
        val id = values[ID_KEY]?.let(GoogleClients::unquote).orEmpty()
        val secret = values[SECRET_KEY]?.let(GoogleClients::unquote).orEmpty()
        if (!KrogerAccount.printable(id) || !KrogerAccount.printable(secret)) return null
        return KrogerClient(id, secret, values[ENVIRONMENT_KEY]?.let(GoogleClients::unquote) == "certification")
    }

    override fun save(client: KrogerClient) {
        require(KrogerAccount.printable(client.id) && KrogerAccount.printable(client.secret)) { KrogerMessages.BAD_CLIENT }
        secrets.put(ID_KEY, client.id)
        secrets.put(SECRET_KEY, client.secret)
        secrets.put(ENVIRONMENT_KEY, if (client.certification) "certification" else "production")
    }

    override fun delete() {
        secrets.put(ID_KEY, "")
        secrets.put(SECRET_KEY, "")
        secrets.put(ENVIRONMENT_KEY, "")
    }

    companion object {
        const val ID_KEY = "MEAL_PLANNER_KROGER_CLIENT_ID"
        const val SECRET_KEY = "MEAL_PLANNER_KROGER_CLIENT_SECRET"
        const val ENVIRONMENT_KEY = "MEAL_PLANNER_KROGER_ENVIRONMENT"
    }
}

/**
 * The customer's refresh token, sealed by [protector] (DPAPI) in [file], written to a temporary file and moved over the
 * old one. No message or toString ever holds the token; a failure says only its kind.
 */
class SealedKrogerTokens(val file: File, private val protector: SecretProtector) : KrogerTokenStore {
    override fun load(): String? {
        if (!file.isFile) return null
        val plain = try {
            protector.unprotect(file.readBytes())
        } catch (e: Exception) {
            throw IOException(KrogerMessages.TOKEN_UNREADABLE)
        }
        return String(plain, Charsets.UTF_8).ifEmpty { null }
    }

    override fun save(refreshToken: String) {
        val sealed = try {
            protector.protect(refreshToken.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException(KrogerMessages.TOKEN_NOT_SAVED)
        }
        val folder = file.absoluteFile.parentFile
        folder.mkdirs()
        val temp = Files.createTempFile(folder.toPath(), "kroger-token", ".tmp").toFile()
        try {
            temp.writeBytes(sealed)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    override fun delete() {
        file.delete()
    }

    override fun toString(): String = "SealedKrogerTokens(${file.name}, saved=${file.isFile})"
}

/**
 * The sign-in's answer on this PC: a one-shot listener on the loopback addresses at [port] (the registered redirect
 * `http://localhost:<port>/kroger`), the browser opened on Kroger's consent page, five minutes to answer. Only an answer
 * at /kroger carrying this sign-in's state ends the wait; anything else gets a 404. One sign-in at a time; [cancel]
 * ends the wait from any thread. The code is never logged.
 */
class LoopbackKrogerAuthorizer(
    private val port: Int,
    private val browse: (URI) -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    /** Opens a listener; tests pass their own. */
    private val bind: (InetSocketAddress) -> HttpServer = { HttpServer.create(it, 0) },
) : KrogerAuthorizer {
    override val redirectUri: String = "http://localhost:$port/kroger"

    private val lock = Any()
    private var pending: CompletableFuture<Map<String, String>>? = null

    override fun authorize(url: String, onUrl: (String) -> Unit): Map<String, String> {
        val answer = CompletableFuture<Map<String, String>>()
        synchronized(lock) {
            if (pending != null) throw KrogerException(KrogerMessages.SIGN_IN_WAITING)
            pending = answer
        }
        val state = URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("state=") }?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }
        val servers = mutableListOf<HttpServer>()
        try {
            // "localhost" may reach either loopback address; IPv4 must open, IPv6 is a bonus.
            servers += try {
                listen(InetAddress.getByName("127.0.0.1"), answer, state)
            } catch (e: IOException) {
                throw KrogerException(KrogerMessages.SIGN_IN_PORT_BUSY)
            }
            try {
                servers += listen(InetAddress.getByName("::1"), answer, state)
            } catch (e: IOException) {
                // No IPv6 loopback here: the browser falls back to 127.0.0.1.
            }
            if (answer.isCancelled) throw KrogerException(KrogerMessages.SIGN_IN_CANCELLED)
            onUrl(url)
            try {
                browse(URI(url))
            } catch (e: Exception) {
                // No browser opened: Settings shows the address to open by hand.
            }
            return try {
                answer.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw KrogerException(KrogerMessages.SIGN_IN_TIMED_OUT)
            } catch (e: CancellationException) {
                throw KrogerException(KrogerMessages.SIGN_IN_CANCELLED)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KrogerException(KrogerMessages.SIGN_IN_CANCELLED)
            } catch (e: ExecutionException) {
                throw KrogerException(KrogerMessages.SIGN_IN_FAILED)
            }
        } finally {
            servers.forEach { it.stop(0) }
            synchronized(lock) { pending = null }
        }
    }

    override fun cancel() {
        synchronized(lock) { pending }?.cancel(false)
    }

    private fun listen(address: InetAddress, answer: CompletableFuture<Map<String, String>>, state: String?): HttpServer =
        bind(InetSocketAddress(address, port)).apply {
            createContext("/") { exchange -> reply(exchange, answer, state) }
            start()
        }

    private fun reply(exchange: HttpExchange, answer: CompletableFuture<Map<String, String>>, state: String?) {
        try {
            val params = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val ours = exchange.requestMethod == "GET" && exchange.requestURI.rawPath == "/kroger" && state != null && params["state"] == state
            val page = if (ours) PAGE else NOT_FOUND
            try {
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(if (ours) 200 else 404, page.size.toLong())
                exchange.responseBody.write(page)
            } finally {
                exchange.close()
                // Only once the browser has its page: the answer ends the wait, which stops this listener.
                if (ours) answer.complete(params)
            }
        } catch (e: IOException) {
            // A browser that went away mid-reply; the wait goes on for a real answer.
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 300_000L

        private val PAGE = (
            "<!doctype html><meta charset=\"utf-8\"><title>Meal Planner</title>" +
                "<body style=\"font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 30rem; text-align: center\">" +
                "<h1 style=\"color:#1c7a4d\">Done</h1><p>You can close this tab and go back to Meal Planner.</p>"
            ).toByteArray(Charsets.UTF_8)
        private val NOT_FOUND = "Not found".toByteArray(Charsets.UTF_8)
    }
}
