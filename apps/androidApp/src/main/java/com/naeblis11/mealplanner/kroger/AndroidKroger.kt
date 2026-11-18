package com.naeblis11.mealplanner.kroger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.naeblis11.mealplanner.MainActivity
import com.naeblis11.mealplanner.app.SettingsStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kroger on the phone (plan 2026-11-17): the client typed into Settings, else the one built into the app; the
 * customer's refresh token and any typed client sealed in [KeystoreKrogerStore]; the sign-in caught at
 * mealplanner://kroger ([PhoneKrogerAuthorizer], [KrogerCallbackActivity]). Made off the main thread (MainActivity),
 * since the account reads the sealed file as it is made.
 */
object AndroidKroger {
    fun create(context: Context, settings: SettingsStore): KrogerAccount {
        val app = context.applicationContext
        val store = KeystoreKrogerStore(File(app.filesDir, KeystoreKrogerStore.FILE_NAME), KrogerKey::get)
        return KrogerAccount(
            http = KrogerHttp(),
            endpointsFor = KrogerEndpoints::forClient,
            builtIn = KrogerClients.builtIn(),
            clients = store.clients,
            tokens = store.tokens,
            settings = settings,
            authorizer = PhoneKrogerAuthorizer(app),
        )
    }
}

/**
 * One AES-256-GCM file under a non-exportable Keystore key, as the sync pairing (KeystorePairingStore): a version
 * byte, the IV's length, the IV, the ciphertext with its tag. It holds a typed client and the refresh token as JSON,
 * read once and then kept in memory. A file that can't be opened (another phone, a key Android dropped) reads as empty:
 * no typed client, not signed in. Excluded from backups and device transfer (data_extraction_rules.xml). Never logged.
 */
class KeystoreKrogerStore(private val file: File, private val key: () -> SecretKey) {
    private var loaded = false
    private var client: KrogerClient? = null
    private var refresh: String? = null

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            val o = Json.parseToJsonElement(String(open(file.readBytes()), Charsets.UTF_8)) as JsonObject
            fun text(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = text("client_id")
            val secret = text("client_secret")
            if (id != null && secret != null) client = KrogerClient(id, secret, (o["certification"] as? JsonPrimitive)?.booleanOrNull == true)
            refresh = text("refresh")
        } catch (e: Exception) {
            System.err.println("Meal Planner: the Kroger sign-in couldn't be read (${e.javaClass.simpleName})")
        }
    }

    @Synchronized
    private fun write() {
        val c = client
        val r = refresh
        if (c == null && r == null) {
            file.delete()
            return
        }
        val json = buildJsonObject {
            if (c != null) { put("client_id", c.id); put("client_secret", c.secret); put("certification", c.certification) }
            if (r != null) put("refresh", r)
        }.toString()
        val sealed = try {
            seal(json.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException(KrogerMessages.TOKEN_NOT_SAVED)
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(sealed)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    val tokens: KrogerTokenStore = object : KrogerTokenStore {
        override fun load(): String? = synchronized(this@KeystoreKrogerStore) { ensure(); refresh }

        override fun save(refreshToken: String) = synchronized(this@KeystoreKrogerStore) { ensure(); refresh = refreshToken; write() }

        override fun delete() = synchronized(this@KeystoreKrogerStore) { ensure(); refresh = null; write() }
    }

    val clients: KrogerClientStore = object : KrogerClientStore {
        override fun load(): KrogerClient? = synchronized(this@KeystoreKrogerStore) { ensure(); client }

        override fun save(client: KrogerClient) = synchronized(this@KeystoreKrogerStore) { ensure(); this@KeystoreKrogerStore.client = client; write() }

        override fun delete() = synchronized(this@KeystoreKrogerStore) { ensure(); client = null; write() }
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == VERSION) { "Not a sealed file." }
        val ivSize = sealed[1].toInt()
        require(ivSize in 12..16 && sealed.size > 2 + ivSize) { "Not a sealed file." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 2, ivSize))
        return cipher.doFinal(sealed, 2 + ivSize, sealed.size - 2 - ivSize)
    }

    override fun toString(): String = "KeystoreKrogerStore(${file.name})"

    companion object {
        /** In filesDir; kept out of backups and device transfers (data_extraction_rules.xml). */
        const val FILE_NAME = "kroger.bin"
        private const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** The Kroger file's key in the Android Keystore: AES-256 for GCM, made once, never exportable, needing no screen lock. */
object KrogerKey {
    private const val ALIAS = "meal-planner-kroger"

    @Synchronized
    fun get(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

/**
 * The phone's half of the sign-in: Kroger's consent page in the browser (ACTION_VIEW, no new dependency), and the wait
 * for its answer at mealplanner://kroger, which [KrogerCallbackActivity] hands over through [deliver]. Five minutes;
 * one at a time; [cancel] ends it. Only an answer with the state this sign-in sent ends the wait (KrogerAccount checks
 * it again).
 */
class PhoneKrogerAuthorizer(private val context: Context, private val timeoutMillis: Long = 300_000L) : KrogerAuthorizer {
    override val redirectUri: String = REDIRECT

    override fun authorize(url: String, onUrl: (String) -> Unit): Map<String, String> {
        val answer = CompletableFuture<Map<String, String>>()
        synchronized(Companion) {
            if (pending != null) throw KrogerException(KrogerMessages.SIGN_IN_WAITING)
            pending = answer
            expectedState = Uri.parse(url).getQueryParameter("state")
        }
        try {
            onUrl(url)
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // No browser: Settings shows the address.
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
            synchronized(Companion) {
                pending = null
                expectedState = null
            }
        }
    }

    override fun cancel() {
        synchronized(Companion) { pending }?.cancel(false)
    }

    companion object {
        const val REDIRECT = "mealplanner://kroger"
        private var pending: CompletableFuture<Map<String, String>>? = null
        private var expectedState: String? = null

        /** The answer at [uri], taken only when a sign-in waits and it carries that sign-in's state. */
        fun deliver(uri: Uri): Boolean = synchronized(this) {
            val waiting = pending ?: return false
            if (uri.scheme != "mealplanner" || uri.host != "kroger") return false
            val state = uri.getQueryParameter("state")
            if (state == null || state != expectedState) return false
            waiting.complete(uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() })
        }
    }
}

/** Kroger's redirect back to the app: hands the answer to the waiting sign-in and returns to Meal Planner. */
class KrogerCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { PhoneKrogerAuthorizer.deliver(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
