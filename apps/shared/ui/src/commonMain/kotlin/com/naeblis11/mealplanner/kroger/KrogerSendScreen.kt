package com.naeblis11.mealplanner.kroger

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import com.naeblis11.mealplanner.domain.krogerPrice
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.LocalLeaveGuard
import com.naeblis11.mealplanner.ui.PlatformBackHandler
import com.naeblis11.mealplanner.ui.SectionLabel
import com.naeblis11.mealplanner.ui.theme.MealColors

/** What the review's buttons do; the screen itself is stateless. */
class SendActions(
    val search: (String) -> Unit = {},
    val more: () -> Unit = {},
    val pick: (KrogerProduct) -> Unit = {},
    val setQuantity: (Int) -> Unit = {},
    val setShowUnavailable: (Boolean) -> Unit = {},
    val change: () -> Unit = {},
    val next: () -> Unit = {},
    val skip: () -> Unit = {},
    val back: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val setIncludePantry: (Boolean) -> Unit = {},
    val addToCart: () -> Unit = {},
    val checkOff: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val close: () -> Unit = {},
)

const val SEND_TITLE = "Send to Kroger"
const val LEAVE_SEND_TITLE = "Leave Send to Kroger?"
const val LEAVE_SEND_TEXT = "What you picked for this send is dropped. Nothing has gone to your Kroger cart yet."

/**
 * Send to Kroger (plan 2026-11-17): one shopping item at a time against the store's products, a summary, then the one
 * Add to cart. While picks could be lost, a switch of section (and Back) asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KrogerSendScreen(state: SendState, actions: SendActions, holdsLeave: Boolean = true) {
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val atRisk = state is SendState.Reviewing || (state is SendState.Summary && !state.adding)
    val adding = state is SendState.Summary && state.adding
    val leaveGuard = LocalLeaveGuard.current
    val busy by rememberUpdatedState(adding)
    if (leaveGuard != null && holdsLeave && atRisk) {
        DisposableEffect(leaveGuard) {
            val release = leaveGuard.hold { proceed -> if (!busy) pendingLeave = proceed }
            onDispose { release() }
        }
    }
    val leave: () -> Unit = { if (atRisk) pendingLeave = actions.close else if (!adding) actions.close() }
    PlatformBackHandler { leave() }
    pendingLeave?.let { proceed ->
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text(LEAVE_SEND_TITLE) },
            text = { Text(LEAVE_SEND_TEXT) },
            confirmButton = { TextButton(onClick = { pendingLeave = null; proceed() }) { Text("Leave", color = MealColors.Danger) } },
            dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("Keep going") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SEND_TITLE) },
                navigationIcon = { TextButton(onClick = leave, enabled = !adding) { Text("Close") } },
            )
        },
        bottomBar = { BottomButtons(state, actions) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                when (state) {
                    SendState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is SendState.NotReady -> NotReady(state, actions)
                    is SendState.Reviewing -> Review(state, actions)
                    is SendState.Summary -> Summary(state, actions)
                    is SendState.Done -> Done(state, actions)
                }
            }
        }
    }
}

@Composable
private fun BottomButtons(state: SendState, actions: SendActions) {
    when (state) {
        is SendState.Reviewing -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.back, enabled = state.index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Back") }
            OutlinedButton(onClick = actions.skip, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Skip") }
            Button(onClick = actions.next, enabled = state.current.pick != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (state.index == state.items.lastIndex) "Review" else "Next")
            }
        }
        is SendState.Summary -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.back, enabled = !state.adding, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            Button(
                onClick = actions.addToCart,
                enabled = !state.adding && state.picked.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(if (state.adding) "Adding..." else addLabel(state.picked.size)) }
        }
        else -> Unit
    }
}

fun addLabel(count: Int): String = if (count == 1) "Add 1 item to Kroger cart" else "Add $count items to Kroger cart"

@Composable
private fun NotReady(state: SendState.NotReady, actions: SendActions) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.message)
        if (state.toSettings) Button(onClick = actions.openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Settings") }
        if (state.offerPantry) OutlinedButton(onClick = { actions.setIncludePantry(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Include pantry items") }
    }
}

@Composable
private fun Review(state: SendState.Reviewing, actions: SendActions) {
    val item = state.current
    var term by rememberSaveable(item.nameKey) { mutableStateOf(item.term) }
    val shown = item.shown(state.modality)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Item ${state.index + 1} of ${state.items.size}", color = MealColors.Muted)
                Text(item.label, style = MaterialTheme.typography.headlineSmall)
                if (state.pantryCount > 0) {
                    LabeledSwitch("Include pantry items (${state.pantryCount})", state.includePantry, actions.setIncludePantry)
                }
            }
        }
        if (item.rememberedGone) item(key = "gone") { AttentionBanner(REMEMBERED_GONE) }
        item(key = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        label = { Text("Search Kroger") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { actions.search(term) }),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { actions.search(term) }, enabled = !item.searching, modifier = Modifier.heightIn(min = 48.dp)) { Text("Search") }
                }
                LabeledSwitch("Show unavailable", item.showUnavailable, actions.setShowUnavailable)
            }
        }
        item.error?.let { e -> item(key = "error") { AttentionBanner(e) } }
        if (item.searching && item.results.isEmpty()) item(key = "searching") { CircularProgressIndicator(Modifier.padding(16.dp)) }
        if (item.searched && !item.searching && shown.isEmpty() && item.error == null) {
            item(key = "none") { Text(if (item.results.isEmpty()) NO_RESULTS else NONE_AVAILABLE, color = MealColors.Muted) }
        }
        if (!item.searched && item.pick != null) {
            item(key = "remembered") { Text(REMEMBERED_NOTE, color = MealColors.Muted) }
        }
        items(shown, key = { "p-${it.productId}" }) { product ->
            ProductRow(
                product = product,
                modality = state.modality,
                selected = item.pick?.productId == product.productId,
                lastTime = product.productId == item.remembered?.productId,
                onPick = { actions.pick(product) },
            )
        }
        if (!item.searched && item.pick != null) {
            item(key = "change") { OutlinedButton(onClick = actions.change, modifier = Modifier.heightIn(min = 48.dp)) { Text("See other products") } }
        }
        if (item.more && item.searched) {
            item(key = "more") { TextButton(onClick = actions.more, enabled = !item.searching, modifier = Modifier.heightIn(min = 48.dp)) { Text("More") } }
        }
        if (item.pick != null) item(key = "qty") { QuantityStepper(item.quantity, actions.setQuantity) }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How many", modifier = Modifier.padding(end = 8.dp))
        OutlinedButton(
            onClick = { onChange(quantity - 1) },
            enabled = quantity > 1,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "One fewer" },
        ) { Text("\u2212") }
        Text("$quantity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Quantity $quantity" })
        OutlinedButton(
            onClick = { onChange(quantity + 1) },
            enabled = quantity < KrogerApi.MAX_QUANTITY,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "One more" },
        ) { Text("+") }
    }
}

/** A product as Kroger returned it: its description, brand and size, its price (and sale price), its stock. */
@Composable
fun ProductRow(product: KrogerProduct, modality: KrogerModality, selected: Boolean, lastTime: Boolean = false, onPick: () -> Unit) {
    val available = product.availableFor(modality)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MealColors.AccentTint else MealColors.Paper),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MealColors.Accent else MealColors.Line),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onPick),
    ) {
        Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            KrogerProductImage(product.imageUrl, modifier = Modifier.padding(start = 4.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(product.description, color = if (available) MealColors.Ink else MealColors.Muted)
                listOfNotNull(product.brand, product.size).joinToString(" \u00B7 ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Text(priceText(product), fontWeight = FontWeight.SemiBold, color = if (product.onSale) MealColors.AccentHover else MealColors.Ink)
                availabilityText(product, modality, lastTime)?.let { Text(it, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

fun priceText(product: KrogerProduct): String {
    val regular = product.regular
    val promo = product.promo
    return when {
        product.onSale && regular != null && promo != null -> "${krogerPrice(promo)} on sale (regular ${krogerPrice(regular)})"
        regular != null -> krogerPrice(regular)
        promo != null -> krogerPrice(promo)
        else -> NO_PRICE
    }
}

private fun availabilityText(product: KrogerProduct, modality: KrogerModality, lastTime: Boolean): String? {
    val parts = mutableListOf<String>()
    if (lastTime) parts += "Last time"
    if (!product.availableFor(modality)) {
        parts += if (product.stock == com.naeblis11.mealplanner.domain.KrogerStock.OUT) "Out of stock" else "Not available for ${modality.label.lowercase()}"
    } else {
        product.stock?.let { parts += it.label }
    }
    product.aisle?.let { parts += it }
    return parts.joinToString(" \u00B7 ").ifEmpty { null }
}

@Composable
private fun Summary(state: SendState.Summary, actions: SendActions) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            Text("To your Kroger cart for ${state.modality.label.lowercase()}", style = MaterialTheme.typography.headlineSmall)
        }
        state.error?.let { e -> item(key = "error") { AttentionBanner(e) } }
        if (state.picked.isEmpty()) item(key = "nothing") { Text("Nothing is picked. Go back to pick a product for an item.", color = MealColors.Muted) }
        items(state.picked, key = { "picked-${it.nameKey}" }) { item ->
            val pick = item.pick!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                KrogerProductImage(pick.imageUrl, size = PICK_IMAGE_SIZE, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${item.quantity} \u00D7 ${pick.description}")
                    Text("For ${item.label} \u00B7 ${priceText(pick)}", color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { actions.edit(item.nameKey) }, enabled = !state.adding, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
            }
        }
        if (state.skipped.isNotEmpty()) {
            item(key = "skipped-label") { SectionLabel("Not sent") }
            items(state.skipped, key = { "skipped-${it.nameKey}" }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, color = MealColors.Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.edit(item.nameKey) }, enabled = !state.adding, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pick") }
                }
            }
        }
    }
}

@Composable
private fun Done(state: SendState.Done, actions: SendActions) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.added.size == 1) "Added 1 item to your Kroger cart." else "Added ${state.added.size} items to your Kroger cart.", style = MaterialTheme.typography.headlineSmall)
                Text(CHECKOUT_NOTE, color = MealColors.Muted)
            }
        }
        items(state.added, key = { "added-${it.nameKey}" }) { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                KrogerProductImage(item.pick!!.imageUrl, size = PICK_IMAGE_SIZE, modifier = Modifier.padding(end = 12.dp))
                Text("${item.quantity} \u00D7 ${item.pick.description}")
            }
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (state.checkedOff) {
                    Text("Checked off the shopping list.", color = MealColors.AccentHover, modifier = Modifier.align(Alignment.CenterVertically))
                } else {
                    Button(onClick = actions.checkOff, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check them off the list") }
                }
                OutlinedButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
        }
    }
}

const val NO_PRICE = "No price at this store"
const val NO_RESULTS = "Kroger found nothing for that. Try other words."
const val NONE_AVAILABLE = "Nothing found is available at your store. Turn on Show unavailable to see it anyway."
const val REMEMBERED_GONE = "The product you picked last time isn't available at your store now. Pick another."
const val REMEMBERED_NOTE = "The product you picked last time:"
const val CHECKOUT_NOTE = "Check out on kroger.com or in the Kroger app. Meal Planner can only add to the cart, never remove from it."
