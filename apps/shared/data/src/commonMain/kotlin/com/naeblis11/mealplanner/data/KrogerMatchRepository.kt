package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.KrogerMatch
import com.naeblis11.mealplanner.domain.KrogerProduct
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The household's remembered Kroger picks (plan 2026-11-17). A send's picks are kept only once the cart took them
 * ([keep]); Settings' Matches changes or forgets one. Writes take a fair lock, so taps apply in tap order.
 */
class KrogerMatchRepository(
    db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.krogerMatchDao()
    private val writeLock = Mutex()

    fun observe(): Flow<List<KrogerMatch>> = dao.observeAll().map { rows -> rows.map { it.toMatch() } }

    suspend fun all(): List<KrogerMatch> = withContext(dispatcher) { dao.all().map { it.toMatch() } }

    suspend fun forKeys(keys: Collection<String>): Map<String, KrogerMatch> = withContext(dispatcher) {
        keys.toSet().mapNotNull { dao.byKey(it)?.toMatch() }.associateBy { it.nameKey }
    }

    /**
     * Keeps [matches], each replacing the one for its name; a match that is already the same writes nothing. The same
     * product without an aisle keeps the aisle already known for it.
     */
    suspend fun keep(matches: Collection<KrogerMatch>) = withContext(dispatcher) {
        writeLock.withLock {
            for (m in matches) {
                require(m.nameKey.isNotEmpty() && m.productId.isNotEmpty() && m.upc.isNotEmpty() && m.quantity >= 1) { "Not a match." }
                val old = dao.byKey(m.nameKey)?.toMatch()
                val kept = if (m.aisle == null && old != null && old.productId == m.productId) {
                    m.copy(aisle = old.aisle, aisleStoreId = old.aisleStoreId)
                } else {
                    m
                }
                if (old != kept) dao.put(KrogerMatchEntity.of(kept))
            }
        }
    }

    /**
     * The aisles [storeId]'s store gave just now for the remembered products among [products] (owner, 2026-11-17):
     * each match whose product is there takes its aisle, unless Kroger gave none. A match already the same writes
     * nothing, so looking up again makes no sync change.
     */
    suspend fun noteAisles(products: Collection<KrogerProduct>, storeId: String) = withContext(dispatcher) {
        val aisles = products.mapNotNull { p -> p.aisle?.takeIf { it.isNotBlank() }?.let { p.productId to it } }.toMap()
        if (aisles.isEmpty()) return@withContext
        writeLock.withLock {
            for (row in dao.all()) {
                val aisle = aisles[row.productId] ?: continue
                if (row.aisle != aisle || row.aisleStoreId != storeId) dao.put(row.copy(aisle = aisle, aisleStoreId = storeId))
            }
        }
    }

    suspend fun forget(nameKey: String) = withContext(dispatcher) { writeLock.withLock { dao.delete(nameKey) } }
}
