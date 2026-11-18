package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.RecipeEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.inTransaction
import java.io.File
import java.util.Locale

/** How a client reaches the hub. 2c's is pinned HTTPS to the paired PC; tests call a SyncHub directly. */
interface SyncTransport {
    suspend fun exchange(request: ExchangeRequest): ExchangeReply

    /** The photo of the recipe [uuid] on the hub, or null when it has none. */
    suspend fun photo(uuid: String): ByteArray?

    suspend fun sendPhoto(uuid: String, bytes: ByteArray)
}

/** The first sync's choice (LAN sync spec, section 3): merge the two libraries, or make this device the PC's copy. */
enum class FirstSync { MERGE, REPLACE }

/** After a Merge: a recipe here and one from the PC with the same name but different uuids, for the user to settle. */
data class PossibleDuplicate(val name: String, val thisDevicesId: Long, val pcsId: Long)

data class SyncResult(
    val sent: Int,
    val received: Int,
    val skipped: List<String>,
    val photos: Int,
    val duplicates: List<PossibleDuplicate>,
    /** Stage 4b: the main PC offered this device its role in this exchange. */
    val handover: Boolean = false,
    /** What the Sync button says instead of the usual counts (stage 4b: "This PC is now the household's main PC..."). */
    val note: String? = null,
)

/** "Replace this device's data with the PC's": the synced tables emptied with their change records, in one go. */
object SyncReset {
    /**
     * Empties every synced table, sync_change and sync_pending in one transaction. The deletes' tombstones go with
     * sync_change, so nothing removed here reaches the PC. app_meta (device_id, change_seq) is kept.
     */
    suspend fun clearLocal(db: AppDatabase) {
        val sync = db.syncDao()
        db.inTransaction {
            sync.clearMeals()
            sync.clearIngredients()
            sync.clearSteps()
            sync.clearRecipes()
            sync.clearPantry()
            sync.clearAisles()
            sync.clearShopping()
            sync.clearKrogerMatches()
            sync.clearChanges()
            sync.clearPending()
        }
    }
}

/**
 * A phone's (or client PC's) Sync (LAN sync spec, section 3): one exchange with the hub, then the photos either side
 * lacks. It sends only its own changes since the last sync (the echo rule), and its sent mark is the counter read
 * before the request was built, so an edit made during the exchange goes next time. Nothing moves the marks until the
 * exchange has been applied, so a dropped sync is simply run again. The marks are kept as soon as the reply is applied,
 * before any photo moves: a photo wish is remembered on both sides, so a failed photo is tried again on its own and
 * never makes the exchange be sent again.
 */
class SyncClient(
    private val db: AppDatabase,
    private val recipes: RecipeRepository,
    private val imagesDir: File,
    private val transport: SyncTransport,
    /**
     * Stamps the request and the shift of the reply. Outside tests it must be the system clock: this device's own
     * changes are stamped by the triggers with SQLite's clock, and the two must agree.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val meta = db.appMetaDao()
    private val records = SyncRecords(db)
    private val applier = SyncApplier(db, recipes, asHub = false, clock)

    suspend fun hasSynced(): Boolean = meta.get(CURSOR_KEY) != null

    /** One Sync. The first needs [first]; after that it is ignored. */
    suspend fun sync(first: FirstSync? = null): SyncResult {
        val cursor = meta.get(CURSOR_KEY)?.toLongOrNull()
        require(cursor != null || first != null) { "The first sync needs Merge or Replace." }
        val firstChoice = if (cursor == null) first else null
        val replace = firstChoice == FirstSync.REPLACE
        SyncPhotos.backfillHashes(db, imagesDir)

        val me = SyncSchema.deviceId(db)
        val counter = SyncSchema.changeSeq(db)
        val full = cursor == null || meta.get(FULL_NEXT_KEY) == "1"
        val sentMark = meta.get(SENT_MARK_KEY)?.toLongOrNull() ?: 0L
        // Replace sends nothing, and clears this device only once the PC has answered: a failed first sync loses nothing.
        // Only what the hub understood at the last sync: a hub before Kroger refuses a whole exchange that holds anything
        // else. A table both sides gained since is caught up with a full exchange (below).
        val agreed = meta.get(TABLES_KEY)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: SyncTables.BASE
        val out = when {
            replace -> emptyList()
            full -> records.everything(me)
            else -> records.changedSince(sentMark, by = me)
        }.filter { it.table in agreed }
        val before = if (firstChoice == FirstSync.MERGE) db.recipeDao().allRecipes() else emptyList()

        // The hub the cursor came from: a cursor means nothing to another hub (a rebuilt PC), which answers with everything.
        val cursorHub = if (cursor == null) null else meta.get(CURSOR_HUB_KEY)
        val reply = transport.exchange(ExchangeRequest(clock(), cursor ?: 0L, full, out, cursorHub, SyncTables.KNOWN))
        val nowAgreed = SyncTables.KNOWN intersect reply.tables
        // A table both sides understand now that one of them didn't at the last sync is caught up with a full exchange
        // next time when something of it was left out: the hub's records of it, when this device's build didn't know it
        // (the hub kept them from it, and its cursor moved on); this device's own, when the hub's build didn't.
        val gainedTables = nowAgreed - agreed
        val mineBefore = meta.get(MY_TABLES_KEY)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: SyncTables.BASE
        val gained = gainedTables.isNotEmpty() && (
            (cursor != null && !mineBefore.containsAll(gainedTables)) || records.everything(me).any { it.table in gainedTables }
            )
        val shifted = inMyClock(reply)
        // On a client PC the recipe files go too (stage 4a), or the folder sync would index them again and send them back.
        if (replace) recipes.clearForReplace { SyncReset.clearLocal(db) }
        // A full reply carries this device's own records too. One it still has a change for is its own latest (only it
        // stamps as itself): taking it again would make a new change of its own and send it back next time.
        val incoming = shifted.filter { it.updatedBy != me || db.syncDao().change(it.table, it.key) == null }
        val result = applier.apply(incoming)

        // After a first sync the mark is the counter read after the apply, not before the request: on a full first sync
        // (above all a Replace, whose clear forgot every change) the hub's reply brings back records stamped by this
        // device, and applying them takes new counter steps that would otherwise be sent back next time. What this can
        // miss is an edit made here while the very first exchange was in flight; the first sync is a one-time step the
        // user starts and waits for, so that window is accepted.
        val mark = if (firstChoice != null) SyncSchema.changeSeq(db) else counter
        db.inTransaction {
            meta.put(AppMetaEntity(CURSOR_KEY, reply.cursor.toString()))
            meta.put(AppMetaEntity(CURSOR_HUB_KEY, reply.hubId))
            meta.put(AppMetaEntity(SENT_MARK_KEY, mark.toString()))
            meta.put(AppMetaEntity(LAST_SYNC_KEY, clock().toString()))
            // The hub answered with everything though this device sent only its changes: send everything next time.
            meta.put(AppMetaEntity(FULL_NEXT_KEY, if ((reply.full && !full) || gained) "1" else "0"))
            meta.put(AppMetaEntity(TABLES_KEY, nowAgreed.sorted().joinToString(",")))
            meta.put(AppMetaEntity(MY_TABLES_KEY, SyncTables.KNOWN.sorted().joinToString(",")))
        }

        // Every photo still wanted (the applier records each), so one a dropped sync missed is fetched now. A wish stays
        // until bytes with its image_hash arrive.
        var photos = 0
        for ((uuid, _) in SyncPhotos.wanted(db)) {
            val bytes = transport.photo(uuid) ?: continue
            if (SyncPhotos.receiveWanted(db, imagesDir, uuid, bytes)) photos++
        }
        // Only the photo the hub asked for: a file replaced here since (not saved yet) is not it.
        for (want in reply.wantPhotos) {
            val bytes = SyncPhotos.bytes(db, imagesDir, want.uuid)?.takeIf { SyncPhotos.hash(it) == want.hash } ?: continue
            transport.sendPhoto(want.uuid, bytes)
            photos++
        }
        return SyncResult(out.size, reply.records.size, reply.skipped + result.skipped, photos, duplicates(before), reply.handover)
    }

    // The reply's stamps are the hub's clock; this device's own changes are stamped by its clock. Shifted by the
    // difference (as the hub shifts a request), a phone whose clock runs ahead doesn't keep beating the hub's newer
    // edits. Never in the future and never <= 0 (0 means unchanged, and stays 0).
    private fun inMyClock(reply: ExchangeReply): List<SyncRecord> {
        val now = clock()
        val skew = now - reply.hubTime
        return reply.records.map { if (it.updatedAt == 0L) it else it.copy(updatedAt = (it.updatedAt + skew).coerceIn(1L, maxOf(now, 1L))) }
    }

    // Recipes the PC brought with a name already used here by another uuid.
    private suspend fun duplicates(before: List<RecipeEntity>): List<PossibleDuplicate> {
        if (before.isEmpty()) return emptyList()
        val mine = before.map { it.recipeUuid }.toSet()
        val byName = before.groupBy { it.name.lowercase(Locale.ROOT) }
        return db.recipeDao().allRecipes().filter { it.recipeUuid !in mine }.flatMap { pcs ->
            byName[pcs.name.lowercase(Locale.ROOT)].orEmpty().map { PossibleDuplicate(pcs.name, it.id, pcs.id) }
        }
    }

    companion object {
        const val CURSOR_KEY = "sync_cursor"

        /**
         * The id of the hub [CURSOR_KEY] came from. Not SyncHub.HUB_ID_KEY: a client PC that becomes the master must not
         * take on the old hub's identity by accident (a hand-over carries it on purpose).
         */
        const val CURSOR_HUB_KEY = "sync_cursor_hub"
        const val SENT_MARK_KEY = "sync_sent_mark"
        const val LAST_SYNC_KEY = "sync_last_at"
        const val FULL_NEXT_KEY = "sync_full_next"

        /** The tables this device and its hub both understood at the last sync, comma-separated (Kroger, plan 2026-11-17). */
        const val TABLES_KEY = "sync_tables"

        /** The tables this device's build understood at the last sync (what the hub sent it). */
        const val MY_TABLES_KEY = "sync_my_tables"
    }
}
