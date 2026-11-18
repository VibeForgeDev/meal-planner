package com.naeblis11.mealplanner.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.domain.ColumnSplit
import com.naeblis11.mealplanner.domain.KrogerQuery
import com.naeblis11.mealplanner.ui.AisleField
import com.naeblis11.mealplanner.ui.AisleHeader
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.CardColumns
import com.naeblis11.mealplanner.ui.MealCard
import com.naeblis11.mealplanner.ui.SectionLabel
import com.naeblis11.mealplanner.ui.itemCount
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.ui.theme.MealSpacing
import com.naeblis11.mealplanner.ui.SyncAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    state: ShoppingState?,
    message: String?,
    error: String?,
    adding: Boolean,
    onMessageShown: (String) -> Unit,
    onCheck: (ShoppingItemEntity, Boolean) -> Unit,
    onAddThisWeek: () -> Unit,
    onAddItem: (name: String, amount: String, unit: String, aisle: String) -> Unit,
    onSetAisle: (ShoppingItemEntity, String) -> Unit,
    onRemove: (ShoppingItemEntity) -> Unit,
    onClear: () -> Unit,
    /** The wide layout (P3-R4): the aisles as cards in two or three columns. */
    wide: Boolean = false,
    /** Send to Kroger (plan 2026-11-17); null hides it (no Kroger client here). */
    onSendToKroger: (() -> Unit)? = null,
    /** The Kroger aisle of each item with a remembered product, by KrogerQuery.nameKey (owner, 2026-11-17). */
    krogerAisles: Map<String, String> = emptyMap(),
) = CompositionLocalProvider(LocalKrogerAisles provides krogerAisles) {
    ShoppingScreenBody(
        state, message, error, adding, onMessageShown, onCheck, onAddThisWeek, onAddItem, onSetAisle, onRemove, onClear, wide, onSendToKroger,
    )
}

// The rows read the aisles from here, so the four places that draw a row need no extra parameter.
private val LocalKrogerAisles = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** "Kroger: Aisle 12" under a row whose item has a remembered Kroger product with an aisle. */
fun krogerAisleText(aisle: String): String = "Kroger: $aisle"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingScreenBody(
    state: ShoppingState?,
    message: String?,
    error: String?,
    adding: Boolean,
    onMessageShown: (String) -> Unit,
    onCheck: (ShoppingItemEntity, Boolean) -> Unit,
    onAddThisWeek: () -> Unit,
    onAddItem: (name: String, amount: String, unit: String, aisle: String) -> Unit,
    onSetAisle: (ShoppingItemEntity, String) -> Unit,
    onRemove: (ShoppingItemEntity) -> Unit,
    onClear: () -> Unit,
    wide: Boolean,
    onSendToKroger: (() -> Unit)?,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            // Consumed even if the user leaves while it shows (so it is not shown again on return),
            // and only this message: a newer one that arrived meanwhile still gets its turn.
            try {
                snackbar.showSnackbar(message)
            } finally {
                onMessageShown(message)
            }
        }
    }
    val listState = rememberLazyListState()
    // The error sits in the first item: bring it into view when an action further down fails.
    LaunchedEffect(error, wide) { if (error != null && !wide) listState.animateScrollToItem(0) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Shopping list", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    SyncAction()
                    if (onSendToKroger != null) TextButton(onClick = onSendToKroger) { Text(SEND_TO_KROGER) }
                    TextButton(onClick = { addOpen = true }) { Text("Add item") }
                },
            )
        },
    ) { padding ->
        if (wide) {
            ShoppingColumns(
                state = state,
                error = error,
                adding = adding,
                onCheck = onCheck,
                onAddThisWeek = onAddThisWeek,
                onEdit = { editingId = it },
                onClear = { confirmClear = true },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "top") { ShoppingIntro(error, adding, onAddThisWeek, Modifier.fillMaxWidth()) }
            when {
                state == null -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
                state.isEmpty -> item(key = "empty") { ShoppingEmpty() }
                else -> {
                    item(key = "need-label") { NeedToBuyLabel(state.needToBuy.isEmpty()) }
                    for (group in state.needToBuy) {
                        item(key = "aisle-${group.aisle}") { AisleHeader(group.aisle, group.items.size) }
                        items(group.items, key = { "item-${it.id}" }) { item ->
                            ShoppingRow(item, shoppingLabel(item), onCheck) { editingId = item.id }
                        }
                    }
                    item(key = "have-label") { AlreadyHaveLabel(state.alreadyHave.isEmpty()) }
                    items(state.alreadyHave, key = { "item-${it.id}" }) { item ->
                        ShoppingRow(item, item.name, onCheck) { editingId = item.id }
                    }
                    item(key = "clear") { ClearListButton { confirmClear = true } }
                }
            }
        }
    }

    if (addOpen) {
        AddItemDialog(onDismiss = { addOpen = false }, onAdd = { n, a, u, ai -> onAddItem(n, a, u, ai); addOpen = false })
    }
    val editing = state?.let { s -> (s.needToBuy.flatMap { it.items } + s.alreadyHave).firstOrNull { it.id == editingId } }
    if (editing != null) {
        ShoppingItemDialog(
            item = editing,
            onDismiss = { editingId = null },
            onSaveAisle = { onSetAisle(editing, it); editingId = null },
            onRemove = { onRemove(editing); editingId = null },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear shopping list?") },
            text = { Text("Everything on it is removed. You can add a week's meals back from the meal plan at any time.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Yes, clear it", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep it") } },
        )
    }
}

/** The shopping list's button for Send to Kroger: short, as the phone's top bar is narrow. */
const val SEND_TO_KROGER = "Kroger"

/** The Pi's row text: "2 cup Flour", the unit only with an amount. */
fun shoppingLabel(item: ShoppingItemEntity): String =
    if (item.amount == null) item.name else listOfNotNull(item.amount, item.unit, item.name).joinToString(" ")

/** From this content width up, the wide shopping list has three columns instead of two. */
private val THREE_COLUMNS_MIN_WIDTH: Dp = 1100.dp

/** The top of the list, on the phone and the wide screen alike: what it is, any error, and "Add this week's meals". */
@Composable
private fun ShoppingIntro(error: String?, adding: Boolean, onAddThisWeek: () -> Unit, buttonModifier: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Crossed against what's already in your pantry.", color = MealColors.Muted)
        if (error != null) AttentionBanner(error)
        Button(
            onClick = onAddThisWeek,
            enabled = !adding,
            shape = RoundedCornerShape(10.dp),
            modifier = buttonModifier.heightIn(min = 48.dp),
        ) { Text("Add this week's meals") }
    }
}

@Composable
private fun ShoppingEmpty() {
    Text(
        "No shopping list yet \u2014 add this week's meals, or add an item.",
        color = MealColors.Muted,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

/** "Need to buy", with the note that there is nothing when [empty]. */
@Composable
private fun NeedToBuyLabel(empty: Boolean) {
    Column {
        SectionLabel("Need to buy")
        if (empty) Text("Nothing to buy \u2014 everything's in your pantry.", color = MealColors.Muted)
    }
}

/** "Already in My Kitchen", with the note that nothing is when [empty]. */
@Composable
private fun AlreadyHaveLabel(empty: Boolean) {
    Column {
        SectionLabel("Already in My Kitchen")
        if (empty) Text("None of this week's ingredients are in your pantry yet.", color = MealColors.Muted)
    }
}

/** At the bottom, away from where the thumb rests while ticking. */
@Composable
private fun ClearListButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 24.dp)) {
        Text("Clear shopping list", color = MealColors.Danger)
    }
}

/** The wide shopping list: the same parts as the phone's, with the aisles as cards in columns, in store order. */
@Composable
private fun ShoppingColumns(
    state: ShoppingState?,
    error: String?,
    adding: Boolean,
    onCheck: (ShoppingItemEntity, Boolean) -> Unit,
    onAddThisWeek: () -> Unit,
    onEdit: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    // The error sits at the top: bring it into view when an action further down fails.
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth - MealSpacing.Band * 2 >= THREE_COLUMNS_MIN_WIDTH) 3 else 2
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(start = MealSpacing.Band, end = MealSpacing.Band, bottom = MealSpacing.Band),
            verticalArrangement = Arrangement.spacedBy(MealSpacing.Row),
        ) {
            ShoppingIntro(error, adding, onAddThisWeek, Modifier)
            when {
                state == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.isEmpty -> ShoppingEmpty()
                else -> {
                    NeedToBuyLabel(state.needToBuy.isEmpty())
                    // A card's height is about its rows plus two for the header.
                    CardColumns(ColumnSplit.contiguous(state.needToBuy, columns) { it.items.size + 2 }, columns) { group ->
                        MealCard(group.aisle, itemCount(group.items.size)) {
                            for (item in group.items) ShoppingRow(item, shoppingLabel(item), onCheck) { onEdit(item.id) }
                        }
                    }
                    AlreadyHaveLabel(state.alreadyHave.isEmpty())
                    CardColumns(ColumnSplit.contiguous(state.alreadyHave, columns) { 1 }, columns) { item ->
                        ShoppingRow(item, item.name, onCheck) { onEdit(item.id) }
                    }
                    ClearListButton(onClear)
                }
            }
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItemEntity, label: String, onCheck: (ShoppingItemEntity, Boolean) -> Unit, onEdit: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // The whole row is the check box: a big target for one thumb in a store.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .toggleable(value = item.checked, role = Role.Checkbox, onValueChange = { onCheck(item, it) }),
            ) {
                Checkbox(checked = item.checked, onCheckedChange = null)
                val krogerAisle = LocalKrogerAisles.current.takeIf { it.isNotEmpty() }?.get(KrogerQuery.nameKey(item.name))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                        color = if (item.checked) MealColors.Muted else MealColors.Ink,
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    )
                    krogerAisle?.let { Text(krogerAisleText(it), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Edit ${item.name}" }) {
                Text("Edit")
            }
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var aisle by rememberSaveable { mutableStateOf("") }
    // Checked on Add, and kept open until it passes, so a typo doesn't lose what was typed.
    var tried by rememberSaveable { mutableStateOf(false) }
    val nameError = if (tried) nameProblem(name) else null
    val amountError = if (tried) amountProblem(amount) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text("Add an item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Item") }, singleLine = true, isError = nameError != null, supportingText = nameError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true, isError = amountError != null, supportingText = amountError?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                AisleField(aisle, { aisle = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (nameProblem(name) == null && amountProblem(amount) == null) onAdd(name, amount, unit, aisle)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShoppingItemDialog(item: ShoppingItemEntity, onDismiss: () -> Unit, onSaveAisle: (String) -> Unit, onRemove: () -> Unit) {
    var aisle by rememberSaveable(item.id) { mutableStateOf(item.aisle ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AisleField(aisle, { aisle = it }, label = "Aisle")
                Text("The aisle is remembered for ${item.name} from now on.", color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRemove) { Text("Remove from list", color = MealColors.Danger) }
            }
        },
        confirmButton = { TextButton(onClick = { onSaveAisle(aisle) }) { Text("Save aisle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
