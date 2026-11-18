package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.naeblis11.mealplanner.domain.KrogerMatch
import kotlinx.coroutines.flow.Flow

/**
 * A remembered Kroger pick (plan 2026-11-17, "Remembered matches"): the shopping list's name for an item
 * ([nameKey], KrogerQuery.nameKey) and the product the household chose for it, with the last quantity sent. Only
 * Kroger's identifiers are kept, never a description or a price (Kroger's acceptable use), plus where [aisleStoreId]'s
 * store shelves it ([aisle], owner 2026-11-17, schema 10), for the shopping list. Synced (kroger_match).
 */
@Entity(tableName = "kroger_match")
data class KrogerMatchEntity(
    @PrimaryKey @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val upc: String,
    val quantity: Int,
    val aisle: String? = null,
    @ColumnInfo(name = "aisle_store_id") val aisleStoreId: String? = null,
) {
    fun toMatch() = KrogerMatch(nameKey, productId, upc, quantity, aisle, aisleStoreId)

    companion object {
        fun of(m: KrogerMatch) = KrogerMatchEntity(m.nameKey, m.productId, m.upc, m.quantity, m.aisle, m.aisleStoreId)
    }
}

@Dao
interface KrogerMatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(match: KrogerMatchEntity)

    @Query("SELECT * FROM kroger_match WHERE name_key = :nameKey")
    suspend fun byKey(nameKey: String): KrogerMatchEntity?

    @Query("SELECT * FROM kroger_match ORDER BY name_key")
    suspend fun all(): List<KrogerMatchEntity>

    @Query("SELECT * FROM kroger_match ORDER BY name_key")
    fun observeAll(): Flow<List<KrogerMatchEntity>>

    @Query("DELETE FROM kroger_match WHERE name_key = :nameKey")
    suspend fun delete(nameKey: String)

    @Query("DELETE FROM kroger_match")
    suspend fun clear()
}
