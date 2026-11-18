package com.naeblis11.mealplanner.kroger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.KrogerMatchRepository
import com.naeblis11.mealplanner.domain.KrogerMatch
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import com.naeblis11.mealplanner.domain.KrogerRanking
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.PlatformBackHandler
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A remembered match and, when the store could be asked, its product there now (never stored). */
data class MatchRow(val match: KrogerMatch, val product: KrogerProduct?)

/** The search for a new product for [nameKey]: Kroger's results, ranked, while Change is open. */
data class MatchChange(
    val nameKey: String,
    val term: String,
    val results: List<KrogerProduct> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

data class MatchesState(
    val rows: List<MatchRow>? = null,
    val modality: KrogerModality = KrogerModality.PICKUP,
    val error: String? = null,
    val change: MatchChange? = null,
)

/** Settings' Remembered products: every match, with the product as the store has it now, Change and Forget. */
class KrogerMatchesViewModel(
    private val controls: KrogerControls,
    private val matches: KrogerMatchRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(MatchesState(modality = controls.status.value.modality))
    val state: StateFlow<MatchesState> = _state.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val all = matches.all()
            // Only what the store can say: without a store or a sign-in to Kroger's client, the names alone.
            val storeId = controls.status.value.store?.locationId
            val products = if (!controls.status.value.clientReady || storeId == null) {
                emptyMap()
            } else {
                try {
                    withContext(io) { controls.products(all.map { it.productId }) }.associateBy { it.productId }
                } catch (e: IOException) {
                    _state.value = _state.value.copy(error = e.message)
                    emptyMap()
                }
            }
            // The aisles the store gave just now are kept for the shopping list (owner, 2026-11-17).
            val shown = if (storeId != null && products.isNotEmpty()) {
                try {
                    matches.noteAisles(products.values, storeId)
                    matches.all()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    all
                }
            } else {
                all
            }
            _state.value = _state.value.copy(rows = shown.map { MatchRow(it, products[it.productId]) })
        }
    }

    fun forget(nameKey: String) {
        viewModelScope.launch {
            matches.forget(nameKey)
            _state.value = _state.value.copy(rows = _state.value.rows?.filterNot { it.match.nameKey == nameKey })
        }
    }

    fun openChange(nameKey: String) {
        _state.value = _state.value.copy(change = MatchChange(nameKey, nameKey))
        search(nameKey)
    }

    fun closeChange() {
        _state.value = _state.value.copy(change = null)
    }

    fun search(term: String) {
        val change = _state.value.change ?: return
        _state.value = _state.value.copy(change = change.copy(term = term, searching = true, error = null))
        viewModelScope.launch {
            val next = try {
                val found = withContext(io) { controls.search(term) }
                change.copy(term = term, results = KrogerRanking.rank(term, found, _state.value.modality), searching = false)
            } catch (e: IOException) {
                change.copy(term = term, searching = false, error = e.message)
            } catch (e: IllegalArgumentException) {
                change.copy(term = term, searching = false, error = e.message)
            }
            if (_state.value.change?.nameKey == change.nameKey) _state.value = _state.value.copy(change = next)
        }
    }

    fun choose(product: KrogerProduct) {
        val change = _state.value.change ?: return
        val old = _state.value.rows?.firstOrNull { it.match.nameKey == change.nameKey }?.match ?: return
        viewModelScope.launch {
            val storeId = controls.status.value.store?.locationId
            val updated = old.copy(
                productId = product.productId, upc = product.upc, aisle = product.aisle, aisleStoreId = storeId.takeIf { product.aisle != null },
            )
            matches.keep(listOf(updated))
            _state.value = _state.value.copy(
                change = null,
                rows = _state.value.rows?.map { if (it.match.nameKey == change.nameKey) MatchRow(updated, product) else it },
            )
        }
    }
}

class MatchesActions(
    val forget: (String) -> Unit = {},
    val openChange: (String) -> Unit = {},
    val closeChange: () -> Unit = {},
    val search: (String) -> Unit = {},
    val choose: (KrogerProduct) -> Unit = {},
    val back: () -> Unit = {},
)

const val MATCHES_TITLE = "Remembered products"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KrogerMatchesScreen(state: MatchesState, actions: MatchesActions) {
    PlatformBackHandler { if (state.change != null) actions.closeChange() else actions.back() }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(MATCHES_TITLE) }, navigationIcon = { TextButton(onClick = actions.back) { Text("Back") } })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val rows = state.rows
            when {
                rows == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item(key = "about") { Text(MATCHES_ABOUT, color = MealColors.Muted, modifier = Modifier.padding(bottom = 8.dp)) }
                    state.error?.let { e -> item(key = "error") { AttentionBanner(e) } }
                    if (rows.isEmpty()) item(key = "none") { Text(NO_MATCHES, modifier = Modifier.padding(vertical = 16.dp)) }
                    items(rows, key = { it.match.nameKey }) { row ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                KrogerProductImage(row.product?.imageUrl, size = PICK_IMAGE_SIZE, modifier = Modifier.padding(end = 12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(row.match.nameKey, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        row.product?.let { "${row.match.quantity} \u00D7 ${it.description} \u00B7 ${priceText(it)}" }
                                            ?: "${row.match.quantity} \u00D7 product ${row.match.productId}",
                                        color = MealColors.Muted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    row.match.aisle?.let { Text(it, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium) }
                                }
                                TextButton(onClick = { actions.openChange(row.match.nameKey) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
                                TextButton(onClick = { actions.forget(row.match.nameKey) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Forget", color = MealColors.Danger)
                                }
                            }
                            HorizontalDivider(color = MealColors.LineSoft)
                        }
                    }
                }
            }
        }
    }
    state.change?.let { change -> ChangeDialog(change, state.modality, actions) }
}

@Composable
private fun ChangeDialog(change: MatchChange, modality: KrogerModality, actions: MatchesActions) {
    var term by rememberSaveable(change.nameKey) { mutableStateOf(change.term) }
    AlertDialog(
        onDismissRequest = actions.closeChange,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text("Product for ${change.nameKey}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = term, onValueChange = { term = it }, label = { Text("Search Kroger") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { actions.search(term) }, enabled = !change.searching) { Text("Search") }
                }
                change.error?.let { AttentionBanner(it) }
                if (change.searching) CircularProgressIndicator()
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(change.results, key = { it.productId }) { p -> ProductRow(p, modality, selected = false) { actions.choose(p) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = actions.closeChange) { Text("Cancel") } },
    )
}

const val MATCHES_ABOUT =
    "The Kroger product picked for each shopping list item, used the next time you send it. Only the product's Kroger number and its aisle at your store are kept; its name, picture and price are looked up each time."
const val NO_MATCHES = "Nothing yet. Products you add to your Kroger cart from the shopping list are remembered here."
