package com.naeblis11.mealplanner.kroger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.KrogerMatchRepository
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.KrogerMatch
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import com.naeblis11.mealplanner.domain.KrogerQuery
import com.naeblis11.mealplanner.domain.KrogerRanking
import com.naeblis11.mealplanner.shopping.shoppingLabel
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item of a send: the shopping rows with the same name (as one), what Kroger has for it at the store, and what the
 * household picked. [results] is Kroger's own list (shown through [shown], ranked); [remembered] is the remembered
 * match's product as the store has it now, null when there is none or the store no longer has it ([rememberedGone]).
 */
data class SendItem(
    val nameKey: String,
    val name: String,
    val label: String,
    val rowIds: List<Long>,
    val term: String,
    val remembered: KrogerProduct? = null,
    val rememberedGone: Boolean = false,
    val results: List<KrogerProduct> = emptyList(),
    val searched: Boolean = false,
    val more: Boolean = false,
    val searching: Boolean = false,
    val error: String? = null,
    val showUnavailable: Boolean = false,
    val pick: KrogerProduct? = null,
    val quantity: Int = 1,
    val skipped: Boolean = false,
) {
    /**
     * What the review lists for [modality]: the remembered product first, then the search's, closest first. Picking
     * one never moves it (owner, 2026-11-17); only a pick the list no longer holds (a new search) is put first.
     */
    fun shown(modality: KrogerModality): List<KrogerProduct> {
        val ranked = KrogerRanking.rank(term, results, modality, showUnavailable)
        val list = listOfNotNull(remembered) + ranked.filter { r -> r.productId != remembered?.productId }
        val p = pick ?: return list
        return if (list.any { it.productId == p.productId }) list else listOf(p) + list
    }
}

/** Where a send stands (plan 2026-11-17, "The send"). */
sealed interface SendState {
    data object Loading : SendState

    /** Kroger isn't ready here (no client, no sign-in, no store), or the list has nothing to send. */
    data class NotReady(val message: String, val toSettings: Boolean, val offerPantry: Boolean = false) : SendState

    data class Reviewing(
        val items: List<SendItem>,
        val index: Int,
        val modality: KrogerModality,
        val includePantry: Boolean,
        val pantryCount: Int,
    ) : SendState {
        val current: SendItem get() = items[index]
    }

    data class Summary(val items: List<SendItem>, val modality: KrogerModality, val adding: Boolean = false, val error: String? = null) : SendState {
        val picked: List<SendItem> get() = items.filter { it.pick != null && !it.skipped }
        val skipped: List<SendItem> get() = items.filter { it.pick == null || it.skipped }
    }

    /** The cart took [added]; [rowIds] are their shopping rows, which Check them off ticks. */
    data class Done(val added: List<SendItem>, val rowIds: List<Long>, val checkedOff: Boolean = false) : SendState
}

/**
 * Send to Kroger: the unchecked shopping rows (pantry rows left out unless asked), each matched to a product at the
 * chosen store, then all added to the cart in one call. Remembered matches come up picked; a send's picks are
 * remembered only once the cart took them. Nothing Kroger returned is written anywhere: only the product ids and
 * quantities go into kroger_match.
 */
class KrogerSendViewModel(
    private val controls: KrogerControls,
    private val shopping: ShoppingRepository,
    private val matches: KrogerMatchRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<SendState>(SendState.Loading)
    val state: StateFlow<SendState> = _state.asStateFlow()

    private var includePantry = false
    private var pantryCount = 0
    private var allItems: List<SendItem> = emptyList()

    init {
        load()
    }

    private fun load() {
        val status = controls.status.value
        when {
            !status.clientReady -> { _state.value = SendState.NotReady(KrogerMessages.NO_CLIENT, toSettings = true); return }
            status.certification -> { _state.value = SendState.NotReady(KrogerMessages.CERTIFICATION_NO_CART, toSettings = true); return }
            !status.signedIn -> { _state.value = SendState.NotReady(KrogerMessages.NOT_SIGNED_IN, toSettings = true); return }
            status.store == null -> { _state.value = SendState.NotReady(KrogerMessages.NO_STORE, toSettings = true); return }
        }
        _state.value = SendState.Loading
        viewModelScope.launch {
            try {
                val rows = shopping.observe().first().filter { !it.checked }
                pantryCount = rows.count { it.inPantry }
                allItems = items(rows)
                if (allItems.isEmpty()) {
                    showReview(0)
                    return@launch
                }
                val remembered = matches.forKeys(allItems.map { it.nameKey })
                val products = withContext(io) { controls.products(remembered.values.map { it.productId }) }.associateBy { it.productId }
                noteAisles(products.values)
                val modality = controls.status.value.modality
                allItems = allItems.map { item ->
                    val match = remembered[item.nameKey] ?: return@map item
                    val product = products[match.productId]
                    if (product != null && product.availableFor(modality)) {
                        item.copy(remembered = product, pick = product, quantity = match.quantity)
                    } else {
                        item.copy(rememberedGone = true, quantity = match.quantity)
                    }
                }
                showReview(0)
            } catch (e: IOException) {
                _state.value = SendState.NotReady(e.message ?: KrogerMessages.OFFLINE, toSettings = false)
            }
        }
    }

    // Where the store shelves the remembered products, for the shopping list (owner, 2026-11-17); never fails the send.
    private suspend fun noteAisles(products: Collection<KrogerProduct>) {
        val storeId = controls.status.value.store?.locationId ?: return
        try {
            matches.noteAisles(products, storeId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    // The rows to send, one item per name: pantry rows only when asked.
    private fun items(rows: List<ShoppingItemEntity>): List<SendItem> =
        rows.filter { includePantry || !it.inPantry }
            .groupBy { KrogerQuery.nameKey(it.name) }
            .filterKeys { it.isNotEmpty() }
            .map { (key, same) ->
                SendItem(
                    nameKey = key,
                    name = same.first().name,
                    label = same.joinToString(" + ") { shoppingLabel(it) },
                    rowIds = same.map { it.id },
                    term = KrogerQuery.term(same.first().name),
                )
            }

    private fun showReview(index: Int) {
        val modality = controls.status.value.modality
        if (allItems.isEmpty()) {
            val offer = pantryCount > 0 && !includePantry
            _state.value = SendState.NotReady(if (offer) ALL_IN_PANTRY else NOTHING_TO_SEND, toSettings = false, offerPantry = offer)
            return
        }
        _state.value = SendState.Reviewing(allItems, index.coerceIn(0, allItems.lastIndex), modality, includePantry, pantryCount)
        // An item with nothing picked is searched as it comes up.
        val item = allItems[index.coerceIn(0, allItems.lastIndex)]
        if (item.pick == null && !item.searched && !item.searching) search(item.term)
    }

    private fun updateCurrent(change: (SendItem) -> SendItem) {
        val s = _state.value as? SendState.Reviewing ?: return
        allItems = allItems.toMutableList().also { it[s.index] = change(it[s.index]) }
        _state.value = s.copy(items = allItems)
    }

    /** Kroger's search for [term] at the store, for the current item; [more] adds the next page. */
    fun search(term: String, more: Boolean = false) {
        val s = _state.value as? SendState.Reviewing ?: return
        val index = s.index
        val key = s.current.nameKey
        val start = if (more) s.current.results.size else 0
        val cleaned = term.trim()
        updateCurrent { it.copy(term = cleaned, searching = true, error = null) }
        viewModelScope.launch {
            val outcome = try {
                Result.success(withContext(io) { controls.search(cleaned, start) })
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
            // The item may have moved on; write back by its key.
            val i = allItems.indexOfFirst { it.nameKey == key }.takeIf { it >= 0 } ?: index
            allItems = allItems.toMutableList().also { list ->
                val item = list[i]
                list[i] = outcome.fold(
                    { found ->
                        item.copy(
                            results = if (more) item.results + found.filter { f -> item.results.none { it.productId == f.productId } } else found,
                            searched = true, searching = false, more = found.size >= PAGE, error = null,
                        )
                    },
                    { e -> item.copy(searching = false, searched = true, error = e.message ?: KrogerMessages.OFFLINE) },
                )
            }
            (_state.value as? SendState.Reviewing)?.let { _state.value = it.copy(items = allItems) }
        }
    }

    fun pick(product: KrogerProduct) = updateCurrent { it.copy(pick = product, skipped = false) }

    fun setQuantity(quantity: Int) = updateCurrent { it.copy(quantity = quantity.coerceIn(1, KrogerApi.MAX_QUANTITY)) }

    fun setShowUnavailable(show: Boolean) = updateCurrent { it.copy(showUnavailable = show) }

    /** Change the remembered or picked product: the search for the item's term. */
    fun change() {
        val s = _state.value as? SendState.Reviewing ?: return
        if (!s.current.searched) search(s.current.term)
    }

    /** The next item, or the summary after the last; Skip leaves this item off the send. */
    fun next(skip: Boolean = false) {
        val s = _state.value as? SendState.Reviewing ?: return
        if (skip) updateCurrent { it.copy(skipped = true) }
        if (s.index >= allItems.lastIndex) {
            _state.value = SendState.Summary(allItems, s.modality)
        } else {
            showReview(s.index + 1)
        }
    }

    fun back() {
        when (val s = _state.value) {
            is SendState.Reviewing -> if (s.index > 0) showReview(s.index - 1)
            is SendState.Summary -> if (!s.adding) showReview(allItems.lastIndex)
            else -> Unit
        }
    }

    /** From the summary, back to [nameKey]'s item. */
    fun edit(nameKey: String) {
        val s = _state.value as? SendState.Summary ?: return
        if (s.adding) return
        allItems.indexOfFirst { it.nameKey == nameKey }.takeIf { it >= 0 }?.let(::showReview)
    }

    /** Pantry rows in or out of the send; what was picked so far is kept. */
    fun setIncludePantry(include: Boolean) {
        if (include == includePantry) return
        includePantry = include
        val before = allItems.associateBy { it.nameKey }
        viewModelScope.launch {
            val rows = shopping.observe().first().filter { !it.checked }
            pantryCount = rows.count { it.inPantry }
            val fresh = items(rows)
            val newKeys = fresh.map { it.nameKey }.filter { it !in before }
            val remembered = matches.forKeys(newKeys)
            val products = try {
                withContext(io) { controls.products(remembered.values.map { it.productId }) }.associateBy { it.productId }
            } catch (e: IOException) {
                emptyMap()
            }
            noteAisles(products.values)
            val modality = controls.status.value.modality
            allItems = fresh.map { item ->
                before[item.nameKey]?.copy(rowIds = item.rowIds, label = item.label) ?: run {
                    val match = remembered[item.nameKey]
                    val product = match?.let { products[it.productId] }
                    when {
                        match == null -> item
                        product != null && product.availableFor(modality) -> item.copy(remembered = product, pick = product, quantity = match.quantity)
                        else -> item.copy(rememberedGone = true, quantity = match.quantity)
                    }
                }
            }
            showReview(0)
        }
    }

    /** The one explicit action: every pick into the cart, then the picks remembered. */
    fun addToCart() {
        val s = _state.value as? SendState.Summary ?: return
        if (s.adding || s.picked.isEmpty()) return
        _state.value = s.copy(adding = true, error = null)
        val picked = s.picked
        val storeId = controls.status.value.store?.locationId
        viewModelScope.launch {
            try {
                withContext(io) { controls.addToCart(picked.map { KrogerCartItem(it.pick!!.upc, it.quantity) }) }
            } catch (e: IOException) {
                _state.value = s.copy(adding = false, error = e.message ?: KrogerMessages.OFFLINE)
                return@launch
            }
            // The cart has them: only now are the picks remembered. A failure here loses no cart item.
            try {
                matches.keep(
                    picked.map {
                        val pick = it.pick!!
                        KrogerMatch(it.nameKey, pick.productId, pick.upc, it.quantity, pick.aisle, storeId.takeIf { pick.aisle != null })
                    },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            _state.value = SendState.Done(picked, picked.flatMap { it.rowIds })
        }
    }

    /** After the send: ticks the added rows on the shopping list. */
    fun checkOff() {
        val s = _state.value as? SendState.Done ?: return
        if (s.checkedOff) return
        _state.value = s.copy(checkedOff = true)
        viewModelScope.launch { s.rowIds.forEach { shopping.setChecked(it, true) } }
    }

    companion object {
        const val PAGE = KrogerApi.PAGE
        const val NOTHING_TO_SEND = "Nothing on the shopping list needs buying."
        const val ALL_IN_PANTRY = "Everything on the shopping list is already in your pantry."
    }
}
