package com.naeblis11.mealplanner.desktop.kroger

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.kroger.KrogerClient
import com.naeblis11.mealplanner.kroger.KrogerException
import com.naeblis11.mealplanner.kroger.KrogerMessages
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PC's Kroger parts on temp files, a fake protector and 127.0.0.1 ephemeral ports: never DPAPI, 5070 or the real .env. */
class DesktopKrogerTest {
    private val dir: File = Files.createTempDirectory("mp-kroger").toFile()
    private val pool = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun theInstalledAppAndThePreviewAnswerOnTheirOwnRegisteredPorts() {
        assertEquals(5070, DesktopKroger.signInPort(5000))
        assertEquals(5071, DesktopKroger.signInPort(5055))
        assertEquals(5071, DesktopKroger.signInPort(5056))
    }

    @Test
    fun aClientSetUpByHandLivesInTheSecretsFileBesideItsOtherLines() {
        val secrets = SecretsFile(File(dir, ".env")).apply { put("MEAL_PLANNER_API_TOKEN", "abcdefghijklmnop") }
        val store = SecretsKrogerClients(secrets)
        assertNull(store.load())
        store.save(KrogerClient("id-1", "secret-1", certification = true))
        assertEquals(KrogerClient("id-1", "secret-1", certification = true), store.load())
        store.save(KrogerClient("id-2", "secret-2"))
        assertEquals(KrogerClient("id-2", "secret-2"), store.load())
        store.delete()
        assertNull(store.load())
        assertEquals("abcdefghijklmnop", secrets.read()["MEAL_PLANNER_API_TOKEN"])
        assertThrows(IllegalArgumentException::class.java) { store.save(KrogerClient("id\n3", "x")) }
    }

    @Test
    fun theRefreshTokenIsSealedAndAFileFromElsewhereIsASentence() {
        val file = File(dir, DesktopKroger.TOKEN_FILE)
        val tokens = SealedKrogerTokens(file, FakeProtector())
        assertNull(tokens.load())
        tokens.save("refresh-1")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("refresh-1"))
        assertEquals("refresh-1", tokens.load())
        file.writeBytes("not sealed here".toByteArray())
        assertEquals(KrogerMessages.TOKEN_UNREADABLE, assertThrows(IOException::class.java) { tokens.load() }.message)
        tokens.delete()
        assertNull(tokens.load())
        assertFalse(tokens.toString().contains("refresh"))
    }

    @Test
    fun theSignInEndsOnlyWithItsOwnAnswer() {
        val port = freePort()
        var opened: URI? = null
        val authorizer = LoopbackKrogerAuthorizer(port, browse = { opened = it }, timeoutMillis = 10_000)
        assertEquals("http://localhost:$port/kroger", authorizer.redirectUri)
        val url = "https://api.kroger.com/v1/connect/oauth2/authorize?client_id=x&state=s-1"
        val answer = pool.submit<Map<String, String>> { authorizer.authorize(url) {} }
        waitUntil { opened != null }
        assertEquals(404, get("http://127.0.0.1:$port/kroger?code=c&state=other"))
        assertEquals(404, get("http://127.0.0.1:$port/favicon.ico"))
        assertEquals(200, get("http://127.0.0.1:$port/kroger?code=c-1&state=s-1"))
        assertEquals(mapOf("code" to "c-1", "state" to "s-1"), answer.get(5, TimeUnit.SECONDS))
        // The listener is gone once it answered.
        assertThrows(IOException::class.java) { get("http://127.0.0.1:$port/kroger?code=c&state=s-1") }
    }

    @Test
    fun aSignInCanBeCancelledOrTimeOut() {
        val port = freePort()
        var opened = false
        val authorizer = LoopbackKrogerAuthorizer(port, browse = { opened = true }, timeoutMillis = 10_000)
        val answer = pool.submit<Map<String, String>> { authorizer.authorize("https://api.kroger.com/x?state=s") {} }
        waitUntil { opened }
        authorizer.cancel()
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) { answer.get(5, TimeUnit.SECONDS) }
        assertEquals(KrogerMessages.SIGN_IN_CANCELLED, e.cause!!.message)

        val quick = LoopbackKrogerAuthorizer(freePort(), browse = {}, timeoutMillis = 50)
        assertEquals(KrogerMessages.SIGN_IN_TIMED_OUT, assertThrows(KrogerException::class.java) { quick.authorize("https://api.kroger.com/x?state=s") {} }.message)
    }

    @Test
    fun aPortAlreadyTakenIsASentenceNotAnotherPort() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            val authorizer = LoopbackKrogerAuthorizer(taken.localPort, browse = {})
            val e = assertThrows(KrogerException::class.java) { authorizer.authorize("https://api.kroger.com/x?state=s") {} }
            assertEquals(KrogerMessages.SIGN_IN_PORT_BUSY, e.message)
        }
        assertTrue(true)
    }

    private fun get(url: String): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2000
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(10)
        }
    }
}
