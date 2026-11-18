package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.update.UpdatePhase

/** What Settings' Updates section can ask for (UpdateViewModel); nothing by default. */
class UpdateActions(
    val checkNow: () -> Unit = {},
    val setAutomatic: (Boolean) -> Unit = {},
    val install: () -> Unit = {},
    val confirmInstall: () -> Unit = {},
    val cancelInstall: () -> Unit = {},
    val openInstallPermission: () -> Unit = {},
)

/**
 * Settings' Updates section (spec "Updates"): why this copy doesn't check, or the Automatically switch, when it last
 * looked, what it found (Install), what it is doing, Android's permission when it is missing, a failed check (quietly)
 * or a failed install (flagged), and Check for updates. On the PC, Install asks first ([UpdateUiState.confirming]).
 */
@Composable
internal fun UpdateSetting(state: UpdateUiState, actions: UpdateActions) {
    val status = state.status
    if (!status.offered) {
        Text(status.unavailableReason.orEmpty(), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val idle = status.phase == UpdatePhase.IDLE
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = status.automatic, role = Role.Switch, onValueChange = actions.setAutomatic),
    ) {
        Text(UPDATE_AUTO_LABEL, modifier = Modifier.weight(1f))
        Switch(checked = status.automatic, onCheckedChange = null)
    }
    Text(UPDATE_AUTO_HELP, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    Text(state.lastCheckedText?.let(::lastChecked) ?: UPDATE_NOT_CHECKED, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    val offer = status.offer
    when {
        status.phase == UpdatePhase.CHECKING -> Busy(UPDATE_CHECKING)
        status.phase == UpdatePhase.DOWNLOADING -> Busy(downloading(status.downloaded, offer?.size ?: 0))
        status.phase == UpdatePhase.INSTALLING -> Busy(UPDATE_STARTING_INSTALLER)
        offer != null -> {
            val title = if (status.waitingToInstall) readyToInstall(offer.version) else updateAvailable(offer.version)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = actions.install, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(INSTALL_UPDATE) }
        }
        status.upToDate -> Text(UP_TO_DATE)
    }
    if (status.needsPermission) {
        AttentionBanner(ALLOW_INSTALLS)
        OutlinedButton(onClick = actions.openInstallPermission, modifier = Modifier.heightIn(min = 48.dp)) { Text(OPEN_INSTALL_SETTINGS) }
    }
    status.problem?.let { AttentionBanner(it) }
    status.message?.let { Text(it, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(onClick = actions.checkNow, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(CHECK_FOR_UPDATES) }
    if (state.confirming && offer != null) {
        AlertDialog(
            onDismissRequest = actions.cancelInstall,
            title = { Text(installTitle(offer.version)) },
            text = { Text(INSTALL_CLOSES_APP) },
            confirmButton = { TextButton(onClick = actions.confirmInstall) { Text(INSTALL_AND_CLOSE) } },
            dismissButton = { TextButton(onClick = actions.cancelInstall) { Text(UPDATE_NOT_NOW) } },
        )
    }
}

@Composable
private fun Busy(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text)
    }
}

const val UPDATES_PANEL = "Updates"
const val UPDATE_AUTO_LABEL = "Check for updates automatically"
const val UPDATE_AUTO_HELP = "When Meal Planner starts, at most once a day. Installing is always your choice."
const val UPDATE_NOT_CHECKED = "Not checked yet."
const val CHECK_FOR_UPDATES = "Check for updates"
const val UPDATE_CHECKING = "Checking for updates..."
const val UP_TO_DATE = "Meal Planner is up to date."
const val INSTALL_UPDATE = "Install"
const val UPDATE_STARTING_INSTALLER = "Starting the installer..."
const val ALLOW_INSTALLS =
    "Android asks you to allow Meal Planner to install its updates once. Open settings, turn on Allow from this source, " +
        "come back and press Install again."
const val OPEN_INSTALL_SETTINGS = "Open settings"
const val INSTALL_CLOSES_APP =
    "Meal Planner closes so the installer can replace it. Your recipes, meal plans and settings stay. When the installer " +
        "has finished, open Meal Planner from the Start menu."
const val INSTALL_AND_CLOSE = "Install and close"
const val UPDATE_NOT_NOW = "Not now"
const val SEE_UPDATE = "See update"
const val ABOUT_PHONE_DATA =
    "Everything you keep in Meal Planner stays on this phone, and on your PC once you join one. It goes online only to " +
        "ask GitHub whether there is a newer Meal Planner and, when you choose Install, to download it, and when you press " +
        "Sync, to reach the PC you joined on your home network, and when you use Kroger, to look up Kroger prices and " +
        "add what you pick to your Kroger cart; it sends nothing about you anywhere else."

fun lastChecked(text: String): String = "Last checked $text."

fun updateAvailable(version: String): String = "Meal Planner $version is available."

/**
 * A verified download that couldn't start its installer yet. Where Install closes the app (the PC) that is because a
 * form or an import review has something unsaved (P8-F1), so the launch notice says what to do first ([closesApp]);
 * Settings, reached only once that page was left, says just the first sentence.
 */
fun readyToInstall(version: String, closesApp: Boolean = false): String =
    "Meal Planner $version is ready to install." + if (closesApp) " $SAVE_THEN_INSTALL" else ""

const val SAVE_THEN_INSTALL = "Save or leave what you're editing, then Install."

fun installTitle(version: String): String = "Install Meal Planner $version?"

fun downloading(done: Long, total: Long): String =
    if (total <= 0) "Downloading..." else "Downloading... ${(done * 100 / total).coerceIn(0, 100)}%"
