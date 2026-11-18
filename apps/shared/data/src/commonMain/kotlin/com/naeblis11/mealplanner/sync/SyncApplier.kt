package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.IngredientAisleEntity
import com.naeblis11.mealplanner.data.KrogerMatchEntity
import com.naeblis11.mealplanner.data.MealPlanEntity
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.SyncChangeEntity
import com.naeblis11.mealplanner.data.SyncPendingEntity
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.ShoppingMerge
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Applies received records (LAN sync spec, section 3). Newest updated_at wins; on a tie the hub's version wins (a
 * client takes it, the hub keeps its own). The write goes through this device's own paths (recipes through
 * RecipeRepository, so the PC writes the file first), the triggers record it, and the record's change is then given the
 * sender's stamp, so it isn't sent back as this device's. What can't apply yet waits in sync_pending.
 *
 * On the hub ([asHub]) two more rules: the same shopping item added on another device joins the row already here
 * (amounts add, except on a first sync), and a removal of most recipes is held (RecipeFolder's R2) until the user
 * confirms it.
 */
class SyncApplier(
    private val db: AppDatabase,
    private val recipes: RecipeRepository,
    private val asHub: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val applied: Int, val skipped: List<String>, val photosNeeded: List<String>, val held: Int)

    private val sync = db.syncDao()
    private val recipeDao = db.recipeDao()
    private val local = SyncRecords(db)

    suspend fun apply(records: List<SyncRecord>, requester: String? = null, firstSync: Boolean = false): Result {
        val retry = readable(sync.pending().filter { it.reason != MASS_REMOVAL && it.reason != PHOTO })
        val ordered = (retry + records).sortedBy { SyncTables.ORDER.indexOf(it.table) }
        var applied = 0
        var held = 0
        val skipped = mutableListOf<String>()
        val photos = mutableListOf<String>()

        val (recipeRecords, rest) = ordered.partition { it.table == SyncTables.RECIPE }
        val (removals, live) = recipeRecords.partition { it.deleted }
        val massHold = asHub && isMassRemoval(recipeRecords)
        // A mass removal holds every removal it counted (applyRecipe holds each in turn below); known now, so the meals'
        // tombstones its sender's cascade sent can wait with them.
        val heldRecipes: Set<String> =
            if (!massHold) emptySet() else removals.filter { recipeDao.idForUuid(it.key) != null && wins(it) }.map { it.key }.toSet()

        // Each record on its own: one that can't be read (a field of the wrong kind) is skipped by name, and the
        // rest of the batch still applies.
        suspend fun applyRecipes(batch: List<SyncRecord>) {
            for (r in batch) {
                try {
                    if (!wins(r)) { sync.dropPending(r.table, r.key); continue }
                    when (val outcome = applyRecipe(r, massHold)) {
                        Outcome.Applied -> { applied++; sync.dropPending(r.table, r.key) }
                        Outcome.Held -> held++
                        Outcome.Pending -> Unit
                        is Outcome.Skipped -> { skipped += outcome.label; sync.dropPending(r.table, r.key) }
                        Outcome.WantsPhoto -> { applied++; sync.dropPending(r.table, r.key); photos += r.key }
                    }
                } catch (e: IllegalArgumentException) {
                    skipped += label(r); sync.dropPending(r.table, r.key)
                }
            }
        }

        // The order matters for a removal: live recipes first (a meal may name one), then the other records, then the
        // recipes' removals last. A meal the sender moved off a recipe it then removed (the phone's Keep the PC's) is on
        // its new recipe by the time the removal's cascade runs, so the cascade doesn't take it here; a removal's cascade
        // still stamps the meals it does take with the removal's stamp (deleteRecipe).
        applyRecipes(live)
        db.inTransaction {
            for (r in rest) {
                // Every field is read before anything is written, so a record that throws here has written nothing.
                try {
                    if (!keyMatches(r)) { skipped += label(r); sync.dropPending(r.table, r.key); continue }
                    if (!wins(r)) { sync.dropPending(r.table, r.key); continue }
                    // The sender's cascade sent its meals' tombstones with the held removals: they wait with them.
                    if (r.deleted && r.table == SyncTables.MEAL && mealOf(r.key) in heldRecipes) { pend(r, MASS_REMOVAL); held++; continue }
                    if (applyRow(r, requester, firstSync)) { applied++; sync.dropPending(r.table, r.key) }
                } catch (e: IllegalArgumentException) {
                    skipped += label(r); sync.dropPending(r.table, r.key)
                }
            }
        }
        applyRecipes(removals)
        return Result(applied, skipped, photos, held)
    }

    /**
     * The removals of [uuids] held under Needs attention, applied now (the user confirmed exactly those): the recipes,
     * then the meal tombstones held with them ([heldFor]). Anything else held stays held, so a removal held after the
     * user was asked never goes with the ones they saw. Returns the number of recipes removed.
     */
    suspend fun applyHeldRemovals(uuids: Set<String>): Int {
        val held = readable(heldFor(uuids))
        var done = 0
        for (r in held.sortedBy { SyncTables.ORDER.indexOf(it.table) }) {
            // A save here since the removal was held beats it, as it would have at the exchange.
            if (!wins(r)) { sync.dropPending(r.table, r.key); continue }
            if (r.table == SyncTables.RECIPE) {
                if (applyRecipe(r, massHold = false) == Outcome.Applied) { done++; sync.dropPending(r.table, r.key) }
            } else {
                db.inTransaction { if (applyRow(r, null, false)) sync.dropPending(r.table, r.key) }
            }
        }
        return done
    }

    /**
     * The held rows that go with the recipes [uuids]: those recipes' own, and the meal tombstones held with them (a meal
     * planned here with one of them). A held row nothing shown stands for (a meal no longer planned with a held recipe,
     * or a recipe no longer here) goes with whichever action comes first, so it is never stuck. Read before anything is
     * applied, while the meals are still planned.
     */
    suspend fun heldFor(uuids: Set<String>): List<SyncPendingEntity> {
        val held = sync.pending().filter { it.reason == MASS_REMOVAL }
        val stillHeld = held.filter { it.tableName == SyncTables.RECIPE && recipeDao.idForUuid(it.syncKey) != null }.map { it.syncKey }.toSet()
        return held.filter { row ->
            if (row.tableName == SyncTables.RECIPE) {
                row.syncKey in uuids || row.syncKey !in stillHeld
            } else {
                val owner = mealOf(row.syncKey)
                owner in uuids || owner !in stillHeld
            }
        }
    }

    // The recipe uuid of the meal planned here under [key], or null.
    private suspend fun mealOf(key: String): String? =
        key.split('|', limit = 2).takeIf { it.size == 2 }?.let { (d, s) -> sync.meal(d, s) }?.recipeUuid

    private sealed interface Outcome {
        data object Applied : Outcome
        data object Held : Outcome
        data object Pending : Outcome
        data class Skipped(val label: String) : Outcome
        data object WantsPhoto : Outcome
    }

    // Newest wins. A record with no change here counts as time 0 when its row exists (it predates sync), and anything
    // beats a row that isn't here. On a tie the hub keeps its own and a client takes the hub's.
    private suspend fun wins(r: SyncRecord): Boolean {
        val mine = sync.change(r.table, r.key)?.updatedAt ?: if (local.data(r.table, r.key) != null) 0L else return true
        return r.updatedAt > mine || (r.updatedAt == mine && !asHub)
    }

    private suspend fun isMassRemoval(records: List<SyncRecord>): Boolean {
        val removals = records.count { it.deleted && recipeDao.idForUuid(it.key) != null && wins(it) }
        val total = recipeDao.count()
        return removals > 0 && (removals == total || (removals > MASS_REMOVAL_MIN && removals * 2 > total))
    }

    private suspend fun applyRecipe(r: SyncRecord, massHold: Boolean): Outcome {
        val since = SyncSchema.changeSeq(db)
        if (r.deleted) {
            val id = recipeDao.idForUuid(r.key)
            if (id != null) {
                if (massHold) { pend(r, MASS_REMOVAL); return Outcome.Held }
                try { deleteRecipe(id, r) } catch (e: IOException) { pend(r, NOT_SAVED); return Outcome.Pending }
            }
            restamp(r, since)
            SyncPhotos.forget(db, r.key)
            return Outcome.Applied
        }
        val data = r.data!!
        val raw = SyncWire.text(data, SyncData.RAW_YAML)
        @Suppress("UNCHECKED_CAST")
        val doc = try { RecipeYaml.load(raw) as? YamlMap } catch (e: Exception) { null }
        val label = doc?.get("recipe_name")?.toString() ?: r.key
        if (doc == null || doc["recipe_uuid"]?.toString() != r.key) return Outcome.Skipped(label)
        val wanted = SyncWire.textOrNull(data, SyncData.IMAGE_HASH)
        // The same recipe and photo as here (a full exchange of an unchanged library): only the stamp is taken, so the
        // PC doesn't write every recipe file again.
        if (sameRecipe(r.key, doc, wanted)) {
            restamp(r, since)
            return photoOf(r.key, wanted)
        }
        try {
            recipes.saveAll(listOf(doc))
        } catch (e: IOException) {
            pend(r, NOT_SAVED) // held by the PC's folder sync, or the library refused: try again next time
            return Outcome.Pending
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Skipped(label)
        }
        restamp(r, since)
        return photoOf(r.key, wanted)
    }

    // True when the recipe [uuid] here (not held) parses to [doc] and stands for the photo [imageHash].
    private suspend fun sameRecipe(uuid: String, doc: YamlMap, imageHash: String?): Boolean {
        val row = recipeDao.idForUuid(uuid)?.let { recipeDao.recipe(it) } ?: return false
        if (recipes.isHeld(row.id)) return false
        val here = try { RecipeYaml.load(row.rawYaml) } catch (e: Exception) { return false }
        return here == doc && imageHash == local.data(SyncTables.RECIPE, uuid)?.let { SyncWire.textOrNull(it, SyncData.IMAGE_HASH) }
    }

    // The photo the applied record names. A wish ends here only when the record names the photo this device has; a
    // record naming another photo replaces the wish (a wish for the same photo is kept, with the photo it was made
    // over); one naming no photo leaves it.
    private suspend fun photoOf(uuid: String, wanted: String?): Outcome {
        val here = recipeDao.idForUuid(uuid)?.let { recipeDao.recipe(it) }?.imageHash
        if (wanted == here) { SyncPhotos.forget(db, uuid); return Outcome.Applied }
        if (wanted == null) return Outcome.Applied
        if (SyncPhotos.wishedHash(db, uuid) != wanted) SyncPhotos.want(db, uuid, wanted, here)
        return Outcome.WantsPhoto
    }

    // Deleting a recipe cascades to its planned meals, and the delete trigger stamps each meal's tombstone at this
    // device's now. The sender's own cascade happened no later than its recipe tombstone, so the meals' tombstones take
    // that stamp instead (keeping the trigger's counter step): a meal the sender planned in the slot afterwards still
    // wins, and the tombstones don't go back to anyone as this device's change.
    private suspend fun deleteRecipe(id: Long, r: SyncRecord) {
        val slots = sync.meals().filter { it.recipeUuid == r.key }.map { "${it.date}|${it.slot}" }
        recipes.delete(id)
        for (key in slots) {
            val seq = sync.change(SyncTables.MEAL, key)?.changeSeq ?: run { sync.bumpChangeSeq(); SyncSchema.changeSeq(db) }
            sync.putChange(SyncChangeEntity(SyncTables.MEAL, key, r.updatedAt, r.updatedBy, seq, true))
        }
    }

    // A record's key is made from its data (SyncSchema.TABLES); one whose key says another record (a pantry key "salt"
    // with the name "Pepper") would overwrite one row and stamp another, so it is skipped. A tombstone has no data to
    // check, and a recipe's uuid is checked against its YAML by applyRecipe.
    private fun keyMatches(r: SyncRecord): Boolean {
        val d = r.data ?: return true
        return when (r.table) {
            SyncTables.PANTRY, SyncTables.AISLE -> r.key == SyncSchema.nameKey(SyncWire.text(d, SyncData.NAME))
            SyncTables.MEAL -> r.key == SyncSchema.mealKey(SyncWire.text(d, SyncData.DATE), SyncWire.text(d, SyncData.SLOT))
            SyncTables.SHOPPING -> r.key == SyncWire.text(d, SyncData.SYNC_ID)
            SyncTables.KROGER -> r.key == SyncWire.text(d, SyncData.NAME_KEY)
            else -> true
        }
    }

    // One non-recipe record, inside the caller's transaction. False when it waits (a meal without its recipe).
    private suspend fun applyRow(r: SyncRecord, requester: String?, firstSync: Boolean): Boolean {
        val since = SyncSchema.changeSeq(db)
        val d = r.data
        // The same fields as here: only the stamp is taken.
        if (d != null && d == local.data(r.table, r.key)) {
            restamp(r, since)
            return true
        }
        when (r.table) {
            SyncTables.MEAL -> {
                val parts = r.key.split('|', limit = 2)
                if (parts.size != 2) return false // not a meal's key: nothing here can hold it
                val (date, slot) = parts
                if (d == null) {
                    db.mealPlanDao().remove(date, slot)
                } else {
                    val recipeId = recipeDao.idForUuid(SyncWire.text(d, SyncData.RECIPE_UUID))
                    if (recipeId == null) { pend(r, MISSING_RECIPE); return false }
                    db.mealPlanDao().put(MealPlanEntity(date = date, slot = slot, recipeId = recipeId, servings = SyncWire.textOrNull(d, SyncData.SERVINGS)))
                }
            }
            SyncTables.PANTRY -> {
                val here = db.pantryDao().byName(d?.let { SyncWire.text(it, SyncData.NAME) } ?: r.key)
                if (d == null) {
                    here?.let { db.pantryDao().delete(it.id) }
                } else {
                    sync.putPantry(
                        PantryItemEntity(
                            id = here?.id ?: 0, name = SyncWire.text(d, SyncData.NAME), exactMatch = SyncWire.flag(d, SyncData.EXACT_MATCH),
                            aisle = SyncWire.textOrNull(d, SyncData.AISLE), active = SyncWire.flag(d, SyncData.ACTIVE),
                            addedOn = SyncWire.textOrNull(d, SyncData.ADDED_ON),
                        ),
                    )
                }
            }
            SyncTables.AISLE -> {
                if (d == null) {
                    db.shoppingDao().forgetAisle(r.key)
                } else {
                    val here = sync.aisle(SyncWire.text(d, SyncData.NAME))
                    db.shoppingDao().rememberAisle(IngredientAisleEntity(id = here?.id ?: 0, name = SyncWire.text(d, SyncData.NAME), aisle = SyncWire.text(d, SyncData.AISLE)))
                }
            }
            SyncTables.SHOPPING -> {
                if (d == null) {
                    sync.deleteShopping(r.key)
                } else {
                    val here = sync.shopping(r.key)
                    if (here == null && asHub && sync.change(r.table, r.key) == null && joinSameItem(r, d, requester, firstSync)) return true
                    sync.putShopping(
                        ShoppingItemEntity(
                            id = here?.id ?: 0, name = SyncWire.text(d, SyncData.NAME), amount = SyncWire.textOrNull(d, SyncData.AMOUNT),
                            unit = SyncWire.textOrNull(d, SyncData.UNIT), aisle = SyncWire.textOrNull(d, SyncData.AISLE),
                            inPantry = SyncWire.flag(d, SyncData.IN_PANTRY), checked = SyncWire.flag(d, SyncData.CHECKED), syncId = r.key,
                        ),
                    )
                }
            }
            SyncTables.KROGER -> {
                if (d == null) {
                    db.krogerMatchDao().delete(r.key)
                } else {
                    val match = KrogerMatchEntity(
                        nameKey = r.key, productId = SyncWire.text(d, SyncData.PRODUCT_ID), upc = SyncWire.text(d, SyncData.UPC),
                        quantity = SyncWire.int(d, SyncData.QUANTITY), aisle = SyncWire.textOrNull(d, SyncData.AISLE),
                        aisleStoreId = SyncWire.textOrNull(d, SyncData.STORE_ID),
                    )
                    require(match.productId.isNotEmpty() && match.upc.isNotEmpty() && match.quantity >= 1) { "Not a Kroger match." }
                    db.krogerMatchDao().put(match)
                }
            }
        }
        restamp(r, since)
        return true
    }

    // The hub's additive rule: a new row whose name is already on the list (from anyone but the sender) joins that row,
    // and the sender's row becomes a tombstone stamped by the hub now, so every device ends with one row. On a first
    // sync both lists were made apart, so the item is matched, not added twice. False when the amounts can't combine
    // (other units): the row then stays on its own.
    private suspend fun joinSameItem(r: SyncRecord, d: JsonObject, requester: String?, firstSync: Boolean): Boolean {
        val same = sync.shoppingNamed(SyncWire.text(d, SyncData.NAME))
            .firstOrNull { sync.change(SyncTables.SHOPPING, it.syncId)?.updatedBy != requester } ?: return false
        if (!firstSync) {
            val row = ShoppingMerge.ListRow(same.id, same.name, same.amount, same.unit, same.aisle, same.inPantry, same.checked)
            val merged = ShoppingMerge.mergeAmounts(row, SyncWire.textOrNull(d, SyncData.AMOUNT), SyncWire.textOrNull(d, SyncData.UNIT)) ?: return false
            db.shoppingDao().update(same.copy(amount = merged.first, unit = merged.second, checked = false))
        }
        sync.bumpChangeSeq()
        sync.putChange(SyncChangeEntity(SyncTables.SHOPPING, r.key, clock(), SyncSchema.deviceId(db), SyncSchema.changeSeq(db), true))
        return true
    }

    // The record's change takes the sender's stamp. Its counter step is the one the trigger just took (after [since],
    // the counter read before the write), or a new one when the write changed nothing here: either way a step of its
    // own, so the hub passes the newer stamp on to devices that synced before it.
    private suspend fun restamp(r: SyncRecord, since: Long) {
        val seq = sync.change(r.table, r.key)?.changeSeq?.takeIf { it > since } ?: run { sync.bumpChangeSeq(); SyncSchema.changeSeq(db) }
        sync.putChange(SyncChangeEntity(r.table, r.key, r.updatedAt, r.updatedBy, seq, r.deleted))
    }

    // Waiting records, decoded. One that can't be read (written by a broken build, say) is dropped, never left to stop
    // every exchange after it.
    private suspend fun readable(rows: List<SyncPendingEntity>): List<SyncRecord> = rows.mapNotNull { p ->
        try {
            SyncWire.decodeRecord(p.record)
        } catch (e: IllegalArgumentException) {
            sync.dropPending(p.tableName, p.syncKey)
            null
        }
    }

    private suspend fun pend(r: SyncRecord, reason: String) =
        sync.putPending(SyncPendingEntity(r.table, r.key, reason, SyncWire.encodeRecord(r)))

    companion object {
        const val MISSING_RECIPE = "missing_recipe"
        const val NOT_SAVED = "not_saved"
        const val MASS_REMOVAL = "mass_removal"

        /**
         * A photo this device still has to fetch (or, on the hub, be sent) for the recipe whose uuid is the row's key. Its
         * rows are in their own [PHOTO_TABLE], so a recipe's not_saved or mass_removal row never replaces one; their
         * record is the wanted image_hash as plain text, not a SyncRecord.
         */
        const val PHOTO = "photo"
        const val PHOTO_TABLE = "recipe_photo"

        /** What a skipped record is called in a result: its name when it has one, else its key. */
        fun label(r: SyncRecord): String =
            (r.data?.get(SyncData.NAME) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: r.key

        /** RecipeFolder's R2: more than this many, and more than half, is a mass removal. */
        const val MASS_REMOVAL_MIN = 3
    }
}
