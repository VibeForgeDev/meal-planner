package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaIds
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncChangeEntity
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.inTransaction
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A recipe whose removal by a device's sync is held for the user: its [uuid] and its [name] as listed. */
data class HeldRemoval(val uuid: String, val name: String)

/**
 * The master PC's side of sync (LAN sync spec, section 3): one exchange at a time, a client's clock corrected to the
 * hub's, the client's changes applied with the hub's rules, and everything changed since the client's cursor sent
 * back, except the client's own. A cursor the hub never gave answers with everything, and everything includes the
 * client's own records. A cursor is the hub's only with the hub's id ([HUB_ID_KEY]): a rebuilt or replaced hub has a
 * new id, so a cursor from before is never mistaken for one of its own, whatever its number.
 * 2b's routes call this after checking the device key; [requester] is the paired device's id.
 */
class SyncHub(
    private val db: AppDatabase,
    private val recipes: RecipeRepository,
    private val imagesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val applier = SyncApplier(db, recipes, asHub = true, clock)
    // Through the property each time: the PC's folder sync sets RecipeRepository.isHeld after this is made.
    private val records = SyncRecords(db) { recipes.isHeld(it) }

    suspend fun exchange(requester: String, request: ExchangeRequest): ExchangeReply = lock.withLock {
        SyncPhotos.backfillHashes(db, imagesDir)
        val hubNow = clock()
        val skew = hubNow - request.deviceTime
        // Never in the future (a clock that was wrong when it stamped, or a crafted deviceTime) and never <= 0 (0 means unchanged).
        val incoming = request.records.map { if (it.updatedAt == 0L) it else it.copy(updatedAt = (it.updatedAt + skew).coerceIn(1L, maxOf(hubNow, 1L))) }
        val hubId = hubId()
        // A first request names no hub and has cursor 0; any other cursor counts only with this hub's id.
        val mine = request.hubId == hubId || (request.hubId == null && request.cursor == 0L)
        val known = mine && request.cursor in 0..SyncSchema.changeSeq(db)
        // A normal request carries only the requester's own changes (the echo rule); a record stamped by another device
        // is refused. A full request is everything the device has, so it may relay other devices' stamps.
        val (own, spoofed) = if (request.full) incoming to emptyList() else incoming.partition { it.updatedBy == requester }
        val result = applier.apply(own, requester, firstSync = request.full)
        val full = request.full || !known
        // The cursor first: a change made while the records are read is sent next time, never lost.
        val cursor = SyncSchema.changeSeq(db)
        val out = if (full) records.everything(SyncSchema.deviceId(db)) else records.changedSince(request.cursor)
        // Every photo still wanted, not only this exchange's: a send that failed after the records applied is asked again.
        val wantPhotos = SyncPhotos.wanted(db).map { (uuid, hash) -> PhotoWant(uuid, hash) }
        // The echo rule only for changes: a full reply is everything, the requester's own records too (a device that
        // starts over with Replace, or a hub restored from a backup, needs them back).
        val reply = (if (full) out else out.filter { it.updatedBy != requester }).filter { it.table in request.tables }
        ExchangeReply(clock(), cursor, full, reply, spoofed.map(SyncApplier::label) + result.skipped, wantPhotos, hubId)
    }

    /**
     * This hub's sync identity, made once; every exchange reply and the pair reply (2b) carry it. Not the device id: a
     * hand-over (stage 4) can carry it to the new master.
     */
    suspend fun hubId(): String = AppMetaIds.keep(db, HUB_ID_KEY, "The hub id", { ByteArray(16).also(SecureRandom()::nextBytes) })

    suspend fun photo(uuid: String): ByteArray? = SyncPhotos.bytes(db, imagesDir, uuid)

    /** A device's photo, kept only while the hub wants one for [uuid] and only when the bytes have the wanted image_hash. */
    suspend fun receivePhoto(uuid: String, bytes: ByteArray): Boolean = lock.withLock { SyncPhotos.receiveWanted(db, imagesDir, uuid, bytes) }

    /** The recipes a device's sync would remove, held for the user (Needs attention, 2b). */
    suspend fun heldRemovals(): List<HeldRemoval> = db.syncDao().pending()
        .filter { it.reason == SyncApplier.MASS_REMOVAL && it.tableName == SyncTables.RECIPE }
        .mapNotNull { row -> db.recipeDao().idForUuid(row.syncKey)?.let { id -> db.recipeDao().recipe(id)?.name }?.let { HeldRemoval(row.syncKey, it) } }

    /** Removes the held recipes [uuids] (what the user was shown and confirmed) with their held meals; others stay held. */
    suspend fun confirmHeldRemovals(uuids: Set<String>): Int = lock.withLock { applier.applyHeldRemovals(uuids) }

    /**
     * Keeps the held recipes [uuids]: each, and each meal still planned with it, is stamped as the hub's newest version,
     * so the device that removed them (its cascade removed the meals) gets them back. The meal tombstones held with them
     * go. Others stay held.
     */
    suspend fun keepHeldRemovals(uuids: Set<String>) = lock.withLock {
        val sync = db.syncDao()
        val me = SyncSchema.deviceId(db)
        db.inTransaction {
            val held = applier.heldFor(uuids)
            val kept = held.filter { it.tableName == SyncTables.RECIPE && it.syncKey in uuids }.map { it.syncKey }.toSet()
            held.forEach { sync.dropPending(it.tableName, it.syncKey) }
            val meals = sync.meals().filter { it.recipeUuid in kept }
            for (uuid in kept) restampAsMine(SyncTables.RECIPE, uuid, me)
            for (m in meals) restampAsMine(SyncTables.MEAL, SyncSchema.mealKey(m.date, m.slot), me)
        }
    }

    private suspend fun restampAsMine(table: String, key: String, me: String) {
        val sync = db.syncDao()
        sync.bumpChangeSeq()
        sync.putChange(SyncChangeEntity(table, key, clock(), me, SyncSchema.changeSeq(db), false))
    }

    companion object {
        /** The hub's own sync identity in app_meta, sent in every reply. */
        const val HUB_ID_KEY = "sync_hub_id"
    }
}
