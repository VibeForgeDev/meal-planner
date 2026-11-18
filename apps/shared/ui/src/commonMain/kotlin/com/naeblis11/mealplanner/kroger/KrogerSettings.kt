package com.naeblis11.mealplanner.kroger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings' Kroger panel: Kroger's state on this device and what the panel is doing. */
data class KrogerSettingsState(
    val status: KrogerStatus,
    val signingIn: Boolean = false,
    /** The consent page's address while a sign-in waits, for when no browser opened. */
    val signInUrl: String? = null,
    val stores: List<KrogerStore>? = null,
    val findingStores: Boolean = false,
    val error: String? = null,
)

private data class PanelWork(
    val signingIn: Boolean = false,
    val signInUrl: String? = null,
    val stores: List<KrogerStore>? = null,
    val findingStores: Boolean = false,
    val error: String? = null,
)

class KrogerSettingsViewModel(
    private val controls: KrogerControls,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val work = MutableStateFlow(PanelWork())
    val state: StateFlow<KrogerSettingsState> = combine(controls.status, work) { status, w ->
        KrogerSettingsState(status, w.signingIn, w.signInUrl, w.stores, w.findingStores, w.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, KrogerSettingsState(controls.status.value))

    private var signInJob: Job? = null

    private fun run(update: (PanelWork) -> PanelWork = { it }, block: suspend () -> Unit) {
        work.value = update(work.value).copy(error = null)
        viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                work.value = work.value.copy(error = e.message ?: KrogerMessages.OFFLINE)
            } catch (e: IllegalArgumentException) {
                work.value = work.value.copy(error = e.message)
            }
        }
    }

    fun signIn() {
        if (work.value.signingIn) return
        work.value = work.value.copy(signingIn = true, error = null, signInUrl = null)
        signInJob = viewModelScope.launch {
            try {
                withContext(io) { controls.signIn { url -> work.value = work.value.copy(signInUrl = url) } }
            } catch (e: IOException) {
                work.value = work.value.copy(error = e.message ?: KrogerMessages.SIGN_IN_FAILED)
            } finally {
                work.value = work.value.copy(signingIn = false, signInUrl = null)
            }
        }
    }

    fun cancelSignIn() = controls.cancelSignIn()

    fun signOut() = run { withContext(io) { controls.signOut() } }

    fun findStores(zip: String) = run({ it.copy(findingStores = true, stores = null) }) {
        try {
            val found = withContext(io) { controls.stores(zip) }
            work.value = work.value.copy(stores = found, error = if (found.isEmpty()) NO_STORES else null)
        } finally {
            work.value = work.value.copy(findingStores = false)
        }
    }

    fun chooseStore(store: KrogerStore) = run({ it.copy(stores = null) }) { withContext(io) { controls.chooseStore(store) } }

    fun changeStore() {
        work.value = work.value.copy(stores = emptyList(), error = null)
    }

    fun setModality(modality: KrogerModality) = run { withContext(io) { controls.setModality(modality) } }

    fun useClient(id: String, secret: String, certification: Boolean) = run { withContext(io) { controls.useClient(id, secret, certification) } }

    fun forgetClient() = run { withContext(io) { controls.forgetClient() } }

    /** Leaving Settings cancels a sign-in still waiting on the browser. */
    fun left() {
        if (work.value.signingIn) controls.cancelSignIn()
    }

    override fun onCleared() {
        left()
    }

    companion object {
        const val NO_STORES = "Kroger has no store near that ZIP code."
    }
}

class KrogerSettingsActions(
    val signIn: () -> Unit = {},
    val cancelSignIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val findStores: (String) -> Unit = {},
    val chooseStore: (KrogerStore) -> Unit = {},
    val changeStore: () -> Unit = {},
    val setModality: (KrogerModality) -> Unit = {},
    val useClient: (id: String, secret: String, certification: Boolean) -> Unit = { _, _, _ -> },
    val forgetClient: () -> Unit = {},
    val openMatches: () -> Unit = {},
)

const val KROGER_PANEL = "Kroger"

/** Settings' Kroger panel (plan 2026-11-17): the sign-in, the store, pickup or delivery, the matches, the client. */
@Composable
fun KrogerSetting(state: KrogerSettingsState, actions: KrogerSettingsActions) {
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(KROGER_ABOUT)
        state.error?.let { AttentionBanner(it) }
        if (!status.clientReady) {
            Text(KrogerMessages.NO_CLIENT)
        } else if (state.signingIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Finish signing in to Kroger in your browser.")
            }
            state.signInUrl?.let { Text("If no browser opened, go to: $it", color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = actions.cancelSignIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        } else if (status.certification) {
            Text(KrogerMessages.CERTIFICATION_NO_CART, color = MealColors.Muted)
        } else if (status.signedIn) {
            Text("Signed in to Kroger. Items you send go to that account's cart.")
            OutlinedButton(onClick = actions.signOut, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out of Kroger") }
        } else {
            Button(onClick = actions.signIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign in to Kroger") }
        }
        if (status.clientReady) {
            StorePart(state, actions)
            Text("How you get your order", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (m in KrogerModality.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.heightIn(min = 48.dp).selectable(selected = status.modality == m, role = Role.RadioButton) { actions.setModality(m) },
                    ) {
                        RadioButton(selected = status.modality == m, onClick = null)
                        Text(m.label, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            OutlinedButton(onClick = actions.openMatches, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remembered products") }
        }
        ClientPart(status, actions)
    }
}

@Composable
private fun StorePart(state: KrogerSettingsState, actions: KrogerSettingsActions) {
    var zip by rememberSaveable { mutableStateOf("") }
    val store = state.status.store
    Text("Your store", style = MaterialTheme.typography.titleMedium)
    if (store != null && state.stores == null) {
        Text(store.name)
        if (store.address.isNotEmpty()) Text(store.address, color = MealColors.Muted)
        TextButton(onClick = actions.changeStore, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change store") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = zip,
            onValueChange = { zip = it.filter { c -> c in '0'..'9' }.take(5) },
            label = { Text("ZIP code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { actions.findStores(zip) }, enabled = !state.findingStores, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.findingStores) "Finding..." else "Find stores")
        }
    }
    state.stores?.forEach { s ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = s.locationId == store?.locationId, role = Role.RadioButton) { actions.chooseStore(s) },
        ) {
            RadioButton(selected = s.locationId == store?.locationId, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(s.name)
                if (s.address.isNotEmpty()) Text(s.address, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ClientPart(status: KrogerStatus, actions: KrogerSettingsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var certification by rememberSaveable { mutableStateOf(false) }
    status.customClientId?.let {
        Text("Using your own Kroger client: $it" + if (status.certification) " (test)" else "", color = MealColors.Muted)
        TextButton(onClick = actions.forgetClient, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use the built-in client") }
        return
    }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use your own Kroger client") }
        return
    }
    Text(OWN_CLIENT_NOTE, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = secret, onValueChange = { secret = it }, label = { Text("Client secret") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).toggleable(value = certification, role = Role.Checkbox) { certification = it },
    ) {
        Checkbox(checked = certification, onCheckedChange = null)
        Text("It's a test (certification) client", modifier = Modifier.padding(start = 8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.useClient(id, secret, certification); secret = ""; open = false }, enabled = id.isNotBlank() && secret.isNotBlank()) { Text("Use this client") }
        TextButton(onClick = { open = false; secret = "" }) { Text("Cancel") }
    }
}

@Composable
private fun <T> remember(calculation: () -> T): T = androidx.compose.runtime.remember(calculation)

const val KROGER_ABOUT =
    "Check Kroger prices for your shopping list and add items to your Kroger cart. Meal Planner never sees your Kroger password, and keeps no prices."
const val OWN_CLIENT_NOTE =
    "For when the built-in client stops working, or to test: register an application at developer.kroger.com with the scopes product.compact and cart.basic:write, then paste its client ID and secret here. A test (certification) client finds stores and prices only; the sign-in and the cart need a production one."
