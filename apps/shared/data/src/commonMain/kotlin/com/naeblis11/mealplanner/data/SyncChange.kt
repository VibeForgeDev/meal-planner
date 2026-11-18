package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.UUID

/**
 * The last change to one synced record (LAN sync spec, section 1): which table, its sync key (SyncSchema.TABLES), when
 * (UTC milliseconds), by which device, and this device's change counter when it was recorded. [deleted] makes it a
 * tombstone; tombstones are kept for good. SyncSchema's triggers write these rows, never the repositories. A record
 * with no row here hasn't changed since sync began.
 */
@Entity(tableName = "sync_change", primaryKeys = ["table_name", "sync_key"], indices = [Index(value = ["change_seq"])])
data class SyncChangeEntity(
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "sync_key") val syncKey: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    @ColumnInfo(name = "change_seq") val changeSeq: Long,
    val deleted: Boolean,
)

/**
 * A received record that couldn't be applied yet (LAN sync stage 2a): a meal whose recipe isn't here
 * ("missing_recipe"), a recipe the PC refused to save ("not_saved": held, or the library blocked), or a recipe removal
 * the hub holds ("mass_removal"). [record] is the record as SyncWire writes it. The first two are retried at every
 * exchange; a held removal waits for the user. A photo still to move ("photo", table "recipe_photo", keyed by the
 * recipe uuid) is the exception: its [record] is "wanted|here", the wanted image_hash and the hash of the photo this
 * device had when the wish was made, as plain text (SyncPhotos.want).
 */
@Entity(tableName = "sync_pending", primaryKeys = ["table_name", "sync_key"])
data class SyncPendingEntity(
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "sync_key") val syncKey: String,
    val reason: String,
    val record: String,
)

/** A planned meal as sync sends it: by its recipe's uuid, not this device's row id. */
data class MealSyncRow(
    val date: String,
    val slot: String,
    @ColumnInfo(name = "recipe_uuid") val recipeUuid: String,
    val servings: String?,
)

@Dao
interface SyncDao {
    /** Every change recorded after [after] on this device's counter, oldest first. */
    @Query("SELECT * FROM sync_change WHERE change_seq > :after ORDER BY change_seq")
    suspend fun changedSince(after: Long): List<SyncChangeEntity>

    @Query("SELECT * FROM sync_change WHERE table_name = :table AND sync_key = :key")
    suspend fun change(table: String, key: String): SyncChangeEntity?

    /** Dates a recorded change (a recipe file's modified time); its order on the counter is kept. */
    @Query("UPDATE sync_change SET updated_at = :at WHERE table_name = :table AND sync_key = :key")
    suspend fun setUpdatedAt(table: String, key: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChange(change: SyncChangeEntity)

    @Query("DELETE FROM sync_change WHERE table_name = :table AND sync_key = :key")
    suspend fun deleteChange(table: String, key: String)

    @Query("SELECT * FROM sync_change ORDER BY change_seq")
    suspend fun allChanges(): List<SyncChangeEntity>

    /** One step of this device's change counter, for a change the triggers didn't record. */
    @Query("UPDATE app_meta SET `value` = CAST(`value` AS INTEGER) + 1 WHERE `key` = 'change_seq'")
    suspend fun bumpChangeSeq()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPending(pending: SyncPendingEntity)

    @Query("SELECT * FROM sync_pending ORDER BY rowid")
    suspend fun pending(): List<SyncPendingEntity>

    @Query("DELETE FROM sync_pending WHERE table_name = :table AND sync_key = :key")
    suspend fun dropPending(table: String, key: String)

    @Query("SELECT * FROM sync_pending WHERE table_name = :table AND sync_key = :key")
    suspend fun pendingFor(table: String, key: String): SyncPendingEntity?

    @Query(
        """SELECT mp.date, mp.slot, r.recipe_uuid, mp.servings FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id
           WHERE mp.date = :date AND mp.slot = :slot""",
    )
    suspend fun meal(date: String, slot: String): MealSyncRow?

    @Query("SELECT mp.date, mp.slot, r.recipe_uuid, mp.servings FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id ORDER BY mp.id")
    suspend fun meals(): List<MealSyncRow>

    @Query("SELECT * FROM pantry_item ORDER BY id")
    suspend fun pantryItems(): List<PantryItemEntity>

    /** REPLACE by id (or the unique name): one statement, so the insert trigger records it once. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPantry(item: PantryItemEntity)

    @Query("SELECT * FROM ingredient_aisle WHERE name = :name")
    suspend fun aisle(name: String): IngredientAisleEntity?

    @Query("SELECT * FROM shopping_list_item WHERE sync_id = :syncId")
    suspend fun shopping(syncId: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_list_item WHERE name = :name COLLATE NOCASE ORDER BY id")
    suspend fun shoppingNamed(name: String): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putShopping(item: ShoppingItemEntity)

    @Query("DELETE FROM shopping_list_item WHERE sync_id = :syncId")
    suspend fun deleteShopping(syncId: String)

    // "Replace this device's data" (SyncReset): every synced table, emptied in one transaction with sync_change.
    @Query("DELETE FROM meal_plan") suspend fun clearMeals()
    @Query("DELETE FROM recipe_ingredient") suspend fun clearIngredients()
    @Query("DELETE FROM recipe_step") suspend fun clearSteps()
    @Query("DELETE FROM recipe") suspend fun clearRecipes()
    @Query("DELETE FROM pantry_item") suspend fun clearPantry()
    @Query("DELETE FROM ingredient_aisle") suspend fun clearAisles()
    @Query("DELETE FROM shopping_list_item") suspend fun clearShopping()
    @Query("DELETE FROM kroger_match") suspend fun clearKrogerMatches()
    @Query("DELETE FROM sync_change") suspend fun clearChanges()
    @Query("DELETE FROM sync_pending") suspend fun clearPending()
}

/** A shopping row's sync id: 128 random bits as 32 lower-case hex digits, like AppMetaIds. */
object SyncIds {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
