package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.IngredientAisleEntity
import com.naeblis11.mealplanner.data.KrogerMatchEntity
import com.naeblis11.mealplanner.data.MealSyncRow
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.data.RecipeEntity
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.SyncChangeEntity
import com.naeblis11.mealplanner.data.SyncSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Each synced table's fields in a record: what SyncRecords writes and SyncApplier reads. */
object SyncData {
    const val RAW_YAML = "raw_yaml"
    const val IMAGE_HASH = "image_hash"
    const val DATE = "date"
    const val SLOT = "slot"
    const val RECIPE_UUID = "recipe_uuid"
    const val SERVINGS = "servings"
    const val NAME = "name"
    const val EXACT_MATCH = "exact_match"
    const val AISLE = "aisle"
    const val ACTIVE = "active"
    const val ADDED_ON = "added_on"
    const val SYNC_ID = "sync_id"
    const val AMOUNT = "amount"
    const val UNIT = "unit"
    const val IN_PANTRY = "in_pantry"
    const val CHECKED = "checked"
    const val NAME_KEY = "name_key"
    const val PRODUCT_ID = "product_id"
    const val UPC = "upc"
    const val QUANTITY = "quantity"
    const val STORE_ID = "store_id"

    /** [imageHash] is the photo this device stands for: its own, or the one it still wishes for (SyncPhotos.wanted). */
    fun recipe(r: RecipeEntity, imageHash: String? = r.imageHash) = buildJsonObject { put(RAW_YAML, r.rawYaml); put(IMAGE_HASH, imageHash) }

    fun meal(m: MealSyncRow) = buildJsonObject {
        put(DATE, m.date); put(SLOT, m.slot); put(RECIPE_UUID, m.recipeUuid); put(SERVINGS, m.servings)
    }

    fun pantry(p: PantryItemEntity) = buildJsonObject {
        put(NAME, p.name); put(EXACT_MATCH, p.exactMatch); put(AISLE, p.aisle); put(ACTIVE, p.active); put(ADDED_ON, p.addedOn)
    }

    fun aisle(a: IngredientAisleEntity) = buildJsonObject { put(NAME, a.name); put(AISLE, a.aisle) }

    fun shopping(s: ShoppingItemEntity) = buildJsonObject {
        put(SYNC_ID, s.syncId); put(NAME, s.name); put(AMOUNT, s.amount); put(UNIT, s.unit); put(AISLE, s.aisle)
        put(IN_PANTRY, s.inPantry); put(CHECKED, s.checked)
    }

    fun kroger(k: KrogerMatchEntity) = buildJsonObject {
        put(NAME_KEY, k.nameKey); put(PRODUCT_ID, k.productId); put(UPC, k.upc); put(QUANTITY, k.quantity)
        put(AISLE, k.aisle); put(STORE_ID, k.aisleStoreId)
    }
}

/**
 * This device's records, read for an exchange (LAN sync spec, section 3): the changes the triggers recorded, or
 * everything for a full exchange. A recipe [isHeld] (the PC's Needs attention) is never sent. A recipe whose photo this
 * device still wishes for is sent with the wished image_hash, not its own photo's.
 */
class SyncRecords(private val db: AppDatabase, private val isHeld: suspend (Long) -> Boolean = { false }) {
    private val sync = db.syncDao()
    private val recipeDao = db.recipeDao()

    /** Every change recorded after [after] on this device's counter, only those stamped by [by] when given. */
    suspend fun changedSince(after: Long, by: String? = null): List<SyncRecord> =
        sync.changedSince(after).filter { by == null || it.updatedBy == by }.mapNotNull { record(it) }

    /**
     * Every live record and every tombstone. A record with no change of its own predates sync: it goes as [baselineBy]'s
     * at time 0, which any recorded change beats.
     */
    suspend fun everything(baselineBy: String): List<SyncRecord> {
        val changes = sync.allChanges()
        val stamps = changes.associateBy { it.tableName to it.syncKey }
        fun live(table: String, key: String, data: JsonObject): SyncRecord {
            val stamp = stamps[table to key]
            return SyncRecord(table, key, stamp?.updatedAt ?: 0L, stamp?.updatedBy ?: baselineBy, false, data)
        }
        val out = mutableListOf<SyncRecord>()
        val wishes = SyncPhotos.wanted(db)
        recipeDao.allRecipes().filterNot { isHeld(it.id) }.forEach {
            out += live(SyncTables.RECIPE, it.recipeUuid, SyncData.recipe(it, wishes[it.recipeUuid] ?: it.imageHash))
        }
        sync.meals().forEach { out += live(SyncTables.MEAL, "${it.date}|${it.slot}", SyncData.meal(it)) }
        sync.pantryItems().forEach { out += live(SyncTables.PANTRY, nameKey(it.name), SyncData.pantry(it)) }
        db.shoppingDao().knownAisles().forEach { out += live(SyncTables.AISLE, nameKey(it.name), SyncData.aisle(it)) }
        db.shoppingDao().allInIdOrder().forEach { out += live(SyncTables.SHOPPING, it.syncId, SyncData.shopping(it)) }
        db.krogerMatchDao().all().forEach { out += live(SyncTables.KROGER, it.nameKey, SyncData.kroger(it)) }
        changes.filter { it.deleted }.forEach { out += SyncRecord(it.tableName, it.syncKey, it.updatedAt, it.updatedBy, true, null) }
        return out
    }

    /** The record [change] describes now, or null when its row is gone or held. */
    private suspend fun record(change: SyncChangeEntity): SyncRecord? {
        if (change.deleted) return SyncRecord(change.tableName, change.syncKey, change.updatedAt, change.updatedBy, true, null)
        val data = data(change.tableName, change.syncKey) ?: return null
        return SyncRecord(change.tableName, change.syncKey, change.updatedAt, change.updatedBy, false, data)
    }

    /** The fields of the live record [key] in [table] here, or null when there is none (or it is a held recipe). */
    internal suspend fun data(table: String, key: String): JsonObject? = when (table) {
        SyncTables.RECIPE -> recipeDao.idForUuid(key)?.takeUnless { isHeld(it) }?.let { recipeDao.recipe(it) }
            ?.let { SyncData.recipe(it, SyncPhotos.wishFor(db, key) ?: it.imageHash) }
        SyncTables.MEAL -> key.split('|', limit = 2).takeIf { it.size == 2 }?.let { (d, s) -> sync.meal(d, s) }?.let(SyncData::meal)
        SyncTables.PANTRY -> db.pantryDao().byName(key)?.let(SyncData::pantry)
        SyncTables.AISLE -> sync.aisle(key)?.let(SyncData::aisle)
        SyncTables.SHOPPING -> sync.shopping(key)?.let(SyncData::shopping)
        SyncTables.KROGER -> db.krogerMatchDao().byKey(key)?.let(SyncData::kroger)
        else -> null
    }

    // The triggers' lower(): ASCII only, as SyncSchema.nameKey.
    private fun nameKey(name: String) = SyncSchema.nameKey(name)
}
