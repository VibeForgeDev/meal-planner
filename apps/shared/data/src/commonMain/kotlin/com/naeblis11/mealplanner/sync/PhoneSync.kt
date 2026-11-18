package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.inTransaction
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sync was asked for with no pairing stored. */
class NotPairedException : SyncHttpException("This phone isn't paired with a PC. Join one in Settings.")

/** The phone's (or a client PC's) way to the PC's sync port: a seam over SyncHttp ([HttpSyncConnector]), so tests reach a SyncHub in-process. */
interface SyncConnector {
    /**
     * Pairs by the code shown on the PC: pinned to [pin] (the QR code), or unpinned when null (the typed code). [kind] is
     * PairingWire.KIND_PHONE or KIND_PC: what the PC lists this device as.
     */
    suspend fun pair(host: String, port: Int, pin: String?, code: String, deviceId: String, name: String, kind: String): Paired

    /** The paired PC at [host]:[port], pinned to [pin], with this phone's [deviceKey]. */
    fun transport(host: String, port: Int, pin: String, deviceKey: String): SyncTransport

    /** Tells the PC this phone is leaving. */
    suspend fun leave(host: String, port: Int, pin: String, deviceKey: String)

    /** Stage 4b: takes the main PC's role (a client PC only). */
    suspend fun takeHandover(host: String, port: Int, pin: String, deviceKey: String, request: HandoverRequest): HandoverBundle =
        throw UnsupportedOperationException("No hand-over here.")

    /** Stage 4b: tells the old main PC the role is taken. */
    suspend fun handoverDone(host: String, port: Int, pin: String, deviceKey: String): Unit =
        throw UnsupportedOperationException("No hand-over here.")
}

/** The real connector: 2b's pinned SyncHttp. */
object HttpSyncConnector : SyncConnector {
    override suspend fun pair(host: String, port: Int, pin: String?, code: String, deviceId: String, name: String, kind: String): Paired =
        SyncHttp(host, port, pin).pair(code, deviceId, name, kind)

    override fun transport(host: String, port: Int, pin: String, deviceKey: String): SyncTransport =
        SyncHttp(host, port, pin).transport(deviceKey)

    override suspend fun leave(host: String, port: Int, pin: String, deviceKey: String) =
        SyncHttp(host, port, pin).leave(deviceKey)

    override suspend fun takeHandover(host: String, port: Int, pin: String, deviceKey: String, request: HandoverRequest): HandoverBundle =
        SyncHttp(host, port, pin).takeHandover(deviceKey, request)

    override suspend fun handoverDone(host: String, port: Int, pin: String, deviceKey: String) =
        SyncHttp(host, port, pin).handoverDone(deviceKey)
}

/**
 * SyncClient's marks in app_meta (cursor, its hub, sent mark, last sync, full-next, the tables both sides understood). They are cleared when the phone
 * leaves, is removed, or pairs anew: otherwise a later Join would skip the Merge-or-Replace question. Nothing else in
 * app_meta (device_id, change_seq) is touched, and no data.
 */
object SyncMarks {
    val KEYS = listOf(
        SyncClient.CURSOR_KEY, SyncClient.CURSOR_HUB_KEY, SyncClient.SENT_MARK_KEY, SyncClient.LAST_SYNC_KEY, SyncClient.FULL_NEXT_KEY,
        SyncClient.TABLES_KEY, SyncClient.MY_TABLES_KEY,
    )

    suspend fun clear(db: AppDatabase) {
        val meta = db.appMetaDao()
        db.inTransaction { KEYS.forEach { meta.remove(it) } }
    }
}

/**
 * The phone's side of LAN sync (stage 2c): pairing, finding the PC, the sync, and Leave, over [connector]. One at a
 * time (a lock): each reads and writes the pairing and the marks.
 * - **Pairing** (D3): a scanned code tries each of its addresses pinned; a typed code pairs unpinned with a PC [finder]
 *   found. Either way the pairing is stored ([store]), the household id goes into app_meta, and the marks are cleared.
 * - **Sync** (D2, D6): only with a stored pairing, always pinned to its fingerprint. The PC is tried at the stored
 *   address, then at each PC mDNS finds in [FIND_MILLIS] (the stored name first). A host that answers with another
 *   certificate is passed over. A new address that works is stored first. Nowhere: SyncHttp.UNREACHABLE.
 * - **Removed on the PC:** UnpairedException forgets the pairing and the marks, keeps the data, and is thrown on.
 * - **Leave** (D3): tells the PC at the stored address (best effort), then forgets as above.
 *
 * There is exactly one PhoneSync per process, held app-scoped with its one [PhoneSyncControls]: the lock only
 * serializes work within one instance, so a second one would race the first over the pairing and the marks.
 *
 * **A client PC** (stage 4a) is the same client with [kind] PairingWire.KIND_PC: the PC lists it as a PC. [onJoined]
 * runs after a pairing is kept (the household id already taken), and [onLeft] after a Leave or a removal has forgotten
 * it; on a PC they change its role (and give a PC that left a household of its own). Both run under the lock, and a
 * failure in either is thrown on: the pairing (or its forgetting) has already been kept.
 */
class PhoneSync(
    private val db: AppDatabase,
    private val recipes: RecipeRepository,
    private val imagesDir: File,
    private val store: PairingStore,
    private val finder: PcFinder,
    private val deviceName: String,
    private val connector: SyncConnector = HttpSyncConnector,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kind: String = PairingWire.KIND_PHONE,
    private val onJoined: suspend (householdId: String) -> Unit = {},
    private val onLeft: suspend () -> Unit = {},
    /**
     * Stage 4b: runs after each sync that worked, inside the same lock, with the pairing it used; what it returns is the
     * sync's result (a client PC takes over as the main PC here when offered, and says so in the result's note).
     */
    private val afterSync: suspend (PhonePairing, SyncResult) -> SyncResult = { _, result -> result },
) {
    private val lock = Mutex()
    private val meta = db.appMetaDao()

    suspend fun pairing(): PhonePairing? = store.load()

    suspend fun hasSynced(): Boolean = meta.get(SyncClient.CURSOR_KEY) != null

    suspend fun lastSyncAt(): Long? = meta.get(SyncClient.LAST_SYNC_KEY)?.toLongOrNull()

    suspend fun findPcs(): List<FoundPc> = finder.find(FIND_MILLIS)

    /**
     * Pairs with the PC in a scanned code (PairingQr's text), trying each of its addresses pinned to its fingerprint. An
     * address where nothing answers, or another machine does (another certificate), is passed over: the PC may have
     * listed an adapter another machine now answers on. None the PC: WrongPcException if some other machine answered,
     * else SyncHttp.UNREACHABLE. IllegalArgumentException for text that isn't a pairing code.
     */
    suspend fun pairScanned(text: String): PhonePairing = lock.withLock {
        val invite = PairingQr.decode(text.trim())
        val deviceId = SyncSchema.deviceId(db)
        var otherMachine = false
        for (address in invite.addresses) {
            val paired = try {
                connector.pair(address, invite.port, invite.fingerprint, invite.code, deviceId, deviceName, kind)
            } catch (e: WrongPcException) {
                otherMachine = true
                continue
            } catch (e: SyncHttpException) {
                if (e.message == SyncHttp.UNREACHABLE) continue
                throw e
            }
            return@withLock keep(paired, listOf(address) + invite.addresses.filter { it != address }, invite.port)
        }
        throw if (otherMachine) WrongPcException() else SyncHttpException(SyncHttp.UNREACHABLE)
    }

    /** Pairs with [pc] by the typed [code], unpinned: SyncHttp checks the PC's proof before trusting what it saw. */
    suspend fun pairTyped(code: String, pc: FoundPc): PhonePairing = lock.withLock {
        val paired = connector.pair(pc.host, pc.port, null, code, SyncSchema.deviceId(db), deviceName, kind)
        keep(paired, listOf(pc.host), pc.port)
    }

    // A new PC (or the same one again) starts with the first-sync question: marks from an earlier pairing mean nothing to it.
    // The pairing is stored first: a store that fails (an AndroidKeyStore error, say) throws before the marks or the
    // household id change, so the phone is left as it was.
    private suspend fun keep(paired: Paired, addresses: List<String>, port: Int): PhonePairing {
        val reply = paired.reply
        val pairing = PhonePairing(addresses, port, paired.fingerprint, reply.pcName, reply.deviceKey, reply.hubId, reply.householdId)
        store.save(pairing)
        SyncMarks.clear(db)
        meta.put(AppMetaEntity(Household.KEY, reply.householdId))
        onJoined(reply.householdId)
        return pairing
    }

    /** One sync; the first with this PC needs [first] (SyncClient). */
    suspend fun sync(first: FirstSync? = null): SyncResult = lock.withLock {
        val pairing = store.load() ?: throw NotPairedException()
        val result = try {
            SyncClient(db, recipes, imagesDir, FindingTransport(pairing), clock).sync(first)
        } catch (e: UnpairedException) {
            forget()
            throw e
        }
        afterSync(store.load() ?: pairing, result)
    }

    /** Tells the PC (best effort), then forgets the pairing and the marks; the data stays. */
    suspend fun leave(): Unit = lock.withLock {
        store.load()?.let { pairing ->
            try {
                connector.leave(pairing.addresses.first(), pairing.port, pairing.fingerprint, pairing.deviceKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: a PC that can't be told keeps listing this phone until it is removed there.
            }
        }
        forget()
    }

    private suspend fun forget() {
        store.clear()
        SyncMarks.clear(db)
        onLeft()
    }

    // An address that isn't the PC any more: nothing answered, or another machine (another certificate) did.
    private fun movedOrGone(e: SyncHttpException): Boolean = e is WrongPcException || e.message == SyncHttp.UNREACHABLE

    /**
     * The PC where the pairing says, else wherever mDNS finds one with the pinned certificate. Found by the first
     * exchange; the photos then go to the same host. An exchange tried again on another host after a lost reply is
     * safe: no mark moved, and applying a change twice changes nothing (spec, section 3).
     */
    private inner class FindingTransport(private val pairing: PhonePairing) : SyncTransport {
        private var found: SyncTransport? = null

        override suspend fun exchange(request: ExchangeRequest): ExchangeReply {
            found?.let { return it.exchange(request) }
            val stored = pairing.addresses.first()
            val there = connector.transport(stored, pairing.port, pairing.fingerprint, pairing.deviceKey)
            try {
                return there.exchange(request).also { found = there }
            } catch (e: SyncHttpException) {
                if (!movedOrGone(e)) throw e
            }
            val candidates = finder.find(FIND_MILLIS)
                .filter { it.host != stored || it.port != pairing.port }
                .sortedBy { if (it.name == pairing.pcName) 0 else 1 }
            for (pc in candidates) {
                val elsewhere = connector.transport(pc.host, pc.port, pairing.fingerprint, pairing.deviceKey)
                val reply = try {
                    elsewhere.exchange(request)
                } catch (e: SyncHttpException) {
                    if (movedOrGone(e)) continue
                    throw e
                }
                found = elsewhere
                val addresses = (listOf(pc.host) + pairing.addresses.filter { it != pc.host }).take(PairingQr.MAX_ADDRESSES)
                // Best effort: the exchange is already applied, so a store that fails mustn't turn it into a failed sync.
                // The next sync just looks for the PC again.
                try {
                    // The PC's name too (stage 4b): the main PC may have been handed over to another PC, which has the
                    // same certificate under its own name.
                    val name = pc.name.takeIf(PairingWire::isName) ?: pairing.pcName
                    store.save(pairing.copy(addresses = addresses, port = pc.port, pcName = name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Kept unsaved; nothing is logged (the pairing holds the device key).
                }
                return reply
            }
            throw SyncHttpException(SyncHttp.UNREACHABLE)
        }

        override suspend fun photo(uuid: String): ByteArray? = checkNotNull(found) { "The PC was not reached." }.photo(uuid)

        override suspend fun sendPhoto(uuid: String, bytes: ByteArray) = checkNotNull(found) { "The PC was not reached." }.sendPhoto(uuid, bytes)
    }

    companion object {
        /** How long a look for the PC by mDNS lasts (spec: "mDNS for up to 5 seconds"). */
        const val FIND_MILLIS = 5_000L
    }
}
