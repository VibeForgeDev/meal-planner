package com.naeblis11.mealplanner.kroger

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where Kroger stands on this device. [clientReady]: a Kroger client is known (built in or set up by hand).
 * [signedIn]: a customer's sign-in is kept. [store]: the chosen store, remembered (owner, 2026-11-17).
 * [customClientId]: the client set up by hand, shown in Settings; null while the built-in one is used.
 */
data class KrogerStatus(
    val clientReady: Boolean = false,
    val signedIn: Boolean = false,
    val store: KrogerStore? = null,
    val modality: KrogerModality = KrogerModality.PICKUP,
    val customClientId: String? = null,
    /** The client in use is a test (certification) one: prices work, the sign-in and the cart don't. */
    val certification: Boolean = false,
) {
    /** Everything Send to Kroger needs. */
    val ready: Boolean get() = clientReady && signedIn && store != null
}

/**
 * Kroger for the screens (plan 2026-11-17), behind an interface so shared screens can be tested with a fake.
 * Every call but [cancelSignIn] blocks (the network or the disk): call them off the UI thread. Failures are
 * IOException (KrogerException) with a sentence for the user; IllegalArgumentException for input Kroger can't take.
 */
interface KrogerControls {
    val status: StateFlow<KrogerStatus>

    /** Opens the browser on Kroger's consent page and waits for the answer; [onUrl] gets the page's address first. */
    fun signIn(onUrl: (String) -> Unit)

    fun cancelSignIn()

    /** Forgets the sign-in on this device. */
    fun signOut()

    fun stores(zip: String): List<KrogerStore>

    fun chooseStore(store: KrogerStore)

    fun setModality(modality: KrogerModality)

    /** Kroger's search at the chosen store. */
    fun search(term: String, start: Int = 0): List<KrogerProduct>

    /** The products [ids] at the chosen store now. */
    fun products(ids: Collection<String>): List<KrogerProduct>

    /** A product's picture ([KrogerProduct.imageUrl]), or null when it can't be had; never kept on disk. */
    fun image(url: String): ByteArray? = null

    /** Adds [items] to the signed-in customer's cart, the chosen way (pickup or delivery). */
    fun addToCart(items: List<KrogerCartItem>)

    /**
     * Sets up a client by hand ([id] and [secret] from developer.kroger.com, [certification] when it was registered for
     * testing); signs out, as a sign-in belongs to its client.
     */
    fun useClient(id: String, secret: String, certification: Boolean = false)

    /** Goes back to the client built into the app; signs out too. */
    fun forgetClient()
}

/** Keeps the customer's refresh token, sealed (DPAPI on the PC, the Keystore on the phone). */
interface KrogerTokenStore {
    /** The refresh token, or null when none is kept. IOException when it can't be read here. */
    fun load(): String?

    fun save(refreshToken: String)

    fun delete()
}

/** Keeps a client set up by hand (the PC's .env, the phone's sealed file); null when there is none. */
interface KrogerClientStore {
    fun load(): KrogerClient?

    fun save(client: KrogerClient)

    fun delete()
}

/**
 * The platform's half of the sign-in: shows [url] (Kroger's consent page) in the browser and waits for the redirect to
 * [redirectUri], returning its query parameters. Blocking; [cancel] ends a wait from any thread with
 * KrogerException(SIGN_IN_CANCELLED). The PC answers on 127.0.0.1 (desktop/kroger); the phone catches its own address.
 */
interface KrogerAuthorizer {
    val redirectUri: String

    fun authorize(url: String, onUrl: (String) -> Unit): Map<String, String>

    fun cancel()
}

/** Proof Key for Code Exchange (S256) and the sign-in's state, both random. */
class KrogerPkce(random: SecureRandom = SecureRandom()) {
    val verifier: String = urlSafe(ByteArray(32).also(random::nextBytes))
    val challenge: String = urlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val state: String = urlSafe(ByteArray(16).also(random::nextBytes))

    override fun toString(): String = "KrogerPkce"

    private companion object {
        fun urlSafe(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Kroger on this device (plan 2026-11-17): the client ([clients], else [builtIn]), the customer's sign-in ([tokens]),
 * the store and pickup or delivery ([settings], this device's only), and the API. The access token lives in memory only.
 * A refresh Kroger refuses forgets the sign-in. Nothing here logs.
 */
class KrogerAccount(
    private val http: KrogerHttp,
    private val endpointsFor: (KrogerClient) -> KrogerEndpoints,
    private val builtIn: KrogerClient?,
    private val clients: KrogerClientStore,
    private val tokens: KrogerTokenStore,
    private val settings: SettingsStore,
    private val authorizer: KrogerAuthorizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : KrogerControls {
    private val api = KrogerApi(http, endpointsFor, ::client, clock)
    private var access: String? = null
    private var accessUntil = 0L
    private val _status = MutableStateFlow(read())
    override val status: StateFlow<KrogerStatus> = _status.asStateFlow()

    private fun custom(): KrogerClient? = try {
        clients.load()
    } catch (e: IOException) {
        null
    }

    private fun client(): KrogerClient? = custom() ?: builtIn

    private fun read(): KrogerStatus {
        val custom = custom()
        val inUse = custom ?: builtIn
        val signedIn = try {
            tokens.load() != null
        } catch (e: IOException) {
            false
        }
        val id = settings.getString(STORE_ID_KEY)
        val store = id?.takeIf { it.isNotEmpty() }?.let { KrogerStore(it, settings.getString(STORE_NAME_KEY).orEmpty(), settings.getString(STORE_ADDRESS_KEY).orEmpty()) }
        return KrogerStatus(
            clientReady = inUse != null,
            signedIn = signedIn,
            store = store,
            modality = KrogerModality.fromWire(settings.getString(MODALITY_KEY)),
            customClientId = custom?.id,
            certification = inUse?.certification == true,
        )
    }

    private fun refreshStatus() {
        _status.value = read()
    }

    override fun signIn(onUrl: (String) -> Unit) {
        if (client() == null) throw KrogerException(KrogerMessages.NO_CLIENT)
        val pkce = KrogerPkce(random)
        val answer = authorizer.authorize(api.authorizeUrl(authorizer.redirectUri, pkce.challenge, pkce.state), onUrl)
        if (answer["state"] != pkce.state) throw KrogerException(KrogerMessages.SIGN_IN_FAILED)
        if (answer["error"] != null) throw KrogerException(KrogerMessages.SIGN_IN_FAILED)
        val code = answer["code"]?.takeIf { it.isNotEmpty() } ?: throw KrogerException(KrogerMessages.SIGN_IN_FAILED)
        val fresh = api.exchange(code, authorizer.redirectUri, pkce.verifier)
        keep(fresh)
        refreshStatus()
    }

    override fun cancelSignIn() = authorizer.cancel()

    @Synchronized
    override fun signOut() {
        access = null
        tokens.delete()
        refreshStatus()
    }

    override fun stores(zip: String): List<KrogerStore> = offline { api.stores(zip.trim()) }

    override fun chooseStore(store: KrogerStore) {
        settings.put(mapOf(STORE_ID_KEY to store.locationId, STORE_NAME_KEY to store.name, STORE_ADDRESS_KEY to store.address))
        refreshStatus()
    }

    override fun setModality(modality: KrogerModality) {
        settings.put(mapOf(MODALITY_KEY to modality.wire))
        refreshStatus()
    }

    override fun search(term: String, start: Int): List<KrogerProduct> = offline { api.search(term, storeId(), start) }

    override fun products(ids: Collection<String>): List<KrogerProduct> =
        if (ids.isEmpty()) emptyList() else offline { api.products(ids, storeId()) }

    override fun image(url: String): ByteArray? = try {
        http.image(url)
    } catch (e: IOException) {
        null
    }

    override fun addToCart(items: List<KrogerCartItem>) {
        if (items.isEmpty()) return
        val modality = status.value.modality
        offline {
            if (!api.addToCart(accessToken(), items, modality)) {
                // Refused: one fresh token, one more try.
                synchronized(this) { access = null }
                if (!api.addToCart(accessToken(), items, modality)) {
                    forgetSignIn()
                    throw KrogerSignInException()
                }
            }
        }
    }

    override fun useClient(id: String, secret: String, certification: Boolean) {
        val client = KrogerClient(id.trim(), secret.trim(), certification)
        require(printable(client.id) && printable(client.secret)) { KrogerMessages.BAD_CLIENT }
        clients.save(client)
        forgetSignIn()
    }

    override fun forgetClient() {
        clients.delete()
        forgetSignIn()
    }

    @Synchronized
    private fun forgetSignIn() {
        access = null
        tokens.delete()
        refreshStatus()
    }

    private fun storeId(): String = status.value.store?.locationId ?: throw KrogerException(KrogerMessages.NO_STORE)

    // A live access token for the customer: the one in memory until a minute before it ends, else a refresh.
    @Synchronized
    private fun accessToken(): String {
        access?.let { if (clock() < accessUntil) return it }
        val refresh = try {
            tokens.load()
        } catch (e: IOException) {
            throw KrogerException(KrogerMessages.TOKEN_UNREADABLE)
        } ?: throw KrogerException(KrogerMessages.NOT_SIGNED_IN)
        val fresh = try {
            api.refresh(refresh)
        } catch (e: KrogerSignInException) {
            forgetSignIn()
            throw e
        }
        keep(fresh)
        return fresh.access
    }

    @Synchronized
    private fun keep(fresh: KrogerTokens) {
        fresh.refresh?.let {
            try {
                tokens.save(it)
            } catch (e: IOException) {
                throw KrogerException(KrogerMessages.TOKEN_NOT_SAVED)
            }
        }
        access = fresh.access
        accessUntil = clock() + fresh.expiresInSeconds * 1000 - 60_000
    }

    // A network failure that isn't Kroger's answer (no connection, a timeout) in a sentence; Kroger's own are kept.
    private fun <T> offline(call: () -> T): T = try {
        call()
    } catch (e: KrogerException) {
        throw e
    } catch (e: IOException) {
        throw KrogerException(KrogerMessages.OFFLINE)
    }

    companion object {
        const val STORE_ID_KEY = "kroger_store_id"
        const val STORE_NAME_KEY = "kroger_store_name"
        const val STORE_ADDRESS_KEY = "kroger_store_address"
        const val MODALITY_KEY = "kroger_modality"

        /** Kroger's ids and secrets are printable ASCII without spaces; anything else is refused, never trimmed inside. */
        fun printable(value: String): Boolean = value.isNotEmpty() && value.length <= 200 && value.all { it in '!'..'~' }
    }
}

/**
 * The Kroger client built into the app: the kroger-client.json resource the build writes (apps/kroger-client.properties),
 * {"client_id", "client_secret", "environment"}; environment "certification" for a test client, else production.
 */
object KrogerClients {
    const val RESOURCE = "/kroger-client.json"

    /** The built-in client, or null when the build had none (or it can't be read; [log] says so by kind only). */
    fun builtIn(read: () -> String? = ::readResource, log: (String) -> Unit = { System.err.println(it) }): KrogerClient? {
        val text = try {
            read()
        } catch (e: IOException) {
            log("Meal Planner: the Kroger client built into this app can't be read (${e.javaClass.simpleName}).")
            return null
        } ?: return null
        val o = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        }
        fun field(name: String) = (o?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val client = KrogerClient(field("client_id"), field("client_secret"), field("environment") == "certification")
        if (!KrogerAccount.printable(client.id) || !KrogerAccount.printable(client.secret)) {
            log("Meal Planner: the Kroger client built into this app isn't a client id and secret, so it isn't used.")
            return null
        }
        return client
    }

    private fun readResource(): String? =
        KrogerClients::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readBytes(), Charsets.UTF_8) }
}
